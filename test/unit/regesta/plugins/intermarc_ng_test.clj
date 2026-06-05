(ns regesta.plugins.intermarc-ng-test
  "INTERMARC-NG entity-relation spoke (ADR 0019), on a spec-faithful synthetic OEMI
   corpus. Proves the graph→graph path end to end: NG entity-records import to LRMoo
   entities + WEMI relations (no floor, no inference), serialise through the existing
   LRMoo / CIDOC-CRM exporters, and round-trip back via `crm-import` — the flagship
   BnF Transition-bibliographique conversion, demonstrated pending real native data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.intermarc-ng :as ng]
            [regesta.plugins.lrmoo :as lrmoo]
            [regesta.plugins.lrmoo.crm :as crm]
            [regesta.plugins.lrmoo.crm-import :as crm-import]
            [regesta.plugins.lrmoo.export :as export]))

(def ^:private fixture
  (slurp "test/fixtures/documentary/intermarc-ng/examples/fleurs-du-mal-oemi.xml"))

(def ^:private work-iri  "http://data.bnf.fr/ark:/12148/cb11947964w")
(def ^:private expr-iri  "http://data.bnf.fr/ark:/12148/cb11947965v")
(def ^:private manif-iri "http://data.bnf.fr/ark:/12148/cb44496975d")

(defn- import1 [] (first (ng/ingest fixture {})))

(defn- iri->kind [rec] (into {} (map (juxt :iri :kind)) (:entities rec)))

(defn- label-of [rec iri]
  (let [id (some #(when (= iri (:iri %)) (:id %)) (:entities rec))]
    (some #(when (and (= id (:subject %)) (= :lrmoo/R33_has_string (:predicate %))) (:value %))
          (:assertions rec))))

(defn- relations [rec]
  (let [idx (into {} (map (juxt :id :iri)) (:entities rec))]
    (set (for [a (:assertions rec)
               :when (and (lrmoo/vocabulary? (:predicate a)) (model/reference-value? (:value a)))]
           [(idx (:subject a)) (:predicate a) (idx (:value/target (:value a)))]))))

(deftest imports-the-oemi-graph-as-lrmoo-entities-and-relations
  (testing "each NG entity-record becomes the right LRMoo entity, keyed by its ARK IRI"
    (let [rec (import1)]
      (is (= {work-iri  :lrmoo/F1_Work
              expr-iri  :lrmoo/F2_Expression
              manif-iri :lrmoo/F3_Manifestation}
             (iri->kind rec)))
      (testing "the access-point labels (150/140/245 $a) become :lrmoo/R33_has_string"
        (is (= "Les fleurs du mal" (label-of rec work-iri)))
        (is (= "Les fleurs du mal (texte, français)" (label-of rec expr-iri)))
        (is (= "Les fleurs du mal" (label-of rec manif-iri))))
      (testing "the fundamental relations (740 Matérialise, 750 Réalise) become R4/R3, direction-correct"
        (is (= #{[manif-iri :lrmoo/R4_embodies       expr-iri]    ; 740, F3→F2
                 [work-iri  :lrmoo/R3_is_realised_in expr-iri]}   ; 750 flipped to F1→F2
               (relations rec)))))))

(deftest serialises-through-the-lrmoo-and-crm-exporters-unchanged
  (testing "the imported graph renders as LRMoo RDF — F-classes + WEMI relations"
    (let [nt (export/->ntriples (import1))]
      (is (str/includes? nt (lrmoo/iri :lrmoo/F1_Work)))
      (is (str/includes? nt (lrmoo/iri :lrmoo/F3_Manifestation)))
      (is (str/includes? nt (lrmoo/iri :lrmoo/R4_embodies)))
      (is (str/includes? nt manif-iri))))
  (testing "and down-projects to additive CIDOC-CRM (the museum view of the BnF entity graph)"
    (let [nt (crm/->ntriples (import1))]
      (is (str/includes? nt (str crm/crm-base "E89_Propositional_Object")))   ; F1 super-type
      (is (str/includes? nt (str crm/crm-base "E73_Information_Object"))))))   ; F2/F3 super-type

(deftest crm-to-lrm-round-trips-the-bnf-entity-graph
  (testing "NG → LRMoo → CRM → LRMoo: the additive CRM recovers the OEMI tree losslessly"
    (let [recovered (crm-import/recover (crm/->ntriples (import1)))]
      (is (= {work-iri  :lrmoo/F1_Work
              expr-iri  :lrmoo/F2_Expression
              manif-iri :lrmoo/F3_Manifestation}
             (:typed recovered)))
      (is (empty? (:ambiguous recovered)))
      (is (= #{[manif-iri :lrmoo/R4_embodies       expr-iri]
               [work-iri  :lrmoo/R3_is_realised_in expr-iri]}
             (:relations recovered))))))
