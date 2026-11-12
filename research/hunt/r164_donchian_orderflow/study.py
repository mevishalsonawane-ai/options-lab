"""R164: judge the pre-registered order-flow filters and exits on the Donchian arms' recorded trades (12 Nov 2026).

Frozen by PREREG.md sections 5 and 6. ``evaluate_entry`` / ``evaluate_exit`` apply the success rules, ``bh`` is the
Benjamini-Hochberg step and ``verdict`` the minimum-sample rule. ``main`` runs the whole study on days Boss has opened on
their own PC (``decrypt_live.py --extract``) plus the unsealed diagnostics; it writes aggregates only (no trade rows).

    python -m research.hunt.r164_donchian_orderflow.study --days DIR_OF_OPENED_DAYS --diag DIR_OF_DIAG_FILES
"""
from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass, replace
from datetime import date, datetime
from zoneinfo import ZoneInfo
from pathlib import Path

import numpy as np

from research.obuy.costs import Costs
from research.hunt.r164_donchian_orderflow import flow as F

HERE = Path(__file__).resolve().parent
PREREG = HERE / "PREREG.md"
ARMS = {"r72_breadth_donchian": "R72", "r51_donchian20": "R51"}
INDICES = ("NIFTY", "BANKNIFTY")
MIN_TRADES = 150        # PREREG 6.5
MIN_CHANGED = 30
WIN_GAIN = 0.05         # PREREG 6.1
P_MAX = 0.05            # PREREG 6.3
Q_MAX = 0.10            # PREREG 6.4
N_RANDOM = 2000
EARLIER_FLOW_OI_TESTS = 131   # research/experiments/validate.py, family flow-oi, 12 Nov 2026 before R164
COSTS = Costs("dated")


@dataclass(frozen=True)
class Trade:
    arm: str              # "R72" / "R51"
    index: str
    direction: int        # +1 call, -1 put
    day: date
    entry_s: float        # epoch second of the fill
    exit_s: float
    entry: float          # premium
    exit: float
    qty: int              # 1 lot's quantity
    live: bool = False
    picker_taken: bool | None = None
    half_spread: float = 0.0   # the option's (ask - bid) / 2 at entry, rupees: the 1.5x-spread stress (PREREG 5)

    @property
    def dn(self) -> int:
        return (self.day - date(1970, 1, 1)).days


STRESS = 1.5


def net(t: Trade, exit_px: float | None = None, spread_mult: float = 1.0, half_spread: float | None = None) -> float:
    """Net rupees of one trade after dated charges. On stress (``spread_mult`` > 1) each leg pays (mult - 1) x the half
    spread more (``half_spread``, else the trade's own)."""
    x = t.exit if exit_px is None else exit_px
    extra = (spread_mult - 1.0) * (t.half_spread if half_spread is None else half_spread)
    e, x = t.entry + extra, max(0.0, x - extra)
    ch = float(COSTS.charge(True, np.array([e]), np.array([t.qty]), np.array([t.dn]))[0])
    ch += float(COSTS.charge(False, np.array([x]), np.array([t.qty]), np.array([t.dn]))[0])
    return (x - e) * t.qty - ch


def _stats(nets: np.ndarray) -> dict:
    n = len(nets)
    return {"trades": n, "win": float(np.mean(nets > 0)) if n else None, "net": float(nets.sum()) if n else 0.0}


def _gates(base: dict, new: dict, p: float | None, enough: bool, base_stress: float, new_stress: float) -> dict:
    gain = None if base["win"] is None or new["win"] is None else new["win"] - base["win"]
    g = {
        "win_gain": gain,
        "g1_win_up_5pts": gain is not None and gain >= WIN_GAIN - 1e-12,
        "net_stress": {"base": base_stress, "rule": new_stress},
        "g2_net_not_lower": new["net"] >= base["net"] - 1e-9 and new_stress >= base_stress - 1e-9,
        "g3_beats_random": p is not None and p <= P_MAX,
        "enough": enough,
    }
    return g


def evaluate_entry(trades: list[Trade], skip: np.ndarray, rng: np.random.Generator, n_random: int = N_RANDOM) -> dict:
    """An entry filter: the kept trades against all, and against random skips of the same count (PREREG 6)."""
    nets = np.array([net(t) for t in trades])
    skip = np.asarray(skip, bool)
    base, new = _stats(nets), _stats(nets[~skip])
    k = int(skip.sum())
    p = None
    if 0 < k < len(trades):
        hits = 0
        for _ in range(n_random):
            m = np.zeros(len(trades), bool)
            m[rng.choice(len(trades), k, replace=False)] = True
            r = _stats(nets[~m])
            if r["win"] - base["win"] >= new["win"] - base["win"] - 1e-12 and r["net"] >= new["net"] - 1e-9:
                hits += 1
        p = (hits + 1) / (n_random + 1)
    enough = len(trades) >= MIN_TRADES and k >= MIN_CHANGED
    st = np.array([net(t, spread_mult=STRESS) for t in trades])
    return {"base": base, "rule": new, "changed": k, "p_random": p,
            **_gates(base, new, p, enough, float(st.sum()), float(st[~skip].sum()))}


