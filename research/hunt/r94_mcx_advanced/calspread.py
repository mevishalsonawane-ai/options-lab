"""R94 idea 1: MCX calendar spreads on futures (PREREG 3.1, NOTES N1). Research only.

Signal source: the two futures legs themselves (s = ln(far / near) on minutes both printed). Trade: 1 lot each leg,
side +1 = buy far / sell near, -1 = sell far / buy near. Costs and half-spreads on both legs (far leg 3x).

    python -m research.hunt.r94_mcx_advanced.calspread design     # design cells -> results/design_idea1.json
(the holdout is read only through study.py, once, after the cross-idea BH step)
"""
from __future__ import annotations

import pickle
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r94_mcx_advanced import common as C

DESIGN = (date(2026, 1, 1), date(2026, 8, 31))      # first data (May 2026) - 31 Aug 2026
HOLDOUT = (date(2026, 9, 1), date(2026, 10, 19))

# PREREG 3.1: consecutive listed months, >= 4,000 minutes with both legs printed (coverage count of 21 Oct)
PAIRS = [("GOLDM", "2026-11-05", "2026-12-04"), ("GOLDM", "2026-12-04", "2027-01-05"),
         ("GOLDM", "2027-01-05", "2027-02-05"), ("SILVERM", "2026-11-30", "2027-02-26"),
         ("SILVERM", "2027-02-26", "2027-04-30"), ("SILVER", "2026-12-04", "2027-03-05"),
         ("CRUDEOILM", "2026-10-19", "2026-11-19"), ("CRUDEOILM", "2026-11-19", "2026-12-18"),
         ("CRUDEOILM", "2026-12-18", "2027-01-19"), ("CRUDEOIL", "2026-10-19", "2026-11-19"),
         ("CRUDEOIL", "2026-11-19", "2026-12-18"), ("NATGASMINI", "2026-10-27", "2026-11-24"),
         ("NATGASMINI", "2026-11-24", "2026-12-28"), ("NATGASMINI", "2026-12-28", "2027-01-25"),
         ("NATURALGAS", "2026-10-27", "2026-11-24"), ("NATURALGAS", "2026-11-24", "2026-12-28")]
BULLION = {"GOLDM", "SILVERM", "SILVER", "GOLD"}


@dataclass(frozen=True)
class Rule:
    name: str
    kind: str            # "z" or "carry"
    n_obs: int = 0       # z window (previous observations)
    entry: float = 2.0   # |z| entry, or the carry gap in annual points
    exit_: float = 0.0   # z cross level, or |carry gap| <= exit_
    stop: float = 2.0    # sd multiple (z) or further carry points
    max_sessions: int = 2


RULES = {"Z1": Rule("Z1", "z", 100, 2.0, 0.0, 2.0, 2), "Z2": Rule("Z2", "z", 400, 2.0, 0.0, 2.0, 5),
         "C1": Rule("C1", "carry", 0, 0.03, 0.01, 0.03, 10)}
FAIR_CARRY = 0.065
NO_ENTRY_BDAYS = 7
CLOSE_BDAYS = 5


def pair_id(sym, near, far) -> str:
    return f"{sym}-{near[2:7].replace('-', '')}-{far[2:7].replace('-', '')}"


# ------------------------------------------------------------------------------------------------ data
def load_live() -> pd.DataFrame:
    p = C.cache_dir() / "fut_live_mcx.pkl"
    if p.exists():
        return pd.read_pickle(p)
    f = pd.read_parquet(C.lake_dir() / "fut_1m_live_mcx" / "fut_1m_live_mcx.parquet",
                        columns=["symbol", "expiry", "ts", "close", "volume"])
    f["ts"] = f.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    f["expiry"] = f.expiry.astype(str)
    f.to_pickle(p)
    return f


