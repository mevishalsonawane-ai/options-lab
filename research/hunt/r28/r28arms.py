#!/usr/bin/env python3
"""R28: the arms' unfiltered trade lists on the lake, each rebuilt with its own study's code (PREREG.md, Step 3).

    python3 research/hunt/r28/r28arms.py orb      # ORB (R20 V0 via R22) + Range Fade, BANKNIFTY + NIFTY  (R17 lake days)
    python3 research/hunt/r28/r28arms.py liq      # Liquidity 15+5 BANKNIFTY (R19b engine walk, lake via LakeAdapter)
    python3 research/hunt/r28/r28arms.py hero     # Hero H0 + lottery L3 (R25 rules on R25's expiry-day records)
    python3 research/hunt/r28/r28arms.py night    # Night R3, the 09:16 sale (R27's rebuild of NightRules)

Each command runs in its own process (the studies use bare module names such as `config`, `data`, `arms`), and writes
<OUT>/trades_<arm>.parquet with one row per trade: arm, und, day, row (the grid row whose GEX the filter reads: the bar
that CLOSES at the filter's clock time, or the signal bar when that comes earlier), sig_row, net (Rs, the study's own
fills and charges), net_x2 (the stress cost: 2x the spread where the code has it, R27's 1.5x for Night; NaN where
none), and the arm's sessions (all trading days the arm could have traded) go to <OUT>/sessions_<arm>.json.
No period is chosen here: every lake day is rebuilt; the split into design / holdout happens in study.py.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

import r28cfg as K

HUNT = K.ROOT / "research" / "hunt"


def _save(arm: str, rows: list[dict], sessions: dict):
    K.OUT.mkdir(parents=True, exist_ok=True)
    df = pd.DataFrame(rows)
    df.to_parquet(K.OUT / f"trades_{arm}.parquet")
    json.dump({u: sorted(str(d) for d in s) for u, s in sessions.items()},
              open(K.OUT / f"sessions_{arm}.json", "w"))
    print(arm, len(df), "trades", {u: len(s) for u, s in sessions.items()}, "sessions", flush=True)


# ------------------------------------------------------------------------------------------------ ORB, Range Fade
def orb():
    os.environ["R22_SOURCE"] = "lake"
    sys.path.insert(0, str(HUNT / "r22"))
    import r22cfg  # noqa: F401  (R17 / R20 on sys.path, R17_SOURCE=lake)
    import arms as A
    import r22trades as T22
    from research.obuy.costs import Fills
    row10 = K.CLOCK["10:00"]

    def fade_day(d, c):
        """R17's Range Fade signals (bars 10:30 .. 13:55, 2 a day, no side cap), R22's trade() at the ORB strike with
        the ORB family's exits per index; a new decision only from the bar after an exit (as run_orb)."""
        out, block = [], -1
        for k, s, _ in A.orb_signals(d, "fade", 15, 56):
            if 5 * k <= block or len(out) >= 2:
                continue
            t = T22.trade(d, c, s, 5 * k + 4)
            if t:
                out.append(t)
                block = t["exit_row"] - (t["exit_row"] % 5)
        return out

    rows, ses = {"orb": [], "fade": []}, {"orb": {}, "fade": {}}
    for und in ("BANKNIFTY", "NIFTY"):
        for d, c in T22.prep(und):
            for arm, fn in (("orb", T22.baseline_day), ("fade", fade_day)):
                ses[arm].setdefault(und, set()).add(c.day)
                base = fn(d, c)
                A.FILLS = T22.Fills2x(mode="liq")
                try:
                    x2 = fn(d, c)
                finally:
                    A.FILLS = Fills(mode="liq")
                x2n = {(t["side"], t["row"]): t["net"] for t in x2}
                for t in base:
                    rows[arm].append(dict(arm=arm, und=und, day=c.day, row=row10, sig_row=int(t["row"]),
                                          side=int(t["side"]), net=float(t["net"]),
                                          net_x2=float(x2n.get((t["side"], t["row"]), np.nan)), why=t["why"]))
        print(und, "done", len(rows["orb"]), len(rows["fade"]), flush=True)
    _save("orb", rows["orb"], ses["orb"])
    _save("fade", rows["fade"], ses["fade"])


# ------------------------------------------------------------------------------------------------ Liquidity 15+5
def liq():
    from research.brain.adapters.lake import LakeAdapter
    from research.hunt.r19b import data as D
    from research.hunt.r19b import sim as S
    D.ReleaseAdapter = lambda raw: LakeAdapter(K.LAKE)          # same build, the lake instead of the release
    p = K.OUT / "liq_recs.pkl"
    if p.exists():
        recs = pd.read_pickle(p)
    else:
        recs = D.build()
        pd.to_pickle(recs, p)
    ad = LakeAdapter(K.LAKE)
    sess = {"BANKNIFTY": set(ad.full_sessions("BANKNIFTY"))}
    rows = []
    for book, _ in S.BOOKS:
        for t in S.run_book(recs, book, None):
            sig = int(t["entry_min"]) - 555 - 1                    # the bar before the entry fill
            rows.append(dict(arm="liq", und="BANKNIFTY", day=t["day"], row=sig, sig_row=sig, side=int(t["side"]),
                             net=float(t["net"]), net_x2=np.nan, why=str(t.get("why", "")), book=book))
    _save("liq", rows, sess)


# ------------------------------------------------------------------------------------------------ Hero H0, lottery L3
def hero():
    sys.path.insert(0, str(HUNT / "r25"))
    import r25cfg as K25
    import r25data as D25
    import r25rules as RU
    rows, sess = [], {}
    r1345 = K.CLOCK["13:45"]
    for und in K25.UNDS:
        days = D25.load(und, "all")                     # cleaned by load()
        sess[und] = {d.day for d in days}
        for rule in (RU.H0, RU.L3):
            base = RU.run(days, rule)
            x2 = {(t.day, t.side, t.strike): t.net for t in RU.run(days, rule, mult=2.0)}
            for t in base:
                row = min(int(t.sig_row), r1345) if rule.id == "H0" else r1345
                rows.append(dict(arm=rule.id.lower(), und=und, day=t.day, row=row, sig_row=int(t.sig_row),
                                 side=int(t.side), net=float(t.net), net_x2=float(x2.get((t.day, t.side, t.strike), np.nan)),
                                 why="", risked=float(t.risked), peak_x=float(t.peak_mult)))
        print(und, "done", len(rows), flush=True)
    df = pd.DataFrame(rows)
    _save("h0", df[df.arm == "h0"].to_dict("records"), {u: s for u, s in sess.items() if u in RU.H0.unds})
    _save("l3", df[df.arm == "l3"].to_dict("records"), sess)


# ------------------------------------------------------------------------------------------------ Night R3
def night():
    sys.path.insert(0, str(HUNT / "r27"))
    import r27data as D27
    import r27lib as L27
    rows, sess = [], {}
    for und in ("NIFTY", "BANKNIFTY"):
        ns = D27.load(und, ("desc", "wf", "hold"))
        sess[und] = {n.d for n in ns}
        for n in L27.r3_nights(ns):
            t = L27.trade(n, n.r3, L27.B0)
            if t is None:
                continue
            rows.append(dict(arm="night", und=und, day=t.d, row=K.CLOCK["15:15"], sig_row=K.CLOCK["15:15"],
                             side=int(t.side), net=float(t.net), net_x2=float(t.net15), why=t.why))
    _save("night", rows, sess)


if __name__ == "__main__":
    {"orb": orb, "liq": liq, "hero": hero, "night": night}[sys.argv[1]]()
