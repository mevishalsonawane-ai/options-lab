"""R167 study (PREREG.md + Amendment A): smart exits on the Donchian arms vs today's exits, 1-3 lots.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                    # R51's clean stores (to 13 Oct)
    python research/hunt/r167_donchian_smart_exits/r167data.py build          # day measurements, OI, VIX
    python research/hunt/r167_donchian_smart_exits/r167study.py design        # design + walk-forward; B0 parity asserted
    python research/hunt/r167_donchian_smart_exits/r167study.py final         # the ONE final read (refuses a second)
    python research/hunt/r167_donchian_smart_exits/r167study.py decide        # BH + gates -> decision.json, raw_pvalues.csv
    python research/hunt/r167_donchian_smart_exits/r167study.py picker        # second look (R151's pickers), descriptive
    python research/hunt/r167_donchian_smart_exits/r167report.py              # results/report.md

Candidates, the walk, statistics and parity are R152's (r152study). CTX and ML are trained only on design days before
the window they score (r167cfg.train_window). 12 Nov 2026.
"""
from __future__ import annotations

import csv
import json
import pickle
import sys
from datetime import datetime, timezone

import numpy as np

import r167cfg as K
import r167lib as L

_M: dict = {}


def mods():
    if not _M:
        import r152study as R
        import r167data as DT
        import r167engine as EN
        M = R.mods()
        _M.update(R=R, DT=DT, EN=EN, S=M["S"], C=M["C"], r51data=M["r51data"], E=M["E"])
    return _M


# ================================================================================================ pricing
def price_units(us: list, rule: str, ver: str, lots: int, extra_of=None) -> list:
    """[priced list per unit] under (rule, ver); extra_of(day) -> the CTX / ML training products for that day."""
    M = mods()
    b0 = M["R"].exits()
    out = []
    for key, cands, _, _ in us:
        und, day = key[0], key[1]
        D = M["r51data"].load(und)[day]
        cx = M["DT"].ctx(und).get(day)
        if cx is None and rule != "B0":
            raise RuntimeError(f"no day measurements for {und} {day}: run r167data.py build")
        ex = extra_of(day) if extra_of is not None else None
        out.append(M["EN"].price(und, D, cx, M["DT"].oi_of(und, day), cands, rule, ver, b0, lots, ex))
    return out


def walk(us: list, pr: list) -> list:
    return mods()["R"].walk(us, pr)


def plain_map(us: list, pr: list) -> dict:
    """{(und, day, er_sig, side): the candidate's trade priced by B0 at the same lots}."""
    return {(t["und"], t["day"], int(c["er"]), t["side"]): t
            for (key, cands, _, _), row in zip(us, pr) for c, t in zip(cands, row) if t is not None}


# ================================================================================================ training (CTX, ML)
_TRAIN: dict = {}


