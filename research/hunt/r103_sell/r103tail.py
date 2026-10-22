"""R103 tail table (PREREG.md Amendment 1, item 5; descriptive, not a test): the largest close-to-09:20 index moves in
the option lake and what the naked 09:20 ATM short straddle and the IF (held to the square-off, no stop) lost on them.

    R103_LAKE=<lake> R103_OUT=<cache> python research/hunt/r103_sell/r103tail.py      # results/tail.json + tail.md

Run only AFTER the holdout read (results/HOLDOUT_READ), because the whole lake is read. Also lists the ten largest
daily moves since 2019 from index_daily (the pre-Aug-2021 ones have no option data: an estimate from the median
09:20 straddle, labelled).
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r103data as RD  # noqa: E402
import r103engine as E  # noqa: E402
import r103stale as ST  # noqa: E402
import r103study as S  # noqa: E402

TOP = 10
MEDIAN_STRADDLE_PCT = {"NIFTY": 0.0096, "BANKNIFTY": 0.0149}      # R102 3.1, for the pre-lake estimate only


def exit_price(D, side: str, k: float, sq: int, d: date, expiry_date: date | None) -> tuple[float, bool]:
    """(price, repriced) of one leg at the square-off: the engine's fill when the leg traded within FILL_LOOK minutes;
    else (Amendment 2) Black-Scholes at its last print's implied volatility with the index at the square-off, floored at
    intrinsic value (a deep in-the-money short that left the lake's ATM +-10 window is worth at least its intrinsic)."""
    con = D.con[(side, float(k))]
    s1 = float(D.idx[3, min(sq, E.NROWS - 1)])
    intr = max(s1 - k, 0.0) if side == "C" else max(k - s1, 0.0)
    if np.isfinite(con[0, sq: sq + E.FILL_LOOK + 1]).any():
        p, _ = E.fill_px(con, sq)
        return p, False
    ok = np.flatnonzero(np.isfinite(con[3, : sq + 1]))
    if not len(ok):
        return float("nan"), True
    r0 = int(ok[-1])
    p0 = float(con[3, r0])
    if expiry_date is not None and np.isfinite(s1):
        v = ST.implied_vol(p0, float(D.idx[3, r0]), float(k), ST.years_left(d, expiry_date, r0), side == "C")
        if v is not None:
            return max(ST.bs(s1, float(k), ST.years_left(d, expiry_date, sq), v, side == "C"), intr, E.TICK), True
    return max(p0, intr), True


def straddle_loss(D, und: str, d: date, expiry: bool, expiry_date: date | None = None) -> dict | None:
    """The naked 09:20 ATM short straddle (both ATM legs sold at the 09:21 open, bought back at the square-off, the
    engine's slippage and dated charges) and the IF held to the square-off, on one day. None without a 09:20 straddle.
    Exit legs with no trade near the square-off are repriced (exit_price)."""
    st = E.setup(D, und, "IF", E.DECIDE_ROW)
    if st is None:
        return None
    q = E.lot(und, d)
    sq = E.SQ_ROW_EXPIRY if expiry else E.SQ_ROW
    er = E.ENTRY_ROW
    out = dict(und=und, day=str(d), expiry=bool(expiry), k0=st.k0, straddle_pts=round(st.s, 2), lot=q)
    gross, ch, rep_ = 0.0, 0.0, 0
    for side, k, _ in st.legs[:2]:
        con = D.con[(side, float(k))]
        p0, _ = E.fill_px(con, er)
        p1, r1 = exit_price(D, side, k, sq, d, expiry_date)
        rep_ += r1
        if not (np.isfinite(p0) and np.isfinite(p1)):
            return None
        f0 = E.slip(p0, False, E.vol5_lots(con, er, q))
        f1 = E.slip(p1, True, E.vol5_lots(con, sq, q))
        gross += (f0 - f1) * q
        ch += E.charges(f0, q, False, d) + E.charges(f1, q, True, d)
    out["straddle_net"] = round(gross - ch)
    # the IF with no stop: X1's stop is never hit when the loss stays below 50% of the credit; to hold to time we price
    # the four legs directly
    gross, ch = 0.0, 0.0
    for side, k, sg in st.legs:
        con = D.con[(side, float(k))]
        p0, _ = E.fill_px(con, er)
        p1, r1 = exit_price(D, side, k, sq, d, expiry_date)
        rep_ += r1
        if not (np.isfinite(p0) and np.isfinite(p1)):
            return None
        f0 = E.slip(p0, sg > 0, E.vol5_lots(con, er, q))
        f1 = E.slip(p1, sg < 0, E.vol5_lots(con, sq, q))
        gross += sg * (f1 - f0) * q
        ch += E.charges(f0, q, sg > 0, d) + E.charges(f1, q, sg < 0, d)
    out["if_net"] = round(gross - ch)
    out["legs_repriced"] = rep_
    out["if_wing_pts"] = float(st.legs[2][1] - st.k0)
    px0, px1 = D.idx[3, E.DECIDE_ROW], D.idx[3, sq]
    out["move_pct"] = round(float(px1 / px0 - 1) * 100, 2) if np.isfinite(px0) and np.isfinite(px1) and px0 > 0 else None
    out["move_over_straddle"] = round(abs(px1 - px0) / st.s, 2) if out["move_pct"] is not None and st.s > 0 else None
    return out


def lake_table() -> list[dict]:
    import os
    os.environ.setdefault("R102_LAKE", str(RD.LAKE))
    sys.path.insert(0, str(HERE.parent / "r102_deep"))
    import r102data as B
    B.LAKE = RD.LAKE
    rows = []
    for u in RD.UNDS:
        days = RD.load(u)
        exp = S.expiry_days(u, days)
        e = B.expiries(u)
        exp_of = dict(zip(e.day, e.expiry.dt.date))
        per = []
        for d, D in days.items():
            if d < RD.START or D.atm is None:
                continue
            r = straddle_loss(D, u, d, d in exp, exp_of.get(d))
            if r is not None and r["move_pct"] is not None:
                per.append(r)
        per.sort(key=lambda r: -abs(r["move_pct"]))
        n = len(per)
        for r in per[:TOP]:
            r["n_days"] = n
        rows += per[:TOP]
        # the straddle's and the IF's whole record, for the write-up
        nets = np.array([r["straddle_net"] for r in per])
        ifn = np.array([r["if_net"] for r in per])
        rows.append(dict(und=u, day="ALL", n_days=n, straddle_net=round(float(nets.sum())), straddle_win=round(float((nets > 0).mean()), 3),
                         straddle_worst=round(float(nets.min())), if_net=round(float(ifn.sum())), if_win=round(float((ifn > 0).mean()), 3),
                         if_worst=round(float(ifn.min()))))
    return rows


def daily_table() -> list[dict]:
    p = RD.LAKE / "index_daily" / "index_daily.parquet"
    if not p.exists():
        return []
    d = pd.read_parquet(p)
    d["date"] = pd.to_datetime(d.date)
    out = []
    for u in RD.UNDS:
        x = d[(d.symbol == u) & (d.date >= "2019-01-01")].sort_values("date").drop_duplicates("date")
        x = x[(x.close > 0) & (x.open > 0)]
        ret = x.close.pct_change() * 100
        gap = (x.open / x.close.shift(1) - 1) * 100
        intra = (x.close / x.open - 1) * 100
        t = pd.DataFrame(dict(day=x.date.dt.date, ret=ret, gap=gap, intra=intra, close=x.close)).dropna()
        t = t.reindex(t.ret.abs().sort_values(ascending=False).index).head(TOP)
        for _, r in t.iterrows():
            lot_ = E.lot(u, r.day)
            est = (abs(r.intra) / 100 - MEDIAN_STRADDLE_PCT[u]) * r.close * lot_
            out.append(dict(und=u, day=str(r.day), close_to_close_pct=round(r.ret, 2), gap_pct=round(r.gap, 2), open_to_close_pct=round(r.intra, 2),
                            lot=lot_, in_option_lake=bool(r.day >= RD.START),
                            straddle_loss_estimate=round(max(est, 0.0)) if r.day < RD.START else None))
    return out


def write() -> dict:
    if not S.HOLDOUT_FLAG.exists():
        raise PermissionError("tail.py reads the whole lake: run it after the holdout read (PREREG.md Amendment 1)")
    res = dict(lake=lake_table(), daily=daily_table())
    S.RESULTS.mkdir(parents=True, exist_ok=True)
    (S.RESULTS / "tail.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    md = ["# R103 tail table (descriptive)", "", "## Largest close-to-09:20 moves in the option lake (Aug 2021 - 19 Oct 2026), 1 lot, net of slippage and charges", "",
          "Exit legs that did not trade near the square-off (they had left the lake's ATM +-10 window) are repriced with Black-Scholes at their last print's IV, floored at intrinsic (PREREG Amendment 2).", "",
          "| index | day | expiry | move 09:20 -> square-off | move / straddle | 09:20 straddle (pts) | naked short straddle net | iron fly (no stop) net | IF wing (pts) | legs repriced |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in res["lake"]:
        if r["day"] == "ALL":
            md.append(f"| {r['und']} | all {r['n_days']:,} days | | | | | net {r['straddle_net']:,}, win {r['straddle_win'] * 100:.0f}%, worst {r['straddle_worst']:,} | "
                      f"net {r['if_net']:,}, win {r['if_win'] * 100:.0f}%, worst {r['if_worst']:,} | | |")
        else:
            md.append(f"| {r['und']} | {r['day']} | {'E' if r['expiry'] else ''} | {r['move_pct']:+.2f}% | {r['move_over_straddle']} | {r['straddle_pts']} | {r['straddle_net']:,} | {r['if_net']:,} | {r['if_wing_pts']:.0f} | {r['legs_repriced']} |")
    md += ["", "## Largest daily moves since 2019 (index_daily; before Aug 2021 the straddle loss is an ESTIMATE from the median 09:20 straddle)", "",
           "| index | day | close to close | gap | open to close | lot | option lake? | naked straddle loss estimate (pre-lake only) |", "|---|---|---|---|---|---|---|---|"]
    for r in res["daily"]:
        est = "" if r["straddle_loss_estimate"] is None else f"about Rs {r['straddle_loss_estimate']:,}"
        md.append(f"| {r['und']} | {r['day']} | {r['close_to_close_pct']:+.2f}% | {r['gap_pct']:+.2f}% | {r['open_to_close_pct']:+.2f}% | {r['lot']} | "
                  f"{'yes' if r['in_option_lake'] else 'no'} | {est} |")
    (S.RESULTS / "tail.md").write_text("\n".join(md) + "\n")
    return res


if __name__ == "__main__":
    write()
    print((S.RESULTS / "tail.md").read_text())
