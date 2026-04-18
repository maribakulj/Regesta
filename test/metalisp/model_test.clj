(ns metalisp.model-test
  "Unit tests for the canonical model: schemas, constructors, predicates.
   Round-trip behavior is covered by metalisp.model.round-trip-test."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [metalisp.model :as model]))

;; ---------------------------------------------------------------------------
;; Structural vocabulary
;; ---------------------------------------------------------------------------

(deftest structural-vocabulary-contents
  (testing "the six structural predicates are defined"
    (is (= :meta/id model/meta-id))
    (is (= :meta/kind model/meta-kind))
    (is (= :meta/source model/meta-source))
    (is (= :meta/fragment model/meta-fragment))
    (is (= :meta/diagnostic model/meta-diagnostic))
    (is (= :meta/provenance model/meta-provenance)))
  (testing "structural-vocabulary is a closed set of six predicates"
    (is (set? model/structural-vocabulary))
    (is (= 6 (count model/structural-vocabulary))))
  (testing "structural? discriminates core vs plugin predicates"
    (is (model/structural? :meta/id))
    (is (model/structural? :meta/provenance))
    (is (not (model/structural? :dc/title)))
    (is (not (model/structural? :canon/agent)))
    (is (not (model/structural? :random/keyword)))))

;; ---------------------------------------------------------------------------
;; Status families
;; ---------------------------------------------------------------------------

(deftest status-families-are-disjoint
  (is (empty? (set/intersection
               model/machine-statuses
               model/workflow-statuses)))
  (is (= 7 (count model/statuses)))
  (is (= (set/union model/machine-statuses model/workflow-statuses)
         model/statuses)))

(deftest machine-and-workflow-predicates
  (is (model/machine-status? :asserted))
  (is (model/machine-status? :superseded))
  (is (not (model/machine-status? :accepted)))
  (is (model/workflow-status? :accepted))
  (is (model/workflow-status? :needs-review))
  (is (not (model/workflow-status? :proposed))))

;; ---------------------------------------------------------------------------
;; Primitive / tagged value predicates
;; ---------------------------------------------------------------------------

(deftest primitive-value-predicate
  (is (model/primitive-value? "hello"))
  (is (model/primitive-value? 42))
  (is (model/primitive-value? 3.14))
  (is (model/primitive-value? true))
  (is (model/primitive-value? :some/keyword))
  (is (model/primitive-value? #uuid "00000000-0000-0000-0000-000000000000"))
  (is (model/primitive-value? #inst "2026-01-01"))
  (is (not (model/primitive-value? {:value/kind :reference :value/target :r/id1})))
  (is (not (model/primitive-value? [1 2 3]))))

(deftest tagged-value-predicates
  (let [r (model/reference :record/r42)
        s (model/structured {:first "Victor" :last "Hugo"})
        u (model/uncertain ["1823" "1832"])]
    (is (model/reference-value? r))
    (is (model/structured-value? s))
    (is (model/uncertain-value? u))
    (is (not (model/reference-value? s)))
    (is (not (model/structured-value? u)))
    (is (not (model/uncertain-value? r)))
    (is (not (model/reference-value? "a string")))))

;; ---------------------------------------------------------------------------
;; Value constructors
;; ---------------------------------------------------------------------------

(deftest reference-constructor
  (let [r1 (model/reference :record/r42)
        r2 (model/reference :record/r42 :creator)]
    (is (= {:value/kind :reference :value/target :record/r42} r1))
    (is (= {:value/kind :reference :value/target :record/r42 :value/role :creator} r2))
    (is (model/valid-value? r1))
    (is (model/valid-value? r2))))

(deftest structured-constructor
  (let [s (model/structured {:first "Victor" :last "Hugo"})]
    (is (= :structured (:value/kind s)))
    (is (= {:first "Victor" :last "Hugo"} (:value/fields s)))
    (is (model/valid-value? s))))

(deftest uncertain-constructor
  (let [u1 (model/uncertain ["1823" "1832"])
        u2 (model/uncertain ["1823" "1832"] :ambiguous-source)]
    (is (= :uncertain (:value/kind u1)))
    (is (= ["1823" "1832"] (:value/alternatives u1)))
    (is (= :ambiguous-source (:value/basis u2)))
    (is (model/valid-value? u1))
    (is (model/valid-value? u2))))

(deftest nested-values
  (testing "structured values can contain other tagged values"
    (let [nested (model/structured
                  {:name (model/structured {:first "Victor" :last "Hugo"})
                   :birth (model/uncertain ["1802" "1803"])})]
      (is (model/valid-value? nested)))))

;; ---------------------------------------------------------------------------
;; Assertion constructor + defaults
;; ---------------------------------------------------------------------------

(deftest assertion-constructor-applies-defaults
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Les Misérables"})]
    (is (= :record/r1 (:subject a)))
    (is (= :canon/title (:predicate a)))
    (is (= "Les Misérables" (:value a)))
    (is (= 1.0 (:confidence a)))
    (is (= :asserted (:status a)))
    (is (not (contains? a :provenance)))
    (is (model/valid-assertion? a))))

(deftest assertion-constructor-overrides-defaults
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Untitled"
                            :confidence 0.3
                            :status :proposed
                            :provenance {:rule :title-guess
                                         :pass :infer}})]
    (is (= 0.3 (:confidence a)))
    (is (= :proposed (:status a)))
    (is (= {:rule :title-guess :pass :infer} (:provenance a)))
    (is (model/valid-assertion? a))))

