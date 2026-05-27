(ns regesta.plugins.shape
  "Generic shape adapter — walks a parsed source tree and produces an
   IR Record per a plugin's mapping (ADR 0009). Sole V1 producer of
   fragments: the rule layer cannot mint them (ADR 0011 §Limitation),
   so the canonical normalization in M4 assumes the shape adapter has
   already laid down references and fragment-level coords in the
   native vocabulary.

   M5.A delivers the JSON walker. M5.B adds the XML walker and the
   cross-format equivalence test (ADR 0012's guarantee that the same
   logical record in either serialization produces identical fragment
   ids).

   ## JSON convention (M5.A)

   - Object keys parsed as namespaced keywords by splitting on the
     first ':' — `\"dc:title\"` → `:dc/title`. Unprefixed keys become
     unnamespaced keywords. JSON-LD `@value`, `@language`, `@id`,
     `@type` parse via `(keyword \"@value\")` etc.
   - Multiplicity: arrays at a predicate position contribute one
     occurrence per element. A bare primitive is a single occurrence.
   - Qualified values use the JSON-LD shape: the value at a qualified
     predicate is a sub-object with `\"@value\"` for the main value
     plus the key declared by the mapping's `:mapping/qualifier :from`
     for the qualifier. A bare primitive at a qualified position is
     tolerated — the fragment gets just the value coord, no qualifier
     coord, matching the XML case where the source has no attribute.
   - Predicates absent from the mapping are silently ignored. The
     shape adapter is mapping-driven.

   ## Fragment minting (M1, ADR 0012)

   Fragment ids come from `regesta.model/mint-fragment-id`. The
   locator is `[predicate idx]` where `idx` is the occurrence's
   position in document order at that predicate. Within a predicate
   the array order is preserved by construction (JSON arrays become
   Clojure vectors). Across predicates the order depends on the
   parser's map representation (`clojure.data.json` returns array-maps
   for small objects, hash-maps above ~8 entries); fragment id
   stability does not require cross-predicate order because every
   locator is rooted at its own predicate.

   ## Out of scope (V1)

   - Deep nesting (CIDOC-style multi-level fragments). Locators are
     two-segment `[predicate idx]` only; richer nesting waits for a
     concrete plugin need.
   - `@id` / `@type` extraction at the top level. The caller (M6's
     plugin wrapper) sets `:record-id` and `:kind` explicitly.
   - Diagnostic emission for shape mismatches (a flat mapping seeing
     an object value, etc.). Silent skip in V1; revisit if a plugin
     author reports confusion."
  (:require [regesta.model :as model]))

;; ---------------------------------------------------------------------------
;; Mapping inspection
;;
;; The walker is mapping-driven: it acts only on predicates the mapping
;; mentions, and consults `:mapping/qualifier` to decide which paths
;; mint fragments. These two projections are everything the walker
;; needs to know about the mapping's structure — transform chains and
;; canonical predicates are M4's concern at :normalize.
;; ---------------------------------------------------------------------------

(defn- qualified-predicate-map
  "Return `{native-predicate → qualifier-from-key}` for every mapping
   rule that declares a `:mapping/qualifier`. If two rules share the
   same `:mapping/from` with different qualifier configs, the last
   one wins; cross-rule ingest consistency is a plugin-authoring
   discipline in V1."
  [mapping-rules]
  (into {}
        (keep (fn [r]
                (when-let [q (:mapping/qualifier r)]
                  [(:mapping/from r) (:from q)])))
        mapping-rules))

