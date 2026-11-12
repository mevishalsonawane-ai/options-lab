"""R166 study (PREREG.md): six checks on the Donchian arms, Nov 2024 onward, 1 lot, vs today's rules (B0).

    python research/hunt/r166_donchian_six/r166study.py design      # design + walk-forward, B0 parity asserted
    python research/hunt/r166_donchian_six/r166study.py final       # the ONE final read (refuses a second)
    python research/hunt/r166_donchian_six/r166study.py decide      # BH + gates -> results/decision.json, raw_pvalues.csv

Engine, walk, strike method and twins are R162's. Filters skip a break that then still holds its side and counts in the
2-a-side cap (r162lib.walk_release, mode 'hold'), so a filter never frees a place. 12 Nov 2026.
"""
from __future__ import annotations

import csv
import json
import pickle
import sys
from datetime import datetime, timezone

import numpy as np

import r166cfg as K
import r166lib as L
import r162lib as L2

_M: dict = {}


def mods():
    if not _M:
        import r162study as S2
        M = S2.mods()
        _M.update(S2=S2, R=M["R"], S=M["S"], C=M["C"], E=M["E"], Y=M["Y"], r51data=M["r51data"], b0=M["b0"])
        import r106engine as X
        import r51cfg as K51
        import r64engine as G
        _M.update(X=X, K51=K51, G=G)
    return _M


# ================================================================================================ features (decision time)
def prev_day(und: str, d):
    days = mods()["r51data"].load(und)
    ks = sorted(k for k in days if k < d)
    return days[ks[-1]] if ks else None


def flags(und: str, D, cands: list) -> list:
    """{rule: skip?} for each candidate of one index-day (filters only), from minutes before each entry row."""
    M = mods()
    Y, E = M["Y"], M["E"]
    days = M["r51data"].load(und)
    P = prev_day(und, D.d)
    pdh, pdl = L.prev_hl(P.idx) if P is not None else (float("nan"), float("nan"))
    hi, lo, cl = D.idx[1], D.idx[2], D.idx[3]
    st = E.stack(und, D)
    exp = L2.next_expiry(D.d, Y.flagged_expiries(und, days))
    out = []
    for j, c in enumerate(cands):
        er, side = int(c["er"]), c["side"]
        close = float(cl[er - 1]) if er >= 1 else float("nan")
        f = dict(SR1=L.sr1_skip(side, close, pdh, pdl), SR2=L.sr2_skip(side, close, pdh, pdl),
                 F1=L.whipsaw(cands, j), F2=L.outside_bar(hi, lo, er))
        ci = st.idx(side, c["strike"])
        ratio = None
        iv_now = iv_bef = None
        if ci >= 0:
            C = st.S[ci]
            ratio = L.volume_ratio(C[4], er)
            r1, r6 = er - 1, er - L.LOOK_IV
            if exp is not None and r6 >= 0:
                for r, dst in ((r1, "now"), (r6, "bef")):
                    px, s = float(C[3, r]), float(cl[r])
                    iv = L2.implied_vol(side, px, s, float(c["strike"]), L2.years_to_expiry(D.d, r, exp), K.K162.RATE)
                    if dst == "now":
                        iv_now = iv
                    else:
                        iv_bef = iv
        f["V1"], f["V2"] = L.v1_skip(ratio), L.v2_skip(ratio)
        f["E1"] = L.iv_falling(iv_now, iv_bef)
        opp = "P" if side == "C" else "C"
        k_atm = E.strike_for(D, und, opp, er - 1, 0)
        oi = st.idx(opp, k_atm)
        e2 = False
        if oi >= 0 and er - L.LOOK_IV >= 0:
            O = st.S[oi]
            e2 = L.other_side_bid(float(O[3, er - 1]), float(O[3, er - L.LOOK_IV]))
        f["E2"] = e2
        f["_ratio"], f["_iv"] = ratio, (iv_now, iv_bef)
        out.append(f)
    return out


