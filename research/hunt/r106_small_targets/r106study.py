"""R106 study (PREREG.md): fixed small rupee targets on the paper arms, gated against today's rules and random exits.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                 # R101's clean stores (to 13 Oct)
    python research/hunt/r106_small_targets/r106study.py post-build       # stores to 19 Oct, own folder (post window)
    python research/hunt/r106_small_targets/r106study.py design [ARM ...] # design + walk-forward, B0 parity asserted
    python research/hunt/r106_small_targets/r106study.py final  [ARM ...] # the ONE final read (refuses a second)
    R106_POST=1 python research/hunt/r106_small_targets/r106study.py post # 14-19 Oct, read once after the final
    python research/hunt/r106_small_targets/r106study.py decide           # BH + gates -> results/decision.json

Pure parts (stats, break-even, twins' totals, BH, gates) take plain lists; test_r106.py covers them.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone

import numpy as np

import r106cfg as K

# ================================================================================================ pure statistics
def daily(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += t["net"]
    return dict(out)


def stats(trades: list, sessions: int | None = None) -> dict:
    """Trades, win rate, net, per trade, per session, days traded, % green days, worst / best day, max drawdown, net
    without the 5 best, exit mix, largest loss, break-even win rate (avg loss / (avg win + avg loss))."""
    n = len(trades)
    base = dict(trades=n, sessions=sessions)
    if not n:
        return dict(base, win=None, net=0, per_trade=None, per_session=0 if sessions else None, days=0, green_days=None,
                    worst_day=None, best_day=None, median_day=None, max_dd=0, ex_top5=0, avg_win=None, avg_loss=None,
                    be_win=None, largest_loss=None, exits={})
    nets = np.array([t["net"] for t in trades], float)
    dd_ = daily(trades)
    seq = np.array([dd_[d] for d in sorted(dd_)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mdd = float((np.maximum.accumulate(eq) - eq).max())
    wins, losses = nets[nets > 0], nets[nets <= 0]
    aw = float(wins.mean()) if len(wins) else 0.0
    al = float(-losses.mean()) if len(losses) else 0.0
    exits = defaultdict(int)
    for t in trades:
        exits[t.get("why", "?")] += 1
    net = float(nets.sum())
    return dict(base, win=round(float((nets > 0).mean()), 4), net=round(net), per_trade=round(net / n),
                per_session=(round(net / sessions) if sessions else None), days=len(dd_),
                green_days=round(float((seq > 0).mean()), 4), worst_day=round(float(seq.min())),
                best_day=round(float(seq.max())), median_day=round(float(np.median(seq))), max_dd=round(mdd),
                ex_top5=round(net - float(np.sort(nets)[::-1][:5].sum())), avg_win=round(aw), avg_loss=round(al),
                be_win=(round(al / (aw + al), 4) if aw + al > 0 else None), largest_loss=round(float(nets.min())),
                exits=dict(exits))


def day_goal_profile(trades: list, sessions: list, goal: float = K.DAY_GOAL) -> dict:
    """'5 trades x Rs 1,000': over every session (a session with no trade counts as Rs 0): median day, % of sessions at
    or above the goal, % green, worst / best day, average."""
    dd_ = daily(trades)
    seq = np.array([dd_.get(d, 0.0) for d in sessions], float)
    if not len(seq):
        return dict(sessions=0)
    return dict(sessions=len(seq), traded=int(sum(1 for d in sessions if d in dd_)), median_day=round(float(np.median(seq))),
                mean_day=round(float(seq.mean())), reach_goal=round(float((seq >= goal).mean()), 4),
                green=round(float((seq > 0).mean()), 4), red=round(float((seq < 0).mean()), 4),
                worst_day=round(float(seq.min())), best_day=round(float(seq.max())),
                p10=round(float(np.percentile(seq, 10))), p90=round(float(np.percentile(seq, 90))))


def be_theory(avg_win: float, avg_loss: float) -> float:
    """Break-even win rate when wins average `avg_win` and losses `avg_loss` (both net, positive numbers)."""
    return avg_loss / (avg_win + avg_loss) if avg_win + avg_loss > 0 else float("nan")


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts extra tests taken as p = 1 (the earlier families)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total or n, n)
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def re_allowed(arm: str, er: int, fx, now: float, ref: float, side: str) -> bool:
    """RE5 (PREREG section 2): a re-entry at row `er` is allowed when within the arm's entry hours and the signal is still
    alive: Liquidity: its index exits (forced row fx) have not fired by `er`; the others: the index close at the target
    minute (`now`) is still beyond the signal bar's close (`ref`) in the signal's direction. Unknown = not alive."""
    if er > K.LAST_ENTRY[arm]:
        return False
    if arm == "LIQ":
        return not (fx is not None and 0 <= fx <= er)
    sgn = 1 if side == "C" else -1
    return bool(np.isfinite(now) and np.isfinite(ref) and sgn * (now - ref) > 0)


def twin_rows(fill_row: int, plain_xr: int, n: int, rng) -> np.ndarray:
    """The random-exit twin's rows: uniform, strictly after the fill and strictly before the plain exit."""
    return rng.integers(fill_row + 1, plain_xr, size=n)


