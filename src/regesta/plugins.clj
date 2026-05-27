(ns regesta.plugins
  "Plugin protocol and registry.

   A plugin is a plain Clojure map (ADR 0007) carrying any combination
   of:

   - an `:importer` (external source → IR records),
   - an `:exporter` (IR records → external output),
   - `:rules` — rule-DSL data, schema in `regesta.rules` (ADR 0002),
   - a `:mapping` — data-shaped sugar over rules (ADR 0009),
   - `:predicates` / `:transforms` — stdlib extensions (ADR 0010),
   - declarations (`:requires`, `:input-format`, `:output-format`).

   Only `:plugin/spec-version` and `:id` are required; every other key is
   optional. The schema is closed: unknown keys are rejected so typos
   surface immediately. New optional keys must justify themselves
   through concrete plugin needs (growth discipline per ADR 0007).

   The registry is a plain immutable map `{plugin-id → plugin-map}`.
   `register` and `unregister` return new registries; multiple registries
   coexist freely (test fixtures, isolated runs).

   This namespace covers the schema, the registry data structure, and
   the inert query helpers (lookup, filter-by-format, pooling). The
   `:requires` resolution graph and the `:input-format` dispatch logic
   live in their own namespaces (Sprint 5 M2.B)."
  (:require [clojure.set :as set]
            [malli.core :as m]
            [regesta.plugins.transforms :as tx]
            [regesta.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Plugin schema
;;
;; Closed shape: every key is named here. The `:plugin/spec-version` field
;; is literal `1` for the current major revision; future incompatible
;; changes bump it, additive minor changes do not.
;;
;; `:rules` and `:mapping` are shallow-validated (`vector of maps`). Deep
;; validation lives with the relevant compiler — the rule compiler
;; (`regesta.rules`) and the mapping compiler (Sprint 5 M4). Keeping
;; this namespace independent of those compilers avoids a load-order
;; cycle and lets a plugin be inspectable without compiling anything.
;; ---------------------------------------------------------------------------

(def Plugin
  [:map {:closed true}
   ;; identity
   [:plugin/spec-version [:= 1]]
   [:id :keyword]
   [:version {:optional true} :string]

   ;; code surface (functions; closures cannot be deeply introspected,
   ;; so the schema checks only that they are callable)
   [:importer {:optional true} fn?]
   [:exporter {:optional true} fn?]
   [:matches? {:optional true} fn?]

   ;; data surface (deep validation deferred to dedicated compilers)
   [:rules      {:optional true} [:vector :map]]
   [:mapping    {:optional true} [:vector :map]]
   [:predicates {:optional true} [:map-of :symbol fn?]]
   [:transforms {:optional true} [:map-of :keyword fn?]]

   ;; declarations
   [:requires      {:optional true} [:set :keyword]]
   [:input-format  {:optional true} :keyword]
   [:output-format {:optional true} :keyword]
   [:doc           {:optional true} :string]])

(defn valid-plugin?
  "True if `plugin` conforms to the Plugin schema (ADR 0007)."
  [plugin]
  (m/validate Plugin plugin))

(defn explain-plugin
  "Return a Malli explanation map describing why `plugin` is invalid,
   or nil if it is valid. Intended for register-time error messages
   and for plugin-authoring tooling."
  [plugin]
  (m/explain Plugin plugin))

;; ---------------------------------------------------------------------------
;; Registry
;;
;; A registry is `{plugin-id → plugin-map}`. Everything is immutable;
;; `register` and `unregister` return new registries.
;; ---------------------------------------------------------------------------

(def empty-registry
  "The empty registry: a plain `{}`. Provided as a named value so
   call-sites read declaratively."
  {})

(defn- validate-plugin-or-throw! [plugin]
  (when-let [explanation (explain-plugin plugin)]
    (throw (ex-info "Invalid plugin"
                    {:plugin-id   (:id plugin)
                     :explanation explanation}))))

(defn register
  "Register `plugin` into `registry`. Returns a new registry with the
   plugin installed under its `:id`.

   Throws if the plugin is malformed (Plugin schema violation) or if a
   plugin with the same `:id` is already present. Same-id duplicates
   are rejected rather than silently overwritten — silent overwrite
   would mask version-skew bugs (ADR 0007)."
  [registry plugin]
  (validate-plugin-or-throw! plugin)
  (let [id (:id plugin)]
    (when (contains? registry id)
      (throw (ex-info "Duplicate plugin id"
                      {:plugin-id id
                       :existing  (get registry id)})))
    (assoc registry id plugin)))

(defn unregister
  "Remove the plugin with the given `:id` from `registry`. Returns a
   new registry. No-op if the plugin is not registered."
  [registry plugin-id]
  (dissoc registry plugin-id))

(defn lookup
  "Return the plugin registered under `plugin-id`, or `nil` if absent."
  [registry plugin-id]
  (get registry plugin-id))

(defn registered-ids
  "Return the set of plugin ids currently in `registry`."
  [registry]
  (set (keys registry)))

;; ---------------------------------------------------------------------------
;; Queries
;;
;; Inert query helpers — they describe what the registry contains, they
;; do not select a single plugin for execution. Format dispatch (the
;; "exactly one eligible" rule per ADR 0007) lives in M2.B alongside
;; the `:requires` graph.
;; ---------------------------------------------------------------------------

(defn plugins-for-format
  "Return the vector of plugins whose `:input-format` equals `format`.
   Order is unspecified — callers needing deterministic order should
   sort by `:id`."
  [registry format]
  (filterv #(= format (:input-format %)) (vals registry)))

(defn importers-for
  "Return plugins that declare an `:importer`. With one arg, every
   importer plugin. With two args, every importer plugin whose
   `:input-format` matches."
  ([registry]
   (filterv :importer (vals registry)))
  ([registry format]
   (filterv (every-pred :importer #(= format (:input-format %)))
            (vals registry))))

(defn exporters-for
  "Return plugins that declare an `:exporter`. With one arg, every
   exporter plugin. With two args, every exporter plugin whose
   `:output-format` matches."
  ([registry]
   (filterv :exporter (vals registry)))
  ([registry format]
   (filterv (every-pred :exporter #(= format (:output-format %)))
            (vals registry))))

(defn all-rules
  "Concatenate the `:rules` of every plugin in `registry`. Each rule
   passes through unchanged — `regesta.rules/compile-rules` is the
   sanctioned next step."
  [registry]
  (into [] (mapcat :rules) (vals registry)))

(defn all-mappings
  "Concatenate the `:mapping` of every plugin in `registry`."
  [registry]
  (into [] (mapcat :mapping) (vals registry)))

;; ---------------------------------------------------------------------------
;; Effective stdlib (ADR 0010)
;;
;; The effective stdlib is the union of the core stdlib (shipped by the
;; runtime) and every registered plugin's `:predicates` / `:transforms`
;; contribution. Name collisions across any pair of contributors —
;; core/plugin or plugin/plugin — are rejected at this point: silent
;; first-wins resolution would make the visible stdlib depend on
;; registration order, which is the worst kind of bug.
;;
;; The core stdlib lives in `regesta.rules/predicate-stdlib` and
;; `regesta.plugins.transforms/core-transforms`. It participates in the
;; merge as a synthetic contributor with id `:core` — that name shows up
;; in collision diagnostics, telling a plugin author exactly which
;; symbol or keyword they cannot redefine.
;; ---------------------------------------------------------------------------

(def ^:private core-source :core)

(defn- merge-no-conflicts
  "Merge a sequence of `[source-id contribution-map]` pairs into one map.
   Throws ex-info naming the conflicting keys and the contributors
   responsible if any key appears in more than one contribution. `kind`
   is the human-readable noun used in the error message (\"Predicate\"
   / \"Transform\")."
  [pairs kind]
  (reduce (fn [acc [source-id contribution]]
            (let [conflicts (set/intersection (set (keys acc))
                                              (set (keys contribution)))]
              (when (seq conflicts)
                (throw (ex-info (str kind " name collision")
                                {:kind         kind
                                 :names        conflicts
                                 :incoming     source-id
                                 :already-from (into {}
                                                     (for [n conflicts]
                                                       [n (-> acc meta ::source-by-name (get n))]))})))
              (with-meta (into acc contribution)
                {::source-by-name
                 (into (or (-> acc meta ::source-by-name) {})
                       (for [k (keys contribution)] [k source-id]))})))
          (with-meta {} {::source-by-name {}})
          pairs))

(defn- plugin-contributions
  "Sequence of `[plugin-id contribution-map]` for the given top-level
   plugin key (`:predicates` or `:transforms`)."
  [registry plugin-key]
  (keep (fn [plugin]
          (when-let [m (get plugin plugin-key)]
            [(:id plugin) m]))
        (vals registry)))

(defn effective-stdlib
  "Return `{:predicates ... :transforms ...}` — the union of core
   stdlibs and every plugin's contribution. Collisions across any pair
   of contributors (core/plugin, plugin/plugin) throw an explanatory
   ex-info naming the contributors involved.

   The maps returned are plain (no metadata). The merge bookkeeping is
   internal; use `predicate-source` / `transform-source` to ask which
   contributor owns a given entry."
  [registry]
  (let [pred-contribs (cons [core-source rules/predicate-stdlib]
                            (plugin-contributions registry :predicates))
        tx-contribs   (cons [core-source tx/core-transforms]
                            (plugin-contributions registry :transforms))]
    {:predicates (with-meta (merge-no-conflicts pred-contribs "Predicate") nil)
     :transforms (with-meta (merge-no-conflicts tx-contribs "Transform")  nil)}))

(defn effective-predicates
  "Return the effective predicate stdlib as a `{symbol → fn}` map.
   Convenience accessor over `effective-stdlib`."
  [registry]
  (:predicates (effective-stdlib registry)))

(defn effective-transforms
  "Return the effective transform stdlib as a `{keyword → fn}` map.
   Convenience accessor over `effective-stdlib`."
  [registry]
  (:transforms (effective-stdlib registry)))

(defn predicate-source
  "Return the contributor of the predicate `sym` in the registry's
   effective stdlib: `:core` for a built-in, the plugin id for a
   plugin contribution, or `nil` if `sym` is not defined.

   For registries that contain colliding contributions, the answer
   reflects scan order and should not be trusted — call
   `effective-stdlib` first to surface conflicts."
  [registry sym]
  (cond
    (contains? rules/predicate-stdlib sym)
    core-source

    :else
    (some (fn [plugin]
            (when (contains? (:predicates plugin) sym)
              (:id plugin)))
          (vals registry))))

(defn transform-source
  "Return the contributor of the transform `kw` in the registry's
   effective stdlib: `:core` for a built-in, the plugin id for a
   plugin contribution, or `nil` if `kw` is not defined.

   Same trust caveat as `predicate-source`."
  [registry kw]
  (cond
    (contains? tx/core-transforms kw)
    core-source

    :else
    (some (fn [plugin]
            (when (contains? (:transforms plugin) kw)
              (:id plugin)))
          (vals registry))))

;; ---------------------------------------------------------------------------
;; :requires graph (ADR 0007)
;;
;; Registration itself is order-insensitive: `register` does not check
;; the :requires graph globally because that would force callers to
;; build dependency-ordered registrations. Instead, callers run
;; `validate-requires!` (or any function that calls it transitively,
;; like `topo-order`) once the registry is fully built. Cycles and
;; missing dependencies both surface from that one call.
;;
;; For V1, :requires is documentary — it does not isolate stdlib
;; visibility or rule pools (ADR 0007 §Consequences). It still earns
;; its keep by catching configuration mistakes early.
;; ---------------------------------------------------------------------------

(defn requires-graph
  "Return `{plugin-id → #{required-plugin-id ...}}` for `registry`.
   A plugin without `:requires` has an empty dependency set."
  [registry]
  (into {} (map (fn [[id p]] [id (set (:requires p))])) registry))

(defn- find-missing-deps
  "Set of plugin ids named in some `:requires` but not themselves
   present in `graph`."
  [graph]
  (let [registered (set (keys graph))
        required   (set (mapcat val graph))]
    (set/difference required registered)))

(defn- compute-topo-order
  "Kahn-style topological sort. Returns `[order remaining]` where
   `order` is the vector of nodes that could be linearized (in
   dependency-then-id order) and `remaining` is the set of nodes that
   could not — non-empty iff the graph contains a cycle.

   Assumes every dependency is registered (i.e. `find-missing-deps`
   has already been checked). Missing deps don't block ordering here
   because they're filtered out of the in-degree computation."
  [graph]
  (let [registered (set (keys graph))
        in-degree  (reduce-kv (fn [acc id deps]
                                (assoc acc id (count (filter registered deps))))
                              {}
                              graph)
        dependents (reduce-kv (fn [acc id deps]
                                (reduce (fn [a d]
                                          (if (contains? registered d)
                                            (update a d (fnil conj #{}) id)
                                            a))
                                        acc
                                        deps))
                              {}
                              graph)]
    (loop [in-degree in-degree
           order     []]
      (let [ready (sort (for [[id deg] in-degree :when (zero? deg)] id))]
        (if (empty? ready)
          [order (set (keys in-degree))]
          (let [n          (first ready)
                in-degree' (reduce (fn [acc dep-id]
                                     (if (contains? acc dep-id)
                                       (update acc dep-id dec)
                                       acc))
                                   (dissoc in-degree n)
                                   (get dependents n #{}))]
            (recur in-degree' (conj order n))))))))

(defn validate-requires!
  "Throw ex-info if `registry`'s `:requires` graph names a missing
   plugin or contains a cycle. Return `registry` on success.

   Self-cycles (a plugin requiring itself) and any larger cycles
   surface with the cycle's node set in the ex-data."
  [registry]
  (let [graph (requires-graph registry)
        missing (find-missing-deps graph)]
    (when (seq missing)
      (throw (ex-info "Plugin :requires references missing plugins"
                      {:missing missing}))))
  (let [[_ remaining] (compute-topo-order (requires-graph registry))]
    (when (seq remaining)
      (throw (ex-info "Cycle in plugin :requires graph"
                      {:cycle remaining}))))
  registry)

(defn topo-order
  "Validate `registry` and return its plugin ids in dependency order:
   every plugin's `:requires` appear before it. Ties break by id sort
   for determinism. Throws on missing deps or cycles."
  [registry]
  (validate-requires! registry)
  (first (compute-topo-order (requires-graph registry))))

;; ---------------------------------------------------------------------------
;; :input-format dispatch (ADR 0007)
;;
;; Resolution order:
;;   1. Explicit `:using-plugin` always wins.
;;   2. Otherwise, gather importer plugins whose `:input-format` matches.
;;   3. Filter through each candidate's `:matches?` (plugins without
;;      `:matches?` stay eligible without sniffing).
;;   4. Exactly one plugin must remain. Zero or 2+ is a hard error.
;;
;; Silent first-wins resolution is explicitly rejected by ADR 0007 —
;; ambiguous dispatch surfaces as an error naming the candidates.
;; ---------------------------------------------------------------------------

(defn- importer? [plugin] (some? (:importer plugin)))

(defn- matches-eligible?
  "Apply `:matches?` if present; eligible by default if absent."
  [plugin opts source]
  (if-let [m? (:matches? plugin)]
    (boolean (m? opts source))
    true))

(defn select-importer
  "Resolve which importer plugin to use for a given source per ADR 0007
   §Input-format dispatch.

   `ctx` is a map:

     :format        (required) input-format keyword to dispatch on
     :source                   source map passed to `:matches?`
     :opts                     opts map passed to `:matches?`
     :using-plugin             explicit plugin id; bypasses dispatch

   Returns the selected plugin. Throws ex-info if zero or two-plus
   plugins remain eligible after the `:matches?` filter, or if
   `:using-plugin` names an unknown plugin or one without an
   `:importer`."
  [registry {:keys [format source opts using-plugin]}]
  (if using-plugin
    (let [plugin (lookup registry using-plugin)]
      (cond
        (nil? plugin)
        (throw (ex-info "Explicitly-selected plugin not in registry"
                        {:using-plugin using-plugin}))
        (not (importer? plugin))
        (throw (ex-info "Explicitly-selected plugin has no importer"
                        {:using-plugin using-plugin}))
        :else plugin))
    (let [candidates (importers-for registry format)
          eligible   (filterv #(matches-eligible? % opts source) candidates)]
      (case (count eligible)
        0 (throw (ex-info "No importer plugin matched the source"
                          {:format     format
                           :candidates (mapv :id candidates)}))
        1 (first eligible)
        (throw (ex-info "Multiple importer plugins matched (ambiguous)"
                        {:format   format
                         :eligible (mapv :id eligible)}))))))
