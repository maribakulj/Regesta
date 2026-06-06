(ns regesta.plugins.unimarc
  "UNIMARC-SRU importer (WP-4, ADR 0007) — the BnF's public diffusion format and
   the third MARC-family spoke, completing MARC21 / INTERMARC / UNIMARC.

   The wire shape is the same MARCXChange XML the BnF SRU returns for INTERMARC
   (`format=\"UNIMARC\"`), so the field/subfield parse is the shared
   `regesta.plugins.marcxml` core; this namespace supplies only the UNIMARC
   policies (a record is an `<mxc:record>` carrying the `id` ARK; its id is
   `:bnf/<cb-number>`) and the UNIMARC→canonical mapping. UNIMARC tag semantics
   differ from MARC21/INTERMARC:

   | UNIMARC                                  | canonical               |
   |------------------------------------------|-------------------------|
   | `200 $a` (titre propre)                  | `:canon/title`          |
   | `500 $a` (titre uniforme)                | `:canon/uniform-title`  |
   | `700/701/702 $a`, `710/711/712 $a`       | `:canon/agent`          |
   | `210 $d` (date de publication)           | `:canon/date`           |
   | `010 $a` ISBN, `011 $a` ISSN, `001`      | `:canon/identifier`     |
   | `856 $u`                                 | `:canon/digital-object` |
   | `300/327/330 $a` (notes / contents / abstract) | `:canon/note`     |

   The author's forename (`700 $b`) is not paired with its surname (`$a`): UNIMARC
   `7xx` is repeatable, and the combine mapping joins *first-of-each* predicate,
   which would mis-pair a multi-author record — so, as for MARC21, only the
   controlled entry element `$a` is lifted (forename pairing needs field-level
   fragments, a documented floor limitation). Subjects (`6xx`), the coded fields
   (`1xx`), series (`4xx`) and physical description (`215`) have no floor home and
   are dropped — the MARC analogue of the other spokes' report-at-ingest loss,
   surfaced by the round-trip exporters."
  (:require [clojure.string :as str]
            [regesta.plugins.marcxml :as marcxml]))

(defn- mxc-record?
  "True for an `<mxc:record>` (local name `record` carrying the `id` ARK), the BnF
   SRU bibliographic/authority record, vs the `<srw:record>` wrapper (no `id`)."
  [elem]
  (and (= "record" (marcxml/local-name elem)) (some? (marcxml/attr elem "id"))))

(defn- record-id-from-ark
  "`:bnf/<cb-number>` from an ARK like \"ark:/12148/cb39508046q\"."
  [ark]
  (keyword "bnf" (last (str/split ark #"/"))))

(defn ingest
  "Parse a UNIMARC MARCXChange `xml-string` into a vector of Records with native
   `:unimarc/*` assertions. `opts` may carry `:kind` (else from the record `type`)."
  [xml-string opts]
  (marcxml/parse-records
   xml-string
   {:ns        "unimarc"
    :record?   mxc-record?
    :record-id (fn [e] (record-id-from-ark (marcxml/attr e "id")))
    :kind      (fn [e] (or (:kind opts)
                           (keyword "unimarc"
                                    (str/lower-case (or (marcxml/attr e "type") "record")))))
    :source    (fn [e] (marcxml/attr e "id"))}))

(defn- source->string [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for UNIMARC"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "UNIMARC importer expects a string or tagged source"
                          {:source source}))))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics []})`."
  [opts source]
  {:records     (ingest (source->string source) opts)
   :diagnostics []})

(def mapping
  "UNIMARC→canonical mapping for the bibliographic core (ADR 0009). Several fields
   collapse onto one canonical predicate (the agents, the identifiers, the notes);
   the round-trip exporters report the collapse as loss (ADR 0015)."
  [{:mapping/id :map/unimarc-title :mapping/from :unimarc/f200_a :mapping/to :canon/title
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-uniform-500 :mapping/from :unimarc/f500_a :mapping/to :canon/uniform-title
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-author-700 :mapping/from :unimarc/f700_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-author-701 :mapping/from :unimarc/f701_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-author-702 :mapping/from :unimarc/f702_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-corporate-710 :mapping/from :unimarc/f710_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-corporate-711 :mapping/from :unimarc/f711_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-corporate-712 :mapping/from :unimarc/f712_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-date-210 :mapping/from :unimarc/f210_d :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-isbn-010 :mapping/from :unimarc/f010_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-issn-011 :mapping/from :unimarc/f011_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-url-856 :mapping/from :unimarc/f856_u :mapping/to :canon/digital-object
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-note-300 :mapping/from :unimarc/f300_a :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-contents-327 :mapping/from :unimarc/f327_a :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/unimarc-abstract-330 :mapping/from :unimarc/f330_a :mapping/to :canon/note
    :mapping/transform [:trim]}])

(def plugin
  "The UNIMARC (BnF MARCXChange) importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :regesta/unimarc
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :doc                 "UNIMARC (BnF diffusion) importer — fields/subfields as :unimarc/* assertions; bibliographic subset mapped to the canonical floor."})
