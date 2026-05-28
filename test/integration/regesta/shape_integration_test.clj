(ns regesta.shape-integration-test
  "End-to-end integration: plugin registry → shape-adapter ingest →
   M4-compiled :normalize rules → canonical Record.

   The scenario is a tiny multilingual Dublin Core record expressed
   in both JSON-LD and XML serializations. The test demonstrates that
   the two formats, processed independently through the plugin
   registry and the V1 pipeline, converge on the same canonical
   assertions — the integration-level expression of ADR 0012's
   cross-format equivalence promise. It also exercises the plugin
   protocol (M2) end-to-end: register, validate, dispatch via
   `:input-format`, invoke the importer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [regesta.model :as model]
            [regesta.plugins :as plug]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.shape :as shape]
            [regesta.runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; Fixture: a DC record with two language-tagged titles
;; ---------------------------------------------------------------------------

(def ^:private dc-uri  "http://purl.org/dc/elements/1.1/")
(def ^:private xml-uri "http://www.w3.org/XML/1998/namespace")

(def ^:private xml-source
  (str "<record xmlns:dc=\"" dc-uri "\">"
       "<dc:title xml:lang=\"fr\">Les Misérables</dc:title>"
       "<dc:title xml:lang=\"en\">The Wretched</dc:title>"
       "</record>"))

(def ^:private json-source
  (str "{\"dc:title\":["
       "{\"@value\":\"Les Misérables\",\"@language\":\"fr\"},"
       "{\"@value\":\"The Wretched\",\"@language\":\"en\"}"
       "]}"))

;; Two mappings, one per native format. Both target the same
;; canonical predicates; only the `:from` keys differ per format
;; (JSON-LD's `@language` vs XML's `xml:lang`). Distinct `:mapping/id`
;; names per plugin to avoid the cross-plugin rule-id collision flagged
;; in ADR 0009 §Open V2.
(def ^:private json-mapping
  [{:mapping/id        :map/dc-title-json
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/qualifier {:from (keyword "@language") :as :canon/lang}}])

(def ^:private xml-mapping
  [{:mapping/id        :map/dc-title-xml
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/qualifier {:from :xml/lang :as :canon/lang}}])

(def ^:private xml-aliases
  {:dc  dc-uri
   :xml xml-uri})

(def ^:private opts
  {:record-id :record/r1 :kind :document})

;; A pretty-printed variant of `xml-source`: same logical content, but
;; the title elements are wrapped across lines with indentation. The
;; XML parser preserves the whitespace verbatim, so the ingest-level
;; values carry leading/trailing `\n`s. The mapping below pairs this
;; fixture with a `[:trim]` transform so the canonical layer still
;; converges with the JSON sibling — the documented cross-format
;; recipe for indented XML in `regesta.plugins.shape`'s ns docstring.
(def ^:private indented-xml-source
  (str "<record xmlns:dc=\"" dc-uri "\">\n"
       "  <dc:title xml:lang=\"fr\">\n"
       "    Les Misérables\n"
       "  </dc:title>\n"
       "  <dc:title xml:lang=\"en\">\n"
       "    The Wretched\n"
       "  </dc:title>\n"
       "</record>"))

(def ^:private xml-mapping-with-trim
  [{:mapping/id        :map/dc-title-xml-trim
    :mapping/from      :dc/title
    :mapping/to        :canon/title
    :mapping/transform [:trim]
    :mapping/qualifier {:from :xml/lang :as :canon/lang}}])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- canonical-record-titles
  "Pluck the record-level `:canon/title` reference values from a
   normalized record."
  [record]
  (->> (:assertions record)
       (filterv #(and (= (:id record) (:subject %))
                      (= :canon/title (:predicate %))))
       (mapv :value)))

