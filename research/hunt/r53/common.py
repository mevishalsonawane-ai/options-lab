"""R53 shared harness (PREREG.md, 15 Oct 2026): the split, walk-forward folds, trade metrics, random-twin p, Benjamini-
Hochberg, the gates, the one-time final-test lock, and the MCX / crypto cost and tax books.

A trade is a dict with at least: entry_ts (tz-aware or naive, consistently), net (money after costs; crypto: after costs,
before tax), and optionally gross, side, day. Every book (mcx / gold / crypto) writes its trades through `summary()`.
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

FIRST_DAY = date(2024, 11, 20)          # nothing traded before this (Boss, 15 Oct)
DESIGN = (date(2024, 11, 20), date(2026, 3, 31))
MCX_DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
FINAL = (date(2026, 4, 1), date(2026, 10, 13))
N_TWINS = 500
SEED = 20261015

# walk-forward folds: (train start, train end, [(oos start, oos end), ...])
FOLDS = [(date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
         (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31))]
MCX_FOLDS = [(date(2025, 11, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 2, 28)),
             (date(2026, 3, 1), date(2026, 3, 31))]

GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40
TAX = 0.312                              # 30% + 4% cess on each winning crypto trade, no set-off
TDS = 0.01
GST = 0.18


# ------------------------------------------------------------------------------------------------ split
def day_of(ts) -> date:
    if isinstance(ts, date) and not isinstance(ts, datetime):
        return ts
    return pd.Timestamp(ts).date()


def part_of(d, mcx: bool = False) -> str | None:
    """'design', 'final' or None (before 20 Nov 2024, or MCX minutes before 5 Aug 2025)."""
    d = day_of(d)
    lo = MCX_DESIGN[0] if mcx else DESIGN[0]
    if lo <= d <= DESIGN[1]:
        return "design"
    if FINAL[0] <= d <= FINAL[1]:
        return "final"
    return None


def in_part(d, part: str, mcx: bool = False) -> bool:
    return part_of(d, mcx) == part


def folds(mcx: bool = False):
    return MCX_FOLDS if mcx else FOLDS


# ------------------------------------------------------------------------------------------------ final-test lock
class FinalReadError(RuntimeError):
    pass


def final_lock_path(book: str) -> Path:
    return RESULTS / f"final_read_{book}.json"


def open_final(book: str, ideas: list[str]) -> None:
    """Record the one final-test read of a book's ideas. A second read of any idea raises."""
    p = final_lock_path(book)
    reads = json.loads(p.read_text()) if p.exists() else {}
    again = [i for i in ideas if i in reads]
    if again:
        raise FinalReadError(f"final test already read for {again}")
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    reads.update({i: now for i in ideas})
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(reads, indent=1, sort_keys=True))


# ------------------------------------------------------------------------------------------------ metrics
def summary(trades: list[dict], key: str = "net") -> dict:
    """Trades, win rate, net, net per day with a trade, average win and loss, worst day, max drawdown (on the trade
    sequence by entry time) and the top-5 trades' share of the net. Net per day of the whole period is the caller's."""
    if not trades:
        return {"trades": 0, "win_rate": None, "net": 0.0, "avg_win": None, "avg_loss": None, "worst_day": 0.0,
                "max_dd": 0.0, "top5_share": None, "days": 0, "net_per_day": None}
    df = pd.DataFrame(trades).sort_values("entry_ts")
    x = df[key].astype(float).values
    days = df.entry_ts.map(day_of)
    by_day = pd.Series(x).groupby(days.values).sum()
    eq = np.cumsum(x)
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:] - eq)) if len(eq) else 0.0
    net = float(x.sum())
    top5 = float(np.sort(x)[::-1][:5].sum())
    return {
        "trades": int(len(x)),
        "win_rate": float((x > 0).mean()),
        "net": net,
        "avg_win": float(x[x > 0].mean()) if (x > 0).any() else None,
        "avg_loss": float(x[x <= 0].mean()) if (x <= 0).any() else None,
        "worst_day": float(by_day.min()),
        "max_dd": dd,
        "top5_share": (top5 / net) if net > 0 else None,
        "days": int(by_day.size),
        "net_per_day": float(net / by_day.size),
    }


def twin_p(rule_net: float, twin_nets) -> float:
    t = np.asarray(list(twin_nets), dtype=float)
    return float((1 + np.sum(t >= rule_net)) / (len(t) + 1))


def bh(pvals: dict) -> dict:
    """Benjamini-Hochberg q-values (step-up, monotone)."""
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


def gates(final: dict, p: float | None, q: float | None, wf_net: float | None, key_net: float | None = None) -> dict:
    """PREREG section 3. `final` is a summary(); key_net overrides the net used (crypto: after tax)."""
    net = final["net"] if key_net is None else key_net
    n = final["trades"]
    g = {
        "net_pos": net > 0,
        "p": p is not None and p < GATE_P,
        "q": q is not None and q <= GATE_Q,
        "trades40": n >= GATE_MIN_TRADES,
        "top5": final.get("top5_share") is not None and final["top5_share"] < 1.0,
        "wf_pos": wf_net is not None and wf_net > 0,
    }
    if not g["trades40"]:
        verdict = "too few trades to judge"
    else:
        verdict = "PASS" if all(g.values()) else "fail"
    return {"gates": g, "verdict": verdict}


