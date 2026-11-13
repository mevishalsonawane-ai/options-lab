"""R168 study (PREREG.md): R72 / R51 with a +Rs 2,000 target and a -Rs 1,000 stop (whole position, net), 1-3 lots.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build            # R51's clean stores (once)
    python research/hunt/r168_donchian_2k1k/r168study.py design      # design + the walk-forward year; B0 parity asserted
    python research/hunt/r168_donchian_2k1k/r168study.py final       # the ONE final read (refuses a second) + twins + gates
    python research/hunt/r168_donchian_2k1k/r168study.py picker      # second look on R151's picks (descriptive)

R152's machinery does the work (units, the rupee-level engine, the walk, B0 parity, twin rows); this file adds the
statistics, the twins' net and win-rate p values and the gates. Pure parts are tested in test_r168.py. 13 Nov 2026.
"""
from __future__ import annotations

import csv
import json
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r152_donchian_2k"))
import r152cfg as K152  # noqa: E402  (paths, R51_CLEAN=1, periods, B0 parity)

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
TARGET, STOP = 2000.0, 1000.0
LOTS = (1, 2, 3)
ARMS = ("R72", "R51")
TWINS, SEED = 500, 168
M_TOTAL = 2674          # R168's 6 + R167's m 2,664 + its 4 pre-registered G2S tests
WIN_UP, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
CAL_2025 = (date(2025, 1, 1), date(2025, 12, 31))


# ================================================================================================ pure
def stats(trades: list) -> dict:
    """Trades, win, net, average win / loss, break-even win rate (avg loss / (avg win + avg loss)), worst day, max
    drawdown (daily), exit mix."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, be_win=None, worst_day=0, max_dd=0, mix={})
    nets = np.array([float(t["net"]) for t in trades])
    w, lo = nets[nets > 0], nets[nets <= 0]
    aw = float(w.mean()) if len(w) else 0.0
    al = float(-lo.mean()) if len(lo) else 0.0
    dd = defaultdict(float)
    for t in trades:
        dd[t["day"]] += float(t["net"])
    seq = np.array([dd[d] for d in sorted(dd)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mix = Counter(t.get("why", "?") for t in trades)
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                avg_win=round(aw) if len(w) else None, avg_loss=round(al) if len(lo) else None,
                be_win=round(al / (aw + al), 4) if (aw + al) > 0 else None, worst_day=round(float(seq.min())),
                max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                mix=dict(target=mix.get("target", 0), stop1k=mix.get("stop2k", 0), stop15=mix.get("stop", 0),
                         other=sum(v for k, v in mix.items() if k not in ("target", "stop2k", "stop"))))


def between(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def twin_p(actual: float, twins) -> float:
    """p = (1 + twins >= actual) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def bh(p, m_total: int) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests (the extra ones at p = 1)."""
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


def gates(rule: dict, base: dict, q: float) -> list:
    """PREREG section 4: the failed gates (empty = passed). rule / base = {'design', 'wf', 'final'} stats."""
    fails = []
    for per in ("wf", "final"):
        r, b = rule[per], base[per]
        if r["win"] is None or b["win"] is None or r["win"] < b["win"] + WIN_UP - 1e-9:
            fails.append(f"{per}: win rate not up 5 points")
        if r["net"] < b["net"]:
            fails.append(f"{per}: net lower than today's")
    for per in ("design", "wf", "final"):
        if not rule[per]["net"] > 0:
            fails.append(f"{per}: net not positive")
    if not q <= GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    if rule["final"]["trades"] < MIN_FINAL:
        fails.append(f"< {MIN_FINAL} final trades")
    return fails


# ================================================================================================ data (R152's)
def R():
    import r152study as R152
    return R152


def plain_map(us: list, pr: list) -> dict:
    return {(t["und"], t["day"], int(c["er"]), t["side"]): t
            for (key, cands, _, _), row in zip(us, pr) for c, t in zip(cands, row) if t is not None}


def twins(trades: list, plain: dict, lots: int, rng) -> dict:
    """PREREG 4: twin net totals and win rates; p on each, the test's p the larger."""
    M = R().mods()
    ex = R().exits()
    tot = np.zeros(TWINS)
    wins = np.zeros(TWINS)
    changed = 0
    for t in trades:
        p = plain.get((t["und"], t["day"], int(t["er_sig"]), t["side"]))
        if not R().changed(t, p):
            tot += t["net"]
            wins += t["net"] > 0
            continue
        changed += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], TWINS, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        v = M["Y"].forced_exit_nets(t["und"], D, t, ex, rows, lots)
        tot += v
        wins += v > 0
    n = max(len(trades), 1)
    net = sum(t["net"] for t in trades)
    win = sum(t["net"] > 0 for t in trades) / n
    pn, pw = twin_p(net, tot), twin_p(win, wins / n)
    return dict(p=max(pn, pw), p_net=pn, p_win=pw, changed=changed, twin_mean_net=round(float(tot.mean())),
                twin_mean_win=round(float((wins / n).mean()), 4))


