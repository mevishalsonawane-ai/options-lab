"""R107 study (PREREG.md): the GEX meter and the danger meter used to trade MORE on the days they flag.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                 # R101's clean stores (to 13 Oct)
    python research/hunt/r107_meters_more/r107study.py design            # design + walk-forward, B1 parity asserted
    python research/hunt/r107_meters_more/r107study.py final             # the ONE final read (refuses a second)
    python research/hunt/r107_meters_more/r107study.py decide            # BH + gates -> results/decision.json

The arms come from R106's engine on R101's candidates (B1 must reproduce R101's published trades and net). Every
candidate is priced twice: at 1 lot (R101's numbers) and at 2 lots (the engine's fills and charges for a 2-lot order).
The meters and the book's maths are in r107meters.py / r107book.py (pure, tested).
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np

import r107cfg as K
import r107book as B
import r107meters as MT

_M: dict = {}


def mods():
    if not _M:
        import r106study as S
        M = S.mods()
        import r51cfg
        import r101cfg
        _M.update(S=S, X=M["X"], W=M["W"], C=M["C"], r51data=M["r51data"], r51cfg=r51cfg, r101cfg=r101cfg)
    return _M


# ------------------------------------------------------------------------------------------------ pricing
def price_lots(und: str, D, cands: list, ex, lots: int) -> list:
    """R106's engine (no rupee levels) with `lots` x the day's lot: the fill's half-spread and the charges are those of
    that order size."""
    M = mods()
    cfg = M["r51cfg"]
    orig = cfg.lot
    if lots != 1:
        cfg.lot = lambda u, d: lots * orig(u, d)
    try:
        return M["X"].price(und, D, cands, ex, None, "own")
    finally:
        cfg.lot = orig


def arm_units(arm: str, lo: date, hi: date) -> list:
    """R101's units with every priced candidate carrying net2 (2 lots) and its arm; asserts the 1-lot engine = R101."""
    M = mods()
    S = M["S"]
    units = S.prepare(arm, lo, hi)
    ex = S.arm_exits(arm)
    n2, moved = 0, 0
    for key, cands, res, how in units:
        D = M["r51data"].load(key[0])[key[1]]
        for c in cands:
            c["fx_idx"] = c.get("fx", -1)
            c["arm"] = arm
        one = price_lots(key[0], D, cands, ex, 1)
        two = price_lots(key[0], D, cands, ex, 2)
        for r, a, b in zip(res, one, two):
            assert (r is None) == (a is None) and (r is None or abs(r["net"] - a["net"]) < 1e-6), (arm, "engine parity")
            if r is None:
                continue
            assert b is not None, (arm, key, "a 2-lot order found no fill where 1 lot did")
            r["net2"] = float(b["net"])
            r["xr2"] = int(b["xr"])
            r["arm"] = arm
            n2 += 1
            moved += int(b["xr"] != r["xr"])
    print(arm, "priced at 2 lots:", n2, "candidates;", moved, "exit at another minute", flush=True)
    return units


def b1_trades(arm: str, units: list, phase: str) -> list:
    """Today's rule at 1 lot (R106 run_rule with no rule), checked against R101's published figure."""
    M = mods()
    tr = M["S"].run_rule(arm, units, [u[2] for u in units], None)
    pub = M["r101cfg"].PUBLISHED[arm].get(phase)
    n0, net0 = len(tr), round(sum(t["net"] for t in tr))
    if pub is not None:
        ok = (pub[0] is None or pub[0] == n0) and abs(pub[1] - net0) <= 1
        assert ok, (arm, phase, "B1 does not reproduce R101", n0, net0, pub)
    print(arm, phase, "B1", n0, net0, "published", pub, flush=True)
    return tr


