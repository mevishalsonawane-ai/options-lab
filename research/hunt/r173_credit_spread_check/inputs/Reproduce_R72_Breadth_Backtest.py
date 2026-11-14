"""Standalone reproduction script for R72 Cross-Index Breadth Donchian (C1) and Enhanced OTM Credit Spread.

Reproduces the exact 1 Nov 2024 - 19 Oct 2026 (485 trading sessions) backtest across
NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, and SENSEX using the IraAlgo-data-lake index_1m Parquet files.
Source repo: https://github.com/mevishalsonawane-ai/options-lab
Data lake:   https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L

Usage:
    python3 reproduce_r72_breadth_backtest.py --data-dir /path/to/index_1m
"""
from __future__ import annotations

import argparse
import json
import math
import os
import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# 1. Core Constants & Per-Index Parameters (from research/hunt/r72/r72rules.py)
# ---------------------------------------------------------------------------
OPEN_M = 9 * 60 + 15          # 09:15 IST = minute 555
SESSION_MINUTES = 375         # 09:15..15:29 IST (indices 0..374)
N_CH = 20                     # 20-bar 5-minute Donchian channel lookback
WARM = 60                     # 60 five-minute bars warm-up minimum
M_BREADTH = 4                 # At least 4 of 5 indices breaking simultaneously

ENH_TARGET_MULT = 3.2         # Enhanced target: +3.2 * IDX_STOP_EXT
ENH_CATASTROPHE_MULT = 4.5    # Enhanced catastrophe stop: -4.5 * IDX_STOP_EXT

UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
LOT_SIZES = {"BANKNIFTY": 30, "NIFTY": 65, "FINNIFTY": 65, "MIDCPNIFTY": 120, "SENSEX": 20}
IDX_STOP_EXT = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}


# ---------------------------------------------------------------------------
# 2. Data Loading & 5-Index Panel Construction
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


def build_days_5m(
    df: pd.DataFrame,
    und: str,
    vix_daily_open: dict[str, float],
    expiries: set[tuple[str, str]],
) -> tuple[dict[str, dict], pd.DataFrame]:
    days = {}
    rows_5m = []
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
        wd = pd.Timestamp(d).weekday()
        is_exp = (
            (und == "MIDCPNIFTY" and wd == 0)
            or (und == "FINNIFTY" and wd == 1)
            or (und == "BANKNIFTY" and wd == 2)
            or (und == "NIFTY" and wd == 3)
            or (und == "SENSEX" and wd == 4)
            or ((und, d) in expiries)
        )
        days[d] = {
            "day": d,
            "I": {"open": s_op, "high": s_hi, "low": s_lo, "close": s_cl},
            "vix": float(vix_daily_open.get(d, 14.0)),
            "is_exp": is_exp,
        }
        for b_idx, s in enumerate(range(0, SESSION_MINUTES, 5)):
            e = min(s + 5, SESSION_MINUTES)
            rows_5m.append((d, b_idx, s_op[s], s_hi[s:e].max(), s_lo[s:e].min(), s_cl[e - 1]))
    df5 = pd.DataFrame(rows_5m, columns=["d", "b", "o", "h", "l", "c"])
    return days, df5


def build_panel_signals(panel_data: dict[str, tuple], M: int = M_BREADTH) -> dict[str, list]:
    keys = sorted({(d, int(b)) for (dd, bb, *_) in panel_data.values() for d, b in zip(dd, bb)})
    R = len(keys)
    pos_map = {k: r for r, k in enumerate(keys)}
    j_map = {}
    f_map = {}

    for u, (d, b, o, h, l, c) in panel_data.items():
        jj = np.full(R, -1, np.int64)
        for i in range(len(d)):
            jj[pos_map[(d[i], int(b[i]))]] = i
        j_map[u] = jj
        T = len(c)
        chi, clo = np.full(T, np.nan), np.full(T, np.nan)
        for i in range(N_CH, T):
            chi[i] = h[i - N_CH : i].max()
            clo[i] = l[i - N_CH : i].min()
        mid = 0.5 * (chi + clo)
        tr = np.maximum(h - l, np.maximum(np.abs(h - np.roll(c, 1)), np.abs(l - np.roll(c, 1))))
        tr[0] = h[0] - l[0]
        atr14 = pd.Series(tr).ewm(span=14, adjust=False).mean().values
        f_map[u] = {
            "d": d, "b": b, "o": o, "h": h, "l": l, "c": c,
            "chi": chi, "clo": clo, "mid": mid,
            "up": (c > chi) & (np.arange(T) >= N_CH),
            "dn": (c < clo) & (np.arange(T) >= N_CH),
            "atr": atr14,
        }

    sigs = {u: [] for u in UNDS}
    for r in range(R):
        for s, name in ((1, "up"), (-1, "dn")):
            hit = [u for u in UNDS if j_map[u][r] >= WARM and bool(f_map[u][name][j_map[u][r]])]
            if len(hit) >= M:
                for u in hit:
                    j = j_map[u][r]
                    sigs[u].append((
                        f_map[u]["d"][j],
                        int(f_map[u]["b"][j]),
                        s,
                        float(f_map[u]["mid"][j]),
                        float(f_map[u]["atr"][j]),
                    ))
    return sigs


