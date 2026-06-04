(ns regesta.eval.linked-art-conformance-test
  "Linked Art conformance (DoD #4), as far as the offline env allows. Full
   draft-2020-12 validation needs a JSON Schema validator the sandbox cannot fetch
   (Maven Central returns 403, like Clojars) — so this is a schema-*derived*
   structural check, not a full validation: it reads the OFFICIAL Linked Art
   `object.json` schema and asserts our output satisfies its ROOT constraints —
   the required fields `[@context id type _label]` and `additionalProperties:false`
   (no key outside the schema's property set). Nested `$defs` (Name, Identifier,
   Creation, …) and formats are NOT checked here; that is the full validator's job,
   runnable where the dependency resolves (e.g. a CI box with Maven access).

   The check is proven sound by running it on the official Mona Lisa example (which
   must pass)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.convert :as convert]))

(def ^:private la-dir "test/fixtures/conformance/linked-art/")
(def ^:private object-schema (json/read-str (slurp (str la-dir "linked-art-schema-object.json"))))

(defn- root-conformance-errors
  "Seq of errors of `doc` against the schema's ROOT constraints: every `required`
   field present, and (additionalProperties:false) every key in the schema's
   property set. Not full validation — see ns doc."
  [doc schema]
  (let [required (set (get schema "required"))
        allowed  (set (keys (get schema "properties")))]
    (concat
     (map #(str "missing required: " %) (remove #(contains? doc %) required))
     (map #(str "illegal property: " %) (remove #(contains? allowed %) (keys doc))))))

(deftest the-check-is-sound-against-the-official-example
  (testing "the official Mona Lisa HumanMadeObject passes the root check (so the check isn't vacuous)"
    (let [mona (json/read-str (slurp (str la-dir "example-object-mona-lisa.json")))]
      (is (= "HumanMadeObject" (get mona "type")))
      (is (empty? (root-conformance-errors mona object-schema))))))

(deftest our-linked-art-output-conforms-to-the-schema-root
  (testing "MARC21 -> Linked Art: every HumanMadeObject has the required root fields, no illegal property"
    (let [out  (:output (convert/convert {:from :marc21 :to :linked-art
                                          :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")}))
          docs (map json/read-str (remove str/blank? (str/split-lines out)))]
      (is (pos? (count docs)))
      (doseq [d docs]
        (is (= "HumanMadeObject" (get d "type")))
        (is (empty? (root-conformance-errors d object-schema))
            (str (get d "_label") ": " (root-conformance-errors d object-schema))))
      (testing "the carried Expression is an embedded LinguisticObject (id/type/_label, no @context)"
        (let [expr (first (get (first docs) "carries"))]
          (is (= "LinguisticObject" (get expr "type")))
          (is (every? #(contains? expr %) ["id" "type" "_label"])))))))

(deftest a-titleless-record-would-not-conform-honest-limit
  (testing "since _label is required, a creator-less/titleless HumanMadeObject would fail — documented, not hidden"
    ;; an object with no _label violates object.json's required set
    (is (some #(str/includes? % "_label")
              (root-conformance-errors {"@context" "x" "id" "y" "type" "HumanMadeObject"} object-schema)))))
