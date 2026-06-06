# Scale & performance budget (WP-7 / DoD #6)

What `regesta.convert/convert-stream` does, measured, and where the remaining bound
is — stated, not hidden. Tests: `regesta.convert-stream-test`.

## Why streaming is sound here (not the roadmap's "hard" clustering)

WP-7 flagged "cross-record Work clustering at scale, inherently global/stateful and
in tension with streaming" as the hard part. It is **not** hard in Regesta's design,
because clustering is **id-collision**, not a pairwise join: a Work's id is a pure
content hash of `agent + uniform/transcribed title` (ADR 0008). Two records of the
same Work mint the *same* id **without being compared** — convergence is a property
of the ids, realised when the emitted triples are aggregated by a downstream store
(roadmap §10, "converter → store"). So per-record conversion has **no cross-record
state**, and the converter can stream its triples in constant memory; the store (or
any `sort -u`) deduplicates by id. Regesta need never hold the corpus to "cluster".

## The mechanism

`convert-stream` `reduce`s a reducible/lazy record stream — one record at a time —
rendering each to the target and **folding** a loss accumulator (`loss-report/
accumulate`) whose footprint is bounded by the distinct fields/categories/edges, never
by the record count. Batch `convert`, by contrast, `mapv`s the whole corpus into a
vector and collects every loss diagnostic (O(N)). The streamed output and loss report
are **identical** to batch (`streamed-conversion-equals-batch`), minus the one
genuinely O(N) figure, `:distinct-losses` (the cross-corpus `[subject field]` dedup),
which stays batch-only.

## Measured budget

MARC21 → N-Triples, the 560-record BIB-R corpus cycled to scale, on the dev container,
**`-Xmx512m`**:

| corpus | wall | throughput | working set | loss `source-fields` |
|-------:|-----:|-----------:|------------:|---------------------:|
| 10 000 | 2.6 s | ~3 900 rec/s | ~33 MB | 25 |
| 100 000 | 23 s | ~4 300 rec/s | ~70 MB | 25 |

The two rows are the budget evidence: **throughput is flat** (per-record work is
constant) and the **working set does not grow with N** — 100 000 records ran in a
512 MB heap using ~70 MB, and the loss accumulator's distinct-field footprint stayed
**25 regardless of N** while the loss *total* scaled linearly (the
`the-loss-accumulator-is-bounded-in-the-corpus-size` test pins exact 5× scaling at no
footprint growth). Reproduce:

```
clojure -J-Xmx512m -M:sandbox:test/unit -n regesta.convert-stream-test
```

(Numbers are machine-relative; the committed tests assert the *properties* —
streamed == batch, bounded footprint, large-corpus completion — not wall-clock.)

## Honest scope — the remaining bound

- **Input parsing is eager per document.** The spoke importers parse a whole document
  (`data.xml/parse-str` builds the tree) before yielding records, so a *single
  multi-GB file* is still bounded by that document. The realistic BnF scale story —
  millions of records arriving as many SRU pages / files — streams at **document
  granularity**: import a page, stream it through `convert-stream`, discard it,
  repeat, in constant memory. True single-file streaming needs a lazy/pull XML parse
  per format (`data.xml/parse` over a Reader); it is the documented follow-up.
- **No CLI `--stream` verb yet.** `convert-stream` is the library seam; wiring it to
  the CLI (write to stdout/file incrementally) is low-risk but waits on the lazy input
  parse above, so the CLI does not over-promise constant memory on one giant file.
- **Distinct-loss dedup is batch-only** (above) — by design, the one O(N) metric.

## Bilan

- **Done.** Per-record conversion is stateless and streams in constant working set;
  100 000 records in a 512 MB heap, throughput flat, loss report bounded and
  byte-identical to batch (minus the deliberate O(N) figure). The id-collision design
  is what makes this hold — the converter streams, a store deduplicates.
- **Bounded by input parse, not by the model.** The remaining memory bound is the
  eager per-document XML parse, addressable with a lazy parser per format; the scale
  property of the conversion itself is in hand.