def evaluate_exit(trades: list[Trade], early: list[tuple[float, float] | None], bid_at, rng: np.random.Generator,
                  n_random: int = N_RANDOM) -> dict:
    """An exit rule: ``early[i]`` = (sold at second, price) or None. Random twins exit the same number of random trades,
    each at a time-in-trade drawn from the rule's own exits, at that second's next best bid via ``bid_at(trade, second)``
    (None: the trade keeps its own exit)."""
    base_n = np.array([net(t) for t in trades])
    new_n = np.array([net(t, e[1]) if e else net(t) for t, e in zip(trades, early)])
    base, new = _stats(base_n), _stats(new_n)
    offs = [e[0] - t.entry_s for t, e in zip(trades, early) if e]
    k = len(offs)
    p = None
    if 0 < k < len(trades):
        hits = 0
        for _ in range(n_random):
            r_n = base_n.copy()
            for i, off in zip(rng.choice(len(trades), k, replace=False), rng.choice(offs, k, replace=True)):
                t = trades[i]
                at = t.entry_s + off
                if at < t.exit_s:
                    px = bid_at(t, at)
                    if px is not None:
                        r_n[i] = net(t, px)
            r = _stats(r_n)
            if r["win"] - base["win"] >= new["win"] - base["win"] - 1e-12 and r["net"] >= new["net"] - 1e-9:
                hits += 1
        p = (hits + 1) / (n_random + 1)
    enough = len(trades) >= MIN_TRADES and k >= MIN_CHANGED
    b_st = sum(net(t, spread_mult=STRESS) for t in trades)
    n_st = sum(net(t, e[1], spread_mult=STRESS) if e else net(t, spread_mult=STRESS) for t, e in zip(trades, early))
    return {"base": base, "rule": new, "changed": k, "p_random": p, **_gates(base, new, p, enough, float(b_st), float(n_st))}


def bh(pvals: list[float | None], m_total: int | None = None) -> list[float | None]:
    """Benjamini-Hochberg q-values. ``m_total`` >= the number of p-values (earlier tests of the family count, with p 1)."""
    idx = [i for i, p in enumerate(pvals) if p is not None]
    m = max(m_total or 0, len(idx))
    out: list[float | None] = [None] * len(pvals)
    if not idx:
        return out
    order = sorted(idx, key=lambda i: pvals[i])
    q_prev = 1.0
    for rank in range(len(order), 0, -1):
        i = order[rank - 1]
        q_prev = min(q_prev, pvals[i] * m / rank)
        out[i] = min(1.0, q_prev)
    return out


def verdict(res: dict, q: float | None) -> str:
    """PREREG 6: too small -> inconclusive, whatever the numbers; else pass only when every gate holds."""
    if not res["enough"]:
        return "inconclusive (too small)"
    ok = res["g1_win_up_5pts"] and res["g2_net_not_lower"] and res["g3_beats_random"] and q is not None and q <= Q_MAX
    return "pass" if ok else "fail"


# ---- the runner (Boss's own PC: opened days + unsealed diagnostics) -----------------------------------------------------
def prereg_sha() -> str:
    return hashlib.sha256(PREREG.read_bytes()).hexdigest()


IST = ZoneInfo("Asia/Kolkata")


def when(v: str, day: date) -> float:
    """A diagnostics time ("HH:MM:SS" on the file's day, else a full stamp) as an epoch second (IST)."""
    if len(v) <= 8:
        d = datetime.combine(day, datetime.strptime(v, "%H:%M:%S").time())
    else:
        d = datetime.fromisoformat(v.replace(" ", "T"))
    return d.replace(tzinfo=IST).timestamp()


def trades_from_diag(diag: dict, lot: dict[str, int]) -> list[tuple[Trade, str | None]]:
    """The day's closed R72 / R51 trades on NIFTY / BANKNIFTY from a diagnostics file (schema 1/2), each with its option's
    trading symbol when found.

    A trade row has no symbol: it is taken from the day's BUY order of the same account whose time is nearest the entry
    (within 120 s) and whose symbol ends with the trade's right and starts with an index name. Without one the trade is left
    out (counted by the caller). A live trade's lots are hidden, so every trade is priced at 1 lot (PREREG 5)."""
    out = []
    day = date.fromisoformat(diag["day"])
    orders = [o for o in diag.get("orders") or [] if str(o.get("side", "")).upper().startswith("B") and o.get("symbol")]
    for a in diag.get("arms") or []:
        arm = ARMS.get(a.get("key"))
        if not arm:
            continue
        for tr in a.get("trades") or []:
            if tr.get("open") or tr.get("entry") is None or tr.get("exit") is None or not tr.get("exit_at"):
                continue
            right = "CE" if str(tr.get("right")).upper().startswith("C") else "PE"
            t_in = when(tr["entry_at"], day)
            best, sym = 121.0, None
            for o in orders:
                s_ = str(o["symbol"]).upper()
                if o.get("account") != tr.get("account") or not s_.endswith(right):
                    continue
                gap = abs(when(o["at"], day) - t_in)
                if gap < best:
                    best, sym = gap, s_
            ix = None if sym is None else next((i for i in sorted(INDICES, key=len, reverse=True) if sym.startswith(i)), None)
            if sym is not None and (ix is None or sym.startswith(("NIFTYNXT", "FINNIFTY", "MIDCPNIFTY"))):
                continue                      # another index's trade: outside the tick zone
            if ix is None:
                out.append((None, None))      # unknown index: counted as dropped
                continue
            out.append((Trade(arm, ix, 1 if right == "CE" else -1, day, t_in, when(tr["exit_at"], day), float(tr["entry"]),
                              float(tr["exit"]), lot[ix], live=tr.get("account") == "live"), sym))
    return out


