(ns regesta.plugins.lrmoo.export
  "RDF export of the LRMoo view (WP-2, ADR 0013). Renders a record's WEMI
   entities and their `:lrmoo/*` assertions as RDF — N-Triples for now (the
   canonical, line-based syntax). Turtle and JSON-LD are thin follow-ons over
   the same `triples` seq. Because local LRMoo names are the spec's own, class
   and predicate IRIs come straight from `lrmoo/iri`.

   `triples` yields the graph as data (`[s p o]`, `o` = `{:iri _}` | `{:lit _}`);
   `exporter` follows the ADR 0007 exporter contract. Only `:lrmoo/*`
   predicates are exported by this slice; canonical/native predicates serialise
   once their own IRI mappings land. Wiring this into the LRMoo plugin map's
   `:exporter` is a later assembly step (kept out here to avoid a cycle with
   the vocabulary namespace)."
  (:require [clojure.string :as str]
            [regesta.diagnostics :as dx]
            [regesta.model :as model]
            [regesta.plugins.lrmoo :as lrmoo]))

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

(defn entity-iri
  "Fallback IRI for an entity id, used when the entity carries no authority
   `:iri`. A keyword (the minted `:ent/*` case) becomes a `urn:regesta:` IRI; a
   string id is assumed to be an IRI already and passes through."
  [id]
  (cond
    (keyword? id) (str "urn:regesta:" (namespace id) ":" (name id))
    (string? id)  id
    :else         (str "urn:regesta:" id)))

(defn- iri-index
  "Map entity id → its authority `:iri`, for the entities of `record` that carry
   one (e.g. a Manifestation's data.bnf ARK). Export prefers these over the
   synthetic `urn:regesta:` fallback, for both subject and reference-object IRIs."
  [record]
  (into {} (keep (fn [e] (when-let [i (:iri e)] [(:id e) i]))) (:entities record)))

(defn- lrmoo-predicate? [p]
  (and (keyword? p) (= "lrmoo" (namespace p))))

(defn triples
  "The LRMoo view of `record` as RDF triples. Each WEMI entity is typed to its
   F-class; each `:lrmoo/*` assertion becomes an object-property triple (when
   its value is a reference) or a literal triple. Each item is `[s-iri p-iri o]`
   with `o` = `{:iri s}` | `{:lit v}`."
  [record]
  (let [idx (iri-index record)
        iri (fn [id] (or (get idx id) (entity-iri id)))]
    (concat
     (for [e (:entities record)
           :when (lrmoo/entity-kind? (:kind e))]
       [(iri (:id e)) rdf-type {:iri (lrmoo/iri (:kind e))}])
     (for [a (:assertions record)
           :when (lrmoo-predicate? (:predicate a))]
       (let [v (:value a)]
         [(iri (:subject a))
          (lrmoo/iri (:predicate a))
          (if (model/reference-value? v)
            {:iri (iri (:value/target v))}
            {:lit v})])))))

(defn- nt-escape [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- nt-object [o]
  (if (:iri o)
    (str "<" (:iri o) ">")
    (str "\"" (nt-escape (:lit o)) "\"")))

(defn render-ntriples
  "Render a seq of `[s p o]` triples (o = `{:iri _}` | `{:lit _}`) as N-Triples,
   one `<s> <p> o .` per line. Reused by alternative projections (e.g. the CRM
   down-projection) that augment the triple seq before rendering."
  [triples]
  (str/join "\n"
            (for [[s p o] triples]
              (str "<" s "> <" p "> " (nt-object o) " ."))))

(defn ->ntriples
  "Render `record`'s LRMoo triples as N-Triples. Returns \"\" for a record with no
   LRMoo content."
  [record]
  (render-ntriples (triples record)))

(defn export-losses
  "Export-edge loss (ADR 0015): this exporter serialises only the `:lrmoo/*`
   view, so every other predicate the record carries (native `:intermarc/*`,
   canonical, …) is *dropped* from the N-Triples target. One `:loss/dropped`
   diagnostic per distinct dropped predicate per subject, `:edge :export`."
  [record]
  (->> (:assertions record)
       (remove #(lrmoo-predicate? (:predicate %)))
       (map (juxt :subject :predicate))
       distinct
       (mapv (fn [[subject pred]]
               (dx/loss {:category     :dropped
                         :subject      subject
                         :edge         :export
                         :source-field pred
                         :message      (str (pr-str pred)
                                            " not expressible in the LRMoo RDF export")})))))

(defn exporter
  "ADR 0007 exporter: render the LRMoo view of `records` as one N-Triples
   document, and report what the target cannot express as export-edge loss
   (ADR 0015). `(fn [opts records] -> {:output String :diagnostics [...]})`."
  [_opts records]
  {:output      (->> records
                     (into [] (comp (map ->ntriples) (remove str/blank?)))
                     (str/join "\n"))
   :diagnostics (into [] (mapcat export-losses) records)})
