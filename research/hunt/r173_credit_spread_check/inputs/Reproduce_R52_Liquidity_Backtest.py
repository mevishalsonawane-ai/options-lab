"""Standalone reproduction script for R52 Liquidity Break (LIQ-2) and Enhanced OTM Credit Spread.

Reproduces the exact 1 Nov 2024 - 19 Oct 2026 (485 trading sessions) backtest across
BANKNIFTY, NIFTY, FINNIFTY, MIDCPNIFTY, and SENSEX using the IraAlgo-data-lake index_1m Parquet files.
Source repo: https://github.com/mevishalsonawane-ai/options-lab
Data lake:   https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L

Usage:
    python3 reproduce_r52_liquidity_backtest.py --data-dir /path/to/index_1m
"""
from __future__ import annotations

import argparse
import math
import os
from dataclasses import dataclass
import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# 1. Core Constants & Per-Index Parameters (from research/hunt/h4/comps.py)
# ---------------------------------------------------------------------------
OPEN_M = 9 * 60 + 15          # 09:15 IST = minute 555
SESSION_MINUTES = 375         # 09:15..15:29 IST (indices 0..374)
ENTRY_START_M = 5             # 09:20 IST (5 mins after 09:15)
ENTRY_END_M = 315             # 14:30 IST (315 mins after 09:15)
SQUARE_OFF_M = 355            # 15:10 IST (355 mins after 09:15)

TFT_BAR_MINUTES = 15          # 15-minute chart candles
SWING_LENGTH = 20             # 20-bar pivot lookback on each side
POOL_CONTACTS = 2             # 2 wick rejections required
POOL_GAP = 5                  # >= 5 bars between contacts
POOL_CONFIRM = 10             # 10 bars held after 2nd contact without close-through
POOL_MAX_AGE = 300            # 300 bars max candidate lifetime

MIN_TARGET_FLOOR_MULT = 0.8   # Enhanced target floor: >= 0.8 * IDX_STOP_EXT ahead
DEFAULT_TARGET_MULT = 2.0     # Fallback target when no active zone lies ahead: 2.0 * IDX_STOP_EXT
CATASTROPHE_STOP_MULT = 4.5   # Enhanced catastrophe stop: 4.5 * IDX_STOP_EXT beyond broken level

LOT_SIZES = {
    "BANKNIFTY": 30,
    "NIFTY": 65,
    "FINNIFTY": 65,
    "MIDCPNIFTY": 120,
    "SENSEX": 20,
}

IDX_STOP_EXT = {
    "BANKNIFTY": 30.0,
    "FINNIFTY": 15.0,
    "NIFTY": 15.0,
    "SENSEX": 50.0,
    "MIDCPNIFTY": 8.0,
}


# ---------------------------------------------------------------------------
# 2. Level Detection (Exact port of indicator/liquidity.py)
# ---------------------------------------------------------------------------
@dataclass
class Zone:
    kind: str          # "swing" or "pool"
    side: int          # +1 high above price, -1 low below price
    top: float
    bottom: float
    origin: int
    known: int
    broken: int = -1
    contacts: int = 0

    @property
    def edge(self) -> float:
        return self.top if self.side > 0 else self.bottom


def _mark(zones: list[Zone], h: np.ndarray, l: np.ndarray, c: np.ndarray) -> None:
    for z in zones:
        seg_c = c[z.known:]
        hits = np.where(seg_c > z.top)[0] if z.side > 0 else np.where(seg_c < z.bottom)[0]
        z.broken = int(z.known + hits[0]) if len(hits) > 0 else -1


def swing_zones(df: pd.DataFrame, length: int = SWING_LENGTH, area: str = "full") -> list[Zone]:
    h, l, o, c = (df[k].values for k in ("high", "low", "open", "close"))
    n = len(c)
    zones: list[Zone] = []
    for i in range(2 * length, n):
        j = i - length
        win = slice(j - length, j + length + 1)
        if h[j] == h[win].max() and (h[win] == h[j]).sum() == 1:
            bot = l[j] if area == "full" else max(o[j], c[j])
            zones.append(Zone("swing", 1, float(h[j]), float(bot), j, i))
        if l[j] == l[win].min() and (l[win] == l[j]).sum() == 1:
            top = h[j] if area == "full" else min(o[j], c[j])
            zones.append(Zone("swing", -1, float(top), float(l[j]), j, i))
    _mark(zones, h, l, c)
    return zones


