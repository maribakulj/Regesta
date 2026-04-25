(ns regesta.runtime
  "Rule execution engine.

   Applies a compiled rule set to a record, collects productions
   (assertions, diagnostics, repair proposals, projection intents), and
   merges them back into the record with full provenance traceability.

   Runtime contract (ADR 0004):
   - Rules execute only in their declared phase.
   - A phase runs for a fixed number of cycles (default 1) with a hard
     cap enforced by `max-cycles`. No generalized fixpoint in V1.
   - New productions from cycle N are visible to rules in cycle N+1.
   - Every merged assertion / diagnostic carries provenance pointing to
     the rule and phase that produced it (set by the rule compiler).

   What the runtime merges:
   - `:assertion` productions → appended to `record[:assertions]`.
   - `:diagnostic` productions → appended to `record[:diagnostics]`.
   - `:repair` productions → not merged into the record in V1; authors
     that want repairs attached to a diagnostic place them inline in
     the diagnostic template's `:repairs` vector. Standalone repairs
     stay accessible via the returned `:productions` trace.

   Out of scope for this sprint (deferred to later):
   - Cross-record execution, dedup, retract semantics, convergence
     detection, projection."
  (:require [malli.core :as m]
            [regesta.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Bounds
;; ---------------------------------------------------------------------------

(def ^:const max-cycles
  "Hard cap on iteration count for any single phase (ADR 0004)."
  16)

;; ---------------------------------------------------------------------------
;; Rule filtering
;; ---------------------------------------------------------------------------

(defn rules-for-phase
  "Return the subset of compiled rules whose :phase equals `phase`."
  [compiled-rules phase]
  (filterv #(= phase (:phase %)) compiled-rules))

;; ---------------------------------------------------------------------------
;; Merging productions into a record
;;
;; Productions are deduplicated at merge time by structural identity
;; (ADR 0008). The first occurrence wins; subsequent identical productions
;; are no-ops on the record but remain visible in the run's production
;; trace.
;; ---------------------------------------------------------------------------

(defn assertion-identity
  "Identity key for assertion deduplication: subject + predicate + value
   + status. Provenance and confidence are intentionally excluded — they
   describe who/how, not what (ADR 0008)."
  [a]
  [(:subject a) (:predicate a) (:value a) (:status a)])

(defn diagnostic-identity
  "Identity key for diagnostic deduplication: subject + code + severity
   + message. Repairs and provenance are excluded (ADR 0008)."
  [d]
  [(:subject d) (:code d) (:severity d) (:message d)])

(defn- dedup-append [record k item identity-fn]
  (let [existing      (get record k [])
        existing-keys (into #{} (map identity-fn) existing)]
    (if (contains? existing-keys (identity-fn item))
      record
      (update record k (fnil conj []) item))))

(defn- merge-production [record production]
  (case (:kind production)
    :assertion  (dedup-append record :assertions  (:value production) assertion-identity)
    :diagnostic (dedup-append record :diagnostics (:value production) diagnostic-identity)
    :repair     record  ;; standalone repairs not merged in V1
    record))

(defn merge-productions
  "Apply each production to `record` in order. Identical productions
   collapse: the first occurrence is kept, subsequent ones are no-ops on
   the record. Returns the enriched record."
  [record productions]
  (reduce merge-production record productions))

;; ---------------------------------------------------------------------------
;; Single-cycle execution
;; ---------------------------------------------------------------------------

(defn run-phase-once
  "Run all rules for `phase` against `record` exactly once. Returns a map
   `{:record enriched-record :productions [...]}`."
  [record compiled-rules phase]
  (let [rs          (rules-for-phase compiled-rules phase)
        productions (into [] (mapcat #(rules/apply-rule % record)) rs)]
    {:record      (merge-productions record productions)
     :productions productions}))

;; ---------------------------------------------------------------------------
;; Multi-cycle execution
;; ---------------------------------------------------------------------------

(defn- validate-cycles! [cycles]
  (when-not (nat-int? cycles)
    (throw (ex-info "cycles must be a non-negative integer"
                    {:cycles cycles})))
  (when (> cycles max-cycles)
    (throw (ex-info "cycles exceeds max-cycles"
                    {:cycles cycles :max max-cycles}))))

(defn run-phase
  "Run `phase` against `record` for `cycles` iterations (default 1). New
   productions from cycle N are visible to rules in cycle N+1. Returns a
   map `{:record ... :productions [all-productions-across-cycles]}`."
  ([record compiled-rules phase]
   (run-phase record compiled-rules phase {}))
  ([record compiled-rules phase {:keys [cycles] :or {cycles 1}}]
   (validate-cycles! cycles)
   (loop [record   record
          all-prod []
          i        0]
     (if (>= i cycles)
       {:record record :productions all-prod}
       (let [{:keys [record productions]}
             (run-phase-once record compiled-rules phase)]
         (recur record (into all-prod productions) (inc i)))))))

;; ---------------------------------------------------------------------------
;; Pipeline execution
;; ---------------------------------------------------------------------------

(def PhaseSpec
  [:map
   [:phase [:enum :ingest :normalize :validate :infer :repair :project]]
   [:cycles {:optional true} [:and :int [:>= 0] [:<= max-cycles]]]])

(def Pipeline
  [:vector PhaseSpec])

(defn valid-pipeline?  [p] (m/validate Pipeline p))
(defn explain-pipeline [p] (m/explain Pipeline p))

(defn- validate-pipeline-or-throw! [pipeline]
  (when-let [err (explain-pipeline pipeline)]
    (throw (ex-info "Invalid pipeline" {:explanation err}))))

(defn run-pipeline
  "Run a pipeline — a vector of `{:phase ... :cycles ...}` maps — against
   the record. Phases execute in declared order. Returns a map
   `{:record ... :productions [...]}` accumulated across every phase."
  [record compiled-rules pipeline]
  (validate-pipeline-or-throw! pipeline)
  (loop [record         record
         all-prod       []
         [spec & more]  pipeline]
    (if-not spec
      {:record record :productions all-prod}
      (let [{:keys [record productions]}
            (run-phase record compiled-rules (:phase spec)
                       {:cycles (get spec :cycles 1)})]
        (recur record (into all-prod productions) more)))))

;; ---------------------------------------------------------------------------
;; Trace queries
;;
;; Every merged assertion and diagnostic carries a provenance map with the
;; rule and pass that produced it. These helpers surface that data.
;; ---------------------------------------------------------------------------

(defn assertions-by-rule
  "Return assertions in `record` whose provenance names the given rule."
  [record rule-id]
  (filterv #(= rule-id (get-in % [:provenance :rule]))
           (:assertions record)))

(defn diagnostics-by-rule
  "Return diagnostics in `record` whose provenance names the given rule."
  [record rule-id]
  (filterv #(= rule-id (get-in % [:provenance :rule]))
           (:diagnostics record)))

(defn productions-by-phase
  "Return every assertion and diagnostic in `record` whose provenance
   attributes it to the given phase."
  [record phase]
  {:assertions  (filterv #(= phase (get-in % [:provenance :pass]))
                         (:assertions record))
   :diagnostics (filterv #(= phase (get-in % [:provenance :pass]))
                         (:diagnostics record))})

(defn trace
  "Summary of every rule that touched the record, with counts.
   Returns `[{:rule ... :assertions N :diagnostics M} ...]`, sorted by
   rule id."
  [record]
  (let [a-by      (group-by #(get-in % [:provenance :rule]) (:assertions record))
        d-by      (group-by #(get-in % [:provenance :rule]) (:diagnostics record))
        rule-ids  (into (sorted-set) (concat (keys a-by) (keys d-by)))]
    (for [rid rule-ids
          :when rid]
      {:rule        rid
       :assertions  (count (get a-by rid))
       :diagnostics (count (get d-by rid))})))
