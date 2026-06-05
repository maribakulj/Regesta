(ns regesta.cli-test
  "Tests for the CLI core `run` (pure: returns {:exit :out :err}, no print/exit).
   Exercises the happy paths, the format listing, the default record-id, file
   output, and the error exits."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.cli :as cli]))

(def ^:private marc21 "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")
(def ^:private dc "test/fixtures/documentary/dublin-core/w3c_dc_example1.xml")

(deftest converts-and-reports
  (testing "convert MARC21 -> Linked Art: output to :out, loss report to :err"
    (let [{:keys [exit out err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "linked-art"])]
      (is (= 0 exit))
      (is (str/includes? out "HumanMadeObject"))
      (is (str/includes? out "The Great Ray Charles"))
      (is (str/includes? err "Loss report"))
      (is (str/includes? err "2 records converted")))))

(deftest dublin-core-defaults-its-record-id-from-the-filename
  (testing "DC needs a record-id; without --record-id the CLI derives one and still converts"
    (let [{:keys [exit out]} (cli/run ["convert" dc "--from" "dc" "--to" "turtle"])]
      (is (= 0 exit))
      (is (str/includes? out "@prefix lrmoo:"))
      (is (str/includes? out "Dublin Core Tutorial")))))

(deftest writes-to-a-file-with-out
  (testing "--out writes the document to a file; stdout stays empty"
    (let [path (str (System/getProperty "java.io.tmpdir") "/regesta-cli-" (System/nanoTime) ".jsonld")]
      (try
        (let [{:keys [exit out err]} (cli/run ["convert" marc21 "--from" "marc21" "--to" "jsonld" "--out" path])]
          (is (= 0 exit))
          (is (= "" out))
          (is (str/includes? err (str "wrote " path)))
          (is (str/includes? (slurp path) "@graph")))
        (finally (.delete (java.io.File. path)))))))

(deftest formats-and-help
  (testing "formats lists the supported source and target formats"
    (let [{:keys [exit err]} (cli/run ["formats"])]
      (is (= 0 exit))
      (is (str/includes? err "linked-art"))
      (is (str/includes? err "intermarc"))))
  (testing "--help and no-args print usage with exit 0"
    (is (= 0 (:exit (cli/run ["--help"]))))
    (is (str/includes? (:err (cli/run ["--help"])) "Usage:"))
    (is (str/includes? (:err (cli/run [])) "Usage:"))))

(deftest error-exits
  (testing "a missing input file exits 2"
    (let [{:keys [exit err]} (cli/run ["convert" "nope.xml" "--from" "dc" "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "not found"))))
  (testing "an unknown format exits 2 with the supported set"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--from" "bogus" "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "error:"))))
  (testing "missing --from exits 2 with usage"
    (let [{:keys [exit err]} (cli/run ["convert" marc21 "--to" "turtle"])]
      (is (= 2 exit))
      (is (str/includes? err "needs"))))
  (testing "an unknown command exits 2"
    (is (= 2 (:exit (cli/run ["frobnicate"]))))))

(deftest validate-command
  (testing "a titled source validates with exit 0"
    (let [{:keys [exit err]} (cli/run ["validate" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? err "VALID"))
      (is (not (str/includes? err "INVALID")))))
  (testing "a titleless record warns; errors-only passes, errors-and-warnings fails (exit 1)"
    (let [path (str (System/getProperty "java.io.tmpdir") "/regesta-notitle-" (System/nanoTime) ".xml")]
      (try
        (spit path "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Anon</dc:creator></metadata>")
        (is (= 0 (:exit (cli/run ["validate" path "--from" "dc"]))))
        (let [{:keys [exit err]} (cli/run ["validate" path "--from" "dc" "--policy" "errors-and-warnings"])]
          (is (= 1 exit))
          (is (str/includes? err "INVALID"))
          (is (str/includes? err "missing-title")))
        (finally (.delete (java.io.File. path))))))
  (testing "validate needs --from"
    (is (= 2 (:exit (cli/run ["validate" marc21]))))))

;; --- report / inspect / reconcile (read-only verbs) -------------------------

(def ^:private intermarc
  "test/fixtures/documentary/intermarc/sru/intermarcXchange/bnf-sru-victor-hugo-50.xml")

(deftest report-emits-the-loss-report-only
  (testing "report MARC21 -> DC: the loss report to stdout, no converted document"
    (let [{:keys [exit out]} (cli/run ["report" marc21 "--from" "marc21" "--to" "dc"])]
      (is (= 0 exit))
      (is (str/includes? out "Loss report"))
      (is (str/includes? out "by category"))
      (is (str/includes? out "2 records"))
      (is (not (str/includes? out "<dc:title")))))          ; the document is NOT emitted
  (testing "report needs --to"
    (let [{:keys [exit err]} (cli/run ["report" marc21 "--from" "marc21"])]
      (is (= 2 exit))
      (is (str/includes? err "report needs --to")))))

(deftest inspect-shows-the-floor-and-the-minted-entities
  (testing "inspect MARC21: the canonical floor and the WEMI entities per record"
    (let [{:keys [exit out]} (cli/run ["inspect" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? out "2 records"))
      (is (str/includes? out ":marc/r5637241"))
      (is (str/includes? out ":canon/title"))
      (is (str/includes? out "The Great Ray Charles"))
      (is (str/includes? out "entities:"))
      (is (str/includes? out "lrmoo/F1_Work")))))

(deftest reconcile-groups-agents-by-authority-id
  (testing "reconcile INTERMARC (Victor Hugo 50): agents reconciled by ISNI"
    (let [{:keys [exit out]} (cli/run ["reconcile" intermarc "--from" "intermarc"])]
      (is (= 0 exit))
      (is (str/includes? out "reconciled to"))
      (is (str/includes? out "distinct agent"))
      (is (str/includes? out "isni.org"))))
  (testing "reconcile MARC21: honest 'none' (no authority-identified agents on the floor)"
    (let [{:keys [exit out]} (cli/run ["reconcile" marc21 "--from" "marc21"])]
      (is (= 0 exit))
      (is (str/includes? out "no authority-identified agents")))))

(deftest read-only-verbs-validate-their-inputs
  (testing "a missing file is an exit-2, not a crash"
    (is (= 2 (:exit (cli/run ["inspect" "/no/such/file.xml" "--from" "marc21"]))))
    (is (= 2 (:exit (cli/run ["reconcile" marc21]))))))         ; missing --from