def book_day_units(all_units: dict, d: date) -> tuple:
    """The paper book's units of one day in its tie order (arm, index, book), with their priced results."""
    rows = []
    for arm in K.BOOK_ARM_ORDER:
        for key, cands, res, how in all_units[arm].get(d, []):
            rows.append(((K.BOOK_ARM_ORDER.index(arm), K.UNDS.index(key[0]), key[2] if len(key) > 2 else 0),
                         (key, cands, how), res))
    rows.sort(key=lambda x: x[0])
    return [r[1] for r in rows], [r[2] for r in rows]


# ------------------------------------------------------------------------------------------------ meters
def meters(hi: date) -> dict:
    """GEX readings per index-day and the danger level per day, for every BANKNIFTY session of the stores up to `hi`
    (the phase's last day: the design run reads nothing of the final window)."""
    M = mods()
    gex = {u: {d: v for d, v in MT.load_gex(u).items() if d <= hi} for u in K.UNDS}
    bn = M["r51data"].load("BANKNIFTY")
    sessions_all = sorted(d for d in bn if d <= hi)
    bn_open, bn_last = {}, {}
    for d in sessions_all:
        D = bn[d]
        o = D.idx[0][np.isfinite(D.idx[0])]
        c = D.idx[3][np.isfinite(D.idx[3])]
        if len(o):
            bn_open[d] = float(o[0])
        if len(c):
            bn_last[d] = float(c[-1])
    vix_last = {d: v for d, v in vix_closes().items() if d <= hi}
    ins = MT.danger_inputs(sessions_all, bn_open, bn_last, vix_last)
    ev = MT.event_days(sessions_all)
    nf = M["r51data"].load("NIFTY")
    level, score = {}, {}
    for d in sessions_all:
        g, vp, vc = ins[d]
        expiry = bool((d in nf and nf[d].expiry) or (d in bn and bn[d].expiry))
        level[d], score[d] = MT.danger_read(g, vp, vc, d in ev, expiry, d)
    return dict(gex=gex, level=level, score=score, inputs=ins, events=sorted(ev))


def vix_closes() -> dict:
    import pandas as pd
    M = mods()
    lake = M["r101cfg"].LAKE
    parts = [pd.read_parquet(p, columns=["ts", "close"]) for p in sorted(lake.glob("index_1m_INDIAVIX_*.parquet"))]
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x = x[(ts.dt.hour * 60 + ts.dt.minute <= 15 * 60 + 30) & (x.close > 0)]
    return {d: float(g.sort_values("ts").close.iloc[-1]) for d, g in x.groupby("day")}


def gex_flag(gx: dict, und: str, d: date, er: int) -> bool:
    v0, v1 = gx[und].get(d, (float("nan"), float("nan")))
    return MT.is_neg(MT.gex_reading(int(er), v0, v1))


def slot_of(er: int) -> int:
    return 1 if er >= K.FIRST_ROW_1000 else 0


# ------------------------------------------------------------------------------------------------ evaluation
def _rk(r: dict) -> dict:
    return {k: (None if (isinstance(v, float) and not np.isfinite(v)) else (round(v, 4) if isinstance(v, float) else v))
            for k, v in r.items()}


def _summ(v: np.ndarray, sess: list, trades: int, changed: int) -> dict:
    out = B.risk(v, sess)
    out.update(trades=trades, changed=changed, sessions=len(sess))
    return out


