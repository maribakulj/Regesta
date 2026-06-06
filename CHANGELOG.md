# Changelog

All notable changes to Regesta are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version line for an unreleased work-in-progress is `[Unreleased]`. When a
release is cut, that section is renamed to the version and date and a fresh
`[Unreleased]` section is opened above it.

## [Unreleased]

The pre-1.0 development line. The Sprint 0–6 substrate and the rich-pivot
work packages WP-0…WP-8 are landed (seven spokes → the LRMoo pivot → ten
targets, loss-aware; conformance; streaming; the full CLI). WP-9
(hardening + release) is in progress; see `docs/roadmap-v1.md`.

### Added

- **Machine-readable loss report** (WP-9, loss-report UX) — `regesta report …
  --format edn` emits the raw `conversion-report` map (ADR 0015) instead of the
  human text: the complete per-edge / per-category / **per-source-field** account
  (not the text view's top-8 truncation), pretty-printed and lossless (namespaced
  keywords intact, e.g. `#:canon{:date 2}`). This is the auditor's machine
  surface — run-to-run diffs, CI loss thresholds, dashboards. `--format text`
  (the default) is unchanged; an unknown `--format` exits 2. **`--format json`**
  emits the same map for non-Clojure audit tooling, with every keyword — map key
  *and* vector element — rendered as a **namespace-qualified** string
  (`:canon/date` → `"canon/date"`), so the source-field keys that data.json's
  default `name` key-fn would otherwise collide stay distinct (`regesta.cli-test`).

- **Degenerate-input hardening** (WP-9) — `convert` now emits a stderr warning
  when a parse yields **0 records** (a wrong `--from`, or the wrong file), so a
  silent exit-0 that writes an empty output no longer masquerades as success.
  Exit stays 0 — an empty collection is legal, so this warns rather than fails.
  Malformed input (truncated / non-XML) already exits 2 with a parse error
  rather than crashing; both contracts are now pinned by `regesta.cli-test`.

