"""R171 study (PREREG.md): four liquidity / execution-cost gates on R51, R72, LIQ (15+5) and the options_lab expiry put.

    R171_DATA=<release files> R171_CACHE=<cache> python research/hunt/r171_liquidity_gates/r171study.py trades
    ... r171study.py design      # design-period table + the combination C chosen per strategy (writes results/design.json)
    ... r171study.py oos         # the out-of-sample read, once (guarded by results/OOS_READ); writes results/oos.json
    ... python research/hunt/r171_liquidity_gates/r171report.py   # results/report.md

A gated trade is always one of the no-gate trades (the skip convention), so every comparison is on the same trades.
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r171arms as A  # noqa: E402
import r171data as D  # noqa: E402

from options_lab import costs as OC  # noqa: E402
from options_lab.features import gates as G  # noqa: E402

RESULTS = HERE / "results"
OOS_FLAG = RESULTS / "OOS_READ"
SEED = 171
MC_DRAWS = 20_000
M_PRIOR = 3841                        # R170's m (every earlier study included)
DESIGN_END = date(2025, 3, 31)
OOS_START, OOS_END = date(2025, 4, 1), date(2026, 2, 23)
BLOCKS = (("2025Q2", date(2025, 4, 1), date(2025, 6, 30)), ("2025Q3", date(2025, 7, 1), date(2025, 9, 30)),
          ("2025Q4", date(2025, 10, 1), date(2025, 12, 31)), ("2026Jan-Feb", date(2026, 1, 1), date(2026, 2, 23)))
STRATS = ("R51", "R72", "LIQ", "EXP")
FAMILIES = {"G1": ("G1a", "G1b", "G1c"), "G2": ("G2a", "G2b", "G2c", "G2d", "G2e", "G2f"),
            "G3": ("G3a", "G3b", "G3c", "G3d"), "G4": ("G4a", "G4b", "G4c", "G4d")}
GATES = tuple(g for f in FAMILIES.values() for g in f)
N_TESTS = (len(GATES) + 1) * len(STRATS)          # 18 x 4 = 72
M_TOTAL = M_PRIOR + N_TESTS
EXP_ENTRY_ROW = 105                                # 11:00


# ================================================================================================ the gates (PREREG 3)
def cost_share(t, regime: str) -> float | None:
    p = t.feat["premium"]
    if p is None or not p > 0:
        return None
    if t.strat == "EXP":
        return OC.sell_to_settle(premium=p, lot_size=t.lot, regime=regime).fraction_of_premium
    return G.round_trip_cost_share(premium=p, lot_size=t.lot, regime=regime)


def _g1(t, W, share):
    return G.pre_entry_active(t.feat[f"vol{W}"], min_share=share)


def _g2(t, cap=None, floor=None, regime="quoted"):
    p = t.feat["premium"]
    if p is None:
        return True                                 # cannot judge -> take
    if floor is not None and p < floor:
        return False
    if cap is not None:
        cs = cost_share(t, regime)
        return cs is None or cs <= cap
    return True


def _g4(t, caps, measured=False):
    p, dte = t.feat["premium"], t.feat["dte"]
    if p is None or dte is None:
        return True
    spread = OC.spread_per_unit(premium=p, regime="quoted")
    if measured and t.feat["roll_pts"] is not None:
        spread = max(t.feat["roll_pts"], OC.TICK_SIZE)
    return G.dte_spread_ok(spread_pts=spread, premium=p, days=dte, caps=caps)


UNIFORM60 = {"2-3": 60.0, "4-7": 60.0, "8-21": 60.0, "22+": 60.0}


def gate_pass(name: str, t) -> bool:
    f = t.feat
    if name == "G1a":
        return _g1(t, 30, 0.80)
    if name == "G1b":
        return _g1(t, 60, 0.80)
    if name == "G1c":
        return _g1(t, 30, 0.95)
    if name == "G2a":
        return _g2(t, cap=0.020)
    if name == "G2b":
        return _g2(t, cap=0.025)
    if name == "G2c":
        return _g2(t, cap=0.030)
    if name == "G2d":
        return _g2(t, floor=10.0)
    if name == "G2e":
        return _g2(t, cap=0.025, regime="roll")
    if name == "G2f":
        return _g2(t, cap=0.025, regime="stress")
    if name == "G3a":
        return G.depth_ok(f["median_lots30"], order_lots=1, multiple=10)
    if name == "G3b":
        return G.depth_ok(f["median_lots30"], order_lots=1, multiple=50)
    if name == "G3c":
        return G.roll_spike_ok(f["roll_rel"], f["roll_normal"], factor=2.0)
    if name == "G3d":
        return G.roll_spike_ok(f["roll_rel"], f["roll_normal"], factor=3.0)
    if name == "G4a":
        return _g4(t, UNIFORM60)
    if name == "G4b":
        return _g4(t, G.DTE_SPREAD_CAPS_TIGHT)
    if name == "G4c":
        dte = f["dte"]
        share = G.coverage_threshold_for_dte(dte) if dte is not None else 0.80
        return _g4(t, G.DTE_SPREAD_CAPS_TIGHT) and G.pre_entry_active(f["vol30"], min_share=share)
    if name == "G4d":
        return _g4(t, G.DTE_SPREAD_CAPS_TIGHT, measured=True)
    raise KeyError(name)


def combo_pass(parts: list, t) -> bool:
    return all(gate_pass(g, t) for g in parts)


# ================================================================================================ trades
def exp_sessions(files) -> list:
    """(file, session) pairs EXP may read: never options_lab's sealed last 30 sessions (expiry_put.split_sessions), never a
    session on or after the hole's start. The sealed files are not even opened."""
    from options_lab.strategy import expiry_put as XP
    sessions = [date.fromisoformat(Path(f).name[:10]) for f in files]
    _, sealed = XP.split_sessions(sessions)
    sealed = set(sealed)
    return [(f, d) for f, d in zip(files, sessions) if d not in sealed and D.usable(d)]


