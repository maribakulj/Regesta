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
