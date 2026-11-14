"""R173 Part A (PREREG.md section 2): run Boss's two scripts UNCHANGED on our data, plus model diagnostics.

    python research/hunt/r173_credit_spread_check/r173parity.py build     # their-format index files from the release
    python research/hunt/r173_credit_spread_check/r173parity.py run       # parity + diagnostics -> results/parity.json

The scripts in inputs/ were read in full first (they only read parquet files and print). They are imported by path and
their functions called as they are; this file only (1) writes index files in their format, (2) drops MIDCPNIFTY (no
data here) from R72's UNDS, (3) cuts their window at 2026-02-23 (the data hole), and (4) for the diagnostics, re-runs
their own credit-spread arithmetic with theta = 0 and on random entries.
"""
from __future__ import annotations

import importlib.util
import json
import math
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
INPUTS = HERE / "inputs"
RESULTS = HERE / "results"
RELEASE = Path(os.environ.get("R171_DATA", Path.home() / ".cache" / "iraalgo" / "r171_release")).expanduser()
THEIR_DIR = Path(os.environ.get("R173_THEIR", Path.home() / ".cache" / "iraalgo" / "r173_their_format")).expanduser()

START, END = "2024-11-01", "2026-02-23"          # their start; our data's end (the hole starts 2026-02-24)
DESIGN_END = "2025-03-31"
SPLITS = (("design", "2024-11-01", "2025-03-31"), ("walkforward", "2025-04-01", "2025-09-30"),
          ("final", "2025-10-01", "2026-02-23"))
RANDOM_DRAWS = 200


def load_script(name: str):
    spec = importlib.util.spec_from_file_location(name, INPUTS / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod                     # their LIQ script defines a dataclass, which needs its module registered
    spec.loader.exec_module(mod)
    return mod


# ================================================================================================ their-format files
def build() -> None:
    """index_1m_<SYM>_<YEAR>.parquet (ts, open, high, low, close) from the release; nothing on or after 2026-02-24."""
    THEIR_DIR.mkdir(parents=True, exist_ok=True)
    srcs = {"NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"],
            "BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"],
            "FINNIFTY": ["finnifty_index.parquet"], "SENSEX": ["sensex_index.parquet"]}
    for sym, files in srcs.items():
        parts = []
        for f in files:
            df = pd.read_parquet(RELEASE / f, columns=["ts", "open", "high", "low", "close", "right"])
            parts.append(df[df.right.astype(str) == "IX"].drop(columns="right"))
        _write(sym, pd.concat(parts))
    ex = pd.read_parquet(RELEASE / "extra_series.parquet")
    _write("INDIAVIX", ex[ex.series == "INDIAVIX"].drop(columns="series"))


def _write(sym: str, df: pd.DataFrame) -> None:
    df = df.copy()
    df["ts"] = pd.to_datetime(df.ts)
    df = df[df.ts < pd.Timestamp("2026-02-24")].sort_values("ts").drop_duplicates("ts")
    for yr, g in df.groupby(df.ts.dt.year):
        g.reset_index(drop=True).to_parquet(THEIR_DIR / f"index_1m_{sym}_{yr}.parquet", index=False)
    print(sym, len(df), df.ts.min(), df.ts.max(), flush=True)


# ================================================================================================ summaries
def summary(t: pd.DataFrame) -> dict:
    if t is None or len(t) == 0:
        return dict(n=0)
    daily = t.groupby("day")["rs"].sum()
    eq = daily.cumsum()
    pos, neg = t.loc[t.rs > 0, "rs"].sum(), -t.loc[t.rs < 0, "rs"].sum()
    out = dict(n=int(len(t)), win_idx=round(100 * float((t.pts > 0).mean()), 1), win_rs=round(100 * float((t.rs > 0).mean()), 1),
               net=round(float(t.rs.sum())), pf=round(float(pos / neg), 2) if neg > 0 else None,
               max_dd=round(float((eq - eq.cummax()).min())), worst_day=round(float(daily.min())))
    for name, a, b in SPLITS:
        out[name] = round(float(t.loc[(t.day >= a) & (t.day <= b), "rs"].sum()))
    return out


def by_und(t: pd.DataFrame) -> dict:
    out = {"ALL": summary(t)}
    if len(t):
        for u, g in t.groupby("und"):
            out[u] = summary(g)
    return out


# ================================================================================================ R72
def r72_setup(m):
    m.UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]          # no MIDCPNIFTY data: 4 of the 4 must break
    exp_path = THEIR_DIR / "fut_expiries.json"
    expiries = {(u, d) for u, d in json.load(open(exp_path))} if exp_path.exists() else set()
    vix = m.load_index(str(THEIR_DIR), "INDIAVIX", "2024-09-01")
    vix_open = vix.groupby("day")["open"].first().to_dict()
    panel, days_by = {}, {}
    for u in m.UNDS:
        df = m.load_index(str(THEIR_DIR), u, "2024-09-01")
        d_map, df5 = m.build_days_5m(df, u, vix_open, expiries)
        days_by[u] = d_map
        panel[u] = (df5["d"].values, df5["b"].values, df5["o"].values, df5["h"].values, df5["l"].values, df5["c"].values)
    sigs = m.build_panel_signals(panel, M=m.M_BREADTH)
    return days_by, sigs


