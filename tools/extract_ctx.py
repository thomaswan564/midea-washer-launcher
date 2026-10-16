#!/usr/bin/env python3
"""Extract keyword-context windows from a (single-line-ish) JS bundle."""
import sys, re, json, argparse

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--kw", nargs="*", default=[])
    ap.add_argument("--kwfile", default=None)
    ap.add_argument("--win", type=int, default=160)
    ap.add_argument("--max", type=int, default=40, help="max hits per keyword")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    with open(a.path, "r", encoding="utf-8", errors="replace") as f:
        src = f.read()

    kws = list(a.kw)
    if a.kwfile:
        with open(a.kwfile, "r", encoding="utf-8") as f:
            kws += [l.strip() for l in f if l.strip() and not l.startswith("#")]

    out = []
    for kw in kws:
        try:
            pat = re.compile(re.escape(kw) if not kw.startswith("RE:") else kw[3:], re.I)
        except re.error as e:
            out.append({"kw": kw, "error": str(e)}); continue
        hits = []
        for m in pat.finditer(src):
            s = max(0, m.start() - a.win); e = min(len(src), m.end() + a.win)
            hits.append({"pos": m.start(), "ctx": src[s:e]})
            if len(hits) >= a.max: break
        out.append({"kw": kw, "count": len(hits), "hits": hits})

    if a.json:
        print(json.dumps(out, ensure_ascii=False, indent=1))
    else:
        for blk in out:
            if "error" in blk:
                print(f"\n### {blk['kw']} -> ERROR {blk['error']}"); continue
            print(f"\n{'='*90}\n### KW: {blk['kw']}  hits={blk['count']}")
            for h in blk["hits"]:
                print(f"\n  @{h['pos']}\n  {h['ctx']}")

if __name__ == "__main__":
    main()
