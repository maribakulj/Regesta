(ns regesta.plugins-test
  "Unit tests for the plugin protocol and registry (Sprint 5 M2.A).
   Covers the Plugin schema, registry CRUD operations, query helpers,
   and the effective-stdlib pooling. `:requires` graph resolution and
   `:input-format` dispatch live in their own test namespace
   alongside the M2.B implementation."
  (:require [clojure.test :refer [deftest is testing]]
            [regesta.plugins :as plug]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def minimal-plugin
  {:plugin/spec-version 1
   :id                  :plugin/minimal})

(defn- make-importer-plugin
  ([id]
   (make-importer-plugin id :xml))
  ([id format]
   {:plugin/spec-version 1
    :id                  id
    :input-format        format
    :importer            (fn [_opts _src] {:records [] :diagnostics []})}))

(defn- make-exporter-plugin
  ([id]
   (make-exporter-plugin id :json-ld))
  ([id format]
   {:plugin/spec-version 1
    :id                  id
    :output-format       format
    :exporter            (fn [_opts _recs] {:output nil :diagnostics []})}))

;; ---------------------------------------------------------------------------
;; Plugin schema
;; ---------------------------------------------------------------------------

(deftest minimal-plugin-validates
  (is (plug/valid-plugin? minimal-plugin))
  (is (nil? (plug/explain-plugin minimal-plugin))))

(deftest full-plugin-validates
  (let [full {:plugin/spec-version 1
              :id                  :plugin/full
              :version             "1.0.0"
              :importer            (fn [_ _] nil)
              :exporter            (fn [_ _] nil)
              :matches?            (fn [_ _] true)
              :rules               [{:id :r/x :phase :validate :match [] :produce {}}]
              :mapping             [{:mapping/id :m/x}]
              :predicates          {'p (fn [_ _] true)}
              :transforms          {:t (fn [v] v)}
              :requires            #{:plugin/canonical}
              :input-format        :xml
              :output-format       :json-ld
              :doc                 "Test plugin"}]
    (is (plug/valid-plugin? full))))

(deftest plugin-rejects-missing-spec-version
  (is (not (plug/valid-plugin? {:id :plugin/x}))))

(deftest plugin-rejects-wrong-spec-version
  (is (not (plug/valid-plugin? {:plugin/spec-version 2 :id :plugin/x})))
  (is (not (plug/valid-plugin? {:plugin/spec-version "1" :id :plugin/x}))))

(deftest plugin-rejects-missing-id
  (is (not (plug/valid-plugin? {:plugin/spec-version 1}))))

(deftest plugin-rejects-non-keyword-id
  (is (not (plug/valid-plugin? {:plugin/spec-version 1 :id "plugin/x"})))
  (is (not (plug/valid-plugin? {:plugin/spec-version 1 :id 42}))))

(deftest plugin-rejects-unknown-keys
  (testing "closed schema catches typos like :rule (singular) instead of :rules"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :rule [{:id :r/x}])))))
  (testing "closed schema catches arbitrary unsanctioned keys"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :random-key "value"))))))

(deftest plugin-rejects-non-fn-importer
  (is (not (plug/valid-plugin?
            (assoc minimal-plugin :importer "not a fn")))))

(deftest plugin-rejects-non-set-requires
  (testing "requires must be a set, not a vector"
    (is (not (plug/valid-plugin?
              (assoc minimal-plugin :requires [:plugin/canonical]))))))

(deftest plugin-rejects-non-keyword-format
  (is (not (plug/valid-plugin?
            (assoc minimal-plugin :input-format "xml")))))

;; ---------------------------------------------------------------------------
;; Registry: register / unregister / lookup
;; ---------------------------------------------------------------------------

(deftest empty-registry-is-empty-map
  (is (= {} plug/empty-registry)))

(deftest register-adds-plugin
  (let [r (plug/register plug/empty-registry minimal-plugin)]
    (is (= minimal-plugin (plug/lookup r :plugin/minimal)))))

(deftest register-is-immutable
  (let [r1 plug/empty-registry
        r2 (plug/register r1 minimal-plugin)]
    (is (= {} r1) "original registry untouched")
    (is (not= r1 r2))))

(deftest register-rejects-malformed-plugin
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid plugin"
                        (plug/register plug/empty-registry
                                       {:id :plugin/no-version}))))

(deftest register-rejects-duplicate-id
  (let [r (plug/register plug/empty-registry minimal-plugin)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Duplicate plugin id"
                          (plug/register r minimal-plugin)))))

(deftest unregister-removes-plugin
  (let [r1 (plug/register plug/empty-registry minimal-plugin)
        r2 (plug/unregister r1 :plugin/minimal)]
    (is (nil? (plug/lookup r2 :plugin/minimal)))))

(deftest unregister-is-noop-on-missing
  (is (= plug/empty-registry
         (plug/unregister plug/empty-registry :plugin/nonexistent))))

(deftest lookup-returns-nil-on-missing
  (is (nil? (plug/lookup plug/empty-registry :plugin/nonexistent))))

