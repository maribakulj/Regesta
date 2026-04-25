(ns regesta.diagnostics
  "Diagnostics API.

   Diagnostics are first-class IR citizens (ADR 0001): a validation rule
   produces a `Diagnostic` attached to its subject; the pipeline never throws
   to signal a data problem. This namespace provides the read-side of that
   model: filters, aggregations, human-readable reporting, and a failure
   policy for callers (CLI, CI) that need a yes/no answer.

   Inputs to most functions are diagnostic vectors (e.g. `(:diagnostics
   record)`); helpers `collect` and `collect-many` flatten a record or a
   collection of records into such a vector. Functions are pure and never
   mutate the underlying record."
  (:require [clojure.string :as str]
            [regesta.model :as model]))

;; ---------------------------------------------------------------------------
;; Severity
;;
;; Ordered low-to-high; `error` > `warning` > `info`. The severity vocabulary
;; is closed by `regesta.model/Severity`.
;; ---------------------------------------------------------------------------

(def severity-order
  "Numeric rank for each severity. Higher means more serious."
  {:info 0 :warning 1 :error 2})

(defn- ensure-known-severity! [sev]
  (when-not (contains? severity-order sev)
    (throw (ex-info "Unknown severity"
                    {:severity sev
                     :supported (set (keys severity-order))})))
  sev)

(defn severity-rank
  "Numeric rank of `sev`. Throws on any value outside the closed enum
   defined by `regesta.model/Severity`. The schema already rejects
   unknown severities; this defensive throw catches the case where a
   caller bypassed schema validation."
  [sev]
  (ensure-known-severity! sev)
  (get severity-order sev))

(defn severity>=
  "True if `a` is at least as severe as `b`. Throws on unknown severities."
  [a b]
  (>= (severity-rank a) (severity-rank b)))

(defn max-severity
  "Return the most severe severity present in `diagnostics`, or nil if the
   collection is empty."
  [diagnostics]
  (when (seq diagnostics)
    (reduce (fn [best d]
              (let [s (:severity d)]
                (if (severity>= s best) s best)))
            :info
            diagnostics)))

;; ---------------------------------------------------------------------------
;; Collection helpers
;; ---------------------------------------------------------------------------

(defn collect
  "Return the `:diagnostics` vector of a record (or an empty vector)."
  [record]
  (or (:diagnostics record) []))

(defn collect-many
  "Flatten the diagnostics of every record in `records` into a single vector,
   preserving record order."
  [records]
  (into [] (mapcat collect) records))

;; ---------------------------------------------------------------------------
;; Filters
;;
;; Every filter takes a diagnostic seq and returns a vector. They compose:
;; `(by-rule (errors ds) :rule/title-required)`.
;; ---------------------------------------------------------------------------

