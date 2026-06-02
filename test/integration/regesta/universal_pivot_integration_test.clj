(ns regesta.universal-pivot-integration-test
  "Universality proof (ADR 0013): a *non-MARC* source reaches the LRMoo pivot.

   Two Dublin Core records — one JSON-LD, one XML — each run the full chain
   shape-adapter ingest (ADR 0007) -> mapping to the canonical floor (ADR 0009) ->
   canonical→WEMI projection (ADR 0013) -> RDF, with no INTERMARC anywhere. They
   converge on the *same* Work and Expression by content alone (ADR 0008 / 0012 at
   the WEMI level), proving the pivot is format-agnostic — a real hub, not an
   INTERMARC converter in disguise."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.diagnostics :as dx]
            [regesta.plugins :as plug]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.project :as project]
            [regesta.plugins.lrmoo.view :as view]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.shape :as shape]
            [regesta.runtime :as runtime]))

(def ^:private dc-uri "http://purl.org/dc/elements/1.1/")
(def ^:private xml-uri "http://www.w3.org/XML/1998/namespace")

(def ^:private json-source
  (str "{\"dc:title\":[{\"@value\":\"Les Misérables\",\"@language\":\"fr\"}],"
       "\"dc:creator\":\"Victor Hugo\",\"dc:date\":\"1862\"}"))

(def ^:private xml-source
  (str "<record xmlns:dc=\"" dc-uri "\">"
       "<dc:title xml:lang=\"fr\">Les Misérables</dc:title>"
       "<dc:creator>Victor Hugo</dc:creator>"
       "<dc:date>1862</dc:date>"
       "</record>"))

(def ^:private json-mapping
  [{:mapping/id :map/title-json :mapping/from :dc/title :mapping/to :canon/title
    :mapping/qualifier {:from (keyword "@language") :as :canon/lang}}
   {:mapping/id :map/creator-json :mapping/from :dc/creator :mapping/to :canon/agent}
   {:mapping/id :map/date-json :mapping/from :dc/date :mapping/to :canon/date}])

(def ^:private xml-mapping
  [{:mapping/id :map/title-xml :mapping/from :dc/title :mapping/to :canon/title
    :mapping/qualifier {:from :xml/lang :as :canon/lang}}
   {:mapping/id :map/creator-xml :mapping/from :dc/creator :mapping/to :canon/agent}
   {:mapping/id :map/date-xml :mapping/from :dc/date :mapping/to :canon/date}])

(defn- project-source
  "Full non-MARC vertical for one `plugin`/`source`: ingest -> normalize to
   canonical -> canonical→WEMI projection."
  [plugin opts source]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        rec      (-> ((:importer plugin) opts source) :records first)
        canon    (:record (runtime/run-phase rec compiled :normalize))]
    (project/project canon)))

(def ^:private jw
  (project-source (shape/shape-json-plugin {:id :plugin/dc-json :mapping json-mapping})
                  {:record-id :record/json1 :kind :document} json-source))

(def ^:private xw
  (project-source (shape/shape-xml-plugin {:id :plugin/dc-xml :mapping xml-mapping
                                           :aliases {:dc dc-uri :xml xml-uri}})
                  {:record-id :record/xml1 :kind :document} xml-source))

(deftest a-non-marc-source-reaches-the-wemi-pivot
  (testing "a Dublin Core record (no INTERMARC) projects to a full WEMI chain"
    (doseq [w [jw xw]]
      (is (= 1 (count (view/manifestations w))))
      (is (= 1 (count (view/expressions w))))
      (is (= 1 (count (view/works w)))))))

(deftest json-and-xml-converge-on-the-same-work-and-expression
  (testing "content-deterministic identity makes two formats cluster (ADR 0008 / 0012)"
    (is (= (:id (first (view/works jw)))       (:id (first (view/works xw)))))
    (is (= (:id (first (view/expressions jw))) (:id (first (view/expressions xw)))))
    (testing "...yet they are distinct Manifestations (distinct source records)"
      (is (not= (:id (first (view/manifestations jw)))
                (:id (first (view/manifestations xw))))))))

(deftest the-projection-reports-dropped-canonical-loss
  (testing ":canon/date is on the canonical floor but not in WEMI -> :import loss (ADR 0015)"
    (let [ls     (dx/losses (:diagnostics jw))
          fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
      (is (seq ls))
      (is (contains? fields :canon/date))
      (is (every? #(= :import (get-in % [:detail :loss/edge])) ls)))))

(deftest projected-wemi-exports-to-rdf
  (testing "the canonical-derived WEMI graph serialises as N-Triples"
    (let [nt (export/->ntriples jw)]
      (is (str/includes? nt "lrmoo/F1_Work"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/R4_embodies")))))

(def ^:private json-bilingual
  (str "{\"dc:title\":[{\"@value\":\"Les Misérables\",\"@language\":\"fr\"},"
       "{\"@value\":\"The Wretched\",\"@language\":\"en\"}],"
       "\"dc:creator\":\"Victor Hugo\"}"))

(deftest parallel-language-titles-under-specify-the-expression-end-to-end
  (testing "a bilingual DC record through the real vertical -> one Expression -> :under-specified"
    (let [w  (project-source (shape/shape-json-plugin {:id :plugin/dc-bi :mapping json-mapping})
                             {:record-id :record/bi :kind :document} json-bilingual)
          us (filterv #(= :loss/under-specified (:code %)) (:diagnostics w))]
      (is (= 1 (count (view/expressions w))))
      (is (= 1 (count us)))
      (is (= :canon/lang (get-in (first us) [:detail :loss/source-field]))))))
