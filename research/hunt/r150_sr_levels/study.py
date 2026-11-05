"""R150 study (PREREG sections 3-4 and A1): 3-month S/R level filters on R51 Donchian 20 (today's app rules).

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study.py design
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study.py final   # read once
    python research/hunt/r150_sr_levels/study.py decide

R51's trades come from R115's engine (parity asserted against the published arm), exactly as R140. Levels: levels.py
on the store's own index minutes, known at the decision minute (the minute before the fill).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r140_structure_arms"))
sys.path.insert(0, str(HERE.parents[2]))
import r140 as R140  # noqa: E402

from research.hunt.r150_sr_levels import levels as V  # noqa: E402

K, L, S = R140.K, R140.L, R140.S
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
SEED, TWINS = 150, 2000
TESTS = {   # id -> (pivot bars each side, kind, touches)
    "W3": (V.K_SIDE, "wall", 3), "W2": (V.K_SIDE, "wall", 2), "B3": (V.K_SIDE, "cross", 3),
    "W3M": (12, "wall", 3), "B3M": (12, "cross", 3),
    "FX": (V.K_SIDE, "fake", 3), "FXM": (12, "fake", 3),          # Amendment A2: exits
}
RULE = {"wall": "skip when a {n}+-turn level lies within 0.25% ahead of the trade",
        "cross": "skip unless the break crossed a {n}+-turn level",
        "fake": "sell when a 5-minute bar closes back through the {n}+-turn level the break crossed (fake breakout)"}


class Lv:
    """One index's 15-minute bars and pivots (both pivot widths) from the R51 store's index minutes."""

    def __init__(self, und: str):
        days = S.mods()["r51data"].load(und)
        self.dates = sorted(days)
        self.pos = {d: i for i, d in enumerate(self.dates)}
        self.idx = {d: days[d].idx for d in self.dates}
        self.b = V.bars15([days[d].idx for d in self.dates])
        self.piv = {k: V.pivots(self.b["h"], self.b["l"], k) for k in (V.K_SIDE, 12)}
        self.cache: dict = {}

    def close_at(self, day, row: int) -> float:
        c = self.idx[day][3, : max(row, 0) + 1]
        ok = np.flatnonzero(np.isfinite(c))
        return float(c[ok[-1]]) if ok.size else np.nan

    def levels(self, day, er: int, k: int) -> list:
        key = (day, er, k)
        if key not in self.cache:
            self.cache[key] = V.levels_at(self.b, self.piv[k], self.pos[day], er)   # bars ending before minute er
        return self.cache[key]


_LV: dict = {}


def lv(und: str) -> Lv:
    if und not in _LV:
        _LV[und] = Lv(und)
    return _LV[und]


def skip(test: str, und: str, day, er: int, side: str) -> bool:
    k, kind, n = TESTS[test]
    x = lv(und)
    levels = x.levels(day, er, k)
    price = x.close_at(day, er - 1)
    if not np.isfinite(price):
        return False
    if kind == "wall":
        return V.wall_ahead(levels, price, side, 0.0025, n)
    prev = x.close_at(day, er - 6)
    return not (np.isfinite(prev) and V.crossed(levels, prev, price, side, n))


def fake_row(test: str, und: str, day, er: int, side: str) -> int:
    """FX / FXM: the forced-exit row of a trade whose break crossed a level, else -1 (Amendment A2)."""
    k, _, n = TESTS[test]
    x = lv(und)
    price, prev = x.close_at(day, er - 1), x.close_at(day, er - 6)
    if not (np.isfinite(price) and np.isfinite(prev)):
        return -1
    level = V.crossed_level(x.levels(day, er, k), prev, price, side, n)
    if level is None:
        return -1
    return V.fake_exit_row(x.idx[day][3], er, level, side)


def exit_priced(test: str, us: list) -> list:
    """R140's exit_priced with the fake-break row: the arm's exits stay on, the earlier wins; no row: R101's own price."""
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