- **Streaming conversion** (WP-7 / DoD #6) — `regesta.convert/convert-stream`
  converts a reducible/lazy record stream in **constant working set**: it `reduce`s
  one record at a time, emits each rendered document via a callback, and folds a
  loss report bounded by the distinct fields/categories/edges (not the record
  count), via a new `regesta.loss-report` fold (`empty-acc`/`accumulate`/
  `finalize`). Sound because Work convergence is id-collision, not a global pass
  (ADR 0008), so per-record conversion has no cross-record state — the converter
  streams its triples, a downstream store deduplicates by id (roadmap §10). The
  streamed output and loss report are byte-identical to batch `convert`, minus the
  one genuinely O(N) figure (`:distinct-losses`, batch-only). Measured budget:
  **100 000 records (MARC21→N-Triples) in a 512 MB heap, ~70 MB used, throughput
  flat ~4 000 rec/s**, the loss accumulator's footprint invariant in N
  (`docs/eval/scale.md`, `regesta.convert-stream-test`).
- **Lazy input parse + CLI `--stream`** (WP-7, the one-giant-file story closed for
  the MARC family). `marcxml/stream-records` pull-parses a `Reader`
  (`data.xml/parse`) into a **lazy** record seq — bounded to one record at a time —
  wired as a plugin `:stream-importer` on MARC21 / INTERMARC / UNIMARC
  (`convert/streamable?` / `stream-source`) and surfaced as `regesta convert <in>
  --from <marc-fmt> --to <fmt> --stream --out <file>`, which writes the document
  incrementally in bounded memory. End-to-end: a **97 MB / 56 000-record flat MARC
  dump → 64 MB N-Triples in a 256 MB heap, ~33 MB used**, where the eager
  `parse-str` path OOMs. Bounded by construction to the *flat-collection* shape
  (direct children — a deep tree-seq retains ancestors); SRU pages are small and stay
  eager (stream at page granularity), and the non-MARC single-record spokes don't
  stream (`--stream` rejects them with the streamable set). A new optional plugin key
  `:stream-importer` (`regesta.plugins` schema).
- **Uniform-title bridging** — the FRBRisation recall step the fidelity doc names
  ("D-series"). A ninth canonical predicate `:canon/uniform-title` (ADR 0003 growth
  discipline: the cataloguer's controlled work title, distinct from the transcribed
  `:canon/title`), mapped from MARC 240 `$a` (and emitted back on export, so the
  round-trip stays lossless). The floor projection (`lrmoo.project`) now keys the
  Work/Expression on the uniform title when a record carries one — so editions whose
  *transcribed* titles differ but whose *uniform* title agrees mint the same Work and
  cluster; the Manifestation keeps its transcribed title. Records without a uniform
  title fall back to the transcribed title (unchanged behaviour). Measured on the
  independent BIB-R gold: recall **0.775 → 0.823** at **no precision cost** (still
  1.000) — `docs/eval/bibr-frbrisation.md`.
  Wired across the floor family: MARC 240 `$a`, **UNIMARC 500 `$a`** (titre
  uniforme — on real BnF data it unifies the French editions and the German
  translation of one work into a single Work), and **MODS `<titleInfo
  type="uniform">`** (also round-tripped back on export). The MODS change is a
  latent-bug fix too: a uniform `titleInfo` was previously conflated into
  `:canon/title`; it now maps to `:canon/uniform-title`. (Dublin Core has no uniform
  title; INTERMARC keeps its richer `145 $3` authority-link rung.)
- Independent FRBRisation eval against the third-party **BIB-R** benchmark
  (`regesta.eval.bibr-frbrisation-test`, `test/fixtures/bibr-gold/`,
  `docs/eval/bibr-frbrisation.md`). BIB-R ("Benchmark of FRBRization solutions",
  bib-r.github.io, CC BY-NC) is a hand-curated MARC→FRBR/RDA gold with no
  dependence on the BnF `f145` link — the non-circular gold `frbrisation-fidelity.md`
  §3 called for, and a third corpus for ADR 0018's recall ceiling. Regesta ingests
  the 560 MARCXML records, clusters by minted F1 Work, and is scored pairwise against
  the gold Work grouping (records joined by normalised title): **P = 1.000,
  R = 0.775, F1 = 0.873** over the joinable subset (362 / 560 — the join coverage is
  asserted as a first-class number, since the MARC `001`s share no identifier with
  the gold's title-slug URIs). Precision-first with a *measured* title-variant recall
  gap, on a gold owing nothing to `f145`. The gold grouping is derived from the
  benchmark's FRBR RDF (provenance in the fixture README); the input is committed
  gzipped to respect repo footprint.
- `regesta.conformance` — the WP-6 conformance mechanism + three institutional
  profiles (Linked Art / Louvre, BnF INTERMARC, IIIF Presentation 3.0). A profile
  is a named set of checks expressed as ordinary diagnostics (ADR 0001) over a
  projected record; the report is those diagnostics filtered to the profile, plus
  a pass/fail verdict under the shared failure policy — mirroring
  `regesta.validate` one rung up. Dataless by construction: the checks encode a
  **public** model, and the only institution-specific input is the acceptance
  *threshold* (the `policy` knob). Two directions, one mechanism. The **Linked Art
  (Louvre)** *target* profile checks the WEMI projection's fitness to serialise (a
  HumanMadeObject root with a name as hard requirements; the carried Expression,
  its Work, an authority-identified creator as richness warnings); the **IIIF
  Presentation 3.0** *target* profile checks fitness to produce a Manifest (a label
  as the one hard requirement; a Canvas-bearing digital object and a dereferenceable
  HTTP id as warnings — a real IIIF manifest is fully conformant, a bibliographic
  record is flagged as having nothing to present). The **BnF INTERMARC** *source*
  profile checks a bibliographic record's own native `:intermarc/*` fields (a 001
  control number and a 245 title as essentials; the 003 ARK, an authority-linked
  100 heading — Transition bibliographique — and a 260 date as expectations; the
  145 Work-link as a FRBRisation-readiness hint), grounded in what real BnF SRU
  records carry. Surfaced as the CLI verb `conformance <input> --from <fmt>
  --profile <linked-art|intermarc|iiif> [--policy <p>]`, exiting non-zero when the
  threshold is breached. This is **not** the strict official-schema validation
  (that stays a test-only eval against the draft-2020-12 Linked Art schema); a
  profile is the institution's requirement set, some of it stricter
  than the schema, some looser.