def eval_size(trades: list, sess: list, flag_of, day_level: bool, mx: dict, rng, flag_day=None) -> dict:
    """A size test (2 lots on flagged trades) on one period: variant, B1, A2, twins."""
    base = B.daily_vector(trades, sess)
    two = B.daily_vector(trades, sess, lots_of=lambda t: True)
    var = B.daily_vector(trades, sess, lots_of=flag_of)
    changed = sum(1 for t in trades if flag_of(t))
    ix = {d: i for i, d in enumerate(sess)}
    if day_level:
        flags = np.array([flag_day(d) for d in sess], bool)
        delta = two - base
        perm = B.perm_matrix(K.TWINS, len(sess), rng)
        T = B.twin_day_size(base, delta, flags, perm)
    else:
        deltas, flags, perms = {}, {}, {}
        for u in K.UNDS:
            fl = np.zeros((len(sess), 2), bool)
            for d, i in ix.items():
                v0, v1 = mx["gex"][u].get(d, (float("nan"), float("nan")))
                fl[i] = (MT.is_neg(v0), MT.is_neg(v1))
            flags[u] = fl
            perms[u] = B.perm_matrix(K.TWINS, len(sess), rng)
        for t in trades:
            k = (t["und"], slot_of(t["er_sig"]))
            deltas.setdefault(k, np.zeros(len(sess)))[ix[t["day"]]] += t["net2"] - t["net"]
        T = B.twin_index_size(base, deltas, flags, perms)
    return finish(var, base, two, T, sess, len(trades), changed)


def finish(var, base, always, T, sess, n_trades, changed) -> dict:
    tr_dd, tr_wd = B.matrix_ratios(T)
    v = _summ(var, sess, n_trades, changed)
    b = _summ(base, sess, n_trades, 0)
    a = _summ(always, sess, n_trades, n_trades)
    med = dict(r_dd=float(np.median(tr_dd)), r_wd=float(np.median(tr_wd)), net=float(np.median(T.sum(axis=1))))
    v["twin_p"] = round(B.twin_p(v["r_dd"], tr_dd), 5)
    v["twin_p_wd"] = round(B.twin_p(v["r_wd"], tr_wd), 5)
    return dict(variant=_rk(v), base=_rk(b), always=_rk(a), twin_median=_rk(med))


def eval_book_slot(day_units: dict, sess: list, cap_fn_for, mx: dict, rng, day_level: bool, flag_day=None) -> dict:
    """A slot test on the paper book: cap 2 (B1), cap 3 every day (A3), the variant and twins. cap_fn_for(state) gives
    cap_of for one day; state = None (variant: the real flags), 'two', 'three', or a twin's code."""
    def walk(d, cap_of):
        us, pr = day_units.get(d, ([], []))
        return B.walk_cap(us, pr, cap_of) if us else []

    base_t, three_t, var_t = [], [], []
    for d in sess:
        base_t += walk(d, lambda c, r: K.CAP_TODAY)
        three_t += walk(d, lambda c, r: K.CAP_MORE)
        var_t += walk(d, cap_fn_for(d, None))
    base, three, var = (B.daily_vector(x, sess) for x in (base_t, three_t, var_t))
    def tkey(t):
        return t["und"], t["day"], t["arm"], t["er_sig"], t["side"], t.get("book")
    keyset = {tkey(t) for t in base_t}
    changed = sum(1 for t in var_t if tkey(t) not in keyset)
    if day_level:
        flags = np.array([flag_day(d) for d in sess], bool)
        perm = B.perm_matrix(K.TWINS, len(sess), rng)
        T = B.twin_day_size(base, three - base, flags, perm)
    else:
        n = len(sess)
        code = np.zeros((K.TWINS, n), np.int64)
        for ui, u in enumerate(K.UNDS):
            fl = np.array([[MT.is_neg(x) for x in mx["gex"][u].get(d, (float("nan"), float("nan")))] for d in sess], bool)
            pu = B.perm_matrix(K.TWINS, n, rng)
            code += (fl[:, 0][pu].astype(np.int64) * 2 + fl[:, 1][pu].astype(np.int64)) * (4 ** ui)
        T = np.zeros((K.TWINS, n))
        for k, d in enumerate(sess):
            col = code[:, k]
            cache = {}
            for c in np.unique(col):
                cache[int(c)] = float(sum(t["net"] for t in walk(d, cap_fn_for(d, int(c)))))
            T[:, k] = np.vectorize(cache.get)(col)
    out = finish(var, base, three, T, sess, len(var_t), changed)
    out["variant"]["trades_base"] = len(base_t)
    out["always"]["trades"] = len(three_t)
    return out, base_t, var_t