(defn- mapped-predicate-set
  "Set of source predicates mentioned anywhere in `mapping-rules`."
  [mapping-rules]
  (into #{} (map :mapping/from) mapping-rules))

;; ---------------------------------------------------------------------------
;; JSON key normalization
;;
;; JSON objects come from parsing with `clojure.data.json` and carry
;; string keys by default. We normalize once at the boundary so the
;; walker compares keys uniformly with the mapping's keyword
;; predicates. The convention is to split on the first ':' for
;; prefix-style names — `\"dc:title\"` → `:dc/title`. Unprefixed
;; strings become unnamespaced keywords. JSON-LD `@…` keys parse via
;; `(keyword \"@…\")` unchanged.
;; ---------------------------------------------------------------------------

(defn- normalize-key
  "Convert a JSON object key (string) to a Clojure keyword, splitting
   on the first ':' for prefix notation. Keywords pass through
   unchanged (idempotent)."
  [k]
  (if (keyword? k)
    k
    (let [s   (str k)
          idx (.indexOf s ":")]
      (if (pos? idx)
        (keyword (subs s 0 idx) (subs s (inc idx)))
        (keyword s)))))

(defn- normalize-tree
  "Recursively walk a JSON tree, normalizing every map key via
   `normalize-key`. Map values, vector elements, and scalars are
   preserved as-is."
  [tree]
  (cond
    (map? tree)
    (into {}
          (map (fn [[k v]] [(normalize-key k) (normalize-tree v)]))
          tree)

    (sequential? tree)
    (mapv normalize-tree tree)

    :else
    tree))

;; ---------------------------------------------------------------------------
;; Emit helpers
;;
;; The walker builds `model/assertion` and `model/fragment` shapes
;; directly — no transforms here, no predicate renaming. Native
;; vocabulary all the way; M4 :normalize rewrites to canonical later.
;; Every emitted assertion carries `:provenance {:pass :ingest}` so
;; trace queries can attribute it to the ingest phase.
;; ---------------------------------------------------------------------------

(def ^:private json-value-key
  "JSON-LD `@value` marker for the main value inside a qualified
   sub-object."
  (keyword "@value"))

(def ^:private ingest-provenance
  {:pass :ingest})

(defn- ensure-vector
  "Wrap a single value in a one-element vector; sequential values
   pass through (coerced to vector); nil becomes an empty vector."
  [x]
  (cond
    (sequential? x) (vec x)
    (nil? x)        []
    :else           [x]))

(defn- emit-flat
  "Build assertions for a flat predicate. Each element of `values`
   yields one `[record-id predicate value]` assertion."
  [record-id predicate values]
  (mapv (fn [v]
          (model/assertion {:subject    record-id
                            :predicate  predicate
                            :value      v
                            :provenance ingest-provenance}))
        values))

(defn- emit-qualified-one
  "Build the fragment + per-fragment assertions for one occurrence of
   a qualified predicate. `idx` is the occurrence index per ADR 0012's
   locator scheme. `occurrence` is either a sub-object (JSON-LD shape)
   or a bare primitive (tolerated: the fragment gets just the value
   coord, no qualifier coord)."
  [record-id predicate qualifier-key idx occurrence]
  (let [frag-id    (model/mint-fragment-id record-id [predicate idx])
        is-map     (map? occurrence)
        main-value (if is-map (get occurrence json-value-key) occurrence)
        qual-value (when is-map (get occurrence qualifier-key))
        base       {:fragment   (model/fragment
                                 {:id     frag-id
                                  :source [:json predicate idx]})
                    :assertions [(model/assertion
                                  {:subject    record-id
                                   :predicate  predicate
                                   :value      (model/reference frag-id)
                                   :provenance ingest-provenance})]}]
    (cond-> base
      (some? main-value)
      (update :assertions conj
              (model/assertion {:subject    frag-id
                                :predicate  predicate
                                :value      main-value
                                :provenance ingest-provenance}))

      (some? qual-value)
      (update :assertions conj
              (model/assertion {:subject    frag-id
                                :predicate  qualifier-key
                                :value      qual-value
                                :provenance ingest-provenance})))))

(defn- emit-qualified
  "Build fragments + assertions across every occurrence of a qualified
   predicate, preserving document order via the occurrence index."
  [record-id predicate qualifier-key occurrences]
  (let [emitted (map-indexed
                 #(emit-qualified-one record-id predicate qualifier-key %1 %2)
                 occurrences)]
    {:fragments  (mapv :fragment emitted)
     :assertions (vec (mapcat :assertions emitted))}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ingest-json
  "Walk a parsed JSON tree (top-level map) and produce a Record per
   the plugin's mapping.

   Inputs:
   - `json-tree`: parsed JSON, a map at the top level. String keys
     accepted (the `clojure.data.json` default); keywords pass through.
   - `mapping`: vector of `MappingRule` per ADR 0009.
   - `opts`: `{:record-id :kind :source}`. `:record-id` and `:kind`
     are required; `:source` is optional and carried into the Record
     for traceability.

   Output: a `Record` per `regesta.model/Record`, populated with
   fragments and native-vocabulary assertions. `record-consistent?` is
   asserted on the result; a failure throws, signalling a bug in this
   namespace rather than user error."
  [json-tree mapping {:keys [record-id kind source]}]
  (when-not (map? json-tree)
    (throw (ex-info "ingest-json expects a top-level map"
                    {:got json-tree})))
  (let [normalized        (normalize-tree json-tree)
        qualified-by-pred (qualified-predicate-map mapping)
        mapped-preds      (mapped-predicate-set mapping)
        {:keys [fragments assertions]}
        (reduce
         (fn [acc [pred raw-value]]
           (cond
             (not (contains? mapped-preds pred))
             acc

             (contains? qualified-by-pred pred)
             (let [qual-key (get qualified-by-pred pred)
                   occs     (ensure-vector raw-value)
                   emitted  (emit-qualified record-id pred qual-key occs)]
               (-> acc
                   (update :fragments into (:fragments emitted))
                   (update :assertions into (:assertions emitted))))

             :else
             (update acc :assertions into
                     (emit-flat record-id pred (ensure-vector raw-value)))))
         {:fragments [] :assertions []}
         normalized)
        record (model/record {:id         record-id
                              :kind       kind
                              :source     source
                              :fragments  fragments
                              :assertions assertions})]
    (when-not (model/record-consistent? record)
      (throw (ex-info "Shape adapter produced an inconsistent record (bug in regesta.plugins.shape)"
                      {:explanation (model/explain-consistency record)})))
    record))