# ---------------------------------------------------------------------------
# 3. Option Pricing & Cost Model (options_lab/costs.py)
# ---------------------------------------------------------------------------
def option_path_1m(I: dict, entry_m: int, sg: int, vix: float, dte_days: float = 3.0, moneyness: str = "ITM1"):
    spot0 = I["open"][entry_m]
    sigma = max(vix / 100.0, 0.08)
    T = max(dte_days / 365.0, 1.0 / 365.0)
    atm_prem = max(0.3989 * spot0 * sigma * math.sqrt(T), 20.0)
    if moneyness == "ITM1":
        prem0 = max(atm_prem * 1.35, 30.0)
        delta = 0.62
        gamma = 0.00035 if spot0 > 30000 else 0.00070
        theta_m = (prem0 / (2.5 * max(dte_days, 1.0))) / float(SESSION_MINUTES)
    else:
        prem0 = atm_prem * 0.55
        delta = 0.28
        gamma = 0.00020
        theta_m = (prem0 * 0.22) / float(SESSION_MINUTES)
    return prem0, delta, gamma, theta_m


def compute_net_cost(entry_px: float, exit_px: float, lot_size: int) -> float:
    sp_in = max(0.05, 3.0 * (0.162 + 0.00292 * max(entry_px, 0.0)))
    sp_out = max(0.05, 3.0 * (0.162 + 0.00292 * max(exit_px, 0.0)))
    half_sp_rs = 0.5 * (sp_in + sp_out) * lot_size
    tb = entry_px * lot_size
    ts = exit_px * lot_size
    brok = 40.0
    stt = 0.00125 * ts
    exch = 0.0003553 * (tb + ts)
    sebi = 0.000001 * (tb + ts)
    stamp = 0.00003 * tb
    gst = 0.18 * (brok + exch + sebi)
    return brok + stt + exch + sebi + stamp + gst + half_sp_rs


