(ns regesta.plugins.lrmoo.export-test
  "Unit tests for the LRMoo RDF export (WP-2 slice 3, ADR 0013): the triple
   model and the N-Triples rendering on a hand-built WEMI graph."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins.lrmoo.export :as export]))

(def rec
  (model/record
   {:id :record/r1 :kind :book
    :entities [(model/entity {:id :ent/work :kind :lrmoo/F1_Work})
               (model/entity {:id :ent/expr :kind :lrmoo/F2_Expression})]
    :assertions [(model/assertion {:subject :ent/work :predicate :lrmoo/R3_is_realised_in
                                   :value (model/reference :ent/expr)})
                 (model/assertion {:subject :ent/expr :predicate :lrmoo/R33_has_string
                                   :value "Madame Bovary"})]}))

(def F1 "http://iflastandards.info/ns/lrm/lrmoo/F1_Work")
(def R3 "http://iflastandards.info/ns/lrm/lrmoo/R3_is_realised_in")
(def R33 "http://iflastandards.info/ns/lrm/lrmoo/R33_has_string")
(def RDF-TYPE "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(deftest entity-iri-maps-ids
  (is (= "urn:regesta:ent:work" (export/entity-iri :ent/work)))
  (is (= "http://x/y" (export/entity-iri "http://x/y"))))   ; string passthrough

(deftest triples-type-property-and-literal
  (let [ts (set (export/triples rec))]
    (testing "each WEMI entity is typed to its F-class IRI"
      (is (contains? ts ["urn:regesta:ent:work" RDF-TYPE {:iri F1}])))
    (testing "a reference value becomes an object-property triple"
      (is (contains? ts ["urn:regesta:ent:work" R3 {:iri "urn:regesta:ent:expr"}])))
    (testing "a literal value becomes a literal triple"
      (is (contains? ts ["urn:regesta:ent:expr" R33 {:lit "Madame Bovary"}])))))

(deftest ntriples-rendering
  (let [nt (export/->ntriples rec)]
    (is (str/includes? nt (str "<urn:regesta:ent:work> <" RDF-TYPE "> <" F1 "> .")))
    (is (str/includes? nt (str "<urn:regesta:ent:work> <" R3 "> <urn:regesta:ent:expr> .")))
    (is (str/includes? nt "\"Madame Bovary\" ."))
    (testing "every line is a well-formed triple ending in ' .'"
      (is (every? #(str/ends-with? % " .") (str/split-lines nt))))
    (testing "a record with no LRMoo content yields the empty string"
      (is (= "" (export/->ntriples (model/record {:id :record/x :kind :book})))))))

(deftest ntriples-escapes-literals
  (let [r (model/record
           {:id :record/r1 :kind :book
            :entities [(model/entity {:id :ent/e :kind :lrmoo/F2_Expression})]
            :assertions [(model/assertion {:subject :ent/e :predicate :lrmoo/R33_has_string
                                           :value "a \"quote\" and \\ slash"})]})]
    (is (str/includes? (export/->ntriples r) "\\\"quote\\\""))
    (is (str/includes? (export/->ntriples r) "\\\\ slash"))))

(deftest exporter-follows-the-adr-0007-contract
  (let [{:keys [output diagnostics]} (export/exporter {} [rec])]
    (is (string? output))
    (is (= [] diagnostics))
    (is (str/includes? output "lrmoo/F1_Work"))))
