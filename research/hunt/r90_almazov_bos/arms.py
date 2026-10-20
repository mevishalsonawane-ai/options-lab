"""R90 same-day comparison with the arms already running (PREREG section 8; descriptive, no gate).

R72 Breadth Donchian (C1), Liquidity BANKNIFTY 15+5 and the ORB signal (R20 V0): R89's own functions on the same
stores. R89's base rule (C-B) is re-run on the same stores with R89's code. The holdout is compared only after R90's own
holdout read (results/FINAL_READ exists).
"""
from __future__ import annotations

import json

import pandas as pd

from research.hunt.r89_pdhl_break import arms as A89
from research.hunt.r89_pdhl_break import cfg as K89
from research.hunt.r89_pdhl_break import study as S89

from . import cfg as K


def r90(tag: str, v: str) -> pd.DataFrame:
    parts = []
    for und in K.INDICES:
        p = K.OUT / "trades" / tag / f"{und}_{v}.parquet"
        if p.exists():
            x = pd.read_parquet(p)
            x = x[x.filled]
            parts.append(pd.DataFrame(dict(und=und, date=pd.to_datetime(x.date).dt.date, side=x.side, net=x.net)))
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=["und", "date", "side", "net"])


def r89_base(lo, hi, holdout: bool) -> pd.DataFrame:
    if holdout:
        S89._HOLDOUT_OPEN = True            # R89's own holdout was read on 20 Oct (its FINAL_READ); this re-runs its base
    rows = []
    for und in K.INDICES:
        _, T = S89.Inst(und).run(K89.BY_NAME["C-B"], lo, hi, twins=1)
        T = T[T.filled]
        rows.append(pd.DataFrame(dict(und=und, date=T.date.values, side=T.side.values, net=T.net.values)))
    return pd.concat(rows, ignore_index=True)


def main():
    wins = [("design", *K.IDX_DESIGN, False)]
    if K.FINAL_FLAG.exists():
        A89.C51.open_final()
        wins.append(("final", *K.HOLDOUT, True))
    out = {}
    for tag, lo, hi, hold in wins:
        sessions = {u: [d for d in sorted(A89.r51data.load(u)) if lo <= d <= hi and A89.r51data.load(u)[d].atm is not None]
                    for u in K.INDICES}
        arms = dict(breadth_donchian=A89.bd_trades(lo, hi), liquidity_bn=A89.liq_trades(lo, hi),
                    orb_signal=A89.orb_signals(lo, hi), r89_cb=r89_base(lo, hi, hold))
        res = dict(arms={k: dict(trades=int(len(a)), net=round(float(a.net.sum()), 0) if "net" in a else None)
                         for k, a in arms.items()})
        res["variants"] = {}
        for v in K.VARIANTS:
            R = r90(tag, v.name)
            res["variants"][v.name] = {
                k: A89.compare(R if k != "liquidity_bn" else R[R.und == "BANKNIFTY"], a, sessions, k != "orb_signal")
                for k, a in arms.items()}
        out[tag] = res
        print(tag, json.dumps(res["arms"]), flush=True)
    (K.RESULTS / "arms.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
