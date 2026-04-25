# 0007 — Plugins as data, not protocols

- Status: Accepted
- Date: 2026-04-25

## Context

The plugin layer is the boundary between Regesta and the outside world.
Every external metadata standard (Dublin Core, MARC, CSV, IIIF, …) reaches
the runtime through a plugin. The runtime itself never knows about any
specific format.

Per the README, a plugin contributes some combination of:

- an **importer** (external bytes / file / parsed tree → IR records),
- an **exporter** (IR records → external bytes / file / structure),
- **rule sets** (validation, normalization, inference, projection),
- a **mapping** from native predicates to the canonical vocabulary.

The first two are unavoidably *code*: there is no realistic EDN-only XML
parser or CSV reader. The latter two are pure *data* — already governed by
the rule schema (ADR 0002).

So the architectural question is: how is a plugin **represented** in
Regesta? What is the shape of `regesta.plugins`?

Three families of answer were on the table.

## Decision

A plugin is a **plain Clojure map**. Registration is explicit. Dispatch is
by data lookup, not by polymorphism.

```clojure
{:id          :plugin/dublin-core
 :version     "0.1.0"

 ;; Optional capabilities. The presence of a key is the contract.
 :importer    (fn [opts source]  ...)   ;; -> {:records [...] :diagnostics [...]}
 :exporter    (fn [opts records] ...)   ;; -> {:output ... :diagnostics [...]}

 ;; Pure-data contributions — rule schema (ADR 0002) and mapping schema.
 :rules       [...]
 :mapping     [...]

 ;; Optional declarations.
 :requires    #{:plugin/canonical}
 :input-format  :xml
 :output-format :json-ld}
```

The registry is itself a map `{plugin-id → plugin-map}`. Operations on it
are pure functions: `register`, `lookup`, `plugins-for-format`,
`importers-for`, `exporters-for`, `all-rules`, `all-mappings`.

The plugin map shape is validated by a Malli schema at register time. The
`:importer` / `:exporter` functions are validated only structurally
(callable, declared signature) — Clojure cannot meaningfully introspect
closures, and that's accepted.

Importer / exporter signatures are fixed:

- `importer : opts × source → {:records [Record] :diagnostics [Diagnostic]}`
- `exporter : opts × records → {:output Any :diagnostics [Diagnostic]}`

A parse failure is **not** an exception: it is a `Diagnostic` attached to
a placeholder record (or to the run, for source-level errors). This keeps
the same first-class diagnostics contract that the rest of the system
uses (ADR 0001).

## Alternatives considered

- **`defprotocol Importer / Exporter`** with `extend-type` per format.
  Idiomatic Java-ish OO. Rejected: it puts dispatch in the type system
  rather than in data, which is the opposite of every other layer of
  Regesta (rules are data, pipelines are data, mappings are data — ADR 0002).
  A protocol also obscures *what a plugin contributes* behind two
  function names; a map makes the contributions inspectable at a glance.
- **`defmulti` keyed on format keyword.** Less rigid than a protocol but
  still spreads a plugin's behavior across `defmethod` calls in many
  namespaces, hard to enumerate. We want to ask the registry "what can
  this plugin do?" and get back a map, not run a search.
- **Macro-based `defplugin`.** Rejected: macros generate code, plugins
  authored by us would no longer be straightforward Clojure data, and
  serialization / inspection / hot reload all become harder. The DSL
  decision (ADR 0002) explicitly rejects macros for the same reason.
- **Classpath scanning / auto-discovery** (e.g. `META-INF/services` or a
  `regesta.plugins` namespace convention). Rejected for V1: the cost of
  silent registration (mystery rules firing because a transitive dep was
  pulled in) outweighs the convenience. Plugins are loaded by explicit
  `require` + `register` calls in user code or in `app.clj`.

## Consequences

- A plugin is inspectable, serializable for the parts that are data, and
  callable for the parts that aren't. Users can `pprint` a plugin and
  see exactly what they're getting.
- Registration is a pure function on a registry value; multiple registries
  can coexist (test fixtures, isolated runs).
- Plugins compose by union: loading two plugins concatenates their rules
  and mappings. Conflicts (two plugins claiming the same `:id`) are
  rejected at register time.
- The plugin schema is small (Sprint 5 deliverable): `:id` required, every
  other key optional. Growth discipline matches ADR 0002 — new optional
  keys must justify themselves through concrete plugin needs.
- Importer / exporter signatures cannot reach into core internals; they
  receive opts and source/records, return records / output. The core /
  plugin boundary remains thin.
- Cross-record concerns (registry-wide rule ordering, format dispatch)
  live in the registry, not in plugins. Plugins stay leaf-shaped.
- A future "AI-assisted authoring" feature (deliberately deferred) gets
  the same data surface as humans: a plugin is a Clojure map, not a Java
  class hierarchy.
- The trade-off accepted: we lose compile-time `defprotocol`-style
  guarantees on importer/exporter shape. Replaced by Malli validation at
  register time and runtime contract enforcement at call sites.