def twin_p(rule_total: float, twin_totals: np.ndarray) -> float:
    return float((1 + int((np.asarray(twin_totals) >= rule_total).sum())) / (len(twin_totals) + 1))


def better_than_b0(r: dict, b: dict) -> bool:
    """PREREG gate 2 for one period: net above B0, or within 5% of B0's with worst day and drawdown each >= 20% smaller."""
    if r["net"] > b["net"]:
        return True
    equal = abs(r["net"] - b["net"]) <= K.EQUAL_NET * abs(b["net"])
    wd_r, wd_b = abs(min(r["worst_day"] or 0, 0)), abs(min(b["worst_day"] or 0, 0))
    smaller = wd_r <= (1 - K.SMALLER_RISK) * wd_b and r["max_dd"] <= (1 - K.SMALLER_RISK) * b["max_dd"]
    return bool(equal and smaller)


def gates(d: dict, f: dict, q_all: float) -> dict:
    """PREREG section 5. d / f: {'all', 'wf', 'b0_all', 'b0_wf'} design and {'all', 'b0_all'} final."""
    fails = []
    if not (d["all"]["net"] > 0 and d["wf"]["net"] > 0 and f["all"]["net"] > 0):
        fails.append("not positive in design, walk-forward and final")
    if not (better_than_b0(d["wf"], d["b0_wf"]) and better_than_b0(f["all"], f["b0_all"])):
        fails.append("not better than today's rules (walk-forward and final)")
    if q_all > K.GATE_Q:
        fails.append(f"exit-twin BH q {q_all:.2f} > 0.10")
    if f["all"]["trades"] < K.MIN_FINAL:
        fails.append("< 40 final trades")
    return dict(passed=not fails, fails=fails, q_all=round(float(q_all), 4))


# ================================================================================================ arms (data)
_M: dict = {}


def mods():
    if not _M:
        import r101cfg  # noqa: F401  (R51_CLEAN=1 and the paths)
        if K.POST:
            import r51cfg
            r51cfg.END = K.POST_END
            r51cfg.OUT = r51cfg.OUT.parent / "clean_post"
        import r101arms as A
        import r106engine as X
        import r106walk as W
        M = A.mods()
        _M.update(A=A, X=X, W=W, E=M["E"], C=M["C"], R51=M["R51"], r51data=M["r51data"], fam_liq=M["fam_liq"])
    return _M


def arm_exits(arm: str):
    M = mods()
    A = M["A"]
    return {"LIQ": lambda: M["fam_liq"].EX, "R72": A._xl, "R51": A._xl, "R52": A._oov_l25}[arm]()


def sessions_in(lo: date, hi: date) -> list:
    M = mods()
    days = set()
    for u in ("NIFTY", "BANKNIFTY"):
        days |= set(M["C"].days_in(u, lo, hi))
    return sorted(days)


def _ref_close(D, c) -> float:
    r = c["er"] - 1
    return float(D.idx[3, r]) if 0 <= r < D.idx.shape[1] else float("nan")


def prepare(arm: str, lo: date, hi: date) -> list:
    """R101's units (key, cands, res, how) with each candidate's reference close (the signal bar's last close)."""
    M = mods()
    units = M["A"].UNITS[arm](lo, hi)
    for key, cands, res, how in units:
        D = M["r51data"].load(key[0])[key[1]]
        for c in cands:
            c["ref_close"] = _ref_close(D, c)
    return units


