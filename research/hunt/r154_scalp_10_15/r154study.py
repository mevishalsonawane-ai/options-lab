"""R154 study (PREREG.md): 10-15 point scalps, two readings, on the arms' entries and four plain scalping entries.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                  # R51's clean stores (to 13 Oct)
    python research/hunt/r106_small_targets/r106study.py post-build        # stores to 19 Oct (post window)
    python research/hunt/r154_scalp_10_15/r154data.py build-early          # early store (Aug 2021 - 19 Nov 2024)
    python research/hunt/r154_scalp_10_15/r154study.py design [ENTRY ...]  # P1 + P2 (arms: B0 parity asserted)
    python research/hunt/r154_scalp_10_15/r154study.py final  [ENTRY ...]  # the ONE final read (refuses a second)
    R154_POST=1 python research/hunt/r154_scalp_10_15/r154study.py post    # 14-19 Oct, read once after the final
    python research/hunt/r154_scalp_10_15/r154study.py decide              # twins' BH + gates -> results/decision.json

Pure parts (compact arrays, stats, walk, walk-forward pick, BH, gates) take plain lists / arrays; test_r154.py covers them.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter
from datetime import date, datetime, timezone

import numpy as np

import r154cfg as K

WHY_CODE = {"target": 0, "stop": 1, "time": 2, "squareoff": 3, "missing": 4}
WHY_NAME = {v: k for k, v in WHY_CODE.items()}


# ================================================================================================ pure
def compact(trades: list) -> dict:
    """Trades -> arrays: day (ordinal), net, charges, why (code), pts (net premium points before charges)."""
    return dict(day=np.array([t["day"].toordinal() for t in trades], np.int64),
                net=np.array([t["net"] for t in trades], float),
                charges=np.array([t["charges"] for t in trades], float),
                why=np.array([WHY_CODE.get(t["why"], 4) for t in trades], np.int8),
                und=np.array([K.UNDS.index(t["und"]) for t in trades], np.int8))


def select(a: dict, mask: np.ndarray) -> dict:
    return {k: v[mask] for k, v in a.items()}


def in_range(a: dict, lo: date, hi: date) -> dict:
    return select(a, (a["day"] >= lo.toordinal()) & (a["day"] <= hi.toordinal()))


def concat(parts: list) -> dict:
    keys = ("day", "net", "charges", "why", "und")
    if not parts:
        return {k: np.zeros(0, np.int64 if k == "day" else (np.int8 if k in ("why", "und") else float)) for k in keys}
    return {k: np.concatenate([p[k] for p in parts]) for k in keys}


def stats(a: dict, sessions: int | None = None) -> dict:
    """Trades, win rate, break-even win rate (avg loss / (avg win + avg loss)), net, per trade, per session, % green
    days, worst day, max drawdown (daily), net without the 5 best trades, exit mix, average charges, largest loss."""
    n = int(len(a["net"]))
    base = dict(trades=n, sessions=sessions)
    if not n:
        return dict(base, win=None, be_win=None, net=0, per_trade=None, per_session=0 if sessions else None,
                    green_days=None, worst_day=None, max_dd=0, ex_top5=0, avg_win=None, avg_loss=None,
                    avg_charges=None, largest_loss=None, exits={})
    nets = a["net"]
    days, inv = np.unique(a["day"], return_inverse=True)
    seq = np.bincount(inv, weights=nets)
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mdd = float((np.maximum.accumulate(eq) - eq).max())
    wins, losses = nets[nets > 0], nets[nets <= 0]
    aw = float(wins.mean()) if len(wins) else 0.0
    al = float(-losses.mean()) if len(losses) else 0.0
    net = float(nets.sum())
    ex = Counter(WHY_NAME.get(int(w), "?") for w in a["why"])
    return dict(base, win=round(float((nets > 0).mean()), 4), be_win=(round(al / (aw + al), 4) if aw + al > 0 else None),
                net=round(net), per_trade=round(net / n), per_session=(round(net / sessions) if sessions else None),
                days=int(len(days)), green_days=round(float((seq > 0).mean()), 4), worst_day=round(float(seq.min())),
                max_dd=round(mdd), ex_top5=round(net - float(np.sort(nets)[::-1][:5].sum())), avg_win=round(aw),
                avg_loss=round(al), avg_charges=round(float(a["charges"].mean())), largest_loss=round(float(nets.min())),
                exits=dict(ex))


def walk_plain(sigs: list, price_fn, max_per_day: int = K.MAX_PER_DAY) -> list:
    """One session of one entry x index: signals [(er, side)] in time order; one open position at a time (a signal at or
    before the open trade's exit row is skipped), at most `max_per_day` trades. price_fn(er, side) -> trade | None."""
    out, busy = [], -1
    for er, side in sorted(sigs):
        if len(out) >= max_per_day:
            break
        if er <= busy:
            continue
        t = price_fn(er, side)
        if t is None:
            continue
        out.append(t)
        busy = t["xr"]
    return out


def wf_pick(nets_by_rule: dict, windows: list, start: date) -> dict:
    """Walk-forward pick (PREREG 4, descriptive): for each window (lo, hi) the rule with the highest net on every day
    from `start` to the day before lo, then that rule's net in the window. nets_by_rule: {rule: arrays (day, net)}."""
    out = []
    for lo, hi in windows:
        best, bv = None, -np.inf
        for r, a in nets_by_rule.items():
            m = (a["day"] >= start.toordinal()) & (a["day"] < lo.toordinal())
            v = float(a["net"][m].sum()) if m.any() else -np.inf
            if v > bv:
                best, bv = r, v
        if best is None:
            out.append(dict(lo=str(lo), hi=str(hi), rule=None, net=0, trades=0))
            continue
        a = nets_by_rule[best]
        m = (a["day"] >= lo.toordinal()) & (a["day"] <= hi.toordinal())
        out.append(dict(lo=str(lo), hi=str(hi), rule=best, prior_net=round(bv), net=round(float(a["net"][m].sum())),
                        trades=int(m.sum())))
    return dict(windows=out, net=sum(w["net"] for w in out), trades=sum(w["trades"] for w in out))


def twin_totals(choices: list, n: int, rng) -> np.ndarray:
    """choices: per trade an array of the nets of its allowed random exits. Returns n twin totals (each trade's exit
    drawn uniformly)."""
    tot = np.zeros(n)
    for c in choices:
        c = np.asarray(c, float)
        tot += c[rng.integers(0, len(c), size=n)]
    return tot


def twin_p(rule_total: float, totals: np.ndarray) -> float:
    return float((1 + int((np.asarray(totals) >= rule_total).sum())) / (len(totals) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total or n, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def gates(p1: dict, p2: dict, p3: dict, q: float, b0_p2: dict | None = None, b0_p3: dict | None = None) -> dict:
    """PREREG section 6 (1 lot)."""
    fails = []
    if not (p1["net"] > 0 and p2["net"] > 0 and p3["net"] > 0):
        fails.append("not positive in P1, P2 and P3")
    if not q <= K.GATE_Q:
        fails.append(f"exit-twin BH q {q:.2f} > {K.GATE_Q}")
    if p3["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    if b0_p2 is not None and b0_p3 is not None and not (p2["net"] > b0_p2["net"] and p3["net"] > b0_p3["net"]):
        fails.append("not above the arm's own exits in P2 and P3")
    return dict(passed=not fails, fails=fails)


# ================================================================================================ data
def mods():
    import r154data as DATA
    M = DATA.mods()
    import r154engine as Y
    import r154signals as SG
    return dict(M, DATA=DATA, Y=Y, SG=SG)


def sessions_count(und: str, lo: date, hi: date) -> int:
    return len(mods()["DATA"].sessions(und, lo, hi))


def plain_run(entry: str, und: str, lo: date, hi: date, rules=K.RULE_IDS, lots=K.LOTS, keep: bool = False) -> tuple:
    """{(rule, lots): compact arrays} for a plain entry (or 'RND') on one index over lo..hi; keep=True also returns the
    trade dicts (for the twins)."""
    M = mods()
    DATA, Y, SG = M["DATA"], M["Y"], M["SG"]
    allday = DATA.sessions(und, K.EARLY_WARM, hi, warm=True)
    sig = SG.plain_signals(entry, allday, und)
    days = DATA.sessions(und, lo, hi)
    out, kept = {}, {}
    for r in rules:
        rule = K.RULES[r]
        for n in lots:
            tr = []
            for d, D in days:
                s = sig.get(d)
                if s:
                    tr += walk_plain(s, lambda er, side: Y.price(und, D, er, side, rule, n))
            out[(r, n)] = compact(tr)
            if keep and n == 1:
                kept[r] = tr
    return out, kept


def arm_units(arm: str, lo: date, hi: date) -> list:
    M = mods()
    if arm == "LIQ":
        return M["A"].liq_units(lo, hi)
    return M["S52"].units(arm, lo, hi)


def arm_b0(arm: str, us: list) -> list:
    """Today's rules (B0) walked by R106's run_rule: the arm's own exits at 1 lot."""
    return mods()["S"].run_rule(arm, us, [u[2] for u in us], None)


def arm_parity(arm: str, phase: str, b0: list) -> dict:
    n0, net0 = len(b0), round(sum(t["net"] for t in b0))
    pub = K.PUBLISHED.get((arm, phase))
    out = dict(trades=n0, net=net0, published=list(pub) if pub else None)
    if pub is not None:
        out["reproduced"] = pub[0] == n0 and abs(pub[1] - net0) <= 1
        assert out["reproduced"], ("B0 does not reproduce", arm, phase, n0, net0, pub)
    print(arm, phase, "B0 parity", out, flush=True)
    return out


def arm_run(arm: str, us: list, rules=K.RULE_IDS, lots=K.LOTS, keep: bool = False) -> tuple:
    """{(rule, lots): compact arrays} of the arm's entries with a scalp exit (index exits dropped), walked by the arm's
    own taking rule (R106's run_rule)."""
    M = mods()
    Y, S, r51data = M["Y"], M["S"], M["r51data"]
    out, kept = {}, {}
    for r in rules:
        rule = K.RULES[r]
        for n in lots:
            pr = []
            for key, cands, _, _ in us:
                D = r51data.load(key[0])[key[1]]
                pr.append([Y.price(key[0], D, c["er"], c["side"], rule, n, strike=c["strike"]) for c in cands])
            tr = S.run_rule(arm, us, pr, None)
            out[(r, n)] = compact(tr)
            if keep and n == 1:
                kept[r] = tr
    return out, kept


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def describe(arrs: dict, und: str, windows: dict) -> dict:
    """{period: stats} for one entry x index x rule x lots; windows: {name: (lo, hi, sessions)}."""
    ui = K.UNDS.index(und)
    a = select(arrs, arrs["und"] == ui)
    return {w: stats(in_range(a, lo, hi), s) for w, (lo, hi, s) in windows.items()}


def windows_for(phase: str, entry: str, und: str) -> dict:
    if phase == "design":
        start = K.ARM_START if entry in K.ARMS else K.EARLY_START
        w = {"P1": (start, K.P1_END), "P2": (K.P2_START, K.P2_END)}
        if entry not in K.ARMS:
            w["P1_early"] = (K.EARLY_START, date(2024, 11, 19))
            w["P1_recent"] = (K.ARM_START, K.P1_END)
        for i, (lo, hi) in enumerate(K.P2_Q):
            w[f"P2_q{i + 1}"] = (lo, hi)
    elif phase == "final":
        w = {"P3": (K.FINAL_START, K.FINAL_END)}
    else:
        w = {"P4": (K.POST_START, K.POST_END)}
    return {k: (lo, hi, sessions_count(und, lo, hi)) for k, (lo, hi) in w.items()}


def phase_range(phase: str, entry: str) -> tuple:
    if phase == "design":
        return (K.ARM_START if entry in K.ARMS else K.EARLY_START), K.P2_END
    if phase == "final":
        return K.FINAL_START, K.FINAL_END
    return K.POST_START, K.POST_END


def evaluate(entry: str, phase: str) -> dict:
    lo, hi = phase_range(phase, entry)
    twins = phase == "final"
    out = {"entry": entry, "phase": phase}
    per_und: dict = {}
    kept_all: dict = {}
    b0 = None
    if entry in K.ARMS:
        us = arm_units(entry, lo, hi)
        b0 = arm_b0(entry, us)
        if phase in ("design", "final"):
            out["parity"] = arm_parity(entry, phase, b0)
        res, kept = arm_run(entry, us, keep=twins)
        per_und = {u: res for u in K.ENTRY_UNDS[entry]}
        kept_all = {u: {r: [t for t in tr if t["und"] == u] for r, tr in kept.items()} for u in K.ENTRY_UNDS[entry]}
        b0a = compact([dict(t, charges=0.0, why=t.get("why", "?")) for t in b0])
    else:
        for u in K.ENTRY_UNDS[entry]:
            per_und[u], kept_all[u] = plain_run(entry, u, lo, hi, keep=twins)
            print(entry, phase, u, "priced", flush=True)
    rng = np.random.default_rng([K.SEED, K.ENTRIES.index(entry) if entry in K.ENTRIES else 99])
    for u in K.ENTRY_UNDS[entry]:
        win = windows_for(phase, entry, u)
        res = per_und[u]
        ou = {}
        for (r, n), arrs in res.items():
            ou[f"{r}@{n}"] = describe(arrs, u, win)
        if b0 is not None:
            ou["B0@1"] = describe(b0a, u, win)
        if twins:
            dm = dict(mods()["DATA"].sessions(u, lo, hi))
            for r in K.RULE_IDS:
                tr = kept_all[u][r]
                choices = [mods()["Y"].twin_nets(t, dm[t["day"]]) for t in tr]
                act = float(sum(t["net"] for t in tr))
                tot = twin_totals(choices, K.TWINS, rng)
                ou[f"{r}@1"]["twin"] = dict(p=round(twin_p(act, tot), 4), mean=round(float(tot.mean())), n=len(tr))
        out[u] = ou
        _save(f"{entry}_{u}_{phase}", {k: select(v, v["und"] == K.UNDS.index(u)) for k, v in res.items()})
        print(entry, phase, u, "done", flush=True)
    return out


def baseline(phase: str) -> dict:
    """Random entries (PREREG 3, descriptive): per index, every rule at 1 lot."""
    out = {}
    for u in K.UNDS:
        lo, hi = phase_range(phase, "RND")
        res, _ = plain_run("RND", u, lo, hi, lots=(1,))
        win = windows_for(phase, "RND", u)
        out[u] = {f"{r}@1": describe(res[(r, 1)], u, win) for r in K.RULE_IDS}
        _save(f"RND_{u}_{phase}", res)
        print("RND", phase, u, flush=True)
    return out


def run(phase: str, entries=K.ENTRIES):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R154 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("entries in progress\n")
    elif phase == "post":
        assert K.POST, "the post window reads the stores built to 19 Oct (R154_POST=1)"
        if not (K.RESULTS / "final.json").exists():
            raise SystemExit("the post window is read only after the final")
        if K.POST_FLAG.exists() and not (K.RESULTS / "post_open").exists():
            raise SystemExit("R154 post window already read once: " + K.POST_FLAG.read_text())
        if not K.POST_FLAG.exists():
            K.POST_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "post_open").write_text("entries in progress\n")
    if phase in ("final", "post"):
        mods()["C"].open_final()
    for e in entries:
        done = K.OUT / f"res_{phase}_{e}.json"
        if phase != "design" and done.exists():
            print(e, phase, "already read; skipped", flush=True)
            continue
        if e == "RND":
            res = baseline(phase)
        else:
            res = evaluate(e, phase)
        K.OUT.mkdir(parents=True, exist_ok=True)
        done.write_text(json.dumps(res, default=str))


def close(phase: str):
    """Merge the per-entry results into results/<phase>.json and mark a once-only read finished."""
    allres = {}
    for e in K.ENTRIES + ("RND",):
        p = K.OUT / f"res_{phase}_{e}.json"
        if p.exists():
            allres[e] = json.loads(p.read_text())
    (K.RESULTS / f"{phase}.json").write_text(json.dumps(allres, indent=0, default=str))
    flag = K.RESULTS / f"{phase}_open"
    if flag.exists() and all(e in allres for e in K.ENTRIES):
        flag.unlink()
    print(phase, "closed:", sorted(allres))


def wf_all() -> dict:
    """Walk-forward pick per entry x index (descriptive), from the saved design and final trades (1 lot)."""
    out = {}
    for e in K.ENTRIES:
        for u in K.ENTRY_UNDS[e]:
            try:
                d, f = _load(f"{e}_{u}_design"), _load(f"{e}_{u}_final")
            except FileNotFoundError:
                continue
            by = {r: concat([d[(r, 1)], f[(r, 1)]]) for r in K.RULE_IDS}
            start = K.ARM_START if e in K.ARMS else K.EARLY_START
            out[f"{e}:{u}"] = dict(P2=wf_pick(by, list(K.P2_Q), start), P3=wf_pick(by, [(K.FINAL_START, K.FINAL_END)], start))
    return out


def decide() -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    tests = list(K.TESTS)
    ps = [f[e][u][f"{r}@1"]["twin"]["p"] for e, u, r in tests]
    m = len(tests) + K.N_PRIOR
    q_all = bh(ps, m)
    q_own = bh(ps)
    q_strict = bh(ps, m + K.N_STRICT_EXTRA)
    out = {}
    for (e, u, r), qa, qo, qs, p in zip(tests, q_all, q_own, q_strict, ps):
        s1, s2, s3 = d[e][u][f"{r}@1"]["P1"], d[e][u][f"{r}@1"]["P2"], f[e][u][f"{r}@1"]["P3"]
        b2 = d[e][u]["B0@1"]["P2"] if e in K.ARMS else None
        b3 = f[e][u]["B0@1"]["P3"] if e in K.ARMS else None
        g = gates(s1, s2, s3, float(qa), b2, b3)
        g.update(twin_p=p, q=round(float(qa), 4), q_r154=round(float(qo), 4), q_strict=round(float(qs), 4))
        out[f"{e}:{u}:{r}"] = g
    out["_m"] = dict(r154=len(tests), prior=K.N_PRIOR, total=m, strict_total=m + K.N_STRICT_EXTRA,
                     passed=sum(1 for k, v in out.items() if not k.startswith("_") and v["passed"]))
    out["_wf"] = wf_all()
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=0))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd, tuple(sys.argv[2:]) or K.ENTRIES + ("RND",))
    elif cmd == "close":
        close(sys.argv[2])
    elif cmd == "decide":
        print(json.dumps(decide()["_m"], indent=1))
    else:
        raise SystemExit(__doc__)