def train(arm: str, lo, hi, design_units: list) -> dict:
    """CTX's per-regime choices and ML's model, from B0's walked trades at 1 lot on [lo, hi] (design days only)."""
    key = (arm, lo, hi)
    if key in _TRAIN:
        return _TRAIN[key]
    assert hi <= K.DESIGN_END, "training never reads the final period"
    M = mods()
    b0 = M["R"].exits()
    us = [u for u in design_units if lo <= u[0][1] <= hi]
    tr = walk(us, price_units(us, "B0", "T", 1))
    # VIX median over the training days (each day's last close)
    vx = []
    for u in us:
        cx = M["DT"].ctx(u[0][0]).get(u[0][1])
        if cx is not None and np.isfinite(cx.vix).any():
            vx.append(float(cx.vix[np.flatnonzero(np.isfinite(cx.vix))[-1]]))
    vix_med = float(np.median(vx)) if vx else float("inf")
    rows_t, rows_f, Xs, ys = [], [], [], []
    for t in tr:
        und, day = t["und"], t["day"]
        D = M["r51data"].load(und)[day]
        cx = M["DT"].ctx(und)[day]
        cand = [dict(side=t["side"], strike=t["strike"], er=t["er_sig"])]
        oi_of = M["DT"].oi_of(und, day)
        reg = L.regimes(cx, vix_med)[max(t["er"] - 1, 0)]
        res_t, res_f = {"B0": t["net"]}, {"B0": t["net"]}
        for it in K.CTX_MENU_T:
            if it == "B0":
                continue
            p = M["EN"].price(und, D, cx, oi_of, cand, it, "T", b0, 1)[0]
            res_t[it] = p["net"] if p else 0.0
            p = M["EN"].price(und, D, cx, oi_of, cand, it, "F", b0, 1)[0]
            res_f[it] = p["net"] if p else 0.0
        rows_t.append(dict(regime=int(reg), res=res_t))
        rows_f.append(dict(regime=int(reg), res=res_f))
        st = M["E"].stack(und, D)
        C = st.S[st.idx(t["side"], t["strike"])]
        s = 1 if t["side"] == "C" else -1
        sq = M["E"].sq_row(D, b0)
        vm = L.vol_median(C[4])
        oi = oi_of(t["side"], t["strike"])
        raw = float(t["E"])
        for m in L.ml_rows(int(t["er"]), sq):
            y = L.ml_label(cx, s, m, sq)
            if y == y:
                Xs.append(L.ml_features(cx, s, int(t["er"]), m, C, oi, raw, vm))
                ys.append(y)
    model = L.ExitModel().fit(np.array(Xs), np.array(ys))
    out = dict(vix_med=vix_med, choice_T=L.choose_menu(rows_t, K.CTX_MENU_T), choice_F=L.choose_menu(rows_f, K.CTX_MENU_F),
               model=model, n_trades=len(tr), n_rows=len(ys), base_rate=float(np.mean(ys)) if ys else None,
               theta=model.theta, window=(str(lo), str(hi)))
    print(arm, "trained", out["window"], "trades", len(tr), "rows", len(ys), "CTX-T", out["choice_T"],
          "CTX-F", out["choice_F"], "theta", round(model.theta, 4), flush=True)
    _TRAIN[key] = out
    return out


def extra_for(arm: str, scored: str, design_units: list):
    def f(day):
        lo, hi = K.train_window(day, scored)
        return train(arm, lo, hi, design_units)
    return f


def train_summary(t: dict) -> dict:
    return {k: v for k, v in t.items() if k != "model"}


# ================================================================================================ describe
def describe(tr: list, base_tr: list | None, plain: dict, nsess: int, wf: bool) -> dict:
    r = dict(L.stats(tr), moved=L.moved(tr, plain) if base_tr is not None else None, giveback=L.giveback(tr),
             sessions=nsess)
    if wf:
        r["wf_q"] = [round(sum(t["net"] for t in tr if a <= t["day"] <= b)) for a, b in K.WF_Q]
    return r


