(ns regesta.plugins.intermarc
  "INTERMARC-SRU importer (WP-4, ADR 0007).

   Parses BnF InterMARCXChange — the MARCXChange XML the BnF SRU API returns —
   into IR records carrying native `:intermarc/*` assertions. A focused parser
   (the structure is regular and known) rather than the generic shape adapter.

   Mapping:
   - one `<mxc:record>` → one Record, id `:bnf/<cb-number>` from its ARK,
     `:kind :intermarc/<type>` (e.g. `:intermarc/bibliographic`);
   - each `<controlfield tag=\"T\">` → `:intermarc/fT` = text;
   - each `<subfield code=\"C\">` of `<datafield tag=\"T\">` →
     `:intermarc/fT_C` = text (repeatable: one assertion per occurrence).

   Tags and subfield codes are numeric, so predicate *names* are prefixed `f`
   (`:intermarc/f145_3`): a keyword name may not start with a digit, and an
   un-prefixed `:intermarc/145_3` would not round-trip through EDN (ADR 0001).
   This subfield granularity is exactly what FRBRisation reads — `f145_3` is the
   embedded Work-authority link, `f100_3` the author's, `f245_a` the title.

   Like every plugin (ADR 0007) this exposes an `:importer` closure; the core
   never sees `:intermarc/*`. Indicators and the leader are not yet captured
   (a later refinement)."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [regesta.model :as model]))

;; ---------------------------------------------------------------------------
;; data.xml element helpers
;;
;; clojure.data.xml represents an element as {:tag :attrs :content}. We match
;; by *local name* (`(name tag)`) so namespace-URI encoding is irrelevant, and
;; read attributes by their local name for the same reason.
;; ---------------------------------------------------------------------------

(defn- local-name [elem]
  (when (and (map? elem) (:tag elem))
    (name (:tag elem))))

(defn- attr
  "Attribute value whose key's local name is `k` (a string), or nil."
  [elem k]
  (some (fn [[ak av]] (when (= k (name ak)) av)) (:attrs elem)))

(defn- text-of
  "Concatenated string content of `elem`, or nil if it has none."
  [elem]
  (let [ss (filter string? (:content elem))]
    (when (seq ss) (apply str ss))))

(defn- elements
  "Depth-first seq of every element map in a parsed data.xml `tree`."
  [tree]
  (when (map? tree)
    (cons tree (mapcat elements (:content tree)))))

(defn- mxc-record?
  "True for an `<mxc:record>` (local name `record` carrying the `id` ARK),
   distinguishing it from the SRU `<srw:record>` wrapper, which has none."
  [elem]
  (and (= "record" (local-name elem)) (some? (attr elem "id"))))

;; ---------------------------------------------------------------------------
;; Record assembly
;; ---------------------------------------------------------------------------

(defn- record-id-from-ark
  "`:bnf/<cb-number>` from an ARK like \"ark:/12148/cb304403926\"."
  [ark]
  (keyword "bnf" (last (str/split ark #"/"))))

(defn- field-assertions
  "Assertions for one control/data field element of a record."
  [record-id field]
  (let [tag (attr field "tag")]
    (case (local-name field)
      "controlfield"
      (when-let [v (text-of field)]
        [(model/assertion {:subject record-id
                           :predicate (keyword "intermarc" (str "f" tag))
                           :value v
                           :provenance (model/provenance {:pass :ingest})})])
      "datafield"
      (for [sf (:content field)
            :when (= "subfield" (local-name sf))
            :let [code (attr sf "code")
                  v    (text-of sf)]
            :when (and code v)]
        (model/assertion {:subject record-id
                          :predicate (keyword "intermarc" (str "f" tag "_" code))
                          :value v
                          :provenance (model/provenance {:pass :ingest})}))
      nil)))

(defn- parse-record [opts elem]
  (let [ark  (attr elem "id")
        rid  (record-id-from-ark ark)
        typ  (attr elem "type")
        kind (or (:kind opts)
                 (keyword "intermarc" (str/lower-case (or typ "record"))))
        as   (vec (mapcat #(field-assertions rid %) (:content elem)))]
    (model/record {:id rid :kind kind :source ark :assertions as})))

;; ---------------------------------------------------------------------------
;; Public API + plugin
;; ---------------------------------------------------------------------------

(defn ingest
  "Parse an InterMARCXChange `xml-string` into a vector of Records. `opts` may
   carry `:kind` (else derived from each record's `type`)."
  [xml-string opts]
  (->> (xml/parse-str xml-string)
       elements
       (filter mxc-record?)
       (mapv #(parse-record opts %))))

(defn- source->string
  "Accept either a raw string or an ADR 0007 tagged source map."
  [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for INTERMARC"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "INTERMARC importer expects a string or tagged source"
                          {:source source}))))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics []})`."
  [opts source]
  {:records (ingest (source->string source) opts)
   :diagnostics []})

(def plugin
  "The INTERMARC-SRU importer plugin (ADR 0007)."
  {:plugin/spec-version 1
   :id                  :regesta/intermarc
   :input-format        :xml
   :importer            importer
   :doc                 "INTERMARC-SRU (InterMARCXChange) importer — fields/subfields as :intermarc/* assertions."})
