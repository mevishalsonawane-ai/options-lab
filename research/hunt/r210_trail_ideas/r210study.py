"""R210 study (PREREG.md): the hybrid trail (index and premium), the Chandelier exit and a higher-timeframe filter on R72,
R51 and Liquidity 15+5, against each arm's CURRENT exits (V5), 1 lot.

    python3 -I research/hunt/r210_trail_ideas/r210study.py design    # repro + design years (read once) -> results/design.json
    python3 -I research/hunt/r210_trail_ideas/r210study.py decide    # BH + the gate -> results/decision.json
    python3 -I research/hunt/r210_trail_ideas/r210study.py holdout   # survivors only, read once -> results/holdout.json
    python3 -I research/hunt/r210_trail_ideas/r210study.py report    # results/report.md

Reuses R193's stack unchanged (r193study: units, walk, the V5 pricing through r186engine.price; R151's picker through
R152's module) and R186's engine (r186engine.price with a moved 'fx' row: the new index exits are sold at the open of the
next minute exactly as the arms' own index exits are). Pure parts in r210lib.py. Plain-English notes, 24 Nov 2026.
"""
from __future__ import annotations

import dataclasses
import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r210cfg as K  # noqa: E402

import r193study as R3  # noqa: E402  (sets R193's environment and imports the stack)
import numpy as np  # noqa: E402

import r193lib as G  # noqa: E402
import r186engine as Z  # noqa: E402  (on the path through r193study)
import r210lib as L  # noqa: E402

S6, R7 = R3.S6, R3.R7
_IX: dict = {}


# ================================================================================================ plumbing
def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def flag(name: str, result: str) -> None:
    """PREREG 8: each read happens once. A crash before its results file is written may be re-run unchanged."""
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    f, res = K.RESULTS / f"READ_{name}", K.RESULTS / result
    if f.exists() and res.exists():
        raise SystemExit(f"R210 {name} already read once: {f.read_text().strip()}")
    if not f.exists():
        f.write_text(now() + "\n")


def boot() -> dict:
    return R3.boot()


