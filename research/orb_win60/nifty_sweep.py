"""Selected NIFTY weekly sweep replay: python -m research.orb_win60.nifty_sweep LAKE TRADES.csv"""
import argparse
import json
from bisect import bisect_left
from pathlib import Path

import numpy as np
import pandas as pd

from .profit_exits import ROOT
from .replay import FEE, MIN, signals


def replay_trade(path, terminal_open, lot, trade_day, slip=1.0):
    """First bar contains the entry open. The terminal open is outside the stop window."""
    buy = path.open.iloc[0] + slip
    debit = buy * lot + FEE.charge(True, buy, lot, trade_day)

    def net(sale):
        return round(float(sale * lot - FEE.charge(False, sale, lot, trade_day) - debit), 2)

    # Find the lowest 5-paise stop that covers the net floor after modeled exit slip.
    def stop_price():
        lo = int(buy * 20)
        hi = lo + int((1000 / lot + slip + 1) * 20)
        while net(hi / 20 - slip) < 500:
            hi += 20
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if net(mid / 20 - slip) >= 500:
                hi = mid
            else:
                lo = mid
        return hi / 20

    stop = None
    timed = net(max(0.0, terminal_open - slip))
    for bar in path.itertuples():
        if stop is not None and bar.low <= stop:
            return net(max(0.0, min(stop, bar.open) - slip)), timed, "profit_lock", debit
        if stop is None and net(bar.high) >= 1000:
            stop = stop_price()
    return timed, timed, "time", debit


def run(lake):
    dates = json.loads((ROOT / "research/hunt/r50/results/expiry_days.json").read_text())["NIFTY"]
    dates += [p.name[:10] for p in (ROOT / "options_lab/data/expiry_cache").glob("2024-11*_chain.parquet")]
    expiry_days = sorted(set(dates + ["2026-10-19", "2026-10-27", "2026-11-03",
                                      "2026-11-09", "2026-11-17"]))
    rows = []
    sessions = set()
    for file in sorted(lake.glob("opt_NIFTY_WEEK_*.parquet")):
        spot_file = file.with_name(file.name.replace("opt_NIFTY_WEEK", "index_1m_NIFTY"))
        spot = pd.read_parquet(spot_file).set_index("ts").sort_index()
        book = pd.read_parquet(file, columns=["ts", "strike", "side", "open", "high", "low", "volume"])
        for day, quotes in book.groupby(book.ts.dt.date):
            date = day.isoformat()
            if date < "2024-11-01":
                continue
            if date in sessions:
                raise ValueError(f"Overlapping option sessions on {date}")
            sessions.add(date)
            index = bisect_left(expiry_days, date)
            if index == len(expiry_days) or (pd.Timestamp(expiry_days[index]).date() - day).days > 7:
                raise ValueError(f"NIFTY weekly expiry calendar needs verification for {date}")
            expiry = expiry_days[index]
            lot = 25 if expiry <= "2024-12-26" else 75 if expiry <= "2025-12-30" else 65
            for t, setup, direction, close, trend, _ in signals(spot.loc[date]):
                if setup != "sweep" or not trend:
                    continue
                start = t + MIN
                end = min(start + 60 * MIN, start.normalize() + pd.Timedelta(hours=15, minutes=20))
                strike = 50 * (int(np.floor(close / 50 + 0.5)) - 2 * direction)
                side = "C" if direction == 1 else "P"
                leg = quotes[(quotes.side == side) & (quotes.strike == strike)].set_index("ts").sort_index()
                leg = leg[~leg.index.duplicated(keep=False) & leg.volume.ge(lot) & leg.open.gt(0)]
                if start not in leg.index:
                    continue
                path = leg.loc[start:end - MIN]
                later = leg.loc[end:]
                if later.empty or path[["high", "low"]].isna().any().any():
                    raise ValueError(f"Incomplete option exit data for {date} {strike}{side}")
                trade_day = (day - pd.Timestamp("1970-01-01").date()).days
                net, timed, reason, debit = replay_trade(path, later.open.iloc[0], lot, trade_day)
                rows.append(dict(date=date, expiry=expiry, strike=strike, side=side, lot=lot,
                                 debit=debit, net=net, timed_net=timed, exit_reason=reason))
    if not rows:
        raise ValueError("No eligible NIFTY option entries")
    return pd.DataFrame(rows).sort_values("date")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("lake", type=Path)
    parser.add_argument("trades", type=Path)
    args = parser.parse_args()
    trades = run(args.lake)
    args.trades.parent.mkdir(parents=True, exist_ok=True)
    trades.to_csv(args.trades, index=False)
    print(trades[["net", "timed_net"]].agg(["count", "sum"]))
