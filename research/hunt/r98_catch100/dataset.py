"""R98 step A dataset: one row per (entry minute, side) on a 5-minute grid of every GOLDM session in a period, with
causal features (R97 market features, R98 candles and clock anchors) and the outcome labels (future and option
brackets). Cached outside git.

    python -m research.hunt.r98_catch100.dataset design
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import engine as E
from research.hunt.r97_gold_reverse import data as D97
from research.hunt.r97_gold_reverse import features as FE
from research.hunt.r97_gold_reverse import rules as R97
from research.hunt.r98_catch100 import candles as CA
from research.hunt.r98_catch100 import cfg as K
from research.hunt.r98_catch100 import labels as L

OPT_TARGET, OPT_STOP, OPT_HOLD = 100.0, 50.0, 60


def option_bracket(book, i_sig: int, d: int, target=OPT_TARGET, stop=OPT_STOP, hold=OPT_HOLD):
    """Buy the ATM call (d > 0) / put at the first print within 5 min after i_sig; out at +target / -stop premium
    points (stop checked first inside a minute, at the level or a worse open), else the printed close at the end of
    the hold / square-off. Returns (outcome, net_pts after costs, minutes) or None (no fill)."""
    m = book.m
    if i_sig + 1 >= len(m.ts) or m.day[i_sig + 1] != m.day[i_sig] or m.is_exp[i_sig + 1]:
        return None
    cp = 0 if d > 0 else 1
    k = E.pick_strike(m, cp, i_sig, 0)
    if not np.isfinite(k):
        return None
    fp = E.first_print(book, cp, k, i_sig + 1, 5)
    if fp is None:
        return None
    i_in, fill = fp
    i_end = min(E.sq_ix(m, m.day[i_in]), i_in + hold)
    if i_end <= i_in:
        return None
    o, h, l, c, mdl = E.contract_marks(book, cp, k, i_in, i_end)
    o[0] = fill
    out, px, kk = 0, np.nan, i_end - i_in
    for j in range(len(c)):
        if not np.isfinite(l[j]):
            continue
        if l[j] <= fill - stop:
            out, px, kk = -1, min(fill - stop, o[j]), j
            break
        if h[j] >= fill + target:
            out, px, kk = 1, max(fill + target, o[j]), j
            break
    md = False
    if out == 0:
        px, md, st = E.exit_price(o, c, mdl, m.day[i_in:i_end + 1], len(c) - 1)
        if not np.isfinite(px):
            return None
    tod_in, tod_out = int(m.tod[i_in]), int(m.tod[i_in + kk])
    cost = E.opt_cost(book.ck, fill * book.units, px * book.units, tod_in, tod_out, 1.0, md) / book.units
    return out, float(px - fill - cost), int(kk), float(fill)


def build(period, name="design") -> pd.DataFrame:
    book = D97.load_book(K.BOOK)
    m = book.m
    ctx = R97.context(book)
    rows = []
    for day, ix, F, tod, fe in R97.sessions(book, period, ctx):
        if len(ix) < 300:
            continue
        cf = CA.session_candles(m.ts[ix], F, tod)
        ck = CA.clock_features(day, tod)
        cost = L.fut_cost_pts(float(np.median(F)))
        for j in range(len(ix)):
            if not (K.FIRST_TOD <= tod[j] <= K.LAST_TOD) or tod[j] % K.SAMPLE_EVERY:
                continue
            for d in (1, -1):
                r = FE.directional(fe, j, d)
                r.update(day=pd.Timestamp(day), tod=int(tod[j]), side=d, i_sig=int(ix[j]), F=float(F[j]),
                         fut_cost=cost)
                for st in K.STOPS:
                    for hd in K.HOLDS:
                        b = L.bracket(F, j, d, K.TARGET, st, hd)
                        if b is not None:
                            r[f"fo_{int(st)}_{hd}"], r[f"fp_{int(st)}_{hd}"] = b[0], b[1]
                mf, ma, kb = L.mfe_mae(F, j, d, 60)
                r.update(mfe60=mf, mae60=ma)
                ob = option_bracket(book, int(ix[j]), d)
                if ob is not None:
                    r.update(oo=ob[0], onet=ob[1], omin=ob[2], ofill=ob[3])
                rows.append((r, j, d))
        sc = CA.signed
        for r, j, d in rows[-sum(1 for x in rows if x[0]["day"] == pd.Timestamp(day)):]:
            r.update(sc(cf.iloc[[j]], d).iloc[0].to_dict())
            r.update(ck.iloc[j].to_dict())
    df = pd.DataFrame([r for r, _, _ in rows])
    K.CACHE.mkdir(parents=True, exist_ok=True)
    df.to_parquet(K.CACHE / f"dataset_{name}.parquet", index=False)
    return df


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "design"
    if which != "design":
        raise SystemExit("only the design dataset is built before the pre-registration")
    df = build(K.DESIGN)
    print(df.shape, df.day.nunique())
