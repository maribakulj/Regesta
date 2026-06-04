# Conformance fixtures — Linked Art JSON Schema

The **official** Linked Art validation schemas (draft 2020-12) + one example,
used by `regesta.eval.linked-art-conformance-test` to check our Linked Art export.

| File | Source | Licence |
|------|--------|---------|
| `linked-art-schema-{core,object,text,digital}.json` | `https://linked.art/api/1.0/schema/…` (repo `linked-art/json-validator`) | Apache-2.0 |
| `example-object-mona-lisa.json` | `https://linked.art/example/object/0` | CC BY 4.0 |

## What is and isn't checked

Full draft-2020-12 validation needs a JSON Schema validator the offline sandbox
cannot fetch (Maven Central → 403, like Clojars). So the eval does a schema-
**derived** *root* check: the required fields `[@context id type _label]` and
`additionalProperties:false` (no key outside the schema's property set), read from
the official `object.json`. It is proven sound by running it on the Mona Lisa
example. Nested `$defs` (Name, Identifier, Creation…) and formats are deferred to
a full validator — runnable where the dependency resolves (a CI box with Maven
access); the schemas here are exactly what such a run would consume.
