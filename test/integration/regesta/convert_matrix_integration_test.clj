(ns regesta.convert-matrix-integration-test
  "Hardening: the full 5×8 conversion matrix on real fixtures. Proves the
   assembly composes universally — every source spoke × every target serialisation
   runs without throwing and yields an ADR 0015 loss report — and documents the
   one honest degenerate case.

   The degenerate case: INTERMARC reaches WEMI via the *enriched* `frbrise` rung,
   which populates `:lrmoo/*` directly and **bypasses the canonical floor**. The
   round-trip exporters (`dc`, `marc21`) read `:canon/*`, so for INTERMARC they
   have nothing to serialise — `intermarc → dc` is empty, and `intermarc → marc21`
   survives only via the `001` control number carried on `:source`. The WEMI/CRM/
   Linked-Art targets read `:lrmoo/*` and work for every spoke."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.convert :as cv]))

(def ^:private fixtures
  {:intermarc "test/fixtures/documentary/intermarc/sru/intermarcXchange/bib-flaubert-madame-bovary-start1-max30.xml"
   :dc        "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml"
   :marc21    "test/fixtures/documentary/marc21/marcxml/loc_collection.xml"
   :mods      "test/fixtures/documentary/mods/loc_mods_book.xml"
   :iiif      "test/fixtures/documentary/iiif/manifest_image_simple.json"})

(def ^:private record-counts {:intermarc 30 :dc 1 :marc21 2 :mods 1 :iiif 1})

(def ^:private froms [:intermarc :dc :marc21 :mods :iiif])
(def ^:private targets [:ntriples :turtle :jsonld :crm :crm-only :linked-art :dc :marc21])

(defn- run [from to]
  (cv/convert {:from from :to to :source (slurp (fixtures from))
               :opts (if (= from :dc) {:record-id :dc/x} {})}))

(deftest every-path-composes-and-reports-loss
  (testing "all 40 source×target paths run without throwing and return a loss report"
    (doseq [from froms, to targets]
      (let [{:keys [records loss]} (run from to)]
        (is (= (record-counts from) records) (str from "->" to " record count"))
        (is (map? loss) (str from "->" to " has a loss report"))
        (is (pos? (:total loss)) (str from "->" to " reports loss"))
        (is (seq (:by-edge loss)) (str from "->" to " has at least one loss edge"))
        (is (= records (:records loss)) (str from "->" to " loss record count"))))))

(deftest the-pivot-and-museum-targets-render-for-every-spoke
  (testing "ntriples/turtle/jsonld/crm/crm-only/linked-art read :lrmoo/* -> non-empty for all five spokes"
    (doseq [from froms
            to   [:ntriples :turtle :jsonld :crm :crm-only :linked-art]]
      (is (pos? (count (:output (run from to)))) (str from "->" to " is non-empty")))))

(deftest intermarc-round-trip-export-is-degenerate-frbrise-bypasses-canonical
  (testing "frbrise populates :lrmoo/* but not :canon/*, so the canonical round-trip exporters are starved"
    (is (= "" (:output (run :intermarc :dc)))
        "intermarc->dc is empty: ->dc-xml reads :canon/* which frbrise never set")
    (is (re-find #"<controlfield tag=\"001\">" (:output (run :intermarc :marc21)))
        "intermarc->marc21 survives only via the 001 carried on :source")
    (testing "the floor spokes, which DO populate :canon/*, round-trip non-empty"
      (doseq [from [:dc :marc21 :mods :iiif]]
        (is (pos? (count (:output (run from :dc)))) (str from "->dc"))))))