(defn- fragment-canonical-pairs
  "Collect `[predicate value]` pairs from every assertion subjected
   to a fragment in `record`, returning a `{fragment-id -> #{[p v]}}`
   map. Used to compare what the normalize phase landed on the
   fragments across formats."
  [record]
  (let [frag-ids (set (map :id (:fragments record)))]
    (reduce
     (fn [acc a]
       (if (contains? frag-ids (:subject a))
         (update acc (:subject a) (fnil conj #{})
                 [(:predicate a) (:value a)])
         acc))
     {}
     (:assertions record))))

;; ---------------------------------------------------------------------------
;; End-to-end
;; ---------------------------------------------------------------------------

(deftest plugin-registry-accepts-both-shape-plugins
  (testing "both factories produce Plugin-schema-conforming maps and register cleanly"
    (let [jp (shape/shape-json-plugin {:id :plugin/dc-json :mapping json-mapping})
          xp (shape/shape-xml-plugin  {:id :plugin/dc-xml  :mapping xml-mapping
                                       :aliases xml-aliases})]
      (is (plug/valid-plugin? jp))
      (is (plug/valid-plugin? xp))
      (let [registry (-> plug/empty-registry
                         (plug/register jp)
                         (plug/register xp))]
        (is (= #{:plugin/dc-json :plugin/dc-xml}
               (plug/registered-ids registry)))))))

(deftest input-format-dispatch-routes-each-source-to-its-plugin
  (let [registry (-> plug/empty-registry
                     (plug/register (shape/shape-json-plugin
                                     {:id :plugin/dc-json :mapping json-mapping}))
                     (plug/register (shape/shape-xml-plugin
                                     {:id :plugin/dc-xml :mapping xml-mapping
                                      :aliases xml-aliases})))]
    (is (= :plugin/dc-json
           (:id (plug/select-importer registry {:format :json :source json-source}))))
    (is (= :plugin/dc-xml
           (:id (plug/select-importer registry {:format :xml  :source xml-source}))))))

(deftest jsonld-and-xml-converge-on-the-same-canonical-record
  (testing "ingest both formats through the registry, normalize via M4, compare canonical state"
    (let [jp        (shape/shape-json-plugin {:id :plugin/dc-json :mapping json-mapping})
          xp        (shape/shape-xml-plugin  {:id :plugin/dc-xml  :mapping xml-mapping
                                              :aliases xml-aliases})
          registry  (-> plug/empty-registry
                        (plug/register jp)
                        (plug/register xp))
          stdlib    (plug/effective-transforms registry)
          compiled  (mapping/compile-mappings (plug/all-mappings registry) stdlib)

          json-rec  (-> ((:importer jp) opts json-source) :records first)
          xml-rec   (-> ((:importer xp) opts xml-source)  :records first)

          jn        (:record (runtime/run-phase json-rec compiled :normalize))
          xn        (:record (runtime/run-phase xml-rec  compiled :normalize))]

      (testing "ingest produced equivalent native records (cross-format identity)"
        (is (= (set (mapv :id (:fragments json-rec)))
               (set (mapv :id (:fragments xml-rec)))))
        (is (= [:frag/record.r1.dc-title.0 :frag/record.r1.dc-title.1]
               (mapv :id (:fragments json-rec)))))

      (testing "after :normalize, both records carry two :canon/title reference assertions"
        (is (= 2 (count (canonical-record-titles jn))))
        (is (= (set (canonical-record-titles jn))
               (set (canonical-record-titles xn))))
        (is (= #{(model/reference :frag/record.r1.dc-title.0)
                 (model/reference :frag/record.r1.dc-title.1)}
               (set (canonical-record-titles jn)))))

      (testing "fragments carry identical canonical (predicate, value) pairs across formats"
        (let [pick-canon (fn [pairs]
                           (set (filter (fn [[p _]]
                                          (#{:canon/title :canon/lang} p))
                                        pairs)))
              jn-frags   (fragment-canonical-pairs jn)
              xn-frags   (fragment-canonical-pairs xn)]
          (is (= (set (keys jn-frags)) (set (keys xn-frags))))
          (doseq [frag-id (keys jn-frags)]
            (is (= (pick-canon (get jn-frags frag-id))
                   (pick-canon (get xn-frags frag-id)))
                (str "canonical assertions diverge on " frag-id)))))

      (testing "specifically: first fragment is fr/Les Misérables, second is en/The Wretched"
        (let [jn-frags (fragment-canonical-pairs jn)]
          (is (contains? (get jn-frags :frag/record.r1.dc-title.0)
                         [:canon/title "Les Misérables"]))
          (is (contains? (get jn-frags :frag/record.r1.dc-title.0)
                         [:canon/lang "fr"]))
          (is (contains? (get jn-frags :frag/record.r1.dc-title.1)
                         [:canon/title "The Wretched"]))
          (is (contains? (get jn-frags :frag/record.r1.dc-title.1)
                         [:canon/lang "en"])))))))

(deftest indented-xml-converges-on-canonical-when-mapping-declares-trim
  (testing "ingest is faithful to XML whitespace; :normalize [:trim] is what reconciles with JSON"
    ;; Compile each plugin's mappings in isolation, mirroring production:
    ;; a JSON record runs through the JSON plugin's normalize rules, an
    ;; XML record runs through the XML plugin's rules. Pooling them in a
    ;; single registry would cross-fire (the JSON rule, which has no
    ;; transform, would also rename `:dc/title` to `:canon/title` on
    ;; the XML record, defeating the point of this test).
    (let [jp           (shape/shape-json-plugin {:id :plugin/dc-json :mapping json-mapping})
          xp           (shape/shape-xml-plugin  {:id :plugin/dc-xml-trim
                                                 :mapping xml-mapping-with-trim
                                                 :aliases xml-aliases})
          json-reg     (plug/register plug/empty-registry jp)
          xml-reg      (plug/register plug/empty-registry xp)
          json-stdlib  (plug/effective-transforms json-reg)
          xml-stdlib   (plug/effective-transforms xml-reg)
          json-compiled (mapping/compile-mappings (plug/all-mappings json-reg) json-stdlib)
          xml-compiled  (mapping/compile-mappings (plug/all-mappings xml-reg)  xml-stdlib)

          json-rec  (-> ((:importer jp) opts json-source)          :records first)
          xml-rec   (-> ((:importer xp) opts indented-xml-source)  :records first)

          ingest-xml-frag-values
          (filterv (fn [a]
                     (and (contains? #{:frag/record.r1.dc-title.0
                                       :frag/record.r1.dc-title.1}
                                     (:subject a))
                          (= :dc/title (:predicate a))))
                   (:assertions xml-rec))]

      (testing "at ingest, XML fragment values carry indentation whitespace verbatim"
        (is (= 2 (count ingest-xml-frag-values)))
        (is (every? (fn [a]
                      (let [v (:value a)]
                        (and (string? v)
                             (re-find #"\n" v)
                             (not= v (str/trim v)))))
                    ingest-xml-frag-values)
            "shape adapter preserved the surrounding `\\n  ` from pretty-printing"))

      (let [jn (:record (runtime/run-phase json-rec json-compiled :normalize))
            xn (:record (runtime/run-phase xml-rec  xml-compiled  :normalize))]

        (testing "after :normalize, both formats agree on the canonical record-level titles"
          (is (= (set (canonical-record-titles jn))
                 (set (canonical-record-titles xn)))))

        (testing "after :normalize, fragment-level :canon/title and :canon/lang match across formats"
          (let [pick-canon (fn [pairs]
                             (set (filter (fn [[p _]]
                                            (#{:canon/title :canon/lang} p))
                                          pairs)))
                jn-frags   (fragment-canonical-pairs jn)
                xn-frags   (fragment-canonical-pairs xn)]
            (is (= (set (keys jn-frags)) (set (keys xn-frags))))
            (doseq [frag-id (keys jn-frags)]
              (is (= (pick-canon (get jn-frags frag-id))
                     (pick-canon (get xn-frags frag-id)))
                  (str "canonical assertions diverge on " frag-id)))))

        (testing "specifically: the trimmed XML titles equal the JSON titles"
          (let [xn-frags (fragment-canonical-pairs xn)]
            (is (contains? (get xn-frags :frag/record.r1.dc-title.0)
                           [:canon/title "Les Misérables"]))
            (is (contains? (get xn-frags :frag/record.r1.dc-title.0)
                           [:canon/lang "fr"]))
            (is (contains? (get xn-frags :frag/record.r1.dc-title.1)
                           [:canon/title "The Wretched"]))
            (is (contains? (get xn-frags :frag/record.r1.dc-title.1)
                           [:canon/lang "en"]))))))))

(deftest trace-attributes-canonical-assertions-to-the-mapping-rule
  (testing "provenance correctly identifies which mapping fired each canonical assertion"
    (let [jp        (shape/shape-json-plugin {:id :plugin/dc-json :mapping json-mapping})
          registry  (plug/register plug/empty-registry jp)
          stdlib    (plug/effective-transforms registry)
          compiled  (mapping/compile-mappings (plug/all-mappings registry) stdlib)
          json-rec  (-> ((:importer jp) opts json-source) :records first)
          jn        (:record (runtime/run-phase json-rec compiled :normalize))
          by-rule   (runtime/assertions-by-rule jn :rule.from-mapping/dc-title-json)]
      (is (seq by-rule))
      (is (every? #(= :normalize (get-in % [:provenance :pass])) by-rule))
      (is (every? #(= :rule.from-mapping/dc-title-json
                      (get-in % [:provenance :rule]))
                  by-rule)))))
