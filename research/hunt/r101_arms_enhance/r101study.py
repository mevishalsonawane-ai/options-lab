"""R101 study (PREREG.md): entry filters and one sizing rule on Liquidity 15+5, R72 Breadth Donchian, R51 Donchian 20
and R52 Open outside value, gated as R80 / R51, win rate and net side by side.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build NIFTY BANKNIFTY FINNIFTY MIDCPNIFTY SENSEX   # the stores
    python research/hunt/r101_arms_enhance/r101study.py design [ARM ...]   # design + walk-forward, B0 parity asserted
    python research/hunt/r101_arms_enhance/r101study.py final  [ARM ...]   # the ONE final read (refuses a second)
    python research/hunt/r101_arms_enhance/r101study.py report             # results/*.json -> results/report.md

Pure parts (walks, keep rules, sizing, gates) take plain lists and dicts; test_r101.py covers them.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone

import numpy as np

import r101cfg as K
import r101feat as F

# ================================================================================================ walks (pure)
def walk_book(cands: list, res: list, keep=None) -> list:
    """One book's day (R80.walk): candidates in time order; a kept, filled candidate is taken when the book is free."""
    out, busy = [], -1
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        if c["er"] <= busy:
            continue
        busy = r["xr"]
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def walk_side2(cands: list, res: list, keep=None, max_per_side: int = 2) -> list:
    """One index's day (r64engine.take, scope 'side'): one open position a side, at most max_per_side fills a side."""
    out, busy, n_side = [], {}, {}
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        if c["er"] <= busy.get(c["side"], -1):
            continue
        if n_side.get(c["side"], 0) >= max_per_side:
            continue
        busy[c["side"]] = r["xr"]
        n_side[c["side"]] = n_side.get(c["side"], 0) + 1
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def walk_one(cands: list, res: list, keep=None) -> list:
    """One a day: the first kept, filled candidate."""
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        return [t]
    return []


WALK = {"book": walk_book, "side2": walk_side2, "one": walk_one}


# ================================================================================================ keep rules (pure)
def keep_signal(v: str, c: dict, dow_drop=None) -> bool:
    """PREREG section 2: does variant v keep candidate c? An unknown input never confirms (the signal is dropped)."""
    if v in ("B1", "C1", "D1"):
        return c.get("f_vixreg") == "LO"
    if v in ("B1b", "C1b", "D1b"):
        return c.get("f_vixreg") == "HI"
    if v in ("B2", "C2"):
        g = c.get("f_gap", float("nan"))
        return bool(np.isfinite(g) and abs(g) <= K.GAP_MAX)
    if v in ("B3", "C3"):
        return c["er"] != K.OPEN_ROW
    if v in ("B4", "C4"):
        return c["er"] <= K.NOON_ROW
    if v == "L1":
        return c.get("f_breadth", 0) >= K.BREADTH_MIN
    if v == "L2":
        return F.vix_confirms(c.get("f_vixchg", float("nan")), c["side"])
    if v == "L3":
        r = c.get("f_rv", float("nan"))
        return bool(np.isfinite(r) and r >= 0.5)
    if v == "L3b":
        r = c.get("f_rv", float("nan"))
        return bool(np.isfinite(r) and r < 0.5)
    if v == "L4":
        return dow_drop is None or c["f_dow"] != dow_drop
    if v == "D2":
        f = c.get("f_far", float("nan"))
        return bool(np.isfinite(f) and f >= K.FAR_MIN)
    if v == "D3":
        return c.get("f_open3", 0) >= K.BREADTH_MIN
    if v == "B5":
        return True
    raise ValueError(v)


# ================================================================================================ variants
def run_variant(units: list, v: str | None, drop: set | None = None, dow_picks: dict | None = None) -> list:
    """Trades of B0 (v None) or a filter, from the priced units; `drop` = candidate ids (g, j) removed at random."""
    trades = []
    for g, (key, cands, res, how) in enumerate(units):
        keep = [True] * len(cands)
        if v is not None and v not in K.SIZING:
            dd = F.dow_drop_for(key[1], dow_picks) if (v == "L4" and dow_picks) else None
            keep = [keep_signal(v, c, dd) for c in cands]
        if drop:
            keep = [k and (g, j) not in drop for j, k in enumerate(keep)]
        trades += WALK[how](cands, res, keep)
    for t in trades:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    trades.sort(key=lambda t: (t["day"], t["und"], t["er"], t.get("book", 0)))
    return trades


