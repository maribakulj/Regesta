(ns regesta.plugins.mods-test
  "Unit tests for the MODS spoke (WP-4) on the three real LoC fixtures (book,
   book-chapter, journal-article): nested extraction of the bibliographic core
   onto the canonical floor, host-relationship exclusion, nonSort/name assembly,
   the report-at-ingest loss, and the WEMI projection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.diagnostics :as dx]
            [regesta.plugins :as plug]
            [regesta.plugins.dc :as dc]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.project :as project]
            [regesta.plugins.lrmoo.view :as view]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.marc21 :as marc21]
            [regesta.plugins.mods :as mods]
            [regesta.runtime :as runtime]))

(defn- fixture [name] (slurp (str "test/fixtures/documentary/mods/" name ".xml")))

(defn- ingest+normalize [name]
  (let [reg      (plug/register plug/empty-registry mods/plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))
        {:keys [records diagnostics]} (mods/importer {} (fixture name))]
    {:record  (first records)
     :canon   (:record (runtime/run-phase (first records) compiled :normalize))
     :ingest  diagnostics}))

(defn- literals [record pred]
  (->> (:assertions record)
       (filter #(and (= pred (:predicate %)) (string? (:value %))))
       (map :value)
       set))

(deftest book-maps-the-nested-core-onto-the-floor
  (testing "titleInfo/name/originInfo/identifier of the book reach canonical"
    (let [{:keys [record canon]} (ingest+normalize "loc_mods_book")]
      (is (= :mods/r11761548 (:id record)))                       ; from recordInfo/recordIdentifier
      (is (= #{"Sound and fury"} (literals canon :canon/title)))
      (is (= #{"Alterman, Eric" "Cornell University Press"}        ; name + publisher
             (literals canon :canon/agent)))
      (is (= #{"c1999" "1999"} (literals canon :canon/date)))      ; two dateIssued
      (is (contains? (literals canon :canon/identifier) "99042030"))   ; lccn
      (is (= 2 (count (literals canon :canon/note)))))))               ; two notes

(deftest unmodelled-top-level-elements-are-reported-not-dropped
  (testing "subject/genre/typeOfResource/… become :import :dropped loss"
    (let [{:keys [ingest]} (ingest+normalize "loc_mods_book")
          ls (dx/losses ingest)]
      (is (every? #(= :loss/dropped (:code %)) ls))
      (is (every? #(= :import (get-in % [:detail :loss/edge])) ls))
      (is (= #{:mods/typeOfResource :mods/genre :mods/language
               :mods/physicalDescription :mods/subject :mods/classification}
             (set (map #(get-in % [:detail :loss/source-field]) ls)))))))

(deftest the-host-relateditem-is-not-read-as-the-records-own-title
  (testing "a chapter keeps its own title/creator; the host book is excluded"
    (let [{:keys [record canon]} (ingest+normalize "loc_mods_book_chapter")]
      (is (= :mods/rAmin1994a (:id record)))                      ; from <identifier> (no recordInfo)
      (is (= #{"Models, Fantasies and Phantoms of Transition"} (literals canon :canon/title)))
      (is (= #{"Ash Amin"} (literals canon :canon/agent)))        ; given + family joined
      (testing "the host (relatedItem) title and editor do NOT leak in"
        (is (not (contains? (literals canon :canon/title) "Post-Fordism")))
        (is (not (str/includes? (str/join " " (literals canon :canon/agent)) "Blackwell")))))))

(deftest nonsort-is-restored-on-the-title
  (testing "the journal title keeps its leading article"
    (let [{:keys [canon]} (ingest+normalize "loc_mods_journal")]
      (is (= #{"The Urban Question as a Scale Question"} (literals canon :canon/title)))
      (is (= #{"Neil Brenner"} (literals canon :canon/agent))))))

(deftest mods-reaches-the-wemi-pivot-and-rdf
  (testing "the floor projects to a full WEMI chain and serialises"
    (let [{:keys [canon]} (ingest+normalize "loc_mods_book")
          wemi (project/project canon)
          nt   (export/->ntriples wemi)]
      (is (= 1 (count (view/manifestations wemi))))
      (is (= 1 (count (view/expressions wemi))))
      (is (= 1 (count (view/works wemi))))
      (is (str/includes? nt "lrmoo/F1_Work"))
      (is (str/includes? nt "Sound and fury")))))

(deftest the-plugin-conforms-and-composes-with-the-other-spokes
  (testing "MODS is a well-formed plugin; its mapping ids don't collide with DC/MARC21"
    (is (= :regesta/mods (:id mods/plugin)))
    (is (= 8 (count (:mapping mods/plugin))))
    (let [reg (reduce plug/register plug/empty-registry [mods/plugin dc/plugin marc21/plugin])]
      (is (some? (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg)))))))
