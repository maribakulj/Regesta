(ns regesta.plugins.intermarc.frbrise-test
  "End-to-end on real BnF data (WP-3, ADR 0016): INTERMARC import → FRBRisation
   → typed view → RDF export, plus lookup-based clustering and idempotency."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.intermarc :as intermarc]
            [regesta.plugins.intermarc.frbrise :as frbrise]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.view :as view]))

(def fixture
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml")
(def records (intermarc/ingest (slurp fixture) {}))

(defn- by-id [recs id] (first (filter #(= id (:id %)) recs)))
(defn- has-145? [r] (some #(= :intermarc/f145_3 (:predicate %)) (:assertions r)))

(deftest manifestation-and-expression-minted-and-linked
  (let [r (frbrise/frbrise (by-id records :bnf/cb304403926))]
    (is (model/valid-record? r))
    (is (model/record-consistent? r))
    (is (= 1 (count (view/manifestations r))))
    (is (= 1 (count (view/expressions r))))
    (testing "the Manifestation embodies the Expression (R4), both directions"
      (let [m (:id (first (view/manifestations r)))
            e (:id (first (view/expressions r)))]
        (is (= [e] (view/expression-of r m)))
        (is (= [m] (view/manifestations-of r e)))))))

(deftest clustering-by-the-embedded-expression-id
  (testing "manifestations sharing f145_3 mint the SAME Expression id (no batch state)"
    (let [with-145 (filter has-145? records)
          exprs    (->> with-145
                        (map frbrise/frbrise)
                        (map #(:id (first (view/expressions %))))
                        distinct)]
      (is (<= 20 (count with-145)))   ; most of the 30 carry the embedded link
      (is (= 1 (count exprs))))))      ; ...and all collapse to one Expression

(deftest fallback-record-gets-manifestation-only
  (testing "a record without f145 yields a Manifestation but no Expression (bridging is future)"
    (let [r (frbrise/frbrise (first (remove has-145? records)))]
      (is (= 1 (count (view/manifestations r))))
      (is (empty? (view/expressions r))))))

(deftest frbrisation-is-idempotent
  (testing "re-running mints nothing new (ADR 0008)"
    (let [r1 (frbrise/frbrise (by-id records :bnf/cb304403926))
          r2 (frbrise/frbrise r1)]
      (is (= (:entities r1) (:entities r2)))
      (is (= (:assertions r1) (:assertions r2))))))

(deftest exports-to-rdf
  (testing "the FRBRised manifestation exports as N-Triples (F3 type, F2 type, R4 link)"
    (let [nt (export/->ntriples (frbrise/frbrise (by-id records :bnf/cb304403926)))]
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/R4_embodies")))))
