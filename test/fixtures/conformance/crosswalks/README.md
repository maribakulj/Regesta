# Conformance fixtures — the LoC MARC21→DC crosswalk (oracle)

The **official** Library of Congress MARCXML → OAI Dublin Core stylesheet, plus the
utility stylesheet it imports, used by `regesta.eval.marc-dc-oracle-test` as an
*independent oracle* for `convert :marc21 → :dc`.

| File | Source | Licence |
|------|--------|---------|
| `loc-MARC21slim2OAIDC.xsl` | `https://www.loc.gov/standards/marcxml/xslt/MARC21slim2OAIDC.xsl` | public domain (US Gov work) |
| `MARC21slimUtils.xsl` | `https://www.loc.gov/standards/marcxml/xslt/MARC21slimUtils.xsl` | public domain (US Gov work) |

Both are **unmodified** LoC stylesheets. The input is the LoC's own sample
`test/fixtures/documentary/marc21/marcxml/loc_collection.xml` (2 records).

## The one offline concession

`MARC21slim2OAIDC.xsl` begins with

```xml
<xsl:import href="http://www.loc.gov/standards/marcxml/xslt/MARC21slimUtils.xsl"/>
```

an **absolute** URL that returns 403 in the offline sandbox. The eval does not edit
the stylesheet; it supplies a `javax.xml.transform.URIResolver` that redirects this
one import to the vendored `MARC21slimUtils.xsl`. The transform itself is the LoC's,
run by the JDK's built-in XSLT 1.0 engine (no added dependency). Where the network
resolves, the same eval runs with no resolver and consumes the import directly.

## What the oracle shows

See `docs/eval/marc-dc-crosswalk.md`. Headline: **5 of the oracle's 9 DC element
types** are covered (`title creator date description identifier`); the gap is the
four coded/controlled axes (`language publisher subject type`) the canonical floor
does not model (ADR 0003); `date` agrees exactly; `identifier` provenance diverges
(disjointly — 010/035 control numbers vs 856 access URLs).
