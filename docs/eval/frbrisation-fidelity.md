# FRBRisation fidelity — measured against data.bnf.fr ground truth

This records what INTERMARC → WEMI FRBRisation (WP-3, ADR 0016) actually does on
real BnF data, measured two ways and reproducible from committed fixtures:

- **C2 (fidelity, on the showcase)** — `regesta.eval.bovary-c2-test`
- **Showcase boundary (coverage, off the showcase)** — `regesta.eval.showcase-boundary-test`

Run both with `clojure -M:sandbox:test/unit`.

---

## 0. A correction: the GET → POST false negative

An earlier scoped C2 probe reported **0** `workManifested` matches for our ARKs and
I concluded data.bnf.fr published no FRBR Work links for our records — that the
avenue was exhausted. **That conclusion was wrong: a false negative.** The SPARQL
`VALUES` join is several KB; sent on the query string of a **GET** it was
truncated/rejected and returned nothing. Re-issued as a SPARQL **POST**, the same
query returns 28 rows. The avenue was never dead — the request was malformed.

The corrected ground truth is committed at `test/fixtures/c2-gold/bovary/`
(CSV + the SPARQL query + provenance README).

## 1. C2 — fidelity on the Madame Bovary showcase

**Gold.** data.bnf.fr's own FRBR grouping via `rdarel:workManifested`, scoped to
the ARKs in our fixtures: **28 manifestations → 1 Work**,
`http://data.bnf.fr/ark:/12148/cb11938746n#about` ("Madame Bovary").

**System.** FRBRise the 30 records of
`bib-flaubert-madame-bovary-start1-max30.xml`; cluster each by the Work it is
linked to (Manifestation —R4→ Expression ←R3— Work), falling back to a singleton.

**Result (pairwise, over all 30 records):**

| tp  | fp | fn | precision | recall | F1   |
|-----|----|----|-----------|--------|------|
| 378 | 0  | 0  | **1.000** | **1.000** | **1.000** |

Our clustering reproduces the gold exactly: the 28 linked editions collapse to one
Work with **no false split** across the heterogeneous editions (illustrated,
abridged, "texte intégral", …) and **no false merge** with the two records the gold
keeps out.

**What this does *and does not* prove — the honest caveat.** The gold
(`workManifested`) and our input (`f145 $3 = 11938746`) are two serialisations of
the **same** BnF work-authority link — the Work ARK stem *is* the `f145_3` value
(`cb`**`11938746`**`n`). So a perfect score confirms **faithful transcription**: we
read the authority link, mint stable content-derived ids (ADR 0008), and group with
neither a split nor a merge bug. It does **not** demonstrate inference — there is
almost none; it is a lookup. The test `the-link-we-read-is-the-link-the-gold-encodes`
asserts this identity outright, so the caveat is part of the eval, not a footnote.

The two records the gold excludes, and that we also leave un-clustered:

- `cb32056819r` — *"Le Réalisme. Madame Bovary…"*, a study guide: a genuinely
  different Work. **True negative** (correct on both sides).
- `cb48756313f` — *"Madame Bovary"* (Flaubert), plausibly the novel, but it carries
  no `f145_3` *and* no `f100_3`, and data.bnf publishes no `workManifested` for it.
  A **shared blind spot** of source and ground truth — not our clustering error.

## 2. Showcase boundary — how rarely the link is present

The same projection, run across eight other BnF genre fixtures (Hugo, Gracq,
monographs, periodicals, youth, music recordings, two analytic sets):

| corpus                | records | with `f145 $3` | Expressions | Works |
|-----------------------|--------:|---------------:|------------:|------:|
| **showcase** (Bovary) |      30 |             28 |          28 |     1 |
| **off-showcase** (8)  |     220 |              0 |           0 |     0 |

Outside the showcase, **0 / 220** records carry the link, so FRBRisation degrades
to a **bare F3_Manifestation** — no Expression, no Work. Across all committed
bibliographic fixtures, projection beyond a Manifestation reaches **28 / 250 ≈
11.2 %** of records, and every one of those belongs to the *single* showcase Work.

(`bib-digitized-start1-max30.xml` is excluded: it is a legitimately empty SRU
response — `numberOfRecords = 0` with a BnF query diagnostic — and our parser
correctly yields 0 records, not a parse failure.)

## 3. Bilan

- **Validated.** On records that carry the embedded Work link, FRBRisation is
  faithful and stable: 28 editions → 1 Work, P = R = 1.0, idempotent, no spurious
  splits or merges. The importer + identity + clustering path has no regression on a
  real, heterogeneous cluster.
- **Bounded.** That path fires for ~11 % of our records and 0 % off the showcase.
  Recall on records lacking `f145 $3` is ~0 because there is no bridging step yet —
  the projection cannot recover a Work it was not handed. This is the gap to close
  (author + uniform-title bridging, D-series), and it is now measured rather than
  asserted.
- **Caveat retained.** The C2 score is high because gold and input share one BnF
  signal; it is a transcription check, not evidence of inference. Independent
  evaluation of bridging will need a gold that is *not* derived from `f145`/
  `workManifested` (e.g. hand-labelled or cross-source).