# ---------------------------------------------------------------------------
# 4. Simulation Engine across R72 Variants
# ---------------------------------------------------------------------------
def simulate_r72(
    days_by_und: dict[str, dict],
    sigs_by_und: dict[str, list],
    mode: str,
    start_day: str = "2024-11-01",
    end_day: str = "2026-10-19",
) -> pd.DataFrame:
    all_trades = []
    for u in UNDS:
        lot = LOT_SIZES[u]
        istop = IDX_STOP_EXT[u]
        d_map = days_by_und[u]
        by_day = {}
        for d, b_idx, sg, mid_val, atr_val in sigs_by_und[u]:
            if start_day <= d <= end_day:
                by_day.setdefault(d, []).append((b_idx, sg, mid_val, atr_val))

        for d, day_sigs in sorted(by_day.items()):
            day_obj = d_map.get(d)
            if day_obj is None or day_obj["is_exp"]:
                continue
            I = day_obj["I"]
            vix = day_obj["vix"]
            open_until = {1: -1, -1: -1}
            entries_taken = {1: 0, -1: 0}
            prev_b = {1: -99, -1: -99}
            max_per_side = 2 if mode == "C1_Orig_XL" else 1
            last_entry_m = 355 if mode != "C1_Enh_FreshBreadth_CreditSpread" else 315

            for b_idx, sg, mid_val, atr_val in day_sigs:
                is_consec = (b_idx == prev_b[sg] + 1)
                prev_b[sg] = b_idx
                if mode == "C1_Enh_FreshBreadth_CreditSpread" and is_consec:
                    continue
                entry_m = (b_idx + 1) * 5
                if entry_m < 5 or entry_m > last_entry_m:
                    continue
                if entry_m <= open_until[sg] or entries_taken[sg] >= max_per_side:
                    continue

                ix0 = I["open"][entry_m]
                if mode == "C1_Enh_ITM1_FastLock" and sg * (ix0 - I["open"][0]) < 0:
                    continue
                if mode == "C1_Enh_FreshBreadth_CreditSpread":
                    twap_sofar = I["close"][: entry_m + 1].mean()
                    if sg * (ix0 - twap_sofar) < 0:
                        continue

                prem0, delta, gamma, theta_m = option_path_1m(
                    I, entry_m, sg, vix,
                    moneyness="ITM1" if mode != "C1_Enh_FreshBreadth_CreditSpread" else "OTM_SPREAD",
                )
                cost_est_pts = compute_net_cost(prem0, prem0 * 1.05, lot) / lot

                if mode == "C1_Orig_XL":
                    stop_px = prem0 * 0.85
                    max_m = min(entry_m + 30, 360)
                    xm = max_m
                    why = "30m_or_1515"
                    exit_px = None
                    for m in range(entry_m, max_m + 1):
                        fav_pts = (I["high"][m] - ix0) if sg > 0 else (ix0 - I["low"][m])
                        adv_pts = (I["low"][m] - ix0) if sg > 0 else (ix0 - I["high"][m])
                        held_m = m - entry_m + 1
                        opt_hi = max(0.05, prem0 + delta * fav_pts + 0.5 * gamma * (fav_pts ** 2) * (1 if fav_pts >= 0 else -0.5) - theta_m * held_m)
                        opt_lo = max(0.05, prem0 + delta * adv_pts + 0.5 * gamma * (adv_pts ** 2) * (1 if adv_pts >= 0 else -0.5) - theta_m * held_m)
                        if opt_lo <= stop_px:
                            xm = m
                            exit_px = stop_px
                            why = "stop_or_lock"
                            break
                        if opt_hi >= prem0 * 2.00:
                            stop_px = max(stop_px, prem0 * 1.60)
                        elif opt_hi >= prem0 * 1.60:
                            stop_px = max(stop_px, prem0 * 1.30)
                        elif opt_hi >= prem0 * 1.30:
                            stop_px = max(stop_px, prem0 + cost_est_pts)
                        elif opt_hi >= prem0 * 1.25:
                            stop_px = max(stop_px, prem0 * 1.05)
                    if exit_px is None:
                        cl_pts = (I["close"][xm] - ix0) * sg
                        held_m = max(xm - entry_m, 1)
                        exit_px = max(0.05, prem0 + delta * cl_pts + 0.5 * gamma * (cl_pts ** 2) * (1 if cl_pts >= 0 else -0.5) - theta_m * held_m)
                    cost_rs = compute_net_cost(prem0, exit_px, lot)
                    net_rs = (exit_px - prem0) * lot - cost_rs
                    pts = (I["close"][xm] - ix0) * sg

                elif mode == "C1_Enh_ITM1_FastLock":
                    stop_px = prem0 * 0.78
                    target_px = prem0 * 1.28
                    max_m = min(entry_m + 30, 360)
                    xm = max_m
                    why = "30m_or_1515"
                    exit_px = None
                    for m in range(entry_m, max_m + 1):
                        fav_pts = (I["high"][m] - ix0) if sg > 0 else (ix0 - I["low"][m])
                        adv_pts = (I["low"][m] - ix0) if sg > 0 else (ix0 - I["high"][m])
                        held_m = m - entry_m + 1
                        opt_hi = max(0.05, prem0 + delta * fav_pts + 0.5 * gamma * (fav_pts ** 2) * (1 if fav_pts >= 0 else -0.5) - theta_m * held_m)
                        opt_lo = max(0.05, prem0 + delta * adv_pts + 0.5 * gamma * (adv_pts ** 2) * (1 if adv_pts >= 0 else -0.5) - theta_m * held_m)
                        if opt_lo <= stop_px:
                            xm = m
                            exit_px = stop_px
                            why = "stop_or_lock"
                            break
                        if opt_hi >= target_px:
                            xm = m
                            exit_px = target_px
                            why = "target_28pct"
                            break
                        if opt_hi >= prem0 * 1.18:
                            stop_px = max(stop_px, prem0 * 1.10)
                        elif opt_hi >= prem0 * 1.09:
                            stop_px = max(stop_px, prem0 + cost_est_pts + 1.5)
                    if exit_px is None:
                        cl_pts = (I["close"][xm] - ix0) * sg
                        held_m = max(xm - entry_m, 1)
                        exit_px = max(0.05, prem0 + delta * cl_pts + 0.5 * gamma * (cl_pts ** 2) * (1 if cl_pts >= 0 else -0.5) - theta_m * held_m)
                    cost_rs = compute_net_cost(prem0, exit_px, lot)
                    net_rs = (exit_px - prem0) * lot - cost_rs
                    pts = (I["close"][xm] - ix0) * sg

                elif mode == "C1_Enh_FreshBreadth_CreditSpread":
                    max_m = 355
                    xm = max_m
                    why = "15:10"
                    exit_ix = None
                    target_ix = ix0 + sg * (ENH_TARGET_MULT * istop)
                    stop_ix = ix0 - sg * (ENH_CATASTROPHE_MULT * istop)
                    for m in range(entry_m, max_m + 1):
                        if sg * ((I["low"][m] if sg > 0 else I["high"][m]) - stop_ix) < 0:
                            xm = m
                            exit_ix = stop_ix
                            why = "catastrophe_stop"
                            break
                        if sg * ((I["high"][m] if sg > 0 else I["low"][m]) - target_ix) >= 0:
                            xm = m
                            exit_ix = target_ix
                            why = "target_hit"
                            break
                    if exit_ix is None:
                        exit_ix = I["close"][xm]
                    pts = (exit_ix - ix0) * sg
                    held_m = max(xm - entry_m, 1)
                    theta_gain = theta_m * held_m * 1.35
                    delta_gain = delta * pts - (gamma * (pts ** 2) if pts < 0 else 0.0)
                    gross_rs = (delta_gain + theta_gain) * lot
                    cost_rs = compute_net_cost(prem0, max(0.05, prem0 - delta_gain - theta_gain), lot)
                    net_rs = gross_rs - cost_rs

                open_until[sg] = xm
                entries_taken[sg] += 1
                all_trades.append(dict(
                    day=d, und=u, mode=mode, sign=sg, entry_m=entry_m, exit_m=xm,
                    held=xm - entry_m, pts=pts, rs=net_rs, why=why,
                ))
    return pd.DataFrame(all_trades)


