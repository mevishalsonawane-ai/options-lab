"""R94 shared harness (PREREG.md, 21 Oct 2026): paths, MCX contract facts, Zerodha MCX costs on every leg, half-spreads,
trade metrics with n, Boss's 2% risk check, random-twin p, block-bootstrap p, Benjamini-Hochberg, and the one-time
holdout lock.

Research only. Nothing here places, sizes or changes a real order.

A trade is a dict with at least: entry_ts, exit_ts (Timestamps, naive IST or dates), net (Rs after every cost, 1 lot /
1 unit as the idea defines), gross, risk_rs (Rs lost if the hard stop is hit at its level, costs included) and
margin_rs (the realistic margin of the position, no spread benefit unless stated).
"""
from __future__ import annotations

import json
import os
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
HOLDOUT_LOCK = RESULTS / "HOLDOUT_READ.json"

CAPITAL = 100_000.0           # MCX_GUIDE.md frame (Boss's MCX capital, 8 Oct 2026)
RISK_MAX = 0.02               # Boss, 21 Oct: risk at most 1-2% of capital a position (2% = the upper bound)
RISK_MIN = 0.01
MONTH_STOP = -13_000.0        # CLAUDE.md account stop: -Rs 13k a month
DD_STOP = -40_000.0           # CLAUDE.md account stop: -Rs 40k drawdown
GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40
N_TWINS = 1000
SEED = 94


def lake_dir() -> Path:
    return Path(os.environ.get("IRAALGO_LAKE", Path.home() / "lake")).expanduser()


def cache_dir() -> Path:
    p = Path(os.environ.get("R94_OUT", Path.home() / ".cache" / "iraalgo" / "r94")).expanduser()
    p.mkdir(parents=True, exist_ok=True)
    return p


# ------------------------------------------------------------------------------------------------ contract facts
# futures: units per point of the quoted price (MCX_GUIDE 1a; research/hunt/r53/mcx/data.py MULT)
FUT_UNITS = {"GOLD": 100.0, "GOLDM": 10.0, "SILVER": 30.0, "SILVERM": 5.0, "CRUDEOIL": 100.0, "CRUDEOILM": 10.0,
             "NATURALGAS": 1250.0, "NATGASMINI": 250.0, "COPPER": 2500.0, "ZINC": 5000.0, "ALUMINIUM": 5000.0}
FUT_TICK = {"GOLD": 1.0, "GOLDM": 1.0, "SILVER": 1.0, "SILVERM": 1.0, "CRUDEOIL": 1.0, "CRUDEOILM": 1.0,
            "NATURALGAS": 0.1, "NATGASMINI": 0.1, "COPPER": 0.05, "ZINC": 0.05, "ALUMINIUM": 0.05}
FAMILY = {"GOLD": "gold", "GOLDM": "gold", "SILVER": "silver", "SILVERM": "silver", "CRUDEOIL": "crude",
          "CRUDEOILM": "crude", "NATURALGAS": "natgas", "NATGASMINI": "natgas", "COPPER": "base", "ZINC": "base",
          "ALUMINIUM": "base"}
# Zerodha NRML = MIS margin per lot, 8 Oct 2026 (MCX_GUIDE 1a). A position's margin is the sum of its legs' margins:
# no calendar-spread or hedge benefit is assumed (PREREG 4.1).
FUT_MARGIN = {"GOLD": 1_379_178.0, "GOLDM": 136_807.0, "SILVER": 855_135.0, "SILVERM": 143_930.0,
              "CRUDEOIL": 267_290.0, "CRUDEOILM": 26_729.0, "NATURALGAS": 64_842.0, "NATGASMINI": 12_968.0,
              "COPPER": 329_357.0, "ZINC": 196_225.0, "ALUMINIUM": 156_228.0}
LIQUID = {"GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI"}   # Boss's list

# options: units per premium point of 1 lot, the minimum price step, the ATM half-spread after / before 17:00 IST
OPT_UNITS = {"CRUDEOIL": 100.0, "CRUDEOILM": 10.0, "NATURALGAS": 1250.0, "NATGASMINI": 250.0, "GOLDM": 10.0,
             "SILVERM": 5.0}
OPT_TICK = {"CRUDEOIL": 0.10, "CRUDEOILM": 0.10, "NATURALGAS": 0.05, "NATGASMINI": 0.05, "GOLDM": 0.50,
            "SILVERM": 0.50}
OPT_HALF = {"crude": (0.0030, 0.0060), "natgas": (0.0045, 0.0090), "gold": (0.0030, 0.0060),
            "silver": (0.0030, 0.0060)}       # R85 strict half-spreads (ATM), after / before 17:00
