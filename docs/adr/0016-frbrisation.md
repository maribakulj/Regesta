# 0016 — FRBRisation: synthesizing WEMI, identity, and reconciliation

- Status: Accepted
- Date: 2026-05-31
- Builds on: ADR 0013 (rich pivot), ADR 0014 (runtime minting), ADR 0012
  (identity scheme — extended here), ADR 0008 (idempotency), ADR 0005 (status),
  ADR 0015 (loss model), ADR 0004 (passes vs fixpoint — noted here)
- Decision record: [`../wp0-decisions.md`](../wp0-decisions.md) (D5, D6, D7, D8,
  D11)
- Empirical: several parameters below are decided *in principle* and **tuned by
  the WP-0 FRBRisation spike** on real MARC + authority data.

## Context

FRBRisation is the heart of the rich pivot and the redefinition's hardest single
problem: turning flat catalogue records into the WEMI graph (Work / Expression /
Manifestation / Item) and reconciling named entities, *during* conversion.
ADR 0013 set the target (the LRMoo view); ADR 0014 enabled the mechanism
(runtime minting). This ADR decides the *intelligence* behind minting — how
entities are identified, clustered, controlled, and reconciled.

It crosses a line the original V1 drew: cross-record clustering is a form of
**deduplication**, which the README listed as out of V1 scope. The redefinition
pulls it in (roadmap §1).

## Decision

### 1. Per-WEMI identity, deterministic, via a resolver seam (D5; extends ADR 0012)
Identity is produced by a pluggable **resolver** —
`resolve-identity(key-material) -> stable-id [+ authority IRI]` — called by the
minting rules (ADR 0014). Each WEMI level has its own key, building on its
parent:

- **Work** = normalised creator + uniform / preferred title (+ original language
  where it distinguishes works);
- **Expression** = work-id + language + expression type (translation, version,
  form);
