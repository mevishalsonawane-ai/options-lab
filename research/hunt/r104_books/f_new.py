"""R104 Amendment 1 (PREREG.md): six new strategies combining book ideas. Committed before their first run.

    python research/hunt/r104_books/f_new.py design        # N1-N5 (arms, MM) -> results/design_f_new_arms.json
    python research/hunt/r104_books/r104run.py design f_new # N6 (VOL)

Unknown context (no option prints, no forecast): a CONFIRMATION filter (N1) drops the signal, a SKIP filter (N2, N3,
N5) keeps it.
"""
from __future__ import annotations

import json
import math
import sys
from collections import defaultdict
from datetime import date
from functools import lru_cache
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import f11_arms as A  # noqa: E402
import r104cfg as K  # noqa: E402
import r104ind as I  # noqa: E402
from r104run import IMPL  # noqa: E402


def _atm_vol(D, r0, r1):
    """ATM call + put volume over rows r0..r1-1 (the ATM of row r0)."""
    k = D.atm[r0] if D.atm is not None and np.isfinite(D.atm[r0]) else np.nan
    if k != k:
        return np.nan
    v = 0.0
    for s in ("C", "P"):
        c = D.con.get((s, float(k)))
        if c is None:
            return np.nan
        v += float(np.nansum(c[4, r0:r1]))
    return v


def keep_n1(arm, und, d, c):
    D = A.day(und, d)
    er = int(c["er"])
    if D is None or er < 30:
        return False
    sig = _atm_vol(D, er - 5, er)
    prev = [_atm_vol(D, er - 5 - 5 * j, er - 5 * j) for j in range(1, 21) if er - 5 - 5 * j >= 0]
    prev = [x for x in prev if x == x]
    if sig != sig or len(prev) < 4 or np.mean(prev) <= 0:
        return False
    return sig >= 2 * np.mean(prev)


@lru_cache(None)
def open_stats(und):
    """{day: (effort, result, first-bar sign)} with 20-session medians from earlier sessions only."""
    import r51data
    st = r51data.load(und)
    days = sorted(st)
    raw = {}
    for d in days:
        D = st[d]
        v = _atm_vol(D, 0, 5)
        h, l = np.nanmax(D.idx[1, :5]), np.nanmin(D.idx[2, :5])
        o, c = D.idx[0, 0], D.idx[3, 4]
        raw[d] = (v, h - l if np.isfinite(h) and np.isfinite(l) else np.nan, np.sign(c - o) if np.isfinite(c) and np.isfinite(o) else 0)
    out = {}
    for i, d in enumerate(days):
        past = [raw[x] for x in days[max(0, i - 20):i]]
        ev = [x[0] for x in past if x[0] == x[0]]
        er = [x[1] for x in past if x[1] == x[1]]
        if len(ev) < 10 or len(er) < 10:
            continue
        v, r, s = raw[d]
        out[d] = (v / np.median(ev) if np.median(ev) > 0 and v == v else np.nan,
                  r / np.median(er) if np.median(er) > 0 and r == r else np.nan, s)
    return out


def keep_n2(arm, und, d, c):
    x = open_stats(und).get(d)
    if x is None or not (x[0] == x[0] and x[1] == x[1]):
        return True
    absorb = x[0] >= 1.5 and x[1] <= 0.7
    if not absorb or x[2] == 0:
        return True
    side = 1 if c["side"] == "C" else -1
    return side != x[2]


@lru_cache(None)
def vol_rich(und):
    """{day: True when the 09:20 ATM IV / HAR forecast is in the top third of its trailing 252 sessions}."""
    import f8_vol as V
    import r51data
    st = r51data.load(und)
    fc = V.har_forecast(und)
    exp_days = sorted(d for d, D in st.items() if D.expiry)
    import bisect
    ratio = {}
    for d in sorted(st):
        D = st[d]
        if D.atm is None:
            continue
        i = bisect.bisect_left(exp_days, d)
        if i >= len(exp_days) or d not in fc:
            continue
        exp = exp_days[i]
        k = D.atm[5] if np.isfinite(D.atm[5]) else np.nan
        S = D.idx[3, 5]
        if k != k or not np.isfinite(S):
            continue
        t = V.years_left(d, exp, 5)
        ivs = []
        for s in ("C", "P"):
            con = D.con.get((s, float(k)))
            if con is not None and np.isfinite(con[3, 5]):
                ivs.append(V.implied_vol(float(con[3, 5]), float(S), float(k), t, s == "C"))
        ivs = [x for x in ivs if x == x]
        if ivs and fc[d] > 0:
            ratio[d] = float(np.mean(ivs)) / fc[d]
    out = {}
    keys = sorted(ratio)
    for i, d in enumerate(keys):
        past = [ratio[x] for x in keys[max(0, i - 252):i]]
        if len(past) >= 60:
            out[d] = ratio[d] >= np.percentile(past, 200 / 3)
    return out


