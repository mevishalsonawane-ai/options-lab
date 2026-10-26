"""R113 (26 Oct 2026): the owner's two known Pine scripts, traded the way the app's Pine auto-trader trades them, from
h22's own code, unchanged (research/hunt/h22/pine_replay.py: bars_of, st_ema_targets, matrix_targets, replay).

  PINE_ST_EMA_5M / PINE_ST_EMA_15M  "Bank Nifty Supertrend + 50 EMA" (research/st_ema_pine.py), BANKNIFTY 5m / 15m
  PINE_MATRIX_5M                    "Matrix Premium v2.2" as written (research/matrix_pine.py), BANKNIFTY 5m
PineAuto defaults (h22): on each completed candle the script's position is the target; on a change the held option is
sold and the new side bought, ATM (half-up, step 100) of the candle's close, nearest expiry, 1 lot in force, at the open
of the minute after the candle (+5 bps buy / -5 bps sell), app charges (Costs('app').charge_exact); 15:15 square-off;
no entry on an expiry day. Signal source: the BANKNIFTY index candles (the default).

Data: the Drive lake laid out for obuy by adapters/obuy_layout.py. Run with
    OBUY_SCRATCH=<cache>/r113/obuy python3 -I research/hunt/r113_time_of_day/adapters/pine_h22.py <out_dir>
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
H22 = HERE.parents[1] / "h22"
sys.path.insert(0, str(H22))
import pine_replay as PR  # noqa: E402
import pandas as pd  # noqa: E402

START, END, PARITY_END = date(2021, 8, 1), date(2026, 10, 13), date(2026, 10, 6)
D0, D1, F0 = date(2024, 11, 20), date(2026, 3, 31), date(2026, 4, 1)      # R113 design / final (PREREG section 1)
PUBLISHED = {"st_ema_5m": (1565, -213000), "st_ema_15m": (539, -168000), "matrix_5m": (1491, -221000)}  # HUNT_H22.md
ARM = {"st_ema_5m": "PINE_ST_EMA_5M", "st_ema_15m": "PINE_ST_EMA_15M", "matrix_5m": "PINE_MATRIX_5M"}


def period(d: date) -> str:
    return "final" if F0 <= d <= END else ("design" if D0 <= d <= D1 else "other")


def ts(d, m: int) -> str:
    return (datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(m))).strftime("%Y-%m-%d %H:%M")


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    mk = PR.market()
    PR.H19.supplement(mk, "BANKNIFTY")
    ix = mk.index("BANKNIFTY")
    S = []
    for d in ix.days:
        a, n = PR.dense(ix.d[d])
        if a is None or n < 250:
            continue
        S.append((d, a, ix.lot(d), bool(ix.d[d].get("exp", False))))
    exp = {d: ex for d, _, _, ex in S}
    days = {d for d, *_ in S if START <= d <= END}
    summ = {}
    for name, tf, fn in (("st_ema_5m", 5, PR.st_ema_targets), ("st_ema_15m", 15, PR.st_ema_targets),
                         ("matrix_5m", 5, PR.matrix_targets)):
        b = PR.bars_of(S, tf)
        tl = PR.replay(mk, S, b, fn(b), days)
        rows = [dict(arm=ARM[name], und="BANKNIFTY", day=r.day.isoformat(), entry_ts=ts(r.day, r.em),
                     exit_ts=ts(r.day, r.xm), side="C" if r.side > 0 else "P", net_rs=round(float(r.net), 2),
                     period=period(r.day), expiry=int(exp[r.day])) for r in tl.itertuples()]
        df = pd.DataFrame(rows, columns=["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"])
        df.to_csv(out / f"{ARM[name]}.csv", index=False)
        par = tl[tl.day <= PARITY_END]
        summ[ARM[name]] = dict(
            n=len(df), net=round(float(df.net_rs.sum()), 2), first=df.day.min(), last=df.day.max(),
            by_period={p: dict(n=int((df.period == p).sum()), net=round(float(df[df.period == p].net_rs.sum()), 2))
                       for p in ("design", "final", "other")},
            parity_window=dict(span=f"{START} .. {PARITY_END}", n=len(par), net=round(float(par.net.sum()), 2),
                               published_n_net=PUBLISHED[name]),
            exits=tl.why.value_counts().to_dict())
        print(ARM[name], json.dumps(summ[ARM[name]], default=str), flush=True)
    return summ


if __name__ == "__main__":
    main(Path(sys.argv[1]).expanduser())