def code_flags(code: int) -> dict:
    """A twin's state code -> {und: (09:20 negative, 10:00 negative)}."""
    out = {}
    for ui, u in enumerate(K.UNDS):
        x = (code // (4 ** ui)) % 4
        out[u] = (bool(x // 2), bool(x % 2))
    return out


def describe(trades_by_arm: dict, book_t: list, sess: list, mx: dict) -> dict:
    """What each meter tells (descriptive): net per trade on flagged vs other trades, net per session on flagged vs
    other sessions, per arm and for the book; the share of flagged sessions per index."""
    out = {}
    lv = mx["level"]
    for name, tr in list(trades_by_arm.items()) + [("BOOK", book_t)]:
        g_f = [t["net"] for t in tr if gex_flag(mx["gex"], t["und"], t["day"], t["er_sig"])]
        g_o = [t["net"] for t in tr if not gex_flag(mx["gex"], t["und"], t["day"], t["er_sig"])]
        dv = B.daily_vector(tr, sess)
        fd = np.array([MT.is_flagged(lv.get(d)) for d in sess])
        d_f = [t["net"] for t in tr if MT.is_flagged(lv.get(t["day"]))]
        d_o = [t["net"] for t in tr if not MT.is_flagged(lv.get(t["day"]))]

        def m(x):
            return round(float(np.mean(x))) if len(x) else None
        out[name] = dict(
            gex_neg=dict(n=len(g_f), per_trade=m(g_f), net=round(sum(g_f)), win=round(float(np.mean(np.array(g_f) > 0)), 3) if g_f else None),
            gex_other=dict(n=len(g_o), per_trade=m(g_o), net=round(sum(g_o)), win=round(float(np.mean(np.array(g_o) > 0)), 3) if g_o else None),
            danger_flagged=dict(n=len(d_f), per_trade=m(d_f), sessions=int(fd.sum()), per_session=m(dv[fd]),
                                worst_day=round(float(dv[fd].min())) if fd.any() else None),
            danger_normal=dict(n=len(d_o), per_trade=m(d_o), sessions=int((~fd).sum()), per_session=m(dv[~fd]),
                               worst_day=round(float(dv[~fd].min())) if (~fd).any() else None))
    share = {}
    for u in K.UNDS:
        v = [mx["gex"][u].get(d) for d in sess]
        have = [x for x in v if x is not None]
        share[u] = dict(sessions=len(have), neg_0920=int(sum(MT.is_neg(x[0]) for x in have)),
                        neg_1000=int(sum(MT.is_neg(x[1]) for x in have)))
    out["_gex_share"] = share
    out["_danger_levels"] = {k: int(sum(1 for d in sess if lv.get(d) == k)) for k in ("normal", "careful", "paper-only")}
    return out


def evaluate(phase: str, lo: date, hi: date) -> dict:
    M = mods()
    S = M["S"]
    sess_phase = S.sessions_in(lo, hi)
    sess_all = S.sessions_in(K.START, hi)
    mx = meters(hi)
    units, trades = {}, {}
    for arm in K.ARMS:
        units[arm] = arm_units(arm, lo, hi)
        trades[arm] = b1_trades(arm, units[arm], phase)
    by_day = {arm: {} for arm in K.ARMS}
    for arm in K.ARMS:
        for u in units[arm]:
            by_day[arm].setdefault(u[0][1], []).append(u)
    day_units = {d: book_day_units(by_day, d) for d in sess_phase}
    periods = ("design", "wf") if phase == "design" else ("final",)
    out = {"_meta": dict(phase=phase, sessions=len(sess_phase), twins=K.TWINS, seed=K.SEED)}
    rng = np.random.default_rng(K.SEED + (0 if phase == "design" else 1))
    lv = mx["level"]
    flagged = lambda d: MT.is_flagged(lv.get(d))          # noqa: E731
    normal = lambda d: lv.get(d) == "normal"              # noqa: E731
    save = {"meters": dict(level=lv, score=mx["score"]), "trades": trades}
    for per in periods:
        sess = [d for d in sess_phase if K.in_period(d, per)]
        tr_p = {a: [t for t in trades[a] if K.in_period(t["day"], per)] for a in K.ARMS}
        res = {}
        for arm in K.ARMS:
            res[f"G2-{arm}"] = eval_size(tr_p[arm], sess, lambda t: gex_flag(mx["gex"], t["und"], t["day"], t["er_sig"]),
                                         False, mx, rng)
            res[f"D2-{arm}"] = eval_size(tr_p[arm], sess, lambda t: flagged(t["day"]), True, mx, rng, flagged)
            print(per, arm, "G2", res[f"G2-{arm}"]["variant"]["r_dd"], "D2", res[f"D2-{arm}"]["variant"]["r_dd"], flush=True)

        def g3_cap(d, code):
            fl = code_flags(code) if code is not None else None

            def cap_of(c, r):
                if fl is None:
                    neg = gex_flag(mx["gex"], c["und"], d, c["er"])
                else:
                    neg = fl[c["und"]][slot_of(c["er"])]
                return K.CAP_MORE if neg else K.CAP_TODAY
            return cap_of

        res["G3"], book_t, g3_t = eval_book_slot(day_units, sess, g3_cap, mx, rng, False)
        res["D3"], _, d3_t = eval_book_slot(day_units, sess, lambda d, code: (lambda c, r: K.CAP_MORE if flagged(d) else K.CAP_TODAY),
                                            mx, rng, True, flagged)
        res["D1"] = eval_size(book_t, sess, lambda t: normal(t["day"]), True, mx, rng, normal)
        print(per, "G3", res["G3"]["variant"]["r_dd"], "D3", res["D3"]["variant"]["r_dd"], "D1", res["D1"]["variant"]["r_dd"], flush=True)
        res["_describe"] = describe(tr_p, book_t, sess, mx)
        out[per] = res
        save[f"book_{per}"] = dict(base=book_t, g3=g3_t, d3=d3_t)
    if phase == "design":
        out["_meters"] = dict(events=[str(d) for d in mx["events"]],
                              levels={k: int(sum(1 for d in sess_all if lv.get(d) == k)) for k in ("normal", "careful", "paper-only")})
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{phase}.pkl").write_bytes(pickle.dumps(save, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def run(phase: str):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("R107 final already read once: " + K.FINAL_FLAG.read_text())
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    else:
        lo, hi = K.START, K.DESIGN_END
    out = evaluate(phase, lo, hi)
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    ps = [f["final"][t]["variant"]["twin_p"] for t in K.TEST_IDS]
    q_all = B.bh(ps, len(ps) + K.N_PRIOR)
    q_own = B.bh(ps)
    out = {}

    def num(x):
        return np.inf if x is None else x

    for t, qa, qo in zip(K.TEST_IDS, q_all, q_own):
        per = {}
        for p, src in (("design", d), ("wf", d), ("final", f)):
            r = src[p][t]
            per[p] = tuple({k: num(r[s].get(k)) for k in ("net", "r_dd", "r_wd")}
                           for s in ("variant", "base", "always", "twin_median"))
        g = B.gates(per, qa, f["final"][t]["variant"]["changed"])
        g["q_r107"] = round(float(qo), 4)
        g["twin_p"] = f["final"][t]["variant"]["twin_p"]
        fv, fb = f["final"][t]["variant"], f["final"][t]["base"]
        g["account"] = dict(final_months_below=fv.get("months_below"), base_months_below=fb.get("months_below"),
                            final_dd_over=fv.get("dd_over"), base_dd_over=fb.get("dd_over"))
        out[t] = g
    out["_m"] = dict(r107=len(ps), prior=K.N_PRIOR, total=len(ps) + K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