def pool_zones(
    df: pd.DataFrame,
    contacts: int = POOL_CONTACTS,
    gap: int = POOL_GAP,
    confirm: int = POOL_CONFIRM,
    max_age: int = POOL_MAX_AGE,
) -> list[Zone]:
    h, l, o, c = (df[k].values for k in ("high", "low", "open", "close"))
    n = len(c)
    out: list[Zone] = []
    cand: list[list] = []
    for i in range(n):
        keep = []
        for z in cand:
            side, top, bot, origin, cnt, last = z
            if i - origin > max_age:
                continue
            if (side > 0 and c[i] > top) or (side < 0 and c[i] < bot):
                continue
            touched = (h[i] >= bot and c[i] < top) if side > 0 else (l[i] <= top and c[i] > bot)
            if touched and i - last >= gap and cnt < contacts:
                z[4] += 1
                z[5] = i
            if z[4] >= contacts and i - z[5] >= confirm:
                if not any(q.side == side and q.broken < 0 and q.bottom <= top and bot <= q.top for q in out[-50:]):
                    out.append(Zone("pool", side, float(top), float(bot), origin, i, contacts=z[4]))
                continue
            keep.append(z)
        cand = keep
        body_hi, body_lo = max(o[i], c[i]), min(o[i], c[i])
        if h[i] > body_hi:
            cand.append([1, float(h[i]), float(body_hi), i, 1, i])
        if l[i] < body_lo:
            cand.append([-1, float(body_lo), float(l[i]), i, 1, i])
    _mark(out, h, l, c)
    return out


# ---------------------------------------------------------------------------
# 3. Data Loading, Session Folding & Cost Model (options_lab/costs.py)
# ---------------------------------------------------------------------------
def load_index(data_dir: str, symbol: str, start_date: str = "2024-09-01") -> pd.DataFrame:
    dfs = []
    for yr in (2024, 2025, 2026):
        p = os.path.join(data_dir, f"index_1m_{symbol}_{yr}.parquet")
        if os.path.exists(p):
            dfs.append(pd.read_parquet(p))
    if not dfs:
        raise FileNotFoundError(f"No parquet files found for {symbol} in {data_dir}")
    df = pd.concat(dfs, ignore_index=True)
    df["ts"] = pd.to_datetime(df["ts"])
    df = df.sort_values("ts").drop_duplicates("ts").reset_index(drop=True)
    df["day"] = df["ts"].dt.strftime("%Y-%m-%d")
    df["minute"] = df["ts"].dt.hour * 60 + df["ts"].dt.minute
    return df[df["day"] >= start_date].reset_index(drop=True)


def build_days(df: pd.DataFrame, vix_daily_open: dict[str, float]) -> list[dict]:
    days = []
    for d, grp in df.groupby("day", sort=True):
        grp = grp.sort_values("minute")
        idx_m = (grp["minute"].values - OPEN_M).astype(int)
        valid = (idx_m >= 0) & (idx_m < SESSION_MINUTES)
        grp = grp[valid]
        idx_m = idx_m[valid]
        if len(idx_m) < 60:
            continue
        op = np.full(SESSION_MINUTES, np.nan)
        hi = np.full(SESSION_MINUTES, np.nan)
        lo = np.full(SESSION_MINUTES, np.nan)
        cl = np.full(SESSION_MINUTES, np.nan)
        op[idx_m] = grp["open"].values
        hi[idx_m] = grp["high"].values
        lo[idx_m] = grp["low"].values
        cl[idx_m] = grp["close"].values
        s_cl = pd.Series(cl).ffill().bfill().values
        s_op = pd.Series(op).fillna(pd.Series(s_cl)).values
        s_hi = pd.Series(hi).fillna(pd.Series(s_cl)).values
        s_lo = pd.Series(lo).fillna(pd.Series(s_cl)).values
        days.append({
            "day": d,
            "I": {"open": s_op, "high": s_hi, "low": s_lo, "close": s_cl},
            "vix": float(vix_daily_open.get(d, 14.0)),
        })
    return days


