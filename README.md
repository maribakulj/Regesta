# Regesta

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
- A **plugin platform**: IIIF, Dublin Core, MARC, CIDOC CRM, Linked Art, or a
  local institutional model are loaded as plugins; none is privileged, none
  lives in the core.
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
         │             regesta.plugins            │
         │  shape-json · shape-xml · dublin-core   │
         │  csv · marc-xml-lite · canonical · ...  │
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
│   ├── app.clj              # CLI and application shell (Sprint 10)
│   └── dev.clj              # REPL / dev helpers
├── test/                    # Test suites, mirrored to src/
├── docs/
│   └── adr/                 # Architecture Decision Records
├── .github/workflows/ci.yml # Lint, format, test on every push
├── .clj-kondo/config.edn    # Linter config
├── .cljfmt.edn              # Formatter config
└── LICENSE
```

---

## Current status

Sprints 0 through 4 are landed:

- **Sprint 0** — scaffolding, tooling, six foundational ADRs.
- **Sprint 1** — canonical model with Malli schemas and EDN round-trip
  (`regesta.model`).
- **Sprint 2** — rule DSL with compiler, pattern matcher and predicate stdlib
  (`regesta.rules`).
- **Sprint 3** — execution engine, phase pipeline and trace queries
  (`regesta.runtime`).
- **Sprint 4** — diagnostics API: filters, aggregations, plain-text reporting
  and a failure policy for CI/CLI integration (`regesta.diagnostics`).

The next sprint (Sprint 5) introduces the plugin protocol and the generic
shape adapter for JSON and XML.

---

## Development

### Requirements

- Java 21+
- Clojure CLI (https://clojure.org/guides/install_clojure)
- clj-kondo and cljfmt (installed via `:lint` / `:fmt` aliases)

### Common tasks

```bash
# Run the test suite
clojure -M:test

# Lint
clojure -M:lint

# Format check
clojure -M:fmt

# Format fix
clojure -M:fmt/fix

# REPL with dev tools loaded
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

The V1 is planned across twelve two-week sprints.

| # | Theme |
|---|---|
| 0 | Foundations (this commit) |
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

**Out of scope for V1**, by deliberate choice: LLM integration, web UI,
persistent storage, cross-record queries, deduplication, IIIF, CIDOC CRM,
Linked Art, TEI, and EAD plugins. Each of those becomes meaningful once the
V1 core is stable; not before.

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