def write(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def save(name: str, obj) -> None:
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


# ================================================================================================ index bars (cached)
def index_bars(und: str) -> dict:
    """{pos: {day: i}, S5, atr5, sw5, S15, ema15, S60, ema60} over every session of the store (oldest first), so ATR and
    EMA warm up on earlier sessions. Rows on the 09:15 grid; each bar is used only after its last minute."""
    if und not in _IX:
        days = R7.mods()["r51data"].load(und)
        ks = sorted(days, key=S6._d)
        arrs = [np.asarray(days[d].idx, float) for d in ks]
        S5 = L.series(arrs, 5)
        S15 = L.series(arrs, 15)
        S60 = L.series(arrs, 60)
        _IX[und] = dict(pos={S6._d(d): i for i, d in enumerate(ks)}, S5=S5,
                        atr5=L.atr(S5["h"], S5["l"], S5["c"], K.ATR_N), sw5=L.swings(S5["h"], S5["l"], K.SWING_K),
                        S15=S15, ema15=L.ema(S15["c"], K.EMA_N), S60=S60, ema60=L.ema(S60["c"], K.EMA_N))
    return _IX[und]


# ================================================================================================ the exit variants
def exits_for(arm: str, kind: str):
    ex = S6.exits(arm)
    if kind == "HUNT":
        ex = dataclasses.replace(ex, tmax=-1, need=-1.0)
    return ex


def moved_units(arm: str, usA: list, kind: str) -> tuple:
    """usA with each candidate's 'fx' moved to the new exit's row when that comes first (fx_arm, the arm's own index exit,
    kept; first exit wins). kind HU / HUNT / HP (hybrid stages 2-3; stage 1 is the engine's breakeven at +1R) or CH.
    Returns (units, {cand key: new row}, how many candidates got a row)."""
    M = R7.mods()
    E, X, K51 = Z.E, Z.X, Z.K51
    ex = exits_for(arm, kind)
    r = float(ex.stop)                                       # 1R = the arm's own -15% stop
    out, rows, n_row = [], {}, 0
    for key, cands, res, how in usA:
        und, d = key[0], key[1]
        D = M["r51data"].load(und)[d]
        st = E.stack(und, D)
        sq = int(E.sq_row(D, ex))
        qty = float(K51.lot(und, D.d))
        ix = index_bars(und)
        di = ix["pos"][S6._d(d)]
        nc = []
        for c in cands:
            c2 = dict(c)
            ci = st.idx(c["side"], c["strike"])
            row = -1
            if ci >= 0:
                C = st.S[ci]
                raw, real, ef, tks = X.entry_info(C, int(c["er"]), sq, qty)
                if ef >= 0:
                    long = c["side"] == "C"
                    if kind == "CH":
                        row = L.chandelier_row(ix["S5"], ix["atr5"], di, ef, sq, D.idx[1], D.idx[2], long, K.CHAND_K)
                    else:
                        t2, t3 = L.stage_rows(C[1], ef, sq, raw, r)
                        if kind == "HP":                     # the option's own 5-minute bars, this session only
                            P = L.series([np.asarray(C[:4], float)], 5)
                            aP = L.atr(P["h"], P["l"], P["c"], K.ATR_N, K.ATR_MIN_PREMIUM)
                            row = L.hybrid_row(P, aP, 0, ef, sq, True, t2, t3, K.HYB_ATR_K, K.SWING_K)
                        else:
                            row = L.hybrid_row(ix["S5"], ix["atr5"], di, ef, sq, long, t2, t3, K.HYB_ATR_K, K.SWING_K,
                                               ix["sw5"])
            if row >= 0:
                n_row += 1
                c2["fx"] = L.earlier(int(c2.get("fx_arm", -1)), row)
            rows[R3.key_c(und, d, c)] = row
            nc.append(c2)
        out.append((key, nc, res, how))
    return out, rows, n_row


def price_kind(arm: str, us: list, kind: str) -> list:
    """Price units with the variant's exits: HU / HUNT / HP = the arm's own exits + breakeven at +1R (no step lock) + the
    moved fx; CH = V5 (breakeven at +10% + today's lock) + the moved fx; V5 = R193's V5 pricing."""
    M = R7.mods()
    if kind in ("V5", "CH"):
        if kind == "V5":
            return R3.price(arm, us, True, K.V5_LOCK[arm], 1)
        ex = exits_for(arm, kind)
        return [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, 1, -1.0, K.V5_LOCK[arm], be_x=K.V5_BE)
                for key, cands, _, _ in us]
    ex = exits_for(arm, kind)
    return [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, 1, -1.0, "", be_x=float(ex.stop))
            for key, cands, _, _ in us]


def mtf_keep(walked: list, kind: str) -> np.ndarray:
    tf = "15" if kind == "F15" else "60"
    out = []
    for t in walked:
        ix = index_bars(t["und"])
        a = L.mtf_agree(ix["S" + tf], ix["ema" + tf], ix["pos"][S6._d(t["day"])], int(t["er_sig"]), t["side"])
        out.append(a is None or bool(a))
    return np.array(out, bool)


def run_window(arm: str, lo: date, hi: date, kinds: tuple) -> dict:
    """{'V5': trades, kind: trades, '_acted': {kind: count}} for one arm over [lo, hi]. The picker (R72 / R51) and the
    MTF filter are applied after the walk, the way the app applies a skip filter (a skipped break still holds its side)."""
    us0, usA = R3.units(arm, lo, hi)
    P = sc = thr = None
    if K.PICKER[arm]:
        P = S6.picker()
        sc, thr = P.scores(arm, us0)
        assert abs(thr - K.PICK_THR[arm]) < 1e-12

    def pick(tr):
        return [t for t in tr if P is None or P.kept(t, sc, thr)]

    wV = R3.walk(arm, usA, price_kind(arm, usA, "V5"), lo, hi)
    out = {"V5": pick(wV), "_acted": {}, "_skipped": {}}
    print(arm, "V5", len(out["V5"]), round(sum(t["net"] for t in out["V5"])), flush=True)
    for kind in kinds:
        if kind in ("F15", "F60"):
            kp = np.array([P is None or P.kept(t, sc, thr) for t in wV], bool)
            km = mtf_keep(wV, kind)
            out[kind] = [t for t, a, b in zip(wV, kp, km) if a and b]
            out["_skipped"][kind] = int((kp & ~km).sum())
        else:
            us, rows, n_row = moved_units(arm, usA, kind)
            tr = pick(R3.walk(arm, us, price_kind(arm, us, kind), lo, hi))
            out[kind] = tr
            out["_acted"][kind] = dict(cands_with_row=n_row, trades_sold_by_it=int(sum(
                1 for t in tr if rows.get(R3.key_t(t), -1) >= 0 and int(t["xr"]) == rows[R3.key_t(t)])))
        print(arm, kind, len(out[kind]), round(sum(t["net"] for t in out[kind])), flush=True)
    return out