def bars(days: list[dict], tf: int = TFT_BAR_MINUTES) -> pd.DataFrame:
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, SESSION_MINUTES, tf):
            e = min(s + tf, SESSION_MINUTES)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def option_params(spot0: float, vix: float, dte_days: float = 3.0) -> tuple[float, float, float, float]:
    sigma = max(vix / 100.0, 0.08)
    T = max(dte_days / 365.0, 1.0 / 365.0)
    atm_prem = max(0.3989 * spot0 * sigma * math.sqrt(T), 20.0)
    delta = 0.50
    gamma = 0.0004 if spot0 > 30000 else 0.0008
    theta_per_min = (atm_prem / (2.0 * max(dte_days, 1.0))) / float(SESSION_MINUTES)
    return atm_prem, delta, gamma, theta_per_min


def compute_net_cost(entry_px: float, exit_px: float, lot_size: int) -> float:
    """Exact options_lab/costs.py statutory charges + 'quoted' spread law."""
    sp_in = max(0.05, 3.0 * (0.162 + 0.00292 * max(entry_px, 0.0)))
    sp_out = max(0.05, 3.0 * (0.162 + 0.00292 * max(exit_px, 0.0)))
    half_sp_rs = 0.5 * (sp_in + sp_out) * lot_size
    turnover_buy = entry_px * lot_size
    turnover_sell = exit_px * lot_size
    brok = 40.0
    stt = 0.00125 * turnover_sell
    exch = 0.0003553 * (turnover_buy + turnover_sell)
    sebi = 0.000001 * (turnover_buy + turnover_sell)
    stamp = 0.00003 * turnover_buy
    gst = 0.18 * (brok + exch + sebi)
    return brok + stt + exch + sebi + stamp + gst + half_sp_rs


# ---------------------------------------------------------------------------
# 4. Backtest Simulation Engine
# ---------------------------------------------------------------------------
def run_variant(
    days: list[dict],
    b15: pd.DataFrame,
    z15: list[Zone],
    sym: str,
    mode: str,
    start_day: str = "2024-11-01",
    end_day: str = "2026-10-19",
) -> pd.DataFrame:
    istop = IDX_STOP_EXT[sym]
    lot = LOT_SIZES[sym]
    DI, S, E, C = b15.di.values, b15.s.values, b15.e.values, b15.close.values
    breaks, known = {}, {}
    for z in z15:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)

    trades = []
    pos = None
    for i in range(len(b15)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None:
            sg = pos["sign"]
            why, xm = None, None
            target = pos["target"]
            m_start = max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i]
            for m in range(m_start, E[i]):
                if m >= SQUARE_OFF_M:
                    why, xm = "15:10", m
                    break
                if mode == "Enhanced_OTM_CreditSpread":
                    if sg * ((I["low"][m] if sg > 0 else I["high"][m]) - pos["level"]) < -CATASTROPHE_STOP_MULT * istop:
                        why, xm = "catastrophe_stop", m
                        break
                if target is not None and sg * ((I["high"][m] if sg > 0 else I["low"][m]) - target) >= 0:
                    why, xm = "target", m
                    break
                nk = [q for q in known.get(i, []) if q.side == sg]
                if nk:
                    why, xm = "new_level", m
                    break
            if why is None and mode == "LIQ2_BarCloseStop_LongATM":
                if sg * (C[i] - pos["level"]) < 0:
                    why, xm = "failed_break_close", E[i] - 1

            if why is not None:
                held = (DI[i] - pos["di"]) * SESSION_MINUTES + xm - pos["m"]
                if why == "target" and target is not None:
                    exit_ix = target
                elif why == "catastrophe_stop":
                    exit_ix = pos["level"] - sg * CATASTROPHE_STOP_MULT * istop
                else:
                    exit_ix = I["close"][xm]
                pts = (exit_ix - pos["ix"]) * sg
                prem0, delta, gamma, theta_m = option_params(I["open"][pos["m"]], d["vix"])

                if mode in ("Baseline_LIQ2_LongATM", "LIQ2_BarCloseStop_LongATM"):
                    d_opt = delta * pts + 0.5 * gamma * (pts ** 2) * (1.0 if pts >= 0 else -0.5) - theta_m * max(held, 1)
                    exit_px = max(0.05, prem0 + d_opt)
                    cost_rs = compute_net_cost(prem0, exit_px, lot)
                    net_rs = (exit_px - prem0) * lot - cost_rs
                else:
                    short_prem = prem0 * 0.55
                    theta_gain = (short_prem * 0.22) * (max(held, 1) / float(SESSION_MINUTES))
                    delta_gain = 0.28 * pts - (0.00020 * (pts ** 2) if pts < 0 else 0.0)
                    gross_rs = (delta_gain + theta_gain) * lot
                    cost_rs = compute_net_cost(short_prem, max(0.05, short_prem - delta_gain - theta_gain), lot)
                    net_rs = gross_rs - cost_rs

                if start_day <= pos["day"] <= end_day:
                    trades.append(dict(
                        day=pos["day"], und=sym, mode=mode, sign=sg,
                        entry_m=pos["m"], exit_m=xm, held=held,
                        entry_ix=pos["ix"], exit_ix=exit_ix, level=pos["level"],
                        target=target, pts=pts, rs=net_rs, why=why,
                    ))
                pos = None

        if pos is None and ENTRY_START_M <= S[i] <= ENTRY_END_M:
            if d["day"] < start_day or d["day"] > end_day:
                continue
            cand = [
                z for z in breaks.get(i, [])
                if z.kind == "pool" and any(
                    q.kind == "swing" and q.side == z.side and q.known <= i and (q.broken < 0 or q.broken >= i)
                    and q.bottom <= z.top and z.bottom <= q.top
                    for q in z15
                )
            ]
            if cand:
                z = cand[0]
                sg = z.side
                m = min(E[i], SESSION_MINUTES - 1)
                ix = I["open"][m]
                if mode == "Enhanced_OTM_CreditSpread":
                    ahead = [
                        q.edge for q in z15
                        if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                        and sg * (q.edge - ix) >= MIN_TARGET_FLOOR_MULT * istop
                    ]
                    target = (min(ahead) if sg > 0 else max(ahead)) if ahead else (ix + sg * DEFAULT_TARGET_MULT * istop)
                else:
                    ahead = [
                        q.edge for q in z15
                        if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                        and sg * (q.edge - ix) > 0
                    ]
                    target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
                pos = dict(day=d["day"], di=DI[i], sign=sg, m=m, ix=ix, level=z.edge, target=target)

    return pd.DataFrame(trades)


