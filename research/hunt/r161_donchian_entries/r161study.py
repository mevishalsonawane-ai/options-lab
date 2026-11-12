"""R161 study (PREREG.md): entry-quality filters on the two Donchian arms, 1-3 lots, against today's rules and on top of
R151's live pickers.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                    # R101's clean stores (to 13 Oct), as R152
    python research/hunt/r161_donchian_entries/r161study.py design           # design + walk-forward; B0 parity asserted
    python research/hunt/r161_donchian_entries/r161study.py final            # the ONE final read (refuses a second)
    python research/hunt/r161_donchian_entries/r161study.py decide           # BH + gates -> decision.json, raw_pvalues.csv
    python research/hunt/r161_donchian_entries/r161report.py                 # report.md + experiment entries

Units, walk, pricing and parity are R152's (r152study / r152engine). Filters: r161feat. Statistics, twins, C1's choice,
BH and gates: r161lib. The picker second look scores candidates as R152 did (R151's features, the app's model numbers).
"""
from __future__ import annotations

import csv
import json
import pickle
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r152_donchian_2k"))
sys.path.insert(0, str(HERE))
import r152cfg as K2  # noqa: E402  (paths, R51_CLEAN=1)
import r152study as R  # noqa: E402
import r161feat as F  # noqa: E402
import r161lib as L  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OUT = K2.OUT.parent / "r161"
ARMS = K2.ARMS
LOTS = K2.LOTS
RULES = F.SINGLES + ("C1",)
LOOKS = ("base", "picker")
TESTS = tuple((a, r, n, lk) for lk in LOOKS for a in ARMS for r in RULES for n in LOTS)     # 168
M_TOTAL = L.N_PRIOR + len(TESTS)                                                            # 2,332
TWINS, SEED = 1000, 161
WF_Q = K2.WF_Q


# ================================================================================================ context
_CTX: dict = {}


def ctx() -> dict:
    """{und: IndexCtx} from each index's continuous 5-minute bars (R51's fam_r44.bars5, the arms' own bars)."""
    if not _CTX:
        fam = R.mods()["S12"].mods()["fam_r44"]
        import r51cfg as K51
        for u in K51.UNDS:
            _CTX[u] = F.IndexCtx(*fam.bars5(u))
    return _CTX


def decisions(us: list) -> dict:
    """{(g, j): {E1..E13 decision}} and the E2 rows {(g, j): new er or None} for every candidate of the units."""
    cx = ctx()
    dec, hold = {}, {}
    for g, (key, cands, _, _) in enumerate(us):
        und, day = key[0], key[1]
        for j, c in enumerate(cands):
            dec[(g, j)] = F.decide(cx[und], day, int(c["er"]), c["side"], cx, float(c.get("f_vixchg", np.nan)))
            hold[(g, j)] = F.hold1(cx[und], day, int(c["er"]), c["side"])
    return dec, hold


# ================================================================================================ units and prices
def priced(us: list, lots: int) -> list:
    """Every candidate priced with today's exits at `lots` lots (R152's engine, no rupee level = B0)."""
    M = R.mods()
    ex = R.exits()
    return [M["Y"].price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, None, None, lots)
            for key, cands, _, _ in us]


def subset(us: list, prs: dict, keep_idx: list) -> tuple:
    """(units, {lots: prices}) keeping candidates keep_idx[g] of each unit (empty units dropped)."""
    u2, p2 = [], {n: [] for n in prs}
    for g, (key, cands, res, how) in enumerate(us):
        idx = keep_idx[g]
        if idx:
            u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
            for n in prs:
                p2[n].append([prs[n][g][j] for j in idx])
    return u2, p2


def rule_idx(us: list, dec: dict, rule) -> list:
    return [[j for j in range(len(u[1])) if F.keep(dec[(g, j)], rule)] for g, u in enumerate(us)]


def hold_units(us: list, hold: dict) -> tuple:
    """E2's units: each kept break moved to its new row, strike re-chosen from the minute before (1-ITM), priced again;
    per unit the first candidate of a (side, row) is kept. Returns (units, {lots: prices}, dropped count)."""
    M = R.mods()
    E = M["E"]
    ex = R.exits()
    u2, p2, dropped = [], {n: [] for n in LOTS}, 0
    for g, (key, cands, _, how) in enumerate(us):
        und, day = key[0], key[1]
        D = M["r51data"].load(und)[day]
        new, seen = [], set()
        for j, c in enumerate(cands):
            er2 = hold[(g, j)]
            if er2 is None or (c["side"], er2) in seen:
                dropped += 1
                continue
            k = E.strike_for(D, und, c["side"], er2 - 1, 1)
            if k is None:
                dropped += 1
                continue
            seen.add((c["side"], er2))
            nc = dict(c)
            nc.update(er=er2, strike=k, er_orig=int(c["er"]))
            nc.update(M["A"].sig_feats(und, day, er2, c["side"]))       # VIX change and bar breadth at the new minute
            new.append(nc)
        if not new:
            continue
        new.sort(key=lambda c: (int(c["er"]), c["side"]))
        prs = {n: M["Y"].price(und, D, new, ex, None, None, n) for n in LOTS}
        u2.append((key, new, prs[1], how))
        for n in LOTS:
            p2[n].append(prs[n])
    return u2, p2, dropped