GST = 0.18


# ------------------------------------------------------------------------------------------------ costs
def fut_leg_charges(buy_rs: float, sell_rs: float) -> float:
    """Zerodha MCX futures charges for one round trip of one leg (McxCosts.kt; R53 common.mcx_fut_charges)."""
    brok = min(20.0, 0.0003 * buy_rs) + min(20.0, 0.0003 * sell_rs)
    ctt = 0.0001 * sell_rs
    txn = 0.000021 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs
    return brok + ctt + txn + sebi + stamp + GST * (brok + txn + sebi)


def opt_leg_charges(buy_rs: float, sell_rs: float) -> float:
    """Zerodha MCX options charges for one round trip of one leg (McxCosts.kt; R53 common.mcx_opt_charges): Rs 20 an
    order, CTT 0.05% on the sell side, exchange 0.0418%, SEBI, stamp 0.003% on the buy side, GST."""
    brok = 40.0
    ctt = 0.0005 * sell_rs
    txn = 0.000418 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    stamp = 0.00003 * buy_rs
    return brok + ctt + txn + sebi + stamp + GST * (brok + txn + sebi)


def fut_half_spread(sym: str, price: float, far: bool = False, far_mult: float = 3.0) -> float:
    """Half the bid-ask spread in price points for one futures fill. Near month: R53 rule (crude Rs 1; else the larger
    of 1 tick and 1 bp, halved); base metals 3 bp. A far (second listed) month pays far_mult x that (PREREG 4.1)."""
    fam = FAMILY[sym]
    if fam == "crude":
        h = 1.0
    elif fam == "base":
        h = max(FUT_TICK[sym], 0.0003 * price) / 2
    else:
        h = max(FUT_TICK[sym], 0.0001 * price) / 2
    return h * (far_mult if far else 1.0)


def opt_half_spread(book: str, premium: float, tod: int, offset: int) -> float:
    """Half-spread in premium points for one option fill: R85's ATM rate (after / before 17:00) x (1 + 0.5 |offset|),
    never below one tick (PREREG 4.2). Wings and far strikes pay more."""
    a, b = OPT_HALF[FAMILY[book]]
    rate = (a if tod >= 17 * 60 else b) * (1.0 + 0.5 * abs(int(offset)))
    return max(OPT_TICK[book], rate * max(premium, 0.0))


# ------------------------------------------------------------------------------------------------ metrics
def day_of(ts) -> date:
    if isinstance(ts, date) and not isinstance(ts, datetime):
        return ts
    return pd.Timestamp(ts).date()


def summary(trades: list[dict], key: str = "net") -> dict:
    """Trades, wins, net, worst trade, worst day and worst month (by exit day), max drawdown on the trade sequence,
    the net without the best 5 trades, and Boss's 2% risk check (share of trades whose stop risk fits 2% of
    Rs 1 lakh; capital needed so the median / every trade fits 2%). Every number carries its n."""
    if not trades:
        return {"trades": 0, "win_rate": None, "net": 0.0, "gross": 0.0, "avg": None, "worst_trade": None,
                "worst_day": None, "worst_month": None, "max_dd": 0.0, "net_wo_top5": 0.0, "days": 0,
                "months_below_stop": 0, "fit_2pct_1L": None, "cap_2pct_median": None, "cap_2pct_max": None,
                "margin_median": None}
    df = pd.DataFrame(trades).sort_values("entry_ts")
    x = df[key].astype(float).values
    ex = df.get("exit_ts", df.entry_ts).map(day_of)
    by_day = pd.Series(x).groupby(ex.values).sum()
    by_month = pd.Series(x).groupby([d.strftime("%Y-%m") for d in ex]).sum()
    eq = np.cumsum(x)
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:] - eq))
    risk = df["risk_rs"].astype(float).values if "risk_rs" in df else np.full(len(x), np.nan)
    marg = df["margin_rs"].astype(float).values if "margin_rs" in df else np.full(len(x), np.nan)
    ok = np.isfinite(risk)
    return {
        "trades": int(len(x)),
        "win_rate": float((x > 0).mean()),
        "net": float(x.sum()),
        "gross": float(df["gross"].astype(float).sum()) if "gross" in df else None,
        "avg": float(x.mean()),
        "worst_trade": float(x.min()),
        "worst_day": float(by_day.min()),
        "worst_month": float(by_month.min()),
        "worst_month_name": str(by_month.idxmin()),
        "months_below_stop": int((by_month < MONTH_STOP).sum()),
        "months": int(by_month.size),
        "max_dd": -dd,
        "net_wo_top5": float(np.sort(x)[:-5].sum()) if len(x) > 5 else 0.0,
        "days": int(by_day.size),
        "fit_2pct_1L": float((risk[ok] <= RISK_MAX * CAPITAL).mean()) if ok.any() else None,
        "cap_2pct_median": float(np.median(risk[ok]) / RISK_MAX) if ok.any() else None,
        "cap_2pct_max": float(np.max(risk[ok]) / RISK_MAX) if ok.any() else None,
        "margin_median": float(np.nanmedian(marg)) if np.isfinite(marg).any() else None,
    }