def keep_n3(arm, und, d, c):
    return not vol_rich(und).get(d, False)


@lru_cache(None)
def daily_trend(und):
    f = A.daily_frame(und)
    sm = f.close.rolling(50).mean()
    pdi, mdi, _ = I.dmi(f.high.values, f.low.values, f.close.values, 8)
    st = np.where((f.close.values > sm.values) & (pdi > mdi), 1, np.where((f.close.values < sm.values) & (pdi < mdi), -1, 0))
    # known for the NEXT session
    return dict(zip(list(f.index[1:]), st[:-1]))


def keep_n5(arm, und, d, c):
    s = daily_trend(und).get(d)
    if s is None:
        return True
    side = 1 if c["side"] == "C" else -1
    return s == side


NEW_FILTERS = {
    "new-N1": ({"base": keep_n1}, ("R72", "R51")),
    "new-N2": ({"base": keep_n2}, A.ARMS),
    "new-N3": ({"base": keep_n3}, A.ARMS),
    "new-N5": ({"base": keep_n5}, A.ARMS),
}


def mm_n4(trades):
    out = [1] * len(trades)
    real = []                       # (day, exit row, net) of taken trades
    for i, t_ in enumerate(trades):
        d = t_["day"]
        mo = (d.year, d.month)
        wk = tuple(d.isocalendar()[:2])
        done = [(dd, n) for dd, xr, n in real if dd < d or (dd == d and xr <= t_["er"])]
        m_pnl = sum(n for dd, n in done if (dd.year, dd.month) == mo)
        w_pnl = sum(n for dd, n in done if tuple(dd.isocalendar()[:2]) == wk)
        if m_pnl <= -13000.0 or w_pnl <= -6500.0:
            out[i] = 0
            continue
        real.append((d, t_["xr"], t_["net"]))
    return out


def design():
    res = {}
    for rid, (fns, arms) in NEW_FILTERS.items():
        A.FILTERS[rid] = (fns, arms)
        allv = A.run_filter(rid)
        vres = {}
        for vn, per in allv.items():
            st = A.delta_stats(per)
            st["twin_p"] = A.filter_twin_p(rid, vn, per)
            vres[vn] = st
        res[f"{rid}|ARM"] = dict(kind="filter", variants=vres)
        print(rid, {k: (v["delta"], v["p"], v["twin_p"]) for k, v in vres.items()}, flush=True)
    trades = A.mm_trades("design")
    base = A.equity_stats(trades, [1] * len(trades))
    lots = mm_n4(trades)
    st = A.equity_stats(trades, lots)
    qs = []
    for a, b in A.K101.WF_Q:
        sel = [i for i, t_ in enumerate(trades) if a <= t_["day"] <= b]
        tt = [trades[i] for i in sel]
        qs.append(dict(b0=A.equity_stats(tt, [1] * len(tt)), var=A.equity_stats(tt, [lots[i] for i in sel])))
    res["new-N4|MM"] = dict(kind="mm", base=base, variants={"base": dict(st, adds_risk=False,
                                                                          twin_p=A.mm_twin_p(trades, lots), quarters=qs)})
    (K.RESULTS / "design_f_new_arms.json").write_text(json.dumps(res, indent=1, default=str))
    return res


# ------------------------------------------------------------------------------------------------ N6 (VOL)
def n6(part):
    import f8_vol as V
    rich = {}

    def cond(und, d, iv, D):
        ch = V.chain(und)
        if und not in rich:
            rich[und] = V.daily_ratio(und, ch, V.har_forecast(und))
        r = rich[und]
        if d not in r:
            return False
        p = V.trailing_pct(r, d, 200 / 3)
        return p == p and r[d] >= p
    return V.short_straddle_hedged(part, ("NIFTY",), 5, cond, lambda pnl, c: pnl <= -min(13000.0, c),
                                   dict(band_lots=1.0, every=15))


IMPL[("new-N6", "VOL")] = dict(fn=None, custom=n6, variants={"base": dict()}, insts=(), money=0, _family=__name__)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "design":
        design()
    else:
        raise SystemExit(__doc__)
