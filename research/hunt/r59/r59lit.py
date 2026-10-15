"""R59 steps 1-2: their rule, literally, on the ROLLING 15-minute ATM series, and where its profit comes from.

  bars15()       15-minute closes of one side's offset-0 rows (resample, last close, empty bins dropped), with the
                 strike and expiry of the bin's last row
  their_trades() pct_change over the whole series (it crosses sessions), signal pct <= -20 and close >= 5, exit at the
                 4th next row, Rs 10,000 notional, fractional contracts, Rs 95 a trade, every signal trades
  classify()     same / switch (other strike) / roll (other expiry) between two rows
  held_value()   diagnostic (not a gate): the same trades valued on the contract actually held at the signal bar
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r59cfg as K
import r59data as D


def bars15(opt: pd.DataFrame, side: str, exps: np.ndarray) -> pd.DataFrame:
    x = opt[(opt.offset == 0) & (opt.side == side)].sort_values("ts")
    x = x.drop_duplicates("ts", keep="last").set_index("ts")
    b = x[["close", "strike"]].resample("15min").last().dropna(subset=["close"])
    b = b.reset_index()
    d = np.array([t.toordinal() for t in b.ts.dt.date], dtype=np.int64)
    b["d"] = d
    b["expiry"] = D.expiry_of_rows(d, exps)
    return b


def classify(b: pd.DataFrame, i: np.ndarray, j: np.ndarray) -> np.ndarray:
    e, k = b.expiry.to_numpy(), b.strike.to_numpy(float)
    out = np.full(len(i), "none", dtype=object)
    ok = (j >= 0) & (j < len(b))
    ii, jj = i[ok], j[ok]
    out[ok] = np.where(e[ii] != e[jj], "roll", np.where(k[ii] != k[jj], "switch", "same"))
    return out


def their_trades(b: pd.DataFrame, step: int = K.THEIR_STEP) -> pd.DataFrame:
    c = b.close.to_numpy(float)
    pct = b.close.pct_change().to_numpy(float) * 100
    sig = np.flatnonzero((pct <= K.DROP) & (c >= K.MIN_PREM))
    sig = sig[sig + step < len(b)]
    x = sig + step
    pnl = K.THEIR_NOTIONAL / c[sig] * (c[x] - c[sig]) - K.THEIR_COST
    d = b.d.to_numpy()
    return pd.DataFrame(dict(
        ts=b.ts.to_numpy()[sig], i=sig, entry=c[sig], exit=c[x], pct=pct[sig], pnl=pnl,
        drop_cat=classify(b, sig, sig - 1), rec_cat=classify(b, sig, x),
        overnight_drop=d[sig] != d[sig - 1], overnight_exit=d[x] != d[sig]))


def held_value(opt: pd.DataFrame, b: pd.DataFrame, T: pd.DataFrame, side: str) -> np.ndarray:
    """Diagnostic: each literal trade's change on the contract held at the signal bar (its own last close in the exit
    bin), Rs 10,000 notional, Rs 95; NaN when that contract has no row in the exit bin."""
    x = opt[opt.side == side]
    x = x.assign(bin=x.ts.dt.floor("15min"))
    last = x.sort_values("ts").groupby(["bin", "strike"]).close.last()
    out = np.full(len(T), np.nan)
    ts = b.ts.to_numpy()
    for n, (i, e) in enumerate(zip(T.i.to_numpy(), T.entry.to_numpy())):
        k = b.strike.iat[i]
        t4 = pd.Timestamp(ts[i + K.THEIR_STEP])
        if b.expiry.iat[i + K.THEIR_STEP] != b.expiry.iat[i]:
            continue
        v = last.get((t4, k))
        if v is not None and np.isfinite(v):
            out[n] = K.THEIR_NOTIONAL / e * (v - e) - K.THEIR_COST
    return out


def summary(T: pd.DataFrame) -> dict:
    if not len(T):
        return dict(trades=0)
    p = T.pnl
    one = (T.drop_cat == "same") & (T.rec_cat == "same")
    tot = float(p.sum())
    out = dict(trades=int(len(T)), win=round(float((p > 0).mean()) * 100, 1), per_trade=round(float(p.mean()), 0),
               median=round(float(p.median()), 0), total=round(tot, 0))
    cats = {}
    for name, m in (("one_contract", one),
                    ("drop_switch", T.drop_cat == "switch"), ("drop_roll", T.drop_cat == "roll"),
                    ("rec_switch", T.rec_cat == "switch"), ("rec_roll", T.rec_cat == "roll"),
                    ("any_switch_or_roll", ~one), ("overnight_drop", T.overnight_drop),
                    ("overnight_exit", T.overnight_exit)):
        s = float(p[m].sum())
        cats[name] = dict(trades=int(m.sum()), share_trades=round(float(m.mean()) * 100, 1),
                          profit=round(s, 0), share_profit=round(s / tot * 100, 1) if tot else None,
                          per_trade=round(float(p[m].mean()), 0) if m.any() else None,
                          win=round(float((p[m] > 0).mean()) * 100, 1) if m.any() else None)
    out["by_category"] = cats
    if "held" in T:
        h = T.held.dropna()
        out["held_contract_diag"] = dict(trades=int(len(h)), win=round(float((h > 0).mean()) * 100, 1) if len(h) else None,
                                         per_trade=round(float(h.mean()), 0) if len(h) else None,
                                         total=round(float(h.sum()), 0))
    return out
