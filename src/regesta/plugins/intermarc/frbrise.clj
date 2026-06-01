(ns regesta.plugins.intermarc.frbrise
  "INTERMARC → WEMI FRBRisation (WP-3, ADR 0016). A compiled `:infer` rule that
   reads `:intermarc/*` assertions and mints LRMoo entities, using the embedded
   authority link (the `f145_3` lookup validated in the WP-0 spike) — no fuzzy
   inference for authority-controlled records.

   This slice models the link the data carries directly:

     <mxc:record>                              -> F3_Manifestation (id from ARK)
     f145 ($m language, $3 authority id)       -> F2_Expression    (id from f145_3)
     R4_embodies                               Manifestation -> Expression

   So manifestations sharing an `f145_3` collapse to one Expression by id alone
   (ADR 0008) — lookup-based clustering, no batch state. The Work level (R3,
   from author + uniform title) and the fuzzy fallback for records without
   `f145` are a later slice (the spike showed the fallback needs bridging).
   Minted claims default to `:proposed` (ADR 0005); confidence-gating to
   `:asserted` is future (D7).

   A `compiled-rule` (not a data rule): it computes content-derived ids, which
   the data DSL deliberately cannot express (the mapping compiler does the same)."
  (:require [regesta.model :as model]
            [regesta.rules :as rules]
            [regesta.runtime :as runtime]))

(defn- field
  "First value of `record`'s assertion with predicate `pred`, or nil."
  [record pred]
  (->> (:assertions record) (filter #(= pred (:predicate %))) first :value))

(defn runner
  "FRBRisation productions for one INTERMARC record (see namespace doc)."
  [record]
  (let [rid   (:id record)
        prov  (model/provenance {:pass :infer :derivation [rid]})
        manif (model/mint-entity-id :lrmoo/F3_Manifestation (str rid))
        x3    (field record :intermarc/f145_3)]
    (cond-> [{:kind  :entity
              :value (model/entity {:id manif :kind :lrmoo/F3_Manifestation
                                    :provenance prov})}]
      x3
      (into (let [expr (model/mint-entity-id :lrmoo/F2_Expression x3)
                  ttl  (field record :intermarc/f145_a)]
              (cond-> [{:kind  :entity
                        :value (model/entity {:id expr :kind :lrmoo/F2_Expression
                                              :provenance prov})}
                       {:kind  :assertion
                        :value (model/assertion {:subject manif
                                                 :predicate :lrmoo/R4_embodies
                                                 :value (model/reference expr)
                                                 :status :proposed :provenance prov})}]
                ttl (conj {:kind  :assertion
                           :value (model/assertion {:subject expr
                                                    :predicate :lrmoo/R33_has_string
                                                    :value ttl :status :proposed
                                                    :provenance prov})})))))))

(def rule
  "Compiled `:infer` FRBRisation rule (ADR 0016)."
  (rules/compiled-rule {:id :rule.intermarc/frbrise :phase :infer :runner runner}))

(defn frbrise
  "Run INTERMARC FRBRisation over `record` (the `:infer` phase). Returns the
   enriched record — Manifestation/Expression entities and the R4 link."
  [record]
  (:record (runtime/run-phase record [rule] :infer)))
