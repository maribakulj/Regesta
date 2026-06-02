#!/usr/bin/env python3
"""WP-0 FRBRisation spike (THROWAWAY).

Parses BnF INTERMARC ISO 2709, derives a naive Work key (author + title),
clusters records into Works, and probes author reconciliation against the
INTERMARC authority file. Purpose: measure over/under-merge and authority
match-rate on real data to tune decisions D5/D6/D11. Not production code.

Usage:
  python3 dev/spike/frbrise_intermarc.py <bib.not> <authorities.not>
"""
import sys, re, unicodedata
from collections import Counter, defaultdict

RT, FT, US = 0x1d, 0x1e, 0x1f  # record term, field term, subfield delim
ARTICLES = {"le","la","les","l","the","a","an","der","die","das","il","lo",
            "un","une","el","los","las","de","of"}


def parse(path):
    data = open(path, "rb").read()
    out = []
    for rec in data.split(bytes([RT])):
        if len(rec) < 25:
            continue
        leader = rec[:24]
        try:
            base = int(leader[12:17])
        except ValueError:
            continue
        directory = rec[24:base].rstrip(bytes([FT]))
        fields = []
        for i in range(0, (len(directory) // 12) * 12, 12):
            e = directory[i:i+12]
            tag = e[0:3].decode("ascii", "replace")
            try:
                ln, st = int(e[3:7]), int(e[7:12])
            except ValueError:
                continue
            fdata = rec[base+st: base+st+ln].rstrip(bytes([FT]))
            fields.append((tag, fdata))
        out.append((leader, fields))
    return out


def subfields(fdata):
    sf = defaultdict(list)
    for p in fdata.split(bytes([US]))[1:]:
        if p:
            sf[chr(p[0])].append(p[1:].decode("utf-8", "replace"))
    return sf


def sfval(fields, tags, code):
    for t, fd in fields:
        if t in tags:
            v = subfields(fd).get(code)
            if v:
                return v[0]
    return None


def norm(s):
    if not s:
        return ""
    s = "".join(c for c in unicodedata.normalize("NFKD", s)
                if not unicodedata.combining(c)).lower()
    toks = re.sub(r"[^a-z0-9 ]", " ", s).split()
    while toks and toks[0] in ARTICLES:
        toks = toks[1:]
    return " ".join(toks)


def main():
    bib_path, auth_path = sys.argv[1], sys.argv[2]
    bib = parse(bib_path)
    auth = parse(auth_path)
    print(f"== bib: {len(bib)} records | authorities: {len(auth)} records ==\n")

    # tag frequency (records containing each tag)
    tagc = Counter()
    for _, fields in bib:
        for t in {t for t, _ in fields}:
            tagc[t] += 1
    print("top tags (records containing tag):")
    for t, c in sorted(tagc.items(), key=lambda x: -x[1])[:22]:
        print(f"  {t}: {c}")
    print()

    # one decoded sample record
    print("== sample record (decoded) ==")
    for t, fd in bib[0][1]:
        sf = subfields(fd)
        if sf:
            print(f"  {t} " + " ".join(f"${k}{v[0][:60]}" for k, v in sf.items()))
        else:
            print(f"  {t} {fd.decode('utf-8','replace')[:60]}")
    print()

    # work-key clustering
    AUTH_TAGS = {"100", "110", "111"}
    n_auth_present = n_title = 0
    clusters = defaultdict(list)
    for i, (_, fields) in enumerate(bib):
        a = sfval(fields, AUTH_TAGS, "a")
        ti = sfval(fields, {"245"}, "a")
        if a:
            n_auth_present += 1
        if ti:
            n_title += 1
        key = (norm(a), norm(ti))
        clusters[key].append((norm(a) or "—", ti or "<no 245$a>"))

    sizes = Counter(len(v) for v in clusters.values())
    print(f"records with author (1XX$a): {n_auth_present}/{len(bib)}")
    print(f"records with title (245$a):  {n_title}/{len(bib)}")
    print(f"distinct work-keys:          {len(clusters)}")
    print(f"cluster-size distribution:   "
          + ", ".join(f"{s}->{n}" for s, n in sorted(sizes.items())))
    print()
    print("== clusters with >1 record (grouped Works) — eyeball over-merge ==")
    multi = sorted(((k, v) for k, v in clusters.items() if len(v) > 1),
                   key=lambda x: -len(x[1]))
    for (ka, kt), v in multi[:12]:
        print(f"  [{len(v)}] key=({ka!r},{kt!r})")
        for _, ti in v[:4]:
            print(f"        · {ti[:72]}")
    print()

    # under-merge probe: same author, near-but-distinct title keys
    by_author = defaultdict(set)
    for (ka, kt) in clusters:
        if ka:
            by_author[ka].add(kt)
    spread = sorted(((a, ts) for a, ts in by_author.items() if len(ts) > 3),
                    key=lambda x: -len(x[1]))
    print("== authors with many distinct title-keys (under-merge candidates) ==")
    for a, ts in spread[:6]:
        print(f"  {a!r}: {len(ts)} distinct title-keys")
    print()

    # reconciliation signal #1: embedded authority id ($3) — the real signal
    ALL_ACCESS = {"100", "110", "111", "700", "710", "600", "606", "607"}
    n_100 = sum(1 for _, f in bib if sfval(f, AUTH_TAGS, "a"))
    n_100_id = sum(1 for _, f in bib if sfval(f, AUTH_TAGS, "3"))
    ap_total = ap_with_id = 0
    distinct_ids = set()
    for _, f in bib:
        for t, fd in f:
            if t in ALL_ACCESS:
                sf = subfields(fd)
                if "a" in sf or "3" in sf:
                    ap_total += 1
                    if "3" in sf:
                        ap_with_id += 1
                        distinct_ids.update(sf["3"])
    print("== reconciliation signal: embedded authority id ($3) ==")
    print(f"main-author records (1XX$a):      {n_100}"
          + (f"  ...with $3: {n_100_id} ({100*n_100_id//n_100}%)" if n_100 else ""))
    print(f"access-point fields (1/6/7XX):    {ap_total}"
          + (f"  ...with $3: {ap_with_id} ({100*ap_with_id//ap_total}%)" if ap_total else ""))
    print(f"distinct authority ids referenced: {len(distinct_ids)}")
    print()

    # reconciliation signal #2: exact normalized name match (fallback, for contrast)
    auth_headings = set()
    for _, fields in auth:
        h = sfval(fields, AUTH_TAGS, "a")
        if h:
            auth_headings.add(norm(h))
    bib_authors = {norm(sfval(f, AUTH_TAGS, "a"))
                   for _, f in bib if sfval(f, AUTH_TAGS, "a")}
    bib_authors.discard("")
    matched = bib_authors & auth_headings
    print("== authority reconciliation (exact normalized 1XX$a match) ==")
    print(f"distinct authority headings: {len(auth_headings)}")
    print(f"distinct bib authors:        {len(bib_authors)}")
    if bib_authors:
        print(f"matched in authority file:   {len(matched)} "
              f"({100*len(matched)//len(bib_authors)}%)")
    print("  sample unmatched:")
    for a in list(bib_authors - auth_headings)[:8]:
        print(f"        · {a!r}")


if __name__ == "__main__":
    main()