def describe_fakes(us: list) -> dict:
    """A2's descriptive table: signals crossing a 3+ level, and how often they go back through it within 30 minutes."""
    out = {}
    for k in (V.K_SIDE, 12):
        n = cross = fake = plain = plain_back = 0
        for key, cands, _, _ in us:
            x = lv(key[0])
            for c in cands:
                er, side = int(c["er"]), c["side"]
                price, prev = x.close_at(key[1], er - 1), x.close_at(key[1], er - 6)
                if not (np.isfinite(price) and np.isfinite(prev)):
                    continue
                n += 1
                level = V.crossed_level(x.levels(key[1], er, k), prev, price, side, 3)
                if level is not None:
                    cross += 1
                    r = V.fake_exit_row(x.idx[key[1]][3], er, level, side)
                    fake += int(0 <= r <= er + 30)
                else:
                    plain += 1
                    r = V.fake_exit_row(x.idx[key[1]][3], er, price, side)
                    plain_back += int(0 <= r <= er + 30)
        out["major" if k == 12 else "dense"] = {"signals": n, "crossed_a_level": cross,
                                               "crossed_and_back_within_30m": fake,
                                               "fake_share": fake / cross if cross else None,
                                               "no_level_back_below_break_close_within_30m": plain_back / plain if plain else None}
    return out


def keep_fn(test: str):
    return lambda g, j, key, c: not skip(test, key[0], key[1], int(c["er"]), c["side"])


def twins(test: str, us: list, lo, hi, n: int, rng) -> np.ndarray:
    kf = keep_fn(test)
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
    out = np.zeros(n)
    for i in range(n):
        gone = {flat[x] for x in L.random_drop(len(flat), k, rng)}
        tr = S.walk("R51", S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
        out[i] = R140.loss_sum((t["day"], t["net"]) for t in tr if lo <= t["day"] <= hi)
    return out


def evaluate(phase: str, lo, hi, with_twins: bool) -> dict:
    rng = np.random.default_rng(SEED)
    pers = ("design", "wf") if phase == "design" else ("final",)
    us = S.units("R51", lo, hi)
    base = S.walk("R51", us)
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), twins=TWINS if with_twins else 0, seed=SEED),
           "parity": S.parity("R51", phase, base), "today": {p: R140.loss_stats(S.by_period(base, p)) for p in pers}}
    base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
    if phase == "design":
        out["fakes"] = describe_fakes(us)
        print("fakes", out["fakes"], flush=True)
    for test, (k, kind, n) in TESTS.items():
        if kind == "fake":
            fu = us
            tr = S.walk("R51", us, exit_priced(test, us))
        else:
            fu = S.filtered(us, keep_fn(test))
            tr = S.walk("R51", fu)
        r = {p: R140.loss_stats(S.by_period(tr, p)) for p in pers}
        r["rule"] = RULE[kind].format(n=n) + (" (major levels: 12 bars each side)" if k == 12 else " (15-min pivots, 4 bars each side)")
        r["removed_candidates"] = sum(len(u[1]) for u in us) - sum(len(u[1]) for u in fu)
        for p in pers:
            r[p]["changed_vs_today"] = L.changed(S.by_period(tr, p), S.by_period(base, p))
        if with_twins:
            act = R140.loss_sum((t["day"], t["net"]) for t in S.by_period(tr, "final"))
            if kind == "fake":
                tw, nch = R140.exit_twins(tr, base_res, lo, hi, TWINS, rng)
                r["twin"] = L.twin_p(act, tw)
                r["twin"]["sold_earlier"] = nch
            else:
                r["twin"] = L.twin_p(act, twins(test, us, lo, hi, TWINS, rng))
            r["twin"]["rule_loss_sum"] = round(act)
        out[test] = r
        print(phase, test, {p: (r[p]["trades"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"]) for p in pers}, "today",
              {p: (out["today"][p]["trades"], out["today"][p]["net"], out["today"][p]["loss_sum"], out["today"][p]["max_dd"])
               for p in pers}, flush=True)
    return out


def run(phase: str) -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("R150 final already read once: " + FINAL_FLAG.read_text())
        assert (RESULTS / "design.json").exists(), "the design read comes first"
        FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        S.mods()["C"].open_final()
        out = evaluate("final", K.FINAL_START, K.FINAL_END, True)
    else:
        out = evaluate("design", K.START, K.DESIGN_END, False)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((RESULTS / "design.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    ids = list(TESTS)
    ps = [f[t]["twin"]["p"] for t in ids]
    qs = L.bh(ps)
    out = {}
    for t, p, q in zip(ids, ps, qs):
        rule = {"design": d[t]["design"], "wf": d[t]["wf"], "final": f[t]["final"]}
        base = {"design": d["today"]["design"], "wf": d["today"]["wf"], "final": f["today"]["final"]}
        g = R140.gates(rule, base, float(q), float(p), int(f[t]["final"]["changed_vs_today"]))
        out[t] = {"rule": d[t]["rule"], "p": p, "q": float(q), **g}
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "decide":
        print(json.dumps(decide(), indent=1, default=str))
    else:
        run(cmd)
