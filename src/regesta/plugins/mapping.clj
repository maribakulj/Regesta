(ns regesta.plugins.mapping
  "Mapping schema and compiler (ADR 0009).

   A `mapping` declares how one native predicate of a source format
   becomes one canonical predicate, optionally through a transform
   chain (ADR 0010) and with a policy for how to behave when the source
   is absent. Mappings are data — a vector of `MappingRule` maps in the
   plugin's `:mapping` slot (ADR 0007). The runtime expands them into
   `:normalize`-phase compiled rules at register time.

   The compiler is a pure function `MappingRule × stdlib → compiled
   rule`. Its output is runtime-shaped: `apply-rule` cannot tell a
   mapping-compiled rule from a data-form-compiled rule. This shape
   choice — compiled rules with bespoke runners, not data-form Rule
   templates — is the V1 reconciliation between two facts: (a) the
   rule DSL deliberately disallows applying a Clojure function to a
   bound value (ADR 0002), and (b) the mapping abstraction needs
   exactly that, because transforms are functions. ADR 0009 §Schema
   spells out the consequence: mappings remain data sugar, while
   compilation goes straight to a runner rather than to a data-form
   rule.

   Qualified mappings rename the same source predicate that appears on
   both the record (as a reference value) and on the fragment (as the
   primitive value coord), plus a second predicate carrying the
   qualifier (e.g. `:xml/lang`). All three renames happen inside a
   single compiled rule's runner — one mapping = one rule entry in the
   trace, regardless of qualifier presence. The shape adapter (M5) is
   the only V1 source of fragments (ADR 0011 §Limitation forbids the
   rule layer from minting them), so the qualified-mapping compiler
   assumes the fragments already exist on the record at `:normalize`
   time."
  (:require [malli.core :as m]
            [regesta.model :as model]
            [regesta.plugins.transforms :as tx]
            [regesta.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Schema
;;
;; Closed shape: every key is named. Extensions go through the transform
;; stdlib (ADR 0010), not through new top-level mapping keys. The
;; cross-field invariant `:mapping/on-empty :default ⇔ :mapping/default
;; present` is enforced by an `:fn` predicate — biconditional, so a stray
;; `:mapping/default` with `:on-empty :skip` is caught as a likely typo.
;; ---------------------------------------------------------------------------

(def Qualifier
  "Shape of the `:mapping/qualifier` sub-map. `:from` names the source
   attribute carrying the qualifier (e.g. `:xml/lang`); `:as` names the
   predicate the qualifier coord takes on the fragment after
   normalization (e.g. `:canon/lang`). Both are keywords; namespacing
   is conventional but not enforced here — the canonical vocabulary
   imposes its own discipline at its own layer."
  [:map {:closed true}
   [:from :keyword]
   [:as   :keyword]])

(def MappingRule
  "Malli schema for one mapping rule, per ADR 0009 §Decision."
  [:and
   [:map {:closed true}
    [:mapping/id         :keyword]
    [:mapping/from       :keyword]
    [:mapping/to         :keyword]
    [:mapping/transform  {:optional true} [:vector :keyword]]
    [:mapping/qualifier  {:optional true} Qualifier]
    [:mapping/on-empty   {:optional true} [:enum :skip :diagnostic :default]]
    [:mapping/default    {:optional true}
     [:fn {:error/message ":mapping/default cannot be nil — it would emit a shape-invalid assertion"}
      some?]]
    [:mapping/confidence {:optional true} model/Confidence]
    [:mapping/doc        {:optional true} :string]]
   [:fn {:error/message
         ":mapping/on-empty :default ⇔ :mapping/default (both or neither)"}
    (fn [m]
      (= (= :default (:mapping/on-empty m))
         (contains? m :mapping/default)))]])

(defn valid-mapping?
  "True if `mapping-rule` conforms to the MappingRule schema."
  [mapping-rule]
  (m/validate MappingRule mapping-rule))

(defn explain-mapping
  "Return a Malli explanation map describing why `mapping-rule` is
   invalid, or nil if it is valid."
  [mapping-rule]
  (m/explain MappingRule mapping-rule))

(defn- validate-or-throw! [mapping-rule]
  (when-let [err (explain-mapping mapping-rule)]
    (throw (ex-info "Invalid mapping rule"
                    {:mapping-id  (:mapping/id mapping-rule)
                     :explanation err})))
  mapping-rule)

;; ---------------------------------------------------------------------------
;; Rule-id derivation
;;
;; The compiled rule's id is derived deterministically from the mapping
;; id so trace queries can route between the two. The convention:
;;
;;   :map/dc-title       → :rule.from-mapping/dc-title
;;   :anything/foo-bar   → :rule.from-mapping/foo-bar
;;
;; Only the `name` portion of the mapping id is kept; cross-plugin
;; uniqueness of compiled rule ids is the registry layer's concern
;; (plugins ship their own mapping namespaces and authors are expected
;; to use distinctive names). ADR 0009 §Consequences cites this exact
;; example.
;; ---------------------------------------------------------------------------

(defn mapping-rule-id
  "Derive the compiled-rule id from a `:mapping/id` keyword. See the
   namespace doc and ADR 0009 §Consequences for the convention.

   Cross-plugin collisions are not detected by *this* function: two
   mapping rules sharing the same `:mapping/id` name portion (across
   different plugins) produce identical compiled rule ids, which would
   conflate in provenance traces. `compile-mappings` rejects such a
   batch at compile time; plugin authors should still pick distinctive
   `:mapping/id` names. See ADR 0009 §Open V2 questions."
  [mapping-id]
  (when-not (keyword? mapping-id)
    (throw (ex-info "mapping-rule-id requires a keyword mapping id"
                    {:mapping-id mapping-id})))
  (keyword "rule.from-mapping" (name mapping-id)))

;; ---------------------------------------------------------------------------
;; Production helpers
;;
;; Productions are the contract between a rule's runner and the runtime:
;; `{:kind :assertion :value <Assertion>}` or
;; `{:kind :diagnostic :value <Diagnostic>}`. Mapping-compiled rules
;; produce the same shapes a data-form rule's `:assert` / `:diagnostic`
;; actions would.
;; ---------------------------------------------------------------------------

(defn- normalize-provenance [rule-id]
  {:rule rule-id :pass :normalize})

(defn- assertion-production
  [{:keys [subject predicate value confidence rule-id]}]
  {:kind :assertion
   :value (model/assertion
           {:subject    subject
            :predicate  predicate
            :value      value
            :confidence confidence
            :status     :asserted
            :provenance (normalize-provenance rule-id)})})

(defn- diagnostic-production
  [{:keys [severity code subject message rule-id]}]
  {:kind :diagnostic
   :value (model/diagnostic
           {:severity   severity
            :code       code
            :subject    subject
            :message    message
            :provenance (normalize-provenance rule-id)})})

(defn- truncate
  "Trim a stringified value to at most `n` chars with an ellipsis. Used
   only in diagnostic messages so a `:transform-failed` on a 10 kB blob
   doesn't dump the blob into the diagnostic vector."
  [s n]
  (if (<= (count s) n) s (str (subs s 0 n) "…")))

(defn- transform-failed-message [chain original-value]
  (str "Transform chain " (pr-str chain)
       " yielded nil for value " (truncate (pr-str original-value) 80)))

;; ---------------------------------------------------------------------------
;; Compilation
;;
;; The runner filters the record's triples by source predicate and emits
;; productions per match. The transform chain applies to primitive values
;; only; non-primitive values (reference, structured, uncertain — see
;; `regesta.model/primitive-value?`) pass through unchanged. This split
;; matters for qualified mappings: the same source predicate appears on
;; the record carrying a reference value (which must rename through
;; untouched) and on the fragment carrying the primitive value (which the
;; transform may target). A blanket "always apply the chain" policy would
;; mis-fire on the reference value, since string transforms short-circuit
;; on maps; the early-skip avoids spurious `:transform-failed` diagnostics
;; in that case.
;;
;; When the chain is non-empty and the value is primitive but the chain
;; yields nil, the rule emits a `:transform-failed` info diagnostic per
;; ADR 0009 §"Open V2 questions" §"Confidence inheritance" ("failure
;; surfaces as a diagnostic"). When no triple matches the source
;; predicate at all, `:on-empty` decides: skip, emit a
;; `:missing-source-predicate` info diagnostic, or emit the default-valued
;; canonical assertion on the record id.
;;
;; The default value bypasses the transform chain — `:mapping/default`
;; is the canonical-side fallback, not source-side. ADR 0009 doesn't
;; spell this out explicitly but it is the only coherent reading: the
;; mapping author wrote the default in the canonical vocabulary.
;;
;; The qualifier-rename block is independent of the on-empty branch: it
;; runs whenever any triple matches `:qualifier/from`, regardless of
;; whether the main predicate is present. In a well-formed record, the
;; qualifier triples live on fragments minted via the main predicate, so
;; the two presence sets agree — but the runner doesn't enforce that
;; correlation, and a stray qualifier triple still gets renamed.
;; Qualifier values are not transformed (transforms target the value
;; coord, not the qualifier).
;; ---------------------------------------------------------------------------

(defn- compile-mapping-rule
  "Compile one mapping rule into a single compiled rule whose runner
   handles both the flat-rename case and, when `:mapping/qualifier` is
   present, the additional qualifier rename on fragments. Assumes
   `mapping-rule` is already schema-validated."
  [mapping-rule transforms-stdlib]
  (let [from       (:mapping/from mapping-rule)
        to         (:mapping/to mapping-rule)
        chain      (get mapping-rule :mapping/transform [])
        transform  (tx/compose transforms-stdlib chain)
        on-empty   (get mapping-rule :mapping/on-empty :skip)
        confidence (get mapping-rule :mapping/confidence 1.0)
        default    (:mapping/default mapping-rule)
        rule-id    (mapping-rule-id (:mapping/id mapping-rule))
        doc        (:mapping/doc mapping-rule)
        qualifier  (:mapping/qualifier mapping-rule)
        q-from     (:from qualifier)
        q-as       (:as qualifier)

        emit-rename-match
        (fn [[s _p v]]
          (cond
            (nil? v)
            nil

            (or (empty? chain) (not (model/primitive-value? v)))
            (assertion-production
             {:subject s :predicate to :value v
              :confidence confidence :rule-id rule-id})

            :else
            (let [v' (transform v)]
              (if (some? v')
                (assertion-production
                 {:subject s :predicate to :value v'
                  :confidence confidence :rule-id rule-id})
                (diagnostic-production
                 {:severity :info :code :transform-failed
                  :subject s :rule-id rule-id
                  :message (transform-failed-message chain v)})))))

        emit-qualifier-match
        (fn [[s _p v]]
          (when (some? v)
            (assertion-production
             {:subject s :predicate q-as :value v
              :confidence confidence :rule-id rule-id})))

        emit-on-empty
        (fn [record-id]
          (case on-empty
            :skip       []
            :diagnostic [(diagnostic-production
                          {:severity :info :code :missing-source-predicate
                           :subject record-id :rule-id rule-id
                           :message (str "Source predicate " from
                                         " not present on record")})]
            :default    [(assertion-production
                          {:subject record-id :predicate to :value default
                           :confidence confidence :rule-id rule-id})]))

        runner
        (fn run [record]
          (let [triples      (rules/record-triples record)
                from-matches (filterv (fn [[_ p _]] (= p from)) triples)
                base-prods   (if (seq from-matches)
                               (into [] (keep emit-rename-match) from-matches)
                               (emit-on-empty (:id record)))]
            (if qualifier
              (let [qual-matches (filterv (fn [[_ p _]] (= p q-from)) triples)]
                (into base-prods (keep emit-qualifier-match) qual-matches))
              base-prods)))]
    (rules/compiled-rule {:id rule-id :phase :normalize :doc doc :runner runner})))

;; ---------------------------------------------------------------------------
;; Public compiler
;; ---------------------------------------------------------------------------

(defn compile-mapping
  "Compile a single mapping rule into a runtime-shaped compiled rule.

   `transforms-stdlib` is the effective transform stdlib (typically
   `(:transforms (regesta.plugins/effective-stdlib registry))`).
   Transform-name resolution happens once at compile time; unknown
   names throw immediately rather than at first-record-time.

   Handles both flat and qualified mapping rules. A qualified mapping
   compiles to a single rule whose runner renames the source predicate
   (on every triple it appears in, primitive values transformed,
   non-primitive values passed through) and renames the qualifier
   predicate on every fragment that carries it. Throws ex-info on
   schema violations or unknown transform names.

   Non-primitive values (reference, structured, uncertain — per
   `regesta.model/primitive-value?`) bypass the transform chain
   entirely and pass through unchanged. This is essential for
   qualified mappings, where the source predicate carries both
   record-level reference values and fragment-level primitives;
   without the early skip, string transforms would short-circuit on
   the reference map and emit a spurious `:transform-failed`
   diagnostic."
  [mapping-rule transforms-stdlib]
  (validate-or-throw! mapping-rule)
  (compile-mapping-rule mapping-rule transforms-stdlib))

(defn- check-distinct-rule-ids!
  "Reject a batch in which two mapping rules derive the same compiled rule
   id. `mapping-rule-id` keeps only the *name* portion of `:mapping/id`, so
   `:plugin-a/dc-title` and `:plugin-b/dc-title` both derive
   `:rule.from-mapping/dc-title` and would conflate silently in the
   provenance trace. The ex-data maps each colliding rule id to the source
   `:mapping/id`s responsible. Non-keyword / absent ids are ignored here:
   per-rule schema validation in `compile-mapping` reports those with a full
   explanation."
  [mapping-rules]
  (let [ids        (filter keyword? (map :mapping/id mapping-rules))
        collisions (into {}
                         (keep (fn [[derived src-ids]]
                                 (when (> (count src-ids) 1)
                                   [derived (vec src-ids)])))
                         (group-by mapping-rule-id ids))]
    (when (seq collisions)
      (throw (ex-info (str "Mapping rules derive colliding compiled rule ids; "
                           "their provenance would conflate. Pick distinct "
                           ":mapping/id name portions.")
                      {:collisions collisions}))))
  mapping-rules)

(defn compile-mappings
  "Compile a vector of mapping rules, in order. Fails fast on the first
   invalid or unsupported mapping rule. Rejects the whole batch if two
   rules derive the same compiled rule id — their provenance would
   otherwise conflate silently in the trace (ADR 0009 §Open V2)."
  [mapping-rules transforms-stdlib]
  (check-distinct-rule-ids! mapping-rules)
  (mapv #(compile-mapping % transforms-stdlib) mapping-rules))
