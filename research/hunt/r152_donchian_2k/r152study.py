"""R152 study (PREREG.md): the Donchian arms sold at +Rs 2,000 / -Rs 2,000 for the whole position, 1-3 lots, vs today's rules.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                  # R101's clean stores (to 13 Oct)
    python research/hunt/r106_small_targets/r106study.py post-build        # stores to 19 Oct, own folder (post window)
    python research/hunt/r152_donchian_2k/r152study.py design              # design + walk-forward, B0 parity asserted
    python research/hunt/r152_donchian_2k/r152study.py final               # the ONE final read (refuses a second)
    R152_POST=1 python research/hunt/r152_donchian_2k/r152study.py post    # 14-19 Oct, read once after the final
    python research/hunt/r152_donchian_2k/r152study.py decide              # BH + gates -> results/decision.json

The candidates are R112's (R72 = R101's; R51 = R101's Donchian 20 kept by Supertrend (10, 3)), walked by R106's run_rule
(one open a side, 2 fresh fills a side a day). Pure parts (stop mix, BH count, gates) take plain lists; test_r152.py
covers them. Statistics, BH, twins' rows and the gates are R106's own functions.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import Counter
from datetime import date, datetime, timezone

import numpy as np

import r152cfg as K

_M: dict = {}


def mods():
    if not _M:
        import r112study as S12
        M = S12.mods()                      # R106's mods first: the post window points r51cfg at the 19 Oct stores
        import r152engine as Y
        _M.update(S=M["S"], S12=S12, A=M["A"], W=M["W"], E=M["E"], C=M["C"], r51data=M["r51data"], Y=Y)
    return _M


# ================================================================================================ pure
def exit_mix(trades: list) -> dict:
    """target / rupee stop / own stop (-15%) / other (lock, time, square-off, ...)."""
    c = Counter(t.get("why", "?") for t in trades)
    return dict(target=c.get("target", 0), stop2k=c.get("stop2k", 0), stop=c.get("stop", 0),
                other=sum(v for k, v in c.items() if k not in ("target", "stop2k", "stop")))


def rupee_stop_share(trades: list) -> float | None:
    """Share of trades whose Rs 2,000 stop was nearer than the arm's -15% (only rules with a rupee stop)."""
    ks = [t.get("stop_kind") for t in trades if t.get("stop_kind") is not None]
    return round(sum(k == "rupee" for k in ks) / len(ks), 4) if ks else None


def m_total(n_tests: int = len(K.TESTS)) -> int:
    return n_tests + K.N_PRIOR


def changed(t: dict, p: dict | None) -> bool:
    """A trade the rule closed earlier than the arm's own exits would have (twin-eligible, R106's condition)."""
    return p is not None and t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2


# ================================================================================================ data
def units(arm: str, lo: date, hi: date) -> list:
    return mods()["S12"].units_of(arm, lo, hi)


def exits():
    return mods()["A"]._xl()


def priced(us: list, tgt=None, stop=None, lots: int = 1) -> list:
    M = mods()
    ex = exits()
    out = []
    for key, cands, _, _ in us:
        D = M["r51data"].load(key[0])[key[1]]
        out.append(M["Y"].price(key[0], D, cands, ex, tgt, stop, lots))
    return out


def walk(us: list, pr: list) -> list:
    """Today's taking walk (R106's run_rule with no rule): one open a side, 2 fresh fills a side a day."""
    return mods()["S"].run_rule("R72", us, pr, None)          # the arm name only picks exits for re-entries (none here)


def sessions_in(lo: date, hi: date) -> list:
    return mods()["S"].sessions_in(lo, hi)


def parity(arm: str, phase: str, us: list) -> dict:
    tr = walk(us, [u[2] for u in us])
    n0, net0 = len(tr), round(sum(t["net"] for t in tr))
    pub = K.PUBLISHED.get((arm, phase))
    out = dict(arm=arm, trades=n0, net=net0, published=list(pub) if pub else None)
    if pub is not None:
        out["reproduced"] = pub[0] == n0 and abs(pub[1] - net0) <= 1
        assert out["reproduced"], ("B0 does not reproduce", arm, phase, n0, net0, pub)
    print(arm, phase, "parity", out, flush=True)
    return out


# ================================================================================================ twins
def exit_twins(trades: list, plain: dict, lots: int, n: int, rng) -> tuple:
    """PREREG gate 3: (twin totals (n,), changed count). plain: {(und, day, er_sig, side): B0-priced trade at `lots`}."""
    M = mods()
    ex = exits()
    tot = np.zeros(n)
    ch = 0
    for t in trades:
        p = plain.get((t["und"], t["day"], t["er_sig"], t["side"]))
        if not changed(t, p):
            tot += t["net"]
            continue
        ch += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], n, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot += M["Y"].forced_exit_nets(t["und"], D, t, ex, rows, lots)
    return tot, ch


