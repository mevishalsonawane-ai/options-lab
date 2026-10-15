#!/usr/bin/env python3
"""Upload built data-lake files to Google Drive with rclone, one dataset folder at a time, and verify them.

  python -I publish_gdrive.py LAKE_DIR [--remote gdrive:IraAlgo-data-lake] [--dataset index_1m ...]
                              [--meta CATALOG.json VALIDATION.md ...] [--extra nse_bhavcopy=/path/bhav] [--dry-run]

Layout on Drive: REMOTE/<dataset>/<file>.parquet, where <dataset> comes from LAKE_DIR/_qa/<file>.json
(written by lake_build.py). `--extra NAME=DIR` uploads a folder as it is (for example bhav_fetch.py output)
to REMOTE/NAME/. REMOTE/MANIFEST.txt lists dataset/file, rows ("-" for --extra files), sha256 and bytes of
every built file in LAKE_DIR and every --extra file (also those not selected with --dataset), and the --meta files are copied to the folder root. Each dataset is copied with
`rclone copy --checksum` and then checked with `rclone check --one-way`, so a failure later on leaves the
datasets already uploaded intact. Re-running skips files Drive already has. The rclone remote is
configured outside this script (for example RCLONE_CONFIG_GDRIVE_* environment variables).
"""
import argparse, glob, hashlib, json, os, subprocess, sys, tempfile

REMOTE = "gdrive:IraAlgo-data-lake"


def lake_files(lake):
    """{dataset: [entry, ...]} for every file lake_build.py wrote, where entry has file, rows, sha256, bytes."""
    out = {}
    for f in sorted(glob.glob(os.path.join(lake, "_qa", "*.json"))):
        m = json.load(open(f))
        if not os.path.exists(os.path.join(lake, m["file"])):
            continue
        out.setdefault(m["dataset"], []).append(
            dict(file=m["file"], rows=m["qa"]["rows"], sha256=m["sha256"], bytes=m["bytes"]))
    return out


def dir_files(d):
    """Entries for every file under d (paths relative to d), for folders uploaded as they are."""
    out = []
    for root, _, fs in os.walk(d):
        for f in sorted(fs):
            fn = os.path.join(root, f)
            h = hashlib.sha256()
            with open(fn, "rb") as fh:
                for b in iter(lambda: fh.read(1 << 20), b""):
                    h.update(b)
            out.append(dict(file=os.path.relpath(fn, d), rows="-", sha256=h.hexdigest(), bytes=os.path.getsize(fn)))
    return sorted(out, key=lambda e: e["file"])


def manifest(groups):
    lines = ["# path\trows\tsha256\tbytes"]
    for ds in sorted(groups):
        for e in groups[ds]:
            lines.append(f"{ds}/{e['file']}\t{e['rows']}\t{e['sha256']}\t{e['bytes']}")
    return "\n".join(lines) + "\n"


def rclone(args, run=subprocess.run):
    cmd = ["rclone", *args]
    print("$", " ".join(cmd), flush=True)
    return run(cmd).returncode


def upload_dataset(lake, remote, ds, entries, run=subprocess.run, dry=False):
    """Copy one dataset's files to remote/ds and verify them; returns True when the check passes."""
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as t:
        t.write("".join(e["file"] + "\n" for e in entries))
    try:
        dst, sel = f"{remote}/{ds}", ["--files-from", t.name]
        if dry:
            return rclone(["copy", lake, dst, *sel, "--checksum", "--dry-run"], run) == 0
        if rclone(["copy", lake, dst, *sel, "--checksum", "--transfers", "4", "--retries", "5"], run) != 0:
            return False
        return rclone(["check", lake, dst, *sel, "--one-way"], run) == 0
    finally:
        os.remove(t.name)


def main(argv=None, run=subprocess.run):
    ap = argparse.ArgumentParser()
    ap.add_argument("lake")
    ap.add_argument("--remote", default=REMOTE)
    ap.add_argument("--dataset", action="append", default=[], help="only these datasets (repeatable)")
    ap.add_argument("--meta", nargs="*", default=[], help="files copied to the folder root, e.g. CATALOG.json")
    ap.add_argument("--extra", action="append", default=[], help="NAME=DIR: upload DIR as it is to REMOTE/NAME")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    groups = lake_files(a.lake)
    extra = dict(x.split("=", 1) for x in a.extra)
    for name, d in extra.items():
        groups[name] = dir_files(d)
    unknown = set(a.dataset) - set(groups)
    if unknown:
        sys.exit(f"no built files for dataset(s): {', '.join(sorted(unknown))}")
    failed = [ds for ds in sorted(a.dataset or groups)
              if not upload_dataset(extra.get(ds, a.lake), a.remote, ds, groups[ds], run, a.dry_run)]
    man = os.path.join(a.lake, "MANIFEST.txt")
    open(man, "w").write(manifest(groups))
    for f in [man, *a.meta]:
        if rclone(["copyto", f, f"{a.remote}/{os.path.basename(f)}", "--checksum"]
                  + (["--dry-run"] if a.dry_run else []), run) != 0:
            failed.append(os.path.basename(f))
    n = sum(len(v) for v in groups.values())
    print(f"{n} files in {len(groups)} datasets; failed: {', '.join(failed) or 'none'}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