def removed_signals(units: list, v: str, dow_picks=None) -> int:
    n = 0
    for key, cands, res, how in units:
        dd = F.dow_drop_for(key[1], dow_picks) if (v == "L4" and dow_picks) else None
        n += sum(1 for c in cands if not keep_signal(v, c, dd))
    return n


# ------------------------------------------------------------------------------------------------ B5 sizing (pure)
def sized(trades: list, lots_of, charges_fn) -> list:
    """B5: each trade at lots_of(t) lots; net = gross x lots - charges at that quantity (brokerage is per order, so it
    does not double). Adds 'lots' and 'net1' (the 1-lot net kept for reference)."""
    out = []
    for t in trades:
        n = lots_of(t)
        q = t["qty"] * n
        ch = float(charges_fn(t["und"], np.array([t["rE"]]), np.array([t["rX"]]), q, t["day"])[0])
        s = dict(t, lots=n, net1=t["net"], net=(t["rX"] - t["rE"]) * q - ch)
        out.append(s)
    return out


def per_lot(trades: list) -> float:
    lots = sum(t.get("lots", 1) for t in trades)
    return float(sum(t["net"] for t in trades) / lots) if lots else 0.0


def sizing_twins(b0: list, n_extra: int, charges_fn, n: int, rng) -> np.ndarray:
    """500 random assignments of the extra lot to n_extra of B0's trades: per-lot nets."""
    out = np.zeros(n)
    nets1 = np.array([t["net"] for t in b0])
    two = np.array([(t["rX"] - t["rE"]) * t["qty"] * 2 - float(charges_fn(t["und"], np.array([t["rE"]]), np.array([t["rX"]]), t["qty"] * 2, t["day"])[0])
                    for t in b0])
    for i in range(n):
        pick = rng.choice(len(b0), size=n_extra, replace=False) if n_extra else np.array([], int)
        tot = nets1.sum() - nets1[pick].sum() + two[pick].sum()
        out[i] = tot / (len(b0) + n_extra) if len(b0) + n_extra else 0.0
    return out


# ================================================================================================ twins, stats, gates
def twin_totals(units: list, v: str, n: int, rng, dow_picks=None) -> np.ndarray:
    """Random-skip twins: drop as many candidate signals at random as the filter dropped, walk again, total net."""
    ids = [(g, j) for g, (_, cands, _, _) in enumerate(units) for j in range(len(cands))]
    k = removed_signals(units, v, dow_picks)
    tot = np.zeros(n)
    for i in range(n):
        pick = rng.choice(len(ids), size=k, replace=False) if k else []
        tot[i] = sum(t["net"] for t in run_variant(units, None, {ids[x] for x in pick}))
    return tot


def stats(trades: list) -> dict:
    """Trades, win rate, net, per trade, worst day, max drawdown, net without the 5 best, top-5 share (pure)."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0.0, per_trade=None, worst_day=None, max_dd=None, ex_top5=0.0, top5_share=None)
    nets = np.array([t["net"] for t in trades])
    daily = defaultdict(float)
    for t in trades:
        daily[t["day"]] += t["net"]
    seq = np.array([daily[d] for d in sorted(daily)])
    eq = np.cumsum(seq)
    dd = float((np.maximum.accumulate(eq) - eq).max()) if len(eq) else 0.0
    top5 = float(np.sort(nets)[::-1][:5].sum())
    net = float(nets.sum())
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(net), per_trade=round(net / n),
                worst_day=round(float(seq.min())), max_dd=round(dd), ex_top5=round(net - top5),
                top5_share=(round(top5 / net, 3) if net > 0 else None), days=len(daily))


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * n / rank)
        q[i] = prev
    return q


def split(trades, lo, hi):
    return [t for t in trades if lo <= t["day"] <= hi]


def gates(v: str, d: dict, f: dict, q: float) -> dict:
    """PREREG section 3: all seven must hold. d / f: the variant's design / final entries (with B0 figures)."""
    fails = []
    if d.get("wf_diff", 0) <= 0:
        fails.append("walk-forward not above B0")
    if f.get("diff", 0) <= 0:
        fails.append("final not above B0")
    if f.get("twin_p", 1.0) >= K.GATE_P:
        fails.append("twin p >= 0.05")
    if q >= K.GATE_Q:
        fails.append("BH q >= 0.10")
    if (f.get("all", {}).get("trades") or 0) < K.MIN_FINAL:
        fails.append("< 40 final trades")
    if f.get("top5_diff", 0) <= 0:
        fails.append("carried by its 5 best trades")
    return dict(q=round(float(q), 4), passed=not fails, fails=fails)


