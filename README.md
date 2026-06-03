# Regesta

[![CI](https://github.com/maribakulj/regesta/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maribakulj/regesta/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/maribakulj/regesta/branch/main/graph/badge.svg)](https://codecov.io/gh/maribakulj/regesta)
[![License](https://img.shields.io/github/license/maribakulj/regesta?color=blue)](./LICENSE)
[![Clojure](https://img.shields.io/badge/Clojure-1.12-5881D8?logo=clojure&logoColor=white)](https://clojure.org)
[![Java](https://img.shields.io/badge/Java-21%2B-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)

A documentary compiler for cultural metadata.

Regesta ingests heterogeneous metadata records, normalizes them into a stable
internal representation, runs explicit validation, inference, and repair passes
over that representation, and projects the result into one or more target
formats — carrying diagnostics and provenance throughout.

The architecture is deliberately modeled on a compiler: sources are parsed into
an IR, the IR is transformed by explicit passes, and targets are emitted from
the IR. Regesta does this for metadata rather than for code.

---

## What Regesta is

- A **metadata transformation engine** with a stable, schema-independent core.
- A **declarative rule system**: validation, inference, normalization, repair,
  and projection logic are expressed as data (EDN), not as code.
- A **plugin platform**: format-specific support (importers, exporters,
  rules, mappings) is loaded as plugins; none is privileged, none lives
  in the core.
- A **diagnostic-first system**: ambiguity, contradiction, intermediate states,
  and proposed repairs are first-class citizens of the internal representation,
  not side channels.

## What Regesta is not

- A converter tied to a single standard.
- An AI-first tool. The V1 stands on its own; LLM assistance is a deliberate
  future, never the foundation.
- A storage system, a query engine, or a web application. It is a library and
  a CLI; it batches over files.

---

## Core concepts

### The internal representation

Records in Regesta are **assertion sets**, not field maps. An assertion is:

```clojure
{:subject    :record/r42
 :predicate  :canon/title
 :value      "Les Misérables"
 :provenance {:source :xml/sample.xml
              :pass   :ingest
              :rule   :dc/map-title}
 :confidence 1.0
 :status     :asserted}
```

A record wraps an identity, a source pointer, an assertion set, a diagnostic
set, and fragment pointers back into the raw source tree.

This shape is what lets Regesta natively represent:

- multiplicity (two candidate titles)
- ambiguity (a date that is either 1823 or 1832)
- contradiction (two sources disagreeing about authorship)
- proposed repairs coexisting with the original value
- full provenance for every piece of data

See [ADR 0001](./docs/adr/0001-assertion-based-ir.md) for the reasoning.

### The rule DSL

Transformation, validation, inference, normalization, and repair logic is
expressed as **EDN data**. A rule:

```clojure
{:id      :dc/title-required
 :phase   :validate
 :match   [[?r :meta/kind :book]
           (absent? ?r :canon/title)]
 :produce {:diagnostic {:severity :error
                        :code     :missing-title
                        :subject  ?r
                        :message  "Record has no title."}}}
```

Rules are data — inspectable, serializable, composable. The compiler
(`regesta.rules`) turns them into executable functions. Rules cannot call
arbitrary Clojure; they use a curated predicate stdlib.

See [ADR 0002](./docs/adr/0002-edn-as-dsl.md).

### The pipeline

A run is a sequence of phases:

```
ingest → normalize → validate → infer → repair → project
```

Each phase is a pure transformation `IR → IR + diagnostics`. Phases run a
declared number of times (default 1); no implicit fixpoint in V1. See
[ADR 0004](./docs/adr/0004-fixed-passes-over-fixpoint.md).

### Vocabulary layering

The core ships only a **structural vocabulary** (`:meta/id`, `:meta/kind`,
`:meta/source`, `:meta/fragment`, `:meta/diagnostic`, `:meta/provenance`). It
knows nothing about documentary content.

A separate, optional plugin — `regesta.plugins.canonical` — provides a
**documentary vocabulary** (`:canon/title`, `:canon/identifier`, `:canon/agent`,
`:canon/date`, `:canon/relation`, `:canon/note`, `:canon/digital-object`,
`:canon/loss-marker`) that format plugins can map toward for cross-source rules
and projection.

This split keeps the core authentically agnostic while still enabling
commensurability when it is wanted. See
[ADR 0003](./docs/adr/0003-core-vs-canonical-vocabulary.md).

### Plugins

A plugin contributes some combination of:

- an **importer** (external format → canonical IR),
- an **exporter** (canonical IR → external format),
- **rule sets** (validation, normalization, projection),
- a **mapping** from its native predicates to the canonical vocabulary.

Plugins connect the outside world to the core. They never reach into the
core's internals; they interact only through the model API and the rule DSL.

### Diagnostics and repair

A validation failure is not an exception — it is a `Diagnostic` attached to
its subject, carried alongside the data through the pipeline. A diagnostic
may suggest zero or more `Repair` operations. Repairs remain `:proposed` and
are never applied automatically in V1; the `apply-repairs` command surfaces
them for human acceptance or rejection. See
[ADR 0005](./docs/adr/0005-status-model.md) for the status model.

---

## Architecture at a glance

```
           ┌──────────────────────────────────────────────────────┐
           │                     regesta.app                     │
           │      CLI, config, batch I/O — no business logic      │
           └──────────────────────┬───────────────────────────────┘
                                  │
           ┌──────────────────────▼──────────────────────┐
           │              regesta.runtime               │
           │  pipeline · pass execution · diagnostics    │
           └───────┬─────────────────────┬───────────────┘
                   │                     │
         ┌─────────▼────────┐   ┌────────▼─────────┐
         │  regesta.rules  │   │ regesta.model   │
         │  DSL compiler    │   │  IR, vocabulary  │
         └─────────┬────────┘   └────────▲─────────┘
                   │                     │
         ┌─────────▼─────────────────────┴─────────┐
         │             regesta.plugins             │
         │      format-specific plugins live here  │
         └─────────────────────────────────────────┘
```

Rules and plugins flow **into** the runtime. Data flows **out** through the IR.
The core never knows any external schema.

---

## Repository structure

```
.
├── deps.edn                 # Clojure deps and aliases
├── src/regesta/
│   ├── model.clj            # Canonical IR (Sprint 1)
│   ├── rules.clj            # Rule DSL + compiler (Sprint 2)
│   ├── runtime.clj          # Execution engine (Sprint 3)
│   ├── diagnostics.clj      # Diagnostic API (Sprint 4)
│   ├── plugins.clj          # Plugin protocol (Sprint 5+)
│   └── app.clj              # CLI and application shell (stub; Sprint 10)
├── test/
│   ├── unit/regesta/        # Fast, hermetic, mirrored to src/
│   ├── property/regesta/    # Generative invariants (test.check + malli.generator)
│   └── integration/regesta/ # Multi-layer end-to-end scenarios
├── docs/
│   └── adr/                 # Architecture Decision Records
├── .github/workflows/ci.yml # Lint, format, test on every push
├── .clj-kondo/config.edn    # Linter config
├── .cljfmt.edn              # Formatter config
└── LICENSE
```

---

## Current status

Sprints 0 through 6 are landed — the **substrate**: assertion IR
(`regesta.model`), rule DSL (`regesta.rules`), runtime (`regesta.runtime`),
diagnostics (`regesta.diagnostics`), the plugin layer + mapping schema + shape
adapter (`regesta.plugins.*`), fragments for qualified values (ADR 0011,
ADR 0012), and the canonical vocabulary plugin (`regesta.plugins.canonical`).

**V1 has since been redefined** (2026) around a rich, **loss-aware** pivot
grounded in **LRMoo** (the object-oriented IFLA LRM, a CIDOC-CRM extension), for
production use across the full IIIF ↔ MARC family ↔ museum range. The Sprint 0–6
substrate is preserved; the redefinition reopens what comes next — see the
Roadmap below and [`docs/roadmap-v1.md`](./docs/roadmap-v1.md).

**WP-0 (design lock) is complete:** the load-bearing decisions are settled
([`docs/wp0-decisions.md`](./docs/wp0-decisions.md)), four foundational ADRs are
written ([0013](./docs/adr/0013-lrmoo-rich-pivot.md)–[0016](./docs/adr/0016-frbrisation.md)),
and throwaway spikes on **real BnF INTERMARC** data validated the hardest piece,
FRBRisation, end to end
([`docs/wp0-spike-findings.md`](./docs/wp0-spike-findings.md)).

---

## Development

### Requirements

- Java 21+
- Clojure CLI (https://clojure.org/guides/install_clojure)
- clj-kondo and cljfmt (installed via `:lint` / `:fmt` aliases)

### Convert (CLI)

```bash
# Convert a source document to a target serialisation through the LRMoo pivot.
# The converted document goes to stdout; the loss report (ADR 0015) to stderr.
clojure -M:run convert path/to/record.xml --from marc21 --to linked-art

# List the supported source and target formats
clojure -M:run formats
#  from: dc iiif intermarc marc21 mods
#  to:   crm crm-only dc jsonld linked-art marc21 ntriples turtle
```

(In a Clojars-restricted sandbox, prepend `:sandbox` — `clojure -M:sandbox:run …`.)

### Common tasks

```bash
# Run the full test suite (unit + property + integration)
clojure -M:test

# Run only one category — useful for fast iteration
clojure -M:test/unit
clojure -M:test/property
clojure -M:test/integration

# Lint
clojure -M:lint

# Format check
clojure -M:fmt

# Format fix
clojure -M:fmt/fix

# REPL with the test paths and test.check on the classpath
clojure -M:dev
```

CI runs the first three on every push and pull request.

### Restricted networks (Clojars unreachable)

Some environments — notably the Claude Code web sandbox — block Clojars but
allow Maven Central and GitHub. The `:sandbox` alias rewrites Clojars-hosted
dependencies (Malli and its transitive deps) to git coordinates so that local
testing works there. Chain it with `:test`:

```bash
clojure -M:sandbox:test
```

Normal local development and CI use the unaliased path. See
[ADR 0006](./docs/adr/0006-deps-resolution-and-sandbox.md).

### Continuous integration

Every push and pull request triggers `.github/workflows/ci.yml`:

- `clj-kondo --lint src test`
- `cljfmt check src test`
- `clojure -M:test`

Runs are parallelized across a Java matrix (Temurin 21 and 24). Concurrent
runs on the same branch (other than `main`) cancel each other to keep CI
turnaround tight. Dependabot tracks GitHub Actions versions weekly.

After the test matrix passes, two follow-up jobs run on a single Java
slice:

- **JUnit XML** — runs `clojure -M:test/junit`, uploads
  `target/junit/junit.xml` as an artifact, and uses
  `EnricoMi/publish-unit-test-result-action` to render the per-test
  results in GitHub's check panel. The publish step uses
  `if: always()` so failing runs still surface their breakdown.
- **Coverage** — runs `cloverage`, uploads the report to Codecov via
  `codecov/codecov-action`, and also publishes it as the workflow
  artifact `coverage-report` (HTML + cobertura XML) for offline
  inspection. A per-namespace text summary is mirrored to the run
  summary. The Codecov dashboard surfaces per-PR diff coverage and the
  status check; thresholds are configured in
  [`codecov.yml`](./codecov.yml) — currently `auto` for the project
  (no regression vs the base branch, with a 1% noise floor) and 80%
  for new code added in a PR.

Local equivalents:

```bash
clojure -M:test/junit  # produces target/junit/junit.xml
clojure -M:coverage    # produces target/coverage/index.html
```

Local coverage:

Note that cloverage is hosted on Clojars, so the `:sandbox` alias does
not proxy it. Local coverage runs require Clojars access; the JUnit
runner uses only Maven Central and works under sandbox.

A separate `release.yml` workflow runs on `v*` tag pushes: it re-executes
the full CI gate, then drafts a GitHub Release with auto-generated notes
that a maintainer publishes manually.

### Branch protection (manual setup)

The "tests must pass before merge" guarantee is enforced server-side, not
in YAML. After cloning the repository on GitHub, enable a branch
protection rule for `main` under **Settings → Branches → Add rule** with:

- *Require a pull request before merging* — yes
  - *Require approvals*: 1 (or more if the team grows)
  - *Dismiss stale approvals on new commits*
  - *Require review from Code Owners* (uses `.github/CODEOWNERS`)
- *Require status checks to pass before merging* — yes
  - Required checks: `Test, lint, format (Java 21)` and
    `Test, lint, format (Java 24)`
  - *Require branches to be up to date before merging*
- *Require conversation resolution before merging* — yes
- *Require linear history* — yes (rebase / squash only, no merge commits
  in `main` history)
- *Do not allow bypassing the above settings*

Force pushes and branch deletion on `main` should be disabled.

---

## Roadmap

**V1 was redefined in 2026** around a rich, **loss-aware** pivot grounded in
**LRMoo** (the object-oriented IFLA LRM, a CIDOC-CRM extension), for production
use at flagship institutions. The detailed plan lives in
[`docs/roadmap-v1.md`](./docs/roadmap-v1.md): dependency-ordered work packages
(WP-0…WP-9) over an honest ~18–24-month horizon. The decisions behind it are in
[`docs/wp0-decisions.md`](./docs/wp0-decisions.md); the architecture is fixed by
ADRs [0013](./docs/adr/0013-lrmoo-rich-pivot.md)–[0016](./docs/adr/0016-frbrisation.md).

The hub-and-spoke shape: spoke importers / exporters (MARC21 · UNIMARC ·
INTERMARC · Dublin Core · IIIF · CIDOC-CRM / Linked Art) ↔ the **assertion IR
(ground truth)** ↔ a **derived, typed LRMoo view** (a plugin, never the core).
Every conversion emits a measurable loss report.

**Scope reversal.** The original plan (below) deliberately deferred IIIF,
CIDOC CRM, Linked Art, and **deduplication**. The redefinition pulls them in:
cross-record Work clustering (FRBRisation) is core to the rich pivot, and the
museum / presentation formats are first-class spokes. The forward compatibility
the original IR reserved — the qualified-value design,
[ADR 0011](./docs/adr/0011-fragments-for-qualified-values.md) — is what makes
this an **extension, not a rewrite**: the Sprint 0–6 substrate stands.

Still out of V1 scope: LLM integration, a web UI, persistent storage, and a live
query engine.

<details>
<summary>Superseded original twelve-sprint plan</summary>

| # | Theme |
|---|---|
| 0 | Foundations |
| 1 | Canonical model (Malli schemas, EDN round-trip) |
| 2 | Rule DSL (schema, compiler, pattern matcher) |
| 3 | Runtime (rule execution, provenance merging) |
| 4 | Pass pipeline and diagnostics |
| 5 | Generic shape adapter (JSON + XML with mapping rules) |
| 6 | Canonical vocabulary plugin |
| 7 | Dublin Core plugin (XML/JSON in, JSON-LD out) |
| 8 | CSV institutional adapter + MARC-XML-lite subset |
| 9 | Repair phase and human workflow (`apply-repairs`) |
| 10 | CLI and UX |
| 11 | Hardening and performance baseline |
| 12 | Documentation and v1.0.0 release |

Sprints 0–6 landed and form the substrate; 7–12 are superseded by the
work-package plan above.
</details>

---

## Design principles

1. **Small core, clear boundaries.** The core knows structure, not content.
2. **Data over code.** Rules are EDN. Pipelines are EDN. Mappings are EDN.
3. **Explicit over implicit.** No fixpoint, no magical dispatch, no globals.
4. **Provenance is not optional.** Every assertion answers "where did you
   come from?"
5. **Diagnostics are data.** Validation failures are attached to subjects,
   not thrown as exceptions.
6. **Plugins connect, they do not replace.** The core can stand alone.
7. **No AI at the foundation.** Future assistance is a module; it never
   replaces rules or the IR.

---

## Contributing

The full contributor workflow lives in [CONTRIBUTING.md](./CONTRIBUTING.md):
branch naming, PR review checklist, ADR discipline, code-quality bar,
and the predicate-stdlib growth rule.

Suspected vulnerabilities go through [SECURITY.md](./SECURITY.md), not
public issues.

User-visible changes are tracked in [CHANGELOG.md](./CHANGELOG.md), in
the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.

---

## License

See [LICENSE](./LICENSE).
