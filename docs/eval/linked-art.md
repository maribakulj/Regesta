# Linked Art export — the verified WEMI → Linked Art mapping

`regesta.plugins.lrmoo.linked-art` serialises the WEMI view as
[Linked Art](https://linked.art) JSON-LD — the museum-sector output (the Louvre
target). Linked Art is a profile of CIDOC-CRM expressed as a structured JSON-LD
tree (not RDF triples-as-JSON-LD), so the export builds the resource tree Linked
Art consumers expect.

The mapping is **grounded in the official Linked Art model examples**, not guessed
— the load-bearing decision (how to represent FRBR Work/Expression/Manifestation,
which Linked Art has no native FRBR levels for) was taken from the examples below,
fetched and read directly.

## Entity mapping

The FRBR chain maps to three *distinct* Linked Art resources (no collapse — Linked
Art is more precise here than plain CRM, where F2/F3 both become E73):

| WEMI | Linked Art type | linked by | source example |
|------|-----------------|-----------|----------------|
| **F3 Manifestation** (the published item, carries the ARK) | `HumanMadeObject` | `carries` → the Expression | a book *carries* its text content |
| **F2 Expression** (the text) | `LinguisticObject` | `part_of` → the Work | the text *part_of* the conceptualization |
| **F1 Work** (the abstract conceptualization) | `PropositionalObject` | — | matches our CRM choice F1 → E89 |

This lines up with the CIDOC-CRM down-projection (`lrmoo.crm`): F1 → E89
(PropositionalObject), and the Expression/Manifestation distinction is preserved.

## Field patterns (each AAT term verified against an example)

| Our predicate | Linked Art pattern | Getty AAT |
|---------------|--------------------|-----------|
| title (`R33`) | `identified_by` → `Name` | `aat:300404670` Primary Name |
| ARK / `:canon/identifier` | `identified_by` → `Identifier` | `aat:300435704` System-Assigned Number |
| creator (`:canon/agent`) | on the Expression: `created_by` → `Creation` → `carried_out_by` → `Person` | — |
| note (`:canon/note`) | `referred_to_by` → `LinguisticObject` | `aat:300435416` Description / `aat:300418049` Brief Text |
| digital (`:canon/digital-object`) | `representation` → `VisualItem` → `digitally_shown_by` → `DigitalObject` | `aat:300215302` Digital Image |

`@context`: `https://linked.art/ns/v1/linked-art.json`.

## Sources (fetched & read, 2026-06)

The reference bundle (Linked Art `@context` + curated model example JSON files)
was fetched from the official site and read directly. The decisive examples:

- **object → carries → text** (`HumanMadeObject` carries a `LinguisticObject`) —
  `https://linked.art/example/object/yul_10801219/1`
- **text → part_of → conceptualization** (`LinguisticObject` part_of a
  `PropositionalObject`) — `https://linked.art/example/text/koot_nightwatch/1`
- **creation of a text** (`created_by` → `Creation` → `Person`) —
  `https://linked.art/example/text/koot_nightwatch/2`
- **Name / Identifier / Statement patterns** — the Linked Art model `base` page.
- **digital image / IIIF** (`representation` → `VisualItem` → `digitally_shown_by`
  → `DigitalObject`; `subject_of` for a IIIF manifest) — the Linked Art model
  `digital` page.

## Identified agents (the first brick of agent reconciliation)

When the source carries an authority identifier for the creator, the agent is
minted as a **first-class `:crm/E21_Person` entity** whose `:iri` is that
identifier, and the Linked Art `created_by` Person carries it as `id` — an
*identified* creator, not a bare label:

```json
"created_by": { "type": "Creation", "carried_out_by": [
  { "type": "Person", "id": "https://isni.org/isni/0000000122762442",
    "_label": "Flaubert, Gustave" } ] }
```

Because the ISNI is a *determinate* identifier, the agent identity is certified
(D7) — exactly the signal real agent reconciliation needs. V1 mints it for
INTERMARC's 100 `$1` ISNI (`regesta.plugins.intermarc.frbrise/with-identified-agent`);
the string-only canonical floor (ADR 0003) cannot hold an authority-linked agent,
so the floor spokes still emit a label-only creator. Cross-record agent
de-duplication is ADR 0018 proper (deliberately not done here, though two records
with the same ISNI already mint the same agent id by content).

## Honest scope (V1)

- A Linked Art-**profile** serialisation; **not** validated against a Linked Art
  processor or shape (no JSON-LD framing/expansion round-trip).
- `created_by` is emitted only when a `:canon/agent` is present — i.e. for the
  floor spokes (DC, MARC21, MODS). INTERMARC's creator lives in `:intermarc/f100_a`
  and is not lifted to `:canon/agent`, so its Linked Art output omits the creator.
- Dates, relations and native predicates are not expressed; they are reported as
  export-edge loss (ADR 0015).
- A `subject_of` link to the IIIF *manifest* (vs `representation` of the image) is
  not yet emitted — a later refinement.
