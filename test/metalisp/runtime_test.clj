(ns metalisp.runtime-test
  "Tests for the rule execution engine: phase filtering, production merging,
   multi-cycle execution, pipeline runs, and trace queries."
  (:require [clojure.test :refer [deftest is testing]]
            [metalisp.model :as model]
            [metalisp.rules :as rules]
            [metalisp.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- book [id & {:keys [title kind] :or {kind :book}}]
  (model/record
   {:id id
    :kind kind
    :assertions (cond-> []
                  title (conj (model/assertion {:subject id
                                                :predicate :canon/title
                                                :value title})))}))

(defn- compile-many [rules]
  (mapv rules/compile-rule rules))

(def ^:private tag-rule
  "Normalize rule: tag every book with :canon/seen true."
  {:id :rule/tag
   :phase :normalize
   :match '[[?r :meta/kind :book]]
   :produce {:assert {:subject '?r
                      :predicate :canon/seen
                      :value true}}})

(def ^:private title-required-rule
  "Validation rule: a book without a title produces an error diagnostic."
  {:id :rule/title-required
   :phase :validate
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :error
                          :code :missing-title
                          :subject '?r}}})

(def ^:private normalize-title-rule
  "Validate rule: for every title, emit a normalized copy under a new
   predicate. Intentionally in :validate so tests can control the phase."
  {:id :rule/normalize-title
   :phase :validate
   :match '[[?r :canon/title ?t]]
   :produce {:assert {:subject '?r
                      :predicate :canon/normalized-title
                      :value '?t}}})

;; ---------------------------------------------------------------------------
;; Rule filtering
;; ---------------------------------------------------------------------------

(deftest rules-for-phase-filters-correctly
  (let [rs (compile-many [tag-rule title-required-rule normalize-title-rule])]
    (is (= 1 (count (rt/rules-for-phase rs :normalize))))
    (is (= 2 (count (rt/rules-for-phase rs :validate))))
    (is (= 0 (count (rt/rules-for-phase rs :infer))))))

;; ---------------------------------------------------------------------------
;; Merging productions
;; ---------------------------------------------------------------------------

(deftest merge-assertion-appends-to-record
  (let [r    (book :r/m1)
        a    (model/assertion {:subject :r/m1 :predicate :canon/x :value 1})
        out  (rt/merge-productions r [{:kind :assertion :value a}])]
    (is (= 1 (count (:assertions out))))
    (is (= :canon/x (-> out :assertions first :predicate)))))

(deftest merge-diagnostic-appends-to-record
  (let [r    (book :r/m2)
        d    (model/diagnostic {:severity :error :code :x :subject :r/m2})
        out  (rt/merge-productions r [{:kind :diagnostic :value d}])]
    (is (= 1 (count (:diagnostics out))))
    (is (= :error (-> out :diagnostics first :severity)))))

(deftest merge-repair-is-a-noop
  (let [r   (book :r/m3)
        rep (model/repair {:description "x" :operation :y})
        out (rt/merge-productions r [{:kind :repair :value rep}])]
    (is (= r out))))

(deftest merge-preserves-existing-content
  (let [existing (model/assertion {:subject :r/e :predicate :canon/p :value 0})
        r        (assoc (book :r/e) :assertions [existing])
        new-a    (model/assertion {:subject :r/e :predicate :canon/p :value 1})
        out      (rt/merge-productions r [{:kind :assertion :value new-a}])]
    (is (= 2 (count (:assertions out))))
    (is (= [0 1] (mapv :value (:assertions out))))))

;; ---------------------------------------------------------------------------
;; run-phase-once
;; ---------------------------------------------------------------------------

(deftest run-phase-once-no-rules-is-identity
  (let [r (book :r/a :title "T")
        {:keys [record productions]} (rt/run-phase-once r [] :validate)]
    (is (= r record))
    (is (empty? productions))))

(deftest run-phase-once-skips-wrong-phase
  (let [r   (book :r/b)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase-once r rs :validate)]
    (is (= r record))
    (is (empty? productions))))

(deftest run-phase-once-merges-productions
  (let [r   (book :r/c)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase-once r rs :normalize)]
    (is (= 1 (count productions)))
    (is (= 1 (count (:assertions record))))
    (is (= :canon/seen (-> record :assertions first :predicate)))
    (is (= :rule/tag  (-> record :assertions first :provenance :rule)))))

(deftest run-phase-once-multi-rule
  (let [r   (book :r/d)
        rs  (compile-many [tag-rule title-required-rule])
        {:keys [record productions]} (rt/run-phase-once r rs :validate)]
    ;; Only title-required applies in :validate; tag-rule is :normalize.
    (is (= 1 (count productions)))
    (is (= :diagnostic (:kind (first productions))))
    (is (= :missing-title (get-in (first productions) [:value :code])))
    (is (= :validate (get-in (first productions) [:value :provenance :pass])))
    (is (= 1 (count (:diagnostics record))))))

;; ---------------------------------------------------------------------------
;; run-phase with cycles
;; ---------------------------------------------------------------------------

(deftest run-phase-default-cycles-is-one
  (let [r   (book :r/e)
        rs  (compile-many [tag-rule])
        {:keys [record]} (rt/run-phase r rs :normalize)]
    (is (= 1 (count (:assertions record))))))

(deftest run-phase-zero-cycles-is-identity
  (let [r   (book :r/f)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase r rs :normalize {:cycles 0})]
    (is (= r record))
    (is (empty? productions))))

(deftest run-phase-three-cycles-iterates
  ;; tag-rule has no guard, so every cycle produces another assertion.
  ;; This is a deliberate non-idempotent rule to exercise cycling.
  (let [r   (book :r/g)
        rs  (compile-many [tag-rule])
        {:keys [record productions]} (rt/run-phase r rs :normalize {:cycles 3})]
    (is (= 3 (count productions)))
    (is (= 3 (count (:assertions record))))))

(deftest run-phase-idempotent-rule-does-not-grow-unboundedly-without-guard
  ;; title-required + a title-absent record: first cycle produces the
  ;; diagnostic. But we don't *suppress* duplicates in V1, so running
  ;; additional cycles produces the same diagnostic again.
  (let [r   (book :r/h)
        rs  (compile-many [title-required-rule])
        {:keys [record]} (rt/run-phase r rs :validate {:cycles 2})]
    ;; Documenting the current (no-dedup) behaviour: 2 diagnostics from
    ;; 2 cycles. Dedup is explicitly out of scope for Sprint 3.
    (is (= 2 (count (:diagnostics record))))))

(deftest run-phase-sees-new-productions-from-previous-cycle
  ;; An infer rule that reacts to an assertion produced by a normalize
  ;; rule — but since they're in different phases, we exercise the
  ;; within-phase visibility by chaining two rules in the same phase.
  (let [produce-x   {:id :rule/produce-x
                     :phase :normalize
                     :match '[[?r :meta/kind :book]
                              (absent? ?r :canon/x)]
                     :produce {:assert {:subject '?r
                                        :predicate :canon/x
                                        :value 1}}}
        consume-x   {:id :rule/consume-x
                     :phase :normalize
                     :match '[[?r :canon/x ?v]]
                     :produce {:assert {:subject '?r
                                        :predicate :canon/x-seen
                                        :value true}}}
        r           (book :r/i)
        rs          (compile-many [produce-x consume-x])
        {:keys [record]} (rt/run-phase r rs :normalize {:cycles 2})]
    (is (some #(= :canon/x       (:predicate %)) (:assertions record)))
    (is (some #(= :canon/x-seen  (:predicate %)) (:assertions record)))))

(deftest run-phase-rejects-over-max-cycles
  (is (thrown? clojure.lang.ExceptionInfo
               (rt/run-phase (book :r/j) [] :normalize
                             {:cycles (inc rt/max-cycles)}))))

(deftest run-phase-rejects-negative-cycles
  (is (thrown? clojure.lang.ExceptionInfo
               (rt/run-phase (book :r/k) [] :normalize {:cycles -1}))))

;; ---------------------------------------------------------------------------
;; run-pipeline
;; ---------------------------------------------------------------------------

(deftest run-pipeline-sequences-phases
  (let [r        (book :r/p :title "Les Misérables")
        rs       (compile-many [tag-rule normalize-title-rule])
        pipeline [{:phase :normalize}
                  {:phase :validate}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)]
    (is (some #(= :canon/seen              (:predicate %)) (:assertions record)))
    (is (some #(= :canon/normalized-title  (:predicate %)) (:assertions record)))))

(deftest run-pipeline-honors-cycles
  (let [r        (book :r/q)
        rs       (compile-many [tag-rule])
        pipeline [{:phase :normalize :cycles 3}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)]
    (is (= 3 (count (:assertions record))))))

(deftest run-pipeline-validates-shape
  (is (thrown? clojure.lang.ExceptionInfo
               (rt/run-pipeline (book :r/r) []
                                [{:phase :unknown-phase}]))))

;; ---------------------------------------------------------------------------
;; Trace queries
;; ---------------------------------------------------------------------------

(deftest trace-surfaces-rule-attribution
  (let [r        (book :r/t :title "T")
        rs       (compile-many [tag-rule title-required-rule normalize-title-rule])
        pipeline [{:phase :normalize} {:phase :validate}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)
        tr       (rt/trace record)
        by-rule  (into {} (map (juxt :rule identity) tr))]
    (is (contains? by-rule :rule/tag))
    (is (contains? by-rule :rule/normalize-title))
    (is (= 1 (get-in by-rule [:rule/tag :assertions])))))

(deftest assertions-by-rule-filters
  (let [r   (book :r/u)
        rs  (compile-many [tag-rule])
        {:keys [record]} (rt/run-phase r rs :normalize)]
    (is (= 1 (count (rt/assertions-by-rule record :rule/tag))))
    (is (empty? (rt/assertions-by-rule record :rule/other)))))

(deftest diagnostics-by-rule-filters
  (let [r   (book :r/v)
        rs  (compile-many [title-required-rule])
        {:keys [record]} (rt/run-phase r rs :validate)]
    (is (= 1 (count (rt/diagnostics-by-rule record :rule/title-required))))
    (is (empty? (rt/diagnostics-by-rule record :rule/other)))))

(deftest productions-by-phase
  (let [r        (book :r/w :title "X")
        rs       (compile-many [tag-rule normalize-title-rule])
        pipeline [{:phase :normalize} {:phase :validate}]
        {:keys [record]} (rt/run-pipeline r rs pipeline)]
    (is (= 1 (count (:assertions (rt/productions-by-phase record :normalize)))))
    (is (= 1 (count (:assertions (rt/productions-by-phase record :validate)))))))

;; ---------------------------------------------------------------------------
;; Exit-gate integration test: 20 rules, 50+ productions, every production
;; traceable to its source rule.
;; ---------------------------------------------------------------------------

(defn- synthetic-rule
  "Generate a rule that asserts :canon/tag-i under :normalize."
  [i]
  {:id (keyword (str "rule/tag-" i))
   :phase :normalize
   :match '[[?r :meta/kind :book]]
   :produce {:assert {:subject '?r
                      :predicate (keyword (str "canon/tag-" i))
                      :value i}}})

(defn- synthetic-validator
  "Generate a validator rule that warns when :canon/title is absent."
  [i]
  {:id (keyword (str "rule/v-" i))
   :phase :validate
   :match '[[?r :meta/kind :book]
            (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :warning
                          :code (keyword (str "check-" i))
                          :subject '?r}}})

(deftest exit-gate-twenty-rules-fifty-plus-productions
  (let [rs       (compile-many (concat
                                (map synthetic-rule (range 15))
                                (map synthetic-validator (range 5))))
        _        (is (= 20 (count rs)) "20 compiled rules")
        r        (book :r/gate)
        pipeline [{:phase :normalize :cycles 3}
                  {:phase :validate  :cycles 1}]
        {:keys [record productions]} (rt/run-pipeline r rs pipeline)]
    (testing "At least 50 productions in total (exit gate)"
      (is (>= (count productions) 50)))
    (testing "Every produced assertion names its rule in provenance"
      (is (every? #(some? (get-in % [:provenance :rule]))
                  (:assertions record))))
    (testing "Every produced diagnostic names its rule in provenance"
      (is (every? #(some? (get-in % [:provenance :rule]))
                  (:diagnostics record))))
    (testing "Trace lists every rule that fired"
      (let [traced-rules (set (map :rule (rt/trace record)))]
        ;; 15 tag-rules each produce 3 assertions (3 cycles), 5 validators
        ;; each produce 1 diagnostic. All 20 must appear.
        (is (= 20 (count traced-rules)))))))
