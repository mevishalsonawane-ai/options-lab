#!/usr/bin/env python3
"""Write research/data/CATALOG.json from the per-file metadata lake_build.py left in LAKE_DIR/_qa.

  python -I make_catalog.py LAKE_DIR TAG DOWNLOAD_DATE
"""
import glob, json, os, sys

lake, tag, day = sys.argv[1:4]
HERE = os.path.dirname(os.path.abspath(__file__))
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
