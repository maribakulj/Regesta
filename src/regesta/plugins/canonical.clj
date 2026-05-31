(ns regesta.plugins.canonical
  "Canonical documentary vocabulary plugin (ADR 0003).

   Two things live here, both as plain data:

   1. The `:canon/*` documentary vocabulary — the eight predicates that
      format plugins (Dublin Core, MARC, CIDOC, …) map their native
      terms onto so cross-source rules, validation, and projection can
      operate on a commensurable layer (ADR 0003 §Decision).
   2. The first validation rules over that layer. Sprint 5 stood up the
      ingest → normalize path (native source → `:canon/*` assertions);
      this plugin closes the loop by *enforcing* the canonical layer —
      `title-required` is the V1 rule and the template every later
      canonical rule follows.

   ## What is and isn't a documentary predicate

   The eight predicates describe *what* a resource is. They do not
   describe *how* a value is qualified — language, script, certainty.
   Qualifiers ride on fragments (ADR 0011), not on new top-level
   predicates. `:canon/lang` is the first such case: it is a qualifier
   coord on a fragment, minted by the shape adapter and renamed by
   qualified mappings (ADR 0009 §Qualifier). ADR 0003's documentary set
   is exactly eight predicates and `:canon/lang` is not among them — it
   is absent from `documentary-vocabulary` by design, not by omission.

   ## The core stays vocabulary-blind

   This is a plugin like any other (ADR 0003 §Consequences). The core —
   model, rules, runtime, diagnostics — never sees `:canon/*`; to it
   these are opaque keywords. The dependency arrow points one way: the
   core never depends on this namespace (guarded by
   `regesta.architecture-test`). Everything here is plain data
   (ADR 0007): no requires, nothing to load-order. Deep rule validity is
   the compiler's job — consumers `compile-rules` these, and
   `regesta.plugins.canonical-test` asserts they do so cleanly.
   Accordingly the vocabulary is versioned with this plugin, independent
   of the core spec-version (ADR 0003 §Consequences).")

;; ---------------------------------------------------------------------------
;; Documentary vocabulary (ADR 0003 §Decision)
;;
;; The closed V1 starter set. It grows by deliberate addition justified
;; by a concrete use case, never by anticipation (ADR 0003 §Consequences,
;; growth discipline). Mirrors `regesta.model/structural-vocabulary` for
;; the `:meta/*` layer: a set plus a membership predicate.
;; ---------------------------------------------------------------------------

(def documentary-vocabulary
  "The eight canonical documentary predicates listed in ADR 0003
   §Decision.

   Each names *what* is described; qualifiers such as `:canon/lang` are
   excluded by design — they ride on fragments (ADR 0011), see the
   namespace doc."
  #{:canon/title
    :canon/identifier
    :canon/agent
    :canon/date
    :canon/relation
    :canon/note
    :canon/digital-object
    :canon/loss-marker})

(defn documentary?
  "True if `predicate` is one of the eight canonical documentary
   predicates (ADR 0003 §Decision).

   The parallel of `regesta.model/structural?` for the documentary
   layer. Qualifier coords (`:canon/lang`, …), structural predicates
   (`:meta/*`) and native source predicates (`:dc/title`, …) are all
   false: only the eight are documentary."
  [predicate]
  (contains? documentary-vocabulary predicate))

;; ---------------------------------------------------------------------------
;; Validation rules (ADR 0002 rule DSL, run in the :validate phase)
;;
;; Validation rules are first-class data: they emit `:diagnostic`
;; productions, never throw, and attach the diagnostic to its subject
;; (ADR 0001). The runtime stamps each diagnostic's provenance with this
;; rule's id and the :validate phase, so `regesta.diagnostics` can filter
;; by rule or phase downstream.
;; ---------------------------------------------------------------------------

(def title-required-rule
  "Every record should carry a `:canon/title`. The `[?r :meta/kind ?k]`
   pattern anchors `?r` to the record id (every record has exactly one
   `:meta/kind`); the `absent?` guard then queries the record's unified
   triple view for any `:canon/title` and fires only when none is found.

   Severity is `:warning`, not `:error`: a titleless record is
   incomplete, not malformed — it still round-trips and projects. Under
   the default `:errors-only` failure policy a missing title does not
   fail a run; callers wanting stricter gating pass `:errors-and-warnings`
   to `regesta.diagnostics/should-fail?`. The `:missing-title` code
   matches the diagnostic vocabulary the runtime's own examples use."
  {:id      :rule.canonical/title-required
   :phase   :validate
   :match   '[[?r :meta/kind ?k]
              (absent? ?r :canon/title)]
   :produce {:diagnostic {:severity :warning
                          :code     :missing-title
                          :subject  '?r
                          :message  "Record has no :canon/title assertion"}}
   :doc     "Warn when a record carries no :canon/title (ADR 0003)."})

(def rules
  "The V1 canonical validation rule set. One grounded rule for now —
   `title-required` — exercised end-to-end by
   `regesta.canonical-integration-test`. Date/ISO and other cross-source
   rules wait for a concrete producer of the predicates they would check
   (growth discipline, ADR 0003 §Consequences)."
  [title-required-rule])

;; ---------------------------------------------------------------------------
;; Plugin map (ADR 0007)
;;
;; A plain data plugin carrying only `:rules`. It declares no importer or
;; mapping: format plugins produce `:canon/*`; this plugin validates it.
;; `regesta.plugins/all-rules` pools these for compilation, and any
;; registry that also holds a documentary plugin gets canonical
;; validation for free.
;; ---------------------------------------------------------------------------

(def plugin
  "The canonical vocabulary plugin (ADR 0003/0007): ships the documentary
   vocabulary (as the `documentary-vocabulary` value) and the canonical
   validation `rules`. Register it alongside any documentary plugin to
   enforce the canonical layer over that plugin's normalized output."
  {:plugin/spec-version 1
   :id                  :regesta/canonical
   :rules               rules
   :doc                 "Canonical documentary vocabulary and its V1 validation rules (ADR 0003)."})