def main():
    parser = argparse.ArgumentParser(description="Reproduce R52 Liquidity Break Backtest (Nov 2024 - Oct 2026)")
    parser.add_argument("--data-dir", default="/working_dir/c_7de8a1dfc94a9450/downloads", help="Directory containing index_1m_*.parquet files")
    args = parser.parse_args()

    vix_df = load_index(args.data_dir, "INDIAVIX", "2024-09-01")
    vix_daily_open = vix_df.groupby("day")["open"].first().to_dict()

    for sym in ["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]:
        df = load_index(args.data_dir, sym, "2024-09-01")
        days = build_days(df, vix_daily_open)
        b15 = bars(days, TFT_BAR_MINUTES)
        z15 = swing_zones(b15, SWING_LENGTH, "full") + pool_zones(b15, POOL_CONTACTS, POOL_GAP, POOL_CONFIRM, POOL_MAX_AGE)

        for mode in ["Baseline_LIQ2_LongATM", "LIQ2_BarCloseStop_LongATM", "Enhanced_OTM_CreditSpread"]:
            tdf = run_variant(days, b15, z15, sym, mode)
            wr_pts = 100.0 * (tdf["pts"] > 0).mean()
            wr_rs = 100.0 * (tdf["rs"] > 0).mean()
            net = tdf["rs"].sum()
            des_net = tdf.loc[tdf["day"] <= "2026-03-31", "rs"].sum()
            hold_net = tdf.loc[tdf["day"] >= "2026-04-01", "rs"].sum()
            pf = tdf.loc[tdf["rs"] > 0, "rs"].sum() / abs(tdf.loc[tdf["rs"] < 0, "rs"].sum())
            dd = (tdf["rs"].cumsum() - tdf["rs"].cumsum().cummax()).min()
            print(
                f"{sym:10s} | {mode:26s} | n={len(tdf):3d} | Win(Idx)={wr_pts:5.1f}% | "
                f"Win(NetRs)={wr_rs:5.1f}% | Net=Rs {net:+9,.0f} (Des {des_net:+8,.0f} / Hold {hold_net:+8,.0f}) | "
                f"PF={pf:4.2f} | MaxDD=Rs {dd:+8,.0f}"
            )


if __name__ == "__main__":
    main()