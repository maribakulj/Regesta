# 0004 — Fixed passes in V1, fixpoint deferred

- Status: Accepted
- Date: 2026-04-17

## Context

The pipeline runs through phases: ingest → normalize → validate → infer →
repair → project. Each phase executes one or more rules against the IR.

Within a phase, rules can conceivably fire each other: an inference rule
derives a new assertion, which enables another inference rule to fire, and
so on. The classical answer is fixpoint iteration: keep running the rule
set until no new productions appear.

Fixpoint is expressive but costs:
- non-termination risk (cycles in rule dependencies)
- debugging difficulty (which pass produced which assertion in which
  iteration?)
- execution complexity (change detection, dependency tracking)

## Decision

V1 runs **fixed passes**. Each phase executes its rule set a declared number
of times (default: 1). The pipeline configuration may override the iteration
count per phase, with a hard cap (default: 16) enforced by the runtime.

Fixpoint is **not generalized** in V1. Where iteration is legitimately
useful — typically `:infer` and `:repair` — the pipeline author declares an
explicit iteration count. The runtime does not detect convergence; it simply
runs the requested number of cycles.

A full fixpoint mode (run until no change, with dependency tracking) is a
deliberate V2 concern, pending a concrete use case that fixed iteration
cannot satisfy.

## Alternatives considered

- **Generalized fixpoint from V1.** Rejected: the complexity-to-value ratio
  is wrong for V1. We ship a usable engine faster by keeping passes
  deterministic.
- **Fixpoint with automatic cycle detection.** Rejected for V1 for the same
  reason, plus the subtleties of cycle detection in pattern-matching
  rewrite systems.
- **No iteration at all (pure single-pass).** Rejected: even simple
  inference cases (e.g. "if A, derive B; if B, derive C") require more than
  one pass. Explicit iteration counts handle these cases without fixpoint
  machinery.

## Consequences

- Pipelines are deterministic and debuggable: the number of passes per phase
  is always known at config time.
- No non-termination risk in V1.
- Authors who need more inference must either chain rules in a single pass
  or declare additional iterations explicitly.
- Migrating to fixpoint later is straightforward: it becomes an additional
  phase mode (`:cycles N` vs `:until-fixpoint`), and existing pipelines
  continue to work unchanged.
