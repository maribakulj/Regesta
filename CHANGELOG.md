# Changelog

All notable changes to Regesta are recorded in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version line for an unreleased work-in-progress is `[Unreleased]`. When a
release is cut, that section is renamed to the version and date and a fresh
`[Unreleased]` section is opened above it.

## [Unreleased]

The pre-1.0 development line. Sprints 0 through 4 are landed; Sprint 5
(plugin protocol and generic shape adapter) is the next milestone.

### Added

- Property-based tests covering rule-engine determinism, triple-view
  losslessness, provenance, merge accumulation and severity ordering
  (test/regesta/property_test.clj).
- `cloverage` coverage report published as a CI workflow artifact, with
  a per-namespace plaintext summary in the run summary panel.
- `CONTRIBUTING.md`, `SECURITY.md`, GitHub issue templates and this
  changelog.

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
