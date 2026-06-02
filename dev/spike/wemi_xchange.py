#!/usr/bin/env python3
"""WP-0 spike (THROWAWAY): WEMI linking on InterMARCXChange.

Tests whether BnF InterMARCXChange records carry an explicit Manifestation->Work
link (field 145 $3 = Work authority id) and clusters manifestations into Works,
using the explicit link where present and an (author,title) hash fallback where
not. Probes the D8 / WEMI-linking question the first spike left open.

Usage: python3 dev/spike/wemi_xchange.py <intermarcXchange.xml>
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
        for cf in rec.findall(MXC + "controlfield"):
            fields.append((cf.get("tag"), {"": [cf.text or ""]}))
        for df in rec.findall(MXC + "datafield"):
            sd = defaultdict(list)
            for sf in df.findall(MXC + "subfield"):
                sd[sf.get("code")].append(sf.text or "")
            fields.append((df.get("tag"), dict(sd)))
        yield {"id": rec.get("id"), "type": rec.get("type"), "fields": fields}


def first(r, tag, code):
    for t, sd in r["fields"]:
        if t == tag and sd.get(code):
            return sd[code][0]
    return None


def main():
    path = sys.argv[1]
    recs = list(records(path))
    print(f"== {len(recs)} records | {path.split('/')[-1]} ==\n")

    n145 = n100 = n_isni = 0
    clusters = defaultdict(list)
    for r in recs:
        w_id = first(r, "145", "3")
        w_title = first(r, "145", "a")
        a_id = first(r, "100", "3")
        a_isni = first(r, "100", "1")
        a_name = first(r, "100", "a")
        title = first(r, "245", "a")
        n145 += bool(w_id)
        n100 += bool(a_id)
        n_isni += bool(a_isni)
        if w_id:
            key = ("WORK#" + w_id, w_title or "")
        else:
            key = ("HASH#" + norm(a_name) + "|" + norm(title), title or "")
        clusters[key].append((r["id"], title, w_id, a_id, a_isni))

    n = len(recs)
    print(f"explicit Work link  (145$3): {n145}/{n}")
    print(f"author authority id (100$3): {n100}/{n}")
    print(f"author ISNI         (100$1): {n_isni}/{n}")
    print(f"distinct Work clusters:      {len(clusters)}\n")

    print("== clusters (Work -> manifestations) ==")
    for (k, kt), v in sorted(clusters.items(), key=lambda x: -len(x[1])):
        kind = "explicit-145" if k.startswith("WORK#") else "fallback-hash"
        print(f"  [{len(v)}] {kind:13} «{(kt or title or '')[:38]}»  {k[:44]}")
        for ark, t, wid, aid, isni in v[:3]:
            print(f"        · {ark}  145$3={wid or '—'}  100$3={aid or '—'}  "
                  f"isni={'y' if isni else '—'}  «{(t or '')[:34]}»")

    linked = sum(1 for r in recs if first(r, "145", "3"))
    print(f"\n== {linked}/{n} Manifestations carry an explicit Work link "
          f"(lookup) | {n - linked} need fallback clustering ==")


if __name__ == "__main__":
    main()