def observations(near: pd.DataFrame, far: pd.DataFrame) -> pd.DataFrame:
    """near / far: one contract's minutes (ts naive IST, close, volume). One observation per 15-minute bar: the LAST
    minute of the bar in which BOTH legs printed (volume > 0). Columns: ts, near, far, s, day."""
    a = near[near.volume > 0][["ts", "close"]].drop_duplicates("ts", keep="last")
    b = far[far.volume > 0][["ts", "close"]].drop_duplicates("ts", keep="last")
    m = a.merge(b, on="ts", suffixes=("_n", "_f")).sort_values("ts")
    m = m[(m.close_n > 0) & (m.close_f > 0)]
    m["bar"] = m.ts.dt.floor("15min")
    m = m.groupby("bar", sort=True).tail(1)
    out = pd.DataFrame({"ts": m.ts.values, "near": m.close_n.values.astype(float),
                        "far": m.close_f.values.astype(float)})
    out["s"] = np.log(out.far / out.near)
    out["day"] = out.ts.dt.normalize()
    return out.reset_index(drop=True)


def sessions_of(near: pd.DataFrame, far: pd.DataFrame) -> np.ndarray:
    """Dates on which either leg printed (NOTES N1.2)."""
    d = pd.concat([near.ts[near.volume > 0], far.ts[far.volume > 0]]).dt.normalize().unique()
    return np.sort(np.asarray(d, dtype="datetime64[ns]"))


# ------------------------------------------------------------------------------------------------ signals
def add_signals(ob: pd.DataFrame, rule: Rule, near_exp: str, far_exp: str) -> pd.DataFrame:
    """z (previous n_obs observations, the current one excluded) or the carry gap; the expiry flags."""
    o = ob.copy()
    if rule.kind == "z":
        prev = o.s.shift(1)
        mu = prev.rolling(rule.n_obs, min_periods=rule.n_obs).mean()
        sd = prev.rolling(rule.n_obs, min_periods=rule.n_obs).std(ddof=1)
        o["sd"] = sd
        o["sig"] = (o.s - mu) / sd
    else:
        gap_days = (pd.Timestamp(far_exp) - pd.Timestamp(near_exp)).days
        o["sd"] = np.nan
        o["sig"] = o.s / (gap_days / 365.0) - FAIR_CARRY          # carry gap in annual units
    ne = np.datetime64(pd.Timestamp(near_exp).date())
    bd = np.busday_count(o.day.values.astype("datetime64[D]"), ne)
    o["no_entry"] = bd <= NO_ENTRY_BDAYS
    o["must_close"] = bd <= CLOSE_BDAYS
    return o


# ------------------------------------------------------------------------------------------------ simulation
def _fill_prices(sym: str, near: float, far: float, side: int, opening: bool, far_mult: float):
    """Fill prices of both legs. side +1 = long far / short near. opening: True for the entry."""
    hn = C.fut_half_spread(sym, near)
    hf = C.fut_half_spread(sym, far, far=True, far_mult=far_mult)
    buy_far = (side == 1) == opening
    f = far + hf if buy_far else far - hf
    n = near - hn if buy_far else near + hn
    return n, f


def arrays(ob: pd.DataFrame) -> dict:
    """The observation columns as numpy arrays (the simulation loops read these, not pandas)."""
    return {k: ob[k].to_numpy() for k in ("ts", "near", "far", "s", "sd", "sig", "must_close")}


def _trade(sym, A, i_in, i_out, side, why, far_mult, risk_pts=np.nan) -> dict:
    u = C.FUT_UNITS[sym]
    n0, f0 = A["near"][i_in], A["far"][i_in]
    n1, f1 = A["near"][i_out], A["far"][i_out]
    fn0, ff0 = _fill_prices(sym, n0, f0, side, True, far_mult)
    fn1, ff1 = _fill_prices(sym, n1, f1, side, False, far_mult)
    gross = side * u * ((f1 - f0) - (n1 - n0))
    pnl = side * u * ((ff1 - ff0) - (fn1 - fn0))
    if side == 1:     # far bought then sold; near sold then bought
        ch = C.fut_leg_charges(ff0 * u, ff1 * u) + C.fut_leg_charges(fn1 * u, fn0 * u)
    else:
        ch = C.fut_leg_charges(ff1 * u, ff0 * u) + C.fut_leg_charges(fn0 * u, fn1 * u)
    net = pnl - ch
    entry_cost = gross - pnl + ch                      # round-trip spread + charges (approx. the same at the stop)
    risk = (risk_pts * u + entry_cost) if np.isfinite(risk_pts) else np.nan
    return {"entry_ts": pd.Timestamp(A["ts"][i_in]), "exit_ts": pd.Timestamp(A["ts"][i_out]),
            "side": int(side), "gross": float(gross), "net": float(net), "costs": float(gross - net),
            "why": why, "i_in": int(i_in), "i_out": int(i_out), "hold_obs": int(i_out - i_in),
            "risk_rs": float(risk), "margin_rs": 2 * C.FUT_MARGIN[sym]}


