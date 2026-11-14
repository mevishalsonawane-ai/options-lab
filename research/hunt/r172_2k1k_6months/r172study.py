"""R172 study (PREREG.md): the last 6 months (20 Apr - 19 Oct 2026) of Liquidity 15+5, R72 and R51 (+ Supertrend), 1-3
lots: (a) today's rules, (b) + R151's picker as live (R72 / R51), and both with Boss's +Rs 2,000 / -Rs 1,000.

    R51_CLEAN=1 python research/hunt/r106_small_targets/r106study.py post-build   # stores to 19 Oct (clean_post)
    python research/hunt/r172_2k1k_6months/r172study.py check    # engine check on 1 Apr - 13 Oct vs the published finals
    python research/hunt/r172_2k1k_6months/r172study.py run      # the report's numbers -> results/r172.json

A reporting backtest of a rule Boss already chose: no gate, no twin, no BH (PREREG). Pure parts (exit mix, months, worst
month, the arms summed) take plain lists; test_r172.py covers them.
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from datetime import date

import r172cfg as K

_M: dict = {}


def mods():
    if not _M:
        import r152study as R
        M = R.mods()
        import r172engine as Z
        _M.update(M, R=R, Z=Z)
        _M["C"].open_final()          # the window is inside / after the final period, read many times (PREREG)
    return _M


# ================================================================================================ pure
def exit_mix(trades: list) -> dict:
    """Rs 2,000 target / Rs 1,000 stop / other (other split: the arm's own premium stop, everything else)."""
    c = Counter(t.get("why", "?") for t in trades)
    own_stop = c.get("stop", 0)
    other = sum(v for k, v in c.items() if k not in ("target2k", "stop1k"))
    return dict(target2k=c.get("target2k", 0), stop1k=c.get("stop1k", 0), other=other, own_stop=own_stop,
                other_rest=other - own_stop, reasons=dict(sorted(c.items())))


def monthly(trades: list) -> dict:
    """Net per calendar month over the window's months (a month with no trade is Rs 0)."""
    out = {m: 0.0 for m in K.MONTHS}
    for t in trades:
        out[K.month_of(t["day"])] = out.get(K.month_of(t["day"]), 0.0) + t["net"]
    return {m: round(v) for m, v in out.items()}


def worst_month(trades: list):
    m = monthly(trades)
    k = min(m, key=lambda x: m[x])
    return k, m[k]


def avg_net(trades: list, why: str):
    """Average net (after charges and slippage) of the trades that exited by `why`; None when there are none."""
    x = [t["net"] for t in trades if t.get("why") == why]
    return round(sum(x) / len(x)) if x else None


def describe(trades: list, sessions: int) -> dict:
    S = mods()["S"]
    s = S.stats(trades, sessions)
    wm = worst_month(trades)
    stops = [t for t in trades if t.get("why") == "stop1k"]
    return dict(trades=s["trades"], win=s["win"], net=s["net"], avg_win=s["avg_win"], avg_loss=s["avg_loss"],
                worst_day=s["worst_day"], max_dd=s["max_dd"], largest_loss=s["largest_loss"], per_session=s["per_session"],
                green_days=s["green_days"], worst_month=dict(month=wm[0], net=wm[1]), months=monthly(trades),
                mix=exit_mix(trades),
                stop1k_worst_net=(round(min(t["net"] for t in stops)) if stops else None),
                stop1k_over_1k=sum(1 for t in stops if t.get("gross", 0) < -1000.0 - 1e-6),
                stop1k_avg_net=avg_net(trades, "stop1k"), target2k_avg_net=avg_net(trades, "target2k"),
                rupee_stop_used=(round(sum(t.get("stop_kind") == "rupee" for t in trades) / len(trades), 4)
                                 if trades and any("stop_kind" in t for t in trades) else None))


def together(by_arm: dict) -> list:
    """The arms' trades summed (each arm trades on its own; their trades simply add up)."""
    out = []
    for a in K.ARMS:
        out += [dict(t, arm=a) for t in by_arm.get(a, [])]
    out.sort(key=lambda t: (t["day"], t["arm"]))
    return out


# ================================================================================================ data
def units(arm: str, lo: date, hi: date) -> list:
    M = mods()
    if arm == "LIQ":
        return M["S"].prepare("LIQ", lo, hi)
    return M["S12"].units_of(arm, lo, hi)


def exits(arm: str):
    return mods()["S"].arm_exits(arm)


def priced(arm: str, us: list, on: bool, lots: int) -> list:
    M = mods()
    ex = exits(arm)
    return [M["Z"].price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, on, lots) for key, cands, _, _ in us]


