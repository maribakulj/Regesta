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

   Qualified mappings (those with `:mapping/qualifier`) are rejected by
   `compile-mapping` in M4.A; their three-rule expansion lands in M4.B.
   The shape adapter (M5) is the only V1 source of fragments —
   ADR 0011 §Limitation forbids the rule layer from minting them, so a
   qualified mapping compiler must assume the fragments already exist
   on the record at `:normalize` time."
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
    [:mapping/default    {:optional true} :any]
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
   namespace doc and ADR 0009 §Consequences for the convention."
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
;; Flat-mapping compilation
;;
;; The runner filters the record's triples by source predicate, applies
;; the transform chain to each match's value, and emits one production
;; per match. Transform short-circuit (some? input, nil output) becomes
;; a `:transform-failed` info diagnostic; ADR 0009 §"Open V2 questions"
;; §"Confidence inheritance" pins this behaviour ("failure surfaces as
;; a diagnostic"). When no triple matches, `:on-empty` decides: skip,
;; emit a `:missing-source-predicate` info diagnostic, or emit the
;; default-valued canonical assertion on the record id.
;;
;; The default value bypasses the transform chain — `:mapping/default`
;; is the canonical-side fallback, not source-side. ADR 0009 doesn't
;; spell this out explicitly but it is the only coherent reading: the
;; mapping author wrote the default in the canonical vocabulary.
;; ---------------------------------------------------------------------------

(defn- compile-flat-mapping
  "Compile a flat (no-qualifier) mapping rule into a compiled rule.
   Assumes `mapping-rule` is already schema-validated."
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

        emit-from-match
        (fn [[s _p v]]
          (let [v' (transform v)]
            (cond
              (some? v')
              (assertion-production
               {:subject s :predicate to :value v'
                :confidence confidence :rule-id rule-id})

              (nil? v)
              nil

              :else
              (diagnostic-production
               {:severity :info :code :transform-failed
                :subject s :rule-id rule-id
                :message (transform-failed-message chain v)}))))

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
          (let [matches (filterv (fn [[_s p _v]] (= p from))
                                 (rules/record-triples record))]
            (if (seq matches)
              (into [] (keep emit-from-match) matches)
              (emit-on-empty (:id record)))))]
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

   Throws ex-info on schema violations. Qualified mappings (those
   carrying a `:mapping/qualifier`) are rejected with an explicit
   message — their compilation lands in Sprint 5 M4.B."
  [mapping-rule transforms-stdlib]
  (validate-or-throw! mapping-rule)
  (when (:mapping/qualifier mapping-rule)
    (throw (ex-info "Qualified mappings are not yet compiled — lands in Sprint 5 M4.B"
                    {:mapping-id (:mapping/id mapping-rule)
                     :qualifier  (:mapping/qualifier mapping-rule)})))
  (compile-flat-mapping mapping-rule transforms-stdlib))

(defn compile-mappings
  "Compile a vector of mapping rules, in order. Fails fast on the first
   invalid or unsupported mapping rule."
  [mapping-rules transforms-stdlib]
  (mapv #(compile-mapping % transforms-stdlib) mapping-rules))
