# Documentary fixtures

Curated real-world metadata samples spanning the universal V1 scope
(IIIF ↔ INTERMARC) plus the pivot ontologies and identity authorities. Used by
the WP-0 FRBRisation spike (`dev/spike/`) and, later, by spoke tests (WP-4).

**These are third-party samples, not Regesta's own work.** Every file is traced
to its upstream source in [`MANIFEST.tsv`](./MANIFEST.tsv) (fixture path → source
URL). Keep that file as the attribution record.

## What's here

| Area | Files | Use |
|---|---|---|
| `intermarc/` | BnF INTERMARC, ISO 2709 — bibliographic **and** authority records, in ISO-5426 **and** UTF-8 | FRBRisation spike; the hardest dialect (D10) |
| `bnf-rdf/` | a real data.bnf.fr record (Machiavelli, ARK cb119137957) in 5 RDF serialisations | authority resolver #2 (D5) |
| `lrm/lrmoo/`, `cidoc-crm/` | LRMoo v1.0 OWL, CIDOC-CRM v7.1.3 RDF | the pivot vocabulary (WP-2) |
| `marc21/`, `mods/`, `dublin-core/` | LoC MARCXML, MODS, W3C DC | bibliographic spokes (WP-4) |
| `iiif/` | image, book, newspaper-OCR manifests | IIIF spoke; `:canon/digital-object` |
| `ead/`, `eac-cpf/`, `mads/` | EAD 2002 + EAD3, EAC-CPF, MADS | archival / authority spokes |
| `mets/`, `gallica/` | METS, Gallica ALTO + plain text | digitisation / structure |

## Licensing (respect upstream terms)

- **BnF** (`intermarc/`, `bnf-rdf/`, `gallica/`) — Licence Ouverte / Etalab
  (free reuse incl. commercial, **attribution required**).
- **Library of Congress** (`marc21/`, `mods/`, `mads/`) — US Government public
  domain.
- **IIIF cookbook**, **CIDOC-CRM**, **LRMoo** — open (specification examples).
- **EAD / EAC-CPF / METS / OCR-D** — under their respective upstream
  repositories' licences (see `MANIFEST.tsv` URLs).

## Notes

- A few entries listed in `MANIFEST.tsv` were not present in the upload and are
  absent here: `marc21/marcxml/loc_record.xml`, `intermarc-ng/*.html`,
  `dublin-core/bnf_oai_dc_example.xml`, `pagexml/ocrd_page_example.xml`.
- INTERMARC is provided in two encodings; the spike uses the **UTF-8** variant.
  The ISO-5426 variant is kept for charset-handling tests.
