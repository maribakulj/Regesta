(ns metalisp.rules-test
  "Tests for the rule DSL: schema validation, compilation, pattern
   matching, guards, production actions, and provenance tagging."
  (:require [clojure.test :refer [deftest is testing]]
            [metalisp.model :as model]
            [metalisp.rules :as rules]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- book-record [& {:keys [id title kind]
                       :or {id :r/book1 kind :book}}]
  (model/record
   {:id id
    :kind kind
    :assertions (cond-> []
                  title (conj (model/assertion {:subject id
                                                :predicate :canon/title
                                                :value title})))}))

(defn- run [rule record]
  (rules/apply-rule (rules/compile-rule rule) record))

;; ---------------------------------------------------------------------------
;; Variables and helpers
;; ---------------------------------------------------------------------------

(deftest variable-detection
  (is (rules/variable? '?r))
  (is (rules/variable? '?title))
  (is (not (rules/variable? 'r)))
  (is (not (rules/variable? :?r)))
  (is (not (rules/variable? "?r"))))

(deftest wildcard-detection
  (is (rules/wildcard? '_))
  (is (not (rules/wildcard? '?_)))
  (is (not (rules/wildcard? '-))))

;; ---------------------------------------------------------------------------
;; Triple view of a record
;; ---------------------------------------------------------------------------

(deftest record-triples-covers-struct-and-assertions
  (let [r (book-record :id :r/r1 :kind :book :title "Les Misérables")
        triples (rules/record-triples r)]
    (is (contains? (set triples) [:r/r1 :meta/id :r/r1]))
    (is (contains? (set triples) [:r/r1 :meta/kind :book]))
    (is (contains? (set triples) [:r/r1 :canon/title "Les Misérables"]))))

;; ---------------------------------------------------------------------------
;; Schema validation
;; ---------------------------------------------------------------------------

(deftest rule-schema-accepts-valid-rule
  (is (rules/valid-rule?
       {:id :rule/t
        :phase :validate
        :match '[[?r :meta/kind :book]]
        :produce {:diagnostic {:severity :info :code :seen-book :subject '?r}}})))

(deftest rule-schema-rejects-unknown-phase
  (is (not (rules/valid-rule?
            {:id :rule/t
             :phase :gibberish
             :match '[[?r :meta/kind :book]]
             :produce {}}))))

(deftest rule-schema-rejects-bad-match-shape
  (is (not (rules/valid-rule?
            {:id :rule/t
             :phase :validate
             :match ['(bogus-predicate ?r)]
             :produce {}}))))

;; ---------------------------------------------------------------------------
;; Compile-time checks
;; ---------------------------------------------------------------------------

(deftest compile-rejects-unbound-variable-in-produce
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule
                {:id :rule/t
                 :phase :validate
                 :match '[[?r :meta/kind :book]]
                 :produce {:diagnostic {:severity :warning
                                        :code :missing-thing
                                        :subject '?missing}}}))))

(deftest compile-rejects-malformed-rule
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule {:id :rule/t
                                    :phase :validate}))))

(deftest compile-rejects-unknown-action
  (is (thrown? clojure.lang.ExceptionInfo
               (rules/compile-rule
                {:id :rule/t
                 :phase :validate
                 :match '[[?r :meta/kind :book]]
                 :produce {:emit {:foo '?r}}}))))

;; ---------------------------------------------------------------------------
;; Basic pattern matching
;; ---------------------------------------------------------------------------

(deftest match-binds-variables-from-struct
  (let [rule {:id :rule/t1
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:diagnostic {:severity :info
                                     :code :saw-book
                                     :subject '?r}}}
        r    (book-record :id :r/abc)
        outs (run rule r)]
    (is (= 1 (count outs)))
    (is (= :diagnostic (:kind (first outs))))
    (is (= :r/abc (get-in (first outs) [:value :subject])))))

(deftest match-returns-no-rows-when-pattern-fails
  (let [rule {:id :rule/t2
              :phase :validate
              :match '[[?r :meta/kind :journal]]
              :produce {:diagnostic {:severity :info
                                     :code :saw-journal
                                     :subject '?r}}}
        r    (book-record :kind :book)]
    (is (empty? (run rule r)))))

(deftest match-binds-value-from-assertion
  (let [rule {:id :rule/t3
              :phase :validate
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?title]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/normalized-title
                                 :value '?title}}}
        r    (book-record :id :r/x :title "Les Misérables")
        outs (run rule r)]
    (is (= 1 (count outs)))
    (is (= :assertion (:kind (first outs))))
    (is (= "Les Misérables" (get-in (first outs) [:value :value])))))

(deftest literal-position-matching
  (let [rule {:id :rule/t4
              :phase :validate
              :match '[[?r :canon/title "Les Misérables"]]
              :produce {:diagnostic {:severity :info :code :match :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/y :title "Les Misérables")))))
    (is (= 0 (count (run rule (book-record :id :r/z :title "Other")))))))

(deftest wildcard-matches-without-binding
  (let [rule {:id :rule/t5
              :phase :validate
              :match '[[?r :canon/title _]]
              :produce {:diagnostic {:severity :info :code :has-title :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/k :title "Any")))))))

;; ---------------------------------------------------------------------------
;; Guards (stdlib predicates)
;; ---------------------------------------------------------------------------

(deftest absent-guard-filters-rows
  (let [rule {:id :rule/title-required
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (absent? ?r :canon/title)]
              :produce {:diagnostic {:severity :error
                                     :code :missing-title
                                     :subject '?r}}}]
    (testing "book without title triggers diagnostic"
      (let [outs (run rule (book-record :id :r/nt))]
        (is (= 1 (count outs)))
        (is (= :missing-title (get-in (first outs) [:value :code])))))
    (testing "book with title does not trigger"
      (is (empty? (run rule (book-record :id :r/wt :title "Something")))))))

(deftest present-guard
  (let [rule {:id :rule/t6
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (present? ?r :canon/title)]
              :produce {:diagnostic {:severity :info :code :has-title :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :title "X")))))
    (is (empty? (run rule (book-record))))))

(deftest matches-guard
  (let [rule {:id :rule/t7
              :phase :validate
              :match '[[?r :canon/title ?t]
                       (matches? ?t "^Les")]
              :produce {:diagnostic {:severity :info :code :starts-with-les :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :id :r/a :title "Les Misérables")))))
    (is (empty? (run rule (book-record :id :r/b :title "Notre Dame"))))))

(deftest in-guard
  (let [rule {:id :rule/t8
              :phase :validate
              :match '[[?r :meta/kind ?k]
                       (in? ?k [:book :journal])]
              :produce {:diagnostic {:severity :info :code :known-kind :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :kind :book)))))
    (is (empty? (run rule (book-record :kind :sculpture))))))

(deftest equality-guard-filters
  (let [rule {:id :rule/t9
              :phase :validate
              :match '[[?r :meta/kind ?k]
                       (= ?k :book)]
              :produce {:diagnostic {:severity :info :code :is-book :subject '?r}}}]
    (is (= 1 (count (run rule (book-record :kind :book)))))
    (is (empty? (run rule (book-record :kind :journal))))))

;; ---------------------------------------------------------------------------
;; Production actions
;; ---------------------------------------------------------------------------

(deftest assert-production-tags-provenance
  (let [rule {:id :rule/annotate
              :phase :normalize
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/seen
                                 :value true}}}
        out  (first (run rule (book-record :id :r/p)))]
    (is (= :assertion (:kind out)))
    (is (= :asserted (get-in out [:value :status])))
    (is (= :rule/annotate (get-in out [:value :provenance :rule])))
    (is (= :normalize (get-in out [:value :provenance :pass])))))

(deftest diagnostic-production-tags-provenance
  (let [rule {:id :rule/dx
              :phase :validate
              :match '[[?r :meta/kind :book]
                       (absent? ?r :canon/title)]
              :produce {:diagnostic {:severity :error
                                     :code :missing-title
                                     :subject '?r
                                     :message "No title."}}}
        out  (first (run rule (book-record :id :r/x)))]
    (is (= :diagnostic (:kind out)))
    (is (= :rule/dx (get-in out [:value :provenance :rule])))
    (is (= :validate (get-in out [:value :provenance :pass])))
    (is (= "No title." (get-in out [:value :message])))))

(deftest repair-production
  (let [rule {:id :rule/fix
              :phase :repair
              :match '[[?r :meta/kind :book]]
              :produce {:repair {:description "Attach canonical title."
                                 :operation :copy-from
                                 :basis :dc/alternative}}}
        out  (first (run rule (book-record)))]
    (is (= :repair (:kind out)))
    (is (= :copy-from (get-in out [:value :operation])))))

(deftest multiple-actions-per-rule
  (let [rule {:id :rule/multi
              :phase :validate
              :match '[[?r :meta/kind :book]]
              :produce {:assert {:subject '?r :predicate :canon/seen :value true}
                        :diagnostic {:severity :info
                                     :code :seen-book
                                     :subject '?r}}}
        outs (run rule (book-record))]
    (is (= 2 (count outs)))
    (is (= #{:assertion :diagnostic} (set (map :kind outs))))))

;; ---------------------------------------------------------------------------
;; Joins: multiple patterns contribute shared bindings
;; ---------------------------------------------------------------------------

(deftest multiple-patterns-cartesian-join
  (let [r (model/record
           {:id :r/m :kind :book
            :assertions [(model/assertion {:subject :r/m :predicate :canon/title :value "A"})
                         (model/assertion {:subject :r/m :predicate :canon/title :value "B"})]})
        rule {:id :rule/join
              :phase :validate
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?t]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/seen-title
                                 :value '?t}}}
        outs (run rule r)]
    (is (= 2 (count outs)))
    (is (= #{"A" "B"} (set (map #(get-in % [:value :value]) outs))))))

;; ---------------------------------------------------------------------------
;; The produced assertions validate against the model schema
;; ---------------------------------------------------------------------------

(deftest produced-assertions-are-schema-valid
  (let [rule {:id :rule/emit
              :phase :infer
              :match '[[?r :meta/kind :book]
                       [?r :canon/title ?t]]
              :produce {:assert {:subject '?r
                                 :predicate :canon/normalized-title
                                 :value '?t}}}
        outs (run rule (book-record :id :r/v :title "Title"))]
    (is (every? #(model/valid-assertion? (:value %))
                (filter #(= :assertion (:kind %)) outs)))))