# ================================================================================================ picker (second look)
_P: dict = {}


def picker_mods():
    if not _P:
        import r152picker as P
        _P.update(P=P, T=P.T)
    return _P


def cand_feats(us: list) -> dict:
    """R151's features of every candidate of the units (E2's moved breaks read at their new minute)."""
    return picker_mods()["T"].feats_of_units(us)


def final_scores(arm: str, keys: list, fx: dict) -> dict:
    """The app's scores (R151's exported model numbers), as R152's r152picker.scores."""
    from research.hunt.r151_donchian_winrate60 import pick as PK
    T = picker_mods()["T"]
    fn, thr, _ = K2.PICKER[arm]
    nums = json.loads((K2.R151 / "results" / fn).read_text())
    assert abs(nums["threshold"] - thr) < 1e-12 and nums["feats"] == T.FEATS
    x = T.matrix(keys, fx)
    return {k: PK.score_from_numbers(nums, row) for k, row in zip(keys, x)}


def oof(arm: str, b0_trades: list, fx_train: dict, us: list, fx_score: dict) -> dict:
    """R151's out-of-fold M2 scores (oof_scores) with the training rows and the scored rows read from separate feature
    dicts, so E2's moved breaks never overwrite a B0 break's features at the same minute."""
    from research.hunt.r151_donchian_winrate60 import pick as PK
    T = picker_mods()["T"]
    tkeys = [T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in b0_trades]
    y = np.array([t["net"] > 0 for t in b0_trades], int)
    tdays = np.array([t["day"] for t in b0_trades], dtype=object)
    sc = {}
    for f0, f1 in T.FOLDS:
        m = PK.train_mask(tdays, f0)
        if m.sum() < 200:
            continue
        mdl = T.make_model("M2").fit(T.matrix([k for k, mm in zip(tkeys, m) if mm], fx_train), y[m])
        ck = [T.cand_key(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs if f0 <= key[1] <= f1]
        if ck:
            sc.update(zip(ck, (float(v) for v in mdl.predict_proba(T.matrix(ck, fx_score))[:, 1])))
    return sc


def keys_of(us: list) -> list:
    T = picker_mods()["T"]
    return [T.cand_key(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs]


def picked(trades: list, sc: dict, thr: float) -> list:
    """The app's rule: a break scored below the threshold is skipped (it already held its side in the walk)."""
    T = picker_mods()["T"]
    out = []
    for t in trades:
        s = sc.get(T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]))
        if s is None or not (s < thr):
            out.append(t)
    return out


# ================================================================================================ evaluation helpers
def per_stats(tr: list, pers: list) -> dict:
    return {p: L.stats(L.in_window(tr, a, b)) for p, a, b in pers}


def quarter_of(d: date):
    for k, (a, b) in enumerate(WF_Q):
        if a <= d <= b:
            return k
    return None


def twins(us: list, prs: list, k: int, rng, lo: date, hi: date, sc=None, thr=None) -> tuple:
    """(win, net) arrays of TWINS random-skip twins: k breaks removed at random, walked again (then the picker)."""
    wins, nets = np.zeros(TWINS), np.zeros(TWINS)
    for x in range(TWINS):
        idx = L.random_drop(us, k, rng)
        u2, p2 = subset(us, {0: prs}, idx)
        tr = L.in_window(R.walk(u2, p2[0]), lo, hi)
        if sc is not None:
            tr = picked(tr, sc, thr)
        wins[x], nets[x] = L.win_net(tr)
    return wins, nets


def twin_ps(tr: list, tw: tuple) -> dict:
    w, n = L.win_net(tr)
    pw, pn = L.twin_p(w, tw[0]), L.twin_p(n, tw[1])
    return dict(p=max(pw["p"], pn["p"]), p_win=pw["p"], p_net=pn["p"], twin_win=round(pw["twin_mean"], 4),
                twin_net=round(pn["twin_mean"]), hits_win=pw["hits"], hits_net=pn["hits"])