- `regesta.curate` — the ADR 0005 repair-application / curation engine, the
  last dataless WP-8 gap. A pure function over a record's *pending* assertions
  (`:proposed`/`:needs-review`): a curator `(fn [assertion] -> :accept | :reject
  | :review)` resolves each into the workflow family (`:accepted` / `:rejected`
  / `:needs-review`), leaving every in-force or already-resolved assertion
  untouched. Named policies (`accept-all`, `reject-all`, `flag-all`,
  `accept-when`) are ordinary decision functions, so an ADR 0018 promotion guard
  composes (`accept-when` accepts only the proposals safe to commit, routes the
  rest to review). The returned transition log is the audit record — the
  Provenance schema is deliberately left unchanged. Surfaced as the CLI verb
  `apply-repairs <input> --from <fmt> [--policy flag|accept|reject]`, which
  curates the `:proposed` WEMI claims a real conversion emits (the DC/MARC21
  string-key inference). Supersession of a replaced in-force assertion is
  documented as out of scope (it needs replacement semantics the proposals do
  not yet carry).
- Sprint 6: canonical vocabulary plugin (`regesta.plugins.canonical`).
  Ships the eight `:canon/*` documentary predicates from ADR 0003
  §Decision as data, with a `documentary?` membership predicate, plus
  the first validation rule over the canonical layer — `title-required`,
  a `:validate`-phase rule that emits a `:missing-title` warning when a
  record carries no `:canon/title`. `:canon/lang` stays a fragment-borne
  qualifier coord (ADR 0011), not a ninth documentary predicate; no ADR
  amendment.
- End-to-end canonical integration test in
  `test/integration/regesta/canonical_integration_test.clj`: a
  registry-driven ingest → normalize → validate → diagnostics-report
  pipeline over a titled and an untitled record, exercising
  `regesta.diagnostics/format-report` and the `should-fail?`
  failure-policy surface.
- Sprint 5: plugin protocol (`regesta.plugins`) with closed schema,
  registry with `:requires` topological resolution and `:input-format`
  dispatch, transform stdlib (`regesta.plugins.transforms`), mapping
  schema and compiler (`regesta.plugins.mapping`), and the generic
  JSON/XML shape adapter (`regesta.plugins.shape`) wired as
  registrable plugins. Fragments for qualified values per ADR 0011 +
  ADR 0012 (single-place `mint-fragment-id` minted at ingest).
- End-to-end shape-adapter integration test in
  `test/integration/regesta/shape_integration_test.clj`: ingest a DC
  record in both JSON-LD and XML, run through the V1 :normalize
  phase, assert canonical convergence.
- `org.clojure/data.xml` 0.2.0-alpha9 and `org.clojure/data.json` 2.5.1
  in `:deps`. Both resolve from Maven Central and do not require
  `:sandbox` rewiring (ADR 0006).

### Fixed

- Self-review remediation of this line's WP-6/7 work: (1) the WEMI projection's
  loss accounting (`lrmoo.project` `language-losses`/`ambiguity-losses`) now keys
  on the same `work-title` condition that decides whether an Expression is minted
  (a new `work-title-of`), so a uniform-title-only record no longer under-reports
  language loss nor falsely reports `:ambiguity-collapsed` (latent — introduced by
  uniform-title bridging; guarded by a test). (2) `regesta.convert/convert-stream`
  via the CLI now writes `--out` atomically (temp file + rename), so a mid-stream
  parse error leaves no partial output, matching the batch path. (3)
  `curate/format-curation` tolerates a single-record `curate-record` result (no
  `:summary`) instead of NPE-ing. Plus docstring honesty (the canonical set is
  nine, not "eight"; the streamed-report import-edge caveat; DC needs `:record-id`).
- `regesta.plugins.mapping/compile-mappings` now rejects a batch whose
  mapping rules derive the same compiled rule id (a cross-plugin
  `:mapping/id` name collision), instead of silently conflating their
  provenance in the trace (ADR 0009 §Open V2).
- `regesta.model/mint-fragment-id` now rejects inputs that could collapse
  two distinct fragments onto one id: a `-` in a locator predicate's
  namespace, or a `.` in any record-id or predicate segment. Predicate
  *names* may still contain hyphens. This enforces ADR 0012's injectivity
  preconditions at construction, so a minted id always round-trips.
- `regesta.rules` produce-template substitution no longer treats a variable
  bound to `false` or `nil` as unbound. `substitute` tested a binding's truth
  instead of its presence, so a legitimately falsey-bound `:produce` variable
  raised "Unbound variable in produce template"; it now distinguishes presence
  from truth (`find` + `if-let`, as `position-match` does). Latent (no current
  rule binds a falsey value) and guarded against regression by a behavioural test.

### Changed

