(ns regesta.loss-report
  "The conversion loss report (ADR 0015's headline deliverable, finally
   assembled). The loss *producers* (frbrise, project, export, mapping) emit
   first-class loss diagnostics; this namespace aggregates them into the artifact
   an institution audits: a per-edge, per-category, **per-source-field** account
   of what a conversion did not carry across.

   It is the round-trip accounting ADR 0015 describes — for a source A converted
   through the pivot to a target B, *which of A's own fields survived*. It reads
   only the structural loss vocabulary (`:loss/*` detail), never documentary
   predicates, so it stays vocabulary-blind (ADR 0003).

   Honest scope: this reports the loss measured at both edges of one conversion.
   A *full* round-trip that re-imports B and diffs it against A needs a reverse
   importer (future); the per-field survival account here is the assemblable,
   institution-useful 90 %."
  (:require [clojure.string :as str]
            [regesta.diagnostics :as dx]))

(defn conversion-report
  "Assemble the loss report for a conversion from its loss diagnostics — the
   merge of import-edge loss (on the records, via `dx/collect-many`) and
   export-edge loss (from the exporter result). Returns:

     {:total N
      :by-category     {category -> count}         ; aggregate, all edges
      :by-edge         {:import {:total :by-category :by-source-field}
                        :export {…}}
      :source-fields   [… distinct native fields lost, sorted …]}

   `opts` may carry `:records` (count) for context; it is echoed back under
   `:records`."
  ([loss-diagnostics] (conversion-report loss-diagnostics {}))
  ([loss-diagnostics {:keys [records]}]
   (let [ls          (dx/losses loss-diagnostics)
         edge-report (fn [es]
                       {:total           (count es)
                        :by-category     (dx/count-by-loss-category es)
                        :by-source-field (dx/count-by-source-field es)})]
     (cond-> {:total         (count ls)
              :by-category   (dx/count-by-loss-category ls)
              :by-edge       (into {} (map (fn [[e es]] [e (edge-report es)]))
                                   (group-by dx/loss-edge ls))
              :source-fields (vec (sort-by str (distinct (keep dx/loss-source-field ls))))}
       records (assoc :records records)))))

(defn- top-fields
  "The `n` most-lost source fields of a `by-source-field` map, as a string."
  [by-field n]
  (->> by-field
       (sort-by (juxt (comp - val) (comp str key)))
       (take n)
       (map (fn [[f c]] (str (pr-str f) " (" c ")")))
       (str/join ", ")))

(defn- category-line [by-cat]
  (->> by-cat
       (filter (comp pos? val))
       (sort-by (comp str key))
       (map (fn [[c n]] (str (name c) " " n)))
       (str/join ", ")))

(defn format-conversion-report
  "Human-readable rendering of a `conversion-report` (ADR 0015), for CLI /
   institutional audit. Never prints. Returns the empty-loss line when clean."
  [{:keys [total by-category by-edge records source-fields] :as _report}]
  (if (zero? total)
    (str "Loss report: lossless"
         (when records (str " (" records " records)")))
    (let [head (str "Loss report — " total " loss"
                    (when records (str " across " records " records"))
                    "\n  by category: " (category-line by-category)
                    "\n  source fields lost (" (count source-fields) "): "
                    (top-fields (reduce (fn [m e] (merge-with + m (:by-source-field e)))
                                        {} (vals by-edge))
                                10))
          edge-block (fn [edge]
                       (when-let [er (get by-edge edge)]
                         (str "\n  " (name edge) " edge — " (:total er)
                              " (" (category-line (:by-category er)) ")"
                              "\n    fields: " (top-fields (:by-source-field er) 8))))]
      (str head (edge-block :import) (edge-block :export)))))
