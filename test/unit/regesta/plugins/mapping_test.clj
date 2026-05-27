(ns regesta.plugins.mapping-test
  "Unit tests for the mapping schema and compiler (Sprint 5 M4.A).

   Covers the MappingRule schema, the flat-mapping compiler, transform
   application and failure handling, all three `:on-empty` branches, the
   compiler's input validation, and the integration of compiled mapping
   rules with the runtime. Qualified-mapping compilation is M4.B; the
   only test of that case here is that M4.A explicitly refuses it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.transforms :as tx]
            [regesta.rules :as rules]
            [regesta.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def stdlib tx/core-transforms)

(def minimal-mapping
  {:mapping/id   :map/x
   :mapping/from :native/x
   :mapping/to   :canon/x})

(defn- record-with
  "Build a record with one assertion per value in `values`, all on the
   given native predicate. Convenience for compiler tests."
  [predicate values]
  (model/record
   {:id         :record/r1
    :kind       :test
    :assertions (mapv (fn [v]
                        (model/assertion
                         {:subject   :record/r1
                          :predicate predicate
                          :value     v}))
                      values)}))

(defn- empty-record []
  (model/record {:id :record/r1 :kind :test}))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(deftest schema-accepts-minimal
  (is (mapping/valid-mapping? minimal-mapping))
  (is (nil? (mapping/explain-mapping minimal-mapping))))

(deftest schema-accepts-full-shape
  (let [full {:mapping/id         :map/full
              :mapping/from       :native/full
              :mapping/to         :canon/full
              :mapping/transform  [:trim :lowercase]
              :mapping/qualifier  {:from :native/lang :as :canon/lang}
              :mapping/on-empty   :diagnostic
              :mapping/confidence 0.8
              :mapping/doc        "documentation"}]
    (is (mapping/valid-mapping? full))))

(deftest schema-rejects-missing-required
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/id))))
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/from))))
  (is (not (mapping/valid-mapping? (dissoc minimal-mapping :mapping/to)))))

(deftest schema-rejects-unknown-keys
  (testing "closed shape catches arbitrary unsanctioned keys (typos)"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/strangelet 1))))))

(deftest schema-rejects-non-keyword-id-or-predicates
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/id "string"))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/from "string"))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/to 42)))))

(deftest schema-rejects-bad-transform-shape
  (testing ":mapping/transform must be a vector of keywords"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform :trim))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform ["string-name"]))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/transform '(:trim)))))))

(deftest schema-rejects-bad-on-empty
  (is (not (mapping/valid-mapping?
            (assoc minimal-mapping :mapping/on-empty :bogus)))))

(deftest schema-rejects-bad-qualifier
  (testing "qualifier must carry exactly :from and :as"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from :x}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:as :y}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from :x :as :y :extra 1}))))
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/qualifier {:from "x" :as :y}))))))

(deftest schema-rejects-bad-confidence
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence 2.0))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence -0.1))))
  (is (not (mapping/valid-mapping? (assoc minimal-mapping :mapping/confidence "0.5")))))

(deftest schema-cross-field-on-empty-default-biconditional
  (testing ":on-empty :default without :mapping/default fails"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/on-empty :default)))))
  (testing ":on-empty :default with :mapping/default succeeds"
    (is (mapping/valid-mapping?
         (assoc minimal-mapping
                :mapping/on-empty :default
                :mapping/default  "fallback"))))
  (testing ":mapping/default without :on-empty :default fails (biconditional)"
    (is (not (mapping/valid-mapping?
              (assoc minimal-mapping :mapping/default "fallback")))))
  (testing "default of nil counts as present"
    (is (mapping/valid-mapping?
         (assoc minimal-mapping
                :mapping/on-empty :default
                :mapping/default  nil)))))

;; ---------------------------------------------------------------------------
;; Rule-id derivation
;; ---------------------------------------------------------------------------

(deftest mapping-rule-id-uses-name-portion
  (is (= :rule.from-mapping/dc-title
         (mapping/mapping-rule-id :map/dc-title)))
  (is (= :rule.from-mapping/x
         (mapping/mapping-rule-id :something/x)))
  (is (= :rule.from-mapping/foo
         (mapping/mapping-rule-id :foo))
      "unnamespaced mapping ids keep their name"))

