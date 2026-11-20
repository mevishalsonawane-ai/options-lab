"""R204 / R205 study (PREREG.md): an earlier breakeven (BE500), an earlier time stop (ET10 / ET15) and Liquidity without
the MIDCPNIFTY 5-minute book (NOM5) on top of today's rules (V5); and the three arms replayed through one pot of money.

    python3 -I research/hunt/r204_be_time_funds/r204study.py design    # 21 Nov 2024 - 31 Mar 2026 -> results/design.json
    python3 -I research/hunt/r204_be_time_funds/r204study.py final     # the second look at the holdout -> results/final.json
    python3 -I research/hunt/r204_be_time_funds/r204study.py decide    # the gate -> results/decision.json
    python3 -I research/hunt/r204_be_time_funds/r204study.py funds     # R205 -> results/funds.json (needs `final`)

Reuses R202's glue (r202study: units, pricing, walk, twins) and R186's engine, unchanged. The breakeven level is added by
wrapping r186engine.lock_levels only while a BE500 variant is priced. Pure parts in r204lib.py. Plain-English notes,
20 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r204cfg as K  # noqa: E402

import numpy as np  # noqa: E402

import r202study as S2  # noqa: E402  (sets the environment and imports the R186 stack)
import r202lib as G  # noqa: E402
import r204lib as L  # noqa: E402

K2 = K.K2
R7, S6 = S2.R7, S2.S6
WINDOWS = K2.WINDOWS


# ================================================================================================ the two exits
@contextmanager
def breakeven_level():
    """While active, every lock ladder the engine builds also holds BE500's level: trigger = the premium that nets
    +Rs 500 a lot, stop = the premium whose stop sale nets Rs 0 (R186's own breakeven level)."""
    import r186engine as Z
    orig = Z.lock_levels

    def patched(mode, real, qty, lots, ch, tks, hmax=np.inf):
        rt, rl = orig(mode, real, qty, lots, ch, tks, hmax)
        trig = Z.X.target_price(real, qty, K.BE_AT * lots, ch)
        lock = Z.X.stop_price(real, qty, -0.0, ch, tks, Z.E.SLIP[2], Z.E.SLIP[3])
        return L.add_level(rt, rl, trig, lock)

    Z.lock_levels = patched
    try:
        yield
    finally:
        Z.lock_levels = orig


def et_row(arm: str, und: str, D, c: dict, minutes: int) -> int:
    """The early time stop's sale minute for one candidate (-1 = it got going, or no fill)."""
    import r186engine as Z
    ex = S6.exits(arm)
    st = Z.E.stack(und, D)
    ci = st.idx(c["side"], c["strike"])
    if ci < 0:
        return -1
    C = st.S[ci]
    qty = float(Z.K51.lot(und, D.d))
    raw, real, ef, tks = Z.X.entry_info(C, int(c["er"]), int(Z.E.sq_row(D, ex)), qty)
    if ef < 0:
        return -1
    need = Z.X.target_price(real, qty, K.ET_NEED, Z._charges_fn(und, qty, D.d))
    return L.no_go_row(C[1], int(ef), minutes, float(need))


def with_time_stop(arm: str, usA: list, minutes: int) -> list:
    days = R7.mods()["r51data"].load
    out = []
    for key, cands, res, how in usA:
        D = days(key[0])[key[1]]
        nc = []
        for c in cands:
            c2 = dict(c)
            c2["fx"] = G.earlier(c["fx_arm"], et_row(arm, key[0], D, c, minutes))
            nc.append(c2)
        out.append((key, nc, res, how))
    return out


def rng_for(arm: str, rule: str, w: str):
    return np.random.default_rng([K.SEED, K.ARMS.index(arm), K.RULES.index(rule), list(WINDOWS).index(w)])


# ================================================================================================ the run
def evaluate(phase: str) -> dict:
    final = phase == "final"
    S2.boot(final)
    lo, hi = K2.SPAN if final else K2.DESIGN
    wins = ("design", "holdout", "total", "last_year") if final else ("design",)
    twin_wins = ("design", "holdout") if final else ("design",)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), phase=phase, span=f"{lo} .. {hi}", twins=K2.TWINS,
                         seed=K.SEED, m_total=K.M_TOTAL, note="the holdout is a second look (PREREG section 0)")}
    keep_all = {}
    for arm in K.ARMS:
        us0, usA = S2.units(arm, lo, hi)
        if arm in K2.PICK_THR:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
            assert abs(thr - K2.PICK_THR[arm]) < 1e-12
            keep_fn = (lambda P, sc, thr: lambda t: P.kept(t, sc, thr))(P, sc, thr)
        else:
            keep_fn = lambda t: True  # noqa: E731
        prB = S2.price(arm, usA)
        plainB = S2.lookup(usA, prB)
        base = S2.walk(arm, usA, prB, lo, hi, keep_fn)
        res = {"B": dict(windows={w: G.stats(G.in_window(base, *WINDOWS[w]), *WINDOWS[w]) for w in wins},
                         folds=[G.stats(G.in_window(base, *f), *f) for f in K2.FOLDS])}
        keep_all[arm + ":B"] = base
        print(arm, "B", {w: (res["B"]["windows"][w]["trades"], res["B"]["windows"][w]["net"]) for w in wins}, flush=True)
        for rule in K.RULES:
            if (arm, rule) not in K.TESTS:
                continue
            r: dict = dict(changed={}, effect={}, twin={})
            if rule in K.EXIT_RULES:
                if rule == "BE500":
                    with breakeven_level():
                        pr = S2.price(arm, usA)
                    tr = S2.walk(arm, usA, pr, lo, hi, keep_fn)
                else:
                    us = with_time_stop(arm, usA, K.ET_MIN[rule])
                    tr = S2.walk(arm, us, S2.price(arm, us), lo, hi, keep_fn)
                for w in wins:
                    tw_ = G.in_window(tr, *WINDOWS[w])
                    ch_ = [(t, plainB[S2.key_t(t)]) for t in tw_ if S2.is_changed(t, plainB.get(S2.key_t(t)))]
                    r["changed"][w] = len(ch_)
                    r["effect"][w] = dict(saved_on_losers=round(sum(t["net"] - p["net"] for t, p in ch_ if p["net"] <= 0)),
                                          given_up_on_winners=round(sum(t["net"] - p["net"] for t, p in ch_ if p["net"] > 0)),
                                          winners_turned_losers=sum(1 for t, p in ch_ if p["net"] > 0 and t["net"] <= 0))
                    if w in twin_wins:
                        tot, n_ch, _, _ = S2.exit_twins(arm, tw_, plainB, rng_for(arm, rule, w))
                        assert n_ch == len(ch_)
                        r["twin"][w] = S2.twin_row(float(sum(t["net"] for t in tw_)), tot)
            else:
                keep = np.array([L.keep_book(t) for t in base], bool)
                tr = [t for t, k in zip(base, keep) if k]
                for w in wins:
                    bw = G.in_window(base, *WINDOWS[w])
                    rem = [t for t in bw if not L.keep_book(t)]
                    r["changed"][w] = len(rem)
                    r["effect"][w] = dict(removed_net=round(sum(t["net"] for t in rem)),
                                          removed_losers=sum(1 for t in rem if t["net"] <= 0))
                    if w in twin_wins:
                        tot = G.skip_twins(np.array([t["net"] for t in bw], float), len(rem), K2.TWINS, rng_for(arm, rule, w))
                        r["twin"][w] = S2.twin_row(float(sum(t["net"] for t in bw if L.keep_book(t))), tot)
            r["windows"] = {w: G.stats(G.in_window(tr, *WINDOWS[w]), *WINDOWS[w]) for w in wins}
            r["folds"] = [G.stats(G.in_window(tr, *f), *f) for f in K2.FOLDS]
            res[rule] = r
            keep_all[f"{arm}:{rule}"] = tr
            print(arm, rule, {w: (r["windows"][w]["trades"], r["windows"][w]["net"], r["changed"][w]) for w in wins},
                  {w: r["twin"][w]["p"] for w in twin_wins}, flush=True)
        out[arm] = res
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"r204_trades_{phase}.pkl").write_bytes(pickle.dumps(keep_all, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def design() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    out = evaluate("design")
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def final() -> dict:
    if not (K.RESULTS / "design.json").exists():
        raise SystemExit("design first (PREREG 5)")
    if K.HOLDOUT_FLAG.exists() and (K.RESULTS / "final.json").exists():
        raise SystemExit("R204 holdout already read: " + K.HOLDOUT_FLAG.read_text())
    if not K.HOLDOUT_FLAG.exists():
        K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + " (a second look: PREREG section 0)\n")
    out = evaluate("final")
    d = json.loads((K.RESULTS / "design.json").read_text())
    out["_meta"]["design_mismatch"] = [f"{a}:{r}" for a in K.ARMS for r in ("B",) + K.RULES
                                       if r in out[a] and out[a][r]["windows"]["design"] != d[a][r]["windows"]["design"]]
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((K.RESULTS / "final.json").read_text())
    ps = [G.p_for_bh(r[a][rule]["changed"]["holdout"], r[a][rule]["twin"]["holdout"]["p_normal"]) for a, rule in K.TESTS]
    qs = G.bh_total(ps, K.M_TOTAL)
    qf = G.bh_total(ps, len(K.TESTS))
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for (a, rule), p, q, q10 in zip(K.TESTS, ps, qs, qf):
        v, b = r[a][rule], r[a]["B"]
        g = G.gate(v["windows"], b["windows"], v["folds"], b["folds"], v["changed"], v["twin"]["holdout"]["p"], q)
        g.update(arm=a, rule=rule, p_normal=p, q_family=q10, design_twin_p=v["twin"]["design"]["p"])
        out["tests"][f"{a}:{rule}"] = g
    out["clears"] = [k for k, g in out["tests"].items() if g["passes"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


# ================================================================================================ R205
def month_of(t) -> str:
    return f"{t['day'].year:04d}-{t['day'].month:02d}"


def funds() -> dict:
    """PREREG section 4: the baseline trades of the final run through one pot of money, and Liquidity from 1 April 2026."""
    tr = pickle.loads((K.OUT / "r204_trades_final.pkl").read_bytes())
    B = {a: tr[a + ":B"] for a in K.ARMS}
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), order=list(K.ARM_ORDER),
                         note="descriptive; 1 lot; every day starts with the account size; no re-walk (PREREG 4)")}
    for name, arms in K.SETS.items():
        rows = {}
        for acc in K.ACCOUNTS:
            taken, refused, peak = L.funded_replay({a: B[a] for a in arms}, acc, tuple(a for a in K.ARM_ORDER if a in arms))
            row = dict(most_money_in_use=round(peak))
            for w, (lo, hi) in WINDOWS.items():
                tw, rw = G.in_window(taken, lo, hi), G.in_window(refused, lo, hi)
                s = G.stats(tw, lo, hi)
                row[w] = dict(taken=s["trades"], not_taken=len(rw), not_taken_net=round(sum(t["net"] for t in rw)),
                              win=s["win"], net=s["net"], max_dd=s["max_dd"], worst_month=s["worst_month"],
                              worst_day=s["worst_day"],
                              by_arm={a: dict(taken=sum(1 for t in tw if t["arm"] == a), not_taken=sum(1 for t in rw if t["arm"] == a),
                                              net=round(sum(t["net"] for t in tw if t["arm"] == a))) for a in arms},
                              net_pct_of_account=None if acc is None else round(s["net"] / acc * 100, 1),
                              max_dd_pct_of_account=None if acc is None else round(s["max_dd"] / acc * 100, 1))
            rows["no limit" if acc is None else str(int(acc))] = row
        out[name] = rows
    liq = [t for t in B["LIQ"] if t["day"] >= K.LIQ_FROM]
    by_m, by_b = defaultdict(list), defaultdict(list)
    for t in liq:
        by_m[month_of(t)].append(t["net"])
        by_b[f"{t['und']} {t['book']}-minute"].append(t["net"])

    def brief(v):
        v = np.array(v, float)
        return dict(trades=len(v), win=round(float((v > 0).mean()) * 100, 1), net=round(float(v.sum())))
    out["liquidity_from_april_2026"] = dict(all=brief([t["net"] for t in liq]), by_month={k: brief(v) for k, v in sorted(by_m.items())},
                                            by_book={k: brief(v) for k, v in sorted(by_b.items())},
                                            stats=G.stats(liq, K.LIQ_FROM, K2.SPAN[1]))
    (K.RESULTS / "funds.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    {"design": design, "final": final, "decide": decide, "funds": funds}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
