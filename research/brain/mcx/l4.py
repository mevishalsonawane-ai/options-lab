"""Level 4: strategy search from the brain's outputs (PREREG section 7). Signal source: the MCX future (its minute
closes and the brain's forecasts made from them; ATM IV as context). Traded: one mini-future lot (B1-B3) or one ATM
straddle bought on the option book (B4).

Fills: entry = the first close in the 5 minutes after the decision row (else no trade); stops and targets on minute
closes (a jump through a level fills at the close that crossed it); 60-minute time exit; flat 15 minutes before the
close; one open trade per symbol and rule variant (signals while it is open are skipped). Costs: R53's Zerodha MCX
futures charges + R53's futures half-spread on entry and exit + 0.02% stop slippage (R83); options: R53's MCX option
charges + R53's option half-spread per leg, printed minutes only (R53 Market, R42 bad-tick filter), no trades on
option expiry days.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.data import mcx as L
from research.hunt.r53 import common as R53

from . import config, data as D

N_TWINS, TWIN_SEED = 200, 20261019
HOLD, ENTRY_WAIT = 60, 5
STOP_SLIP = 0.0002
RULES = {
    "B1": [dict(thr=0.55), dict(thr=0.60)],
    "B2": [dict(q="size_q67"), dict(q="size_q80")],
    "B3": [dict(k=1.0), dict(k=1.5)],
    "B4": [dict(q="ratio_q90"), dict(q="ratio_q80")],
}


class Path_:
    """Minute closes of one symbol's cube with day boundaries and the flat row of each day."""

    def __init__(self, cube: pd.DataFrame):
        self.c = np.where(cube.flag.isin(["ok", "stale"]), cube.close.to_numpy(float), np.nan)
        self.ts = pd.DatetimeIndex(cube.ts)
        tod = self.ts.hour * 60 + self.ts.minute
        day = self.ts.normalize()
        self.dayid = pd.factorize(day)[0]
        flat = pd.Series(day).map(lambda d: L.close_min(d) - config.FLAT_BEFORE_CLOSE).to_numpy()
        ok = (tod.to_numpy() <= flat - 1)
        last_ok = pd.Series(np.where(ok, np.arange(len(cube)), -1)).groupby(self.dayid).transform("max").to_numpy()
        self.flat_row = last_ok
        self.seg = pd.factorize(cube.seg.astype(str))[0]
        self.tod = tod.to_numpy()


def sim_fut(P: Path_, i: int, side: int, stop_pct: float | None, tgt_px: float | None = None,
            tgt_pct: float | None = None) -> dict | None:
    """One futures trade from decision row i. stop_pct / tgt_pct: % of the entry price; tgt_px: a price level."""
    e = None
    for j in range(i + 1, min(i + 1 + ENTRY_WAIT, len(P.c))):
        if P.dayid[j] != P.dayid[i] or j > P.flat_row[i]:
            break
        if np.isfinite(P.c[j]):
            e = j
            break
    if e is None:
        return None
    px = P.c[e]
    lim = min(e + HOLD, P.flat_row[i])
    stop = px * (1 - side * stop_pct / 100) if stop_pct else None
    tgt = tgt_px if tgt_px is not None else (px * (1 + side * tgt_pct / 100) if tgt_pct else None)
    ex, why = e, "time"
    for j in range(e + 1, lim + 1):
        if P.seg[j] != P.seg[e]:
            break
        cj = P.c[j]
        if not np.isfinite(cj):
            continue
        ex = j
        if stop is not None and side * (cj - stop) <= 0:
            why = "stop"
            break
        if tgt is not None and side * (cj - tgt) >= 0:
            why = "target"
            break
    return dict(entry_row=e, exit_row=ex, entry_px=px, exit_px=P.c[ex], side=side, why=why)


def fut_net(sym: str, t: dict) -> dict:
    tr = config.SPECS[config.TRADE[sym]]
    mult = tr.mult
    gross = t["side"] * (t["exit_px"] - t["entry_px"]) * mult
    buy, sell = (t["entry_px"], t["exit_px"]) if t["side"] > 0 else (t["exit_px"], t["entry_px"])
    charges = R53.mcx_fut_charges(buy * mult, sell * mult)
    hs = R53.fut_half_spread(t["entry_px"], tr.tick, tr.family) * mult * 2
    slip = STOP_SLIP * t["exit_px"] * mult if t["why"] == "stop" else 0.0
    return {**t, "gross": gross, "cost": charges + hs + slip, "net": gross - charges - hs - slip}