EXP_SKIPPED: list = []


def exp_trades() -> list:
    """options_lab expiry put on expiry_cache sessions, minus its sealed last 30 (never read) and the hole."""
    import pandas as pd
    from options_lab import lots as LT
    from options_lab.strategy import expiry_put as XP
    out = []
    for f, d in exp_sessions(sorted((ROOT / "options_lab" / "data" / "expiry_cache").glob("*_chain.parquet"))):
        ch = pd.read_parquet(f)
        try:
            lot = XP._dated_lot(ch, "NIFTY", d)
            res = {g: XP.run_session(d, ch, lot_size=lot, regime=g) for g in A.REGIMES}
        except (XP.SessionSkipped, XP.ThinChain, XP.NotASnapshot, LT.NoLotSize) as exc:   # never silent: kept and printed
            EXP_SKIPPED.append((d, str(exc)[:80]))
            continue
        k = res["quoted"]["strike"]
        leg = ch[(ch.strike == k) & (ch.right == "PE")].copy()
        ts = pd.to_datetime(leg.ts)
        leg["r"] = (ts.dt.hour * 60 + ts.dt.minute) - (9 * 60 + 15)
        grid = np.full((D.N, 5), np.nan)
        ok = (leg.r >= 0) & (leg.r < D.N)
        rr = leg.r[ok].to_numpy()
        for j in (D.O, D.H, D.L, D.C):
            grid[rr, j] = leg.close[ok].to_numpy(float)
        grid[rr, D.V] = leg.volume[ok].to_numpy(float)
        t = A.Trade("EXP", "NIFTY", d, -1, EXP_ENTRY_ROW, EXP_ENTRY_ROW, D.N - 1, float(k), "P", d, lot,
                    res["quoted"]["credit"], res["quoted"]["settlement"], "settle",
                    {g: float(res[g]["net_pnl"]) for g in A.REGIMES})
        t.feat = A.gate_inputs(grid, EXP_ENTRY_ROW, lot, 0, normal_fn=None)
        out.append(t)
    return out


