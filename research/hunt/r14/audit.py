"""R14 Part B: the other strategies' churn, from the trades R12 rebuilt with each strategy's own source code
(<scratch>/hunt/r12/arm_<arm>.npz: Liquidity BANKNIFTY, ORB Sweep, Range Fade, VIX divergence NIFTY / BANKNIFTY, Hero).
Per strategy: trades, entries a day (mean / max), same-side entries a day (max), same-side re-entries right after a target,
gross, charges, net, charges as % of the winners' gross; design (to 30 Sep 2025) and holdout. Information only.

    python3 -I research/hunt/r14/audit.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import config as C  # noqa: E402

R12 = os.path.join(C.SCRATCH, "hunt", "r12")
ARMS = ("liq_bn", "orb_sweep", "range_fade", "vixdiv_ni", "vixdiv_ba", "hero")
HOLD = pd.Timestamp("2025-10-01")


def load(arm):
    z = np.load(os.path.join(R12, f"arm_{arm}.npz"), allow_pickle=True)
    dn = z["m_dn"]
    t = pd.DataFrame(dict(day=pd.to_datetime(dn, unit="D"), side=z["m_side"], sig=z["m_sig_min"], book=z["m_book"],
                          why=z["src_why"], gross=z["src_gross"], charges=z["src_charges"], net=z["src_net"]))
    return t.sort_values(["day", "sig"], kind="stable").reset_index(drop=True)


def stats(t):
    pd_ = t.groupby("day").size()
    ps = t.groupby(["day", "book", "side"]).size()
    prev_side = t.groupby(["day", "book"]).side.shift()
    prev_why = t.groupby(["day", "book"]).why.shift()
    re = t[(prev_side == t.side) & prev_why.isin(["target", "index_target"])]
    gp = t.gross[t.gross > 0].sum()
    return dict(trades=len(t), days=int(pd_.size), per_day=round(float(pd_.mean()), 2), max_day=int(pd_.max()),
                max_same_side=int(ps.max()), reentry_after_target=len(re), reentry_net=round(float(re.net.sum())),
                gross=round(float(t.gross.sum())), charges=round(float(t.charges.sum())), net=round(float(t.net.sum())),
                chg_pct=round(100 * float(t.charges.sum()) / gp, 1) if gp > 0 else None)


def main():
    rows = []
    for arm in ARMS:
        try:
            t = load(arm)
        except FileNotFoundError:
            print(arm, "no data")
            continue
        for part, m in (("design", t.day < HOLD), ("holdout", t.day >= HOLD)):
            rows.append(dict(arm=arm, part=part, **stats(t[m])))
    df = pd.DataFrame(rows)
    pd.set_option("display.width", 250)
    print(df.to_string(index=False))
    df.to_csv(os.path.join(C.SCRATCH, "hunt", "r14", "audit.csv"), index=False)


if __name__ == "__main__":
    main()