def run_design() -> dict:
    out = {}
    for arm in ARMS:
        us = R().units(arm, K152.START, K152.DESIGN_END)
        a = {"parity": R().parity(arm, "design", us)}
        for lots in LOTS:
            for name, tgt, stop in (("B0", None, None), ("T2S1", TARGET, STOP)):
                tr = R().walk(us, R().priced(us, tgt, stop, lots))
                a[f"{name}@{lots}"] = dict(design=stats(tr), wf=stats(between(tr, K152.WF_START, K152.DESIGN_END)),
                                           cal2025=stats(between(tr, *CAL_2025)),
                                           wf_q=[stats(between(tr, lo, hi))["net"] for lo, hi in K152.WF_Q])
                print(arm, "design", name, lots, a[f"{name}@{lots}"]["wf"], flush=True)
        out[arm] = a
    (RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def run_final() -> dict:
    if FINAL_FLAG.exists():
        raise SystemExit("R168 final already read once: " + FINAL_FLAG.read_text())
    design = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    R().mods()["C"].open_final()
    out, rows = {}, []
    for ai, arm in enumerate(ARMS):
        us = R().units(arm, K152.FINAL_START, K152.FINAL_END)
        a = {"parity": R().parity(arm, "final", us)}
        for lots in LOTS:
            pr0 = R().priced(us, None, None, lots)
            b0 = R().walk(us, pr0)
            tr = R().walk(us, R().priced(us, TARGET, STOP, lots))
            tw = twins(tr, plain_map(us, pr0), lots, np.random.default_rng([SEED, ai, lots]))
            a[f"B0@{lots}"] = dict(final=stats(b0))
            a[f"T2S1@{lots}"] = dict(final=stats(tr), twin=tw)
            rows.append((arm, lots, tw))
            print(arm, "final", lots, stats(tr), tw, flush=True)
        out[arm] = a
    q = bh([r[2]["p"] for r in rows], M_TOTAL)
    q_alone = bh([r[2]["p"] for r in rows], len(rows))
    dec = []
    with open(RESULTS / "raw_pvalues.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["study", "arm", "rule", "lots", "p", "p_net", "p_win", "final_trades", "final_win", "ref_final_win",
                    "final_net", "ref_final_net", f"q_r168_m{M_TOTAL}", "q_r168_alone"])
        for (arm, lots, tw), qq, qa in zip(rows, q, q_alone):
            d, f = design[arm], out[arm]
            rule = dict(design=d[f"T2S1@{lots}"]["design"], wf=d[f"T2S1@{lots}"]["wf"], final=f[f"T2S1@{lots}"]["final"])
            base = dict(design=d[f"B0@{lots}"]["design"], wf=d[f"B0@{lots}"]["wf"], final=f[f"B0@{lots}"]["final"])
            fails = gates(rule, base, float(qq))
            dec.append(dict(arm=arm, lots=lots, p=tw["p"], q=float(qq), q_alone=float(qa), passed=not fails, fails=fails))
            w.writerow(["R168", arm, "T2S1", lots, round(tw["p"], 6), round(tw["p_net"], 6), round(tw["p_win"], 6),
                        rule["final"]["trades"], rule["final"]["win"], base["final"]["win"], rule["final"]["net"],
                        base["final"]["net"], round(float(qq), 6), round(float(qa), 6)])
    (RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    (RESULTS / "decision.json").write_text(json.dumps(dict(m_total=M_TOTAL, tests=dec), indent=1, default=str))
    return out


def run_picker() -> dict:
    assert FINAL_FLAG.exists(), "the second look follows R168's one final read"
    import r152picker as PK
    R().mods()["C"].open_final()
    out = {}
    for arm in ARMS:
        us = R().units(arm, K152.FINAL_START, K152.FINAL_END)
        sc, thr = PK.scores(arm, us)
        a = {}
        for lots in LOTS:
            for name, tgt, stop in (("B0", None, None), ("T2S1", TARGET, STOP)):
                tr = [t for t in R().walk(us, R().priced(us, tgt, stop, lots)) if PK.kept(t, sc, thr)]
                a[f"{name}@{lots}"] = stats(tr)
        out[arm] = a
        print(arm, "picker", a, flush=True)
    (RESULTS / "picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    RESULTS.mkdir(parents=True, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"design": run_design, "final": run_final, "picker": run_picker}.get(cmd, lambda: sys.exit(__doc__))()