def twins(tr: list, plain: dict, n: int, rng) -> dict:
    """PREREG section 6: twin net totals and win rates; p on each and the larger."""
    M = mods()
    tot = np.zeros(n)
    wins = np.zeros(n)
    changed = 0
    for t in tr:
        b = plain.get(L.tkey(t))
        v = None
        if b is not None:
            D = M["r51data"].load(t["und"])[t["day"]]
            v = M["EN"].twin_nets(t["und"], D, t, b, n, rng)
        if v is None:
            tot += t["net"]
            wins += t["net"] > 0
        else:
            changed += 1
            tot += v
            wins += v > 0
    nt = max(len(tr), 1)
    act_net = sum(t["net"] for t in tr)
    act_win = sum(t["net"] > 0 for t in tr) / nt
    jp = L.joint_p(act_net, act_win, tot, wins / nt)
    return dict(jp, changed=changed, twin_mean_net=round(float(tot.mean())), twin_mean_win=round(float((wins / nt).mean()), 4))


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def design(arm: str) -> dict:
    M = mods()
    R = M["R"]
    us = R.units(arm, K.START, K.DESIGN_END)
    out = {"parity": R.parity(arm, "design", us)}
    sess = R.sessions_in(K.START, K.DESIGN_END)
    wf_sess = [d for d in sess if d >= K.WF_START]
    us_wf = [u for u in us if u[0][1] >= K.WF_START]
    res0 = [u[2] for u in us]
    trades = {}
    for lots in K.LOTS:
        pr0 = price_units(us, "B0", "T", lots)
        if lots == 1:            # the R167 engine with no rule prices exactly as R101 / R152 did
            for a_, b_ in zip(pr0, res0):
                for x, y in zip(a_, b_):
                    assert (x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6), "engine parity"
        b0 = walk(us, pr0)
        plain = plain_map(us, pr0)
        trades[("B0", "T", lots)] = b0
        out[f"B0@{lots}"] = dict(design=describe(b0, None, plain, len(sess), False),
                                 wf=describe([t for t in b0 if t["day"] >= K.WF_START], None, plain, len(wf_sess), True))
        for rule, ver in K.VARIANTS:
            if rule in K.LEARNED:
                tr_d = walk(us, price_units(us, rule, ver, lots, extra_for(arm, "design", us)))
                tr_w = walk(us_wf, price_units(us_wf, rule, ver, lots, extra_for(arm, "wf", us)))
            else:
                tr_d = walk(us, price_units(us, rule, ver, lots))
                tr_w = [t for t in tr_d if t["day"] >= K.WF_START]
            trades[(rule, ver, lots)] = (tr_d, tr_w)
            out[f"{rule}-{ver}@{lots}"] = dict(design=describe(tr_d, b0, plain, len(sess), False),
                                               wf=describe(tr_w, b0, plain, len(wf_sess), True),
                                               in_sample_design=rule in K.LEARNED)
            print(arm, "design", rule, ver, lots, len(tr_d), round(sum(t["net"] for t in tr_d)), "wf", len(tr_w),
                  round(sum(t["net"] for t in tr_w)), flush=True)
    out["training"] = {f"{k[1]}..{k[2]}": train_summary(v) for k, v in _TRAIN.items() if k[0] == arm}
    _save(f"{arm}_design", trades)
    return out


def final(arm: str) -> dict:
    M = mods()
    R = M["R"]
    us_d = R.units(arm, K.START, K.DESIGN_END)          # training only
    M["C"].open_final()
    us = R.units(arm, K.FINAL_START, K.FINAL_END)
    out = {"parity": R.parity(arm, "final", us)}
    sess = R.sessions_in(K.FINAL_START, K.FINAL_END)
    trades = {}
    for lots in K.LOTS:
        pr0 = price_units(us, "B0", "T", lots)
        b0 = walk(us, pr0)
        plain = plain_map(us, pr0)
        trades[("B0", "T", lots)] = b0
        out[f"B0@{lots}"] = dict(final=describe(b0, None, plain, len(sess), False))
        for i, (rule, ver) in enumerate(K.VARIANTS):
            ex = extra_for(arm, "final", us_d) if rule in K.LEARNED else None
            tr = walk(us, price_units(us, rule, ver, lots, ex))
            trades[(rule, ver, lots)] = tr
            rng = np.random.default_rng([K.SEED, K.ARMS.index(arm), i, lots])
            out[f"{rule}-{ver}@{lots}"] = dict(final=describe(tr, b0, plain, len(sess), False),
                                               twin=twins(tr, plain, K.TWINS, rng))
            print(arm, "final", rule, ver, lots, len(tr), round(sum(t["net"] for t in tr)), out[f"{rule}-{ver}@{lots}"]["twin"],
                  flush=True)
    out["training"] = {f"{k[1]}..{k[2]}": train_summary(v) for k, v in _TRAIN.items() if k[0] == arm}
    _save(f"{arm}_final", trades)
    return out


def run(phase: str):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R167 final already read once: " + K.FINAL_FLAG.read_text())
        assert all((K.RESULTS / f"design_{a}.json").exists() for a in K.ARMS), "design first"
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("arms in progress\n")
    for arm in K.ARMS:
        if (K.RESULTS / f"{phase}_{arm}.json").exists():
            continue
        r = design(arm) if phase == "design" else final(arm)
        (K.RESULTS / f"{phase}_{arm}.json").write_text(json.dumps(r, indent=1, default=str))
    if phase == "final":
        (K.RESULTS / "final_open").unlink(missing_ok=True)