def twin_p(rule_net: float, twin_nets) -> float:
    """One-sided: share of random twins that did at least as well as the rule ((1 + count) / (n + 1))."""
    t = np.asarray(list(twin_nets), dtype=float)
    t = t[np.isfinite(t)]
    if not len(t):
        return float("nan")
    return float((1 + np.sum(t >= rule_net)) / (len(t) + 1))


def block_bootstrap_p(x, block: int = 5, n: int = 5000, seed: int = SEED) -> float:
    """One-sided p that the mean of x is <= 0: moving-block bootstrap of x re-centred to mean 0 (H0), share of
    resampled means >= the observed mean. Used where every eligible day is traded (PREREG 6)."""
    x = np.asarray(x, dtype=float)
    x = x[np.isfinite(x)]
    if len(x) < 3:
        return float("nan")
    m = x.mean()
    z = x - m
    rng = np.random.default_rng(seed)
    b = max(1, min(block, len(x)))
    nb = int(np.ceil(len(x) / b))
    starts = rng.integers(0, len(x) - b + 1, size=(n, nb))
    idx = (starts[:, :, None] + np.arange(b)[None, None, :]).reshape(n, -1)[:, :len(x)]
    means = z[idx].mean(axis=1)
    return float((1 + np.sum(means >= m)) / (n + 1))


def bh(pvals: dict) -> dict:
    """Benjamini-Hochberg q-values (step-up, monotone) over every finite p in the dict."""
    keys = [k for k, v in pvals.items() if v is not None and np.isfinite(v)]
    if not keys:
        return {}
    p = np.array([pvals[k] for k in keys], dtype=float)
    n = len(p)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * n / rank)
        q[i] = prev
    return {k: float(min(1.0, v)) for k, v in zip(keys, q)}


def fold_nets(trades: list[dict], folds) -> list[float | None]:
    """Net per walk-forward fold (by entry day); None where the fold has no trade."""
    out = []
    for a, b in folds:
        x = [t["net"] for t in trades if a <= day_of(t["entry_ts"]) <= b]
        out.append(float(np.sum(x)) if x else None)
    return out


def design_gate(s: dict, p: float, q: float, folds: list, min_trades: int = GATE_MIN_TRADES) -> dict:
    """PREREG 7 design gates. Each is reported; a cell passes only if all hold."""
    have = [f for f in folds if f is not None]
    g = {
        "n": s["trades"] >= min_trades,
        "net": s["net"] > 0,
        "p": p is not None and np.isfinite(p) and p <= GATE_P,
        "q": q is not None and np.isfinite(q) and q <= GATE_Q,
        "walk_forward": bool(have) and sum(f > 0 for f in have) >= int(np.ceil(2 * len(have) / 3)),
        "wo_top5": s["net_wo_top5"] > 0 if s["trades"] >= 40 else s["net"] > 0,
        "month_stop": s["worst_month"] is not None and s["worst_month"] >= MONTH_STOP,
    }
    g["pass"] = all(g.values())
    return g


# ------------------------------------------------------------------------------------------------ holdout lock
class HoldoutReadError(RuntimeError):
    pass


def open_holdout(idea: str, cells: list[str], lock: Path | None = None) -> None:
    """Record the ONE holdout read of an idea's cells. A second read of any cell raises."""
    p = Path(lock or HOLDOUT_LOCK)
    reads = json.loads(p.read_text()) if p.exists() else {}
    again = [c for c in cells if f"{idea}:{c}" in reads]
    if again:
        raise HoldoutReadError(f"holdout already read for {idea}: {again[:5]}")
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    reads.update({f"{idea}:{c}": now for c in cells})
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(reads, indent=1, sort_keys=True))


def save(name: str, obj) -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / name
    p.write_text(json.dumps(obj, indent=1, sort_keys=True, default=_jd))
    return p


def _jd(o):
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return float(o)
    if isinstance(o, (pd.Timestamp, datetime, date)):
        return str(o)
    if isinstance(o, np.ndarray):
        return o.tolist()
    return str(o)