# ------------------------------------------------------------------------------------------------ walk-forward
def walk_forward(variant_trades: dict[str, list[dict]], original: str, mcx: bool = False) -> dict:
    """variant name -> design trades. Returns the original's OOS net, the re-tune's OOS net and picks, and the design pick."""
    def net_between(tr, a, b):
        return float(sum(t["net"] for t in tr if a <= day_of(t["entry_ts"]) <= b))

    lo = MCX_DESIGN[0] if mcx else DESIGN[0]
    fl = folds(mcx)
    orig_oos = sum(net_between(variant_trades[original], a, b) for a, b in fl)
    picks, rt_oos = [], 0.0
    for a, b in fl:
        train_end = a - pd.Timedelta(days=1)
        scores = {v: net_between(tr, lo, train_end) for v, tr in variant_trades.items()}
        best = max(scores.values())
        pick = original if scores[original] == best else sorted(v for v, s in scores.items() if s == best)[0]
        picks.append(pick)
        rt_oos += net_between(variant_trades[pick], a, b)
    full = {v: net_between(tr, lo, DESIGN[1]) for v, tr in variant_trades.items()}
    best_v = max(full, key=lambda v: (full[v], v == original))
    design_pick = best_v if (best_v != original and full[best_v] > 0) else None
    return {"orig_wf_net": orig_oos, "retune_wf_net": rt_oos, "retune_picks": picks, "design_net": full,
            "design_pick": design_pick}


# ------------------------------------------------------------------------------------------------ MCX costs (Zerodha)
def mcx_opt_charges(buy_prem_rs: float, sell_prem_rs: float) -> float:
    """Zerodha MCX options, one round trip (McxCosts.kt; m3lib.opt_charges)."""
    brok = 40.0
    ctt = 0.0005 * sell_prem_rs
    txn = 0.000418 * (buy_prem_rs + sell_prem_rs)
    sebi = 10e-7 * (buy_prem_rs + sell_prem_rs)
    stamp = 0.00003 * buy_prem_rs
    return brok + ctt + txn + sebi + stamp + GST * (brok + txn + sebi)


def mcx_fut_charges(buy_rs: float, sell_rs: float) -> float:
    """Zerodha MCX futures, one round trip (McxCosts.kt; m3lib.fut_charges)."""
    brok = min(20.0, 0.0003 * buy_rs) + min(20.0, 0.0003 * sell_rs)
    ctt = 0.0001 * sell_rs
    txn = 0.000021 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs
    return brok + ctt + txn + sebi + stamp + GST * (brok + txn + sebi)


OPT_SPREAD = {"crude": 0.0030, "natgas": 0.0043, "gold": 0.0040, "silver": 0.0040}   # full spread after 17:00 IST


def opt_half_spread(family: str, minute_of_day: int) -> float:
    full = OPT_SPREAD[family] * (1 if minute_of_day >= 17 * 60 else 2)
    return full / 2


def fut_half_spread(price: float, tick: float, family: str) -> float:
    if family == "crude":
        return 1.0                      # Rs 2/bbl full spread
    return max(tick, 0.0001 * price) / 2


# ------------------------------------------------------------------------------------------------ crypto costs and tax
CRYPTO_BOOKS = {
    # name: (perp fee rate per side, option fee rate on notional, option cap on premium, GST on fee)
    "delta": (0.0005, 0.0001, 0.035, True),
    "coinswitch_gst": (0.0005, 0.00015, None, True),
    "coinswitch_nogst": (0.0005, 0.00015, None, False),
}
PERP_SLIP = 0.0002


def perp_fee(book: str, notional_usd: float) -> float:
    rate, _, _, gst = CRYPTO_BOOKS[book]
    return rate * notional_usd * ((1 + GST) if gst else 1.0)


def option_fee(book: str, premium_usd: float, notional_usd: float) -> float:
    _, rate, cap, gst = CRYPTO_BOOKS[book]
    f = rate * notional_usd
    if cap is not None:
        f = min(f, cap * max(premium_usd, 0.0))
    return f * ((1 + GST) if gst else 1.0)


def after_tax(nets) -> np.ndarray:
    """31.2% of each winning trade, no set-off between trades."""
    x = np.asarray(nets, dtype=float)
    return np.where(x > 0, x * (1 - TAX), x)


def tds_locked(sell_values) -> float:
    return float(TDS * np.sum(np.asarray(sell_values, dtype=float)))


# ------------------------------------------------------------------------------------------------ io
def save(name: str, obj) -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / name
    p.write_text(json.dumps(obj, indent=1, default=str, sort_keys=True))
    return p


def cache_dir() -> Path:
    return Path(os.environ.get("IRAALGO_CACHE", os.path.expanduser("~/.cache/iraalgo"))) / "r53"


def lake_dir() -> Path:
    return Path(os.environ.get("IRAALGO_LAKE", os.path.expanduser("~/lake")))
