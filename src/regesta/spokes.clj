(ns regesta.spokes
  "The source-spoke registry — the format keyword → importer plugin map shared by
   the conversion assembly (`regesta.convert`) and the validation gate
   (`regesta.validate`).

   It is deliberately lightweight: it requires only the spoke *plugins* (their
   importers and `:mapping`), not the exporters or the WEMI projection. So
   listing formats or *validating* a source — neither of which exports — does not
   drag in the whole export stack (the coupling audit point A1)."
  (:require [regesta.plugins.dc :as dc]
            [regesta.plugins.iiif :as iiif]
            [regesta.plugins.intermarc :as intermarc]
            [regesta.plugins.intermarc-ng :as intermarc-ng]
            [regesta.plugins.marc21 :as marc21]
            [regesta.plugins.mods :as mods]
            [regesta.plugins.unimarc :as unimarc]))

(def plugins
  "Source format keyword → its ADR 0007 importer plugin."
  {:intermarc    intermarc/plugin
   :intermarc-ng intermarc-ng/plugin
   :unimarc      unimarc/plugin
   :marc21       marc21/plugin
   :dc           dc/plugin
   :mods         mods/plugin
   :iiif         iiif/plugin})

(defn source-formats
  "The set of supported source format keywords."
  []
  (set (keys plugins)))

(defn plugin
  "The importer plugin for source format `from`, or nil."
  [from]
  (get plugins from))