# ------------------------------------------------------------------------------------------------ options (B4)
class Book:
    """R53's option Market of one symbol, indexed by minute."""

    def __init__(self, sym: str, lake):
        from research.hunt.r53.mcx.data import build_market, lake_options
        self.m = build_market(sym, lake_options(sym, lake))
        self.pos = pd.Series(np.arange(len(self.m.ts)), index=pd.DatetimeIndex(self.m.ts))
        self.sym = sym

    def straddle(self, ts, flat_ts) -> dict | None:
        m = self.m
        j = self.pos.index.searchsorted(ts, side="right") - 1
        if j < 0 or m.is_exp[j] or pd.Timestamp(m.ts[j]).normalize() != pd.Timestamp(ts).normalize():
            return None
        d = m.day[j]
        legs = []
        for cp in (0, 1):
            k = m.strike(cp, 0, j)
            lg = m.leg(d, cp, k) if np.isfinite(k) else None
            if lg is None:
                return None
            ii, v = lg
            t_ii = m.ts[ii]
            a = np.flatnonzero((t_ii > np.datetime64(ts)) & (t_ii <= np.datetime64(ts + pd.Timedelta(minutes=ENTRY_WAIT))))
            if not len(a):
                return None
            legs.append((t_ii, v[:, 3], a[0]))
        t0 = max(l[0][l[2]] for l in legs)
        entry = [l[1][l[2]] for l in legs]
        cost0 = sum(entry)
        lim = min(t0 + np.timedelta64(HOLD, "m"), np.datetime64(flat_ts))
        times = np.unique(np.concatenate([l[0][(l[0] > t0) & (l[0] <= lim)] for l in legs]))
        last = list(entry)
        why, tex = "time", t0
        for t in times:
            for n, l in enumerate(legs):
                h = np.flatnonzero(l[0] <= t)
                if len(h) and l[0][h[-1]] >= l[0][l[2]]:
                    last[n] = l[1][h[-1]]
            tex = t
            if sum(last) <= 0.7 * cost0:
                why = "stop"
                break
        mult = config.SPECS[self.sym].mult
        fam = config.SPECS[self.sym].family
        tod = pd.Timestamp(t0).hour * 60 + pd.Timestamp(t0).minute
        gross = (sum(last) - cost0) * mult
        cost = 0.0
        for a, b in zip(entry, last):
            cost += R53.mcx_opt_charges(a * mult, b * mult) + R53.opt_half_spread(fam, tod) * (a + b) * mult
        return dict(entry_ts=pd.Timestamp(t0), exit_ts=pd.Timestamp(tex), entry_px=cost0, exit_px=sum(last), side=0,
                    why=why, gross=gross, cost=cost, net=gross - cost)


# ------------------------------------------------------------------------------------------------ rules
def signals(rule: str, var: dict, o: pd.DataFrame) -> pd.DataFrame:
    """Decision rows that fire, with side and exit parameters (o: one symbol's forecasts, sorted by ts)."""
    pr = np.exp(o.size_comm.to_numpy(float))                       # predicted 60-minute close range, %
    if rule == "B1":
        p = o.p_dir.to_numpy(float)
        side = np.where(p >= var["thr"], 1, np.where(p <= 1 - var["thr"], -1, 0))
        return o.assign(side=side, stop=pr, tgt=np.nan, tgt_px=np.nan)[side != 0]
    if rule == "B2":
        r = o.ret_30m.to_numpy(float)
        fire = (o.size_comm >= o["thr_" + var["q"]]).to_numpy() & (np.abs(r) >= 0.33 * pr)
        return o.assign(side=np.sign(r).astype(int), stop=0.75 * pr, tgt=pr, tgt_px=np.nan)[fire & (r != 0)]
    if rule == "B3":
        dsm = o.dist_session_mean.to_numpy(float)
        fire = (o.size_comm <= o.thr_size_q33).to_numpy() & (np.abs(dsm) >= var["k"] * pr)
        return o.assign(side=-np.sign(dsm).astype(int), stop=0.75 * pr, tgt=np.nan, tgt_mean=dsm)[fire & (dsm != 0)]
    if rule == "B4":
        with np.errstate(invalid="ignore", divide="ignore"):
            ratio = pr / o.iv_range_60m.to_numpy(float)
        fire = np.isfinite(ratio) & (ratio >= o["thr_" + var["q"]].to_numpy(float))
        return o.assign(side=0, stop=np.nan, tgt=np.nan)[fire]
    raise ValueError(rule)


