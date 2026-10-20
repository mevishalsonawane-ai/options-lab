"""R87B Part B3: candles as an EXIT for two breakout arms (PREREG_B section 4). Paper research only; an exit overlay can
only close a position earlier (risk-lowering), never add or resize one.

Arms (reconstructed from the index's own 5-minute candles; labelled "reconstructed", not the app's exact arms):
  DON  Donchian-20: a 5m close above the previous 20 candles' high -> long (1-ITM call); below their low -> short
       (1-ITM put). Base exits: premium stop 15%, lock (+20% -> +10%, trail 10%), out after 30 minutes, square-off.
  ORB  opening range 09:15-09:29: the first 5m close above its high (from 09:30) -> long; below its low -> short;
       one each side a day. Base exits as DON but out after 60 minutes.
  Entries 09:30-14:30; one open trade per arm and index; DON at most 2 entries a side a day.
Overlays (exit at the next minute's open after a 5m candle that closes AGAINST the position, after the entry):
  X1 big wick against (long: big upper wick; short: big lower wick)
  X2 marubozu against (long: red marubozu; short: green marubozu)
  X3 engulfing against (long: bearish engulfing; short: bullish engulfing)
The same entries are re-exited (an overlay never changes which trades are taken). Pricing: Market('hybrid').
Test: paired difference per trade (overlay - base), day-clustered z, BH over the 6 arm x overlay pairs.

    python -m research.hunt.r87_candle_anatomy.exits design
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import build as B
from . import cfg
from . import rules as R
from . import sim as S
from . import stats as ST
from .batlas import RES

ARMS = {"DON": 30, "ORB": 60}
OVERLAYS = {"X1": ("big_upper_wick", "big_lower_wick"), "X2": ("marubozu_red", "marubozu_green"),
            "X3": ("bear_engulfing", "bull_engulfing")}
STOP = 0.15
NO_STOP = 1e12


def arm_signals(k: pd.DataFrame, arm: str) -> pd.DataFrame:
    """k: 5m candle table (continuous series). Returns day, sig_row, long."""
    k = k.reset_index(drop=True)
    sig = (k.tl.dt.hour * 60 + k.tl.dt.minute - cfg.OPEN_MIN).to_numpy()
    ok = (sig + 1 >= R.ENTRY_FIRST) & (sig + 1 <= R.ENTRY_LAST)
    c = k.c.to_numpy(float)
    if arm == "DON":
        hh = k.h.shift(1).rolling(20, min_periods=20).max().to_numpy()
        ll = k.l.shift(1).rolling(20, min_periods=20).min().to_numpy()
        up, dn = c > hh, c < ll
    else:
        up = (k.slot.to_numpy() >= 15) & (c > k.orh.to_numpy())
        dn = (k.slot.to_numpy() >= 15) & (c < k.orl.to_numpy())
        first = pd.DataFrame(dict(d=k.day, up=up & ok, dn=dn & ok))
        up = up & ok & (first.groupby("d").up.cumsum().to_numpy() == 1)
        dn = dn & ok & (first.groupby("d").dn.cumsum().to_numpy() == 1)
    rows = []
    for i in np.flatnonzero(ok & (up | dn)):
        rows.append((k.day.iloc[i].date(), int(sig[i]), bool(up[i])))
    return pd.DataFrame(rows, columns=["day", "sig_row", "long"])


def trigger_rows(k: pd.DataFrame) -> dict:
    """day -> {flag: sorted array of exit rows (candle's last minute + 1)} for the overlay flags."""
    out = {}
    sig = (k.tl.dt.hour * 60 + k.tl.dt.minute - cfg.OPEN_MIN).to_numpy() + 1
    flags = sorted({f for pair in OVERLAYS.values() for f in pair})
    for d, idx in k.groupby(k.day.dt.date).indices.items():
        out[d] = {f: np.sort(sig[idx][k[f].to_numpy(bool)[idx]]) for f in flags}
    return out


def first_after(rows: np.ndarray, er: int) -> int:
    j = np.searchsorted(rows, er + 1)                 # a candle that closed at or after the entry minute
    return int(rows[j]) if j < len(rows) else -1


def run_arm(und: str, arm: str, mk: R.Market, k5: pd.DataFrame, frm: date, to: date) -> pd.DataFrame:
    sig = arm_signals(k5, arm)
    sig = sig[(sig.day >= frm) & (sig.day <= to)]
    trig = trigger_rows(k5[(k5.day.dt.date >= frm) & (k5.day.dt.date <= to)])
    hold = ARMS[arm]
    have = set(mk.ogrp) & set(mk.mgrp)
    busy, nside, out = (None, -1), {}, []
    for s in sig.itertuples(index=False):
        if s.day not in have or (busy[0] == s.day and s.sig_row + 1 <= busy[1]):
            continue
        key = (s.day, s.long)
        if arm == "DON" and nside.get(key, 0) >= 2:
            continue
        c, atm, con, ex = mk.day(s.day)
        stop_u = -NO_STOP if s.long else NO_STOP
        base = S.price_trade(und, s.day, con, c, atm, s.sig_row, s.long, stop_u, hold, STOP, ex)
        if base is None:
            continue
        busy = (s.day, base["xr"])
        nside[key] = nside.get(key, 0) + 1
        row = dict(und=und, arm=arm, day=s.day, long=s.long, er=base["er"], base=base["net"], base_why=base["why"],
                   real_entry=base.get("real_entry"))
        for x, (fl, fs) in OVERLAYS.items():
            fx = first_after(trig.get(s.day, {}).get(fl if s.long else fs, np.zeros(0, int)), base["er"])
            if fx < 0 or fx >= base["xr"]:
                row[x], row[x + "_hit"] = base["net"], False
                continue
            t = S.price_trade(und, s.day, con, c, atm, s.sig_row, s.long, stop_u, hold, STOP, ex, fx=fx)
            row[x], row[x + "_hit"] = (t["net"] if t else base["net"]), bool(t and t["why"] == "candle")
        out.append(row)
    return pd.DataFrame(out)


def risk(net: pd.Series, day: pd.Series) -> dict:
    d = net.groupby(day).sum()
    eq = d.cumsum()
    return dict(net=float(net.sum()), worst_day=float(d.min()), max_dd=float((eq.cummax().clip(lower=0) - eq).max()))


def evaluate(tr: pd.DataFrame, folds) -> list:
    res = []
    for arm in ARMS:
        a = tr[tr.arm == arm]
        if a.empty:
            continue
        rb = risk(a.base, a.day)
        for x in OVERLAYS:
            dlt = (a[x] - a.base).to_numpy()
            m, se, g = ST.clustered(dlt, (a.und + "|" + a.day.astype(str)).to_numpy())
            z = m / se if se and se > 0 else np.nan
            fold = []
            for f0, f1 in folds:
                s = a[(a.day >= f0) & (a.day <= f1)]
                fold.append(float((s[x] - s.base).sum()))
            rx = risk(a[x], a.day)
            res.append(dict(arm=arm, overlay=x, trades=int(len(a)), hits=int(a[x + "_hit"].sum()), base=rb, variant=rx,
                            delta=float(dlt.sum()), delta_mean=m, z=z, p=ST.pval(z), folds=fold,
                            real_entry_share=float(a.real_entry.mean()) if a.real_entry.notna().any() else None))
    q = ST.bh([r["p"] for r in res])
    for r, qq in zip(res, q):
        r["q"] = float(qq)
        r["pass"] = bool(r["delta"] > 0 and qq < cfg.Q and sum(f > 0 for f in r["folds"]) >= 2
                         and r["variant"]["worst_day"] >= r["base"]["worst_day"]
                         and r["variant"]["max_dd"] <= r["base"]["max_dd"])
    return res


def collect(frm: date, to: date) -> pd.DataFrame:
    parts = []
    for u in cfg.UNDS:
        mk = R.Market(u, "hybrid")
        k5 = B.load(u, 5)
        for arm in ARMS:
            parts.append(run_arm(u, arm, mk, k5, frm, to))
    return pd.concat(parts, ignore_index=True)


def design() -> None:
    tr = collect(cfg.RULE_START, cfg.DESIGN_END)
    RES.mkdir(parents=True, exist_ok=True)
    tr.to_csv(RES / "exits_design_trades.csv", index=False, float_format="%.4f")
    res = evaluate(tr, cfg.RULE_FOLDS)
    (RES / "exits_design.json").write_text(json.dumps(res, indent=1, default=str))
    for r in res:
        print(r["arm"], r["overlay"], r["trades"], r["hits"], round(r["delta"]), round(r["q"], 3), r["pass"])


if __name__ == "__main__":
    {"design": design}[sys.argv[1]]()
