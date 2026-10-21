"""One command that (re)builds the whole India Data Brain: data -> L3 cube -> L4 features -> labels -> L5 models.

    python -m research.brain.refresh --source lake            # sync the Dhan lake from Drive, then rebuild everything
    python -m research.brain.refresh --source lake --upload --pack   # + model files to Drive, + knowledge pack
    python -m research.brain.refresh --source release --und NIFTY BANKNIFTY   # the research-data release only
    python -m research.brain.refresh --no-sync --no-models    # rebuild from what is already in the cache

Steps (each is incremental: a day is redone only when its source or the code changed):
  1. sync     rclone copy of only the datasets the brain reads from gdrive:IraAlgo-data-lake (index_1m,
              options_rolling_1m *_WEEK_*, fut_1m_live_index, CATALOG.json) into $IRAALGO_LAKE, plus the research-data
              release (gh). Files are read later one month at a time, so memory stays small.
  2. cube     cube.build per index (lake first where it has the day with options, then the release, then the lake's
              index-only days)
  3. tables   L3 levels + profiles (skip with --no-tables)
  4. features L4 feature store
  5. labels   design days only (never the embargo or the locked holdout, holdout.py)
  6. coverage research/brain/COVERAGE.md + coverage.json: what is covered and what is missing
  7. models   L5 walk-forward + metrics_v1.json + model files (--upload: to Drive)
  8. pack     research/knowledge/build_pack.py (with --pack)
When the full lake lands (BANKNIFTY options, ...), the same command picks it up: no code change.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import date
from pathlib import Path

from . import config, cube, holdout, labels

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
COVERAGE_MD = HERE / "COVERAGE.md"
COVERAGE_JSON = HERE / "coverage.json"
INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX")
DRIVE = "gdrive:IraAlgo-data-lake"
SYNC_INCLUDE = ["CATALOG.json", "index_1m/**", "options_rolling_1m/opt_*_WEEK_*.parquet", "fut_1m_live_index/**"]
# Datasets on Drive the brain does not read yet (reported, not downloaded): a separate grid or no minute history.
NOT_USED = {
    "fut_1m_live_mcx": "MCX futures, 1-minute, live contracts only (mid-2026 on); the cube is the NSE 09:15-15:29 grid",
    "options_rolling_1m (opt_*_MONTH_*, MCX)": "monthly-expiry and MCX option series (the cube uses the nearest weekly)",
    "fut_daily_continuous": "daily continuous futures (no minutes)",
    "index_daily": "daily index OHLC (the cube is built from the minutes)",
    "equity_daily": "daily constituents; breadth needs constituent MINUTES (equity_1m), not on Drive yet",
    "nse_bhavcopy": "end-of-day NSE F&O sample (validation; R16 max pain)",
}


def sync(lake: Path, release_dir: Path, unds, lake_too: bool = True) -> dict:
    ok = None
    if lake_too:
        lake.mkdir(parents=True, exist_ok=True)
        cmd = ["rclone", "copy", DRIVE, str(lake), "--checksum", "--transfers", "4", "-q"]
        for inc in SYNC_INCLUDE:
            cmd += ["--include", inc]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True)
            ok = r.returncode == 0
            err = r.stderr.strip()[-300:]
        except FileNotFoundError:
            ok, err = False, "rclone not installed"
        print("lake sync from Drive:", "ok" if ok else f"failed ({err}); building from the cached lake")
    from .adapters.release import LAYOUT, fetch, files_for
    for u in unds:
        if u in LAYOUT:
            try:
                fetch(files_for(u), release_dir)
            except Exception as e:                    # no gh / no network: build from what is cached
                print(f"release fetch for {u} skipped: {e}")
    return dict(lake_sync=ok)


def coverage(root: Path, unds, lake: Path) -> dict:
    """Per index: cube days, span, sources, chain / VIX / futures days, and design / embargo / holdout sessions."""
    import pandas as pd
    out = {}
    for u in unds:
        days = cube.built_days(root, u)
        if not days:
            out[u] = dict(days=0)
            continue
        src, chain, vix, fut = {}, 0, 0, 0
        for d in days:
            man = cube.manifest(root, u, d) or {}
            s = man.get("sources", {})
            k = s.get("index", "").split(":")[0] or "?"
            src[k] = src.get(k, 0) + 1
            chain += man.get("chain", {}).get("flags", {}).get("ok", 0) > 0
            vix += man.get("vix", {}).get("flags", {}).get("ok", 0) > 0
            fut += man.get("fut", {}).get("flags", {}).get("ok", 0) > 0
        fdays = sorted(date.fromisoformat(p.stem) for p in (root / "features" / u).glob("*/*.parquet"))
        ldays = sorted(date.fromisoformat(p.stem) for p in (root / "labels" / u).glob("*/*.parquet"))
        cd = [d for d in days if d <= holdout.DESIGN_END]
        out[u] = dict(days=len(days), first=str(days[0]), last=str(days[-1]), sources=src, chain_days=int(chain),
                      vix_days=int(vix), futures_days=int(fut), feature_days=len(fdays), label_days=len(ldays),
                      label_last=str(ldays[-1]) if ldays else None, design_days=len(cd),
                      embargo_days=sum(holdout.DESIGN_END < d <= holdout.EMBARGO_END for d in days),
                      holdout_days=sum(d >= holdout.HOLDOUT_START for d in days),
                      weekday_gaps=len(_gaps(days)))
    lake_files = sorted(p.name for p in lake.rglob("*.parquet")) if lake.exists() else []
    return dict(built_from=max((v.get("last", "") for v in out.values()), default=""), indices=out,
                lake_files=lake_files, not_used=NOT_USED, holdout=dict(design_end=str(holdout.DESIGN_END),
                                                                       holdout_start=str(holdout.HOLDOUT_START)))


def _gaps(days):
    from .calendar import holidays_inferred
    return holidays_inferred(days) if days else []


def coverage_md(c: dict) -> str:
    L = ["# India Data Brain: coverage", "",
         "Generated by `python -m research.brain.refresh` (do not edit). What the L3 cube, L4 features and labels cover",
         f"per index, from the cache built on the data available on {c['built_from']}. Labels exist for design days only "
         f"(up to {c['holdout']['design_end']}); the locked holdout starts {c['holdout']['holdout_start']}.", "",
         "| index | cube days | first | last | sources (days) | with option chain | with VIX | with futures | "
         "features | labels | design / embargo / holdout days | weekdays without a session |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for u, v in c["indices"].items():
        if not v.get("days"):
            L.append(f"| {u} | 0 | | | | | | | | | | |")
            continue
        s = ", ".join(f"{k} {n}" for k, n in sorted(v["sources"].items()))
        L.append(f"| {u} | {v['days']} | {v['first']} | {v['last']} | {s} | {v['chain_days']} | {v['vix_days']} | "
                 f"{v['futures_days']} | {v['feature_days']} | {v['label_days']} | {v['design_days']} / "
                 f"{v['embargo_days']} / {v['holdout_days']} | {v['weekday_gaps']} |")
    L += ["", "Weekdays without a session are exchange holidays OR missing data (see research/data/VALIDATION.md).", "",
          "## On Drive but not used by the brain yet", ""]
    L += [f"- `{k}`: {v}" for k, v in c["not_used"].items()]
    L += ["", f"Lake files read by the brain ({len(c['lake_files'])}): " + ", ".join(f"`{f}`" for f in c["lake_files"])
          if c["lake_files"] else "", ""]
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.refresh")
    ap.add_argument("--source", default="lake", choices=["lake", "release", "auto"],
                    help="lake: sync the Drive lake, build lake-first with the release for the rest (= auto); "
                         "release: the research-data release only")
    ap.add_argument("--und", nargs="*", default=list(INDICES))
    ap.add_argument("--cache", default=None)
    ap.add_argument("--no-sync", action="store_true")
    ap.add_argument("--no-tables", action="store_true")
    ap.add_argument("--no-models", action="store_true")
    ap.add_argument("--upload", action="store_true", help="copy the model files to Drive")
    ap.add_argument("--pack", action="store_true", help="rebuild the knowledge pack at the end")
    a = ap.parse_args(argv)
    p = config.paths(a.cache)
    t0 = time.time()
    if not a.no_sync:
        sync(p.lake, p.raw_release, a.und, lake_too=a.source != "release")
    build_src = "release" if a.source == "release" else "auto"
    from .features import load_all
    from .features.store import build as fbuild
    load_all()
    lo, hi = date(2000, 1, 1), date(2099, 12, 31)
    for u in a.und:
        print(f"== {u}")
        cube.build(p.root, u, lo, hi, build_src)
        if not cube.built_days(p.root, u):
            continue
        if not a.no_tables:
            from .tables import TABLES, build as tbuild
            tbuild(p.root, u, lo, hi, list(TABLES))
        fbuild(p.root, u, lo, hi)
        labels.build(p.root, u, lo, hi)
    c = coverage(p.root, a.und, p.lake)
    COVERAGE_JSON.write_text(json.dumps(c, indent=1) + "\n")
    COVERAGE_MD.write_text(coverage_md(c))
    print(f"wrote {COVERAGE_MD.relative_to(ROOT)}")
    if not a.no_models:
        from .models.train import run
        run(p.root, [u for u in a.und if cube.built_days(p.root, u)], upload=a.upload)
    if a.pack:
        subprocess.run([sys.executable, str(ROOT / "research/knowledge/build_pack.py")], check=True)
    print(f"refresh done in {time.time() - t0:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
