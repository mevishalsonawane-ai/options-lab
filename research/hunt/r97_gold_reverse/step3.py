"""R97 step 3 (descriptive, DESIGN window only): what the GOLD / GOLDM options really did during the swings Boss sees.

    python -m research.hunt.r97_gold_reverse.step3

HINDSIGHT on purpose: only swings that DID reach S points (zig-zag X = 200) are taken. Entry: the first minute the
future is T = 100 points past the swing's start (pivot), the option bought at the open of its first print within 5
minutes after that minute (real prints only). Exit: the minute the swing's end was confirmed (the future came back X
from the extreme), the option sold at that minute's printed close (else the next print within 10 minutes), or the
square-off (close - 15 min) if the day ended first. This is the BEST a buyer could do on those swings with a
real-time trigger and a real-time trail exit; failed starts are left out, so it is a ceiling, not a strategy.

Fills on real prints only. An exit whose strike has left the stored ATM+/-3 window is priced by R96's Black-76 mark
(IV of the nearest stored strike) at 2x the half-spread and counted ("model exits"); real-print exits are also
reported alone. Swings with no print within 5 minutes of the trigger are counted (liquidity), not traded. Costs: Zerodha MCX option charges + the R96 half-spread (0.60% before 17:00 IST, 0.30% after).
Writes results/step3.json.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import engine as E
from research.hunt.r97_gold_reverse import cfg as K
from research.hunt.r97_gold_reverse import data as D
from research.hunt.r97_gold_reverse import moves as M
from research.hunt.r97_gold_reverse.step1 import MIN_MINUTES, clock_bucket, q

T, X = 100, 200
SIZES = (300, 500, 1000)
FILL_WAIT = 5
STRIKES = {"ATM": 0, "1-ITM": 1, "1-OTM": -1}


def atm_iv(book, cp: int, j: int) -> float:
    a = book.iv.get((cp, 0))
    return float(a[j]) if a is not None and np.isfinite(a[j]) else np.nan


def one(book, d, ix, F, g, itm: int):
    """Option result of one swing g (hindsight leg) bought `itm` steps in the money. None when no fill."""
    m = book.m
    j = next((k for k in range(g.i0, g.i1 + 1) if g.d * (F[k] - F[g.i0]) >= T), None)
    if j is None:
        return None
    i_sig = ix[j]
    if m.is_exp[i_sig]:
        return dict(skip="expiry day")
    cp = 0 if g.d > 0 else 1
    strike = E.pick_strike(m, cp, i_sig, itm)
    if not np.isfinite(strike):
        return dict(skip="no strike")
    fp = E.first_print(book, cp, strike, i_sig + 1, FILL_WAIT) if i_sig + 1 < len(m.ts) and \
        m.day[i_sig + 1] == m.day[i_sig] else None
    if fp is None:
        return dict(skip="no print within 5 min")
    i_in, fill = fp
    sq = E.sq_ix(m, d)
    i_exit = ix[g.ic] if g.ic >= 0 else sq
    i_exit = min(i_exit, sq)
    if i_exit <= i_in:
        return dict(skip="swing over before the fill")
    o, h, l, c, mdl = E.contract_marks(book, cp, strike, i_in, i_exit)
    px, md, stale = E.exit_price(o, c, mdl, m.day[i_in:i_exit + 1], i_exit - i_in)
    if not np.isfinite(px) or stale:
        return dict(skip="no exit print")
    u = book.units
    tod_in, tod_out = int(m.tod[i_in]), int(m.tod[i_exit])
    cost = E.opt_cost(book.ck, fill * u, px * u, tod_in, tod_out, model_exit=md)
    gross = (px - fill) * u
    dF = g.d * (m.F[i_exit] - m.F[i_in])
    printed = np.isfinite(c).mean()
    return dict(day=str(pd.Timestamp(d).date()), d=g.d, size=g.size, tod=tod_in, clock=clock_bucket(tod_in),
                fill=fill, exit=px, gross=gross, cost=cost, net=gross - cost, fut_pts=dF, fut_rs=dF * u,
                delta=(px - fill) / dF if abs(dF) > 1e-9 else np.nan, hold_min=int(i_exit - i_in),
                iv_in=atm_iv(book, cp, i_in), iv_out=atm_iv(book, cp, i_exit), printed_share=float(printed),
                prem_rs=fill * u, model=bool(md), entry_bar_hl_pct=float(100 * (h[0] - l[0]) / fill) if fill > 0 else np.nan)


def run(name: str) -> dict:
    book = D.load_book(name)
    m = book.m
    rows = {k: [] for k in STRIKES}
    skips = {k: {} for k in STRIKES}
    for d in D.design_days(m):
        ix, F, tod = D.day_arrays(m, d)
        if len(ix) < MIN_MINUTES:
            continue
        for g in M.zigzag(F, X):
            if g.size < min(SIZES):
                continue
            for k, itm in STRIKES.items():
                r = one(book, d, ix, F, g, itm)
                if r is None:
                    continue
                if "skip" in r:
                    skips[k][r["skip"]] = skips[k].get(r["skip"], 0) + 1
                else:
                    rows[k].append(r)
    out = dict(book=name, units=book.units, T=T, X=X, skips=skips)
    for k, rr in rows.items():
        df = pd.DataFrame(rr)
        df.to_pickle(K.CACHE / f"step3_{name}_{k}.pkl")
        res = {}
        for S in SIZES:
            g = df[df["size"] >= S]
            if not len(g):
                continue
            res[f"S{S}"] = dict(
                n=int(len(g)), days=int(g.day.nunique()), fut_pts=q(g.fut_pts), fut_rs_per_lot=q(g.fut_rs),
                prem_rs=q(g.prem_rs), gross=q(g.gross), cost=q(g.cost), net=q(g.net),
                net_win=float((g.net > 0).mean()), model_exit_share=float(g.model.mean()),
                net_real_exits=q(g.net[~g.model]), delta=q(g.delta), hold_min=q(g.hold_min),
                iv_change=q(g.iv_out - g.iv_in), printed_share=q(g.printed_share),
                entry_bar_hl_pct=q(g.entry_bar_hl_pct),
                cost_share_of_gross_win=float(g.cost[g.gross > 0].sum() / g.gross[g.gross > 0].sum())
                if (g.gross > 0).any() else None,
                by_clock={c: dict(n=int((g.clock == c).sum()), net_mean=float(g.net[g.clock == c].mean()))
                          for c in sorted(g.clock.unique())})
        out[k] = res
    return out


def main():
    res = {n: run(n) for n in ("GOLDM", "GOLD")}
    (K.RESULTS / "step3.json").write_text(json.dumps(res, indent=1, default=str))
    for n, r in res.items():
        for k in STRIKES:
            for s, v in r[k].items():
                print(n, k, s, v["n"], "net med", round(v["net"]["p50"]), "mean", round(v["net"]["mean"]),
                      "delta", round(v["delta"]["p50"], 2))


if __name__ == "__main__":
    main()