# ================================================================================================ phases
def evaluate(arm: str, phase: str, lo: date, hi: date, choices: dict | None = None) -> dict:
    us = R.units(arm, lo, hi)
    out = {"parity": R.parity(arm, phase, us), "candidates": sum(len(u[1]) for u in us)}
    if phase == "design":
        pers = [("design", lo, hi), ("wf", K2.WF_START, hi)] + [(f"q{k + 1}", a, b) for k, (a, b) in enumerate(WF_Q)]
        ppers = [("wf", K2.WF_START, hi)]
    else:
        pers = [("final", lo, hi)]
        ppers = pers
    dec, hold = decisions(us)
    prs = {n: priced(us, n) for n in LOTS}
    b0 = {n: R.walk(us, prs[n]) for n in LOTS}
    n1, net1 = len(b0[1]), round(sum(t["net"] for t in b0[1]))
    assert (n1, net1) == (out["parity"]["trades"], out["parity"]["net"]), ("engine B0 != published", n1, net1)
    out["decision_counts"] = {r: dict(take=sum(1 for v in dec.values() if v[r] is True),
                                      skip=sum(1 for v in dec.values() if v[r] is False),
                                      unknown=sum(1 for v in dec.values() if v[r] is None)) for r in F.COMBO_POOL}

    # ---- picker scores (WF: R151's out-of-fold M2; final: the app's numbers)
    T = picker_mods()["T"]
    thr = K2.PICKER[arm][1]
    hu, hp, hdrop = hold_units(us, hold)
    fx = cand_feats(us)
    fxh = cand_feats(hu)
    if phase == "design":
        sc = oof(arm, b0[1], fx, us, fx)
        sch = oof(arm, b0[1], fx, hu, fxh)
        ref = T.oof_scores(arm, us, b0[1], fx, "M2")          # R151's own function: must agree
        assert all(abs(ref[k] - sc[k]) < 1e-9 for k in ref) and set(ref) == set(sc)
    else:
        sc = final_scores(arm, keys_of(us), fx)
        sch = final_scores(arm, keys_of(hu), fxh)

    # ---- the rule's units for every rule
    def rule_units(r):
        if r == "E2":
            return hu, hp, hdrop
        rr = r
        if r == "C1":
            rr = choices["final"] if phase == "final" else None
        if rr is None:                              # C1 in design: per-quarter choice, assembled below
            return None
        idx = rule_idx(us, dec, rr)
        u2, p2 = subset(us, prs, idx)
        return u2, p2, out["candidates"] - sum(len(i) for i in idx)

    if phase == "design":
        single1 = {}
        for r in F.COMBO_POOL:
            u2, p2, _ = rule_units(r)
            single1[r] = R.walk(u2, p2[1])
        ch = {}
        for k, (a, _) in enumerate(WF_Q):
            train_hi = a - timedelta(days=1)
            base = L.win_net(L.in_window(b0[1], lo, train_hi))
            ch[f"q{k + 1}"] = L.choose_combo({r: L.win_net(L.in_window(single1[r], lo, train_hi)) for r in single1},
                                             base, F.COMBO_POOL)
        base = L.win_net(L.in_window(b0[1], lo, hi))
        ch["final"] = L.choose_combo({r: L.win_net(L.in_window(single1[r], lo, hi)) for r in single1}, base,
                                     F.COMBO_POOL)
        out["c1_choices"] = {k: list(v) for k, v in ch.items()}
        choices = ch

    rng = np.random.default_rng(SEED + (0 if phase == "design" else 1) + (10 if arm == "R51" else 0))
    for n in LOTS:
        out[f"B0@{n}"] = per_stats(b0[n], pers)
        out[f"B0@{n}"]["picker"] = per_stats(picked(b0[n], sc, thr), ppers)
        for r in RULES:
            ru = rule_units(r)
            if ru is None:                          # C1 at design: each WF quarter with its own out-of-sample choice
                trq, full = [], None
                for k in range(len(WF_Q)):
                    idx = rule_idx(us, dec, choices[f"q{k + 1}"])
                    u2, p2 = subset(us, prs, idx)
                    trq += [t for t in R.walk(u2, p2[n]) if quarter_of(t["day"]) == k]
                idx = rule_idx(us, dec, choices["final"])
                u2, p2 = subset(us, prs, idx)
                full = R.walk(u2, p2[n])
                tr = [t for t in full if t["day"] < K2.WF_START] + trq      # design = in-sample before WF + WF OOS
                res = per_stats(tr, pers)
                res["design_insample_full"] = L.stats(full)
                res["removed"] = None
            else:
                u2, p2, removed = ru
                tr = R.walk(u2, p2[n])
                res = per_stats(tr, pers)
                res["removed"] = removed
            ptr = picked(tr, sch if r == "E2" else sc, thr)
            res["picker"] = per_stats(ptr, ppers)
            if phase == "final":
                k = res["removed"]
                tw = twins(us, prs[n], k, rng, lo, hi)
                res["twin"] = twin_ps(L.in_window(tr, lo, hi), tw)
                twp = twins(us, prs[n], k, rng, lo, hi, sc, thr)
                res["picker"]["twin"] = twin_ps(L.in_window(ptr, lo, hi), twp)
            out[f"{r}@{n}"] = res
            pm = pers[0][0] if phase == "final" else "wf"
            print(arm, phase, r, n, "base", (res[pm]["trades"], res[pm]["win"], res[pm]["net"]), "B0",
                  (out[f"B0@{n}"][pm]["trades"], out[f"B0@{n}"][pm]["win"], out[f"B0@{n}"][pm]["net"]),
                  "picker", (res["picker"][pm]["trades"], res["picker"][pm]["win"], res["picker"][pm]["net"]),
                  res.get("twin", {}).get("p"), res["picker"].get("twin", {}).get("p"), flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    return out


def run(phase: str):
    M = R.mods()
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        assert (RESULTS / "design.json").exists(), "design first"
        if FINAL_FLAG.exists() and not (RESULTS / "final_open").exists():
            raise SystemExit("R161 final already read once: " + FINAL_FLAG.read_text())
        if not FINAL_FLAG.exists():
            FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K2.FINAL_START, K2.FINAL_END
        d = json.loads((RESULTS / "design.json").read_text())
    else:
        lo, hi = K2.START, K2.DESIGN_END
    for arm in ARMS:
        done = RESULTS / f"{phase}_{arm}.json"
        if phase == "final" and done.exists():
            continue
        choices = None
        if phase == "final":
            choices = {k: tuple(v) for k, v in d[arm]["c1_choices"].items()}
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi, choices), indent=1, default=str))
    if all((RESULTS / f"{phase}_{a}.json").exists() for a in ARMS):
        (RESULTS / f"{phase}_open").unlink(missing_ok=True)
        (RESULTS / f"{phase}.json").write_text(json.dumps(
            {a: json.loads((RESULTS / f"{phase}_{a}.json").read_text()) for a in ARMS}, indent=1))
        for a in ARMS:
            (RESULTS / f"{phase}_{a}.json").unlink()


