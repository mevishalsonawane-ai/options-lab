"""R113 adapter (26 Oct 2026): the MCX paper arm "US-night silver" (app McxUsSilverRules = R2's frozen candidate C2
"follow US overnight", SILVER, 09:05 -> close, SILVERMIC futures; research/HUNT_R2_US_MCX.md, research/hunt/r2/
hourly.py (us_on, inr_on) + cand.py (C2, exit XEOD, inst FUT) + lib.py fut_pnl).

R2's own outputs (cand_trades_*.parquet) were in a scratchpad and are gone, and hourly.py / cand.py are scripts that run
every commodity (crude needs a cache that no longer exists), so this adapter re-runs only the SILVER C2 FUT XEOD path,
copying cand.py's and hourly.py's logic line for line and importing lib.py (fut_pnl) and m3lib.py (Market) unchanged:
  - signal: s = us_on + inr_on (inr_on 0 if missing); us_on = ln(SI=F at 03:00 UTC on D / SI=F at 18:00 UTC on the
    previous MCX day), price = open of the 1h bar starting then, else the last bar's close <= 2h old (hourly.px_at);
    inr_on likewise on INR=X. Side = sign(s).
  - entry: the first SILVERM future minute >= 09:05 (skipped if after 09:15 or the contract segment changed since the
    previous close); exit: the day's cut (last minute <= session end - 10 min); 1 SILVERMIC (1 kg); lib.fut_pnl costs.
Data: SILVERM minutes = the lake's Dhan rolling-option `spot` (M3 layout via _mcx_raw.py); MCX day list = the lake's
fut_daily_continuous SILVERM (R2 used m1's Dhan daily file); US hourly bars = Yahoo chart API 1h SI=F and INR=X fetched
26 Oct 2026 exactly as research/hunt/r2/fetch_yahoo.sh does (range=730d), NOT in the lake.
usage: python3 -I research/hunt/r113_time_of_day/adapters/mcx_us_night_silver.py <dir with h_SI%3DF.json, h_INR%3DX.json>"""
import json, os, sys
from pathlib import Path
import numpy as np, pandas as pd

REPO = Path("/home/user/options-lab")
sys.path.insert(0, str(REPO / "research/hunt/m3")); sys.path.insert(0, str(REPO / "research/hunt/r2"))
sys.path.insert(0, str(Path(__file__).parent))
import _mcx_raw
import m3lib as M
import lib as L

OUT = Path(os.path.expanduser("~/.cache/iraalgo/r113/trades"))
LAKE = _mcx_raw.LAKE


