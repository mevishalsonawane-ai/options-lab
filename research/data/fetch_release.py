#!/usr/bin/env python3
"""Download data-lake Parquet files from a GitHub Release and verify their sha256 against CATALOG.json.

Usage:
  python fetch_release.py                       # everything in CATALOG.json into ./lake
  python fetch_release.py --match opt_NIFTY_WEEK --dest /data/lake
  python fetch_release.py --list

Works for public repos without a token; for a private repo set GH_TOKEN (or GITHUB_TOKEN).
Files already present with the right sha256 are skipped.
"""
import argparse, hashlib, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = "mevishalsonawane-ai/options-lab"


def sha256(fn):
    h = hashlib.sha256()
    with open(fn, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def gh(url, accept="application/vnd.github+json"):
    req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "fetch_release"})
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if tok:
        req.add_header("Authorization", f"Bearer {tok}")
    return urllib.request.urlopen(req, timeout=300)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default=os.path.join(HERE, "CATALOG.json"))
    ap.add_argument("--dest", default="lake")
    ap.add_argument("--match", action="append", default=[], help="substring filter on file name (repeatable)")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    cat = json.load(open(a.catalog))
    files = [f for f in cat["files"] if not a.match or any(m in f["file"] for m in a.match)]
    if a.list:
        for f in files:
            print(f"{f['file']:45s} {f['bytes']/1e6:9.1f} MB {f['rows']:>12,} rows  {f.get('first','')} .. {f.get('last','')}")
        print(f"{len(files)} files, {sum(f['bytes'] for f in files)/1e9:.2f} GB")
        return
    tag = cat["release_tag"]
    rel = json.load(gh(f"https://api.github.com/repos/{REPO}/releases/tags/{tag}"))
    assets = {x["name"]: x for x in rel["assets"]}
    os.makedirs(a.dest, exist_ok=True)
    bad = 0
    for f in files:
        fn = os.path.join(a.dest, f["file"])
        if os.path.exists(fn) and sha256(fn) == f["sha256"]:
            print("ok (cached)", f["file"])
            continue
        if f["file"] not in assets:
            print("MISSING in release", f["file"]); bad += 1
            continue
        with gh(assets[f["file"]]["url"], "application/octet-stream") as r, open(fn + ".part", "wb") as o:
            while b := r.read(1 << 20):
                o.write(b)
        if sha256(fn + ".part") != f["sha256"]:
            print("SHA256 MISMATCH", f["file"]); bad += 1
            os.remove(fn + ".part")
            continue
        os.replace(fn + ".part", fn)
        print("ok", f["file"])
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