def r72_cs_components(m, days_by, trades: pd.DataFrame) -> pd.DataFrame:
    """Their credit-spread arithmetic split into its delta and theta terms (their formula, line for line)."""
    rows = []
    for r in trades.itertuples():
        d = days_by[r.und][r.day]
        I = d["I"]
        prem0, delta, gamma, theta_m = m.option_path_1m(I, r.entry_m, r.sign, d["vix"], moneyness="OTM_SPREAD")
        held = max(r.exit_m - r.entry_m, 1)
        theta_gain = theta_m * held * 1.35
        delta_gain = delta * r.pts - (gamma * r.pts ** 2 if r.pts < 0 else 0.0)
        lot = m.LOT_SIZES[r.und]
        cost0 = m.compute_net_cost(prem0, max(0.05, prem0 - delta_gain), lot)
        rows.append(dict(day=r.day, und=r.und, pts=r.pts, rs=r.rs, theta_rs=theta_gain * lot,
                         rs_no_theta=delta_gain * lot - cost0, prem0=prem0))
    return pd.DataFrame(rows)


def r72_random(m, days_by, rng, draws: int = RANDOM_DRAWS) -> list:
    """Random entries under THEIR credit-spread model: per index and tradable day (not their expiry skip), one random side
    and one random entry minute (09:20 - 14:30) per draw, their target / catastrophe / 15:10 exits and P&L formula."""
    totals = []
    keys = [(u, d) for u in m.UNDS for d, o in days_by[u].items() if START <= d <= END and not o["is_exp"]]
    for _ in range(draws):
        tot = 0.0
        for u, d in keys:
            o = days_by[u][d]
            I = o["I"]
            sg = 1 if rng.random() < 0.5 else -1
            entry_m = int(rng.integers(1, 64)) * 5            # 5 .. 315
            tot += _their_cs_trade(m, I, o["vix"], u, entry_m, sg)
        totals.append(tot)
    return totals


def _their_cs_trade(m, I, vix, u, entry_m, sg) -> float:
    lot, istop = m.LOT_SIZES[u], m.IDX_STOP_EXT[u]
    ix0 = I["open"][entry_m]
    prem0, delta, gamma, theta_m = m.option_path_1m(I, entry_m, sg, vix, moneyness="OTM_SPREAD")
    xm, exit_ix = 355, None
    target_ix = ix0 + sg * (m.ENH_TARGET_MULT * istop)
    stop_ix = ix0 - sg * (m.ENH_CATASTROPHE_MULT * istop)
    for mm in range(entry_m, 356):
        if sg * ((I["low"][mm] if sg > 0 else I["high"][mm]) - stop_ix) < 0:
            xm, exit_ix = mm, stop_ix
            break
        if sg * ((I["high"][mm] if sg > 0 else I["low"][mm]) - target_ix) >= 0:
            xm, exit_ix = mm, target_ix
            break
    if exit_ix is None:
        exit_ix = I["close"][xm]
    pts = (exit_ix - ix0) * sg
    held = max(xm - entry_m, 1)
    theta_gain = theta_m * held * 1.35
    delta_gain = delta * pts - (gamma * pts ** 2 if pts < 0 else 0.0)
    return (delta_gain + theta_gain) * lot - m.compute_net_cost(prem0, max(0.05, prem0 - delta_gain - theta_gain), lot)


# ================================================================================================ LIQ
LIQ_UNDS = ["BANKNIFTY", "NIFTY", "FINNIFTY", "SENSEX"]


def liq_run(m, rng, draws: int = RANDOM_DRAWS):
    vix = m.load_index(str(THEIR_DIR), "INDIAVIX", "2024-09-01")
    vix_open = vix.groupby("day")["open"].first().to_dict()
    res, comps, rand = {}, [], np.zeros(draws)
    for sym in LIQ_UNDS:
        df = m.load_index(str(THEIR_DIR), sym, "2024-09-01")
        days = m.build_days(df, vix_open)
        b15 = m.bars(days, m.TFT_BAR_MINUTES)
        z15 = (m.swing_zones(b15, m.SWING_LENGTH, "full")
               + m.pool_zones(b15, m.POOL_CONTACTS, m.POOL_GAP, m.POOL_CONFIRM, m.POOL_MAX_AGE))
        for mode in ("Baseline_LIQ2_LongATM", "LIQ2_BarCloseStop_LongATM", "Enhanced_OTM_CreditSpread"):
            t = m.run_variant(days, b15, z15, sym, mode, start_day=START, end_day=END)
            res.setdefault(mode, []).append(t)
            if mode == "Enhanced_OTM_CreditSpread" and len(t):
                byday = {d["day"]: d for d in days}
                for r in t.itertuples():
                    d = byday[r.day]
                    prem0 = m.option_params(d["I"]["open"][r.entry_m], d["vix"])[0]
                    sp = prem0 * 0.55
                    theta = sp * 0.22 * max(r.held, 1) / 375.0
                    dg = 0.28 * r.pts - (0.0002 * r.pts ** 2 if r.pts < 0 else 0.0)
                    lot = m.LOT_SIZES[sym]
                    comps.append(dict(day=r.day, und=sym, pts=r.pts, rs=r.rs, theta_rs=theta * lot,
                                      rs_no_theta=dg * lot - m.compute_net_cost(sp, max(0.05, sp - dg), lot)))
                # random entries under their model: per tradable day, random side / minute, the trade's own exits
                # replaced by their catastrophe (4.5 x from the entry) / 2 x IDX_STOP_EXT default target / 15:10
                keys = [d for d in days if START <= d["day"] <= END]
                for k in range(draws):
                    rand[k] += sum(_liq_random_trade(m, d, sym, rng) for d in keys if rng.random() < len(t) / len(keys))
    out = {mode: pd.concat(v, ignore_index=True) for mode, v in res.items()}
    return out, pd.DataFrame(comps), rand.tolist()


