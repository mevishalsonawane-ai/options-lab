"""R162 study (PREREG.md): trade-structure rules on the two Donchian arms, 1-3 lots, vs today's exits (B0).

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                    # R51's clean stores (to 13 Oct)
    python research/hunt/r162_donchian_exits/r162study.py design             # design + walk-forward, B0 parity asserted
    python research/hunt/r162_donchian_exits/r162study.py final              # the ONE final read (refuses a second)
    python research/hunt/r162_donchian_exits/r162study.py decide             # twins' BH + gates -> results/decision.json
    python research/hunt/r162_donchian_exits/r162study.py picker             # second look + the release item (Apr - Oct)

Candidates are R152's (R112's units: R72 = R101's; R51 = R101's Donchian 20 kept by Supertrend (10, 3)), walked by
R106's run_rule (one open a side, 2 fresh fills a side a day). 12 Nov 2026.
"""
from __future__ import annotations

import csv
import json
import pickle
import sys
from datetime import datetime, timezone

import numpy as np

import r162cfg as K
import r162lib as L

_M: dict = {}


def mods():
    if not _M:
        import r152study as R
        M = R.mods()
        import r162engine as Y
        _M.update(R=R, S=M["S"], C=M["C"], E=M["E"], r51data=M["r51data"], Y=Y, b0=R.exits())
    return _M


def priced(us: list, rule: str, lots: int, money: int | None = None) -> list:
    M = mods()
    out = []
    for key, cands, _, _ in us:
        days = M["r51data"].load(key[0])
        out.append(M["Y"].price(key[0], days[key[1]], cands, rule, M["b0"], lots, days, money))
    return out


def walk(us: list, pr: list) -> list:
    return mods()["R"].walk(us, pr)


def tkey(t: dict) -> tuple:
    return (t["und"], t["day"], int(t["er_sig"]), t["side"])


def plain_map(us: list, pr: list) -> dict:
    return {(key[0], key[1], int(c["er"]), c["side"]): t
            for (key, cands, _, _), row in zip(us, pr) for c, t in zip(cands, row) if t is not None}


def describe(tr: list, sess: list, phase: str) -> dict:
    S = mods()["S"]
    w, n = L.round_wins(tr)
    r = {"all": dict(S.stats(tr, len(sess)), round_win=(round(w / n, 4) if n else None), rounds=n)}
    if phase == "design":
        wfs = [d for d in sess if d >= K.WF_START]
        wt = [t for t in tr if t["day"] >= K.WF_START]
        w2, n2 = L.round_wins(wt)
        r["wf"] = dict(S.stats(wt, len(wfs)), round_win=(round(w2 / n2, 4) if n2 else None), rounds=n2)
        r["wf_q"] = [dict(net=round(sum(t["net"] for t in tr if a <= t["day"] <= b)),
                          trades=sum(1 for t in tr if a <= t["day"] <= b),
                          win=(round(float(np.mean([t["net"] > 0 for t in tr if a <= t["day"] <= b])), 4)
                               if any(a <= t["day"] <= b for t in tr) else None)) for a, b in K.WF_Q]
    return r


# ================================================================================================ twins
def exit_twins(tr: list, plain: dict, n: int, rng) -> dict:
    M = mods()
    tot, wins = np.zeros(n), np.zeros(n)
    changed = 0
    for t in tr:
        b = plain.get(tkey(t))
        nets = None
        if b is not None:
            D = M["r51data"].load(t["und"])[t["day"]]
            nets = M["Y"].twin_nets(t["und"], D, t, b, n, rng)
        if nets is None:
            tot += t["net"]
            wins += t["net"] > 0
            continue
        changed += 1
        tot += nets
        wins += nets > 0
    return dict(tot=tot, wins=wins, changed=changed)


