(ns regesta.validate
  "Validation pass — the institutional gate that complements `regesta.convert`
   (validate *before* you convert). Import a source, normalise it to the canonical
   floor, run the canonical `:validate` rules (ADR 0003), and return the
   diagnostics with a pass/fail verdict under a failure policy.

   It reuses `regesta.convert`'s source-spoke registry, registers the canonical
   plugin alongside, and runs the `:normalize` → `:validate` pipeline. Loss is a
   separate concern (that is `convert`/`loss-report`); this answers only \"is the
   canonical record valid?\"."
  (:require [regesta.convert :as convert]
            [regesta.diagnostics :as dx]
            [regesta.plugins :as plug]
            [regesta.plugins.canonical :as canonical]
            [regesta.plugins.mapping :as mapping]
            [regesta.rules :as rules]
            [regesta.runtime :as runtime]))

(def ^:private pipeline [{:phase :normalize} {:phase :validate}])

(defn validate
  "Validate `source` (source spoke `from`) against the canonical rules. Returns:

     {:records N :diagnostics [...] :summary {...} :failed? bool}

   `opts` is threaded to the importer (e.g. `:record-id`); `policy`
   (∈ `dx/failure-policies`, default `:errors-only`) decides `:failed?`. Throws on
   an unknown `from`."
  [{:keys [from source opts policy] :or {opts {} policy :errors-only}}]
  (when-not (contains? convert/importers from)
    (throw (ex-info "Unknown source format" {:from from :supported (convert/source-formats)})))
  (let [plugin    (:plugin (convert/importers from))
        registry  (-> plug/empty-registry
                      (plug/register plugin)
                      (plug/register canonical/plugin))
        compiled  (into (mapping/compile-mappings (plug/all-mappings registry)
                                                  (plug/effective-transforms registry))
                        (rules/compile-rules (plug/all-rules registry)))
        {:keys [records]} ((:importer plugin) opts source)
        validated (mapv #(:record (runtime/run-pipeline % compiled pipeline)) records)
        diags     (dx/collect-many validated)]
    {:records     (count validated)
     :diagnostics diags
     :summary     (dx/summary diags)
     :failed?     (dx/should-fail? diags policy)}))
