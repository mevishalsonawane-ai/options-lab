"""L3 tables beside the per-minute cube (ARCHITECTURE "Changes the data dictionary forces", item 3).

    python -m research.brain.tables build [--what profiles,levels] [--from --to --und --cache --force]
    python -m research.brain.tables info  [--und]

  <cache>/profiles/<UND>/<YYYY>/<day>.parquet   profile.table(): "day" (final, valid from the close), "week" (week to
                                                date through the close), "dev" (developing, one row per minute)
  <cache>/levels/<UND>/<YYYY>/<day>.parquet     levels.table(): one row per level, with valid_from and held/broke
                                                counts from earlier sessions only

Both are recomputed from the cube with a formula version, never stored raw (item 8). Like the feature store, a day is
rebuilt only when its formula version or a cube day it reads (the day + the table's warm-up sessions) changed. Only
the minutes are read: the max-OI strikes and the ATM IV are in the minutes table.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import date
from pathlib import Path

import pandas as pd

from . import config, cube, levels, profile
from .features.registry import Ctx
from .features.store import _dep_hash

TABLES = {
    "profiles": dict(fn=profile.table, version=profile.FORMULA_VERSION, warmup=5),
    "levels": dict(fn=levels.table, version=levels.FORMULA_VERSION, warmup=levels.LOOKBACK_SESSIONS),
}


def _paths(root: Path, what: str, und: str, d: date):
    base = root / what / und / str(d.year)
    return base / f"{d}.parquet", base / f"{d}.json"


def build(root: Path, und: str, frm: date, to: date, what=tuple(TABLES), force: bool = False,
          verbose: bool = True) -> dict:
    days = cube.built_days(root, und)
    pos = {d: i for i, d in enumerate(days)}
    res = {}
    for w in what:
        spec = TABLES[w]
        W, sig = spec["warmup"], f"{w}:v{spec['version']}:cube{config.CUBE_VERSION}"
        todo = []
        for d in days:
            if not (frm <= d <= to):
                continue
            dh = _dep_hash(root, und, days[max(0, pos[d] - W):pos[d] + 1])
            pp, pj = _paths(root, w, und, d)
            if not force and pj.exists() and pp.exists():
                j = json.loads(pj.read_text())
                if j.get("signature") == sig and j.get("deps") == dh:
                    continue
            todo.append((d, dh))
        t0, n = time.time(), 0
        by_month: dict = {}
        for d, dh in todo:
            by_month.setdefault((d.year, d.month), []).append((d, dh))
        for _, items in sorted(by_month.items()):
            first = items[0][0]
            M, _ = cube.load(root, und, days[max(0, pos[first] - W)], items[-1][0], chain=False)
            T = spec["fn"](Ctx(M, None, und), days_from=first)
            for d, dh in items:
                pp, pj = _paths(root, w, und, d)
                pp.parent.mkdir(parents=True, exist_ok=True)
                t = T[T.day.dt.date == d].reset_index(drop=True)
                t.to_parquet(pp, compression="zstd", index=False)
                pj.write_text(json.dumps(dict(signature=sig, deps=dh, rows=len(t))))
                n += 1
        res[w] = dict(built=n, seconds=round(time.time() - t0, 1))
        if verbose:
            print(f"{und} {w}: {n} days written, {len(days) - n} up to date or out of range ({res[w]['seconds']}s)")
    return res


def load(root: Path, und: str, what: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    base = root / what / und
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p) for p in keep], ignore_index=True) if keep else pd.DataFrame()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.tables")
    ap.add_argument("cmd", choices=["build", "info"])
    ap.add_argument("--what", default=",".join(TABLES))
    ap.add_argument("--from", dest="frm", default="2000-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--und", default="BANKNIFTY")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args(argv)
    root = config.paths(a.cache).root
    what = [w for w in a.what.split(",") if w]
    if a.cmd == "build":
        build(root, a.und, date.fromisoformat(a.frm), date.fromisoformat(a.to), what, a.force)
    else:
        for w in what:
            t = load(root, a.und, w)
            if t.empty:
                print(f"{w}: nothing built")
                continue
            print(f"{w}: {t.day.nunique()} days, {len(t)} rows; " + ", ".join(
                f"{k} {v}" for k, v in t[("kind" if w == "profiles" else "type")].value_counts().items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