# ================================================================================================ one arm, one phase
def dow_picks_for(b0: list, phase: str) -> dict:
    """L4's walk-forward picks: each design quarter uses the worst weekday on the B0 trades before it; the final uses
    the pick on all design days; nothing before the walk-forward window (no filter there)."""
    if phase == "final":
        return {(K.FINAL_START, K.END): F.worst_weekday(b0, K.FINAL_START)}
    return {q: F.worst_weekday(b0, q[0]) for q in K.WF_Q}


def evaluate(arm: str, units: list, phase: str, design_b0: list | None = None) -> tuple:
    """Every variant of `arm` on `units`: stats, differences to B0, twins. Returns (results dict, trades by variant)."""
    import engine as E
    b0 = run_variant(units, None)
    out = {"B0": {"all": stats(b0)}, "n_candidates": sum(len(u[1]) for u in units)}
    tr = {"B0": b0}
    if phase == "design":
        out["B0"]["wf"] = stats(split(b0, K.WF_START, K.DESIGN_END))
        out["B0"]["wf_q"] = [stats(split(b0, a, b))["net"] for a, b in K.WF_Q]
    picks = dow_picks_for(design_b0 if design_b0 is not None else b0, phase) if "L4" in K.VARIANTS[arm] else None
    rng = np.random.default_rng(K.SEED)
    for v in K.VARIANTS[arm]:
        if v in K.SIZING:
            vt = sized(b0, lambda t: K.SIZE55_LOTS if t.get("f_count") == 5 else 1, E.charges)
            n_extra = sum(1 for t in vt if t["lots"] > 1)
            r = {"all": stats(vt), "lots": sum(t["lots"] for t in vt), "extra_lot_trades": n_extra,
                 "per_lot": round(per_lot(vt)), "b0_per_lot": round(per_lot(b0)),
                 "at_b0_lots": round(per_lot(vt) * len(b0)), "diff": round(per_lot(vt) * len(b0) - sum(t["net"] for t in b0)),
                 "top5_diff": round((stats(vt)["ex_top5"] / max(sum(t["lots"] for t in vt), 1)) * len(b0) - stats(b0)["ex_top5"])}
            if phase == "design":
                wv, wb = split(vt, K.WF_START, K.DESIGN_END), split(b0, K.WF_START, K.DESIGN_END)
                r["wf"] = stats(wv); r["wf_per_lot"] = round(per_lot(wv))
                r["wf_diff"] = round(per_lot(wv) * len(wb) - sum(t["net"] for t in wb))
            tw = sizing_twins(b0, n_extra, E.charges, K.TWINS, rng)
            r["twin_p"] = round((1 + int((tw >= per_lot(vt)).sum())) / (K.TWINS + 1), 4)
            r["twin_mean_per_lot"] = round(float(tw.mean()))
        else:
            vt = run_variant(units, v, dow_picks=picks)
            r = {"all": stats(vt), "removed_trades": len(b0) - len(vt), "removed_signals": removed_signals(units, v, picks),
                 "diff": round(sum(t["net"] for t in vt) - sum(t["net"] for t in b0)),
                 "top5_diff": round(stats(vt)["ex_top5"] - stats(b0)["ex_top5"])}
            if v == "L4":
                r["dow_picks"] = {f"{a}..{b}": wd for (a, b), wd in picks.items()}
            if phase == "design":
                r["wf"] = stats(split(vt, K.WF_START, K.DESIGN_END))
                r["wf_diff"] = round(r["wf"]["net"] - out["B0"]["wf"]["net"])
                r["wf_q_diff"] = [round(stats(split(vt, a, b))["net"] - q) for (a, b), q in zip(K.WF_Q, out["B0"]["wf_q"])]
            tw = twin_totals(units, v, K.TWINS, rng, picks)
            act = sum(t["net"] for t in vt)
            r["twin_p"] = round((1 + int((tw >= act).sum())) / (K.TWINS + 1), 4)
            r["twin_mean"] = round(float(tw.mean()))
        tr[v] = vt
        out[v] = r
        print(arm, phase, v, r["all"]["trades"], "win", r["all"]["win"], "net", r["all"]["net"], "diff", r["diff"],
              "twin_p", r.get("twin_p"), flush=True)
    return out, tr


