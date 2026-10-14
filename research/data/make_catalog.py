#!/usr/bin/env python3
"""Write research/data/CATALOG.json from the per-file metadata lake_build.py left in LAKE_DIR/_qa.

  python -I make_catalog.py LAKE_DIR TAG DOWNLOAD_DATE
  python -I make_catalog.py --restore-qa LAKE_DIR   # files downloaded from Drive: write LAKE_DIR/_qa from CATALOG.json

--restore-qa writes _qa/<file>.json for every file in LAKE_DIR that CATALOG.json lists with the same sha256 and has
no _qa entry yet, so files that are not rebuilt keep their catalog entry (the per-symbol/per-contract QA details,
which the catalog does not keep, are lost for those files).
"""
import glob, hashlib, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def restore_qa(lake, cat):
    n = 0
    os.makedirs(f"{lake}/_qa", exist_ok=True)
    for e in cat["files"]:
        fn, qn = f"{lake}/{e['file']}", f"{lake}/_qa/{e['file']}.json"
        if not os.path.exists(fn) or os.path.exists(qn):
            continue
        h = hashlib.sha256()
        with open(fn, "rb") as fh:
            for b in iter(lambda: fh.read(1 << 20), b""):
                h.update(b)
        if h.hexdigest() != e["sha256"]:
            print("sha256 differs from CATALOG.json, skipped:", e["file"])
            continue
        m = {k: v for k, v in e.items() if k not in ("rows", "first", "last", "qa")}
        m["qa"] = dict(e["qa"], rows=e["rows"], first=e["first"], last=e["last"])
        json.dump(m, open(qn, "w"), indent=1)
        n += 1
    return n


def main():
    if sys.argv[1] == "--restore-qa":
        print(restore_qa(sys.argv[2], json.load(open(os.path.join(HERE, "CATALOG.json")))), "QA files restored")
        return
    lake, tag, day = sys.argv[1:4]
    files = []
    for f in sorted(glob.glob(f"{lake}/_qa/*.json")):
        m = json.load(open(f))
        q = m.pop("qa")
        e = {k: m[k] for k in ("file", "dataset") if k in m}
        e.update(rows=q["rows"], first=q.get("first"), last=q.get("last"), bytes=m["bytes"], sha256=m["sha256"],
                 source=m["source"], columns=m["columns"])
        e["qa"] = {k: v for k, v in q.items() if not k.startswith("per_") and k not in ("rows", "first", "last")}
        for k in ("symbol", "underlying", "expiry_flag", "expiry_code", "offsets", "year"):
            if k in m:
                e[k] = m[k]
        files.append(e)
    cat = dict(release_tag=tag, release_name=f"Data lake {day} (Dhan API v2)", repo="mevishalsonawane-ai/options-lab",
               release_published=os.environ.get("LAKE_PUBLISHED") == "1",
               download_date=day, timezone="Asia/Kolkata (IST)", format="Parquet, zstd level 9",
               release_notes="Historical index, futures, equity and expired-option data from Dhan API v2. "
                             "See research/data/README.md and research/data/VALIDATION.md.",
               total_bytes=sum(f["bytes"] for f in files), total_rows=sum(f["rows"] for f in files), files=files)
    json.dump(cat, open(os.path.join(HERE, "CATALOG.json"), "w"), indent=1)
    print(len(files), "files", f"{cat['total_bytes']/1e9:.2f} GB", f"{cat['total_rows']:,} rows")


if __name__ == "__main__":
    main()
