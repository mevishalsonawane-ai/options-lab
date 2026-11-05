"""R150 Amendment B: 5-minute support / resistance on R51 Donchian 20 (today's app rules).

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_b.py design
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_b.py final    # second look
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_b.py unseen   # read once
    python research/hunt/r150_sr_levels/study_b.py decide
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

CMD = (sys.argv[1:] or [""])[0]
if CMD == "unseen":                       # before anything imports r51cfg: R143's old store
    _b = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
    os.environ["R51_OUT"] = str(_b / "r143_old")

import numpy as np  # noqa: E402

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r140_structure_arms"))
sys.path.insert(0, str(HERE.parents[2]))
import r140 as R140  # noqa: E402

from research.hunt.r150_sr_levels import levels as V  # noqa: E402

K, L, S = R140.K, R140.L, R140.S
RESULTS = HERE / "results"
UNSEEN_FLAG = RESULTS / "UNSEEN_READ_B"
SEED, TWINS = 1502, 2000
K5 = 6
UNSEEN = (date(2022, 12, 1), date(2024, 11, 19))
OLD_WARM, OLD_START, OLD_END = date(2022, 9, 1), date(2022, 11, 1), date(2024, 12, 31)
R143_BASE = (4815, -400363)              # R51 today on the unseen two years (R143's published run)
TESTS = {"W5": (5, "wall"), "W20": (20, "wall"), "B5": (5, "cross"), "B20": (20, "cross"), "FX5": (5, "fake"), "FX20": (20, "fake")}
RULE = {"wall": "skip when a 3+-turn 5-min level is within 0.25% ahead", "cross": "skip unless the break crossed a 3+-turn 5-min level",
        "fake": "sell when a 5-min bar closes back through the 3+-turn 5-min level the break crossed (fake breakout)"}


class Lv:
    def __init__(self, und: str):
        days = S.mods()["r51data"].load(und)
        self.dates = sorted(days)
        self.pos = {d: i for i, d in enumerate(self.dates)}
        self.idx = {d: days[d].idx for d in self.dates}
        self.b = V.bars15([days[d].idx for d in self.dates], minutes=5)
        self.piv = V.pivots(self.b["h"], self.b["l"], K5)
        self.cache: dict = {}

    def close_at(self, day, row: int) -> float:
        c = self.idx[day][3, : max(row, 0) + 1]
        ok = np.flatnonzero(np.isfinite(c))
        return float(c[ok[-1]]) if ok.size else np.nan

    def levels(self, day, er: int, w: int) -> list:
        key = (day, er, w)
        if key not in self.cache:
            self.cache[key] = V.levels_at(self.b, self.piv, self.pos[day], er, window=w)
        return self.cache[key]


_LV: dict = {}


def lv(und):
    if und not in _LV:
        _LV[und] = Lv(und)
    return _LV[und]


def facts(test, und, day, er, side):
    w, _ = TESTS[test]
    x = lv(und)
    return x, x.levels(day, er, w), x.close_at(day, er - 1), x.close_at(day, er - 6)


def skip(test, und, day, er, side) -> bool:
    _, kind = TESTS[test]
    x, levels, price, prev = facts(test, und, day, er, side)
    if not np.isfinite(price):
        return False
    if kind == "wall":
        return V.wall_ahead(levels, price, side, 0.0025, 3)
    return not (np.isfinite(prev) and V.crossed(levels, prev, price, side, 3))


def fake_row(test, und, day, er, side) -> int:
    x, levels, price, prev = facts(test, und, day, er, side)
    if not (np.isfinite(price) and np.isfinite(prev)):
        return -1
    level = V.crossed_level(levels, prev, price, side, 3)
    return -1 if level is None else V.fake_exit_row(x.idx[day][3], er, level, side)


def keep_fn(test):
    return lambda g, j, key, c: not skip(test, key[0], key[1], int(c["er"]), c["side"])


def exit_priced(test, us):
    M = S.mods()
    ex = M["A"]._xl()
    out = []
    for key, cands, res, how in us:
        fxs = [fake_row(test, key[0], key[1], int(c["er"]), c["side"]) for c in cands]
        if all(f < 0 for f in fxs):
            out.append(list(res))
            continue
        D = M["r51data"].load(key[0])[key[1]]
        pr = M["X"].price(key[0], D, [dict(c, fx=f) for c, f in zip(cands, fxs)], ex)
        out.append([q if f >= 0 else r for q, r, f in zip(pr, res, fxs)])
    return out


def skip_twins(test, us, lo, hi, rng):
    kf = keep_fn(test)
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
    out = np.zeros(TWINS)
    for i in range(TWINS):
        gone = {flat[x] for x in L.random_drop(len(flat), k, rng)}
        tr = S.walk("R51", S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
        out[i] = R140.loss_sum((t["day"], t["net"]) for t in tr if lo <= t["day"] <= hi)
    return out


def in_(t, lo, hi):
    return lo <= t["day"] <= hi


def evaluate(phase: str) -> dict:
    rng = np.random.default_rng(SEED)
    if phase == "unseen":
        k = S.mods()["r51data"].K
        k.WARM_START, k.START, k.END = OLD_WARM, OLD_START, OLD_END
        lo, hi = UNSEEN
        us = S.units("R51", lo, hi)
        base = S.walk("R51", us)
        bu = [t for t in base if in_(t, lo, hi)]
        par = dict(trades=len(bu), net=round(sum(t["net"] for t in bu)), published=list(R143_BASE))
        assert (par["trades"], par["net"]) == R143_BASE or abs(par["net"] - R143_BASE[1]) <= 1, ("unseen base does not reproduce R143", par)
        per = {"unseen": lambda tr: [t for t in tr if in_(t, lo, hi)]}
    else:
        lo, hi = (K.START, K.DESIGN_END) if phase == "design" else (K.FINAL_START, K.FINAL_END)
        us = S.units("R51", lo, hi)
        base = S.walk("R51", us)
        par = S.parity("R51", phase, base)
        per = ({"design": lambda tr: S.by_period(tr, "design"), "wf": lambda tr: S.by_period(tr, "wf")} if phase == "design"
               else {"final": lambda tr: S.by_period(tr, "final")})
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), seed=SEED), "parity": par,
           "today": {p: R140.loss_stats(f(base)) for p, f in per.items()}}
    base_res = {(key[0], key[1], int(c["er"]), c["side"]): r for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
    for test, (w, kind) in TESTS.items():
        if kind == "fake":
            tr = S.walk("R51", us, exit_priced(test, us))
        else:
            tr = S.walk("R51", S.filtered(us, keep_fn(test)))
        r = {p: R140.loss_stats(f(tr)) for p, f in per.items()}
        r["rule"] = RULE[kind] + f" (lookback {w} sessions)"
        for p, f in per.items():
            r[p]["changed_vs_today"] = L.changed(f(tr), f(base))
        if phase != "design":
            p0 = list(per)[0]
            act = R140.loss_sum((t["day"], t["net"]) for t in per[p0](tr))
            if kind == "fake":
                tw, nch = R140.exit_twins(tr, base_res, lo, hi, TWINS, rng)
                r["twin"] = L.twin_p(act, tw)
                r["twin"]["sold_earlier"] = nch
            else:
                r["twin"] = L.twin_p(act, skip_twins(test, us, lo, hi, rng))
        out[test] = r
        print(phase, test, {p: (r[p]["trades"], r[p]["win"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"]) for p in per},
              "today", {p: (out["today"][p]["trades"], out["today"][p]["win"], out["today"][p]["net"], out["today"][p]["loss_sum"],
                            out["today"][p]["max_dd"]) for p in per}, flush=True)
    return out


def run(phase: str) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "unseen":
        if UNSEEN_FLAG.exists():
            raise SystemExit("Amendment B's unseen years already read: " + UNSEEN_FLAG.read_text())
        assert (RESULTS / "b_design.json").exists(), "design first"
        UNSEEN_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        S.mods()["C"].open_final()
    if phase == "final":
        S.mods()["C"].open_final()
    out = evaluate(phase)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (RESULTS / f"b_{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d, u = (json.loads((RESULTS / f"b_{p}.json").read_text()) for p in ("design", "unseen"))
    ids = list(TESTS)
    ps = [u[t]["twin"]["p"] for t in ids]
    qs = L.bh(ps)
    out = {}
    for t, p, q in zip(ids, ps, qs):
        rule = {"design": d[t]["design"], "wf": d[t]["wf"], "final": u[t]["unseen"]}     # R140.gates' 3rd slot = UNSEEN
        base = {"design": d["today"]["design"], "wf": d["today"]["wf"], "final": u["today"]["unseen"]}
        g = R140.gates(rule, base, float(q), float(p), int(u[t]["unseen"]["changed_vs_today"]))
        g["fails"] = [x.replace("final:", "unseen:") for x in g["fails"]]
        out[t] = {"rule": d[t]["rule"], "p": p, "q": float(q), **g}
    (RESULTS / "b_decision.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    if CMD == "decide":
        print(json.dumps(decide(), indent=1, default=str))
    else:
        run(CMD)
