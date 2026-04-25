# Architecture Decision Records

Structural decisions that shape the Regesta core. Each ADR is a standalone
document: context, decision, consequences. New major decisions get their own
ADR; supersessions are explicit, not silent edits.

## Index

| # | Title | Status |
|---|---|---|
| [0001](./0001-assertion-based-ir.md) | Assertion-based internal representation | Accepted |
| [0002](./0002-edn-as-dsl.md) | EDN as the rule DSL, no parser | Accepted |
| [0003](./0003-core-vs-canonical-vocabulary.md) | Structural vocabulary at core, documentary vocabulary in a plugin | Accepted |
| [0004](./0004-fixed-passes-over-fixpoint.md) | Fixed passes in V1, fixpoint deferred | Accepted |
| [0005](./0005-status-model.md) | Dual status model: machine truth and human workflow | Accepted |
| [0006](./0006-deps-resolution-and-sandbox.md) | Maven coordinates by default, git override alias for restricted networks | Accepted |
| [0007](./0007-plugins-as-data.md) | Plugins as data, not protocols | Accepted |

## Template

New ADRs follow this skeleton:

```markdown
# NNNN — Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
What problem are we facing? What are the constraints?

## Decision
What did we choose? Stated plainly.

## Alternatives considered
Briefly, what else was on the table and why it was rejected.

## Consequences
What becomes easier. What becomes harder. What future decisions are gated on this.
```
