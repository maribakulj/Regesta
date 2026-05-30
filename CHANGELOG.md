# Changelog

All notable changes to Regesta are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version line for an unreleased work-in-progress is `[Unreleased]`. When a
release is cut, that section is renamed to the version and date and a fresh
`[Unreleased]` section is opened above it.

## [Unreleased]

The pre-1.0 development line. Sprints 0 through 5 are landed; Sprint 6
(canonical vocabulary plugin) is the next milestone.

### Added

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

- `regesta.plugins.mapping/compile-mappings` now rejects a batch whose
  mapping rules derive the same compiled rule id (a cross-plugin
  `:mapping/id` name collision), instead of silently conflating their
  provenance in the trace (ADR 0009 §Open V2).
- `regesta.model/mint-fragment-id` now rejects inputs that could collapse
  two distinct fragments onto one id: a `-` in a locator predicate's
  namespace, or a `.` in any record-id or predicate segment. Predicate
  *names* may still contain hyphens. This enforces ADR 0012's injectivity
  preconditions at construction, so a minted id always round-trips.

### Changed

- Post-Sprint-5 audit cleanup (no feature change): documentation and config
  reconciled with the tree (removed the phantom `dev.clj` / `resources`
  references); a test now guards that the core never depends on
  `regesta.plugins.*`; `regesta.plugins/topo-order` is marked `^:no-doc`
  (provisional — no consumer yet, still callable). Tracked in
  `docs/cleanup/remediation-pass.md`.

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
