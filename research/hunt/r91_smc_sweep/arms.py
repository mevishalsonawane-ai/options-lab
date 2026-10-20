"""R91 same-day comparison with the arms already running (PREREG section 8; descriptive, no gate).

R72 Breadth Donchian (C1), the Liquidity 15+5 arm (R51 fam_liq: BANKNIFTY as the app runs it, plus the other four
indices descriptively) and the ORB signal (R20 V0): R89's own functions on the same stores. The holdout is compared only
after R91's own holdout read (results/FINAL_READ exists).
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r89_pdhl_break import arms as A89

from . import cfg as K


def r91(tag: str, v: str) -> pd.DataFrame:
    parts = []
    for und in K.INDICES:
        p = K.OUT / "trades" / tag / f"{und}_{v}.parquet"
        if p.exists():
            x = pd.read_parquet(p)
            x = x[x.filled]
            parts.append(pd.DataFrame(dict(und=und, date=pd.to_datetime(x.date).dt.date, side=x.side, net=x.net)))
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=["und", "date", "side", "net"])


def liq_all(lo, hi) -> pd.DataFrame:
    import fam_liq  # noqa: E402  (research/hunt/r51 on sys.path via R89's store)
    rows = []
    for und in K.INDICES:
        T = fam_liq.make(und, (15, 5)).run(lo, hi)
        if len(T):
            rows.append(pd.DataFrame(dict(und=T.und.values, date=T.day.values, side=np.where(T.side == "C", 1, -1),
                                          net=T.net.values)))
    return pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=["und", "date", "side", "net"])


def main():
    wins = [("design", *K.IDX_DESIGN)]
    if K.FINAL_FLAG.exists():
        A89.C51.open_final()
        wins.append(("final", *K.HOLDOUT))
    out = {}
    for tag, lo, hi in wins:
        sessions = {u: [d for d in sorted(A89.r51data.load(u)) if lo <= d <= hi and A89.r51data.load(u)[d].atm is not None]
                    for u in K.INDICES}
        LQ = liq_all(lo, hi)
        arms = dict(breadth_donchian=A89.bd_trades(lo, hi), liquidity_bn=LQ[LQ.und == "BANKNIFTY"],
                    liquidity_all5=LQ, orb_signal=A89.orb_signals(lo, hi))
        res = dict(arms={k: dict(trades=int(len(a)), net=round(float(a.net.sum()), 0) if "net" in a else None)
                         for k, a in arms.items()})
        res["variants"] = {}
        for v in K.VARIANTS:
            R = r91(tag, v.name)
            res["variants"][v.name] = {
                k: A89.compare(R if k != "liquidity_bn" else R[R.und == "BANKNIFTY"], a, sessions, k != "orb_signal")
                for k, a in arms.items()}
        out[tag] = res
        print(tag, json.dumps(res["arms"]), flush=True)
    (K.RESULTS / "arms.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
