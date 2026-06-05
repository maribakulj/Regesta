(ns regesta.conformance-test
  "Tests for the WP-6 conformance mechanism (`regesta.conformance`) and the Linked
   Art (Louvre) profile. The mechanism: a profile's checks are ordinary diagnostics
   over a projected WEMI record; the verdict is policy-gated (the acceptance
   threshold). Grounded on constructed records (each check in isolation) and on the
   real pipeline (MARC21 conformant-with-warnings; a titleless record fails the name
   requirement)."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.conformance :as conf]
            [regesta.model :as model]))

(defn- codes [diags] (set (map (comp name :code) diags)))

;; ---------------------------------------------------------------------------
;; Each check, in isolation, on constructed WEMI records
;; ---------------------------------------------------------------------------

(def ^:private conformant
  (model/record
   {:id :rec/ok :kind :test
    :entities [(model/entity {:id :w/a :kind :lrmoo/F1_Work})
               (model/entity {:id :e/a :kind :lrmoo/F2_Expression})
               (model/entity {:id :m/a :kind :lrmoo/F3_Manifestation :iri "https://example.org/ark"})
               (model/entity {:id :p/a :kind :crm/E21_Person
                              :iri "https://isni.org/isni/0000000122762442"})]
    :assertions [(model/assertion {:subject :e/a :predicate :lrmoo/R33_has_string :value "A Title"})
                 (model/assertion {:subject :rec/ok :predicate :canon/agent :value "Gustave Flaubert"})]}))

(deftest a-fully-conformant-record-raises-nothing
  (testing "manifestation + title + expression + work + identified creator + identifier"
    (is (empty? (conf/check-record conf/linked-art-profile conformant)))))

(deftest the-hard-requirements-are-errors
  (testing "a bare record (no entities, no title) fails the two error checks"
    (let [diags (conf/check-record conf/linked-art-profile
                                   (model/record {:id :rec/bare :kind :test}))
          errs  (filter #(= :error (:severity %)) diags)]
      (is (= #{"root-human-made-object" "has-name"} (codes errs)))
      (testing "and the richness checks downgrade to warnings / info, not errors"
        (is (contains? (codes diags) "carries-expression"))
        (is (contains? (codes diags) "expression-realises-work"))
        (is (contains? (codes diags) "has-identifier"))))))

(deftest creator-identified-discriminates-bare-label-from-authority-id
  (let [base {:id :rec/c :kind :test
              :entities [(model/entity {:id :m/a :kind :lrmoo/F3_Manifestation})]
              :assertions [(model/assertion {:subject :m/a :predicate :lrmoo/R33_has_string :value "T"})
                           (model/assertion {:subject :rec/c :predicate :canon/agent :value "Someone"})]}]
    (testing "a bare-label creator (no E21_Person iri) warns"
      (is (contains? (codes (conf/check-record conf/linked-art-profile (model/record base)))
                     "creator-identified")))
    (testing "an authority-identified creator does not"
      (let [with-id (update base :entities conj
                            (model/entity {:id :p/a :kind :crm/E21_Person :iri "https://isni.org/x"}))]
        (is (not (contains? (codes (conf/check-record conf/linked-art-profile (model/record with-id)))
                            "creator-identified")))))))

(deftest for-profile-filters-to-the-profile
  (let [conf-diags (conf/check-record conf/linked-art-profile
                                      (model/record {:id :rec/bare :kind :test}))
        foreign    (model/diagnostic {:severity :warning :code :other/unrelated :subject :rec/x})
        mixed      (conj (vec conf-diags) foreign)]
    (is (seq conf-diags))
    (is (= (set conf-diags) (set (conf/for-profile mixed :linked-art))))
    (is (not (some #(= :other/unrelated (:code %)) (conf/for-profile mixed :linked-art))))))

;; ---------------------------------------------------------------------------
;; On the real pipeline
;; ---------------------------------------------------------------------------

(def ^:private marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")

(deftest marc21-is-conformant-with-richness-warnings-and-the-policy-is-the-threshold
  (let [run (fn [policy]
              (conf/conformance {:from :marc21 :source (slurp marc21)
                                 :profile conf/linked-art-profile :policy policy}))]
    (testing "the two records pass the hard requirements (no errors) -> conformant under errors-only"
      (let [{:keys [records diagnostics failed? summary]} (run :errors-only)]
        (is (= 2 records))
        (is (false? failed?))
        (is (= :warning (:max-severity summary)))
        (testing "the floor creators are bare labels -> a creator-identified warning each"
          (is (= 2 (count (filter #(= :conformance.linked-art/creator-identified (:code %))
                                  diagnostics)))))))
    (testing "raising the acceptance threshold (the institution's knob) makes the warnings fail"
      (is (true? (:failed? (run :errors-and-warnings)))))))

(deftest a-titleless-record-fails-the-name-requirement
  (testing "no dc:title -> has-name error -> failed under errors-only"
    (let [src "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>"
          {:keys [diagnostics failed? summary]}
          (conf/conformance {:from :dc :source src :opts {:record-id :doc/untitled}
                             :profile conf/linked-art-profile})]
      (is (true? failed?))
      (is (= :error (:max-severity summary)))
      (is (some #(= :conformance.linked-art/has-name (:code %)) diagnostics)))))