def design_stats(tr: list) -> dict:
    s = {"design": G.stats(tr, *K.DESIGN)}
    for f, (lo, hi) in K.FOLDS.items():
        s[f] = G.stats([t for t in tr if lo <= t["day"] <= hi], lo, hi)
    return s


# ================================================================================================ commands
def design() -> dict:
    flag("design", "design.json")
    boot()
    rng = np.random.default_rng(K.SEED)
    out = {"_meta": dict(written=now(), design=f"{K.DESIGN[0]} .. {K.DESIGN[1]}", flips=K.FLIPS, seed=K.SEED,
                         m_total=K.M_TOTAL)}
    trades = {}
    for arm in K.ARMS:
        res = run_window(arm, *K.DESIGN, kinds=K.KINDS)
        base = design_stats(res["V5"])
        pub = K.R193_W1[arm]
        out["V5:" + arm] = dict(stats=base, repro=dict(
            published=list(pub), trades=base["design"]["trades"], net=base["design"]["net"],
            gap_trades=base["design"]["trades"] - pub[0], gap_net=base["design"]["net"] - pub[1],
            reproduced=base["design"]["trades"] == pub[0] and abs(base["design"]["net"] - pub[1]) <= 1))
        trades["V5:" + arm] = res["V5"]
        for kind in K.KINDS:
            tid = f"{arm}-{kind}"
            tr = res[kind]
            trades[tid] = tr
            sf = L.sign_flip(L.daily_diffs(tr, res["V5"], *K.DESIGN), K.FLIPS, rng)
            out[tid] = dict(arm=arm, kind=kind, stats=design_stats(tr), test=sf, acted=res["_acted"].get(kind),
                            mtf_skipped=res["_skipped"].get(kind))
    save("design_trades", trades)
    write("design.json", out)
    return out


def decide() -> dict:
    r = json.loads((K.RESULTS / "design.json").read_text())
    tids = list(K.TESTS)
    qs = L.bh([r[t]["test"]["p_normal"] for t in tids], K.M_TOTAL)
    q_in = L.bh([r[t]["test"]["p_normal"] for t in tids], len(tids))       # descriptive only
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for t, q, qi in zip(tids, qs, q_in):
        arm = K.TESTS[t][0]
        g = L.gate(r[t]["stats"], r["V5:" + arm]["stats"], q, K.MIN_TRADES, K.MIN_FOLDS, K.GATE_Q)
        g.update(p=r[t]["test"]["p"], p_normal=r[t]["test"]["p_normal"], q_within_study=qi)
        out["tests"][t] = g
    out["survivors"] = [t for t in tids if out["tests"][t]["passes"]]
    write("decision.json", out)
    return out