def yjson(p):  # parse.py yjson, verbatim (UTC, tz-naive)
    j = json.loads(Path(p).read_text())["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    df = pd.DataFrame({k: q.get(k) for k in ("open", "high", "low", "close", "volume")})
    df["ts"] = pd.to_datetime(j["timestamp"], unit="s")
    df = df.dropna(subset=["close"]).set_index("ts").sort_index()
    return df[~df.index.duplicated(keep="last")]


def px_at(y, t, col="open"):  # hourly.py px_at, verbatim
    i = y.index.searchsorted(t)
    if i < len(y) and y.index[i] == t:
        return y[col].iloc[i]
    if i > 0 and t - y.index[i - 1] <= pd.Timedelta(hours=2):
        return y["close"].iloc[i - 1]
    return np.nan


def hourly(mode, si, inr, daily_days):
    """hourly.py's us_on / inr_on for SILVER over the mode's window (its LO/HI; first day has no previous day)."""
    LO, HI = (pd.Timestamp("2023-10-02"), L.HOLD_START - pd.Timedelta(days=1)) if mode == "design" else (L.HOLD_START, L.HOLD_END)
    d = [D for D in daily_days if max(LO, si.index.min().normalize() + pd.Timedelta(days=2)) <= D <= HI]
    out, prev = {}, None
    for D in d:
        if prev is None:
            prev = D; continue
        t_c_prev, t_pre = prev + pd.Timedelta(hours=18), D + pd.Timedelta(hours=3)
        a, b = px_at(si, t_c_prev), px_at(si, t_pre)
        i0, i1 = px_at(inr, t_c_prev), px_at(inr, t_pre)
        out[D] = (np.log(b / a), np.log(i1 / i0) if i0 > 0 else np.nan)
        prev = D
    return out


def main(ydir):
    si, inr = yjson(Path(ydir) / "h_SI%3DF.json"), yjson(Path(ydir) / "h_INR%3DX.json")
    dd = pd.read_parquet(LAKE / "fut_daily_continuous.parquet")
    dd = dd[(dd.symbol == "SILVERM") & (dd.expiry_code == 0)]
    dd = dd[(dd.close > 0) & (dd.high >= dd.low) & (dd.open > 0)]
    daily_days = sorted(pd.to_datetime(dd.date).dt.normalize().unique())
    M.RAW = _mcx_raw.build("SILVERM")
    m = M.Market("SILVERM")
    ts = pd.DatetimeIndex(m.ts)
    rows = []
    for mode, per in (("design", "design"), ("holdout", "final")):
        hr = hourly(mode, si, inr, daily_days)
        LO, HI = (pd.Timestamp("2025-08-01"), L.HOLD_START) if mode == "design" else (L.HOLD_START, L.HOLD_END + pd.Timedelta(days=1))
        for D in [pd.Timestamp(x) for x in m.days if LO <= pd.Timestamp(x) < HI]:  # cand.py loop, C2 FUT XEOD only
            d64 = D.to_datetime64()
            ix = np.where(m.day == d64)[0]
            if len(ix) < 200: continue
            prev_ix = np.where(m.day < d64)[0]
            if not len(prev_ix): continue
            pc = prev_ix[-1]
            e = ix[np.searchsorted(m.tod[ix], 9 * 60 + 5)] if (m.tod[ix] >= 9 * 60 + 5).any() else None
            if e is None or m.tod[e] > 9 * 60 + 15 or m.seg[e] != m.seg[pc]: continue
            p_in = m.F[e]
            fair = np.nan
            if D in hr and np.isfinite(hr[D][0]):
                fair = hr[D][0] + (hr[D][1] if np.isfinite(hr[D][1]) else 0.0)
            sd = np.sign(fair) if np.isfinite(fair) else 0.0
            if sd == 0: continue
            xe = m.day_cut[d64]
            g, n = L.fut_pnl("SILVER", int(sd), p_in, m.F[xe])
            rows.append(dict(arm="MCX_US_NIGHT_SILVER", und="SILVERM", day=D.strftime("%Y-%m-%d"),
                             entry_ts=ts[e].strftime("%Y-%m-%d %H:%M"), exit_ts=ts[xe].strftime("%Y-%m-%d %H:%M"),
                             side="L" if sd > 0 else "S", net_rs=round(float(n), 2), period=per, expiry="", gross=g))
    T = pd.DataFrame(rows)
    T.drop(columns=["gross"]).to_csv(OUT / "MCX_US_NIGHT_SILVER.csv", index=False)
    summ = {p: dict(n=int((T.period == p).sum()), net=round(float(T[T.period == p].net_rs.sum()), 1),
                    net_per_trade=round(float(T[T.period == p].net_rs.mean()), 1),
                    win=round(float((T[T.period == p].net_rs > 0).mean()), 3)) for p in ("design", "final")}
    fin = T[T.period == "final"]
    summ["final"]["long_per_trade"] = round(float(fin[fin.side == "L"].net_rs.mean()), 1)
    summ["final"]["short_per_trade"] = round(float(fin[fin.side == "S"].net_rs.mean()), 1)
    pub = dict(final=dict(n=166, win=0.56, net_per_trade=1189, net_approx=166 * 1189, long_per_trade=1690, short_per_trade=776,
                          src="HUNT_R2_US_MCX.md table 'C2 follow US overnight, SILVER, 09:05 -> close', SILVERMIC futures"),
               design=dict(n="31 days ('09:05 minute check -Rs 246/tr (31 d)'); the 36-day all-commodity check -246..+9",
                           net_per_trade=-246))
    gapn = summ["final"]["n"] - 166; gapnet = summ["final"]["net"] - 166 * 1189
    meta = dict(
        arm="MCX_US_NIGHT_SILVER", app_rule="McxUsSilverRules (mcx_us_silver); the app adds a 3% disaster stop that is NOT "
        "in the research and NOT in this list",
        source_code="research/hunt/r2/cand.py (C2_follow_us, XEOD, FUT, SILVER) + hourly.py (us_on, inr_on, px_at) + lib.py "
                    "fut_pnl + m3/m3lib.py Market; logic copied verbatim for the one cell (scripts run all commodities)",
        adapter="research/hunt/r113_time_of_day/adapters/mcx_us_night_silver.py (+ _mcx_raw.py)",
        command="python3 -I research/hunt/r113_time_of_day/adapters/mcx_us_night_silver.py <yahoo_dir>",
        signal_source="US overnight move of COMEX silver (SI=F) + USD/INR, read before the MCX open; traded on the SILVERM "
                      "future itself (priced as 1 SILVERMIC); the underlying is the future, no option",
        periods=dict(design="1 Aug - 30 Sep 2025 (R2's only pre-holdout MCX minutes; option data from 5 Aug)",
                     final="1 Oct 2025 - 6 Oct 2026 (R2 locked holdout, opened once)"),
        size="1 SILVERMIC lot (1 kg), SILVERM price", cost_model="lib.fut_pnl: Zerodha futures charges (brokerage min(20, "
        "0.03%), CTT 0.01% sell, MCX 0.0021%, SEBI, stamp 0.002% buy, GST) + 0.03% full spread",
        n=summ, published=pub, gap_final=dict(n=gapn, net=round(gapnet, 1), net_pct=round(100 * gapnet / (166 * 1189), 1)),
        parity="yes" if gapn == 0 and abs(gapnet) <= 0.01 * 166 * 1189 else "no (see gap_final)",
        caveats=["US hourly bars were re-fetched from Yahoo on 26 Oct 2026 (not in the lake); Yahoo's SI=F continuation "
                 "and bar set could differ from R2's 9 Oct copy; on this run the totals still reproduce R2 (see parity)",
                 "MCX day list from the lake's fut_daily_continuous (R2 used m1's Dhan daily file); SILVERM minutes from the "
                 "lake's Dhan rolling-option spot",
                 "entry_ts = the 09:05 minute (price = that minute's Dhan spot), exit_ts = the day's cut minute (session end "
                 "- 10 min, so 23:20 or 23:45); no stop in the research",
                 "expiry blank: the SILVERMIC future's expiry calendar is not in the lake for 2025-26 (fut_mcx_contracts "
                 "starts Jul 2026); R2 skips a day whose option segment changed overnight",
                 "the design window is tiny (2 months); R2's verdict: passed BH on the holdout, but one extraordinary year"],
    )
    (OUT / "MCX_US_NIGHT_SILVER.json").write_text(json.dumps(meta, indent=1, default=str))
    print(json.dumps(dict(n=summ, gap=meta["gap_final"], parity=meta["parity"]), indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