def price_units(arm: str, units: list, T, stop_mode: str) -> list:
    M = mods()
    ex = arm_exits(arm)
    out = []
    for key, cands, res, how in units:
        D = M["r51data"].load(key[0])[key[1]]
        out.append(M["X"].price(key[0], D, cands, ex, T, stop_mode))
    return out


def make_re_fn(arm: str):
    """RE5's re-entry (PREREG section 2): the same signal bought again at the next minute, if in hours and alive."""
    M = mods()

    def make_re(unit, c, r):
        key = unit[0]
        er = r["xr"] + 1
        D = M["r51data"].load(key[0])[key[1]]
        now = float(D.idx[3, r["xr"]]) if 0 <= r["xr"] < D.idx.shape[1] else float("nan")
        if not re_allowed(arm, er, c.get("fx", -1), now, c.get("ref_close", float("nan")), c["side"]):
            return None
        k = M["E"].strike_for(D, key[0], c["side"], er - 1, 1)
        if k is None:
            return None
        nc = {kk: v for kk, v in c.items() if kk not in ("er", "strike")}
        nc.update(er=er, strike=k)
        return nc

    return make_re


def run_rule(arm: str, units: list, priced: list, rule: str | None) -> list:
    """Trades of B0 (rule None, priced = R101's) or one rule (priced with its target / stop)."""
    M = mods()
    W = M["W"]
    spec = K.RULES.get(rule, dict(re=False, goal=False, target=None, stop="own"))
    ex = arm_exits(arm)
    make_re = make_re_fn(arm) if spec["re"] else None

    def price_re(unit, nc):
        key = unit[0]
        D = M["r51data"].load(key[0])[key[1]]
        return M["X"].price(key[0], D, [nc], ex, spec["target"], spec["stop"])[0]

    trades = []
    for d, gs in sorted(W.by_day(units).items()):
        us = [(units[g][0], units[g][1], units[g][3]) for g in gs]
        pr = [priced[g] for g in gs]
        trades += W.walk_day(us, pr, re=spec["re"], make_re=make_re, price_re=price_re,
                             max_fills=K.MAX_FILLS_PER_INDEX_DAY, goal=K.DAY_GOAL if spec["goal"] else None,
                             day_max=K.DAY_MAX_TRADES if spec["goal"] else None)
    for t in trades:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    trades.sort(key=lambda t: (t["day"], t["und"], t["er_sig"], t.get("book") or 0, t["re"]))
    return trades


