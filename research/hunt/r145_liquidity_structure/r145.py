"""R145 (PREREG.md): structure-shift exits, stop-and-reverse, failed-breakout reverse and flip on Liquidity 15+5.
Research only: the arm does not change.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r145_liquidity_structure/r145.py recent
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r145_liquidity_structure/r145.py unseen
    python research/hunt/r145_liquidity_structure/r145.py decide
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0]
if CMD == "unseen":
    base = Path(os.environ.get("R51_OUT", Path.home() / ".cache" / "iraalgo" / "r51")).expanduser()
    os.environ["R51_OUT"] = str(base / "r143_old")

import numpy as np  # noqa: E402

sys.path.insert(0, str(HERE.parent / "r144_other_arms"))
sys.argv = [sys.argv[0], "x"] + sys.argv[2:]        # r144 reads its own command; keep it neutral
import r144 as X  # noqa: E402

U, A, M, K, L, S = X.U, X.A, X.M, X.K, X.L, X.S
RESULTS = HERE / "results"
SEED = 145
TW_EXIT, TW_REV = 2000, 200
LAST_ENTRY = 345                                   # 15:00
ARM = "LIQ"
TESTS = ("M15-L", "M5-L", "REV-S", "REV-F", "F-L")


def opp(side: str) -> str:
    return "P" if side == "C" else "C"


def atm(D, und: str, r: int) -> float | None:
    """The strike nearest the last index close before minute r (half up)."""
    c = D.idx[3][:r]
    c = c[np.isfinite(c)]
    if not len(c):
        return None
    step = S.mods()["r51data"].K.STEP[und]
    return float(np.floor(c[-1] / step + 0.5) * step)


def one(und: str, D, side: str, strike: float, er: int, fx: int = -1):
    if strike is None or er > LAST_ENTRY:
        return None
    return X.liq_price(und, D, [dict(side=side, strike=strike, er=er)], [fx])[0]


# ------------------------------------------------------------------------------------------------ rules
def mss_priced(us: list, tf: int) -> list:
    """Every candidate re-priced with the earlier of its own forced row and the structure-shift row."""
    Mm = S.mods()
    out = []
    for key, cands, res, how in us:
        D = Mm["r51data"].load(key[0])[key[1]]
        ix = M.index(key[0])
        fxs = []
        for c in cands:
            r = M.mss_row(ix, tf, key[1], int(c["er"]), c["side"])
            own = int(c.get("fx", -1))
            fxs.append(min(own, r) if (own >= 0 and r >= 0) else max(own, r))
        if all(f == int(c.get("fx", -1)) for f, c in zip(fxs, cands)):
            out.append(list(res))
        else:
            out.append(list(X.liq_price(key[0], D, cands, fxs)))
    return out


def flipped_units(us: list) -> list:
    """F-L: each candidate the other way, same minute and strike, Liquidity's premium exits, no forced index row."""
    Mm = S.mods()
    out = []
    for key, cands, res, how in us:
        D = Mm["r51data"].load(key[0])[key[1]]
        fc = [dict(c, side=opp(c["side"]), fx=-1) for c in cands]
        out.append((key, fc, list(X.liq_price(key[0], D, fc, [-1] * len(fc))), how))
    return out


def reverse_legs(base: list, trigger: str) -> list:
    """REV-S / REV-F: the opposite ATM option bought the minute after a trigger exit; skipped when the same book's next
    trade starts before the leg ends. Legs are marked rev=True."""
    Mm = S.mods()
    by_book = {}
    for t in base:
        by_book.setdefault((t["und"], t["day"], t.get("book")), []).append(t)
    legs = []
    for bk, ts in by_book.items():
        ts.sort(key=lambda x: int(x["er"]))
        for i, t in enumerate(ts):
            hit = t["why"] == "stop" if trigger == "S" else (t["why"] == "rule_exit" and t["net"] < 0)
            if not hit:
                continue
            er = int(t["xr"]) + 1
            D = Mm["r51data"].load(t["und"])[t["day"]]
            side = opp(t["side"])
            res = one(t["und"], D, side, atm(D, t["und"], er), er)
            if res is None:
                continue
            nxt = ts[i + 1] if i + 1 < len(ts) else None
            if nxt is not None and int(nxt["er"]) <= int(res["xr"]):
                continue
            legs.append(dict(res, und=t["und"], day=t["day"], book=t.get("book"), er_sig=er, rev=True))
    return legs


def legs_twins(legs: list, n: int, rng, lo: date, hi: date, flip: bool = False) -> np.ndarray:
    """Each leg's entry moved to a uniformly random minute from its own entry to 15:00 (same day, side, engine)."""
    Mm = S.mods()
    tot = np.zeros(n)
    for t in legs:
        if not lo <= t["day"] <= hi:
            continue
        D = Mm["r51data"].load(t["und"])[t["day"]]
        e0 = int(t["er_sig"] if not flip else t["er"])
        if e0 > LAST_ENTRY:
            continue
        nets = np.zeros(n)
        for i, r in enumerate(rng.integers(e0, LAST_ENTRY + 1, size=n)):
            res = one(t["und"], D, t["side"], atm(D, t["und"], int(r)) if not flip else t["strike"], int(r))
            nets[i] = res["net"] if res is not None else 0.0
        tot += nets
    return tot