(defn by-severity
  "Diagnostics whose severity equals `sev`."
  [diagnostics sev]
  (filterv #(= sev (:severity %)) diagnostics))

(defn errors   [diagnostics] (by-severity diagnostics :error))
(defn warnings [diagnostics] (by-severity diagnostics :warning))
(defn infos    [diagnostics] (by-severity diagnostics :info))

(defn at-least
  "Diagnostics whose severity is >= `sev` in the severity order."
  [diagnostics sev]
  (filterv #(severity>= (:severity %) sev) diagnostics))

(defn by-code
  "Diagnostics whose `:code` equals `code`."
  [diagnostics code]
  (filterv #(= code (:code %)) diagnostics))

(defn by-subject
  "Diagnostics whose `:subject` equals `subject`."
  [diagnostics subject]
  (filterv #(= subject (:subject %)) diagnostics))

(defn by-rule
  "Diagnostics whose provenance attributes them to `rule-id`."
  [diagnostics rule-id]
  (filterv #(= rule-id (get-in % [:provenance :rule])) diagnostics))

(defn by-phase
  "Diagnostics whose provenance attributes them to `phase`."
  [diagnostics phase]
  (filterv #(= phase (get-in % [:provenance :pass])) diagnostics))

(defn with-repairs
  "Diagnostics that carry at least one repair proposal."
  [diagnostics]
  (filterv #(seq (:repairs %)) diagnostics))

;; ---------------------------------------------------------------------------
;; Aggregations
;; ---------------------------------------------------------------------------

(defn count-by-severity
  "Map severity → count, including zeros for severities not present.
   Throws if any diagnostic carries an unknown severity (schema-bypass
   defense; see `severity-rank`)."
  [diagnostics]
  (let [base (zipmap (keys severity-order) (repeat 0))]
    (reduce (fn [acc d]
              (ensure-known-severity! (:severity d))
              (update acc (:severity d) (fnil inc 0)))
            base
            diagnostics)))

(defn group-by-subject
  "Map subject id → vector of diagnostics targeting that subject."
  [diagnostics]
  (reduce (fn [acc d] (update acc (:subject d) (fnil conj []) d))
          {}
          diagnostics))

(defn group-by-code
  "Map diagnostic code → vector of diagnostics with that code."
  [diagnostics]
  (reduce (fn [acc d] (update acc (:code d) (fnil conj []) d))
          {}
          diagnostics))

(defn summary
  "High-level summary suitable for CLI output:
     {:total N
      :by-severity {:error E :warning W :info I}
      :max-severity :error|:warning|:info|nil
      :subjects S      ;; distinct subject count
      :codes C}"
  [diagnostics]
  (let [counts (count-by-severity diagnostics)]
    {:total        (count diagnostics)
     :by-severity  counts
     :max-severity (max-severity diagnostics)
     :subjects     (count (into #{} (map :subject) diagnostics))
     :codes        (count (into #{} (map :code) diagnostics))}))

;; ---------------------------------------------------------------------------
;; Reporting
;;
;; Plain text, no colors, no terminal control. The CLI (Sprint 10) is free
;; to add presentation on top.
;; ---------------------------------------------------------------------------

(defn- severity-tag [sev]
  (case sev
    :error   "ERROR"
    :warning "WARN"
    :info    "INFO"
    (throw (ex-info "Unknown severity in formatter"
                    {:severity sev}))))

(defn format-diagnostic
  "One-line representation of a single diagnostic. Repairs and provenance
   are summarized rather than expanded; pass `:expand-repairs? true` to list
   them on additional lines."
  ([d] (format-diagnostic d {}))
  ([d {:keys [expand-repairs?] :or {expand-repairs? false}}]
   (let [head (format "[%s] %s on %s%s"
                      (severity-tag (:severity d))
                      (pr-str (:code d))
                      (pr-str (:subject d))
                      (if-let [m (:message d)] (str " - " m) ""))
         reps (:repairs d)]
     (if (and expand-repairs? (seq reps))
       (str/join "\n"
                 (cons head
                       (map (fn [r]
                              (str "  -> repair: "
                                   (:description r)
                                   " (op " (:operation r) ")"))
                            reps)))
       head))))

(defn- header-line [sum]
  (format "Diagnostics: %d total (errors %d, warnings %d, infos %d)"
          (:total sum)
          (get-in sum [:by-severity :error])
          (get-in sum [:by-severity :warning])
          (get-in sum [:by-severity :info])))

(defn- section-lines [sev bucket opts]
  (into ["" (format "== %s (%d) ==" (severity-tag sev) (count bucket))]
        (map #(format-diagnostic % opts))
        bucket))

(defn format-report
  "Multi-line report for a collection of diagnostics. Sections are grouped
   by severity in descending order (errors first). Returns a single string;
   never prints."
  ([diagnostics] (format-report diagnostics {}))
  ([diagnostics opts]
   (let [sum     (summary diagnostics)
         body    (mapcat (fn [sev]
                           (let [bucket (by-severity diagnostics sev)]
                             (when (seq bucket)
                               (section-lines sev bucket opts))))
                         [:error :warning :info])]
     (str/join "\n" (cons (header-line sum) body)))))

;; ---------------------------------------------------------------------------
;; Failure policy
;;
;; `should-fail?` answers the CI/CLI question: "should this run be reported
;; as failing?" Policies:
;;
;;   :never               always succeed regardless of diagnostics
;;   :errors-only         fail if any :error is present (default)
;;   :errors-and-warnings fail if any :error or :warning is present
;;   :strict              fail if any diagnostic at all is present
;;
;; The function never throws on diagnostics; callers map the boolean to a
;; process exit code. Unknown policies are a programmer error and throw.
;; ---------------------------------------------------------------------------

(def failure-policies
  #{:never :errors-only :errors-and-warnings :strict})

(defn- policy-threshold
  "Minimum severity that triggers failure under `policy`, or nil for `:never`."
  [policy]
  (case policy
    :never               nil
    :errors-only         :error
    :errors-and-warnings :warning
    :strict              :info))

(defn should-fail?
  "True if `diagnostics` would cause a run to fail under `policy`.
   Default policy is `:errors-only`. Throws on unknown policies."
  ([diagnostics] (should-fail? diagnostics :errors-only))
  ([diagnostics policy]
   (when-not (contains? failure-policies policy)
     (throw (ex-info "Unknown failure policy"
                     {:policy policy :supported failure-policies})))
   (if-let [threshold (policy-threshold policy)]
     (boolean (some #(severity>= (:severity %) threshold) diagnostics))
     false)))

;; ---------------------------------------------------------------------------
;; Sanity check helpers
;; ---------------------------------------------------------------------------

(defn diagnostic?
  "True if `x` is shape-valid as a Diagnostic (delegates to the model)."
  [x]
  (model/valid-diagnostic? x))