# ================================================================================================ phases
def _save(name: str, obj):
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def describe(tr: list, sess: list, phase: str) -> dict:
    S = mods()["S"]
    r = {"all": dict(S.stats(tr, len(sess)), mix=exit_mix(tr), rupee_stop_nearer=rupee_stop_share(tr))}
    if phase == "design":
        wfs = [d for d in sess if d >= K.WF_START]
        w = [t for t in tr if t["day"] >= K.WF_START]
        r["wf"] = dict(S.stats(w, len(wfs)), mix=exit_mix(w))
        r["wf_q"] = [round(sum(t["net"] for t in tr if a <= t["day"] <= b)) for a, b in K.WF_Q]
    return r


def evaluate(arm: str, phase: str, lo: date, hi: date) -> dict:
    M = mods()
    us = units(arm, lo, hi)
    out = {"sessions": None, "parity": parity(arm, phase, us)}
    if arm == "R51" and phase in ("design", "final"):
        out["parity_r51plain"] = parity("R51plain", phase, units("R51plain", lo, hi))
    sess = sessions_in(lo, hi)
    out["sessions"] = len(sess)
    res0 = [u[2] for u in us]
    trades = {}
    rng = np.random.default_rng(K.SEED)
    for lots in K.LOTS:
        pr0 = priced(us, None, None, lots)
        if lots == 1:                         # the R152 engine with no rupee level prices exactly as R101 did
            for a_, b_ in zip(pr0, res0):
                for x, y in zip(a_, b_):
                    assert (x is None) == (y is None) and (x is None or abs(x["net"] - y["net"]) < 1e-6), "engine parity"
        trades[("B0", lots)] = walk(us, pr0)
        plain = {(t["und"], t["day"], c["er"], c["side"]): t
                 for (key, cands, _, _), row in zip(us, pr0) for c, t in zip(cands, row) if t is not None}
        for rule in K.RULE_IDS:
            spec = K.RULES[rule]
            tr = walk(us, priced(us, spec["target"], spec["stop"], lots))
            trades[(rule, lots)] = tr
            print(arm, phase, rule, lots, len(tr), round(sum(t["net"] for t in tr)), flush=True)
        for name in ("B0",) + K.RULE_IDS:
            r = describe(trades[(name, lots)], sess, phase)
            if phase in ("final", "post") and name != "B0":
                tw, ch = exit_twins(trades[(name, lots)], plain, lots, K.TWINS, rng)
                act = sum(t["net"] for t in trades[(name, lots)])
                r["twin"] = dict(p=round(M["S"].twin_p(act, tw), 4), mean=round(float(tw.mean())), changed=ch)
                print(arm, phase, name, lots, "twin", r["twin"], flush=True)
            out[f"{name}@{lots}"] = r
    _save(f"{arm}_{phase}", trades)
    return out


def run(phase: str, arms=K.ARMS):
    M = mods()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if K.FINAL_FLAG.exists() and not (K.RESULTS / "final_open").exists():
            raise SystemExit("R152 final already read once: " + K.FINAL_FLAG.read_text())
        if not K.FINAL_FLAG.exists():
            K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (K.RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    elif phase == "post":
        assert K.POST, "the post window reads the stores built to 19 Oct (R152_POST=1)"
        if not (K.RESULTS / "final.json").exists():
            raise SystemExit("the post window is read only after the final")
        if K.POST_FLAG.exists() and not (K.RESULTS / "post_open").exists():
            raise SystemExit("R152 post window already read once: " + K.POST_FLAG.read_text())
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
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi), indent=1, default=str))
    if all((K.RESULTS / f"{phase}_{a}.json").exists() for a in K.ARMS):
        (K.RESULTS / f"{phase}_open").unlink(missing_ok=True)
        res = {a: json.loads((K.RESULTS / f"{phase}_{a}.json").read_text()) for a in K.ARMS}
        (K.RESULTS / f"{phase}.json").write_text(json.dumps(res, indent=1))


def decide() -> dict:
    S = mods()["S"]
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    ps = [f[a][f"{r}@{n}"]["twin"]["p"] for a, r, n in K.TESTS]
    q_all = S.bh(ps, m_total())
    q_own = S.bh(ps)
    out = {}
    for (a, r, n), qa, qo in zip(K.TESTS, q_all, q_own):
        k, b = f"{r}@{n}", f"B0@{n}"
        g = S.gates(dict(all=d[a][k]["all"], wf=d[a][k]["wf"], b0_all=d[a][b]["all"], b0_wf=d[a][b]["wf"]),
                    dict(all=f[a][k]["all"], b0_all=f[a][b]["all"]), qa)
        g["q_r152"] = round(float(qo), 4)
        g["twin_p"] = f[a][k]["twin"]["p"]
        out[f"{a}:{k}"] = g
    out["_m"] = dict(r152=len(K.TESTS), prior=K.N_PRIOR, total=m_total())
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final", "post"):
        run(cmd, tuple(sys.argv[2:]) or K.ARMS)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