# ================================================================================================ pricing
def strike_cands(rule: str, und: str, D, cands: list) -> list:
    """Candidates with the rule's strike (family A)."""
    M = mods()
    Y, E = M["Y"], M["E"]
    days = M["r51data"].load(und)
    out = []
    for c in cands:
        nc = dict(c)
        if rule == "D60C2":
            k = Y.strike_of("D60", und, D, c["side"], c["er"], days)
            nc["strike"] = L.cap_depth(c["side"], k, E.strike_for(D, und, c["side"], c["er"] - 1, 2))
        elif rule == "D60W" and und in K.WEEKLY:
            nc["strike"] = Y.strike_of("D60", und, D, c["side"], c["er"], days)
        out.append(nc)
    return out


def price_ft(und: str, D, cands: list, n: int) -> list:
    """FT n (PREREG C): today's exits plus a forced sell at fill + n when the index made no new extreme beyond the signal
    bar in the first n minutes. Trade dicts as r162engine.price (with p_fx for twins)."""
    M = mods()
    Y, E, X, K51, G = M["Y"], M["E"], M["X"], M["K51"], M["G"]
    b0 = M["b0"]
    st = E.stack(und, D)
    qty = float(K51.lot(und, D.d))
    sq = E.sq_row(D, b0)
    ch = Y._ch(und, D, qty)
    out = []
    for c in cands:
        ci = st.idx(c["side"], c["strike"])
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(b0) else 0.0
        lvl = L.signal_extreme(D.idx[1], D.idx[2], int(c["er"]), c["side"])
        fx = L.ft_exit_row(D.idx[1], D.idx[2], int(ef), n, lvl, c["side"], sq)
        a, b, rE, rX, r0, r1, r = Y._one(C, c["er"], sq, fx, b0, qty, be)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), lots=1, qty=qty, p_stop=b0.stop,
                        p_steps=tuple(b0.steps), p_tmax=b0.tmax, p_need=b0.need, p_lkt=-1.0, p_lkk=0.0, p_be=be, p_fx=fx,
                        p_tgt=-1.0, er=int(r0), xr=int(r1), E=float(a), X=float(b), rE=float(rE), rX=float(rX),
                        why=E.REASONS[int(r)], net=float((rX - rE) * qty - ch(rE, rX))))
    return out


def priced(us: list, rule: str) -> list:
    M = mods()
    out = []
    for key, cands, _, _ in us:
        days = M["r51data"].load(key[0])
        D = days[key[1]]
        spec = K.RULES.get(rule, {"kind": "b0"})
        if spec["kind"] == "strike":
            out.append(M["Y"].price(key[0], D, strike_cands(rule, key[0], D, cands), "B0", M["b0"], 1, days))
        elif spec["kind"] == "exit":
            out.append(price_ft(key[0], D, cands, spec["n"]))
        else:
            out.append(M["Y"].price(key[0], D, cands, "B0", M["b0"], 1, days))
    return out


def walk_skip(us: list, pr: list, skip: set) -> list:
    """R106's side2 walk where the (g, j) in `skip` hold their side and count in the cap but are not traded."""
    tr = []
    for d, gs in sorted(L2.day_of(us).items()):
        uu = [(us[g][0], us[g][1], us[g][3]) for g in gs]
        pp = [pr[g] for g in gs]
        tr += L2.walk_release(uu, pp, lambda gi, j: (gs[gi], j) in skip, "hold", None)
    for t in tr:
        t.setdefault("lots", 1)
    tr.sort(key=lambda t: (t["day"], t["und"], t["er_sig"]))
    return tr


# ================================================================================================ stats and twins
def describe(tr: list, sess: list, phase: str) -> dict:
    S = mods()["S"]
    r = {"all": S.stats(tr, len(sess))}
    if phase == "design":
        wfs = [d for d in sess if d >= K.WF_START]
        r["wf"] = S.stats([t for t in tr if t["day"] >= K.WF_START], len(wfs))
        r["wf_q"] = [dict(net=round(sum(t["net"] for t in tr if a <= t["day"] <= b)),
                          trades=sum(1 for t in tr if a <= t["day"] <= b)) for a, b in K.WF_Q]
    return r