def strike_twins(us: list, tr: list, lots: int, n: int, rng) -> dict:
    """PREREG section 5, strike rules: each taken trade buys a uniformly random strike of 0-3 ITM (available ones)."""
    M = mods()
    tot, wins = np.zeros(n), np.zeros(n)
    for t in tr:
        days = M["r51data"].load(t["und"])
        D = days[t["day"]]
        c = dict(side=t["side"], er=int(t["er_sig"]))
        alt = []
        for m in K.TWIN_MONIES:
            x = M["Y"].price(t["und"], D, [c], "B0", M["b0"], lots, days, money=m)[0]
            if x is not None:
                alt.append(x["net"])
        if not alt:
            tot += t["net"]
            wins += t["net"] > 0
            continue
        a = np.asarray(alt)[rng.integers(0, len(alt), size=n)]
        tot += a
        wins += a > 0
    return dict(tot=tot, wins=wins, changed=len(tr))


def twin_summary(tr: list, tw: dict) -> dict:
    net = sum(t["net"] for t in tr)
    w = sum(t["net"] > 0 for t in tr)
    pn, pw = L.twin_p(net, tw["tot"]), L.twin_p(w, tw["wins"])
    return dict(p=round(max(pn, pw), 4), p_net=round(pn, 4), p_win=round(pw, 4), changed=tw["changed"],
                twin_net=round(float(tw["tot"].mean())), twin_win=round(float(tw["wins"].mean()) / max(len(tr), 1), 4))


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def evaluate(arm: str, phase: str, lo, hi, rules=K.RULE_IDS) -> dict:
    M = mods()
    R = M["R"]
    us = R.units(arm, lo, hi)
    out = {"parity": R.parity(arm, phase, us)}
    sess = R.sessions_in(lo, hi)
    out["sessions"] = len(sess)
    trades = {}
    rng = np.random.default_rng(K.SEED)
    for lots in K.LOTS:
        pr0 = priced(us, "B0", lots)
        if lots == 1:                           # the R162 engine with no rule prices exactly as R101 did
            for a_, (_, _, res, _) in zip(pr0, us):
                for x, y in zip(a_, res):
                    assert (x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6), "engine parity"
        trades[("B0", lots)] = walk(us, pr0)
        plain = plain_map(us, pr0)
        out[f"B0@{lots}"] = describe(trades[("B0", lots)], sess, phase)
        for rule in rules:
            if lots not in K.lots_of(rule):
                continue
            tr = walk(us, priced(us, rule, lots))
            trades[(rule, lots)] = tr
            r = describe(tr, sess, phase)
            if phase == "final":
                if K.RULES[rule]["kind"] == "strike":
                    tw = strike_twins(us, tr, lots, K.TWINS, rng)
                else:
                    tw = exit_twins(tr, plain, K.TWINS, rng)
                r["twin"] = twin_summary(tr, tw)
            out[f"{rule}@{lots}"] = r
            print(arm, phase, rule, lots, len(tr), round(sum(t["net"] for t in tr)),
                  r["all"]["win"], r.get("twin", ""), flush=True)
    _save(f"{arm}_{phase}", trades)
    return out


def run(phase: str, arms=K.ARMS):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R162 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    else:
        lo, hi = K.START, K.DESIGN_END
    for arm in arms:
        done = K.RESULTS / f"{phase}_{arm}.json"
        if phase == "final" and done.exists():
            print(arm, phase, "already read; skipped", flush=True)
            continue
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi), indent=1, default=str))
    if all((K.RESULTS / f"{phase}_{a}.json").exists() for a in K.ARMS):
        (K.RESULTS / f"{phase}_open").unlink(missing_ok=True)


def m_total() -> int:
    return len(K.TESTS) + K.N_PRIOR