- **Manifestation** = a source bibliographic record ≈ one manifestation; key =
  expression-id + publisher + date + edition / ISBN (or the record's own id);
- **Item** = manifestation-id + holding / copy id — only when holdings data is
  present.

The id is a **deterministic function of the key** (a hash over the canonicalised
key string), extending the ADR 0012 scheme to synthesized entities — stateless,
reproducible, idempotent at merge (ADR 0008).

### 2. Two resolvers in V1: hash + dump-based authority (D5)
Behind the seam, V1 ships:

- a **deterministic work-key hash** — always available, the fallback;
- an **authority-anchored** resolver reconciling creators / works against a
  **pinned authority snapshot** (open data.bnf.fr, ISNI, IdRef) — deterministic
  and offline because it reads a *dump*, not a live API. On a match, the
  authority IRI anchors identity and is emitted as `sameAs` (interoperable
  output); on a miss, the hash is used.

Live online lookup is deferred (additive behind the same seam). The run records
*which snapshot* it used (reproducibility).

**Spike outcome (2026-06-01, [`../wp0-spike-findings.md`](../wp0-spike-findings.md)).**
On real BnF INTERMARC, every access point carries an embedded authority id
(`$3`) — **100% coverage** (346/346 fields) — while fuzzy name matching scored
**4%**. The resolver order is therefore refined to: (1) **embedded authority id**
(`$3` / `$0` / `$1`) → authority IRI [primary, deterministic]; (2) the work-key
hash [fallback when no embedded id]; (3) fuzzy name matching [last resort]. The
work-key must also canonicalise **multiscript** parallel fields, and
Manifestation identity is read from `001` / `003` (ARK), not synthesized.

### 3. Clustering is batch-local, stabilised by deterministic identity (D6)
Works / agents are clustered **within a run**; Regesta keeps no persistent
store. Cross-run and cross-institution consistency comes *for free* from the
deterministic id + authority IRIs (same content / authority → same id), not from
shared state — preserving the "not a storage system" principle. Honest limit:
fuzzy matching of entities that share *neither* an exact key *nor* an authority
id, across separate batches, needs the deferred store / online layer.

### 4. Control is a confidence-gated hybrid (D7)
High-confidence FRBRisation is **automatic** in `infer` (machine truth).
Low-confidence / ambiguous synthesis is emitted as **`:proposed`** and surfaced
via `apply-repairs` (ADR 0005). This is the only option that both scales to
millions and earns institutional trust. The threshold is a documented, tunable
policy.

### 5. Reconciliation scope: precision-first, tiered breadth (D11)
The same machinery reconciles named entities during conversion. Two dials:

- **Breadth** — V1 ships Tier 1 (**agents, works**) and Tier 2 (**places**, via
  Geonames) if budget allows; subjects (RAMEAU / LCSH) and events are additive
  later behind the seam.
- **Aggressiveness** — **cast a wide net, commit conservatively**: *attempt*
  broadly, but only *assert* a merge when confident; route the uncertain tail to
  proposals. A false merge costs far more than a miss (it fabricates a false
  identity), so the asserted graph is precision-first; recall survives as
  proposals. **Spike (2026-06-01):** bridging the unlinked *Madame Bovary*
  records, an exact author+title match scored **0 false merges** (ebook merged,
  study guide kept apart) while a greedy substring match produced **1 false
  merge** — so auto-commit on exact only; near-misses go to proposals.

### 6. Convergence: bounded passes, fixpoint only if forced (D8; notes ADR 0004)
FRBRisation cascades (mint a Work, then link its Expressions). V1 keeps
ADR 0004's **bounded fixed passes**, with FRBRisation designed to converge in a
small declared number (e.g. synthesize, then link). Escalate to a **scoped
fixpoint for `infer` with a hard iteration cap** *only if* the WP-0 spike proves
fixed passes cannot express WEMI linking. Decided empirically, not a priori.
**Spike (2026-06-01):** on real InterMARCXChange, 28/30 *Madame Bovary*
manifestations carry an explicit Work link (`145 $3`) — WEMI linking is a
**lookup**, not a cascade — so bounded passes are confirmed sufficient and no
fixpoint is needed; a cascade could only arise on the minority
fallback-synthesis path, which is itself bounded.

## Alternatives considered

(Weighed in full in `wp0-decisions.md` D5 / D6 / D7 / D8 / D11; in brief.)

- **Authority via live API instead of a snapshot.** Rejected for V1:
  non-deterministic and network-bound; a snapshot gives the same quality,
  offline and reproducible. Live lookup is additive later.
- **Persistent identity store for cross-run clustering.** Rejected for V1:
  violates the no-storage principle; deterministic ids already give cross-run
  stability for exact-key / authority matches. Store is V2, additive behind the
  seam.
- **Fully automatic FRBRisation.** Rejected: unattended wrong merges erode trust
  at the scale where they are least reviewable.
- **Fully human-in-the-loop.** Rejected: does not scale to millions; turns
  conversion into a review project.
- **Maximal merging (recall-first).** Rejected: a false merge is costlier and
  harder to undo than a miss; authority data is precision-first.
- **Sequential / mutable ids.** Rejected: breaks idempotency and reproducibility
  (ADR 0008); identity must be a pure function of content.

## Consequences

- FRBRisation is a set of `infer` / `repair` rule sets in the LRMoo plugin,
  using the runtime minting capability (ADR 0014) and the resolver seam.
- Regesta becomes, **by construction, an entity-reconciliation engine that runs
  during conversion** — FRBRisation + authority reconciliation in one pass — a
  major capability, bounded honestly (precision-first, batch-local, tiered).
- A pinned authority snapshot becomes an optional, versioned **input**; runs
  record it. No store, no live dependency, and no core vocabulary touched: the
  intelligence is plugin-side; the only core change was ADR 0014's minting
  permission.
- The synthesized-entity identity scheme **extends ADR 0012**; idempotency
  (ADR 0008) becomes a property test over re-runs (a second run mints nothing).
- Un-FRBRisable / ambiguous cases are accounted through the loss model
  (ADR 0015).
- The hard parts — work-key composition, the confidence threshold, over/under-
  merge rates, whether bounded passes suffice — are **measured on real data in
  the WP-0 spike**. Fidelity is a reported metric, not a binary claim.
- This formalises the reversal of the README's "deduplication out of scope"
  boundary (roadmap §1–2).

## What this ADR does not decide

- The exact work-key ingredients, the confidence threshold, and the first
  authority snapshot to pin — **tuned by the WP-0 spike** (D5).
- Whether `infer` ultimately needs a scoped fixpoint — **resolved by the WEMI
  spike (2026-06-01): no.** Explicit `145 $3` Work links dominate (28/30), so
  linking is a lookup; bounded passes suffice (D8). ADR 0004 is unaffected.
- Reconciliation of subjects / events (Tier 3 / 4) — additive, later, behind the
  seam (D11).
- The RDF / authority loader implementation and serialisation library — WP-2 /
  WP-4 (roadmap "can be deferred").