- Post-Sprint-5 audit cleanup (no feature change): documentation and config
  reconciled with the tree (removed the phantom `dev.clj` / `resources`
  references); a test now guards that the core never depends on
  `regesta.plugins.*`; `regesta.plugins/topo-order` is marked `^:no-doc`
  (provisional — no consumer yet, still callable). Tracked in
  `docs/cleanup/remediation-pass.md`.

### Security

- **XML input hardening (WP-9).** All XML importers now parse through a single
  façade, `regesta.xml`, which **refuses DTDs** (`:support-dtd false`) instead of
  calling `clojure.data.xml` directly. This closes a real `billion laughs`
  entity-expansion denial of service: `clojure.data.xml` expands internal
  general entities with no size bound and the JDK's `entityExpansionLimit` does
  not fire through its StAX path (verified — a small nested-entity payload
  expanded unbounded). Refusing DTDs also removes any DTD-borne XXE surface
  (external `SYSTEM`/`PUBLIC` entities were already unresolved by data.xml;
  `:supporting-external-entities false` is now pinned for defence in depth). No
  fixture or supported format uses a DTD, so the policy is total. Wired across
  MARC-XML (and its INTERMARC/UNIMARC/INTERMARC-NG dialects), MODS, Dublin Core
  and the generic shape adapter; pinned by `regesta.xml-test` on both the eager
  and streaming parse paths; documented in `SECURITY.md` (Hardening).

### Earlier in this line

- Property-based tests covering rule-engine determinism, triple-view
  losslessness, provenance, merge accumulation and severity ordering
  (test/regesta/property_test.clj).
- `cloverage` coverage report published as a CI workflow artifact, with
  a per-namespace plaintext summary in the run summary panel.
- `CONTRIBUTING.md`, `SECURITY.md`, GitHub issue templates and this
  changelog.
- End-to-end integration test scenario covering all four V1 phases
  (test/integration/regesta/integration_test.clj).
- Test layout reorganized into `test/unit/`, `test/property/`,
  `test/integration/` with matching `:test/unit`, `:test/property`,
  `:test/integration` aliases for category-scoped runs.
- JUnit XML output via a custom `:test/junit` runner, published to the
  GitHub check panel through `EnricoMi/publish-unit-test-result-action`.
- README badges (CI status, license, Clojure / Java version).
- Codecov integration: cloverage reports are uploaded to Codecov on
  every CI run via `codecov/codecov-action`. PRs receive a per-file
  diff coverage comment. Thresholds configured in `codecov.yml`:
  project = `auto` with 1% noise floor, patch (new code) = 80%.

### Changed

- Plugin architecture documentation: ADR 0007 revised in place to
  specify the `source` contract, reducible records, `:requires`
  topo-sort, `:input-format` dispatch, and `:plugin/spec-version`.
  ADR 0009 introduces the `:mapping` schema; ADR 0010 specifies how
  plugins extend the predicate / transform stdlib safely.
- `record-triples` now exposes the full structural vocabulary (six
  predicates, was three). `absent?` / `present?` query the unified
  triple-view rather than `:assertions` only — fixes a silent
  matcher / guard divergence.
- Productions deduplicate at merge time by structural identity
  (ADR 0008). Identical assertions or diagnostics no longer accumulate
  across cycles. `:cycles N` has clear semantics for the first time.
- `regesta.diagnostics` severity tolerance is consistent: every
  function rejects unknown severities (the schema already pins the
  enum). Format strings use uniform `pr-str` rendering across
  subjects and codes.
- `Primitive` schema rejects NaN and the infinities at validate time,
  not just at generation time.
- Rule production provenance: engine fields (`:rule`, `:pass`) are
  authoritative; template-supplied `:source`, `:derivation` and
  `:timestamp` are preserved by deep-merge.
- `compile-rule` now distinguishes pattern-bindings from guard-usages.
  A variable referenced only by a guard fails at compile time.
- `compile-rule`'s internal runner key renamed `::run` →
  `:regesta.rules/runner` for clarity in pprint / inspection.

### Added (model)

- `record-consistent?` and `explain-consistency` enforce the contract
  that assertion / diagnostic subjects match the record id or one of
  its fragment ids. Importer authors now have a first-class predicate
  to assert against.
- `finite-double?` is public so importers can guard inputs.

### Deprecated

- Nothing yet.

### Removed

- The `dev` extra-path in the `:dev` alias of `deps.edn`. The
  directory never existed; the entry was cargo-culted.

### Fixed

- See "Changed" above for the matcher / guard coherence fix and the
  no-dedup runtime drift.

### Security

- No security advisories at this time. See SECURITY.md for the
  disclosure policy.
