"""What-if LABELS (R24, 13 Oct 2026): what buying a call or put at minute t would have done. The future, so labels only.

    python -m research.brain.whatif_labels build [--und NIFTY]   -> <cache>/labels_whatif/<UND>/<YYYY>/<day>.parquet

Source: the R24 what-if table (<cache>/whatif/<UND>/<YYYY>.parquet, research/hunt/r24/whatif.py; on Drive under
IraAlgo-data-lake/derived/r24_whatif). One row per decision minute t (the bar whose close the decision is made on; the
fill is in minute t + 1 .. t + 3), keyed by `ts` = t, so `split.join_forward(features, labels)` puts the features known
at t next to what happened after t.

Columns (all "y_wi_", all per lot, net of the app's charges and the spread by moneyness):
  y_wi_<ce|pe>_<itm1|atm|otm1>_net30     net % of the fill, sold 30 minutes after the fill
  y_wi_<ce|pe>_<itm1|atm|otm1>_net1505   net % of the fill, sold at 15:05
  y_wi_<ce|pe>_<itm1|atm|otm1>_mfe       best traded high from the fill to 15:19, % of the fill
  y_wi_<ce|pe>_<itm1|atm|otm1>_mae       worst traded low, % of the fill
  y_wi_<ce|pe>_<itm1|atm|otm1>_orb_rs    net Rs under the ORB exit family (-40 / +40, the ladder, 15:10 / 15:05)
  y_wi_best_net1505                      the best net % at 15:05 among the 14 options (ATM-3 .. ATM+3, CE and PE)
  y_wi_win_share1505                     the share of those 14 (filled) with a net gain at 15:05

Never imported by research/brain/features (tests/test_whatif_labels.py and test_lookahead.py check).
"""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from . import config
from .labels import PREFIX

WI = PREFIX + "wi_"
SIDES = {1: "ce", -1: "pe"}
MNY = {1: "itm1", 0: "atm", -1: "otm1"}
FIELDS = {"net30": "net_pct_30", "net1505": "net_pct_1505", "mfe": "mfe_pct", "mae": "mae_pct", "orb_rs": "orb_net"}


def columns() -> list[str]:
    out = [f"{WI}{s}_{m}_{k}" for s in SIDES.values() for m in MNY.values() for k in FIELDS]
    return out + [f"{WI}best_net1505", f"{WI}win_share1505"]


def compact(t: pd.DataFrame) -> pd.DataFrame:
    """What-if rows (any number of sessions of one index) -> one row per decision minute with the y_wi_* columns."""
    t = t[t.filled.astype(bool)]
    if t.empty:
        return pd.DataFrame(columns=["ts"] + columns())
    key = t.ts_decision.values
    out = pd.DataFrame({"ts": np.unique(key)})
    for s, sn in SIDES.items():
        for m, mn in MNY.items():
            sub = t[(t.side == s) & (t.mny == m)].drop_duplicates("ts_decision").set_index("ts_decision")
            for k, c in FIELDS.items():
                out[f"{WI}{sn}_{mn}_{k}"] = out.ts.map(sub[c]).astype(np.float32).values
    g = t.groupby("ts_decision")
    out[f"{WI}best_net1505"] = out.ts.map(g.net_pct_1505.max()).astype(np.float32).values
    out[f"{WI}win_share1505"] = out.ts.map(g.net_rs_1505.apply(lambda x: float((x > 0).mean()))).astype(np.float32).values
    return out


def build(root: Path, und: str, whatif_dir: Path | None = None) -> int:
    src = Path(whatif_dir) if whatif_dir else root / "whatif"
    n = 0
    for p in sorted((src / und).glob("*.parquet")):
        t = pd.read_parquet(p)
        t["d"] = pd.to_datetime(t.day).dt.date
        for d, g in t.groupby("d"):
            q = root / "labels_whatif" / und / str(d.year) / f"{d}.parquet"
            q.parent.mkdir(parents=True, exist_ok=True)
            compact(g).to_parquet(q, compression="zstd", index=False)
            n += 1
    print(f"{und}: what-if labels for {n} days written")
    return n


def load(root: Path, und: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    base = root / "labels_whatif" / und
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p) for p in keep], ignore_index=True) if keep else pd.DataFrame()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.whatif_labels")
    ap.add_argument("cmd", choices=["build"])
    ap.add_argument("--und", default=None)
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    root = config.paths(a.cache).root
    for u in ([a.und] if a.und else ["NIFTY", "BANKNIFTY"]):
        build(root, u)


if __name__ == "__main__":
    sys.exit(main())
