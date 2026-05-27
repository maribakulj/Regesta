(ns regesta.plugins.shape-test
  "Unit tests for the generic shape adapter, JSON walker half
   (Sprint 5 M5.A). Covers flat and qualified ingest, the JSON-LD
   `@value` convention, predicate key conversion, mapping-driven
   filtering, fragment id stability and the consistency guarantee on
   the produced Record. XML and cross-format equivalence land in M5.B."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.shape :as shape]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def title-flat-mapping
  [{:mapping/id   :map/dc-title
    :mapping/from :dc/title
    :mapping/to   :canon/title}])

(def title-qualified-mapping
  [{:mapping/id        :map/dc-title
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/qualifier {:from (keyword "@language") :as :canon/lang}}])

(def opts
  {:record-id :record/r1 :kind :test})

(defn- assertions-on
  "Pluck the assertions of `record` whose subject equals `s`."
  [record s]
  (filterv #(= s (:subject %)) (:assertions record)))

(defn- assertions-for-predicate
  "Pluck the assertions of `record` whose predicate equals `p`."
  [record p]
  (filterv #(= p (:predicate %)) (:assertions record)))

;; ---------------------------------------------------------------------------
;; Flat ingest
;; ---------------------------------------------------------------------------

(deftest flat-primitive-value-becomes-one-assertion-on-record
  (let [record (shape/ingest-json {"dc:title" "Les Misérables"}
                                  title-flat-mapping opts)]
    (is (= 1 (count (:assertions record))))
    (is (= [] (:fragments record)))
    (let [a (-> record :assertions first)]
      (is (= :record/r1 (:subject a)))
      (is (= :dc/title  (:predicate a)))
      (is (= "Les Misérables" (:value a))))))

(deftest flat-array-of-primitives-becomes-multiple-assertions
  (let [record (shape/ingest-json {"dc:title" ["A" "B" "C"]}
                                  title-flat-mapping opts)]
    (is (= 3 (count (:assertions record))))
    (is (= ["A" "B" "C"] (mapv :value (:assertions record))))
    (is (every? #(= :dc/title (:predicate %)) (:assertions record)))))

(deftest flat-ingest-emits-ingest-phase-provenance
  (let [record (shape/ingest-json {"dc:title" "X"} title-flat-mapping opts)]
    (is (= :ingest (-> record :assertions first :provenance :pass)))))

;; ---------------------------------------------------------------------------
;; Qualified ingest
;; ---------------------------------------------------------------------------

(deftest qualified-single-object-mints-one-fragment-with-three-assertions
  (let [record (shape/ingest-json
                {"dc:title" {"@value" "Les Misérables" "@language" "fr"}}
                title-qualified-mapping opts)]
    (is (= 1 (count (:fragments record))))
    (let [frag-id (-> record :fragments first :id)]
      (testing "record-level reference"
        (let [refs (filterv #(and (= :record/r1 (:subject %))
                                  (= :dc/title  (:predicate %)))
                            (:assertions record))]
          (is (= 1 (count refs)))
          (is (= (model/reference frag-id) (:value (first refs))))))
      (testing "fragment value coord"
        (let [vals (filterv #(and (= frag-id   (:subject %))
                                  (= :dc/title (:predicate %)))
                            (:assertions record))]
          (is (= 1 (count vals)))
          (is (= "Les Misérables" (:value (first vals))))))
      (testing "fragment qualifier coord"
        (let [qual (filterv #(and (= frag-id (:subject %))
                                  (= (keyword "@language") (:predicate %)))
                            (:assertions record))]
          (is (= 1 (count qual)))
          (is (= "fr" (:value (first qual)))))))))

(deftest qualified-array-of-objects-mints-one-fragment-per-element
  (let [record (shape/ingest-json
                {"dc:title" [{"@value" "Les Misérables" "@language" "fr"}
                             {"@value" "The Wretched"    "@language" "en"}]}
                title-qualified-mapping opts)
        frag-ids (mapv :id (:fragments record))]
    (is (= 2 (count (:fragments record))))
    (is (= [:frag/record.r1.dc-title.0 :frag/record.r1.dc-title.1]
           frag-ids)
        "fragment ids encode the predicate and per-predicate occurrence index per ADR 0012")
    (testing "record carries two references in source order"
      (let [refs (filterv #(= :dc/title (:predicate %)) (assertions-on record :record/r1))]
        (is (= 2 (count refs)))
        (is (= (mapv model/reference frag-ids)
               (mapv :value refs)))))
    (testing "each fragment carries its value and qualifier coords"
      (let [f0-vals (filterv #(= :dc/title (:predicate %))
                             (assertions-on record :frag/record.r1.dc-title.0))
            f1-qual (filterv #(= (keyword "@language") (:predicate %))
                             (assertions-on record :frag/record.r1.dc-title.1))]
        (is (= "Les Misérables" (-> f0-vals first :value)))
        (is (= "en"             (-> f1-qual first :value)))))))

(deftest qualified-bare-primitive-tolerated-no-qualifier-coord
  (testing "a primitive at a qualified position mints a fragment with just the value coord"
    (let [record (shape/ingest-json {"dc:title" "Anonymous"}
                                    title-qualified-mapping opts)
          frag-id (-> record :fragments first :id)]
      (is (= 1 (count (:fragments record))))
      (testing "reference still emitted on record"
        (is (some #(and (= :dc/title (:predicate %))
                        (= (model/reference frag-id) (:value %)))
                  (assertions-on record :record/r1))))
      (testing "fragment carries the value coord"
        (is (some #(and (= :dc/title (:predicate %))
                        (= "Anonymous" (:value %)))
                  (assertions-on record frag-id))))
      (testing "no qualifier coord present"
        (is (not (some #(= (keyword "@language") (:predicate %))
                       (assertions-on record frag-id))))))))

(deftest qualified-object-missing-value-emits-reference-only
  (testing "a qualified sub-object without @value still emits the reference and the qualifier if present"
    (let [record (shape/ingest-json
                  {"dc:title" {"@language" "fr"}}
                  title-qualified-mapping opts)
          frag-id (-> record :fragments first :id)]
      (is (= 1 (count (:fragments record))))
      (is (some #(= (model/reference frag-id) (:value %))
                (assertions-on record :record/r1)))
      (testing "no value coord on the fragment (the sub-object had no @value)"
        (is (not (some #(= :dc/title (:predicate %))
                       (assertions-on record frag-id)))))
      (testing "qualifier coord still emitted"
        (is (some #(= (keyword "@language") (:predicate %))
                  (assertions-on record frag-id)))))))

;; ---------------------------------------------------------------------------
;; Predicate key conversion
;; ---------------------------------------------------------------------------

(deftest prefixed-keys-split-on-first-colon
  (let [mapping [{:mapping/id :map/x :mapping/from :dc/title :mapping/to :canon/x}]
        record  (shape/ingest-json {"dc:title" "v"} mapping opts)]
    (is (= 1 (count (:assertions record))))
    (is (= :dc/title (-> record :assertions first :predicate)))))

(deftest unprefixed-keys-become-unnamespaced-keywords
  (let [mapping [{:mapping/id :map/x :mapping/from :title :mapping/to :canon/x}]
        record  (shape/ingest-json {"title" "v"} mapping opts)]
    (is (= :title (-> record :assertions first :predicate)))))

(deftest keyword-keys-pass-through-idempotent
  (testing "if the caller already keyword-keyed the input, normalization is a no-op"
    (let [mapping [{:mapping/id :map/x :mapping/from :dc/title :mapping/to :canon/x}]
          record  (shape/ingest-json {:dc/title "v"} mapping opts)]
      (is (= :dc/title (-> record :assertions first :predicate))))))

(deftest jsonld-at-keys-parse-to-at-prefixed-keywords
  (testing "JSON-LD's `@language` becomes `(keyword \"@language\")` for the qualifier predicate"
    (let [record (shape/ingest-json
                  {"dc:title" {"@value" "X" "@language" "fr"}}
                  title-qualified-mapping opts)
          frag-id (-> record :fragments first :id)]
      (is (some #(= (keyword "@language") (:predicate %))
                (assertions-on record frag-id))))))

;; ---------------------------------------------------------------------------
;; Mapping-driven filtering
;; ---------------------------------------------------------------------------

(deftest predicates-not-in-mapping-are-ignored
  (let [record (shape/ingest-json
                {"dc:title" "X" "dc:creator" "Hugo" "dc:nope" "skip me"}
                title-flat-mapping opts)]
    (is (= 1 (count (:assertions record))))
    (is (= :dc/title (-> record :assertions first :predicate)))))

(deftest empty-mapping-produces-empty-record-assertions
  (let [record (shape/ingest-json {"dc:title" "X" "dc:creator" "Y"}
                                  [] opts)]
    (is (= [] (:assertions record)))
    (is (= [] (:fragments record)))
    (is (= :record/r1 (:id record)))))

(deftest empty-json-tree-produces-empty-record
  (let [record (shape/ingest-json {} title-flat-mapping opts)]
    (is (= [] (:assertions record)))
    (is (= [] (:fragments record)))))

(deftest non-map-top-level-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"top-level map"
                        (shape/ingest-json [1 2 3] title-flat-mapping opts)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"top-level map"
                        (shape/ingest-json "string" title-flat-mapping opts))))

;; ---------------------------------------------------------------------------
;; Mixed flat + qualified
;; ---------------------------------------------------------------------------

(deftest one-record-can-mix-flat-and-qualified-predicates
  (let [mapping (into title-qualified-mapping
                      [{:mapping/id   :map/id
                        :mapping/from :dc/identifier
                        :mapping/to   :canon/id}])
        record  (shape/ingest-json
                 {"dc:title"      {"@value" "Hugo" "@language" "fr"}
                  "dc:identifier" "oai:123"}
                 mapping opts)]
    (is (= 1 (count (:fragments record))) "one fragment from the qualified title")
    (testing "the flat identifier rides on the record directly"
      (let [ids (assertions-for-predicate record :dc/identifier)]
        (is (= 1 (count ids)))
        (is (= :record/r1 (-> ids first :subject)))
        (is (= "oai:123"  (-> ids first :value)))))))

;; ---------------------------------------------------------------------------
;; Fragment id stability
;; ---------------------------------------------------------------------------

(deftest fragment-ids-are-reproducible-across-runs
  (testing "same input, same fragment ids — required for idempotent merge (ADR 0008)"
    (let [tree {"dc:title" [{"@value" "A" "@language" "fr"}
                            {"@value" "B" "@language" "en"}]}
          r1   (shape/ingest-json tree title-qualified-mapping opts)
          r2   (shape/ingest-json tree title-qualified-mapping opts)]
      (is (= (mapv :id (:fragments r1))
             (mapv :id (:fragments r2)))))))

(deftest fragment-ids-encode-record-and-locator-per-adr-0012
  (let [record (shape/ingest-json
                {"dc:title" [{"@value" "A" "@language" "fr"}
                             {"@value" "B" "@language" "en"}]}
                title-qualified-mapping opts)]
    (is (= [:frag/record.r1.dc-title.0 :frag/record.r1.dc-title.1]
           (mapv :id (:fragments record))))))

;; ---------------------------------------------------------------------------
;; Output consistency
;; ---------------------------------------------------------------------------

(deftest output-record-is-consistent
  (testing "every assertion's subject is the record id or one of the minted fragment ids"
    (let [tree   {"dc:title"      [{"@value" "A" "@language" "fr"}
                                   {"@value" "B" "@language" "en"}]
                  "dc:identifier" ["x" "y"]}
          mapping (into title-qualified-mapping
                        [{:mapping/id   :map/id
                          :mapping/from :dc/identifier
                          :mapping/to   :canon/id}])
          record (shape/ingest-json tree mapping opts)]
      (is (model/record-consistent? record))
      (is (nil? (model/explain-consistency record))))))

;; ---------------------------------------------------------------------------
;; Record envelope
;; ---------------------------------------------------------------------------

(deftest record-carries-id-kind-and-source
  (let [record (shape/ingest-json
                {"dc:title" "X"} title-flat-mapping
                {:record-id :record/r1 :kind :document
                 :source {:file "test.json" :hash "abc"}})]
    (is (= :record/r1 (:id record)))
    (is (= :document  (:kind record)))
    (is (= {:file "test.json" :hash "abc"} (:source record)))))

(deftest source-is-omitted-when-not-provided
  (let [record (shape/ingest-json {} title-flat-mapping
                                  {:record-id :record/r1 :kind :test})]
    (is (not (contains? record :source)))))

;; ---------------------------------------------------------------------------
;; Document order within a predicate
;; ---------------------------------------------------------------------------

(deftest occurrence-indices-follow-array-order
  (testing "JSON arrays preserve order, so fragment ids reflect source ordering"
    (let [record (shape/ingest-json
                  {"dc:title" [{"@value" "first"}
                               {"@value" "second"}
                               {"@value" "third"}]}
                  title-qualified-mapping opts)]
      (is (= ["first" "second" "third"]
             (mapv (fn [f] (-> (assertions-on record (:id f))
                               (->> (filterv #(= :dc/title (:predicate %))))
                               first
                               :value))
                   (:fragments record)))))))