def walk(arm: str, us: list, pr: list) -> list:
    tr = mods()["S"].run_rule(arm, us, pr, None)     # today's taking walk; no re-entries
    for t in tr:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    return tr


def picker():
    import r152picker as P                             # R151's app model numbers and the app's keep rule
    return P


def sessions(lo: date, hi: date) -> list:
    return mods()["S"].sessions_in(lo, hi)


# ================================================================================================ phases
def check() -> dict:
    """PREREG section 1: today's rules at 1 lot over 1 Apr - 13 Oct on these stores vs the published finals, the R172
    engine with no rupee level vs R101's own pricing, and R151's picker walk."""
    lo, hi = K.CHECK_START, K.CHECK_END
    out = {}
    for arm in K.ARMS:
        us = units(arm, lo, hi)
        pr = priced(arm, us, False, 1)
        same = all((x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6)
                   for a_, b_ in zip(pr, [u[2] for u in us]) for x, y in zip(a_, b_))
        tr = walk(arm, us, pr)
        n, net = len(tr), round(sum(t["net"] for t in tr))
        pub = K.PUBLISHED[arm]
        r = dict(trades=n, net=net, published=list(pub), reproduced=(n == pub[0] and abs(net - pub[1]) <= 1),
                 engine_equals_r101=same)
        if arm in K.PICKER_ARMS:
            P = picker()
            sc, thr = P.scores(arm, us)
            par = P.T.kept_walk(arm, us, sc, thr)
            pp = K.PICKER_PUBLISHED[arm]
            pn, pnet = len(par), round(sum(t["net"] for t in par))
            r["picker"] = dict(trades=pn, net=pnet, published=list(pp), reproduced=(pn == pp[0] and abs(pnet - pp[1]) <= 1))
        out[arm] = r
        print(arm, "check", r, flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "check.json").write_text(json.dumps(out, indent=1))
    return out


def run() -> dict:
    lo, hi = K.START, K.END
    sess = sessions(lo, hi)
    out = {"_meta": dict(window=f"{lo} .. {hi}", sessions=len(sess), first_session=str(sess[0]), last_session=str(sess[-1]),
                         sessions_by_month=dict(Counter(K.month_of(d) for d in sess)))}
    trades = defaultdict(dict)                         # trades[(variant, lots)][arm]
    for arm in K.ARMS:
        us = units(arm, lo, hi)
        sc = thr = None
        if arm in K.PICKER_ARMS:
            P = picker()
            sc, thr = P.scores(arm, us)
            out["_meta"][f"{arm}_candidates"] = sum(len(u[1]) for u in us)
            out["_meta"][f"{arm}_scored_below"] = sum(1 for v in sc.values() if v < thr)
        for lots in K.LOTS:
            for on in (False, True):
                tr = walk(arm, us, priced(arm, us, on, lots))
                tr = [t for t in tr if K.in_window(t["day"])]
                a = "A+2k1k" if on else "A"
                b = "B+2k1k" if on else "B"
                trades[(a, lots)][arm] = tr
                trades[(b, lots)][arm] = tr if sc is None else [t for t in tr if P.kept(t, sc, thr)]
                print(arm, lots, a, len(tr), round(sum(t["net"] for t in tr)), b, len(trades[(b, lots)][arm]),
                      round(sum(t["net"] for t in trades[(b, lots)][arm])), flush=True)
    for (v, lots), by_arm in sorted(trades.items(), key=lambda x: (x[0][1], K.VARIANTS.index(x[0][0]))):
        key = f"{v}@{lots}"
        out[key] = {a: describe(by_arm[a], len(sess)) for a in K.ARMS}
        out[key]["ALL"] = describe(together(by_arm), len(sess))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "r172.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "check":
        check()
    elif cmd == "run":
        run()
    else:
        raise SystemExit(__doc__)
