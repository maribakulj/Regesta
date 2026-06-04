(ns regesta.convert
  "End-to-end conversion assembly (the institution-facing keystone): a source
   document in any supported spoke format → the WEMI/LRMoo pivot → a chosen target
   serialisation, with the ADR 0015 loss report over every edge.

   This is the namespace that wires the parts the rest of the system built and the
   convergence capstone proved compose: the five importers, the two projection
   rungs (INTERMARC's enriched `frbrise`, the floor `project` for the others), and
   the ten exporters. `convert` returns the output string plus the conversion
   loss report, in one call.

   Loss is collected at three points and merged: import-edge (a spoke's
   report-at-ingest, e.g. Dublin Core / MODS / IIIF unmodelled elements), the
   projection edge (canonical fields with no WEMI home, frbrise loss), and the
   export edge (what the chosen target cannot express). The per-edge, per-field
   account is exactly `regesta.loss-report`'s artifact."
  (:require [clojure.string :as str]
            [regesta.diagnostics :as dx]
            [regesta.loss-report :as lr]
            [regesta.plugins :as plug]
            [regesta.plugins.dc.export :as dc-export]
            [regesta.plugins.iiif.export :as iiif-export]
            [regesta.plugins.intermarc.frbrise :as frbrise]
            [regesta.plugins.lrmoo.crm :as crm]
            [regesta.plugins.lrmoo.export :as export]
            [regesta.plugins.lrmoo.linked-art :as linked-art]
            [regesta.plugins.lrmoo.project :as project]
            [regesta.plugins.mapping :as mapping]
            [regesta.plugins.marc21.export :as marc21-export]
            [regesta.plugins.mods.export :as mods-export]
            [regesta.runtime :as runtime]
            [regesta.spokes :as spokes]))

;; ---------------------------------------------------------------------------
;; Registries — the source spokes live in `regesta.spokes` (shared with
;; `regesta.validate`); convert owns the projection rung per spoke and the
;; target serialisations.
;; ---------------------------------------------------------------------------

(def ^:private to-pivots
  "Source format -> the projection that lifts a normalised record to WEMI.
   INTERMARC takes the *enriched* `frbrise` rung (the 145 $3 authority link) and
   then mints its authority-identified agent; the rest take the floor `project`.
   Every spoke normalises to `:canon/*` first (in `to-wemi`)."
  {:intermarc (comp frbrise/with-identified-agent frbrise/frbrise)
   :dc        project/project
   :marc21    project/project
   :mods      project/project
   :iiif      project/project})

(defn- crm-only-losses [record]
  (concat (crm/crm-only-losses record) (export/export-losses record)))

(def exporters
  "Target format -> {:render <record -> string> :losses <record -> [loss]>}.
   Every renderer takes a projected WEMI record (which retains its canonical and
   native assertions, so the round-trip exporters read `:canon/*` off it)."
  {:ntriples   {:render export/->ntriples            :losses export/export-losses}
   :turtle     {:render export/->turtle              :losses export/export-losses}
   :jsonld     {:render export/->jsonld              :losses export/export-losses}
   :crm        {:render crm/->ntriples               :losses export/export-losses}
   :crm-only   {:render crm/->crm-only-ntriples      :losses crm-only-losses}
   :linked-art {:render linked-art/->jsonld          :losses linked-art/export-losses}
   :dc         {:render dc-export/->dc-xml           :losses dc-export/export-losses}
   :marc21     {:render marc21-export/->marcxml      :losses marc21-export/export-losses}
   :mods       {:render mods-export/->mods-xml       :losses mods-export/export-losses}
   :iiif       {:render iiif-export/->json           :losses iiif-export/export-losses}})

(defn source-formats [] (spokes/source-formats))
(defn target-formats [] (set (keys exporters)))

;; ---------------------------------------------------------------------------
;; Pipeline
;; ---------------------------------------------------------------------------

(defn- to-wemi
  "Import `source` through `spoke`, normalise to the canonical floor, then project
   each record to WEMI by the appropriate rung — INTERMARC's enriched `frbrise`
   (via the 145 $3 link) or the floor `project`. Both run normalize first, so
   `:canon/*` is populated for every spoke (the round-trip exporters read it).
   Returns `{:records [wemi…] :ingest [loss…]}`."
  [from opts source]
  (let [plugin   (spokes/plugin from)
        to-pivot (to-pivots from)
        {:keys [records diagnostics]} ((:importer plugin) opts source)
        reg      (plug/register plug/empty-registry plugin)
        compiled (mapping/compile-mappings (plug/all-mappings reg)
                                           (plug/effective-transforms reg))]
    {:ingest  diagnostics
     :records (mapv #(to-pivot (:record (runtime/run-phase % compiled :normalize))) records)}))

(defn convert
  "Convert `source` from spoke `from` to serialisation `to`. Returns
   `{:output String :loss <conversion-report> :records N}`.

   `opts` is threaded to the importer (e.g. `:record-id` for the single-record
   spokes). Throws with the supported sets on an unknown `from`/`to`."
  [{:keys [from to source opts] :or {opts {}}}]
  (when-not (contains? spokes/plugins from)
    (throw (ex-info "Unknown source format" {:from from :supported (source-formats)})))
  (when-not (contains? exporters to)
    (throw (ex-info "Unknown target format" {:to to :supported (target-formats)})))
  (let [{:keys [render losses]} (exporters to)
        {:keys [records ingest]} (to-wemi from opts source)
        projection-loss (dx/collect-many records)
        export-loss     (into [] (mapcat losses) records)
        output          (->> records
                             (into [] (comp (map render) (remove str/blank?)))
                             (str/join "\n"))]
    {:output  output
     :records (count records)
     :loss    (lr/conversion-report (concat ingest projection-loss export-loss)
                                    {:records (count records)})}))

(defn convert-report
  "Convenience: `convert` plus the human-readable loss report appended, for CLI /
   audit display."
  [request]
  (let [{:keys [loss] :as result} (convert request)]
    (assoc result :report (lr/format-conversion-report loss))))
