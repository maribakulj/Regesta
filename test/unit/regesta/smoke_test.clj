(ns regesta.smoke-test
  "Smoke test: ensures every public namespace loads without error."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.app]
            [regesta.diagnostics]
            [regesta.model]
            [regesta.plugins]
            [regesta.plugins.canonical]
            [regesta.rules]
            [regesta.runtime]))

(deftest namespaces-load
  (testing "every public namespace loads"
    (is (some? (find-ns 'regesta.model)))
    (is (some? (find-ns 'regesta.rules)))
    (is (some? (find-ns 'regesta.runtime)))
    (is (some? (find-ns 'regesta.diagnostics)))
    (is (some? (find-ns 'regesta.plugins)))
    (is (some? (find-ns 'regesta.plugins.canonical)))
    (is (some? (find-ns 'regesta.app)))))
