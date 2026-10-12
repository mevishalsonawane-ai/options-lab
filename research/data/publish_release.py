#!/usr/bin/env python3
"""Create the GitHub Release named in CATALOG.json and upload every catalogued Parquet file as an asset.

Run where releases may be created (your own machine or a GitHub Actions job), not from a Claude
Code cloud session (those get HTTP 403 on release creation).

  GH_TOKEN=... python publish_release.py LAKE_DIR [--catalog CATALOG.json]

Checks each file's sha256 against the catalog before uploading; skips assets already uploaded
with the same size. Needs a token with contents:write on the repo.
"""
import argparse, hashlib, json, os, sys, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = "mevishalsonawane-ai/options-lab"


def call(method, url, data=None, ctype="application/json"):
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not tok:
        sys.exit("set GH_TOKEN")
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json",
        "Content-Type": ctype, "User-Agent": "publish_release"})
    try:
        with urllib.request.urlopen(req, timeout=3600) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def sha256(fn):
    h = hashlib.sha256()
    with open(fn, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lake")
    ap.add_argument("--catalog", default=os.path.join(HERE, "CATALOG.json"))
    a = ap.parse_args()
    cat = json.load(open(a.catalog))
    tag = cat["release_tag"]
    st, rel = call("GET", f"https://api.github.com/repos/{REPO}/releases/tags/{tag}")
    if st == 404:
        body = json.dumps({"tag_name": tag, "name": cat.get("release_name", tag),
                           "body": cat.get("release_notes", "See research/data/README.md")}).encode()
        st, rel = call("POST", f"https://api.github.com/repos/{REPO}/releases", body)
        if st != 201:
            sys.exit(f"create release failed: HTTP {st} {rel.get('message')}")
    have = {x["name"]: x["size"] for x in rel.get("assets", [])}
    up = rel["upload_url"].split("{")[0]
    for f in cat["files"]:
        fn = os.path.join(a.lake, f["file"])
        if have.get(f["file"]) == f["bytes"]:
            print("already uploaded", f["file"]); continue
        if sha256(fn) != f["sha256"]:
            sys.exit(f"sha256 mismatch for {fn}; not uploading")
        st, r = call("POST", f"{up}?name={urllib.parse.quote(f['file'])}", open(fn, "rb").read(), "application/octet-stream")
        print("uploaded" if st == 201 else f"FAILED HTTP {st} {r.get('message')}", f["file"], flush=True)
    print(rel.get("html_url"))


if __name__ == "__main__":
    main()
