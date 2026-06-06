# BIB-R FRBRization benchmark — gold fixture

An **independent** gold for evaluating Regesta's MARC21 → WEMI FRBRisation, from a
third-party benchmark. Consumed by `regesta.eval.bibr-frbrisation-test` and
documented in `docs/eval/bibr-frbrisation.md`.

## Source and licence

- **BIB-R — "Benchmark of FRBRization solutions"**, http://bib-r.github.io/
  (repository `bib-r/bib-r`). The BIB-RCAT dataset publishes one catalogue in three
  aligned forms: MARC21, FRBR-flat (RDF/XML) and FRBR-nested.
- Licence: **CC BY-NC** (Creative Commons Attribution-NonCommercial). These files
  are redistributed here under that licence, with attribution, for non-commercial
  evaluation. Regesta's own code is Apache-2.0; this third-party *data* keeps its
  own licence.

## Files in this directory

| file | what it is |
|------|------------|
| `bibrcat_marc21.xml.gz` | the **input** — BIB-RCAT `bibrcat_marc21.xml` verbatim (560 MARCXML-slim records), gzipped to keep the repo lean (it is ~1 MB raw, larger than any other fixture). The eval gunzips it in memory. |
| `work_grouping.tsv` | the **gold** — `work_core⇥manifestation_title`, one row per gold Manifestation, **derived** from `bibrcat_frbr_flat.xml` (see below). The 2.3 MB FRBR RDF source is *not* committed; this compact derivation is. |

The full upstream repo also ships the **T42** dataset and a Java evaluator; neither
is needed here and neither is included.

## How `work_grouping.tsv` was derived

From `bibrcat_frbr_flat.xml` (RDA-registry + FRBRer RDF). The Work→Manifestation
grouping is reconstructed by following the **inverse** RDA chain:

```
Work --rdaw:expressionOfWork--> Expression --rdae:manifestationOfExpression--> Manifestation
   (plus a Work's direct rdae:manifestationOfExpression)
```

For each Manifestation we emit `work_core ⇥ rdam:title`, where **`work_core`** is the
Work's `rdf:about` slug with its *derivation prefix* stripped
(`work` / `expwork` / `manifwork` / `manifillus` / `illus`). That prefix is a pure
artifact — the same Work appears as both `workFoo` and `expworkFoo` — so stripping it
merges those duplicates while preserving the genuine grouping: the slug core is an
author+uniform-title key, so the gold unifies transcribed-title **variants**,
translations and integral/abridged editions under one Work. (Augmentation /
whole-part Work-Work relations are deliberately **not** merged — they are distinct
related works.)

## How the eval joins to it (and the honest caveat)

The MARC `001`s (sequential `123456…`) carry **no** link to the gold's title-slug
URIs, so `regesta.eval.bibr-frbrisation-test` joins each MARC record to a gold Work
**by normalised title** (`regesta.text/norm` on both sides), keeping only titles the
gold maps to a *single* Work. A title the gold fragments across several work URIs
(e.g. La Fontaine's *Fables*) is **excluded**. Join coverage is **362 / 560
(≈ 65 %)** and is asserted as a first-class number — the metric is over the
cleanly-joinable subset, not the whole corpus.
