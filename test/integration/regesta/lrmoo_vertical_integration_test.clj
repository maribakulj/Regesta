(ns regesta.lrmoo-vertical-integration-test
  "The sibling promised in `integration_test` (\"begins with import, ends with
   export\"): the full vertical on real BnF data —

     INTERMARC-SRU import (ADR 0007)
       -> WEMI FRBRisation (ADR 0016)
         -> LRMoo RDF export (ADR 0007 / 0013)

   with loss reported at *both* edges (ADR 0015) and the Manifestation exported as
   its real data.bnf ARK. Exercises the plugin *contracts* (importer / exporter
   closures), not just internal fns, against the Madame Bovary fixture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.diagnostics :as dx]
            [regesta.model :as model]
            [regesta.plugins.intermarc :as intermarc]
            [regesta.plugins.intermarc.frbrise :as frbrise]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.view :as view]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")

;; import (ADR 0007 contract) -> FRBRise (ADR 0016): the vertical, per record.
(def imported (intermarc/importer {} {:source/kind :file :source/value fixture}))
(def frbrised (mapv frbrise/frbrise (:records imported)))
(defn- showcase [] (first (filter #(= "ark:/12148/cb304403926" (:source %)) frbrised)))

(deftest import-honours-the-contract-and-parses-cleanly
  (testing "the importer returns the ADR 0007 shape and consistent records"
    (is (= 30 (count (:records imported))))
    (is (= [] (:diagnostics imported)))
    (is (every? model/valid-record? (:records imported)))
    (is (every? model/record-consistent? (:records imported)))))

(deftest frbrisation-builds-and-links-the-wemi-graph
  (let [r (showcase)]
    (is (= 1 (count (view/manifestations r))))
    (is (= 1 (count (view/expressions r))))
    (is (= 1 (count (view/works r))))
    (testing "Work -R3-> Expression <-R4- Manifestation, traversable"
      (let [w (:id (first (view/works r)))
            e (:id (first (view/expressions r)))
            m (:id (first (view/manifestations r)))]
        (is (= [e] (view/expressions-of r w)))
        (is (= [e] (view/expression-of r m)))))
    (testing "the vertical stays consistent and reports import-edge loss"
      (is (model/record-consistent? r))
      (let [imp (dx/losses (:diagnostics r))]
        (is (seq imp))
        (is (every? #(= :import (get-in % [:detail :loss/edge])) imp))))))

(deftest export-emits-real-iris-and-reports-export-loss
  (let [{:keys [output diagnostics]} (export/exporter {} frbrised)]
    (testing "the RDF carries the WEMI types and both links"
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/F3_Manifestation"))
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies"))
      (is (str/includes? output "http://iflastandards.info/ns/lrm/lrmoo/R3_is_realised_in")))
    (testing "the showcase Manifestation node is its real data.bnf ARK (not a urn)"
      (is (str/includes? output "<http://data.bnf.fr/ark:/12148/cb304403926>")))
    (testing "export-edge loss is reported for the dropped native predicates (ADR 0015)"
      (is (seq diagnostics))
      (is (every? #(= :loss/dropped (:code %)) diagnostics))
      (is (every? #(= :export (get-in % [:detail :loss/edge])) diagnostics))
      (is (contains? (set (map #(get-in % [:detail :loss/source-field]) diagnostics))
                     :intermarc/f245_a)))))

(deftest the-vertical-is-idempotent
  (testing "re-FRBRising the output mints nothing new (ADR 0008)"
    (let [r1 (showcase)
          r2 (frbrise/frbrise r1)]
      (is (= (:entities r1) (:entities r2)))
      (is (= (:assertions r1) (:assertions r2))))))
