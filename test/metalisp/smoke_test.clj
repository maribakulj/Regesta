(ns metalisp.smoke-test
  "Smoke test: ensures every public namespace loads without error.

   This is the single guarantee Sprint 0 provides. Real tests land with
   each feature sprint."
  (:require [clojure.test :refer [deftest is testing]]
            [metalisp.app]
            [metalisp.diagnostics]
            [metalisp.model]
            [metalisp.plugins]
            [metalisp.rules]
            [metalisp.runtime]))

(deftest namespaces-load
  (testing "every public namespace loads"
    (is (some? (find-ns 'metalisp.model)))
    (is (some? (find-ns 'metalisp.rules)))
    (is (some? (find-ns 'metalisp.runtime)))
    (is (some? (find-ns 'metalisp.diagnostics)))
    (is (some? (find-ns 'metalisp.plugins)))
    (is (some? (find-ns 'metalisp.app)))))
