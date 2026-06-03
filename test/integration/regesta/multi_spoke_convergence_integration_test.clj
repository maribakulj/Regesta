(ns regesta.multi-spoke-convergence-integration-test
  "WP-4 capstone — three real spokes converge on one pivot.

   INTERMARC (BnF MARC, real Madame Bovary), MARC21 (LoC MARCXML, real Ray
   Charles) and Dublin Core (W3C XML) are registered in **one** registry, each
   reaching the **same** LRMoo pivot (WEMI) and the **same** loss model
   (ADR 0015) — one unified loss report spanning all three native vocabularies.

   The two-rung ladder (ADR 0013) is visible here: INTERMARC takes the *enriched*
   projection (`frbrise`, via the 145 $3 authority link), so its Work identity is
   authority-based; DC and MARC21 take the *floor* projection (string key), so two
   floor records of the same creator+title collapse to the **same** Work id —
   content-addressed identity is format-independent on the floor. Converging
   INTERMARC's authority identity with the floor's string key would need name-form
   reconciliation, the entity-resolution recall ceiling measured in
   `docs/eval/entity-resolution.md`; that is deliberately not claimed here."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.diagnostics :as dx]
            [regesta.loss-report :as lr]
            [regesta.plugins :as plug]
            [regesta.plugins.dc :as dc]
            [regesta.plugins.intermarc :as intermarc]
            [regesta.plugins.intermarc.frbrise :as frbrise]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.project :as project]
            [regesta.plugins.lrmoo.view :as view]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.marc21 :as marc21]
            [regesta.runtime :as runtime]))

;; --- real fixtures ----------------------------------------------------------

(def ^:private intermarc-fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
(def ^:private marc21-fixture
  (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"))
(def ^:private dc-fixture
  (slurp "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"))

(defn- intermarc-showcase []
  (->> (:records (intermarc/importer {} {:source/kind :file :source/value intermarc-fixture}))
       (filter #(= "ark:/12148/cb304403926" (:source %)))
       first
       frbrise/frbrise))

(defn- floor-project
  "The floor route a host takes for a mapping-bearing spoke: register, compile its
   mapping, ingest one record, normalise to the canonical floor, project to WEMI.
   Returns `{:wemi :ingest}`."
  [plugin opts src]
  (let [reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} ((:importer plugin) opts src)
        canon    (:record (runtime/run-phase (first records) compiled :normalize))]
    {:wemi (project/project canon) :ingest diagnostics}))

;; --- Part 1: three formats, one registry, one pivot, one loss report --------

(deftest three-spokes-coexist-in-one-registry
  (testing "INTERMARC + MARC21 + DC register together and their mappings compile as one set"
    (let [reg (reduce plug/register plug/empty-registry
                      [intermarc/plugin marc21/plugin dc/plugin])]
      (is (= #{:regesta/intermarc :regesta/marc21 :regesta/dublin-core}
             (set (plug/registered-ids reg))))
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))

(deftest every-format-reaches-the-same-pivot
  (testing "all three real records yield the same LRMoo WEMI shape (F3 + F2 + F1)"
    (let [im  (intermarc-showcase)
          m21 (:wemi (floor-project marc21/plugin {} marc21-fixture))      ; Ray Charles (first)
          dc  (:wemi (floor-project dc/plugin {:record-id :dc/example1} dc-fixture))]
      (doseq [[label w] [["intermarc" im] ["marc21" m21] ["dc" dc]]]
        (is (= 1 (count (view/manifestations w))) (str label " manifestation"))
        (is (= 1 (count (view/expressions w)))    (str label " expression"))
        (is (= 1 (count (view/works w)))          (str label " work"))))))

(deftest one-loss-report-spans-all-three-vocabularies
  (testing "a single ADR 0015 report aggregates loss from INTERMARC, MARC21 and DC"
    (let [im   (intermarc-showcase)
          m21  (:wemi (floor-project marc21/plugin {} marc21-fixture))
          dcr  (floor-project dc/plugin {:record-id :dc/example1} dc-fixture)
          dc   (:wemi dcr)
          import-loss (concat (dx/collect im) (dx/collect m21) (dx/collect dc)
                              (:ingest dcr))                          ; DC's report-at-ingest
          export-loss (mapcat export/export-losses [im m21 dc])       ; LRMoo can't express natives
          report (lr/conversion-report (concat import-loss export-loss) {:records 3})
          nss    (set (map namespace (:source-fields report)))]
      (testing "one report, three native vocabularies present"
        (is (contains? nss "intermarc"))
        (is (contains? nss "marc21"))
        (is (contains? nss "dc"))         ; DC's unmodelled-element ingest loss
        (is (contains? nss "canon")))     ; floor fields dropped at projection
      (testing "both edges accounted, over the three records"
        (is (pos? (get-in report [:by-edge :import :total])))
        (is (pos? (get-in report [:by-edge :export :total])))
        (is (= 3 (:records report)))))))

;; --- Part 2: content-addressed identity is format-independent (floor) -------

(def ^:private dc-hugo
  (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
       "<dc:title>Les Misérables</dc:title>"
       "<dc:creator>Victor Hugo</dc:creator>"
       "</metadata>"))

(def ^:private marc21-hugo
  (str "<record xmlns=\"http://www.loc.gov/MARC21/slim\">"
       "<controlfield tag=\"001\">hugo-lm-1</controlfield>"
       "<datafield tag=\"100\" ind1=\"1\" ind2=\" \"><subfield code=\"a\">Victor Hugo</subfield></datafield>"
       "<datafield tag=\"245\" ind1=\"1\" ind2=\"0\"><subfield code=\"a\">Les Misérables</subfield></datafield>"
       "</record>"))

(deftest floor-formats-content-converge-on-the-same-work
  (testing "a DC and a MARC21 record of the same creator+title mint the SAME Work + Expression"
    (let [dc-w  (:wemi (floor-project dc/plugin {:record-id :dc/hugo} dc-hugo))
          m21-w (:wemi (floor-project marc21/plugin {} marc21-hugo))
          dc-work  (:id (first (view/works dc-w)))
          m21-work (:id (first (view/works m21-w)))]
      (is (some? dc-work))
      (is (= dc-work m21-work))                                       ; format-independent identity
      (is (= (:id (first (view/expressions dc-w)))
             (:id (first (view/expressions m21-w)))))
      (testing "...yet the Manifestations stay distinct (distinct source records)"
        (is (not= (:id (first (view/manifestations dc-w)))
                  (:id (first (view/manifestations m21-w)))))))))