def holdout() -> dict:
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    surv = dec["survivors"]
    if not surv:
        out = {"_meta": dict(written=now()), "read": False,
               "note": "no variant passed the design gate, so the holdout was not read for any R210 variant"}
        write("holdout.json", out)
        return out
    flag("holdout", "holdout.json")
    boot()
    out = {"_meta": dict(written=now(), holdout=f"{K.HOLDOUT[0]} .. {K.HOLDOUT[1]}"), "read": True}
    for arm in K.ARMS:
        kinds = tuple(K.TESTS[t][1] for t in surv if K.TESTS[t][0] == arm)
        if not kinds:
            continue
        res = run_window(arm, *K.HOLDOUT, kinds=kinds)
        base = G.stats(res["V5"], *K.HOLDOUT)
        out["V5:" + arm] = base
        for kind in kinds:
            tid = f"{arm}-{kind}"
            s = G.stats(res[kind], *K.HOLDOUT)
            out[tid] = dict(stats=s, verdict=L.holdout_verdict(s, base), acted=res["_acted"].get(kind),
                            mtf_skipped=res["_skipped"].get(kind))
    write("holdout.json", out)
    return out


def money(x: float) -> str:
    return f"-Rs {abs(x):,}" if x < 0 else f"Rs {x:,}"


def _row(name: str, s: dict) -> str:
    wm = s["worst_month"]
    win = "-" if s["win"] is None else f"{s['win']}%"
    return (f"| {name} | {s['trades']:,} | {win} | {money(s['net'])} | {money(wm['net'])} ({wm['month']}) | "
            f"Rs {s['max_dd']:,} |")


def report() -> str:
    r = json.loads((K.RESULTS / "design.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    hp = K.RESULTS / "holdout.json"
    h = json.loads(hp.read_text()) if hp.exists() else {"read": False}
    L_ = ["# R210 results: hybrid trail, Chandelier exit and higher-timeframe filter on R72, R51 and Liquidity 15+5", "",
          f"Design {K.DESIGN[0]} - {K.DESIGN[1]} (4 walk-forward folds), 1 lot, dated Zerodha charges and R17 slippage. "
          f"Baseline = each arm's current exits (V5). BH over m = {K.M_TOTAL:,}.", ""]
    for arm in K.ARMS:
        b = r["V5:" + arm]
        L_ += [f"## {K.ARM_NAME[arm]}", "",
               f"Reproduction of R193's V5 on the design years: {b['repro']['trades']:,} trades / Rs {b['repro']['net']:,} "
               f"(R193: {b['repro']['published'][0]:,} / Rs {b['repro']['published'][1]:,}; "
               f"{'reproduced' if b['repro']['reproduced'] else 'NOT reproduced'}).", "",
               "| rule | trades | win | net | worst month | max DD |", "|---|---:|---:|---:|---|---:|",
               _row("V5 (current exits)", b["stats"]["design"])]
        for kind in K.KINDS:
            t = f"{arm}-{kind}"
            L_.append(_row(f"{t}: {K.LABEL[kind]}", r[t]["stats"]["design"]))
        L_ += ["", "| test | folds beaten | sign-flip p | BH q | within-study q | design gate | failed items |",
               "|---|---|---:|---:|---:|---|---|"]
        for kind in K.KINDS:
            t = f"{arm}-{kind}"
            g = dec["tests"][t]
            L_.append(f"| {t} | {', '.join(g['folds_won']) or 'none'} | {g['p']:.4f} | {g['q']:.3g} | "
                      f"{g['q_within_study']:.3g} | {'PASS' if g['passes'] else 'fail'} | {'; '.join(g['fails'])} |")
        L_.append("")
    L_ += ["## Holdout (1 Apr - 13 Nov 2026), read once for the design survivors only", ""]
    if not h.get("read"):
        L_.append("Not read: no variant passed the design gate.")
    else:
        for t in dec["survivors"]:
            arm = K.TESTS[t][0]
            L_ += ["| rule | trades | win | net | worst month | max DD |", "|---|---:|---:|---:|---|---:|",
                   _row("V5", h["V5:" + arm]), _row(t, h[t]["stats"]),
                   "", f"Verdict: {'CANDIDATE (only Boss can choose it)' if h[t]['verdict']['candidate'] else 'failed'}"
                   f" {'; '.join(h[t]['verdict']['fails'])}", ""]
    txt = "\n".join(L_) + "\n"
    (K.RESULTS / "report.md").write_text(txt)
    return txt


if __name__ == "__main__":
    {"design": design, "decide": decide, "holdout": holdout, "report": report}.get(
        (sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