def _liq_random_trade(m, d, sym, rng) -> float:
    I = d["I"]
    istop, lot = m.IDX_STOP_EXT[sym], m.LOT_SIZES[sym]
    sg = 1 if rng.random() < 0.5 else -1
    e = int(rng.integers(1, 64)) * 5
    ix = I["open"][e]
    target, stop = ix + sg * m.DEFAULT_TARGET_MULT * istop, ix - sg * m.CATASTROPHE_STOP_MULT * istop
    xm, exit_ix = m.SQUARE_OFF_M, None
    for mm in range(e, m.SQUARE_OFF_M):
        if sg * ((I["low"][mm] if sg > 0 else I["high"][mm]) - stop) < 0:
            xm, exit_ix = mm, stop
            break
        if sg * ((I["high"][mm] if sg > 0 else I["low"][mm]) - target) >= 0:
            xm, exit_ix = mm, target
            break
    if exit_ix is None:
        exit_ix = I["close"][xm]
    pts = (exit_ix - ix) * sg
    prem0 = m.option_params(ix, d["vix"])[0]
    sp = prem0 * 0.55
    theta = sp * 0.22 * max(xm - e, 1) / 375.0
    dg = 0.28 * pts - (0.0002 * pts ** 2 if pts < 0 else 0.0)
    return (dg + theta) * lot - m.compute_net_cost(sp, max(0.05, sp - dg - theta), lot)


# ================================================================================================ main
def run() -> dict:
    rng = np.random.default_rng(173)
    out: dict = {"window": [START, END], "note": "their scripts unchanged; R72 without MIDCPNIFTY (4 of 4); no fut_expiries.json"}
    m72 = load_script("Reproduce_R72_Breadth_Backtest")
    days_by, sigs = r72_setup(m72)
    r72 = {}
    for mode in ("C1_Orig_XL", "C1_Enh_ITM1_FastLock", "C1_Enh_FreshBreadth_CreditSpread"):
        t = m72.simulate_r72(days_by, sigs, mode, start_day=START, end_day=END)
        r72[mode] = by_und(t)
        if mode == "C1_Enh_FreshBreadth_CreditSpread":
            cs = t
    comp = r72_cs_components(m72, days_by, cs)
    rnd = r72_random(m72, days_by, rng)
    out["R72"] = dict(modes=r72, theta_share=_theta_share(comp), no_theta=summary(comp.assign(rs=comp.rs_no_theta)),
                      random=_rand_summary(rnd, float(cs.rs.sum())),
                      wins_with_negative_index=int(((cs.pts <= 0) & (cs.rs > 0)).sum()))
    mlq = load_script("Reproduce_R52_Liquidity_Backtest")
    liq, lcomp, lrnd = liq_run(mlq, rng)
    out["LIQ"] = dict(modes={k: by_und(v) for k, v in liq.items()}, theta_share=_theta_share(lcomp),
                      no_theta=summary(lcomp.assign(rs=lcomp.rs_no_theta)),
                      random=_rand_summary(lrnd, float(liq["Enhanced_OTM_CreditSpread"].rs.sum())),
                      wins_with_negative_index=int(((liq["Enhanced_OTM_CreditSpread"].pts <= 0)
                                                    & (liq["Enhanced_OTM_CreditSpread"].rs > 0)).sum()))
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "parity.json").write_text(json.dumps(out, indent=1, default=float))
    return out


def _theta_share(c: pd.DataFrame) -> dict:
    return dict(n=int(len(c)), net=round(float(c.rs.sum())), theta_rs=round(float(c.theta_rs.sum())),
                net_without_theta=round(float(c.rs_no_theta.sum())))


def _rand_summary(tot: list, actual: float) -> dict:
    a = np.asarray(tot)
    return dict(draws=len(a), mean=round(float(a.mean())), sd=round(float(a.std(ddof=1))),
                share_positive=round(float((a > 0).mean()), 3), actual=round(actual),
                share_ge_actual=round(float((a >= actual).mean()), 3))


if __name__ == "__main__":
    cmd = (sys.argv[1:] or ["run"])[0]
    if cmd == "build":
        build()
    else:
        print(json.dumps(run(), indent=1, default=float))