def main(argv=None) -> int:  # pragma: no cover - runs only on opened recordings
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", required=True, type=Path, help="folder of opened days (YYYY-MM-DD/ticks.jsonl.gz)")
    ap.add_argument("--diag", required=True, type=Path, help="folder of diag-YYYY-MM-DD.json.gz")
    ap.add_argument("--lot", default="NIFTY=65,BANKNIFTY=30")
    ap.add_argument("--siblings-tests", type=int, default=0, help="R161-R163 tests to count in the joint BH")
    ap.add_argument("--out", type=Path, default=HERE / "results" / "results.json")
    a = ap.parse_args(argv)
    from research.data import read_diagnostics as RD
    lot = {k: int(v) for k, v in (x.split("=") for x in a.lot.split(","))}
    rng = np.random.default_rng(164)
    per_arm: dict[str, list] = {"R72": [], "R51": []}
    dropped = {"no_ticks": 0, "no_symbol": 0, "not_in_watchlist": 0, "no_pre_signal_rows": 0, "gappy": 0}
    for f in sorted(a.diag.glob("diag-*.json.gz")):
        d = RD.load(f)
        tick = a.days / d["day"] / "ticks.jsonl.gz"
        pairs = trades_from_diag(d, lot)
        dropped["no_symbol"] += sum(1 for t, _ in pairs if t is None)
        pairs = [(t, sym) for t, sym in pairs if t is not None]
        if not tick.exists():
            dropped["no_ticks"] += len(pairs)
            continue
        series, inst = F.read_ticks(tick)
        by_sym = {str(x[1]).upper(): k for k, x in inst.items()}
        for t, sym in pairs:
            fut = next((series.get(k) for k, x in inst.items() if x[2] == t.index and x[3] == "FUT"), None)
            opt = series.get(by_sym.get(sym)) if sym in by_sym else None
            if opt is None:
                dropped["not_in_watchlist"] += 1
                continue
            i0 = opt.at_or_before(t.entry_s)
            if i0 is None or opt.s[i0] <= t.entry_s - 60:
                dropped["no_pre_signal_rows"] += 1
                continue
            a_, b_ = opt._span(t.entry_s - 1, t.exit_s)
            if (t.exit_s - t.entry_s + 1) > 0 and (b_ - a_) < 0.8 * (t.exit_s - t.entry_s + 1):
                dropped["gappy"] += 1
                continue
            hs = (opt.ask[i0] - opt.bid[i0]) / 2 if np.isfinite(opt.ask[i0] - opt.bid[i0]) else 0.0
            per_arm[t.arm].append((replace(t, half_spread=max(0.0, float(hs))), fut, opt))
    results = {"prereg_sha256": prereg_sha(), "dropped": dropped, "arms": {}}
    pvals, keys = [], []
    for arm, rows in per_arm.items():
        trades = [r[0] for r in rows]
        res = {}
        if trades:
            for rule in F.ENTRY_RULES:
                skip = np.array([F.entry_skips(F.entry_reads(fu, op, t.entry_s, t.direction))[rule] for t, fu, op in rows])
                res[rule] = evaluate_entry(trades, skip, rng)
            for rule in F.EXIT_RULES:
                early = []
                for t, fu, op in rows:
                    e = F.flip_exit(rule, fu, op, t.entry_s, t.exit_s, t.direction)
                    early.append((e[1], e[2]) if e else None)
                lookup = {id(t): op for t, fu, op in rows}
                bid_at = lambda tr, s: (lambda x: x[1] if x else None)(lookup[id(tr)].bid_after(s, tr.exit_s))
                res[rule] = evaluate_exit(trades, early, bid_at, rng)
        for rule, r in res.items():
            pvals.append(r["p_random"]); keys.append((arm, rule))
        results["arms"][arm] = res
    m_total = EARLIER_FLOW_OI_TESTS + 2 * (len(F.ENTRY_RULES) + len(F.EXIT_RULES)) + a.siblings_tests
    for (arm, rule), q in zip(keys, bh(pvals, m_total)):
        r = results["arms"][arm][rule]
        r["q"], r["verdict"] = q, verdict(r, q)
        r["option_chart_rule"] = rule in F.OPTION_CHART_RULES
    results["bh_m_total"] = m_total
    a.out.write_text(json.dumps(results, indent=1, default=float))
    print(json.dumps({k: {r: v["verdict"] for r, v in x.items()} for k, x in results["arms"].items()}, indent=1))
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