def _stop_hit(rule: Rule, side: int, s_e: float, sd_e: float, g_e: float, s: float, g: float) -> bool:
    if rule.kind == "z":
        return (s >= s_e + rule.stop * sd_e) if side == -1 else (s <= s_e - rule.stop * sd_e)
    return (g >= g_e + rule.stop) if side == -1 else (g <= g_e - rule.stop)


def _risk_pts(rule: Rule, A, i, gap_days) -> float:
    far = A["far"][i]
    if rule.kind == "z":
        return rule.stop * A["sd"][i] * far
    return rule.stop * (gap_days / 365.0) * far


def _manage(sym, rule, A, sess_ix, i_in, side, far_mult, gap_days, hold_obs=None, end_ix=None) -> dict | None:
    """Run one open position from fill index i_in. hold_obs: twins exit after that many observations instead of the
    signal exit (NOTES N1.1). Returns the trade, or None if no exit observation exists in the data."""
    S, G, MC = A["s"], A["sig"], A["must_close"]
    n = len(S)
    s_e, sd_e, g_e = S[i_in], A["sd"][i_in], G[i_in]
    # the stop distance is fixed from the observation that fills the entry (its own sd / gap)
    sess0 = sess_ix[i_in]
    rp = _risk_pts(rule, A, i_in, gap_days)
    last = n - 1 if end_ix is None else min(end_ix, n - 1)
    for j in range(i_in + 1, last + 1):
        if _stop_hit(rule, side, s_e, sd_e, g_e, S[j], G[j]):
            return _trade(sym, A, i_in, j, side, "stop", far_mult, rp)
        if MC[j]:
            return _trade(sym, A, i_in, j, side, "expiry", far_mult, rp)
        if sess_ix[j] >= sess0 + rule.max_sessions:
            return _trade(sym, A, i_in, j, side, "time", far_mult, rp)
        if hold_obs is not None:
            if j - i_in >= hold_obs:
                return _trade(sym, A, i_in, j, side, "hold", far_mult, rp)
            continue
        g = G[j]
        crossed = (g <= rule.exit_ if side == -1 else g >= -rule.exit_) if rule.kind == "z" else \
            (abs(g) <= rule.exit_)
        if crossed and j + 1 <= last:
            return _trade(sym, A, i_in, j + 1, side, "signal", far_mult, rp)
    return None


def _entry_side(rule: Rule, g: float) -> int:
    if not np.isfinite(g):
        return 0
    if g >= rule.entry:
        return -1
    if g <= -rule.entry:
        return 1
    return 0


def simulate(sym, rule, ob, sess_ix, window, far_mult=3.0, gap_days=30) -> list[dict]:
    """One position at a time. A signal at observation i (entry date in the window) fills at i + 1."""
    out, i, n = [], 0, len(ob)
    A = arrays(ob)
    a, b = np.datetime64(window[0]), np.datetime64(window[1])
    days = ob.day.values.astype("datetime64[D]")
    NE = ob.no_entry.to_numpy()
    while i < n - 1:
        side = _entry_side(rule, A["sig"][i])
        k = i + 1
        if side and not NE[i] and not NE[k] and a <= days[k] <= b \
                and (rule.kind != "z" or np.isfinite(A["sd"][k])):
            t = _manage(sym, rule, A, sess_ix, k, side, far_mult, gap_days)
            if t is None:
                break
            out.append(t)
            i = t["i_out"]
            continue
        i += 1
    return out


def valid_entries(rule, ob, window) -> np.ndarray:
    """Observations where the rule could have entered (NOTES N1.4): signal defined, entry allowed, next obs exists."""
    a, b = np.datetime64(window[0]), np.datetime64(window[1])
    days = ob.day.values.astype("datetime64[D]")
    n = len(ob)
    ok = np.zeros(n, dtype=bool)
    ok[:-1] = np.isfinite(ob.sig.values[:-1]) & ~ob.no_entry.values[:-1] & ~ob.no_entry.values[1:] \
        & (days[1:] >= a) & (days[1:] <= b)
    if rule.kind == "z":
        ok[:-1] &= np.isfinite(ob.sd.values[1:])
    return np.nonzero(ok)[0]


