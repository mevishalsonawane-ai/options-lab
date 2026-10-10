"""h38 data checks (no P&L, nothing chosen from them):
 1. synthetic futures basis (put-call parity, nearest option series) vs the REAL futures basis on the minute-futures
    window: correlation of 15-minute changes (the synthetic is the only basis series that exists before 2026-07-29);
 2. bhavcopy vs Dhan daily continuous futures (spot-check of the fetched daily data);
 3. coverage of the daily table.

    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/diag.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import feats as FT  # noqa: E402

pd.set_option("display.width", 220)


def basis_check():
    print("== synthetic vs real futures basis, 15-min changes (bps), 2026-07-29 .. 2026-10-06 ==")
    for u in FT.UNDS_B:
        fd, F = FT.fut(u)
        sd, S = FT.syn(u)
        sp = {d: i for i, d in enumerate(sd)}
        a, b, lv_r, lv_s = [], [], [], []
        for i, d in enumerate(fd):
            if d not in sp:
                continue
            fb, sb = F["fbas"][i], S["basis"][sp[d]]
            for t in range(30, 360, 15):
                if np.isfinite(fb[t]) and np.isfinite(fb[t - 15]) and np.isfinite(sb[t]) and np.isfinite(sb[t - 15]):
                    a.append(fb[t] - fb[t - 15]); b.append(sb[t] - sb[t - 15])
                    lv_r.append(fb[t]); lv_s.append(sb[t])
        a, b = np.array(a), np.array(b)
        print(f"{u:11s} n={len(a):5d} corr(d15)={np.corrcoef(a, b)[0, 1]:.2f}  sd real {a.std():.1f} syn {b.std():.1f}  "
              f"level real {np.median(lv_r):.0f} syn {np.median(lv_s):.0f} bps (monthly vs nearest-option expiry)")


def daily_check():
    D = pd.read_parquet(os.path.join(FT.DATA, "daily_fut.parquet"))
    print("\n== daily table coverage ==")
    print(D.groupby("sym").agg(days=("date", "size"), first=("date", "min"), last=("date", "max"),
                               spot_ok=("spot_close", lambda x: x.notna().mean()),
                               opt_ok=("o_oi_CE", lambda x: x.notna().mean()),
                               med_oi_lots=("f_oi", "median")).to_string())
    dh = os.path.join(C.DATA, "futures", "NSE_FNO", "NIFTY", "2026-10-27_daily.parquet")
    if os.path.exists(dh):
        x = pd.read_parquet(dh)
        x["date"] = pd.to_datetime(x.ts, unit="s", utc=True).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None).dt.normalize()
        m = x.merge(D[D.sym == "NIFTY"], on="date")
        print(f"\nNIFTY: Dhan daily continuous (2026-10 id) vs bhavcopy near month, {len(m)} days: "
              f"median |close diff| {np.median(np.abs(m.close - m.f_close)):.2f} pts; "
              f"median OI ratio Dhan / near-month {np.median(m.open_interest / m.f_oi_near):.3f}")
    for u in FT.UNDS_DAILY:
        S = FT.daily(u)
        print(u, {c: int((S[c] != 0).sum()) for c in S.columns if c[:1] == "D" and c[:2] != "db" and c != "dOI_z"})


if __name__ == "__main__":
    daily_check() if sys.argv[1:] == ["daily"] else (basis_check() if sys.argv[1:] == ["basis"] else (basis_check(), daily_check()))
