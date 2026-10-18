"""R80 study (PREREG.md): ten entry filters on Liquidity 15+5 (BANKNIFTY first; NIFTY / SENSEX for a pass).
Research only. 18 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build BANKNIFTY NIFTY SENSEX   # R51's clean stores
    python research/hunt/r80/r80study.py design
    python research/hunt/r80/r80study.py final          # the ONE final read (refuses a second)

Every candidate signal is simulated once on the R51 engine (fam_liq's exits and forced index-exit rows); B0 and each
filter then walk the books from those results, so a removed signal frees its book exactly as the app would. B0 is
asserted equal to fam_liq.make(...).run trade by trade. Trades go to $R80_OUT (never git); results/ holds small JSON.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
os.environ["R51_CLEAN"] = "1"
for _d in ("r51", "r64"):
    sys.path.insert(0, str(HERE.parent / _d))
sys.path.insert(0, str(HERE))
import r51cfg as K  # noqa: E402
import common as C  # noqa: E402
import engine as E  # noqa: E402
import fam_liq  # noqa: E402
import r51data  # noqa: E402
import r64study as P  # noqa: E402  (stats, paired_boot)
import r80rules as R  # noqa: E402

OUT = Path(os.environ.get("R80_OUT", K.CACHE_ROOT / "r80")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
BOOKS = (15, 5)
IDX_STOP = {"BANKNIFTY": 30.0, "NIFTY": 15.0, "SENSEX": 50.0}       # h4 IDX_STOP_EXT, a priori
TWINS, SEED, BOOT = 500, 80, 2000
GATE_P, GATE_Q, MIN_FINAL = 0.10, 0.10, 40
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
for _u, _s in IDX_STOP.items():
    fam_liq.IDX_STOP.setdefault(_u, _s)


# ================================================================================================ signals
def fh_flags(days: dict) -> dict:
    """{day: V7 gate} from each session's first-hour range against the previous 20 sessions' (all store sessions)."""
    hist, out = [], {}
    for d in sorted(days):
        x = R.first_hour_range(days[d].idx)
        out[d] = R.fh_gate(x, hist)
        hist.append(x)
    return out


def signals(und: str, tf: int, room: float = 1.0) -> dict:
    """As fam_liq.signals (same days, same 10-day history, same rows) with R80's features on every row."""
    days = r51data.load(und)
    step, istop, unit = K.STEP[und], IDX_STOP[und], R.ROUND[und]
    fh = fh_flags(days)
    hist, out, prev = [], {}, None
    for d in sorted(days):
        D = days[d]
        a = D.idx
        ok = np.isfinite(a[3])
        today1 = [(d, R.OPEN_M + r, float(a[0, r]), float(a[1, r]), float(a[2, r]), float(a[3, r]))
                  for r in np.flatnonzero(ok)]
        hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
        if d >= K.START and D.atm is not None and not D.expiry and hist:
            ones = [b for hh in hist for b in hh[1]] + today1
            bars = fam_liq.H4.fold(ones, tf)
            if len(bars) >= 2 * fam_liq.H4.L + 2:
                rows = R.day_signals(fam_liq.H4, d, bars, tf, istop, step, room, unit, prev)
                for s in rows:
                    s["f_fh"] = fh[d]
                out[d] = rows
        if ok.any():
            prev = (float(np.nanmax(a[1])), float(np.nanmin(a[2])))
        hist.append((d, today1))
    return out


def candidates(und: str, lo: date, hi: date) -> list:
    """[(day, book, cands, res)] for every tradable day: each candidate simulated once with fam_liq's exits."""
    days = r51data.load(und)
    out = []
    for tf in BOOKS:
        sig = signals(und, tf)
        for d in C.days_in(und, lo, hi):
            if d not in sig or not sig[d]:
                continue
            D = days[d]
            cands = [dict(s, fx=fam_liq.forced_row(D, s), book=tf) for s in sig[d]]
            res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands],
                            [c["er"] for c in cands], fam_liq.EX, [c["fx"] for c in cands])
            out.append((d, tf, cands, res))
    return out


# ================================================================================================ variants
def run_variant(groups: list, v: str | None, drop: set | None = None) -> list:
    """Trades of B0 (v None), a filter V1-V8, or V9 / V10 after the walk. `drop` = candidate ids (g, j) removed at
    random (twins)."""
    trades = []
    for g, (d, tf, cands, res) in enumerate(groups):
        keep = [True] * len(cands)
        if v is not None and v not in R.POST_WALK:
            keep = [R.keep_signal(v, c) for c in cands]
        if drop:
            keep = [k and (g, j) not in drop for j, k in enumerate(keep)]
        trades += R.walk(cands, res, keep)
    if v in R.POST_WALK:
        by = defaultdict(list)
        for t in trades:
            by[(t["und"], t["day"])].append(t)
        trades = [t for k in sorted(by) for t in R.post_walk(v, by[k])]
    for t in trades:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    trades.sort(key=lambda t: (t["day"], t["und"], t["er"], t["book"]))
    return trades


def removed_signals(groups: list, v: str) -> int:
    return sum(1 for (_, _, cands, res) in groups for c in cands if not R.keep_signal(v, c))


def twin_totals(groups: list, v: str, b0: list, vt: list, n: int, rng) -> np.ndarray:
    """Random-skip twins: drop as many candidate signals at random as the filter dropped (V9 / V10: as many of B0's
    trades), walk again, total net."""
    tot = np.zeros(n)
    if v in R.POST_WALK:
        k = len(b0) - len(vt)
        nets = np.array([t["net"] for t in b0])
        for i in range(n):
            idx = rng.choice(len(nets), size=k, replace=False) if k else []
            tot[i] = nets.sum() - nets[idx].sum()
        return tot
    ids = [(g, j) for g, (_, _, cands, _) in enumerate(groups) for j in range(len(cands))]
    k = removed_signals(groups, v)
    for i in range(n):
        pick = rng.choice(len(ids), size=k, replace=False) if k else []
        tot[i] = sum(t["net"] for t in run_variant(groups, None, {ids[x] for x in pick}))
    return tot


