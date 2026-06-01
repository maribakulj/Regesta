# WP-0 spike — FRBRisation on real INTERMARC (findings)

- Status: **spike (throwaway)** — empirical input to ADR 0016 (D5 / D6 / D11),
  not production code.
- Date: 2026-06-01
- Inputs: `test/fixtures/documentary/intermarc/iso2709_utf8/` (BnF INTERMARC,
  149 bibliographic + 122 authority records).
- Method: `dev/spike/frbrise_intermarc.py` — parse ISO 2709, derive a naive Work
  key (author + title), cluster, probe reconciliation.

## Headline findings

1. **Parser works on real ISO 2709.** 149 bib + 122 authority records decoded
   (leader / directory / subfields), multiscript content intact.

2. **`$3` embedded authority id has 100% coverage — this is the reconciliation
   signal.** Every access point (1XX / 6XX / 7XX) carries a BnF authority record
   number in `$3`: 108/108 main authors, **346/346 access-point fields**, 370
   distinct authority ids. Meanwhile exact normalized **name** matching against
   the authority file scored **4%** (heavy transliteration: Arabic, Hebrew,
   Chinese romanisations). → For authority-controlled sources, reconciliation is
   a **lookup on `$3`**, not a fuzzy-matching problem.

3. **Multiscript parallel fields are pervasive.** Title (245), edition (250),
   publication (260) are *repeated* — one romanised, one in the original script
   (e.g. `245 $aAdūnīs muntaḥilan` alongside `245 $aأدونيس منتحلا`),
   distinguished by `$w`. A naive "first 245$a" key silently picks one script →
   the same Work splits across script variants.

4. **Title-only clustering over-merges.** 8 records titled "Li čo sil lok" with
   *no* 1XX author collapsed into a single Work on title alone — exactly the
   precision hazard. With no author and no `$3` on a work-level access point, the
   signal is too weak to *assert* a merge.

5. **Record (Manifestation) identity is already in the data.** `001` = the BnF
   record number (FRBNF…), `003` = its ARK (`cb37319050s`). Manifestation
   identity needs no synthesis — read it off the record.

6. **~27% of records have no personal author** (100 present in 108/149): title
   main entry / corporate. The Work key cannot assume a 1XX exists.

## Refinements to the accepted decisions

These *tune* what ADR 0016 deferred to the spike; none reopens a decision.

- **D5 — resolver order (refined).** The identity resolver should try, in order:
  1. **embedded authority id** (`$3`, and `$0`/`$1` in other dialects) →
     resolve to the authority IRI via the dump. *Primary; 100% on INTERMARC,
     fully deterministic.*
  2. **deterministic work-key hash** — fallback when no embedded id (CSV, some
     MARC21).
  3. fuzzy name matching — last resort only; weak (4%) and transliteration-
     fragile. Effectively deprioritised.
- **D5 — work-key (refined).** Canonicalise **multiscript**: prefer the original
  script (or a single stable romanisation) so script variants converge. Do not
  build a Work key from a single arbitrary parallel field.
- **D5 — Manifestation key (refined).** Use `001` / `003` (ARK) directly; no
  synthesis needed at the Manifestation level.
- **D11 / D7 — precision-first (confirmed on real data).** Never *assert* a merge
  on title alone (no author / no `$3`); route those to proposals. The ×8 case is
  the concrete justification.
- **D6 — clustering (confirmed).** With `$3` and ARK present, identity is
  deterministic from the data itself — batch-local clustering needs no store.

## Caveats / limits

- Small samples; the bib and authority files are **disjoint** sets, so the 4%
  name-match is partly sample mismatch — but the `$3` = 100% finding is intrinsic
  and decisive regardless.
- BnF INTERMARC is *exceptionally* authority-controlled; sources without embedded
  ids (some MARC21, CSV) still need the hash fallback — which is exactly why the
  resolver keeps it.
- The ISO-5426 variant was not exercised (charset handling still to validate).
- The spike measured the **Work key + reconciliation** only; it did **not** yet
  exercise Expression/Manifestation linking, so **D8 (fixed passes vs fixpoint)
  remains open** as planned — to be probed when WEMI linking is prototyped.
- Throwaway code; naive normalisation; illustrative multiscript handling.

## Net

The architecture holds. The spike **sharpens D5 decisively** (embedded `$3` is
the reconciliation key — reconciliation is a lookup, not ML), **confirms D11**
precision-first on real over-merge, and **surfaces multiscript** as a
first-class work-key concern. D8 stays open until WEMI linking is prototyped.
