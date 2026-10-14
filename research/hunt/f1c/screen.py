"""F1C step 2: screen every crypto brain feature for information about the next 15 / 60 / 240-minute move.

    python -m research.hunt.f1c.screen            -> results/screen.json, results/screen.md

Design data only (f1ccfg.DESIGN). PREREG.md section 3:
  - target: the signed next move y_ret_h (direction family) and its size |y_ret_h| (size family), h = 15, 60, 240
  - rows: non-overlapping, one per h minutes (bars closing on an h-minute boundary), so windows never overlap
  - test: Spearman rank correlation of the feature at t with the target, two-sided, on the SCREEN split
  - BH across every (coin, feature, horizon) of a family together, q < 0.05
  - a survivor must then show the same sign with p < 0.05 on the separate SELECT split
  - "pays": the top-minus-bottom decile spread of the next move (bps, SELECT split), halved = the gross edge of a
    long-top / short-bottom trade, must beat a Delta India perp round trip (fee + GST + slippage, both sides)
Everything else is reported as noise.
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd

from research.brain.crypto.features import REGISTRY, load_all

from . import data
from . import f1ccfg as C
from .stats import bh, decile_spread, spearman

HORIZONS = (15, 60, 240)


def round_trip_bps(sym: str) -> float:
    return 2 * (C.PERP_FEE * (1 + C.GST) + C.PERP_SLIP[sym]) * 1e4


def screen_coin(sym: str, feats: list[str]) -> list[dict]:
    R = data.rows(sym, 15, "design", horizon_min=max(HORIZONS), columns=["ts"] + feats)
    if R.empty:
        return []
    mins = (R.ts.dt.hour * 60 + R.ts.dt.minute).to_numpy()
    scr = C._in(R.ts, C.SCREEN)
    sel = C._in(R.ts, C.SELECT)
    ist_h = ((R.ts + C.IST).dt.hour).to_numpy()
    win = ist_h < 9
    out = []
    for h in HORIZONS:
        rowmask = (mins + 1) % h == 0
        y = R[f"y_ret_{h}m"].to_numpy(float)
        for f in feats:
            x = R[f].to_numpy(float)
            for fam, yy in (("direction", y), ("size", np.abs(y))):
                r1, p1, n1 = spearman(x[rowmask & scr], yy[rowmask & scr])
                r2, p2, n2 = spearman(x[rowmask & sel], yy[rowmask & sel])
                rw, pw, nw = spearman(x[rowmask & sel & win], yy[rowmask & sel & win], min_n=100)
                rec = dict(coin=sym, feature=f, family=REGISTRY[f].family, horizon=h, target=fam,
                           ic_screen=r1, p_screen=p1, n_screen=n1, ic_select=r2, p_select=p2, n_select=n2,
                           ic_select_window=rw, n_select_window=nw)
                if fam == "direction":
                    sp = decile_spread(x[rowmask & sel], y[rowmask & sel]) * 100      # % -> bps
                    rec["decile_spread_bps"] = sp
                    rec["gross_edge_bps"] = abs(sp) / 2 if np.isfinite(sp) else np.nan
                    rec["round_trip_bps"] = round_trip_bps(sym)
                out.append(rec)
    return out


def coin_file(sym: str):
    return C.OUT / "screen" / f"{sym}.parquet"


def run_coin(sym: str) -> None:
    load_all()
    r = pd.DataFrame(screen_coin(sym, sorted(REGISTRY)))
    coin_file(sym).parent.mkdir(parents=True, exist_ok=True)
    r.to_parquet(coin_file(sym), index=False)
    print(f"{sym}: {len(r)} tests", flush=True)


def run(coins=C.COINS) -> pd.DataFrame:
    for s in coins:
        if not coin_file(s).exists():
            run_coin(s)
    D = pd.concat([pd.read_parquet(coin_file(s)) for s in coins], ignore_index=True)
    D["q_screen"] = np.nan
    for fam in ("direction", "size"):
        m = D.target == fam
        D.loc[m, "q_screen"] = bh(D.loc[m, "p_screen"].to_numpy())
    D["confirmed"] = ((D.q_screen < 0.05) & (np.sign(D.ic_screen) == np.sign(D.ic_select)) & (D.p_select < 0.05))
    D["pays"] = D.confirmed & (D.target == "direction") & (D.gross_edge_bps > D.round_trip_bps)
    return D


def write(D: pd.DataFrame) -> None:
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    D.to_csv(C.RESULTS / "screen.csv", index=False, float_format="%.6g")
    summ = {}
    for fam in ("direction", "size"):
        d = D[D.target == fam]
        summ[fam] = dict(tests=int(d.p_screen.notna().sum()), bh_pass=int((d.q_screen < 0.05).sum()),
                         confirmed=int(d.confirmed.sum()), pays=int(d.pays.sum()) if fam == "direction" else None,
                         rows_screen=dict(d.groupby("horizon").n_screen.median().astype(int).astype(str)),
                         rows_select=dict(d.groupby("horizon").n_select.median().astype(int).astype(str)))
    (C.RESULTS / "screen.json").write_text(json.dumps(summ, indent=1, default=str))


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "coin":
        run_coin(sys.argv[2])
        sys.exit(0)
    D = run()
    write(D)
    print(json.dumps(json.loads((C.RESULTS / "screen.json").read_text()), indent=1))