def skip_twins(us: list, pr0: list, k: int, n: int, rng, lo, hi) -> dict:
    """PREREG 3: k breaks (among those with a fill) marked skipped at random, walked the same way."""
    pool = [(g, j) for g, row in enumerate(pr0) for j, t in enumerate(row) if t is not None]
    tot, wins, cnt = np.zeros(n), np.zeros(n), np.zeros(n)
    for x in range(n):
        pick = rng.choice(len(pool), size=min(k, len(pool)), replace=False) if k else []
        tr = [t for t in walk_skip(us, pr0, {pool[i] for i in pick}) if lo <= t["day"] <= hi]
        tot[x] = sum(t["net"] for t in tr)
        wins[x] = float(np.mean([t["net"] > 0 for t in tr])) if tr else 0.0
        cnt[x] = len(tr)
    return dict(tot=tot, win=wins, cnt=cnt)


def summary_twin(tr: list, tw: dict, kind: str) -> dict:
    net = sum(t["net"] for t in tr)
    if kind == "filter":
        w = float(np.mean([t["net"] > 0 for t in tr])) if tr else 0.0
        pw = L2.twin_p(w, tw["win"])
        tw_win = float(tw["win"].mean())
    else:
        w = sum(t["net"] > 0 for t in tr)
        pw = L2.twin_p(w, tw["wins"])
        tw_win = float(tw["wins"].mean()) / max(len(tr), 1)
    return dict(p_net=round(L2.twin_p(net, tw["tot"]), 4), p_win=round(pw, 4), twin_net=round(float(tw["tot"].mean())),
                twin_win=round(tw_win, 4))


# ================================================================================================ phases
def evaluate(arm: str, phase: str, lo, hi) -> dict:
    M = mods()
    R, S2 = M["R"], M["S2"]
    us = R.units(arm, lo, hi)
    out = {"parity": R.parity(arm, phase, us)}
    sess = R.sessions_in(lo, hi)
    out["sessions"] = len(sess)
    pr0 = priced(us, "B0")
    for a_, (_, _, res, _) in zip(pr0, us):
        for x, y in zip(a_, res):
            assert (x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6), "engine parity"
    b0 = walk_skip(us, pr0, set())
    assert len(b0) == out["parity"]["trades"] and abs(sum(t["net"] for t in b0) - out["parity"]["net"]) <= 1, "walk parity"
    out["B0"] = describe(b0, sess, phase)
    fl = [flags(key[0], M["r51data"].load(key[0])[key[1]], cands) for key, cands, _, _ in us]
    plain = S2.plain_map(us, pr0)
    trades = {"B0": b0}
    rng = np.random.default_rng(K.SEED)
    wf_lo = K.WF_START
    for rule in K.RULE_IDS:
        spec = K.RULES[rule]
        info = {}
        if spec["kind"] == "filter":
            skip = {(g, j) for g, row in enumerate(fl) for j, f in enumerate(row) if f[rule] and pr0[g][j] is not None}
            tr = walk_skip(us, pr0, skip)
            info["marked"] = len(skip)
        else:
            tr = walk_skip(us, priced(us, rule), set())
        trades[rule] = tr
        r = describe(tr, sess, phase)
        r.update(info)
        # twins: family A on the walk-forward window (design phase), the others on the final
        if spec["kind"] == "strike" and phase == "design":
            w = [t for t in tr if t["day"] >= wf_lo]
            r["twin_wf"] = summary_twin(w, S2.strike_twins(us, w, 1, K.TWINS, rng), "strike")
        if phase == "final":
            if spec["kind"] == "filter":
                r["twin"] = summary_twin(tr, skip_twins(us, pr0, info["marked"], K.TWINS, rng, lo, hi), "filter")
            elif spec["kind"] == "exit":
                r["twin"] = summary_twin(tr, S2.exit_twins(tr, plain, K.TWINS, rng), "exit")
            else:
                r["twin"] = summary_twin(tr, S2.strike_twins(us, tr, 1, K.TWINS, rng), "strike")
        out[rule] = r
        a = r["all"]
        print(arm, phase, rule, a["trades"], a["win"], a["net"], r.get("twin", r.get("twin_wf", "")), flush=True)
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{arm}_{phase}.pkl").write_bytes(pickle.dumps(trades, protocol=pickle.HIGHEST_PROTOCOL))
    # descriptive: the share of breaks each check marks and the coverage of the option features
    n_c = sum(1 for row in pr0 for t in row if t is not None)
    out["marked_share"] = {rl: round(sum(1 for g, row in enumerate(fl) for j, f in enumerate(row)
                                         if f[rl] and pr0[g][j] is not None) / max(n_c, 1), 4)
                           for rl in K.RULE_IDS if K.RULES[rl]["kind"] == "filter"}
    out["coverage"] = dict(candidates=n_c,
                           volume_ratio_known=sum(1 for g, row in enumerate(fl) for j, f in enumerate(row)
                                                  if f["_ratio"] is not None and pr0[g][j] is not None),
                           iv_known=sum(1 for g, row in enumerate(fl) for j, f in enumerate(row)
                                        if None not in f["_iv"] and pr0[g][j] is not None))
    return out