def one_trade(rule, sym, P: Path_, book, row, side, stop, tgt, tgt_mean=None):
    i = int(row)
    if rule == "B4":
        if book is None:
            return None
        ts = P.ts[i]
        flat_ts = P.ts[P.flat_row[i]]
        t = book.straddle(ts, flat_ts)
        return t
    tgt_px = None
    if tgt_mean is not None and np.isfinite(tgt_mean):
        tgt_px = P.c[i] * np.exp(-tgt_mean / 100)                 # the session mean of closes, as a price
    t = sim_fut(P, i, int(side), stop, tgt_px, tgt if np.isfinite(tgt) else None)
    if t is None:
        return None
    t = fut_net(sym, t)
    t["entry_ts"], t["exit_ts"] = P.ts[t["entry_row"]], P.ts[t["exit_row"]]
    return t


def run_variant(rule, var, sym, o, P, book) -> list[dict]:
    sig = signals(rule, var, o.sort_values("ts"))
    out, busy_until = [], pd.Timestamp.min
    for r in sig.itertuples(index=False):
        if r.ts <= busy_until:
            continue
        t = one_trade(rule, sym, P, book, r.row, r.side, r.stop, r.tgt, getattr(r, "tgt_mean", None))
        if t is None:
            continue
        t.update(sym=sym, ts=r.ts, block=r.block, hour=r.ts.hour)
        out.append(t)
        busy_until = t["exit_ts"]
    return out


def twin_p(rule, var, sym, o, P, book, trades, seed) -> tuple[float, list]:
    """Random twins: the same number of trades at random forecast rows of the same symbol, blocks and hour, random
    side (B1-B3) or the same straddle (B4), with the rule's own exits. Net of each row x side is computed once."""
    if not trades:
        return float("nan"), []
    rng = np.random.default_rng(seed)
    pr = np.exp(o.size_comm.to_numpy(float))
    hours = o.ts.dt.hour.to_numpy()
    cache: dict = {}

    def net_at(k, side):
        key = (k, side)
        if key not in cache:
            r = o.iloc[k]
            if rule == "B1":
                t = one_trade(rule, sym, P, book, r.row, side, pr[k], np.nan)
            elif rule == "B2":
                t = one_trade(rule, sym, P, book, r.row, side, 0.75 * pr[k], pr[k])
            elif rule == "B3":
                t = one_trade(rule, sym, P, book, r.row, side, 0.75 * pr[k], np.nan, r.dist_session_mean)
            else:
                t = one_trade(rule, sym, P, book, r.row, 0, np.nan, np.nan)
            cache[key] = t["net"] if t else None
        return cache[key]

    by_hour = {h: np.flatnonzero(hours == h) for h in np.unique(hours)}
    nets = []
    real = sum(t["net"] for t in trades)
    for _ in range(N_TWINS):
        tot = 0.0
        for t in trades:
            pool = by_hour.get(t["hour"], np.arange(len(o)))
            for _try in range(20):
                k = int(rng.choice(pool))
                side = 0 if rule == "B4" else int(rng.choice([-1, 1]))
                v = net_at(k, side)
                if v is not None:
                    tot += v
                    break
        nets.append(tot)
    return R53.twin_p(real, nets), nets


def summarize(trades: list[dict]) -> dict:
    if not trades:
        return dict(trades=0, net=0.0)
    s = R53.summary([{**t, "entry_ts": t["entry_ts"]} for t in trades])
    df = pd.DataFrame(trades)
    s["by_block"] = {int(k): float(v) for k, v in df.groupby("block").net.sum().items()}
    s["gross"] = float(df.gross.sum())
    s["cost"] = float(df.cost.sum())
    s["exits"] = {k: int(v) for k, v in df.why.value_counts().items()}
    s["top5_removed"] = float(np.sort(df.net.to_numpy())[::-1][5:].sum())
    s["worst_month"] = float(df.groupby(pd.to_datetime(df.entry_ts).dt.to_period("M")).net.sum().min())
    return s


def walk_forward(variant_trades: list[list[dict]], n_blocks: int = 4) -> dict:
    by = [[sum(t["net"] for t in tr if t["block"] == b) for b in range(n_blocks)] for tr in variant_trades]
    wf, picks = 0.0, []
    for b in range(1, n_blocks):
        sc = [sum(v[:b]) for v in by]
        pick = int(np.argmax(sc))                                  # ties -> the first variant
        picks.append(pick)
        wf += by[pick][b]
    tot = [sum(v) for v in by]
    return dict(wf_net=wf, wf_picks=picks, block_net=by, frozen=int(np.argmax(tot)))
