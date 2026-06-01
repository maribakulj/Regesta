(ns regesta.plugins.intermarc.frbrise-test
  "End-to-end on real BnF data (WP-3, ADR 0016): INTERMARC import → FRBRisation
   → typed view → RDF export, plus lookup-based clustering and idempotency."
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
  (testing "manifestations sharing f145_3 collapse to ONE Expression and Work (no batch state)"
    (let [frbrised (map frbrise/frbrise (filter has-145? records))
          exprs    (->> frbrised (map #(:id (first (view/expressions %)))) distinct)
          works    (->> frbrised (keep #(:id (first (view/works %)))) distinct)]
      (is (<= 20 (count (filter has-145? records))))  ; most of the 30 carry the link
      (is (= 1 (count exprs)))                          ; ...one Expression (by f145_3)
      (is (= 1 (count works))))))                       ; ...one Work (by author + title)

(deftest work-minted-and-realised-in-expression
  (let [r (frbrise/frbrise (by-id records :bnf/cb304403926))]
    (is (= 1 (count (view/works r))))
    (testing "the Work is realised in the Expression (R3), both directions"
      (let [w (:id (first (view/works r)))
            e (:id (first (view/expressions r)))]
        (is (= [e] (view/expressions-of r w)))
        (is (= [w] (view/work-of r e)))))))

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
      (is (= (:assertions r1) (:assertions r2)))
      (is (= (:diagnostics r1) (:diagnostics r2))))))

(deftest exports-to-rdf
  (testing "the FRBRised manifestation exports as N-Triples (F3 type, F2 type, R4 link)"
    (let [nt (export/->ntriples (frbrise/frbrise (by-id records :bnf/cb304403926)))]
      (is (str/includes? nt "lrmoo/F3_Manifestation"))
      (is (str/includes? nt "lrmoo/F2_Expression"))
      (is (str/includes? nt "lrmoo/R4_embodies")))))

(deftest frbrisation-reports-loss
  (let [r  (frbrise/frbrise (by-id records :bnf/cb304403926))
        ls (dx/losses (:diagnostics r))]
    (is (seq ls))
    (is (every? #(= :loss/dropped (:code %)) ls))
    (is (every? #(= :import (get-in % [:detail :loss/edge])) ls))
    (testing "mapped fields are NOT reported as loss, dropped ones are"
      (let [fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
        (is (not (contains? fields :intermarc/f145_3)))   ; projected -> Expression id
        (is (not (contains? fields :intermarc/f145_a)))   ; projected -> R33 title
        (is (contains? fields :intermarc/f100_a))))       ; author name -> dropped (for now)
    (testing "loss-summary is a per-category / per-edge breakdown"
      (let [s (dx/loss-summary (:diagnostics r))]
        (is (pos? (:total s)))
        (is (= (:total s) (get-in s [:by-category :dropped])))
        (is (pos? (get-in s [:by-edge :import])))))
    (testing "loss diagnostics keep the record consistent (subject = record id)"
      (is (model/record-consistent? r)))))

(deftest coverage-grows-with-the-projection
  (let [c (frbrise/coverage (by-id records :bnf/cb304403926))]
    (is (= 4 (:mapped c)))     ; f145_3, f145_a, f100_3, f245_a (was 2 last slice)
    (is (> (:total c) 10))     ; many INTERMARC fields are present
    (is (< (:pct c) 50))))     ; still partial — the loss report tracks the gap
