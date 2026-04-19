(ns metalisp.model
  "Canonical internal representation (IR).

   Defines schemas and constructors for Record, Assertion, Value, Provenance,
   Diagnostic, Repair and Fragment. No logic lives here — only data shapes,
   constructors, and shape-level predicates.

   The model knows only the structural vocabulary (`:meta/*`). Documentary
   predicates (title, agent, date, ...) live in `metalisp.plugins.canonical`
   or in format-specific plugins.

   See ADR 0001 (assertion-based IR), ADR 0003 (vocabulary layering) and
   ADR 0005 (status model)."
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Structural vocabulary
;;
;; The closed set of predicates the core itself recognizes. Anything else
;; comes from plugins. See ADR 0003.
;; ---------------------------------------------------------------------------

(def meta-id         :meta/id)
(def meta-kind       :meta/kind)
(def meta-source     :meta/source)
(def meta-fragment   :meta/fragment)
(def meta-diagnostic :meta/diagnostic)
(def meta-provenance :meta/provenance)

(def structural-vocabulary
  #{meta-id meta-kind meta-source meta-fragment meta-diagnostic meta-provenance})

(defn structural?
  "True if `predicate` belongs to the core's structural vocabulary."
  [predicate]
  (contains? structural-vocabulary predicate))

;; ---------------------------------------------------------------------------
;; Statuses (ADR 0005)
;;
;; Two disjoint families: machine truth and human workflow.
;; ---------------------------------------------------------------------------

(def machine-statuses  #{:asserted :proposed :retracted :superseded})
(def workflow-statuses #{:accepted :rejected :needs-review})
(def statuses          (into machine-statuses workflow-statuses))

(defn machine-status? [s] (contains? machine-statuses s))
(defn workflow-status? [s] (contains? workflow-statuses s))

;; ---------------------------------------------------------------------------
;; Scalar schemas
;; ---------------------------------------------------------------------------

(def Severity
  [:enum :error :warning :info])

(def Phase
  "The six phases of the pipeline. See ADR 0004."
  [:enum :ingest :normalize :validate :infer :repair :project])

(def Status
  (into [:enum] statuses))

(def Confidence
  [:and :double [:>= 0.0] [:<= 1.0]])

(def Id
  "A subject or record identifier. Opaque to the core."
  [:or :keyword :string :uuid])

(def Predicate
  "An assertion predicate. Always a keyword; the core does not interpret
   namespaces."
  :keyword)

;; ---------------------------------------------------------------------------
;; Provenance
;; ---------------------------------------------------------------------------

(def Provenance
  "Where an assertion came from. All fields optional; a bare `{}` is valid
   (e.g. for hand-constructed test data)."
  [:map
   [:source {:optional true} :any]
   [:pass {:optional true} Phase]
   [:rule {:optional true} :keyword]
   [:derivation {:optional true} [:vector Id]]
   [:timestamp {:optional true} inst?]])

;; ---------------------------------------------------------------------------
;; Fragment (pointer into raw source)
;; ---------------------------------------------------------------------------

(def Fragment
  [:map
   [:id Id]
   [:source :any]
   [:locator {:optional true} :any]
   [:raw {:optional true} :string]])

;; ---------------------------------------------------------------------------
;; Value
;;
;; Values are either bare primitives (the 90% case — strings, numbers,
;; keywords, etc.) or tagged maps for references, structured values and
;; uncertain values. Tagged maps carry a `:value/kind` discriminator.
;;
;; The schema is recursive: structured and uncertain values contain other
;; values, so we use Malli's local registry + :ref mechanism.
;; ---------------------------------------------------------------------------

(def Primitive
  "Bare primitive values. Doubles are clamped through :gen/fmap to reject
   NaN and Infinity during generation; the validator itself accepts any
   `:double`. This asymmetry preserves generator-based round-trip testability
   without tightening runtime validation."
  (let [finite-double [:double {:gen/fmap (fn [d]
                                            (if (or (Double/isNaN d)
                                                    (Double/isInfinite d))
                                              0.0
                                              d))}]]
    [:or :string :int finite-double :boolean :keyword :uuid inst?]))

(def Value
  [:schema
   {:registry
    {::value [:or
              Primitive
              [:ref ::reference]
              [:ref ::structured]
              [:ref ::uncertain]]

     ::reference [:map
                  [:value/kind [:= :reference]]
                  [:value/target Id]
                  [:value/role {:optional true} :keyword]]

     ::structured [:map
                   [:value/kind [:= :structured]]
                   [:value/fields [:map-of :keyword [:ref ::value]]]]

     ::uncertain [:map
                  [:value/kind [:= :uncertain]]
                  [:value/alternatives [:vector [:ref ::value]]]
                  [:value/basis {:optional true} :any]]}}
   [:ref ::value]])

(defn reference-value? [x]
  (and (map? x) (= :reference (:value/kind x))))

(defn structured-value? [x]
  (and (map? x) (= :structured (:value/kind x))))

(defn uncertain-value? [x]
  (and (map? x) (= :uncertain (:value/kind x))))

(defn primitive-value? [x]
  (or (string? x) (int? x) (double? x) (boolean? x)
      (keyword? x) (uuid? x) (inst? x)))

;; ---------------------------------------------------------------------------
;; Assertion
;; ---------------------------------------------------------------------------

(def Assertion
  [:map
   [:subject Id]
   [:predicate Predicate]
   [:value Value]
   [:provenance {:optional true} Provenance]
   [:confidence {:optional true} Confidence]
   [:status {:optional true} Status]])

;; ---------------------------------------------------------------------------
;; Repair
;; ---------------------------------------------------------------------------

(def Repair
  [:map
   [:description :string]
   [:operation :keyword]
   [:basis {:optional true} :any]
   [:applicable? {:optional true} :boolean]
   [:safe? {:optional true} :boolean]])

;; ---------------------------------------------------------------------------
;; Diagnostic
;; ---------------------------------------------------------------------------

(def Diagnostic
  [:map
   [:severity Severity]
   [:code :keyword]
   [:subject Id]
   [:message {:optional true} :string]
   [:repairs {:optional true} [:vector Repair]]
   [:provenance {:optional true} Provenance]])

;; ---------------------------------------------------------------------------
;; Record (top-level IR envelope)
;; ---------------------------------------------------------------------------

(def Record
  [:map
   [:id Id]
   [:kind :keyword]
   [:source {:optional true} :any]
   [:fragments {:optional true} [:vector Fragment]]
   [:assertions {:optional true} [:vector Assertion]]
   [:diagnostics {:optional true} [:vector Diagnostic]]
   [:provenance {:optional true} Provenance]])

;; ---------------------------------------------------------------------------
;; Validators
;; ---------------------------------------------------------------------------

(defn valid-record?     [x] (m/validate Record x))
(defn valid-assertion?  [x] (m/validate Assertion x))
(defn valid-diagnostic? [x] (m/validate Diagnostic x))
(defn valid-value?      [x] (m/validate Value x))
(defn valid-repair?     [x] (m/validate Repair x))
(defn valid-fragment?   [x] (m/validate Fragment x))
(defn valid-provenance? [x] (m/validate Provenance x))

(defn explain-record     [x] (m/explain Record x))
(defn explain-assertion  [x] (m/explain Assertion x))
(defn explain-diagnostic [x] (m/explain Diagnostic x))
(defn explain-value      [x] (m/explain Value x))

;; ---------------------------------------------------------------------------
;; Constructors
;;
;; Constructors return plain maps with sensible defaults applied. They do NOT
;; validate — callers that want validation call `valid-*?` or `explain-*`.
;; This keeps constructors cheap on the hot path.
;; ---------------------------------------------------------------------------

(defn reference
  "Construct a reference value pointing to `target` (an Id), optionally
   qualified by a `role` keyword."
  ([target] {:value/kind :reference :value/target target})
  ([target role] {:value/kind :reference :value/target target :value/role role}))

(defn structured
  "Construct a structured value from a map of keyword → Value."
  [fields]
  {:value/kind :structured :value/fields fields})

(defn uncertain
  "Construct an uncertain value from a vector of alternatives, optionally
   annotated with `basis`."
  ([alternatives] {:value/kind :uncertain :value/alternatives (vec alternatives)})
  ([alternatives basis] {:value/kind :uncertain
                         :value/alternatives (vec alternatives)
                         :value/basis basis}))

(defn provenance
  "Construct a Provenance map. All fields optional; missing ones are omitted."
  [{:keys [source pass rule derivation timestamp]}]
  (cond-> {}
    source     (assoc :source source)
    pass       (assoc :pass pass)
    rule       (assoc :rule rule)
    derivation (assoc :derivation (vec derivation))
    timestamp  (assoc :timestamp timestamp)))

(defn fragment
  "Construct a Fragment. `id` and `source` are required."
  [{:keys [id source locator raw]}]
  (cond-> {:id id :source source}
    locator (assoc :locator locator)
    raw     (assoc :raw raw)))

(defn assertion
  "Construct an Assertion. Required: :subject, :predicate, :value.
   Defaults: :confidence 1.0, :status :asserted. :provenance is optional."
  [{:keys [subject predicate value provenance confidence status]
    :or   {confidence 1.0 status :asserted}}]
  (cond-> {:subject subject
           :predicate predicate
           :value value
           :confidence confidence
           :status status}
    provenance (assoc :provenance provenance)))

(defn repair
  "Construct a Repair proposal. Required: :description, :operation."
  [{:keys [description operation basis applicable? safe?]}]
  (cond-> {:description description :operation operation}
    (some? basis)       (assoc :basis basis)
    (some? applicable?) (assoc :applicable? applicable?)
    (some? safe?)       (assoc :safe? safe?)))

(defn diagnostic
  "Construct a Diagnostic. Required: :severity, :code, :subject."
  [{:keys [severity code subject message repairs provenance]}]
  (cond-> {:severity severity :code code :subject subject}
    message    (assoc :message message)
    repairs    (assoc :repairs (vec repairs))
    provenance (assoc :provenance provenance)))

(defn record
  "Construct a Record. Required: :id, :kind. Collections default to empty."
  [{:keys [id kind source fragments assertions diagnostics provenance]}]
  (cond-> {:id id :kind kind}
    (some? source) (assoc :source source)
    fragments      (assoc :fragments (vec fragments))
    assertions     (assoc :assertions (vec assertions))
    diagnostics    (assoc :diagnostics (vec diagnostics))
    provenance     (assoc :provenance provenance)))

;; ---------------------------------------------------------------------------
;; Status predicates
;; ---------------------------------------------------------------------------

(defn- status= [expected]
  (fn [x] (= expected (:status x))))

(def asserted?     (status= :asserted))
(def proposed?     (status= :proposed))
(def retracted?    (status= :retracted))
(def superseded?   (status= :superseded))
(def accepted?     (status= :accepted))
(def rejected?     (status= :rejected))
(def needs-review? (status= :needs-review))

(defn in-force?
  "Assertions the pipeline treats as currently true: :asserted or :accepted."
  [x]
  (let [s (:status x)]
    (or (= :asserted s) (= :accepted s))))

(defn pending?
  "Assertions that represent unresolved proposals awaiting human action
   or further machine processing."
  [x]
  (let [s (:status x)]
    (or (= :proposed s) (= :needs-review s))))

;; ---------------------------------------------------------------------------
;; Simple queries
;; ---------------------------------------------------------------------------

(defn assertions-for
  "Return the subset of a record's assertions whose predicate equals `p`."
  [record p]
  (filterv #(= p (:predicate %)) (:assertions record)))

(defn has-assertion?
  "True if the record contains at least one assertion with predicate `p`."
  [record p]
  (boolean (some #(= p (:predicate %)) (:assertions record))))
