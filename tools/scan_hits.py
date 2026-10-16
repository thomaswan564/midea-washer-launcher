#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""扫描目录内所有 .js，列出每个关键词命中的文件与次数。"""
import os, sys, argparse

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--kw", action="append", default=[])
    ap.add_argument("--kwfile")
    ap.add_argument("--count", action="store_true")
    a = ap.parse_args()

    kws = list(a.kw)
    if a.kwfile:
        with open(a.kwfile, encoding="utf-8") as f:
            kws += [l.strip() for l in f if l.strip() and not l.startswith("#")]

    for dp, dn, fn in os.walk(a.root):
        for f in sorted(fn):
            if not f.lower().endswith((".js", ".json", ".html", ".css")):
                continue
            p = os.path.join(dp, f)
            try:
                t = open(p, encoding="utf-8", errors="ignore").read()
            except Exception:
                continue
            hits = []
            for k in kws:
                c = t.count(k)
                if c:
                    hits.append(f"{k}={c}" if a.count else k)
            if hits:
                rel = os.path.relpath(p, a.root)
                print(f"{rel:<28} {len(t):>9}  {' '.join(hits)}")

if __name__ == "__main__":
    main()