def pooled(phase: str, lo: date, hi: date, arms=K.ARMS) -> dict:
    """PREREG section 4, '5 trades x Rs 1,000' for all arms together: the arms' T1000SR candidates merged day by day,
    re-entries as each arm's RE5, the day goal (5 trades or +Rs 5,000 closed) across all of them. Also the arms' B0
    trades summed per day, for comparison. Descriptive only (never gated)."""
    M = mods()
    W = M["W"]
    spec = K.RULES["DAY5K"]
    units, priced, b0 = [], [], []
    for arm in arms:
        us = prepare(arm, lo, hi)
        for _, cands, _, _ in us:
            for c in cands:
                c["arm"] = arm
                c["fx_idx"] = c.get("fx", -1)
        pr = price_units(arm, us, spec["target"], spec["stop"])
        b0 += run_rule(arm, us, [u[2] for u in us], None)
        units += us
        priced += pr
    re_fns = {a: make_re_fn(a) for a in arms}
    exs = {a: arm_exits(a) for a in arms}

    def make_re(unit, c, r):
        return re_fns[c["arm"]](unit, c, r)

    def price_re(unit, nc):
        key = unit[0]
        D = M["r51data"].load(key[0])[key[1]]
        return M["X"].price(key[0], D, [nc], exs[nc["arm"]], spec["target"], spec["stop"])[0]

    trades = []
    for d, gs in sorted(W.by_day(units).items()):
        us = [(units[g][0], units[g][1], units[g][3]) for g in gs]
        trades += W.walk_day(us, [priced[g] for g in gs], re=True, make_re=make_re, price_re=price_re,
                             max_fills=K.MAX_FILLS_PER_INDEX_DAY, goal=K.DAY_GOAL, day_max=K.DAY_MAX_TRADES)
    sess = sessions_in(lo, hi)
    out = dict(phase=phase, arms=list(arms), sessions=len(sess), day5k=stats(trades, len(sess)),
               day5k_profile=day_goal_profile(trades, sess), b0=stats(b0, len(sess)), b0_profile=day_goal_profile(b0, sess),
               by_arm={a: sum(1 for t in trades if t.get("arm") == a) for a in arms})
    if phase == "design":
        wf = [d for d in sess if d >= K.WF_START]
        out["day5k_wf_profile"] = day_goal_profile([t for t in trades if t["day"] >= K.WF_START], wf)
        out["b0_wf_profile"] = day_goal_profile([t for t in b0 if t["day"] >= K.WF_START], wf)
    _save(f"pooled_{phase}", dict(day5k=trades, b0=b0))
    (K.RESULTS / f"pooled_{phase}.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ twins
def plain_of(arm: str, trades: list) -> list:
    """Each trade priced again from its own signal entry row with the arm's own exits (the 'plain' exit row)."""
    M = mods()
    ex = arm_exits(arm)
    out = []
    for t in trades:
        D = M["r51data"].load(t["und"])[t["day"]]
        c = dict(side=t["side"], strike=t["strike"], er=t["er_sig"], fx=t.get("fx_idx", -1))
        out.append(M["X"].price(t["und"], D, [c], ex, None, "own")[0])
    return out


def exit_twins(arm: str, trades: list, n: int, rng) -> tuple:
    """PREREG gate 3: (twin totals (n,), changed count). A trade is changed when the rule's exit row is before the plain
    exit row; the twin sells it at a uniform random minute strictly after its fill and before the plain exit."""
    M = mods()
    ex = arm_exits(arm)
    plain = plain_of(arm, trades)
    tot = np.zeros(n)
    changed = 0
    for t, p in zip(trades, plain):
        if p is None or t["xr"] >= p["xr"] or p["xr"] - t["er"] < 2:
            tot += t["net"]
            continue
        changed += 1
        rows = twin_rows(t["er"], p["xr"], n, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += M["X"].forced_exit_nets(t["und"], D, t, ex, rows, t.get("fx_idx", -1))
    return tot, changed


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


PRICINGS = {}
for _r, _s in K.RULES.items():
    PRICINGS.setdefault((_s["target"], _s["stop"]), []).append(_r)


def evaluate(arm: str, phase: str, lo: date, hi: date) -> dict:
    import r101cfg as K101
    M = mods()
    units = prepare(arm, lo, hi)
    for _, cands, _, _ in units:
        for c in cands:
            c["fx_idx"] = c.get("fx", -1)
    res0 = [u[2] for u in units]
    b0 = run_rule(arm, units, res0, None)
    n0, net0 = len(b0), round(sum(t["net"] for t in b0))
    pub = K101.PUBLISHED[arm].get(phase)
    parity = dict(trades=n0, net=net0, published=list(pub) if pub else None)
    if pub is not None:
        ok = (pub[0] is None or pub[0] == n0) and abs(pub[1] - net0) <= 1
        parity["reproduced"] = ok
        assert ok, (arm, phase, "B0 does not reproduce R101", n0, net0, pub)
    # the R106 engine with no rupee levels must price every candidate exactly as R101 did
    own = price_units(arm, units, None, "own")
    for a_, b_ in zip(own, res0):
        for x, y in zip(a_, b_):
            assert (x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6), (arm, "engine parity")
    print(arm, phase, "B0", parity, flush=True)
    sess = sessions_in(lo, hi)
    trades = {"B0": b0}
    for (T, stop), rules in PRICINGS.items():
        pr = price_units(arm, units, T, stop)
        for rule in rules:
            trades[rule] = run_rule(arm, units, pr, rule)
            print(arm, phase, rule, len(trades[rule]), round(sum(t["net"] for t in trades[rule])), flush=True)
    out = {"parity": parity, "sessions": len(sess)}
    for name, tr in trades.items():
        r = {"all": stats(tr, len(sess))}
        if phase == "design":
            wfs = [d for d in sess if d >= K.WF_START]
            r["wf"] = stats([t for t in tr if t["day"] >= K.WF_START], len(wfs))
            r["wf_q"] = [round(sum(t["net"] for t in tr if a <= t["day"] <= b)) for a, b in K.WF_Q]
        r["re_trades"] = sum(1 for t in tr if t.get("re", 0) > 0)
        r["day_profile"] = day_goal_profile(tr, sess)
        out[name] = r
    if phase in ("final", "post"):
        rng = np.random.default_rng(K.SEED)
        for rule in K.RULE_IDS:
            tw, ch = exit_twins(arm, trades[rule], K.TWINS, rng)
            act = sum(t["net"] for t in trades[rule])
            out[rule]["twin"] = dict(p=round(twin_p(act, tw), 4), mean=round(float(tw.mean())), changed=ch)
            print(arm, phase, rule, "twin p", out[rule]["twin"], flush=True)
    _save(f"{arm}_{phase}", trades)
    return out


def run(phase: str, arms=K.ARMS):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R106 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    elif phase == "post":
        assert K.POST, "the post window reads the stores built to 19 Oct (R106_POST=1)"
        if not (K.RESULTS / "final.json").exists():
            raise SystemExit("the post window is read only after the final")
        if K.POST_FLAG.exists() and not (K.RESULTS / "post_open").exists():
            raise SystemExit("R106 post window already read once: " + K.POST_FLAG.read_text())
        if not K.POST_FLAG.exists():
            K.POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "post_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K.POST_START, K.POST_END
    else:
        lo, hi = K.START, K.DESIGN_END
    for arm in arms:
        done = K.RESULTS / f"{phase}_{arm}.json"
        if phase != "design" and done.exists():
            print(arm, phase, "already read; skipped", flush=True)
            continue
        out = evaluate(arm, phase, lo, hi)
        done.write_text(json.dumps(out, indent=1, default=str))


def close(phase: str):
    """Mark a once-only read finished (every arm's file written); a second run is then refused."""
    p = K.RESULTS / f"{phase}_open"
    if all((K.RESULTS / f"{phase}_{a}.json").exists() for a in K.ARMS) and p.exists():
        p.unlink()
    res = {a: json.loads((K.RESULTS / f"{phase}_{a}.json").read_text()) for a in K.ARMS
           if (K.RESULTS / f"{phase}_{a}.json").exists()}
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(res, indent=1))


def decide() -> dict:
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    tests = [(a, r) for a in K.ARMS for r in K.RULE_IDS]
    ps = [f[a][r]["twin"]["p"] for a, r in tests]
    q_all = bh(ps, len(tests) + K.N_PRIOR)
    q_own = bh(ps)
    out = {}
    for (a, r), qa, qo in zip(tests, q_all, q_own):
        g = gates(dict(all=d[a][r]["all"], wf=d[a][r]["wf"], b0_all=d[a]["B0"]["all"], b0_wf=d[a]["B0"]["wf"]),
                  dict(all=f[a][r]["all"], b0_all=f[a]["B0"]["all"]), qa)
        g["q_r106"] = round(float(qo), 4)
        g["twin_p"] = f[a][r]["twin"]["p"]
        out[f"{a}:{r}"] = g
    out["_m"] = dict(r106=len(tests), prior=K.N_PRIOR, total=len(tests) + K.N_PRIOR)
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


def post_build():
    """Stores to 19 Oct in their own folder (clean_post), so R101's stores stay as they were."""
    import os
    os.environ["R51_CLEAN"] = "1"
    import r51cfg
    r51cfg.END = K.POST_END
    r51cfg.OUT = r51cfg.OUT.parent / "clean_post"
    import r51data
    for u in sys.argv[2:] or r51cfg.UNDS:
        print(r51data.build(u), flush=True)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd, tuple(sys.argv[2:]) or K.ARMS)
    elif cmd == "pooled":
        ph = sys.argv[2]
        if ph == "final":
            assert K.FINAL_FLAG.exists(), "the pooled final view is part of the one final read"
            mods()["C"].open_final()
            print(json.dumps(pooled(ph, K.FINAL_START, K.FINAL_END), indent=1, default=str))
        elif ph == "post":
            assert K.POST and K.POST_FLAG.exists()
            mods()["C"].open_final()
            print(json.dumps(pooled(ph, K.POST_START, K.POST_END), indent=1, default=str))
        else:
            print(json.dumps(pooled(ph, K.START, K.DESIGN_END), indent=1, default=str))
    elif cmd == "close":
        close(sys.argv[2])
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    elif cmd == "post-build":
        post_build()
    else:
        raise SystemExit(__doc__)