# ------------------------------------------------------------------------------------------------ run
def run(mode: str):
    Mm = S.mods()
    if mode == "unseen":
        U.patch_old()
        lo, hi, periods = U.TEST[0], U.TEST[1], X.UNSEEN
    else:
        Mm["C"].open_final()
        lo, hi, periods = K.START, K.FINAL_END, X.RECENT
    rng = np.random.default_rng(SEED + (1 if mode == "unseen" else 0))
    us = S.units(ARM, lo, hi)
    X.liq_parity(us)
    base = S.walk(ARM, us)
    out = {"_meta": dict(mode=mode, written=datetime.now(timezone.utc).isoformat()),
           "today": {p: M.loss_stats([x for x in base if a <= x["day"] <= b]) for p, (a, b) in periods.items()},
           "tests": {}, "reported": {}}
    key_p = "test" if mode == "unseen" else "final"
    a0, b0 = periods[key_p]
    base_res = {(key[0], key[1], int(c["er"]), c["side"]): r for key, cs, rr, _ in us for c, r in zip(cs, rr)
                if r is not None}
    if mode == "unseen":                      # R140's structure filters, reported only on the unseen years
        for tf in (15, 5):
            tr = S.walk(ARM, S.filtered(us, M.keep_fn(tf)))
            out["reported"][f"T{tf}-L"] = {p: M.loss_stats([x for x in tr if a <= x["day"] <= b])
                                          for p, (a, b) in periods.items()}
    for t in TESTS:
        if t.startswith("M"):
            tr = S.walk(ARM, us, mss_priced(us, 15 if t == "M15-L" else 5))
            tw = X.liq_exit_twins(us, tr, base_res, a0, b0, TW_EXIT, rng)[0] if mode == "unseen" else None
            act = M.loss_sum((x["day"], x["net"]) for x in tr if a0 <= x["day"] <= b0)
        elif t.startswith("REV"):
            legs = reverse_legs(base, t[-1])
            tr = base + legs
            act = float(sum(x["net"] for x in legs if a0 <= x["day"] <= b0))
            tw = legs_twins(legs, TW_REV, rng, a0, b0) if mode == "unseen" else None
        else:
            fu = flipped_units(us)
            tr = S.walk(ARM, fu)
            act = float(sum(x["net"] for x in tr if a0 <= x["day"] <= b0))
            tw = legs_twins([x for x in tr], TW_REV, rng, a0, b0, flip=True) if mode == "unseen" else None
        r = {p: M.loss_stats([x for x in tr if a <= x["day"] <= b]) for p, (a, b) in periods.items()}
        r["changed"] = L.changed([x for x in tr if a0 <= x["day"] <= b0], [x for x in base if a0 <= x["day"] <= b0])
        if t.startswith("REV"):
            lg = [x for x in tr if x.get("rev")]
            r["legs"] = {p: M.loss_stats([x for x in lg if a <= x["day"] <= b]) for p, (a, b) in periods.items()}
        if tw is not None:
            r["twin"] = L.twin_p(act, tw)
            r["twin"]["measure"] = "sum of losing days" if t.startswith("M") else "total net of the new legs"
        out["tests"][t] = r
        print(t, {p: (r[p]["trades"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"], r[p]["win"]) for p in periods},
              "today", {p: (out["today"][p]["trades"], out["today"][p]["net"], out["today"][p]["loss_sum"])
                        for p in periods}, "p", round(r.get("twin", {}).get("p", np.nan), 4), flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{mode}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    rec = json.loads((RESULTS / "recent.json").read_text())
    uns = json.loads((RESULTS / "unseen.json").read_text())
    ps = [uns["tests"][t]["twin"]["p"] for t in TESTS]
    out = {}
    for t, q, p in zip(TESTS, L.bh(ps), ps):
        fails = []
        for src, pers in ((rec, ("design", "wf", "final")), (uns, ("test",))):
            for per in pers:
                rr, bb = src["tests"][t][per], src["today"][per]
                if not rr["loss_sum"] > bb["loss_sum"]:
                    fails.append(f"{per}: losing days not smaller")
                if rr["max_dd"] > bb["max_dd"]:
                    fails.append(f"{per}: drawdown worse")
                if rr["worst_day"] < bb["worst_day"]:
                    fails.append(f"{per}: worst day worse")
                if rr["worst_month"] < bb["worst_month"]:
                    fails.append(f"{per}: worst month worse")
                if per == "test":
                    if rr["net"] < bb["net"]:
                        fails.append("unseen: net lower than today's")
                elif not rr["net"] > 0:
                    fails.append(f"{per}: no longer makes money")
        if not p < 0.05:
            fails.append(f"twin p {p:.3g}")
        if q > 0.10:
            fails.append(f"q {q:.3g}")
        if uns["tests"][t]["test"]["trades"] < 40 or uns["tests"][t]["changed"] < 30:
            fails.append("too few trades")
        out[t] = dict(passed=not fails, fails=fails, p=p, q=round(float(q), 4))
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    if CMD in ("recent", "unseen"):
        run(CMD)
    elif CMD == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