def check_b0(arm: str, b0: list, phase: str, units: list):
    """PREREG section 1: B0 must reproduce the published figure (Liquidity trade by trade against fam_liq; the others
    by trades and net). A mismatch stops the study and is reported, never silently accepted."""
    import r101arms as A
    n, net = len(b0), round(sum(t["net"] for t in b0))
    pub = K.PUBLISHED[arm][phase]
    if arm == "LIQ":
        lo, hi = (K.START, K.DESIGN_END) if phase == "design" else (K.FINAL_START, K.END)
        ref = A.liq_reference(lo, hi)
        key = lambda t: (str(t["day"])[:10], t["und"], t["book"], t["er"])  # noqa: E731
        a, b = sorted(ref, key=key), sorted(b0, key=key)
        assert len(a) == len(b), ("LIQ B0 vs fam_liq", len(a), len(b))
        assert all(abs(x["net"] - y["net"]) < 1e-6 for x, y in zip(a, b)), "LIQ B0 nets differ from fam_liq"
    ok = (pub[0] is None or pub[0] == n) and abs(pub[1] - net) <= 1
    print(f"{arm} B0 {phase}: {n} trades, net {net} (published {pub}) -> {'reproduced' if ok else 'DIFFERS'}", flush=True)
    return dict(trades=n, net=net, published=list(pub), reproduced=ok)


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def run(phase: str, arms=K.ARMS) -> dict:
    import r101arms as A
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R101 final period already read once: " + K.FINAL_FLAG.read_text())
        K.RESULTS.mkdir(parents=True, exist_ok=True)
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        lo, hi = K.FINAL_START, K.END
    else:
        lo, hi = K.START, K.DESIGN_END
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    for arm in arms:
        units = A.UNITS[arm](lo, hi)
        b0 = run_variant(units, None)
        parity = check_b0(arm, b0, phase, units)
        design_b0 = _load(f"{arm}_design")["B0"] if phase == "final" and "L4" in K.VARIANTS[arm] else None
        out, tr = evaluate(arm, units, phase, design_b0)
        out["parity"] = parity
        _save(f"{arm}_{phase}", tr)
        _save(f"{arm}_{phase}_units", units)              # the priced candidates (so a report never needs a re-run)
        # one file per arm (the arms run in parallel processes), merged into {phase}.json by merge()
        (K.RESULTS / f"{phase}_{arm}.json").write_text(json.dumps(out, indent=1, default=str))
    return merge(phase)


def merge(phase: str) -> dict:
    """{phase}.json from the per-arm files (an arm without one keeps its entry in the combined file)."""
    path = K.RESULTS / f"{phase}.json"
    res = json.loads(path.read_text()) if path.exists() else {}
    for arm in K.ARMS:
        p = K.RESULTS / f"{phase}_{arm}.json"
        if p.exists():
            res[arm] = json.loads(p.read_text())
    path.write_text(json.dumps(res, indent=1, default=str))
    return res


def decide() -> dict:
    """PREREG section 3: BH over all 20 final twin p (and per arm, reported), then the seven gates."""
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    tests = [(a, v) for a in K.ARMS for v in K.VARIANTS[a] if a in f and v in f[a]]
    ps = [f[a][v].get("twin_p", 1.0) for a, v in tests]
    q_all = bh(ps)
    out = {}
    for arm in K.ARMS:
        if arm not in f:
            continue
        vs = [v for a, v in tests if a == arm]
        q_arm = bh([f[arm][v].get("twin_p", 1.0) for v in vs])
        for v, qa in zip(vs, q_arm):
            qq = float(q_all[tests.index((arm, v))])
            g = gates(v, d[arm][v], f[arm][v], qq)
            g["q_arm"] = round(float(qa), 4)
            out[f"{arm}:{v}"] = g
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


