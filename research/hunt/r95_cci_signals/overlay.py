"""R95 amendment A1, idea 1 (PREREG section 10.2): the CCI cross back inside +-200 as an early EXIT for two arms.

The arms' own trades (Liquidity BANKNIFTY 15+5 + FINNIFTY 30+5; Breadth Donchian C1 / 4 / XL on the 5 indices) are
taken as they are. While a trade is open, the indicator's signal AGAINST it (a SELL for a call, a BUY for a put, CCI 25
of hlc3 on the UNDERLYING, 5m or 15m bars) sells the option at the next minute's open, if that is before the arm's own
exit. The exit can only move EARLIER (risk-lowering). Both exits are priced with the same pricer, so the difference
is the exit alone. Rows are R51 rows: row r = minute 09:15 + r.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r89_pdhl_break.study import stats
from research.hunt.r90_almazov_bos import price as P90

from . import cfg as K


# ------------------------------------------------------------------------------------------------ the arms' trades
def arm_trades(arm: str, lo, hi) -> list:
    """[dict(und, day, side +1 / -1, er, xr, net)] from the arm's own code (R89 / R91 calls, same stores)."""
    from research.hunt.r89_pdhl_break import arms as A89  # noqa: F401  (R51 / R71 / R72 on sys.path)
    out = []
    if arm == "liquidity":
        import fam_liq
        for und in fam_liq.IDX_STOP:                # R51 defines the arm for BANKNIFTY and FINNIFTY only
            T = fam_liq.make(und, fam_liq.ORIG_BOOKS[und]).run(lo, hi)
            for r in T.to_dict("records"):
                out.append(_norm(r))
    elif arm == "breadth_donchian":
        import r72study as S72
        S72.use_r72()
        out = [_norm(r) for r in S72.S.rule_trades("C1", 4, "XL", lo, hi)]
    else:
        raise ValueError(arm)
    return out


def _norm(r: dict) -> dict:
    return dict(und=r["und"], day=r["day"], side=1 if r["side"] == "C" else -1, er=int(r["er"]), xr=int(r["xr"]),
                net=float(r["net"]))


# ------------------------------------------------------------------------------------------------ the rule
def day_bars(Ind, P, d: int) -> tuple:
    """(close rows, buy, sell) of session d's bars: the R51 row of each bar's last minute."""
    ix = np.flatnonzero(Ind.B["day"] == d)
    rows = (P.mt[Ind.B["e"][ix]] - K.NSE_OPEN).astype(np.int64)
    return rows, Ind.buy[ix], Ind.sell[ix]


def exit_row(rows: np.ndarray, buy: np.ndarray, sell: np.ndarray, er: int, xr: int, side: int) -> int:
    """The overlay's exit row (the minute after the first bar closing at or after the entry row with the signal against
    the position), or -1 when none comes before the arm's own exit row xr."""
    for r, b, s in zip(rows, buy, sell):
        if r < er:
            continue
        x = int(r) + 1
        if x >= xr:
            break
        if (side > 0 and s) or (side < 0 and b):
            return x
    return -1


def price_rows(und: str, D, side: int, er: int, fx_rows, lot: float) -> np.ndarray:
    """Net (1 lot, after costs) of the 1-ITM option chosen at entry row er, sold at each exit row in fx_rows."""
    fx = np.asarray(fx_rows, np.int64)
    n = len(fx)
    net, _, _, _, ok = P90.idx_option_day(und, D, ["C" if side > 0 else "P"] * n, [er - 1] * n, fx, [lot] * n)
    return np.where(ok, net, np.nan)


# ------------------------------------------------------------------------------------------------ one cell
def run_cell(arm: str, tf: int, insts: dict, lo, hi, twins: int = K.TWINS, seed: int = K.SEED) -> tuple:
    """insts: und -> study.Inst. Returns (cell result, per-trade frame)."""
    rng = np.random.default_rng(seed + 7 * tf + (0 if arm == "liquidity" else 1))
    T = arm_trades(arm, lo, hi)
    rows, tw = [], np.zeros(twins)
    for t in T:
        I = insts[t["und"]]
        P = I.P
        d = I.day_index_of(t["day"])
        if d is None:
            continue
        Ind = I.ind(tf)
        br, b, s = day_bars(Ind, P, d)
        x = exit_row(br, b, s, t["er"], t["xr"], t["side"])
        lot = float(P90.K51.lot(t["und"], t["day"]))
        D = I.store[t["day"]]
        fx = [t["xr"], x if x >= 0 else t["xr"]]
        n0, n1 = price_rows(t["und"], D, t["side"], t["er"], fx, lot)
        if not (np.isfinite(n0) and np.isfinite(n1)):
            continue
        changed = x >= 0
        if changed and t["xr"] - t["er"] > 2:
            rr = rng.integers(t["er"] + 1, t["xr"], size=twins)
            tn = price_rows(t["und"], D, t["side"], t["er"], rr, lot)
            tw += np.where(np.isfinite(tn), tn - n0, n1 - n0)
        elif changed:
            tw += n1 - n0                   # no room for a random early exit: the twin equals the trade
        rows.append(dict(t, date=t["day"], changed=changed, x=x, net0=float(n0), net1=float(n1), diff=float(n1 - n0)))
    F = pd.DataFrame(rows)
    return summarise(arm, tf, F, tw), F


def summarise(arm: str, tf: int, F: pd.DataFrame, tw: np.ndarray) -> dict:
    from scipy.stats import norm
    res = dict(inst=f"ARM:{arm}", tf=tf, variant="EX", trades=int(len(F)))
    if not len(F):
        res["opt"] = dict(trades=0, net=0.0, p_emp=1.0, p_norm=1.0)
        return res
    ch = F[F.changed]
    diff = float(F["diff"].sum())
    sd = float(tw.std())
    o = dict(trades=int(len(ch)), net=round(diff, 0), twin_mean=round(float(tw.mean()), 0),
             p_emp=round((1 + int((tw >= diff).sum())) / (len(tw) + 1), 5),
             p_norm=float(norm.sf((diff - float(tw.mean())) / sd)) if sd > 0 else (0.0 if diff > tw.mean() else 1.0))
    top5 = np.sort(ch["diff"].values)[::-1][:5].sum() if len(ch) else 0.0
    o["ex_top5"] = round(diff - float(top5), 0)
    res["opt"] = o
    res["as_is"] = stats(F, 0, "net0")
    res["with_ex"] = stats(F, 0, "net1")
    o["worst_month"] = res["with_ex"]["worst_month"]
    res["changed_share"] = round(float(F.changed.mean()), 4)
    res["recorded_arm_net"] = round(float(F.net.sum()), 0)
    res["folds"] = [dict(lo=str(a), hi=str(b), net=round(float(F[(F.date >= a) & (F.date <= b)]["diff"].sum()), 0))
                    for a, b in K.IDX_FOLDS]
    return res


def gates(r: dict) -> dict:
    o = r["opt"]
    g = dict(G1=o.get("trades", 0) >= K.MIN_TRADES, G2=o.get("net", 0) > 0, G3=o.get("p_emp", 1) < K.GATE_P,
             G4=r.get("q", 1) < K.GATE_Q, G5=sum(x["net"] > 0 for x in r.get("folds", [])) >= 3,
             G6=o.get("ex_top5", -1) > 0, G7=o.get("worst_month", -1e9) >= K.MONTH_STOP)
    g["pass"] = bool(all(g.values()))
    return {k: bool(x) for k, x in g.items()}