def twins(sym, rule, ob, sess_ix, window, trades, n_sets=C.N_TWINS, seed=C.SEED, far_mult=3.0, gap_days=30):
    """Random entry observation, random side, holding time drawn from the rule's trades (NOTES N1.1)."""
    if not trades:
        return np.array([])
    rng = np.random.default_rng(seed)
    cand = valid_entries(rule, ob, window)
    if not len(cand):
        return np.array([])
    holds = np.array([t["hold_obs"] for t in trades])
    A = arrays(ob)
    k = len(trades)
    cache = {}
    tot = np.zeros(n_sets)
    for s in range(n_sets):
        ii = rng.choice(cand, size=k, replace=True)
        sides = rng.choice([-1, 1], size=k)
        hh = rng.choice(holds, size=k, replace=True)
        x = 0.0
        for i, sd, h in zip(ii, sides, hh):
            key = (int(i), int(sd), int(h))
            if key not in cache:
                t = _manage(sym, rule, A, sess_ix, int(i) + 1, int(sd), far_mult, gap_days, hold_obs=int(h))
                cache[key] = t["net"] if t else 0.0
            x += cache[key]
        tot[s] = x
    return tot


# ------------------------------------------------------------------------------------------------ cells
def build_pair(live: pd.DataFrame, sym, near, far):
    g = live[live.symbol == sym]
    A, B = g[g.expiry == near], g[g.expiry == far]
    ob = observations(A, B)
    sess = sessions_of(A, B)
    sess_ix = np.searchsorted(sess, ob.day.values.astype("datetime64[ns]"))
    return ob, sess_ix


def month_folds(trades, window):
    ms = pd.period_range(window[0], window[1], freq="M")
    return [(m.start_time.date(), m.end_time.date()) for m in ms]


def cells(part: str, live: pd.DataFrame | None = None, n_sets: int = C.N_TWINS) -> dict:
    window = DESIGN if part == "design" else HOLDOUT
    live = load_live() if live is None else live
    out = {}
    for sym, near, far in PAIRS:
        ob0, sess_ix = build_pair(live, sym, near, far)
        gap_days = (pd.Timestamp(far) - pd.Timestamp(near)).days
        for rname, rule in RULES.items():
            if rule.kind == "carry" and sym not in BULLION:
                continue
            cid = f"c-{rname}-{pair_id(sym, near, far)}"
            ob = add_signals(ob0, rule, near, far)
            tr = simulate(sym, rule, ob, sess_ix, window, gap_days=gap_days)
            tw = twins(sym, rule, ob, sess_ix, window, tr, n_sets=n_sets, gap_days=gap_days)
            s = C.summary(tr)
            p = C.twin_p(s["net"], tw) if tr else 1.0
            folds = month_folds(tr, (max(window[0], ob.day.min().date()) if len(ob) else window[0], window[1])) \
                if part == "design" else []
            sens = {f"far_{m}x": float(sum(t["net"] for t in simulate(sym, rule, ob, sess_ix, window, m, gap_days)))
                    for m in (1.0, 5.0)}
            in_win = ob[(ob.day >= pd.Timestamp(window[0])) & (ob.day <= pd.Timestamp(window[1]))]
            out[cid] = {"trades": tr, "p": p, "folds": C.fold_nets(tr, folds), "fold_bounds": folds,
                        "extra": {"observations": int(len(in_win)), "first_obs": str(ob.ts.min()) if len(ob) else None,
                                  "exits": pd.Series([t["why"] for t in tr]).value_counts().to_dict() if tr else {},
                                  "twin_mean": float(np.mean(tw)) if len(tw) else None, **sens,
                                  "median_hold_obs": float(np.median([t["hold_obs"] for t in tr])) if tr else None}}
    return out


def main(argv):
    if argv[:1] != ["design"]:
        print(__doc__)
        return 2
    res = cells("design")
    with open(C.cache_dir() / "idea1_design_trades.pkl", "wb") as f:
        pickle.dump({k: v["trades"] for k, v in res.items()}, f)
    C.save("design_idea1.json", {k: {"summary": C.summary(v["trades"]), "p": v["p"], "folds": v["folds"],
                                     "extra": v["extra"]} for k, v in res.items()})
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