# ================================================================================================ report
def rs(x) -> str:
    if x is None:
        return "-"
    x = int(round(x))
    s = f"{abs(x):,}"
    return ("-" if x < 0 else "+" if x > 0 else "") + "Rs " + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def table(arm: str, res: dict, phase: str, dec: dict | None) -> list:
    lines = [f"| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | "
             + ("walk-forward net | wf vs B0 | " if phase == "design" else "")
             + "twin p | " + ("q (all) | q (arm) | verdict |" if dec else "|"),
             "|---|---|---|---|---|---|---|---|---|" + ("---|---|" if phase == "design" else "") + "---|" + ("---|---|---|" if dec else "")]
    b0 = res["B0"]["all"]
    row = f"| **B0** | {b0['trades']} | {pct(b0['win'])} | {rs(b0['net'])} | | {rs(b0['per_trade'])} | {rs(b0['worst_day'])} | {rs(-b0['max_dd']) if b0['max_dd'] is not None else '-'} | {rs(b0['ex_top5'])} | "
    if phase == "design":
        row += f"{rs(res['B0']['wf']['net'])} | | "
    row += "| " + ("| | |" if dec else "")
    lines.append(row)
    for v in K.VARIANTS[arm]:
        if v not in res:
            continue
        r = res[v]; a = r["all"]
        net = rs(a["net"]) + (f" ({r['lots']} lots; {rs(r['per_lot'])} a lot vs {rs(r['b0_per_lot'])})" if v in K.SIZING else "")
        row = f"| {v} {K.LABEL[v]} | {a['trades']} | {pct(a['win'])} | {net} | {rs(r['diff'])}{' at B0 lots' if v in K.SIZING else ''} | {rs(a['per_trade'])} | {rs(a['worst_day'])} | {rs(-a['max_dd']) if a['max_dd'] is not None else '-'} | {rs(a['ex_top5'])} | "
        if phase == "design":
            row += f"{rs(r['wf']['net'])} | {rs(r['wf_diff'])} | "
        row += f"{r.get('twin_p', '-')} | "
        if dec:
            g = dec.get(f"{arm}:{v}", {})
            row += f"{g.get('q', '-')} | {g.get('q_arm', '-')} | {'**PASS**' if g.get('passed') else 'fail: ' + '; '.join(g.get('fails', []))} |"
        lines.append(row)
    return lines


def report() -> str:
    d = json.loads((K.RESULTS / "design.json").read_text())
    fpath = K.RESULTS / "final.json"
    f = json.loads(fpath.read_text()) if fpath.exists() else {}
    dec = decide() if f else None
    names = {"LIQ": "Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5)", "R72": "R72 Breadth Donchian",
             "R51": "R51 Donchian 20", "R52": "R52 Open outside value"}
    L = ["# R101 report: entry filters and sizing on the arms with a real record (tables)", "",
         "Net after charges and slippage, 1 lot (B5: as stated). Win rate and net side by side; a higher win rate with a lower net fails.", ""]
    for arm in K.ARMS:
        if arm not in d:
            continue
        L += [f"## {names[arm]}", ""]
        p = d[arm].get("parity", {})
        L += [f"B0 parity (design): {p.get('trades')} trades, {rs(p.get('net'))}, published {p.get('published')}: "
              f"{'reproduced' if p.get('reproduced') else 'DIFFERS'}", ""]
        L += ["### Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)", ""] + table(arm, d[arm], "design", None) + [""]
        if arm in f:
            p = f[arm].get("parity", {})
            L += [f"B0 parity (final): {p.get('trades')} trades, {rs(p.get('net'))}, published {p.get('published')}: "
                  f"{'reproduced' if p.get('reproduced') else 'DIFFERS'}", ""]
            L += ["### Final 1 Apr - 13 Oct 2026 (read once)", ""] + table(arm, f[arm], "final", dec) + [""]
    if dec:
        passed = [k for k, g in dec.items() if g["passed"]]
        L += ["## Decision", "", f"{len(passed)} of {len(dec)} pass every gate" + (": " + ", ".join(passed) if passed else "."), ""]
    text = "\n".join(L)
    (K.RESULTS / "report.md").write_text(text)
    return text


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    arms = tuple(sys.argv[2:]) or K.ARMS
    if cmd in ("design", "final"):
        run(cmd, arms)
    elif cmd == "report":
        print(report())
    else:
        raise SystemExit(__doc__)
