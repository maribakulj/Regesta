#!/usr/bin/env python3
"""WP-0 spike (THROWAWAY): evaluate the fallback work-key against ground truth.

Across every InterMARCXChange bib fixture, use the explicit Work link (145 $3) as
GROUND TRUTH and measure how the naive fallback key (norm author + norm 245-title)
would perform if used instead:
  - OVER-MERGE: one fallback key spanning >1 distinct Work id (distinct works fused)
  - UNDER-MERGE: one Work id spanning >1 fallback key (one work split)
Also reports explicit-link coverage per file (it varies by genre/source).

Usage: python3 dev/spike/keyeval.py <dir-or-file> [more...]
"""
import sys, glob, os, re, unicodedata
import xml.etree.ElementTree as ET
from collections import defaultdict

MXC = "{info:lc/xmlns/marcxchange-v2}"
ART = {"le", "la", "les", "l", "the", "a", "an", "de", "of", "un", "une"}


def norm(s):
    if not s:
        return ""
    s = "".join(c for c in unicodedata.normalize("NFKD", s)
                if not unicodedata.combining(c)).lower()
    toks = re.sub(r"[^a-z0-9 ]", " ", s).split()
    while toks and toks[0] in ART:
        toks = toks[1:]
    return " ".join(toks)


def records(path):
    root = ET.parse(path).getroot()
    for rec in root.iter(MXC + "record"):
        f = []
        for df in rec.findall(MXC + "datafield"):
            sd = defaultdict(list)
            for sf in df.findall(MXC + "subfield"):
                sd[sf.get("code")].append(sf.text or "")
            f.append((df.get("tag"), dict(sd)))
        yield {"id": rec.get("id"), "fields": f}


def first(r, tag, code):
    for t, sd in r["fields"]:
        if t == tag and sd.get(code):
            return sd[code][0]
    return None


def author(r):
    return " ".join(x for x in (first(r, "100", "a"), first(r, "100", "m")) if x)


def expand(paths):
    for p in paths:
        if os.path.isdir(p):
            yield from sorted(glob.glob(p + "/*.xml"))
        else:
            yield p


def main():
    files = list(expand(sys.argv[1:]))
    tot_rec = tot_link = tot_over = tot_under = tot_works = 0
    over_examples, under_examples = [], []

    print(f"{'file':52} {'rec':>4} {'145$3%':>6} {'works':>5} "
          f"{'over':>4} {'under':>5}")
    for path in files:
        recs = list(records(path))
        if not recs:
            continue
        key2works = defaultdict(set)
        work2keys = defaultdict(set)
        linked = 0
        for r in recs:
            w = first(r, "145", "3")
            if not w:
                continue
            linked += 1
            k = (norm(author(r)), norm(first(r, "245", "a")))
            key2works[k].add(w)
            work2keys[w].add(k)
        works = len(work2keys)
        over = {k: ws for k, ws in key2works.items() if len(ws) > 1}
        under = {w: ks for w, ks in work2keys.items() if len(ks) > 1}
        name = os.path.basename(path)
        cov = (100 * linked // len(recs)) if recs else 0
        print(f"{name:52} {len(recs):>4} {cov:>5}% {works:>5} "
              f"{len(over):>4} {len(under):>5}")
        tot_rec += len(recs); tot_link += linked; tot_works += works
        tot_over += len(over); tot_under += len(under)
        for k, ws in list(over.items())[:2]:
            over_examples.append((name, k, ws))
        for w, ks in list(under.items())[:2]:
            under_examples.append((name, w, ks))

    print(f"\n{'TOTAL':52} {tot_rec:>4} "
          f"{(100*tot_link//tot_rec) if tot_rec else 0:>5}% {tot_works:>5} "
          f"{tot_over:>4} {tot_under:>5}")
    print(f"\nlink coverage varies; fallback-key would OVER-merge {tot_over} "
          f"key(s) (distinct works fused) and UNDER-merge {tot_under} "
          f"work(s) (one work split), measured against {tot_works} ground-truth "
          f"Works.\n")

    print("== sample OVER-merges (one author+title key -> several Works) ==")
    for name, k, ws in over_examples[:6]:
        print(f"  {name}: key={k}  -> Works {sorted(ws)}")
    print("\n== sample UNDER-merges (one Work -> several title keys) ==")
    for name, w, ks in under_examples[:6]:
        print(f"  {name}: Work {w}")
        for a, t in list(ks)[:4]:
            print(f"        · «{t[:48]}»  (author {a!r})")


if __name__ == "__main__":
    main()