def top5_less(trades: list) -> float:
    net = sorted((t["net"] for t in trades), reverse=True)
    return float(sum(net) - sum(net[:5]))


def split(trades, lo, hi):
    return [t for t in trades if lo <= t["day"] <= hi]


def summary(trades: list) -> dict:
    s = P.stats(trades)
    keep = ("trades", "days", "win", "net", "per_trade", "worst_day", "max_dd", "ex_top5", "top5_share", "stops",
            "targets", "time", "squareoff", "rule_exit")
    return {k: s.get(k) for k in keep}


def evaluate(und: str, groups: list, phase: str, twins: bool = True) -> dict:
    b0 = run_variant(groups, None)
    out = {"B0": {"all": summary(b0)}, "n_candidates": sum(len(g[2]) for g in groups)}
    tr = {"B0": b0}
    if phase == "design":
        out["B0"]["wf"] = summary(split(b0, WF_START, K.DESIGN_END))
        out["B0"]["wf_q"] = [summary(split(b0, a, b))["net"] for a, b in WF_Q]
    for v in R.VARIANTS:
        vt = run_variant(groups, v)
        tr[v] = vt
        r = {"all": summary(vt), "removed_trades": len(b0) - len(vt),
             "removed_signals": None if v in R.POST_WALK else removed_signals(groups, v),
             "diff": round(sum(t["net"] for t in vt) - sum(t["net"] for t in b0)),
             "top5_diff": round(top5_less(vt) - top5_less(b0))}
        if phase == "design":
            r["wf"] = summary(split(vt, WF_START, K.DESIGN_END))
            r["wf_diff"] = round(r["wf"]["net"] - out["B0"]["wf"]["net"])
            r["wf_q_diff"] = [round(summary(split(vt, a, b))["net"] - q) for (a, b), q in zip(WF_Q, out["B0"]["wf_q"])]
        pb = P.paired_boot(b0, vt, BOOT, SEED)
        r["boot_p"] = pb[0]
        if twins:
            tw = twin_totals(groups, v, b0, vt, TWINS, np.random.default_rng(SEED))
            act = sum(t["net"] for t in vt)
            r["twin_p"] = round((1 + int((tw >= act).sum())) / (TWINS + 1), 4)
            r["twin_mean"] = round(float(tw.mean()))
        out[v] = r
        print(und, phase, v, r["all"]["trades"], r["all"]["net"], "diff", r["diff"], "twin_p", r.get("twin_p"),
              flush=True)
    _save(f"{und}_{phase}", tr)
    return out


# ================================================================================================ decision
def decide(bn_design: dict, bn_final: dict) -> dict:
    """PREREG section 5 on BANKNIFTY, BH over the 10 final twin p."""
    ps = [bn_final[v].get("twin_p", 1.0) for v in R.VARIANTS]
    q = P.bh(ps)
    out = {}
    for v, qq in zip(R.VARIANTS, q):
        f, d = bn_final[v], bn_design[v]
        fails = []
        if d["wf_diff"] <= 0:
            fails.append("walk-forward not above B0")
        if f["diff"] <= 0:
            fails.append("final not above B0")
        if f["twin_p"] >= GATE_P:
            fails.append("twin p >= 0.10")
        if qq >= GATE_Q:
            fails.append("BH q >= 0.10")
        if f["top5_diff"] <= 0:
            fails.append("carried by top 5")
        if f["all"]["trades"] < MIN_FINAL:
            fails.append("< 40 final trades")
        out[v] = dict(q=round(float(qq), 4), passed=not fails, fails=fails)
    return out


# ================================================================================================ helpers / main
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def check_b0(und: str, groups: list, lo: date, hi: date):
    """B0 from the walk must equal fam_liq.make(und, (15, 5)).run trade by trade (same order by day, book, entry)."""
    ref = fam_liq.make(und, BOOKS).run(lo, hi).to_dict("records")
    mine = run_variant(groups, None)
    key = lambda t: (str(t["day"])[:10], t["book"], t["er"])  # noqa: E731
    a, b = sorted(ref, key=key), sorted(mine, key=key)
    assert len(a) == len(b), (und, len(a), len(b))
    assert all(abs(x["net"] - y["net"]) < 1e-6 and x["er"] == y["er"] for x, y in zip(a, b)), und


def run(phase: str, unds=("BANKNIFTY", "NIFTY", "SENSEX")) -> dict:
    if phase == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("R80 final period already read once: " + FINAL_FLAG.read_text())
        RESULTS.mkdir(parents=True, exist_ok=True)
        FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        C.open_final()
        lo, hi = K.FINAL_START, K.END
    else:
        lo, hi = K.START, K.DESIGN_END
    res = {}
    for und in unds:
        groups = candidates(und, lo, hi)
        check_b0(und, groups, lo, hi)
        res[und] = evaluate(und, groups, phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{phase}.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    ph = sys.argv[1]
    if ph in ("design", "final"):
        run(ph, tuple(sys.argv[2:]) or ("BANKNIFTY", "NIFTY", "SENSEX"))
    elif ph == "decide":
        dz, fz = (json.loads((RESULTS / f"{p}.json").read_text()) for p in ("design", "final"))
        dec = {u: decide(dz[u], fz[u]) for u in dz if u in fz}
        (RESULTS / "decision.json").write_text(json.dumps(dec, indent=1))
        print(json.dumps(dec["BANKNIFTY"], indent=1))
