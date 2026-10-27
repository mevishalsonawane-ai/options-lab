"""R117: why did FT3 skip an option on a given day, and what would each exit have made on it?

    python3 research/hunt/r117_zero_hero_lock/r117day.py NIFTY 2026-10-27 22700 P     # needs that day in the lake
    python3 research/hunt/r117_zero_hero_lock/r117day.py describe                    # 2021-26: which FT3 test fails, by side

`mark_table` re-does FT3's test at one mark for EVERY option of the chain, from that minute's data only, and says which
part failed: price band (Rs 6-10 on the mark's close), OTM, traded in its last 3 bars, distance (<= 1.5466 ATM
straddles), IV ratio (<= 1.0333 x the ATM IV, Black-76). Its pick is `ft3_app.pick_at`'s exactly (a test checks this on
every 2021-26 index-day).

27 Oct 2026 is NOT in the lake (it ends 19 Oct; the Dhan token is expired; the phone's recording is sealed with Boss's
passphrase). This script is ready for when it is.
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np

import r117cfg as K
import ft3_app as F
import r98data as R98
import r98lib as L98


def hm(t: int) -> str:
    m = t + 9 * 60 + 15
    return f"{m // 60:02d}:{m % 60:02d}"


def mark_context(day, t, sp, cache):
    """(spot, ATM strike, straddle, ATM IV, years to 15:30) at mark t, or None when FT3 cannot judge the mark."""
    s = sp[t]
    if not s == s:
        return None
    step = R98.STEP[day.und]
    ka = float(round(s / step) * step)

    def ff(key):
        if key not in cache:
            a = day.opts.get(key)
            cache[key] = L98.ffill(a[:, R98.C], 3) if a is not None else None
        return cache[key]
    c, p = ff((ka, "C")), ff((ka, "P"))
    if c is None or p is None:
        return None
    em = c[t] + p[t]
    if not em > 0:
        return None
    yt = F.years_to_1530(t)
    atm = [x for x in (F.iv(c[t], s, ka, yt, True), F.iv(p[t], s, ka, yt, False)) if x == x]
    if not atm:
        return None
    return float(s), ka, float(em), float(np.mean(atm)), yt


def mark_table(day, t, sp=None, cache=None) -> list[dict]:
    """Every option of the chain at mark t with FT3's tests. 'fails' lists the tests it fails, in FT3's order; an empty
    list means it qualifies. The FT3 pick is the qualifying row with the smallest (dist, iv ratio, call first)."""
    sp = L98.spot(day) if sp is None else sp
    cache = {} if cache is None else cache
    ctx = mark_context(day, t, sp, cache)
    if ctx is None:
        return []
    s, ka, em, atm_iv, yt = ctx
    out = []
    for (k, side), a in day.opts.items():
        px = a[t, R98.C]
        row = dict(strike=float(k), side=side, px=float(px) if px == px else None, spot=s, straddle=em, atm_iv=atm_iv,
                   dist=abs(k - s) / em, ivr=None, fails=[])
        if not (px == px and F.PX_LO - 1e-9 <= px <= F.PX_HI + 1e-9):
            row["fails"].append("price")
        if not L98.otm(k, side, s):
            row["fails"].append("otm")
        if not L98.traded(a, t):
            row["fails"].append("traded")
        if row["dist"] > F.DIST_EM_MAX + 1e-9:
            row["fails"].append("distance")
        if px == px and px > 0:
            o = F.iv(float(px), s, k, yt, side == "C")
            if o == o:
                row["ivr"] = o / atm_iv
                if row["ivr"] > F.IV_REL_MAX + 1e-9:
                    row["fails"].append("iv")
            else:
                row["fails"].append("iv")
        else:
            row["fails"].append("iv")
        out.append(row)
    return out


def pick_from_table(rows: list[dict]):
    ok = [r for r in rows if not r["fails"]]
    if not ok:
        return None
    r = min(ok, key=lambda r: (r["dist"], r["ivr"], 0 if r["side"] == "C" else 1))
    return r["strike"], r["side"], r["px"], r["dist"], r["ivr"]


def explain(day, strike: float, side: str) -> list[dict]:
    """FT3's 24 marks for one option: its close, distance, IV ratio and the tests it failed; and the mark's pick."""
    sp, cache = L98.spot(day), {}
    out = []
    for t in F.MARKS:
        rows = mark_table(day, t, sp, cache)
        me = [r for r in rows if r["strike"] == strike and r["side"] == side]
        pk = pick_from_table(rows)
        out.append(dict(mark=hm(t), option=me[0] if me else None,
                        pick=None if pk is None else f"{pk[0]:g} {'CE' if pk[1] == 'C' else 'PE'} at {pk[2]:.2f}"))
        if pk is not None:
            break                                          # FT3 takes the first mark with a pick
    return out


def describe(days) -> dict:
    """2021-26, every FT3 mark of every index-day before its pick: of the OTM options priced Rs 6-10 that traded and lie
    within the distance, the share failing the IV ratio, by side; and how often each index had no FT3 signal."""
    cnt = {s: {"n": 0, "iv_fail": 0} for s in ("C", "P")}
    band = {s: {"n": 0, "dist_fail": 0} for s in ("C", "P")}
    no_sig: dict = {}
    for day in days:
        sp, cache = L98.spot(day), {}
        got = False
        for t in F.MARKS:
            rows = mark_table(day, t, sp, cache)
            for r in rows:
                f = set(r["fails"])
                if f & {"price", "otm", "traded"}:
                    continue
                band[r["side"]]["n"] += 1
                band[r["side"]]["dist_fail"] += "distance" in f
                if "distance" in f:
                    continue
                cnt[r["side"]]["n"] += 1
                cnt[r["side"]]["iv_fail"] += "iv" in f
            if pick_from_table(rows) is not None:
                got = True
                break
        u = no_sig.setdefault(day.und, [0, 0])
        u[0] += 1
        u[1] += not got
    return dict(iv=cnt, distance=band, no_signal={u: dict(days=v[0], none=v[1]) for u, v in no_sig.items()})


def main(argv):
    if argv[:1] == ["describe"]:
        days = [d for u in R98.UNDS for d in R98.load(u) if K.period(d.day) is not None]
        out = describe(days)
        K.RESULTS.mkdir(exist_ok=True)
        (K.RESULTS / "describe.json").write_text(json.dumps(out, indent=1))
        print(json.dumps(out, indent=1))
        return
    und, d, strike, side = argv[0], date.fromisoformat(argv[1]), float(argv[2]), argv[3].upper()[0]
    day = next((x for x in R98.load(und) if x.day == d), None)
    if day is None:
        print(f"{und} {d}: not in the lake (it ends {max(x.day for x in R98.load(und))}). Cannot replay.")
        return
    for r in explain(day, strike, side):
        o = r["option"]
        if o is None:
            print(r["mark"], "the option is not in the chain at this minute", "| pick:", r["pick"])
        else:
            ivr = "-" if o["ivr"] is None else f"{o['ivr']:.3f}"
            print(r["mark"], f"px {o['px']}", f"dist {o['dist']:.2f}", f"ivr {ivr}", "fails:", ",".join(o["fails"]) or
                  "none", "| pick:", r["pick"])


if __name__ == "__main__":
    main(sys.argv[1:])