# ================================================================================================ decide
def decide_rows(design: dict, final: dict) -> list:
    """[(arm, rule, ver, lots, p, p_net, p_win, rule stats by period, B0 stats by period)] for every test."""
    out = []
    for arm, rule, ver, lots in K.TESTS:
        d, f = design[arm], final[arm]
        k = f"{rule}-{ver}@{lots}"
        r = dict(design=d[k]["design"], wf=d[k]["wf"], final=f[k]["final"])
        b = dict(design=d[f"B0@{lots}"]["design"], wf=d[f"B0@{lots}"]["wf"], final=f[f"B0@{lots}"]["final"])
        tw = f[k]["twin"]
        out.append((arm, rule, ver, lots, tw["p"], tw["p_net"], tw["p_win"], r, b))
    return out


def decide() -> dict:
    design = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    final = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    rows = decide_rows(design, final)
    ps = [r[4] for r in rows]
    q = L.bh(ps, K.M_TOTAL)
    q_alone = L.bh(ps)
    tests = []
    with open(K.RESULTS / "raw_pvalues.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["study", "arm", "rule", "version", "lots", "p", "p_net", "p_win", "final_trades", "final_win",
                    "ref_final_win", "final_net", "ref_final_net", f"q_r167_m{K.M_TOTAL}", "q_r167_alone"])
        for (arm, rule, ver, lots, p, pn, pw, r, b), qq, qa in zip(rows, q, q_alone):
            g = L.gates(r, b, float(qq), K.WIN_UP, K.GATE_Q, K.MIN_FINAL)
            w.writerow(["R167", arm, rule, ver, lots, round(p, 6), round(pn, 6), round(pw, 6), r["final"]["trades"],
                        r["final"]["win"], b["final"]["win"], r["final"]["net"], b["final"]["net"], round(float(qq), 6),
                        round(float(qa), 6)])
            tests.append(dict(arm=arm, rule=rule, version=ver, lots=lots, p=p, p_net=pn, p_win=pw, q=float(qq),
                              q_alone=float(qa), live_eligible=(ver == "T"), **g))
    dec = dict(m_total=K.M_TOTAL, prior=K.PRIOR_TESTS, tests=tests, passed=[t for t in tests if t["passed"]])
    (K.RESULTS / "decision.json").write_text(json.dumps(dec, indent=1, default=str))
    print("passed", len(dec["passed"]), "of", len(tests), flush=True)
    return dec


# ================================================================================================ second look
def picker() -> dict:
    """PREREG section 7 second look (descriptive): every rule on the trades R151's app pickers take, Apr - Oct 2026."""
    assert K.FINAL_FLAG.exists(), "the second look follows R167's one final read"
    import r152picker as PK
    M = mods()
    R = M["R"]
    M["C"].open_final()
    sess = R.sessions_in(K.FINAL_START, K.FINAL_END)
    out = {"_meta": dict(period=f"{K.FINAL_START} .. {K.FINAL_END}", sessions=len(sess),
                         note="second look: Apr - Oct 2026 was read many times; the pickers failed their gates")}
    for arm in K.ARMS:
        us_d = R.units(arm, K.START, K.DESIGN_END)
        us = R.units(arm, K.FINAL_START, K.FINAL_END)
        sc, thr = PK.scores(arm, us)
        a = {}
        for lots in K.LOTS:
            pr0 = price_units(us, "B0", "T", lots)
            plain = plain_map(us, pr0)
            b0 = [t for t in walk(us, pr0) if PK.kept(t, sc, thr)]
            a[f"B0@{lots}"] = describe(b0, None, plain, len(sess), False)
            for rule, ver in K.VARIANTS:
                ex = extra_for(arm, "final", us_d) if rule in K.LEARNED else None
                tr = [t for t in walk(us, price_units(us, rule, ver, lots, ex)) if PK.kept(t, sc, thr)]
                a[f"{rule}-{ver}@{lots}"] = describe(tr, b0, plain, len(sess), False)
            print(arm, "picker lots", lots, "done", flush=True)
        out[arm] = a
    (K.RESULTS / "picker.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        decide()
    elif cmd == "picker":
        picker()
    else:
        raise SystemExit(__doc__)
