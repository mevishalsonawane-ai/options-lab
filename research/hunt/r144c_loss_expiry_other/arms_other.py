"""R144c (PREREG.md section 1): trade lists of Night (R3), VIX divergence and IraThinker Flow.

    IRAALGO_LAKE=<lake> R27_OUT=<r27 cache> OBUY_SCRATCH=<obuy layout> python -I \
        research/hunt/r144c_loss_expiry_other/arms_other.py [night|vixdiv|flow ...]

- Night (R3): R27's B0 (= the app's NightRules), R27's own data and costs, every night of the lake (R113's adapter loop,
  with the entry minute checked against R27's stored 15:20 price).
- VIX divergence: R1's `n13_vixdiv` + `run_engine` (R113's adapter), on every good session (R1's `ok`), not only R1's
  design / holdout blocks. NIFTY with the Liquidity exit, BANKNIFTY to 15:10.
- Flow: `flow_port.py`.
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
sys.path.insert(0, str(HERE))
import lake as LK  # noqa: E402


def save(arm, rows):
    LK.OUT.mkdir(parents=True, exist_ok=True)
    (LK.OUT / f"trades_{arm}.pkl").write_bytes(pickle.dumps(rows))
    print(arm, len(rows), round(sum(r["net"] for r in rows)), flush=True)


def night():
    sys.path.insert(0, str(HUNT / "r27"))
    import r27cfg as K
    import r27data as D
    import r27lib as R
    rows, bad = [], 0
    for u in K.UNDS:
        nights = R.r3_nights(D.load(u, ("desc", "wf", "hold")))
        o = D._opt_frames(u, K.TRADE_SERIES[u], lambda f: (f.m >= D.R1520) & (f.m < D.R1520 + 4))
        o = o[np.isfinite(o.open)].sort_values("m")
        er = {k: (int(r.m), float(r.open)) for k, r in o.groupby(["d", "strike", "side"]).first().iterrows()}
        for n in nights:
            t = R.trade(n, n.r3, R.B0)
            if t is None:
                continue
            k = n.strike[n.r3]
            sd = "C" if n.r3 > 0 else "P"
            r0, px = er.get((n.d, k, sd), (-1, np.nan))
            if r0 < 0 or abs(px - n.entry[n.r3]) > 1e-9:
                bad += 1
                continue
            gap = float(n.ix[0, 0] / n.d_close - 1) if n.ix is not None and n.d_close > 0 else np.nan
            rows.append(dict(arm="NIGHT", und=u, day=n.d, exit_day=n.nd, side=sd, strike=float(k), sig_row=r0 - 1,
                             entry_row=r0, entry_px=float(t.efill), exit_row=int(t.row), exit_kind="09:16 next day",
                             net=float(t.net), reach=np.nan, loc=float(n.loc), move=float(n.move),
                             breadth=float(n.breadth), buz=float(n.buz), vix_prev=float(n.vix_prev),
                             next_gap_signed=gap * (1 if n.r3 > 0 else -1)))
    print("night entry-minute mismatches", bad)
    save("NIGHT", sorted(rows, key=lambda r: (r["day"], r["und"])))


def vixdiv():
    sys.path.insert(0, str(HUNT / "r1"))
    import lib as L
    import design as Dz
    import signals as S
    pick = {"NIFTY": "N13_VIXDIV|a0.002|LIQ", "BANKNIFTY": "N13_VIXDIV|a0.002|EOD"}
    rows = []
    for u in ("NIFTY", "BANKNIFTY"):
        P = L.load_index(u)
        days_ok = np.nonzero(P["ok"])[0]
        vmin, _ = L.vix_minutes(P["days"])
        Rq = S.Req()
        S.n13_vixdiv(P, Rq, days_ok, vmin)
        req = Rq.frame()
        req = req[req.di.isin(days_ok) & (req["var"] == pick[u])].reset_index(drop=True)
        tr = Dz.engine_dedup(P, req)
        tr = tr[np.isfinite(tr.net)]
        for r in tr.itertuples():
            d = P["days"][int(r.di)]
            ent, ex = int(r.ent), int(r.ex)
            kind = "15:10" if ex >= LK.row(15, 9) else ("stop" if getattr(r, "stop", 0) else "20-min / early")
            rows.append(dict(arm="VIXDIV", und=u, day=d, side="C" if int(r.side) == 0 else "P", strike=np.nan,
                             sig_row=int(r.s), entry_row=ent, entry_px=float(r.E), exit_row=ex, exit_kind=kind,
                             net=float(r.net), reach=np.nan, expiry=int(bool(P["exp"][int(r.di)]))))
        L.D.market().release()
    save("VIXDIV", sorted(rows, key=lambda r: (r["day"], r["und"])))


def flow():
    import flow_port as F
    rows, stages = F.build()
    (LK.OUT / "flow_stages.pkl").write_bytes(pickle.dumps(stages))
    print(stages)
    save("FLOW", rows)


if __name__ == "__main__":
    for a in sys.argv[1:] or ["night", "vixdiv", "flow"]:
        globals()[a]()