;; ---------------------------------------------------------------------------
;; Diagnostic + Repair
;; ---------------------------------------------------------------------------

(deftest diagnostic-constructor
  (let [d (model/diagnostic {:severity :error
                             :code :missing-title
                             :subject :record/r1
                             :message "Record has no title."})]
    (is (= :error (:severity d)))
    (is (= :missing-title (:code d)))
    (is (= :record/r1 (:subject d)))
    (is (= "Record has no title." (:message d)))
    (is (model/valid-diagnostic? d))))

(deftest repair-constructor
  (let [r (model/repair {:description "Copy alternative title."
                         :operation :copy-from
                         :basis :dc/alternative
                         :applicable? true
                         :safe? true})]
    (is (= :copy-from (:operation r)))
    (is (true? (:safe? r)))
    (is (model/valid-repair? r))))

(deftest diagnostic-with-repairs
  (let [d (model/diagnostic {:severity :warning
                             :code :date-normalizable
                             :subject :record/r1
                             :repairs [(model/repair {:description "Normalize to ISO-8601."
                                                      :operation :normalize-date})]})]
    (is (= 1 (count (:repairs d))))
    (is (model/valid-diagnostic? d))))

;; ---------------------------------------------------------------------------
;; Record constructor
;; ---------------------------------------------------------------------------

(deftest record-constructor-minimum
  (let [r (model/record {:id :record/r1 :kind :book})]
    (is (= :record/r1 (:id r)))
    (is (= :book (:kind r)))
    (is (not (contains? r :assertions)))
    (is (model/valid-record? r))))

(deftest record-constructor-with-content
  (let [a (model/assertion {:subject :record/r1
                            :predicate :canon/title
                            :value "Les Misérables"})
        d (model/diagnostic {:severity :info
                             :code :normalized
                             :subject :record/r1})
        r (model/record {:id :record/r1
                         :kind :book
                         :source "file:///tmp/sample.xml"
                         :assertions [a]
                         :diagnostics [d]
                         :provenance {:source "file:///tmp/sample.xml"
                                      :pass :ingest}})]
    (is (= 1 (count (:assertions r))))
    (is (= 1 (count (:diagnostics r))))
    (is (model/valid-record? r))))

;; ---------------------------------------------------------------------------
;; Status predicates
;; ---------------------------------------------------------------------------

(deftest status-predicates
  (let [mk (fn [status] (model/assertion {:subject :r/id1
                                          :predicate :p
                                          :value "v"
                                          :status status}))]
    (is (model/asserted?     (mk :asserted)))
    (is (model/proposed?     (mk :proposed)))
    (is (model/retracted?    (mk :retracted)))
    (is (model/superseded?   (mk :superseded)))
    (is (model/accepted?     (mk :accepted)))
    (is (model/rejected?     (mk :rejected)))
    (is (model/needs-review? (mk :needs-review)))
    (is (not (model/asserted? (mk :proposed))))
    (is (not (model/accepted? (mk :asserted))))))

(deftest in-force-and-pending
  (let [mk (fn [status] (model/assertion {:subject :r/id1 :predicate :p
                                          :value "v" :status status}))]
    (is (model/in-force? (mk :asserted)))
    (is (model/in-force? (mk :accepted)))
    (is (not (model/in-force? (mk :proposed))))
    (is (not (model/in-force? (mk :rejected))))
    (is (model/pending? (mk :proposed)))
    (is (model/pending? (mk :needs-review)))
    (is (not (model/pending? (mk :asserted))))
    (is (not (model/pending? (mk :rejected))))))

;; ---------------------------------------------------------------------------
;; Simple queries
;; ---------------------------------------------------------------------------

(deftest assertions-for-returns-matches
  (let [a1 (model/assertion {:subject :r/id1 :predicate :canon/title :value "A"})
        a2 (model/assertion {:subject :r/id1 :predicate :canon/title :value "B"})
        a3 (model/assertion {:subject :r/id1 :predicate :canon/agent :value "Hugo"})
        r  (model/record {:id :r/id1 :kind :book
                          :assertions [a1 a2 a3]})]
    (is (= 2 (count (model/assertions-for r :canon/title))))
    (is (= 1 (count (model/assertions-for r :canon/agent))))
    (is (= 0 (count (model/assertions-for r :canon/date))))
    (is (model/has-assertion? r :canon/title))
    (is (not (model/has-assertion? r :canon/date)))))

;; ---------------------------------------------------------------------------
;; Invalid data is rejected
;; ---------------------------------------------------------------------------

(deftest invalid-data-rejected
  (testing "record missing :kind is invalid"
    (is (not (model/valid-record? {:id :r/id1}))))
  (testing "assertion missing :value is invalid"
    (is (not (model/valid-assertion? {:subject :r/id1 :predicate :p}))))
  (testing "diagnostic with unknown severity is invalid"
    (is (not (model/valid-diagnostic? {:severity :catastrophic
                                       :code :x
                                       :subject :r/id1}))))
  (testing "confidence out of range is invalid"
    (let [bad (assoc (model/assertion {:subject :r/id1
                                       :predicate :p
                                       :value "v"})
                     :confidence 1.5)]
      (is (not (model/valid-assertion? bad)))))
  (testing "reference value without :value/target is invalid"
    (is (not (model/valid-value? {:value/kind :reference})))))
