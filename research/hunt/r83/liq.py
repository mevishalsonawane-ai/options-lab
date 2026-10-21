"""R83 E7 (PREREG section 4): the app's Liquidity book on crude, 1h and 15m bars, on the WTI proxy inside MCX hours.

Book = research/liquidity_gold.py (the app's Liquidity rule, as R53 GB7/GB10 ran it): levels read continuously across
sessions like a chart, swing_zones(bars, 20, "full") + pool_zones(bars, 2, 5, 10) (indicator/liquidity.py); a POOL broken
by a bar's close where an active SWING zone of the same side overlaps -> enter at the next bar's first minute (its open),
in the break's direction (BOTH sides: up = long, down = short CRUDEOILM). One position per book. Exits, first reached
(liquidity_gold.simulate order, minute by minute inside a bar, then at the bar's close):
  - the session cut-off (flat 15 minutes before the MCX close, common.flat_min) -> that minute's close;
  - R83 hard stop: a minute CLOSE through entry -/+ 1 x ATR(14) of the book's bars (ATR known at the signal bar)
    -> that close, with stop slippage (PREREG: "plus a hard stop at 1 x ATR(14) of the book's bars");
  - the next liquidity level (nearest known, unbroken level ahead) touched by a minute high / low -> the level;
  - at the bar's close: a close back through the broken level (failed break), a new level on the trade's side
    (new liquidity), or the session's last bar (cut-off) -> the bar's last minute close.
Sessions: 09:00 IST to the MCX close (23:30 / 23:55); entries from 09:15 IST to 2 hours before the close (the next bar's
first minute must be in that window). Bars are cut every tf minutes from 09:00. Days with < 60% of their MCX minutes
are dropped (liquidity_gold.sessions); missing minutes are forward-filled within the day.
Signal source and fills: WTI proxy (Dukascopy LIGHTCMDUSD); P&L as 1 CRUDEOILM lot at MCX costs (common.fut_net).

    python -m research.hunt.r83.liq design | final
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

from indicator.liquidity import pool_zones, swing_zones
from research.hunt.r83 import common as C
from research.hunt.r83 import eve as E

VARIANTS = ["1h", "15m"]            # primary first
TF = {"1h": 60, "15m": 15}
FIRST_REL = 15                      # 09:15 IST
LAST_BEFORE_CLOSE = 120
ATR_N = 14


def sessions(df: pd.DataFrame, cover: float = 0.6):
    """MCX-hours minutes (common.mcx_hours output) -> list of day grids: day, n, o/h/l/c arrays of n minutes from 09:00,
    forward-filled (a missing minute = the last close, as liquidity_gold.sessions)."""
    out = []
    for day, g in df.groupby("day", sort=True):
        n = C.mcx_close_min(day) - C.MCX_OPEN_MIN
        rel = g.m.to_numpy() - C.MCX_OPEN_MIN
        ok = (rel >= 0) & (rel < n)
        if ok.sum() < cover * n:
            continue
        grid = {}
        for k in "ohlc":
            a = np.full(n, np.nan)
            a[rel[ok]] = g[k].to_numpy(float)[ok]
            grid[k] = a
        c = pd.Series(grid["c"]).ffill().bfill().to_numpy()
        for k in "ohl":
            grid[k] = np.where(np.isnan(grid[k]), c, grid[k])
        grid["c"] = c
        out.append(dict(day=day, n=n, **grid))
    return out


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        n = d["n"]
        for s in range(0, n, tf):
            e = min(s + tf, n)
            rows.append((di, s, e, d["o"][s], d["h"][s:e].max(), d["l"][s:e].min(), d["c"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def bar_atr(b: pd.DataFrame, n: int = ATR_N) -> np.ndarray:
    pc = b.close.shift(1)
    tr = pd.concat([b.high - b.low, (b.high - pc).abs(), (b.low - pc).abs()], axis=1).max(axis=1)
    return tr.rolling(n, min_periods=n).mean().to_numpy()


def simulate(days, b, zones, atr_mult: float = 1.0, mkt: str = "CRUDE", start_i: int = 0, notional_fn=None):
    """liquidity_gold.simulate with carry=False, both sides, per-day first / last / cut, plus the hard ATR stop.
    Trades are recorded only for entries at bar index >= start_i (zones still use all bars before)."""
    DI, S, E_, Cl = b.di.values, b.s.values, b.e.values, b.close.values
    A = bar_atr(b)
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades, pos = [], None
    for i in range(len(b)):
        d = days[DI[i]]
        cut = d["n"] - 15
        if pos is not None:
            sg = pos["sign"]
            why, px, xm, is_stop = None, None, None, False
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E_[i]):
                if m >= cut:
                    why, px, xm = "cut-off", d["c"][m], m
                    break
                if np.isfinite(pos["stop"]) and sg * (d["c"][m] - pos["stop"]) <= 0:
                    why, px, xm, is_stop = "atr stop", d["c"][m], m, True
                    break
                if pos["target"] is not None and ((sg > 0 and d["h"][m] >= pos["target"]) or (sg < 0 and d["l"][m] <= pos["target"])):
                    why, px, xm = "next liquidity", pos["target"], m
                    break
            last = E_[i] - 1
            if why is None and sg * (Cl[i] - pos["level"]) < 0:
                why, px, xm = "failed break", d["c"][last], last
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, px, xm = "new liquidity", d["c"][last], last
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, px, xm = "cut-off", d["c"][last], last
            if why:
                notional = (notional_fn or (lambda px, day: C.proxy_notional(mkt, px, day)))(pos["px"], d["day"])
                gross, net = C.fut_net(mkt, sg, pos["px"], px, notional, stop=is_stop)
                trades.append(dict(entry_ts=pd.Timestamp(pos["day"]) + pd.Timedelta(minutes=C.MCX_OPEN_MIN + pos["m"]),
                                   exit_ts=pd.Timestamp(d["day"]) + pd.Timedelta(minutes=C.MCX_OPEN_MIN + int(xm)),
                                   side=sg, fill=pos["px"], exit=float(px), why=why, gross=gross, net=net,
                                   entry_m=C.MCX_OPEN_MIN + pos["m"], hold=int(xm - pos["m"]),
                                   ds=pos["ds"], dt=pos["dt"], cap=int(xm - pos["m"])))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i] or i + 1 < start_i:
            continue
        m = S[i + 1]
        if not (FIRST_REL <= m <= d["n"] - LAST_BEFORE_CLOSE):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool"]
        cand = [p for p in pl if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        px = d["o"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i) and sg * (q.edge - px) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        stop = px - sg * atr_mult * A[i] if np.isfinite(A[i]) else np.nan
        pos = dict(day=d["day"], di=DI[i], sign=sg, m=int(m), px=float(px), level=z.edge, target=target, stop=stop,
                   ds=(abs(px - stop) / px) if np.isfinite(stop) else None,
                   dt=(abs(target - px) / px) if target is not None else None)
    return trades


def book(days, tf, notional_fn=None):
    b = bars(days, tf)
    zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
    return simulate(days, b, zones, notional_fn=notional_fn)


def load():
    raw = C.mcx_hours(C.load_proxy("CRUDE"))
    days = sessions(raw)
    return raw, days


def run_all(days):
    return {v: book(days, TF[v]) for v in VARIANTS}


def _cut_rule(d):
    return C.flat_min(d)


def twin_nets(D, trades, rng, n=C.N_TWINS):
    """Twins: same side, the same clock minute on a random same-year day, the same ATR-stop and target distances
    (fraction of the fill) and the same minutes held at most (capped by the cut-off); exits on closes."""
    for t in trades:
        t["flat_rule"] = _cut_rule
    tot = E.twin_nets(D, trades, rng, mkt="CRUDE", n=n)
    for t in trades:
        t.pop("flat_rule", None)
    return tot


def design():
    raw, days = load()
    D = E.Days(raw)
    allv = run_all(days)
    V = {v: C.in_part(tr, "design") for v, tr in allv.items()}
    wf = C.walk_forward(V, VARIANTS[0])
    rng = np.random.default_rng(C.SEED + 7)
    res = {"idea": "E7", "signal_source": "WTI proxy (Dukascopy LIGHTCMDUSD), traded as CRUDEOILM future", "variants": {},
           "baseline": "flat (0)"}
    for v in VARIANTS:
        tr = V[v]
        tw = twin_nets(D, tr, rng)
        net = sum(t["net"] for t in tr)
        res["variants"][v] = {**C.summary(tr), "gross": float(sum(t["gross"] for t in tr)), "design_twin_p": C.twin_p(net, tw),
                              "fold_net": wf["fold_net"][v],
                              "exits": pd.Series([t["why"] for t in tr]).value_counts().to_dict() if tr else {},
                              "longs_net": float(sum(t["net"] for t in tr if t["side"] > 0)),
                              "shorts_net": float(sum(t["net"] for t in tr if t["side"] < 0))}
    res["walk_forward"] = {k: wf[k] for k in ("wf_net", "wf_picks", "design_net", "frozen")}
    res["baseline_wf_net"] = 0.0
    res["frozen"] = wf["frozen"]
    C.save_json("E7_design.json", res)
    return res


def final():
    import json
    des = json.loads((C.RESULTS / "E7_design.json").read_text())
    frozen = des["frozen"]
    C.open_final("E7")
    raw, days = load()
    D = E.Days(raw)
    tr = C.in_part(book(days, TF[frozen]), "final")
    rng = np.random.default_rng(C.SEED + 8)
    tw = twin_nets(D, tr, rng)
    net = sum(t["net"] for t in tr)
    out = {"idea": "E7", "frozen": frozen, "baseline": "flat (0)", "baseline_final_net": 0.0,
           frozen: {**C.summary(tr), "gross": float(sum(t["gross"] for t in tr)), "twin_p": C.twin_p(net, tw),
                    "top5_removed": C.top5_removed(tr),
                    "longs_net": float(sum(t["net"] for t in tr if t["side"] > 0)),
                    "shorts_net": float(sum(t["net"] for t in tr if t["side"] < 0)),
                    "by_year": {str(y): float(sum(t["net"] for t in tr if t["entry_ts"].year == y)) for y in range(2023, 2027)}},
           "wf_net": des["walk_forward"]["wf_net"], "baseline_wf_net": 0.0}
    from research.hunt.r83 import mcxreal
    out["mcx_real"] = mcxreal.liq_replay(TF[frozen])
    C.save_json("E7_final.json", out)
    return out


if __name__ == "__main__":
    print({"design": design, "final": final}[sys.argv[1]]())