def build_trades() -> dict:
    store = D.load()
    hist = A.RollHistory(store)
    out = {}
    for name, fn in (("R51", A.r51_trades), ("R72", A.r72_trades), ("LIQ", A.liq_trades)):
        tr = fn(store)
        A.attach_features(tr, store, hist)
        out[name] = tr
        print(name, len(tr), flush=True)
    out["EXP"] = exp_trades()
    print("EXP", len(out["EXP"]), "skipped", EXP_SKIPPED, flush=True)
    D.CACHE.mkdir(parents=True, exist_ok=True)
    with open(D.CACHE / "trades.pkl", "wb") as fh:
        pickle.dump(out, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return out


def load_trades() -> dict:
    p = D.CACHE / "trades.pkl"
    if not p.exists():
        return build_trades()
    with open(p, "rb") as fh:
        return pickle.load(fh)


def in_period(t, period: str) -> bool:
    if period == "design":
        return t.day <= DESIGN_END
    if period == "oos":
        return OOS_START <= t.day <= OOS_END
    for name, a, b in BLOCKS:
        if period == name:
            return a <= t.day <= b
    raise KeyError(period)


# ================================================================================================ measurement
def stats(trades: list, regime: str = "quoted") -> dict:
    if not trades:
        return dict(n=0, net=0.0, per_trade=None, win=None, worst_day=None, max_dd=None)
    x = np.array([t.net[regime] for t in trades])
    byday: dict = {}
    for t in trades:
        byday[t.day] = byday.get(t.day, 0.0) + t.net[regime]
    days = sorted(byday)
    eq = np.cumsum([byday[d] for d in days])
    dd = float(np.min(eq - np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:])) if len(eq) else 0.0
    return dict(n=len(x), net=round(float(x.sum()), 2), per_trade=round(float(x.mean()), 2),
                win=round(float((x > 0).mean() * 100), 2), worst_day=round(float(min(byday.values())), 2),
                max_dd=round(min(dd, 0.0), 2))


def skip_p_normal(pop: np.ndarray, kept: np.ndarray) -> float:
    """One-sided p that a random subset of len(kept) from pop has a mean >= kept's (finite-population normal)."""
    n, k = len(pop), len(kept)
    if k == 0 or k >= n:
        return 1.0
    sd = pop.std() * math.sqrt((n - k) / (k * (n - 1)))
    if sd == 0:
        return 1.0
    z = (kept.mean() - pop.mean()) / sd
    return float(0.5 * math.erfc(z / math.sqrt(2)))


