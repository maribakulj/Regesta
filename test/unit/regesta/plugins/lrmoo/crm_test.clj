(ns regesta.plugins.lrmoo.crm-test
  "Unit tests for the additive CIDOC-CRM down-projection (museum spoke, ADR 0013):
   every LRMoo type/relation triple gains its CRM super-type/super-property triple,
   losslessly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.lrmoo.crm :as crm]
            [regesta.plugins.lrmoo.export :as export]))

(def rec
  (model/record
   {:id :record/r1 :kind :book
    :entities [(model/entity {:id :ent/work  :kind :lrmoo/F1_Work})
               (model/entity {:id :ent/expr  :kind :lrmoo/F2_Expression})
               (model/entity {:id :ent/manif :kind :lrmoo/F3_Manifestation})]
    :assertions [(model/assertion {:subject :ent/work :predicate :lrmoo/R3_is_realised_in
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/manif :predicate :lrmoo/R4_embodies
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/expr :predicate :lrmoo/R33_has_string
                                   :value "Madame Bovary"})]}))

(def E89 "http://www.cidoc-crm.org/cidoc-crm/E89_Propositional_Object")
(def E73 "http://www.cidoc-crm.org/cidoc-crm/E73_Information_Object")
(def P130 "http://www.cidoc-crm.org/cidoc-crm/P130_shows_features_of")
(def P165 "http://www.cidoc-crm.org/cidoc-crm/P165_incorporates")
(def P3 "http://www.cidoc-crm.org/cidoc-crm/P3_has_note")
(def RDF-TYPE "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def F1 "http://iflastandards.info/ns/lrm/lrmoo/F1_Work")
(def R4 "http://iflastandards.info/ns/lrm/lrmoo/R4_embodies")

(deftest down-projection-is-additive-and-lossless
  (testing "every LRMoo triple is retained — the CRM view only adds"
    (is (every? (set (crm/crm-triples rec)) (export/triples rec)))))

(deftest f-classes-gain-their-crm-super-types
  (let [ts (set (crm/crm-triples rec))]
    (is (contains? ts ["urn:regesta:ent:work" RDF-TYPE {:iri E89}]))
    (testing "F2_Expression and F3_Manifestation BOTH map to E73 (the honest collapse)"
      (is (contains? ts ["urn:regesta:ent:expr"  RDF-TYPE {:iri E73}]))
      (is (contains? ts ["urn:regesta:ent:manif" RDF-TYPE {:iri E73}])))))

(deftest wemi-relations-gain-their-crm-super-properties
  (let [ts (set (crm/crm-triples rec))]
    (testing "R3 -> P130, alongside the kept R3 triple"
      (is (contains? ts ["urn:regesta:ent:work" P130 {:iri "urn:regesta:ent:expr"}])))
    (testing "R4 -> P165"
      (is (contains? ts ["urn:regesta:ent:manif" P165 {:iri "urn:regesta:ent:expr"}])))
    (testing "R33 literal -> P3_has_note literal"
      (is (contains? ts ["urn:regesta:ent:expr" P3 {:lit "Madame Bovary"}])))))

(deftest ntriples-carries-both-vocabularies
  (let [nt (crm/->ntriples rec)]
    (is (str/includes? nt "lrmoo/F1_Work"))                       ; LRMoo kept
    (is (str/includes? nt (str "<" E89 ">")))                     ; CRM added
    (is (str/includes? nt (str "<" P165 ">")))
    (testing "empty record -> empty string"
      (is (= "" (crm/->ntriples (model/record {:id :record/x :kind :book})))))))

(deftest exporter-follows-the-adr-0007-contract
  (let [{:keys [output diagnostics]} (crm/exporter {} [rec])]
    (is (string? output))
    (is (= [] diagnostics))                                       ; rec has only :lrmoo/* -> no drop
    (is (str/includes? output "cidoc-crm/E89_Propositional_Object"))))

;; --- pure CRM (replacement, lossy) — slice 2 -------------------------------

(deftest pure-crm-replaces-and-drops-lrmoo
  (let [ts (set (crm/crm-only-triples rec))]
    (testing "CRM E/P present"
      (is (contains? ts ["urn:regesta:ent:work"  RDF-TYPE {:iri E89}]))
      (is (contains? ts ["urn:regesta:ent:manif" P165 {:iri "urn:regesta:ent:expr"}])))
    (testing "LRMoo F/R gone (replacement, not additive)"
      (is (not (contains? ts ["urn:regesta:ent:work"  RDF-TYPE {:iri F1}])))
      (is (not (contains? ts ["urn:regesta:ent:manif" R4 {:iri "urn:regesta:ent:expr"}]))))))

(deftest pure-crm-reports-its-loss
  (let [ls     (crm/crm-only-losses rec)
        codes  (frequencies (map :code ls))
        fields (set (map #(get-in % [:detail :loss/source-field]) ls))]
    (is (every? #(= :export (get-in % [:detail :loss/edge])) ls))
    (testing "E73 collapse -> one :under-specified (Expression + Manifestation both present)"
      (is (= 1 (:loss/under-specified codes)))
      (is (contains? fields :lrmoo/F3_Manifestation)))
    (testing "each WEMI relation generalised -> :coerced (R3, R4, R33 present)"
      (is (= 3 (:loss/coerced codes)))
      (is (contains? fields :lrmoo/R3_is_realised_in))
      (is (contains? fields :lrmoo/R4_embodies))
      (is (contains? fields :lrmoo/R33_has_string)))))

(deftest pure-crm-ntriples-has-no-lrmoo
  (let [nt (crm/->crm-only-ntriples rec)]
    (is (str/includes? nt "cidoc-crm/E73_Information_Object"))
    (is (not (str/includes? nt "lrm/lrmoo/F")))     ; no F-classes
    (is (not (str/includes? nt "lrm/lrmoo/R")))))    ; no R-properties

(deftest pure-crm-exporter-reports-loss
  (let [{:keys [output diagnostics]} (crm/crm-only-exporter {} [rec])]
    (is (str/includes? output "cidoc-crm/E89_Propositional_Object"))
    (is (not (str/includes? output "lrm/lrmoo/F1")))
    (is (some #(= :loss/under-specified (:code %)) diagnostics))
    (is (some #(= :loss/coerced (:code %)) diagnostics))))
