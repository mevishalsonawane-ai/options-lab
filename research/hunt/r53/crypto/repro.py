"""R53 crypto reproduction checks against the original studies (design window only; no final-test data).

1. Signals: RC2 R8 / R4 / R6 on Delta India's 5-minute index (as RC2 ran them) vs on the Binance perp (R53's signal
   source), and F1C P07 / P13 / O19 on Binance spot (the crypto brain's close) vs on the perp: decision counts and
   the share of decisions that match (same decision bar and side).
2. RC1 idea g: R53's breakout walk (perps.brk_signals, original exits) vs rc1 strategies.breakout_spells on the same
   4-hour perp bars: the spells must be identical.
3. RC2's own quarterly design results (rc2/results/walkforward.csv, the picked exit per quarter) next to R53's
   design numbers for the same rule, exit and quarter (from results/crypto_design.json): trades and rupees before tax.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.f1c import rules as f1crules
from research.hunt.rc2 import signals as rc2sig

from .. import common as RC
from . import data, perps, rules as R

RC2_WF = Path(__file__).resolve().parents[2] / "rc2" / "results" / "walkforward.csv"


def _design(T) -> np.ndarray:
    return np.array([RC.part_of(t) == "design" for t in pd.DatetimeIndex(T)], bool)


def match(a: pd.DataFrame, b: pd.DataFrame) -> dict:
    ka = set(zip(pd.DatetimeIndex(a["T"]), a.side))
    kb = set(zip(pd.DatetimeIndex(b["T"]), b.side))
    both = len(ka & kb)
    return dict(n_a=len(ka), n_b=len(kb), same=both, share_of_a=both / len(ka) if ka else None)


def rc2_signals(bars: pd.DataFrame, dvol: pd.Series, name: str) -> pd.DataFrame:
    b = bars[["o", "h", "l", "c"]]
    e = {"R8_pdl_break": lambda: rc2sig.pdl_break(b), "R4_vol_burst": lambda: rc2sig.vol_burst(b, dvol),
         "R6_hero": lambda: rc2sig.hero(b, dvol)}[name]()
    return e[_design(e["T"])]


def main() -> dict:
    out = {"signals": {}, "rc1_g_identical": {}, "rc2_quarters": []}
    for coin in data.COINS:
        mk = R.Market(coin)
        # RC2's index bars (rc2/data.py index5m): 5-minute OHLC on a full grid, gaps carried forward
        ix = pd.read_parquet(RC.cache_dir() / f"delta_idx_{coin}_1m.parquet").set_index("t").sort_index()
        i5 = data.resample(ix[["o", "h", "l", "c"]], "5min")
        i5 = i5.reindex(pd.date_range(i5.index[0], i5.index[-1], freq="5min", name="t"))
        i5["c"] = i5.c.ffill()
        for k in ("o", "h", "l"):
            i5[k] = i5[k].fillna(i5.c)
        for name in ("R8_pdl_break", "R4_vol_burst", "R6_hero"):
            a = rc2_signals(i5, mk.dvol, name)
            b = rc2_signals(mk.b5, mk.dvol, name)
            out["signals"][f"{coin} {name} index-vs-perp"] = match(a, b)
        spot = data.to_grid(pd.read_parquet(RC.cache_dir() / f"spot_{coin}_1m.parquet"))
        ms = R.Market(coin, m1=spot, dvol=mk.dvol, oi=mk.oi, index=mk.index, store=mk.chain.store)
        for sig, fn in (("P07", f1crules.p07_prevday_break), ("P13", f1crules.p13_oi_divergence),
                        ("O19", f1crules.o19_vol_burst)):
            a, b = fn(ms.dec), fn(mk.dec)
            a = a.assign(T=R.utc(a.ts))
            b = b.assign(T=R.utc(b.ts))
            out["signals"][f"{coin} {sig} spot-vs-perp"] = match(a[_design(a["T"])], b[_design(b["T"])])
        b4 = mk.b4
        o, h, l, c = (b4[k].to_numpy(float) for k in ("o", "h", "l", "c"))
        ours = perps.brk_signals(o, h, l, c, perps.BRK_EXITS["orig"])
        theirs = perps.rc1s.breakout_spells(o, h, l, c)
        k = min(len(ours), len(theirs)) - 1          # the last spell may still be open at the data end
        same = [(e, x) for e, x, _, _ in ours[:k]] == [(e, x) for e, x, _ in theirs[:k]]
        px = all(abs(p - f) < 1e-9 for (_, _, p, w), (_, _, f) in zip(ours[:k], theirs[:k]) if w == "stop")
        out["rc1_g_identical"][coin] = dict(spells_ours=len(ours), spells_rc1=len(theirs), same_bars=bool(same),
                                            same_stop_fills=bool(px))
    dz = RC.RESULTS / "crypto_design.json"
    if dz.exists() and RC2_WF.exists():
        d = json.loads(dz.read_text())
        wf = pd.read_csv(RC2_WF)
        rid_of = {"R8_pdl_break": "CC1", "R4_vol_burst": "CC2", "R6_hero": "CC3"}
        for _, r in wf.iterrows():
            if r.scope not in rid_of:
                continue
            q = pd.Period(pd.Timestamp(r.quarter), "Q")
            if not (pd.Period("2025Q1") <= q <= pd.Period("2026Q1")):
                continue
            rid = rid_of[r.scope]
            ex = r.pick.split("/")[1]
            ours = d["rules"][rid]["variants"].get(ex, {}).get("quarters_delta_pre_tax", {}).get(str(q))
            out["rc2_quarters"].append(dict(rule=rid, quarter=str(q), exit=ex, rc2_trades=int(r.trades),
                                            rc2_inr=float(r.inr), r53_trades=(ours or {}).get("trades"),
                                            r53_inr=(ours or {}).get("net")))
    RC.save("crypto_repro.json", out)
    print(json.dumps(out, indent=1, default=str))
    return out