(deftest registered-ids-returns-set
  (let [r (-> plug/empty-registry
              (plug/register (make-importer-plugin :plugin/a))
              (plug/register (make-importer-plugin :plugin/b)))]
    (is (= #{:plugin/a :plugin/b} (plug/registered-ids r)))))

;; ---------------------------------------------------------------------------
;; Queries: plugins-for-format / importers-for / exporters-for
;; ---------------------------------------------------------------------------

(deftest plugins-for-format-filters
  (let [r (-> plug/empty-registry
              (plug/register (make-importer-plugin :plugin/xml1 :xml))
              (plug/register (make-importer-plugin :plugin/xml2 :xml))
              (plug/register (make-importer-plugin :plugin/json :json)))]
    (is (= 2 (count (plug/plugins-for-format r :xml))))
    (is (= 1 (count (plug/plugins-for-format r :json))))
    (is (= 0 (count (plug/plugins-for-format r :csv))))))

(deftest importers-for-one-arg-filters-by-importer-presence
  (let [r (-> plug/empty-registry
              (plug/register (make-importer-plugin :plugin/imp))
              (plug/register (make-exporter-plugin :plugin/exp)))]
    (is (= 1 (count (plug/importers-for r))))
    (is (= :plugin/imp (:id (first (plug/importers-for r)))))))

(deftest importers-for-two-arg-filters-by-format
  (let [r (-> plug/empty-registry
              (plug/register (make-importer-plugin :plugin/xml :xml))
              (plug/register (make-importer-plugin :plugin/json :json)))]
    (is (= 1 (count (plug/importers-for r :xml))))
    (is (= :plugin/xml (:id (first (plug/importers-for r :xml)))))))

(deftest exporters-for-two-arg-filters-by-output-format
  (let [r (-> plug/empty-registry
              (plug/register (make-exporter-plugin :plugin/jl :json-ld))
              (plug/register (make-exporter-plugin :plugin/turtle :turtle)))]
    (is (= 1 (count (plug/exporters-for r :json-ld))))
    (is (= :plugin/jl (:id (first (plug/exporters-for r :json-ld)))))))

;; ---------------------------------------------------------------------------
;; Queries: all-rules / all-mappings
;; ---------------------------------------------------------------------------

(deftest all-rules-concatenates-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :rules [{:id :r/a :phase :validate
                                             :match [] :produce {}}]))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :rules [{:id :r/b :phase :normalize
                                             :match [] :produce {}}
                                            {:id :r/c :phase :infer
                                             :match [] :produce {}}])))]
    (is (= 3 (count (plug/all-rules r))))
    (is (= #{:r/a :r/b :r/c}
           (set (map :id (plug/all-rules r)))))))

(deftest all-mappings-concatenates-across-plugins
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :mapping [{:mapping/id :m/a}]))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :mapping [{:mapping/id :m/b}
                                              {:mapping/id :m/c}])))]
    (is (= 3 (count (plug/all-mappings r))))))

(deftest all-rules-empty-when-no-plugins-contribute
  (is (= [] (plug/all-rules plug/empty-registry))))

;; ---------------------------------------------------------------------------
;; Effective stdlib (ADR 0010)
;; ---------------------------------------------------------------------------

(deftest effective-stdlib-merges-predicates
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :predicates {'foo? (fn [_ _] true)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :predicates {'bar? (fn [_ _] false)})))]
    (let [{:keys [predicates]} (plug/effective-stdlib r)]
      (is (= #{'foo? 'bar?} (set (keys predicates))))
      (is (fn? (get predicates 'foo?)))
      (is (fn? (get predicates 'bar?))))))

(deftest effective-stdlib-merges-transforms
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :transforms {:tx-a (fn [v] v)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :transforms {:tx-b (fn [v] v)})))]
    (let [{:keys [transforms]} (plug/effective-stdlib r)]
      (is (= #{:tx-a :tx-b} (set (keys transforms)))))))

(deftest effective-stdlib-rejects-predicate-collision
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :predicates {'shared? (fn [_ _] true)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :predicates {'shared? (fn [_ _] false)})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Predicate name collision"
                          (plug/effective-stdlib r)))))

(deftest effective-stdlib-rejects-transform-collision
  (let [r (-> plug/empty-registry
              (plug/register (assoc minimal-plugin
                                    :transforms {:tx (fn [v] v)}))
              (plug/register (assoc minimal-plugin
                                    :id :plugin/b
                                    :transforms {:tx (fn [v] (str v))})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Transform name collision"
                          (plug/effective-stdlib r)))))

(deftest effective-stdlib-returns-plain-maps
  (testing "no metadata leaks from the internal source-tracking"
    (let [r (plug/register plug/empty-registry
                           (assoc minimal-plugin
                                  :predicates {'p (fn [_ _] true)}
                                  :transforms {:t (fn [v] v)}))
          {:keys [predicates transforms]} (plug/effective-stdlib r)]
      (is (nil? (meta predicates)))
      (is (nil? (meta transforms))))))

(deftest effective-stdlib-empty-when-no-plugins-contribute
  (is (= {:predicates {} :transforms {}}
         (plug/effective-stdlib plug/empty-registry))))