def skip_p_mc(pop: np.ndarray, kept_mean: float, k: int, draws: int = MC_DRAWS, seed: int = SEED) -> float:
    n = len(pop)
    if k == 0 or k >= n:
        return 1.0
    rng = np.random.default_rng(seed)
    hits = 0
    done = 0
    chunk = max(1, min(draws, 2_000_000 // max(n, 1)))
    while done < draws:
        m = min(chunk, draws - done)
        idx = np.argpartition(rng.random((m, n)), k - 1, axis=1)[:, :k]
        hits += int((pop[idx].mean(axis=1) >= kept_mean - 1e-9).sum())
        done += m
    return (hits + 1) / (draws + 1)


def bh_total(p, m_total: int) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests in the family; the earlier tests are taken as p = 1 (R169's bh_total)."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def evaluate(trades: list, period: str, keep, mc: bool = False) -> dict:
    """keep(t) -> bool. The gate's stats, the no-gate stats and the random-skip p, per regime."""
    base = [t for t in trades if in_period(t, period)]
    kept = [t for t in base if keep(t)]
    out = dict(period=period, base={g: stats(base, g) for g in A.REGIMES}, kept={g: stats(kept, g) for g in A.REGIMES})
    pop = np.array([t.net["quoted"] for t in base])
    kx = np.array([t.net["quoted"] for t in kept])
    out["p_norm"] = skip_p_normal(pop, kx) if len(kx) else 1.0
    if mc:
        out["p_mc"] = skip_p_mc(pop, float(kx.mean()) if len(kx) else 0.0, len(kx))
    out["gain"] = {g: (None if not kept or out["kept"][g]["per_trade"] is None else
                       round(out["kept"][g]["per_trade"] - out["base"][g]["per_trade"], 2)) for g in A.REGIMES}
    out["stress_share"] = {lots: (round(float(np.mean([G.depth_regime(t.feat["median_lots30"], order_lots=lots) == "stress"
                                                        for t in base])) * 100, 1) if base else None) for lots in (1, 3)}
    return out


def choose_combo(trades: list) -> list:
    """PREREG C: per family the design-best variant with gain > 0 and design random-skip p <= 0.10; AND of those."""
    parts = []
    for fam, names in FAMILIES.items():
        best, best_gain = None, 0.0
        for g in names:
            r = evaluate(trades, "design", lambda t, g=g: gate_pass(g, t))
            gain = r["gain"]["quoted"]
            if gain is not None and gain > best_gain and r["p_norm"] <= 0.10 and r["kept"]["quoted"]["n"] > 0:
                best, best_gain = g, gain
        if best:
            parts.append(best)
    return parts


def _dump(obj, path: Path):
    path.write_text(json.dumps(obj, indent=1, default=str))


def run_design():
    RESULTS.mkdir(exist_ok=True)
    T = load_trades()
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), strategies={})
    for s in STRATS:
        tr = T[s]
        rows = {g: evaluate(tr, "design", lambda t, g=g: gate_pass(g, t)) for g in GATES}
        combo = choose_combo(tr)
        rows["C"] = evaluate(tr, "design", lambda t, c=combo: combo_pass(c, t)) if combo else None
        out["strategies"][s] = dict(combo=combo, rows=rows)
        print(s, "combo", combo, flush=True)
    _dump(out, RESULTS / "design.json")


def run_oos():
    if OOS_FLAG.exists():
        sys.exit("OOS already read (results/OOS_READ); R171 reads it once.")
    des = json.loads((RESULTS / "design.json").read_text())
    T = load_trades()
    OOS_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), m_prior=M_PRIOR, n_tests=N_TESTS,
               m_total=M_TOTAL, strategies={})
    tests = []
    for s in STRATS:
        tr = T[s]
        combo = des["strategies"][s]["combo"]
        rows = {}
        for g in GATES + ("C",):
            if g == "C" and not combo:
                rows[g] = None
                tests.append((s, g, 1.0))
                continue
            keep = (lambda t, c=combo: combo_pass(c, t)) if g == "C" else (lambda t, g=g: gate_pass(g, t))
            r = evaluate(tr, "oos", keep, mc=True)
            r["blocks"] = {b: evaluate(tr, b, keep)["gain"]["quoted"] for b, _, _ in BLOCKS}
            r["design_gain"] = (des["strategies"][s]["rows"][g] or {}).get("gain", {}).get("quoted")
            rows[g] = r
            tests.append((s, g, r["p_norm"]))
        out["strategies"][s] = dict(combo=combo, rows=rows,
                                    base_blocks={b: stats([t for t in tr if in_period(t, b)]) for b, _, _ in BLOCKS})
    q = bh_total([p for _, _, p in tests], M_TOTAL)
    for (s, g, _), qq in zip(tests, q):
        r = out["strategies"][s]["rows"][g]
        if r is None:
            continue
        r["q"] = float(qq)
        r["verdict"], r["why"] = verdict(r)
    _dump(out, RESULTS / "oos.json")


def verdict(r: dict) -> tuple[str, list]:
    why = []
    k, b = r["kept"]["quoted"], r["base"]["quoted"]
    if k["n"] < 40:
        why.append(f"{k['n']} kept OOS trades < 40")
    if r["gain"]["quoted"] is None or r["gain"]["quoted"] <= 0:
        why.append("net per trade not above the no-gate arm's")
    if r.get("p_mc", 1.0) > 0.01:
        why.append(f"Monte-Carlo random-skip p {r.get('p_mc', 1.0):.3g} > 0.01")
    if r["q"] > 0.10:
        why.append(f"BH q {r['q']:.3g} > 0.10 (m {M_TOTAL})")
    for g in ("roll", "stress"):
        if r["gain"][g] is None or r["gain"][g] <= 0:
            why.append(f"no gain under {g} costs")
    if r.get("design_gain") is None or r["design_gain"] <= 0:
        why.append("no gain on design")
    if k["n"] and (k["worst_day"] < b["worst_day"] or k["max_dd"] < b["max_dd"]):
        why.append("worst day or max DD worse than no gate")
    return ("pass" if not why else "fail"), why


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    {"trades": build_trades, "design": run_design, "oos": run_oos}[cmd]()