def decide() -> dict:
    d = json.loads((RESULTS / "design.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    rows = []
    for a, r, n, lk in TESTS:
        k, b = f"{r}@{n}", f"B0@{n}"
        if lk == "base":
            rule = dict(design=d[a][k]["design"], wf=d[a][k]["wf"], final=f[a][k]["final"])
            ref = dict(design=d[a][b]["design"], wf=d[a][b]["wf"], final=f[a][b]["final"])
            tw = f[a][k]["twin"]
            per = ("design", "wf", "final")
        else:
            rule = dict(wf=d[a][k]["picker"]["wf"], final=f[a][k]["picker"]["final"])
            ref = dict(wf=d[a][b]["picker"]["wf"], final=f[a][b]["picker"]["final"])
            tw = f[a][k]["picker"]["twin"]
            per = ("wf", "final")
        rows.append((a, r, n, lk, rule, ref, tw, per))
    ps = [x[6]["p"] for x in rows]
    q_all = L.bh(ps, M_TOTAL)
    q_own = L.bh(ps)
    out = {}
    with open(RESULTS / "raw_pvalues.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["study", "arm", "rule", "name", "lots", "look", "p", "p_win", "p_net", "final_trades",
                    "final_win", "ref_final_win", "final_net", "ref_final_net", "q_r161_m2332", "q_r161_alone"])
        for (a, r, n, lk, rule, ref, tw, per), qa, qo in zip(rows, q_all, q_own):
            g = L.gates(rule, ref, tw["p"], float(qa), per)
            g.update(p_win=tw["p_win"], p_net=tw["p_net"], q_all=round(float(qa), 4), q_r161=round(float(qo), 4))
            out[f"{lk}:{a}:{r}@{n}"] = g
            w.writerow(["R161", a, r, F.NAMES[r], n, lk, f"{tw['p']:.6g}", f"{tw['p_win']:.6g}", f"{tw['p_net']:.6g}",
                        rule["final"]["trades"], rule["final"]["win"], ref["final"]["win"], rule["final"]["net"],
                        ref["final"]["net"], f"{qa:.4g}", f"{qo:.4g}"])
    out["_m"] = dict(r161=len(TESTS), prior=L.N_PRIOR, total=M_TOTAL,
                     note="R162 and R163 run in parallel; the coordinator adds their tests for one joint BH")
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        dd = decide()
        print(sum(1 for k, v in dd.items() if not k.startswith("_") and v["passed"]), "of", len(TESTS), "pass")
    else:
        raise SystemExit(__doc__)
