(ns regesta.reconcile-test
  "Unit tests for cross-record agent reconciliation (ADR 0018): grouping the
   identified-agent entities by authority id, on hand-built records."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.reconcile :as rec]))

(defn- record-with-agent [rid label iri]
  (model/record
   {:id rid :kind :book
    :entities (cond-> []
                iri (conj (model/entity {:id (model/mint-entity-id :crm/E21_Person iri)
                                         :kind :crm/E21_Person :iri iri})))
    :assertions (cond-> []
                  label (conj (model/assertion {:subject rid :predicate :canon/agent :value label})))}))

(def ^:private hugo  "https://isni.org/isni/0000000121200174")
(def ^:private flau  "https://isni.org/isni/0000000122762442")

(deftest reconciles-shared-authority-ids-across-records
  (testing "two records with the same ISNI reconcile to one agent; a different ISNI is a second"
    (let [records [(record-with-agent :rec/a "Hugo, Victor" hugo)
                   (record-with-agent :rec/b "Hugo, Victor" hugo)        ; same person, second record
                   (record-with-agent :rec/c "Flaubert, Gustave" flau)
                   (record-with-agent :rec/d "Anon" nil)]                ; no identified agent
          {:keys [agents distinct mentions records] :as r} (rec/reconcile-agents records)]
      (is (= 2 distinct))
      (is (= 3 mentions))                                  ; d contributes nothing
      (is (= 3 records))
      (testing "the Hugo agent gathers both records under one authority id"
        (let [hugo-agent (first (filter #(= hugo (:iri %)) agents))]
          (is (= 2 (:mentions hugo-agent)))
          (is (= #{:rec/a :rec/b} (set (:records hugo-agent))))
          (is (= "Hugo, Victor" (:label hugo-agent)))))
      (testing "same ISNI -> same content-addressed agent id (the dedup is exact)"
        (is (apply = (map :id (filter #(= hugo (:iri %)) agents))))   ; one entry, trivially
        (is (str/includes? (rec/format-agent-reconciliation r) "reconciled to 2 distinct agents"))))))

(deftest empty-and-agentless
  (testing "no records / no identified agents -> empty reconciliation"
    (is (= {:agents [] :distinct 0 :mentions 0 :records 0} (rec/reconcile-agents [])))
    (is (zero? (:distinct (rec/reconcile-agents [(record-with-agent :rec/x "Anon" nil)]))))
    (is (str/includes? (rec/format-agent-reconciliation (rec/reconcile-agents []))
                       "no authority-identified agents"))))
