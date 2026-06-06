# Independent FRBRisation eval — Regesta vs the BIB-R benchmark

This measures Regesta's MARC21 → WEMI Work clustering against an **independent,
third-party** gold: the **BIB-R "Benchmark of FRBRization solutions"**
(http://bib-r.github.io/, CC BY-NC). It is the non-circular counterpart that
[`frbrisation-fidelity.md`](./frbrisation-fidelity.md) §3 says is needed, and a
**third corpus** for ADR 0018's recall ceiling — after Bovary/data.bnf and
OpenLibrary.

Reproduce: `clojure -M:sandbox:test/unit -n regesta.eval.bibr-frbrisation-test`.
Fixtures + derivation + licence: `test/fixtures/bibr-gold/README.md`.

---

## 0. Why this is not circular

The C2 eval scores P = R = 1.0, but its gold (`workManifested`) and its input
(`f145 $3`) are two serialisations of the **same** BnF link — so it is a
*transcription* check, not evidence of inference, and it says so. BIB-R removes that
circularity: it is a **hand-curated MARC → FRBR/RDA gold** with no dependence on any
link Regesta reads. Regesta sees only flat MARC; the gold is the cataloguer's
independent WEMI grouping.

## 1. What Regesta does here, and what the gold demands

- **Regesta** projects flat MARC21 by the *floor* rung (`lrmoo.project`): the Work
  key is `agent + norm(transcribed 245 title)`, and a Work is minted only when a
  creator is present. So Regesta groups two records iff they share a creator **and**
  a normalised transcribed title.
- **The gold** groups by the cataloguer's *uniform* Work (its work URIs are
  author+uniform-title slugs). It therefore unifies **transcribed-title variants**,
  translations and integral/abridged editions of one Work — e.g. La Fontaine's
  *Fables* across `Fables`, *Les plus belles fables*, *Fables, livres I–VII…*; an
  article variant *Le capitaine Fracasse* / *Capitaine Fracasse*; a French/Spanish
  translation pair.

The gap between the two is exactly the **title-variant recall** that the floor key
cannot recover — measured here on an independent corpus.

## 2. Result

System: ingest the 560 BIB-RCAT MARC records, cluster by minted F1 Work id (records
with no creator are singletons). Gold: the BIB-R Work grouping. Records are joined to
the gold **by normalised title**, unambiguous titles only. Scored pairwise over the
joined records.

| joined | tp | fp | fn | precision | recall | F1 |
|-------:|---:|---:|---:|----------:|-------:|----:|
| **362 / 560** | 385 | 0 | 112 | **1.000** | **0.775** | **0.873** |

- **Precision = 1.000.** Regesta never false-merges two distinct gold Works — the
  `agent + norm-title` key is conservative (consistent with every prior eval).
- **Recall = 0.775.** Of the gold's same-Work pairs, the floor key recovers ~78 %;
  the 112 missed pairs are the title variants the gold unifies and a transcribed key
  cannot. Higher than Bovary's 0.43 (this children's-literature catalogue repeats
  exact titles across editions far more than the 28 heterogeneous Bovary editions),
  lower than 1.0 — the ceiling is real and corpus-dependent.

## 3. Honest scope — the join, stated not hidden

The MARC `001`s carry no link to the gold's title-slug URIs, so the join is
**by normalised title**, and a title the gold spreads across several work URIs is
**excluded**. The gold itself fragments some works (La Fontaine's *Fables* appears
under several work URIs), which caps clean coverage at **362 / 560 ≈ 65 %**. The
metric is therefore over the cleanly-joinable subset, and the coverage is asserted
as a first-class number, not buried. This does not bias precision (Regesta makes no
false merge on the joined set) and only narrows the recall measurement to titles the
gold resolves unambiguously.

## 4. Bilan

- **Corroborated, independently.** P = 1.0, R ≈ 0.78 on a third corpus with a gold
  that owes nothing to `f145`: precision-first with a real title-variant recall gap.
  This is the independent evidence `frbrisation-fidelity.md` §3 asked for, and it
  agrees with the ADR 0018 thesis — the embedded authority link (or a uniform-title
  bridge) is what recall beyond exact-title needs; the floor cannot invent it.
- **Bounded by the join, not by the model.** The 65 % coverage is a property of
  aligning two catalogues with no shared identifier, not of the projection. A future
  run with an id-aligned gold (or a uniform-title bridge on the system side) would
  raise both coverage and the achievable recall.
- **Data credit.** BIB-R (bib-r.github.io), CC BY-NC; used here for non-commercial
  evaluation with attribution.
