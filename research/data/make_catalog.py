#!/usr/bin/env python3
"""Write research/data/CATALOG.json from the per-file metadata lake_build.py left in LAKE_DIR/_qa.

  python -I make_catalog.py LAKE_DIR TAG DOWNLOAD_DATE
  python -I make_catalog.py --restore-qa LAKE_DIR   # files downloaded from Drive: write LAKE_DIR/_qa from CATALOG.json
  python -I make_catalog.py --annotate [LAKE_DIR]   # update CATALOG.json in place: same_contract_as notes, and
                                                    # qa.spikes for the option files in LAKE_DIR (sha256 must match)

--restore-qa writes _qa/<file>.json for every file in LAKE_DIR that CATALOG.json lists with the same sha256 and has
no _qa entry yet, so files that are not rebuilt keep their catalog entry (the per-symbol/per-contract QA details,
which the catalog does not keep, are lost for those files).

Option files get `qa.spikes` (one-minute bad-tick spikes, defined in spikes.py) when the file is in LAKE_DIR, and
byte-identical WEEK/MONTH pairs get `same_contract_as` + `same_contract_reason` (found by sha256).
--annotate lets this run file by file: download one option file, annotate, delete it, next.
"""
import glob, hashlib, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import spikes


def sha256(fn):
    h = hashlib.sha256()
    with open(fn, "rb") as fh:
        for b in iter(lambda: fh.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def annotate(files, lake=None):
    """Add same_contract_as notes to every entry, and qa.spikes to option entries whose file is in lake."""
    pairs = spikes.same_contract_pairs(files)
    n = 0
    for e in files:
        e.pop("same_contract_as", None); e.pop("same_contract_reason", None)
        if e["file"] in pairs:
            e["same_contract_as"], e["same_contract_reason"] = pairs[e["file"]]
        fn = f"{lake}/{e['file']}" if lake else None
        if fn and spikes.book(e["file"]) and os.path.exists(fn):
            if sha256(fn) != e["sha256"]:
                print("sha256 differs from CATALOG.json, spikes not computed:", e["file"])
                continue
            e.setdefault("qa", {})["spikes"] = spikes.file_spikes(fn)
            n += 1
    return n


def restore_qa(lake, cat):
    n = 0
    os.makedirs(f"{lake}/_qa", exist_ok=True)
    for e in cat["files"]:
        fn, qn = f"{lake}/{e['file']}", f"{lake}/_qa/{e['file']}.json"
        if not os.path.exists(fn) or os.path.exists(qn):
            continue
        if sha256(fn) != e["sha256"]:
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
    if sys.argv[1] == "--annotate":
        p = os.path.join(HERE, "CATALOG.json")
        cat = json.load(open(p))
        n = annotate(cat["files"], sys.argv[2] if len(sys.argv) > 2 else None)
        json.dump(cat, open(p, "w"), indent=1)
        print(n, "option files got spike counts;", sum("same_contract_as" in e for e in cat["files"]), "files are in a same-contract pair")
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
    annotate(files, lake)
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