(deftest mapping-rule-id-rejects-non-keyword
  (is (thrown? clojure.lang.ExceptionInfo (mapping/mapping-rule-id "string"))))

;; ---------------------------------------------------------------------------
;; Compiled-rule shape
;; ---------------------------------------------------------------------------

(deftest compile-mapping-returns-runtime-shaped-rule
  (let [cr (mapping/compile-mapping minimal-mapping stdlib)]
    (is (= :rule.from-mapping/x (:id cr)))
    (is (= :normalize (:phase cr)))
    (testing "the runner is the contract apply-rule reaches for"
      (is (vector? (rules/apply-rule cr (empty-record)))))))

(deftest compile-mapping-preserves-doc-when-present
  (let [m  (assoc minimal-mapping :mapping/doc "explain")
        cr (mapping/compile-mapping m stdlib)]
    (is (= "explain" (:doc cr))))
  (let [cr (mapping/compile-mapping minimal-mapping stdlib)]
    (is (not (contains? cr :doc)))))

;; ---------------------------------------------------------------------------
;; Flat compilation — happy paths
;; ---------------------------------------------------------------------------

(deftest flat-mapping-renames-predicate
  (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
        record (record-with :native/x ["v1" "v2"])
        prods  (rules/apply-rule cr record)
        asrts  (mapv :value prods)]
    (is (= [:assertion :assertion] (mapv :kind prods)))
    (is (= [:canon/x :canon/x] (mapv :predicate asrts)))
    (is (= ["v1" "v2"] (mapv :value asrts)))
    (testing "subject is preserved"
      (is (every? #(= :record/r1 (:subject %)) asrts)))))

(deftest flat-mapping-applies-transform-chain
  (let [m      (assoc minimal-mapping :mapping/transform [:trim :lowercase])
        cr     (mapping/compile-mapping m stdlib)
        record (record-with :native/x ["  ABC  " "DeF" "ghi"])
        vals   (mapv #(:value (:value %)) (rules/apply-rule cr record))]
    (is (= ["abc" "def" "ghi"] vals))))

(deftest flat-mapping-multiplicity-preserved
  (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
        record (record-with :native/x ["a" "b" "c" "d"])
        prods  (rules/apply-rule cr record)]
    (is (= 4 (count prods)))
    (testing "no deduplication at the compiler level — that's runtime's job"
      (let [record-dups (record-with :native/x ["x" "x" "x"])
            dup-prods   (rules/apply-rule cr record-dups)]
        (is (= 3 (count dup-prods)))))))

(deftest flat-mapping-confidence-respected
  (testing "explicit confidence flows through"
    (let [m    (assoc minimal-mapping :mapping/confidence 0.5)
          cr   (mapping/compile-mapping m stdlib)
          prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
      (is (= 0.5 (:confidence (:value prod))))))
  (testing "default confidence is 1.0"
    (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
          prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
      (is (= 1.0 (:confidence (:value prod)))))))

(deftest flat-mapping-provenance-has-rule-and-phase
  (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
        prod (first (rules/apply-rule cr (record-with :native/x ["v"])))
        prov (:provenance (:value prod))]
    (is (= :rule.from-mapping/x (:rule prov)))
    (is (= :normalize (:pass prov)))))

(deftest flat-mapping-emitted-assertion-is-asserted-status
  (let [cr   (mapping/compile-mapping minimal-mapping stdlib)
        prod (first (rules/apply-rule cr (record-with :native/x ["v"])))]
    (is (= :asserted (:status (:value prod))))))

;; ---------------------------------------------------------------------------
;; Flat compilation — transform failure
;; ---------------------------------------------------------------------------

(deftest flat-mapping-transform-failure-emits-info-diagnostic
  (let [m      (assoc minimal-mapping :mapping/transform [:parse-int])
        cr     (mapping/compile-mapping m stdlib)
        record (record-with :native/x ["42" "garbage" "7"])
        prods  (rules/apply-rule cr record)]
    (is (= [:assertion :diagnostic :assertion] (mapv :kind prods)))
    (testing "the diagnostic is informational and pins the failure"
      (let [d (:value (second prods))]
        (is (= :info (:severity d)))
        (is (= :transform-failed (:code d)))
        (is (= :record/r1 (:subject d)))
        (is (string? (:message d)))
        (is (re-find #"garbage" (:message d)))
        (is (= :rule.from-mapping/x (get-in d [:provenance :rule])))))
    (testing "successful matches still emit their canonical assertions"
      (is (= 42 (:value (:value (first prods)))))
      (is (=  7 (:value (:value (last prods))))))))

(deftest flat-mapping-truncates-large-values-in-diagnostic-message
  (let [m      (assoc minimal-mapping :mapping/transform [:parse-int])
        cr     (mapping/compile-mapping m stdlib)
        record (record-with :native/x [(apply str (repeat 500 \a))])
        d      (-> (rules/apply-rule cr record) first :value)]
    (is (= :transform-failed (:code d)))
    (is (re-find #"…" (:message d))
        "long values are truncated with an ellipsis")))

;; ---------------------------------------------------------------------------
;; Flat compilation — :on-empty branches
;; ---------------------------------------------------------------------------

(deftest on-empty-skip-by-default
  (let [cr    (mapping/compile-mapping minimal-mapping stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= [] prods))))

(deftest on-empty-explicit-skip
  (let [m  (assoc minimal-mapping :mapping/on-empty :skip)
        cr (mapping/compile-mapping m stdlib)]
    (is (= [] (rules/apply-rule cr (empty-record))))))

(deftest on-empty-diagnostic-emits-info-missing-predicate
  (let [m  (assoc minimal-mapping :mapping/on-empty :diagnostic)
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= 1 (count prods)))
    (let [d (:value (first prods))]
      (is (= :info (:severity d)))
      (is (= :missing-source-predicate (:code d)))
      (is (= :record/r1 (:subject d)))
      (is (re-find #":native/x" (:message d)))
      (is (= :rule.from-mapping/x (get-in d [:provenance :rule]))))))

(deftest on-empty-default-emits-canonical-assertion-with-default-value
  (let [m  (assoc minimal-mapping
                  :mapping/on-empty :default
                  :mapping/default  "fallback")
        cr (mapping/compile-mapping m stdlib)
        prods (rules/apply-rule cr (empty-record))]
    (is (= 1 (count prods)))
    (let [a (:value (first prods))]
      (is (= :record/r1 (:subject a)))
      (is (= :canon/x (:predicate a)))
      (is (= "fallback" (:value a)))
      (is (= :asserted (:status a))))))

(deftest on-empty-default-does-not-fire-when-source-present
  (testing "with source values present the default branch is bypassed"
    (let [m  (assoc minimal-mapping
                    :mapping/on-empty :default
                    :mapping/default  "fallback")
          cr (mapping/compile-mapping m stdlib)
          prods (rules/apply-rule cr (record-with :native/x ["real"]))]
      (is (= 1 (count prods)))
      (is (= "real" (:value (:value (first prods))))))))

(deftest on-empty-default-bypasses-transform-chain
  (testing "the default is canonical-side; transforms apply to source values only"
    (let [m  (assoc minimal-mapping
                    :mapping/on-empty :default
                    :mapping/default  "FALLBACK"
                    :mapping/transform [:lowercase])
          cr (mapping/compile-mapping m stdlib)
          prods (rules/apply-rule cr (empty-record))]
      (is (= 1 (count prods)))
      (is (= "FALLBACK" (:value (:value (first prods))))))))

;; ---------------------------------------------------------------------------
;; compile-mapping — input validation
;; ---------------------------------------------------------------------------

(deftest compile-mapping-rejects-schema-violations
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid mapping rule"
                        (mapping/compile-mapping {:mapping/from :a} stdlib))))

(deftest compile-mapping-rejects-unknown-transform-eagerly
  (let [m (assoc minimal-mapping :mapping/transform [:nonexistent])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown transform"
                          (mapping/compile-mapping m stdlib))
        "unknown transforms fail at compile time, not at first record")))

(deftest compile-mapping-rejects-qualified-mapping-explicitly
  (let [m (assoc minimal-mapping
                 :mapping/qualifier {:from :native/lang :as :canon/lang})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Qualified mappings"
                          (mapping/compile-mapping m stdlib)))))

;; ---------------------------------------------------------------------------
;; Compiler purity
;; ---------------------------------------------------------------------------

(deftest compilation-is-pure-across-runs
  (testing "the same mapping compiled twice produces runners with identical outputs"
    (let [m      (assoc minimal-mapping :mapping/transform [:trim])
          cr1    (mapping/compile-mapping m stdlib)
          cr2    (mapping/compile-mapping m stdlib)
          record (record-with :native/x ["  abc  " "  def  "])]
      (is (= (rules/apply-rule cr1 record)
             (rules/apply-rule cr2 record))))))

(deftest applying-the-same-rule-twice-yields-identical-productions
  (testing "the runner is a pure function of the record"
    (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
          record (record-with :native/x ["a" "b"])]
      (is (= (rules/apply-rule cr record)
             (rules/apply-rule cr record))))))

;; ---------------------------------------------------------------------------
;; compile-mappings
;; ---------------------------------------------------------------------------

(deftest compile-mappings-returns-flat-list-of-compiled-rules
  (let [ms  [{:mapping/id :map/a :mapping/from :n/a :mapping/to :c/a}
             {:mapping/id :map/b :mapping/from :n/b :mapping/to :c/b}]
        crs (mapping/compile-mappings ms stdlib)]
    (is (= 2 (count crs)))
    (is (= [:rule.from-mapping/a :rule.from-mapping/b] (mapv :id crs)))
    (is (every? #(= :normalize (:phase %)) crs))))

(deftest compile-mappings-fails-fast-on-first-bad-rule
  (let [ms [{:mapping/id :map/a :mapping/from :n/a :mapping/to :c/a}
            {:mapping/from :nope}]]   ; invalid: missing :mapping/id and :mapping/to
    (is (thrown? clojure.lang.ExceptionInfo
                 (mapping/compile-mappings ms stdlib)))))

;; ---------------------------------------------------------------------------
;; Plugin extension transforms compose
;; ---------------------------------------------------------------------------

(deftest plugin-extension-transforms-work-end-to-end
  (testing "a compiler given an extended stdlib resolves plugin transforms"
    (let [extended (assoc stdlib :uppercase-bang
                          (fn [v] (when (string? v)
                                    (str (str/upper-case v) "!"))))
          m        (assoc minimal-mapping :mapping/transform [:uppercase-bang])
          cr       (mapping/compile-mapping m extended)
          record   (record-with :native/x ["hello"])
          prod     (first (rules/apply-rule cr record))]
      (is (= "HELLO!" (:value (:value prod)))))))

;; ---------------------------------------------------------------------------
;; Runtime integration
;; ---------------------------------------------------------------------------

(deftest compiled-mapping-runs-through-run-phase
  (testing "mapping-compiled rules participate in run-phase like any rule"
    (let [m      (assoc minimal-mapping :mapping/transform [:trim])
          cr     (mapping/compile-mapping m stdlib)
          record (record-with :native/x ["  hello  "])
          {enr :record} (runtime/run-phase record [cr] :normalize)]
      (is (some #(and (= :canon/x (:predicate %))
                      (= "hello"  (:value %)))
                (:assertions enr))))))

(deftest compiled-mapping-rules-co-exist-with-data-form-rules
  (testing "the runtime treats both kinds uniformly"
    (let [mapping-rule (mapping/compile-mapping minimal-mapping stdlib)
          regular-rule (rules/compile-rule
                        {:id      :rule/tag
                         :phase   :normalize
                         :match   [['?r :meta/id '?r]]
                         :produce {:assert {:subject '?r
                                            :predicate :canon/tagged
                                            :value true}}})
          record       (record-with :native/x ["v"])
          {enr :record}
          (runtime/run-phase record [mapping-rule regular-rule] :normalize)]
      (is (some #(= :canon/x (:predicate %))       (:assertions enr)))
      (is (some #(= :canon/tagged (:predicate %))  (:assertions enr))))))

(deftest compiled-mapping-trace-mentions-rule-id
  (testing "provenance from a mapping is queryable by rule id"
    (let [cr     (mapping/compile-mapping minimal-mapping stdlib)
          record (record-with :native/x ["v"])
          {enr :record} (runtime/run-phase record [cr] :normalize)]
      (is (= 1 (count (runtime/assertions-by-rule enr :rule.from-mapping/x)))))))