def run(phase: str, arms=K.ARMS):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R166 final already read once: " + K.FINAL_FLAG.read_text())
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
            print(arm, "final already read; skipped", flush=True)
            continue
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi), indent=1, default=str))
    if all((K.RESULTS / f"{phase}_{a}.json").exists() for a in K.ARMS):
        (K.RESULTS / f"{phase}_open").unlink(missing_ok=True)


def decide() -> dict:
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    rows, ps, keep = [], [], []
    for a, r in K.TESTS:
        rule = dict(design=d[a][r]["all"], wf=d[a][r]["wf"], final=f[a][r]["all"])
        base = dict(design=d[a]["B0"]["all"], wf=d[a]["B0"]["wf"], final=f[a]["B0"]["all"])
        fam_a = K.RULES[r]["fam"] == "A"
        per = ("wf",) if fam_a else ("wf", "final")
        tw = d[a][r]["twin_wf"] if fam_a else f[a][r]["twin"]
        route = L.routes(rule, base, per)
        p = L.p_of(route, tw["p_net"], tw["p_win"])
        ps.append(p)
        keep.append((a, r, rule, base, per, tw))
    m = len(K.TESTS) + K.N_PRIOR
    q_all, q_own = L2.bh(ps, m), L2.bh(ps)
    out = {}
    for (a, r, rule, base, per, tw), p, qa, qo in zip(keep, ps, q_all, q_own):
        g = L.gates(rule, base, float(qa), per, K.GATE_Q, K.MIN_FINAL)
        g.update(p=p, p_net=tw["p_net"], p_win=tw["p_win"], q_all=round(float(qa), 4), q_r166=round(float(qo), 4))
        out[f"{a}:{r}"] = g
        rows.append(dict(study="R166", test=f"{a}:{r}", arm=a, rule=r, family=K.FAMILY[K.RULES[r]["fam"]], p=p,
                         p_net=tw["p_net"], p_win=tw["p_win"], q_m2290=round(float(qa), 4), q_r166=round(float(qo), 4),
                         passed=g["passed"]))
    out["_m"] = dict(r166=len(K.TESTS), prior=K.N_PRIOR, total=m)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    with open(K.RESULTS / "raw_pvalues.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd, tuple(sys.argv[2:]) or K.ARMS)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1)[:3000])
    else:
        raise SystemExit(__doc__)
