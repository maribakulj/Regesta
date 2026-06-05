(ns regesta.conformance
  "Conformance mechanism (WP-6) — does a source's WEMI projection satisfy an
   institutional *profile*? A profile is a named set of checks expressed as
   ordinary diagnostics (ADR 0001); a conformance report is those diagnostics
   filtered to the profile, plus a pass/fail verdict under the shared failure
   policy (`regesta.diagnostics`). It mirrors `regesta.validate` — the mechanism an
   institution gates an *ingest* on — one rung up: the mechanism an institution
   gates a *target conversion* on.

   Dataless by construction. A profile's checks encode the **public** model of the
   target (here the Linked Art object model — https://linked.art). The only
   institution-specific input is the *acceptance threshold* — which severities a
   given institution treats as failing — and that is the `policy` parameter, exactly
   as in `regesta.validate`. So the BnF/Louvre acceptance *criteria* are a knob, not
   a blocker on building the mechanism.

   This is deliberately **not** the strict official-schema validation: that lives,
   test-only, in `regesta.eval.linked-art-conformance-test`, which validates our
   output against the official draft-2020-12 Linked Art schema (a stronger,
   external-oracle bar). A profile is the institution's *requirement set* — some
   requirements are stricter than the schema (an identified creator), some looser —
   answering 'is this good enough for THIS target?', filtered and policy-gated, in
   the project's own diagnostics vocabulary."
  (:require [regesta.convert :as convert]
            [regesta.diagnostics :as dx]
            [regesta.model :as model]
            [regesta.plugins.lrmoo.view :as view]))

;; ---------------------------------------------------------------------------
;; Profile model
;;
;; A check is a map {:id :severity :message :conformant?}. `conformant?` is a
;; predicate over a projected WEMI record; when it returns false the check emits
;; a conformance diagnostic with the given severity. A profile bundles checks
;; under an id and a human label.
;; ---------------------------------------------------------------------------

(defn- check->diagnostic
  "Run one profile `check` against `record`; return a conformance diagnostic when
   the record violates it, else nil. The diagnostic is a standard model Diagnostic
   coded `:conformance.<profile>/<check>` and tagged with the profile in `:detail`
   (so it is filterable, per the roadmap's 'diagnostics filtered to a profile')."
  [profile-id record {:keys [id severity message conformant?]}]
  (when-not (conformant? record)
    (model/diagnostic
     {:severity severity
      :code     (keyword (str "conformance." (name profile-id)) (name id))
      :subject  (:id record)
      :message  message
      :detail   {:conformance/profile profile-id}})))

(defn check-record
  "The conformance diagnostics `profile` raises against one projected `record`."
  [profile record]
  (into [] (keep #(check->diagnostic (:id profile) record %)) (:checks profile)))

(defn for-profile
  "Filter `diagnostics` to those a given `profile-id` produced (the report view)."
  [diagnostics profile-id]
  (filterv #(= profile-id (get-in % [:detail :conformance/profile])) diagnostics))

;; ---------------------------------------------------------------------------
;; The Linked Art (Louvre) profile — the public Linked Art object model
;; ---------------------------------------------------------------------------

(defn- titled?
  "The record carries a string title (`:lrmoo/R33_has_string`) — Linked Art's
   `identified_by` Primary Name."
  [record]
  (boolean (some #(and (= :lrmoo/R33_has_string (:predicate %)) (string? (:value %)))
                 (:assertions record))))

(defn- has-agent?
  [record]
  (boolean (some #(and (= :canon/agent (:predicate %)) (string? (:value %)))
                 (:assertions record))))

(defn- identified-agent?
  "An authority-identified creator entity (`:crm/E21_Person` with an `:iri`) — the
   D7-certified, reconcilable creator (ADR 0018)."
  [record]
  (boolean (some #(and (= :crm/E21_Person (:kind %)) (:iri %)) (:entities record))))

(defn- has-identifier?
  "A stable identifier: a Manifestation authority IRI, or a `:canon/identifier` —
   Linked Art's `identified_by` Identifier."
  [record]
  (boolean (or (some :iri (view/manifestations record))
               (some #(and (= :canon/identifier (:predicate %)) (string? (:value %)))
                     (:assertions record)))))

(def linked-art-profile
  "Linked Art (Louvre) — the museum target's requirement set, grounded in the
   public Linked Art object model. Two hard requirements (a HumanMadeObject root
   with a name), three richness warnings (the carried Expression, its Work, an
   identified creator), one identifier hint. The acceptance threshold is the
   caller's `policy`."
  {:id    :linked-art
   :label "Linked Art (Louvre)"
   :checks
   [{:id :root-human-made-object
     :severity :error
     :message "no Manifestation: Linked Art needs a HumanMadeObject root, none was projected"
     :conformant? #(seq (view/manifestations %))}
    {:id :has-name
     :severity :error
     :message "no title: Linked Art requires an identified_by Primary Name"
     :conformant? titled?}
    {:id :carries-expression
     :severity :warning
     :message "no Expression: the HumanMadeObject carries no LinguisticObject (thin object)"
     :conformant? #(seq (view/expressions %))}
    {:id :expression-realises-work
     :severity :warning
     :message "no Work: the Expression is not part_of a PropositionalObject (incomplete WEMI chain)"
     :conformant? #(seq (view/works %))}
    {:id :creator-identified
     :severity :warning
     :message "creator is a bare name, not an authority-identified Person (not reconcilable, ADR 0018)"
     :conformant? #(or (not (has-agent? %)) (identified-agent? %))}
    {:id :has-identifier
     :severity :info
     :message "no stable identifier (ARK / :canon/identifier) for an identified_by Identifier"
     :conformant? has-identifier?}]})

(def profiles
  "The institutional profiles the conformance mechanism ships."
  {:linked-art linked-art-profile})

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(defn conformance
  "Run institutional `profile` over `source` (source spoke `from`): import →
   normalise → project to WEMI → check. Returns

     {:profile :linked-art :label \"…\" :records N
      :diagnostics [...] :summary {...} :failed? bool}

   `opts` is threaded to the importer (e.g. `:record-id`); `policy` (∈
   `dx/failure-policies`, default `:errors-only`) is the acceptance threshold that
   decides `:failed?`. Throws on an unknown spoke (via `convert/to-wemi`)."
  [{:keys [from source opts profile policy] :or {opts {} policy :errors-only}}]
  (let [{:keys [records]} (convert/to-wemi from opts source)
        diags (into [] (mapcat #(check-record profile %)) records)]
    {:profile     (:id profile)
     :label       (:label profile)
     :records     (count records)
     :diagnostics diags
     :summary     (dx/summary diags)
     :failed?     (dx/should-fail? diags policy)}))