def decide() -> dict:
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    ps = [f[a][f"{r}@{n}"]["twin"]["p"] for a, r, n in K.TESTS]
    q_all, q_own = L.bh(ps, m_total()), L.bh(ps)
    out = {}
    rows = []
    for (a, r, n), p, qa, qo in zip(K.TESTS, ps, q_all, q_own):
        k, b = f"{r}@{n}", f"B0@{n}"
        rule = dict(design=d[a][k]["all"], wf=d[a][k]["wf"], final=f[a][k]["all"])
        base = dict(design=d[a][b]["all"], wf=d[a][b]["wf"], final=f[a][b]["all"])
        g = L.gates(rule, base, float(qa), K.WIN_UP, K.GATE_Q, K.MIN_FINAL)
        tw = f[a][k]["twin"]
        g.update(p=p, p_net=tw["p_net"], p_win=tw["p_win"], q_all=round(float(qa), 4), q_r162=round(float(qo), 4))
        out[f"{a}:{k}"] = g
        rows.append(dict(study="R162", test=f"{a}:{r}@{n}", arm=a, rule=r, lots=n, family=K.FAMILY[K.RULES[r]["kind"]],
                         p=p, p_net=tw["p_net"], p_win=tw["p_win"], q_m1349=round(float(qa), 4),
                         q_r162=round(float(qo), 4), passed=g["passed"]))
    out["_m"] = dict(r162=len(K.TESTS), prior=K.N_PRIOR, total=m_total())
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    with open(K.RESULTS / "raw_pvalues.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return out


# ================================================================================================ second look + release
def picker():
    """PREREG sections 6 (second look) and 7 (release rules), Apr - Oct 2026, descriptive."""
    assert K.FINAL_FLAG.exists(), "the second look follows R162's one final read"
    M = mods()
    import r152picker as PK                     # R151's app pickers (model numbers, thresholds, features)
    M["C"].open_final()
    lo, hi = K.FINAL_START, K.FINAL_END
    sess = M["R"].sessions_in(lo, hi)
    out = {"_meta": dict(period=f"{lo} .. {hi}", sessions=len(sess),
                         note="second look: Apr - Oct 2026 was read many times; the pickers failed their gates")}
    for arm in K.ARMS:
        us = M["R"].units(arm, lo, hi)
        sc, thr = PK.scores(arm, us)

        def skipped(t_or_c, und, day):
            return not PK.kept(dict(und=und, day=day, er_sig=t_or_c["er"], side=t_or_c["side"]), sc, thr)

        a = {}
        # the release item (section 7), B0, 1 lot
        pr0 = priced(us, "B0", 1)
        rel = {}
        for name, (mode, hold) in K.RELEASE.items():
            tr = []
            for d, gs in sorted(L.day_of(us).items()):
                uu = [(us[g][0], us[g][1], us[g][3]) for g in gs]
                pp = [pr0[g] for g in gs]
                tr += L.walk_release(uu, pp, lambda gi, j: skipped(uu[gi][1][j], uu[gi][0][0], uu[gi][0][1]), mode, hold)
            rel[name] = M["S"].stats(tr, len(sess))
            print(arm, "release", name, len(tr), round(sum(t["net"] for t in tr)), flush=True)
        a["release"] = rel
        a["release_parity_r151"] = dict(trades=rel["P-R151"]["trades"], net=rel["P-R151"]["net"],
                                        published=list(K.K152.PICKER[arm][2]))
        # the rules on the picker's trades (the app's hold rule), 1-3 lots
        for lots in K.LOTS:
            for rule in ("B0",) + K.RULE_IDS:
                if rule != "B0" and lots not in K.lots_of(rule):
                    continue
                tr = [t for t in walk(us, priced(us, rule, lots)) if PK.kept(t, sc, thr)]
                w, n = L.round_wins(tr)
                a[f"{rule}@{lots}"] = dict(M["S"].stats(tr, len(sess)), round_win=(round(w / n, 4) if n else None))
            print(arm, "picker lots", lots, "done", flush=True)
        out[arm] = a
    (K.RESULTS / "picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd, tuple(sys.argv[2:]) or K.ARMS)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1)[:4000])
    elif cmd == "picker":
        picker()
    else:
        raise SystemExit(__doc__)
