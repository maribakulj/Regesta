(ns regesta.smoke-test
  "Smoke test: ensures every public namespace loads without error.

   This is the single guarantee Sprint 0 provides. Real tests land with
   each feature sprint."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.app]
            [regesta.diagnostics]
            [regesta.model]
            [regesta.plugins]
            [regesta.rules]
            [regesta.runtime]))

(deftest namespaces-load
  (testing "every public namespace loads"
    (is (some? (find-ns 'regesta.model)))
    (is (some? (find-ns 'regesta.rules)))
    (is (some? (find-ns 'regesta.runtime)))
    (is (some? (find-ns 'regesta.diagnostics)))
    (is (some? (find-ns 'regesta.plugins)))
    (is (some? (find-ns 'regesta.app)))))