def main():
    parser = argparse.ArgumentParser(description="Reproduce R72 Cross-Index Breadth Donchian Backtest (Nov 2024 - Oct 2026)")
    parser.add_argument("--data-dir", default="/working_dir/c_7de8a1dfc94a9450/downloads", help="Directory containing index_1m_*.parquet and fut_expiries.json")
    args = parser.parse_args()

    exp_path = os.path.join(args.data_dir, "fut_expiries.json")
    expiries = {(u, d) for u, d in json.load(open(exp_path))} if os.path.exists(exp_path) else set()

    vix_df = load_index(args.data_dir, "INDIAVIX", "2024-09-01")
    vix_daily_open = vix_df.groupby("day")["open"].first().to_dict()

    panel_data = {}
    days_by_und = {}
    for u in UNDS:
        df_u = load_index(args.data_dir, u, "2024-09-01")
        d_map, df5 = build_days_5m(df_u, u, vix_daily_open, expiries)
        days_by_und[u] = d_map
        panel_data[u] = (
            df5["d"].values, df5["b"].values, df5["o"].values,
            df5["h"].values, df5["l"].values, df5["c"].values,
        )

    sigs_by_und = build_panel_signals(panel_data, M=M_BREADTH)

    for mode in ["C1_Orig_XL", "C1_Enh_ITM1_FastLock", "C1_Enh_FreshBreadth_CreditSpread"]:
        tdf = simulate_r72(days_by_und, sigs_by_und, mode)
        wr_pts = 100.0 * (tdf["pts"] > 0).mean()
        wr_rs = 100.0 * (tdf["rs"] > 0).mean()
        net = tdf["rs"].sum()
        des = tdf.loc[tdf["day"] <= "2026-03-31", "rs"].sum()
        hold = tdf.loc[tdf["day"] >= "2026-04-01", "rs"].sum()
        pf = tdf.loc[tdf["rs"] > 0, "rs"].sum() / abs(tdf.loc[tdf["rs"] < 0, "rs"].sum())
        daily_pnl = tdf.groupby("day")["rs"].sum()
        dd = (daily_pnl.cumsum() - daily_pnl.cumsum().cummax()).min()
        print(f"=== {mode} (Pooled 5 Indices) | n={len(tdf):4d} | Win(Idx)={wr_pts:5.1f}% | Win(NetRs)={wr_rs:5.1f}% | Net=Rs {net:+10,.0f} (Des {des:+9,.0f} / Hold {hold:+9,.0f}) | PF={pf:4.2f} | MaxDD=Rs {dd:+9,.0f} ===")
        for u in UNDS:
            sub = tdf[tdf["und"] == u]
            u_wr_pts = 100.0 * (sub["pts"] > 0).mean()
            u_wr_rs = 100.0 * (sub["rs"] > 0).mean()
            u_net = sub["rs"].sum()
            u_des = sub.loc[sub["day"] <= "2026-03-31", "rs"].sum()
            u_hold = sub.loc[sub["day"] >= "2026-04-01", "rs"].sum()
            u_pf = sub.loc[sub["rs"] > 0, "rs"].sum() / abs(sub.loc[sub["rs"] < 0, "rs"].sum())
            u_dd = (sub["rs"].cumsum() - sub["rs"].cumsum().cummax()).min()
            print(f"  {u:10s} | n={len(sub):3d} | Win(Idx)={u_wr_pts:5.1f}% | Win(NetRs)={u_wr_rs:5.1f}% | Net=Rs {u_net:+9,.0f} (Des {u_des:+8,.0f} / Hold {u_hold:+8,.0f}) | PF={u_pf:4.2f} | MaxDD=Rs {u_dd:+8,.0f}")


if __name__ == "__main__":
    main()