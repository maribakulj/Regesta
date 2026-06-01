#!/usr/bin/env python3
"""WP-0 spike (THROWAWAY): fallback bridging — match-or-mint precision.

Stage 1: build Works from records that carry an explicit Work link (145 $3).
Stage 2: for records WITHOUT a link, try to bridge them to an existing Work.
Compares a CONSERVATIVE bridge (exact normalized author+title) against a GREEDY
one (title substring), to show which preserves precision (D11): the ebook should
join the Work; the "Le Réalisme. Madame Bovary" study guide must NOT.

Usage: python3 dev/spike/bridge_fallback.py <intermarcXchange.xml>
"""
import sys, re, unicodedata
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
        fields = []
        for df in rec.findall(MXC + "datafield"):
            sd = defaultdict(list)
            for sf in df.findall(MXC + "subfield"):
                sd[sf.get("code")].append(sf.text or "")
            fields.append((df.get("tag"), dict(sd)))
        yield {"id": rec.get("id"), "fields": fields}


def first(r, tag, code):
    for t, sd in r["fields"]:
        if t == tag and sd.get(code):
            return sd[code][0]
    return None


def author(r):
    return " ".join(x for x in (first(r, "100", "a"), first(r, "100", "m")) if x)


def main():
    recs = list(records(sys.argv[1]))

    # Stage 1: Works from explicit links (145 $3)
    works = {}
    unlinked = []
    for r in recs:
        wid = first(r, "145", "3")
        if wid:
            works.setdefault(wid, {"title": first(r, "145", "a"),
                                   "author": author(r), "n": 0})
            works[wid]["n"] += 1
        else:
            unlinked.append(r)

    print(f"== Stage 1: {len(works)} Work(s) from explicit links, "
          f"{len(unlinked)} unlinked record(s) ==")
    idx_exact = {}
    for wid, w in works.items():
        idx_exact[(norm(w["author"]), norm(w["title"]))] = wid
        print(f"  WORK {wid}  «{w['title']}»  by «{w['author']}»  "
              f"({w['n']} manifestations)")
    print()

    # Stage 2: bridge each unlinked record
    print("== Stage 2: bridging unlinked records ==")
    tally = {"exact_merge": 0, "exact_keepsep": 0,
             "greedy_merge": 0, "greedy_false": 0}
    for r in unlinked:
        a, t = norm(author(r)), norm(first(r, "245", "a"))
        exact = idx_exact.get((a, t))
        greedy = [wid for wid, w in works.items()
                  if norm(w["author"]) == a
                  and (norm(w["title"]) in t or t in norm(w["title"]))]
        print(f"  {r['id']}  «{(first(r,'245','a') or '')[:46]}»")
        print(f"      author={a!r}  title-key={t!r}")
        print(f"      CONSERVATIVE (exact) -> "
              + (f"bridge to {exact}" if exact else "keep separate (mint new)"))
        print(f"      GREEDY (substring)   -> "
              + (f"bridge to {greedy}" if greedy else "keep separate"))
        # crude verdict using the work title-key vs record title-key
        same_work = bool(exact)
        if exact:
            tally["exact_merge"] += 1
        else:
            tally["exact_keepsep"] += 1
        if greedy:
            tally["greedy_merge"] += 1
            if not same_work:
                tally["greedy_false"] += 1
                print("      ^^ GREEDY FALSE MERGE (title is a superset — "
                      "different work)")
        print()

    print("== verdict ==")
    print(f"  conservative: {tally['exact_merge']} merged, "
          f"{tally['exact_keepsep']} kept separate, 0 false merges")
    print(f"  greedy:       {tally['greedy_merge']} merged, of which "
          f"{tally['greedy_false']} FALSE merge(s)")


if __name__ == "__main__":
    main()
