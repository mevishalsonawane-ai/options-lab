"""R145 panel: 5-minute bars of every coin on one UTC grid -> point-in-time features, labels and sequence channels.

A panel is a dict of DataFrames (index = bar open time on a 5-minute grid, columns = symbols) for the bar fields, plus
the funding events per symbol. Every feature at a decision bar k (bar open T_k, decision time t = T_k + 5 min) uses
only bars 0..k and funding settled by t: all rolling windows end at k, cross-sectional values use the same k. The
look-ahead guard (guard.py) checks this by deleting and perturbing everything after t.

Labels (y_*) use bars k+1 .. k+h/5 only, and are kept apart from the features.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.stats import norm

BAR = 5
FIELDS = ["open", "high", "low", "close", "volume", "qv", "trades", "tbqv", "rv", "n1"]
HORIZONS = (15, 60, 240)
RET_H = (5, 15, 60, 240, 1440, 4320)
DAY = 1440 // BAR                  # 288 bars
WEEK = 7 * DAY                     # 2016 bars
LISTING_WARMUP = 14 * DAY          # a coin's rows start 14 days after its first bar
IST = pd.Timedelta(hours=5, minutes=30)
SEQ_LEN = 48
SEQ_CH = ["s_ret", "s_range", "s_volz", "s_taker", "s_btc", "s_mkt"]


def make_panel(bars: dict[str, pd.DataFrame], lo=None, hi=None) -> dict[str, pd.DataFrame]:
    """bars: symbol -> DataFrame(ts, FIELDS). Returns field -> DataFrame(grid x symbols). Missing bars stay NaN."""
    syms = sorted(bars)
    lo = pd.Timestamp(lo) if lo is not None else min(b.ts.min() for b in bars.values())
    hi = pd.Timestamp(hi) if hi is not None else max(b.ts.max() for b in bars.values())
    grid = pd.date_range(lo.floor(f"{BAR}min"), hi.floor(f"{BAR}min"), freq=f"{BAR}min")
    P = {}
    for f in FIELDS:
        P[f] = pd.DataFrame({s: bars[s].set_index("ts")[f].reindex(grid) for s in syms}, index=grid, dtype="float64")
    return P


def decision_bars(grid: pd.DatetimeIndex) -> np.ndarray:
    """Bar positions whose close is on the hour (bar open at HH:55): decision time t = HH:00 of the next hour."""
    return np.flatnonzero((grid + pd.Timedelta(minutes=BAR)).minute == 0)


# --- helpers --------------------------------------------------------------------------------------------------------
def _logc(P):
    return np.log(P["close"].ffill(limit=12))


def _sigma_bar(P):
    """Per-bar volatility: sqrt of the mean 5-minute realised variance over the last day (min half a day of bars)."""
    return np.sqrt(P["rv"].rolling(DAY, min_periods=DAY // 2).mean())


def _xs_rank(df: pd.DataFrame) -> pd.DataFrame:
    return df.rank(axis=1, pct=True)


def _funding_at(fund: dict[str, pd.DataFrame], idx: pd.DatetimeIndex, syms) -> dict[str, pd.DataFrame]:
    """Last settled funding rate at each decision time t (settled at or before t), its 30-day z (90 events) and
    the minutes to the coin's next settlement."""
    rate, z, nxt = {}, {}, {}
    q = pd.DataFrame({"t": idx}).sort_values("t")
    for s in syms:
        f = fund.get(s)
        if f is None or not len(f):
            rate[s] = z[s] = nxt[s] = pd.Series(np.nan, index=idx)
            continue
        f = f.sort_values("ts").drop_duplicates("ts").reset_index(drop=True)
        mu = f.rate.rolling(90, min_periods=10).mean()
        sd = f.rate.rolling(90, min_periods=10).std()
        f = f.assign(z=(f.rate - mu) / sd.replace(0, np.nan), last=f.ts)
        m = pd.merge_asof(q, f[["ts", "rate", "z", "interval_h", "last"]], left_on="t", right_on="ts",
                          direction="backward", tolerance=pd.Timedelta(hours=9))
        m.index = m.t
        rate[s] = m.rate.reindex(idx)
        z[s] = m.z.reindex(idx)
        mins = (m.interval_h * 60 - (m.t - m["last"]).dt.total_seconds() / 60).clip(lower=0)
        nxt[s] = mins.reindex(idx)
    df = lambda d: pd.DataFrame(d, index=idx)            # noqa: E731
    return dict(fund_rate=df(rate) * 1e4, fund_z=df(z), fund_next_min=df(nxt))


# --- features ---------------------------------------------------------------------------------------------------------
def features(P: dict, fund: dict | None = None, dec: np.ndarray | None = None,
             first_seen: dict | None = None) -> pd.DataFrame:
    """Point-in-time features at the decision bars, long format: ts (decision time), sym, feature columns.

    first_seen: symbol -> time of its first bar in all the data loaded so far (a past fact at every decision time);
    by default the first bar in this panel, and a coin trading on the panel's first day counts as old."""
    grid = P["close"].index
    syms = list(P["close"].columns)
    dec = decision_bars(grid) if dec is None else dec
    di = grid[dec]
    out: dict[str, pd.DataFrame] = {}

    def at(df):
        return df.iloc[dec]

    logc = _logc(P)
    sb = _sigma_bar(P)
    sig = lambda h: sb * np.sqrt(h / BAR)                # noqa: E731
    r = {}
    for h in RET_H:
        n = h // BAR
        r[h] = logc - logc.shift(n)
        out[f"rn_{h}"] = at(r[h] / sig(h))
    out["r_60"] = at(r[60])
    out["r_1440"] = at(r[1440])
    rv = P["rv"]
    for h in (60, 240, 1440):
        n = h // BAR
        out[f"lrv_{h}"] = at(0.5 * np.log(rv.rolling(n, min_periods=n // 2).mean().clip(lower=1e-14)))
    out["vol_ratio"] = out["lrv_60"] - out["lrv_1440"]
    for name, n in (("pos_24h", DAY), ("pos_7d", WEEK)):
        hh = P["high"].rolling(n, min_periods=n // 2).max()
        ll = P["low"].rolling(n, min_periods=n // 2).min()
        out[name] = at((P["close"].ffill(limit=12) - ll) / (hh - ll).replace(0, np.nan))
    vwap = P["qv"].rolling(DAY, min_periods=DAY // 2).sum() / P["volume"].rolling(DAY, min_periods=DAY // 2).sum()
    out["vwap_dist"] = at((logc - np.log(vwap)) / sig(1440))
    for name, f in (("qv_z", "qv"), ("trades_z", "trades")):
        x = np.log(P[f].rolling(12, min_periods=6).sum() + 1.0)
        out[name] = at((x - x.rolling(WEEK, min_periods=DAY).mean()) / x.rolling(WEEK, min_periods=DAY).std())
    for h in (60, 240):
        n = h // BAR
        out[f"taker_{h}"] = at(P["tbqv"].rolling(n, min_periods=n // 2).sum()
                               / P["qv"].rolling(n, min_periods=n // 2).sum().replace(0, np.nan) - 0.5)
    btc = "BTCUSDT" if "BTCUSDT" in syms else syms[0]
    b60 = r[60][btc]
    cov = r[60].rolling(WEEK, min_periods=WEEK // 2).cov(b60)
    var = b60.rolling(WEEK, min_periods=WEEK // 2).var()
    beta = cov.div(var, axis=0)
    corr = r[60].rolling(WEEK, min_periods=WEEK // 2).corr(b60)
    out["beta_btc"] = at(beta)
    out["corr_btc"] = at(corr)
    out["resid_60"] = at((r[60] - beta.mul(b60, axis=0)) / sig(60))
    out["resid_240"] = at((r[240] - beta.mul(r[240][btc], axis=0)) / sig(240))
    # days since listing: a coin already trading on the panel's first day counts as old (365)
    present = P["n1"].notna()
    first = present.idxmax().where(present.any(), pd.NaT)
    if first_seen is not None:
        first = pd.Series({s: pd.Timestamp(first_seen.get(s, first[s])) for s in syms})
        age = pd.DataFrame({s: (di - first[s]) / pd.Timedelta(days=1) for s in syms}, index=di)
    else:
        age = pd.DataFrame({s: (di - first[s]) / pd.Timedelta(days=1) if first[s] != grid[0]
                            else np.full(len(di), 365.0) for s in syms}, index=di)
    out["age_log"] = np.log1p(age.clip(lower=0, upper=365))
    for name, src in (("xr_r60", out["rn_60"]), ("xr_r1440", out["rn_1440"]), ("xr_vol", out["lrv_1440"]),
                      ("xr_qvz", out["qv_z"])):
        out[name] = _xs_rank(src)
    if fund is not None:
        fr = _funding_at(fund, di + pd.Timedelta(minutes=BAR), syms)      # as of the decision time t
        out.update({k: v.set_axis(di) for k, v in fr.items()})
    else:
        for k in ("fund_rate", "fund_z", "fund_next_min"):
            out[k] = pd.DataFrame(np.nan, index=di, columns=syms)

    # market context (the same for every coin at t)
    mk = {}
    for h in (15, 60, 240, 1440):
        mk[f"btc_r{h}"] = at(r[h][btc])
    eth = "ETHUSDT" if "ETHUSDT" in syms else btc
    mk["eth_r60"], mk["eth_r240"] = at(r[60][eth]), at(r[240][eth])
    mk["btc_lrv60"], mk["btc_lrv1440"] = out["lrv_60"][btc], out["lrv_1440"][btc]
    for h in (60, 240, 1440):
        rr = at(r[h])
        mk[f"breadth_{h}"] = (rr > 0).where(rr.notna()).mean(axis=1)
    mk["mkt_r60"], mk["mkt_r240"] = at(r[60]).mean(axis=1), at(r[240]).mean(axis=1)
    mk["disp_60"] = at(r[60]).std(axis=1)
    l240 = 0.5 * np.log(rv.rolling(48, min_periods=24).mean().clip(lower=1e-14))
    vz = (l240 - l240.rolling(WEEK, min_periods=DAY).mean()) / l240.rolling(WEEK, min_periods=DAY).std()
    mk["mkt_volz"] = at(vz).median(axis=1)
    ist = di + IST
    mk["hour_sin"] = pd.Series(np.sin(2 * np.pi * (ist.hour + ist.minute / 60) / 24), index=di)
    mk["hour_cos"] = pd.Series(np.cos(2 * np.pi * (ist.hour + ist.minute / 60) / 24), index=di)
    mk["dow_sin"] = pd.Series(np.sin(2 * np.pi * ist.dayofweek / 7), index=di)
    mk["dow_cos"] = pd.Series(np.cos(2 * np.pi * ist.dayofweek / 7), index=di)
    mk["weekend"] = pd.Series((ist.dayofweek >= 5).astype(float), index=di)

    # long format
    base = at(P["n1"])
    ok = base.notna() & (base > 0)
    listed = (age.to_numpy() >= LISTING_WARMUP / DAY) if first_seen is not None else (
        (np.arange(len(grid))[dec][:, None] - present.values.argmax(axis=0)[None, :]) >= LISTING_WARMUP)
    ok &= listed & present.values.any(axis=0)[None, :]
    st = ok.stack()
    st = st[st]
    long = pd.DataFrame(index=st.index)
    for k, v in out.items():
        v = v.copy()
        v.index = di
        long[k] = v.stack(future_stack=True).reindex(st.index).to_numpy(float)
    long = long.reset_index()
    long.columns = ["ts", "sym"] + list(out)
    for k, v in mk.items():
        long[k] = pd.Series(np.asarray(v, float), index=di).reindex(long.ts).to_numpy()
    long["ts"] = long.ts + pd.Timedelta(minutes=BAR)              # decision time t = bar open + 5 min
    long["bar"] = np.searchsorted(grid.values, (long.ts - pd.Timedelta(minutes=BAR)).values)
    return long


FEATURES = None   # filled on first build: every column except ts, sym, bar


def feature_names(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in ("ts", "sym", "bar") and not c.startswith("y_")]


# --- labels -----------------------------------------------------------------------------------------------------------
def labels(P: dict, dec: np.ndarray | None = None, horizons=HORIZONS) -> pd.DataFrame:
    """Targets at each decision bar k from bars k+1 .. k+h/5 only. Long format (ts = decision time, sym)."""
    grid = P["close"].index
    dec = decision_bars(grid) if dec is None else dec
    syms = list(P["close"].columns)
    c = P["close"].ffill(limit=1)
    logc = np.log(c)
    sb = _sigma_bar(P)
    rv = P["rv"]
    n1 = P["n1"].fillna(0)
    res = {}
    for h in horizons:
        n = h // BAR
        ret = logc.shift(-n) - np.log(P["close"])                   # entry: the real close of bar k
        s = sb * np.sqrt(h / BAR)
        fut_rv = rv[::-1].rolling(n, min_periods=n).sum()[::-1].shift(-1)       # bars k+1..k+n
        cover = n1[::-1].rolling(n, min_periods=n).sum()[::-1].shift(-1)
        vol = 0.5 * np.log(fut_rv.clip(lower=1e-14)).where(cover >= 0.9 * h)
        R = ret.iloc[dec]
        res[f"y_ret_{h}"] = R
        res[f"y_dir_{h}"] = (R > 0).astype(float).where(R.notna())
        res[f"y_size_{h}"] = (R.abs() / s.iloc[dec])
        res[f"y_vol_{h}"] = vol.iloc[dec]
        res[f"y_big_{h}"] = (R.abs() > 2 * s.iloc[dec]).astype(float).where(R.notna() & s.iloc[dec].notna())
        rk = R.rank(axis=1)
        cnt = R.notna().sum(axis=1)
        xs = pd.DataFrame(norm.ppf((rk.values - 0.5) / cnt.values[:, None]), index=R.index, columns=syms)
        res[f"y_xs_{h}"] = xs.where(np.broadcast_to(cnt.values[:, None] >= 10, xs.shape))
    res["sig_bar"] = sb.iloc[dec]                  # trailing per-bar volatility at t (for stops; not a feature)
    di = grid[dec]
    long = None
    for k, v in res.items():
        v = v.copy()
        v.index = di
        s = v.stack(future_stack=True).rename(k)
        long = s.to_frame() if long is None else long.join(s, how="outer")
    long = long.reset_index()
    long.columns = ["ts", "sym"] + list(res)
    long["ts"] = long.ts + pd.Timedelta(minutes=BAR)
    return long


# --- sequence channels ------------------------------------------------------------------------------------------------
def seq_channels(P: dict) -> np.ndarray:
    """Per-bar channels (grid x symbols x 6), float32, each point-in-time at its own bar. NaN -> 0 at model time."""
    syms = list(P["close"].columns)
    btc = "BTCUSDT" if "BTCUSDT" in syms else syms[0]
    logc = _logc(P)
    sb = _sigma_bar(P).replace(0, np.nan)
    r1 = (logc - logc.shift(1)) / sb
    rng = np.log(P["high"] / P["low"]) / sb
    x = np.log(P["qv"] + 1.0)
    vz = (x - x.rolling(WEEK, min_periods=DAY).mean()) / x.rolling(WEEK, min_periods=DAY).std()
    tk = P["tbqv"] / P["qv"].replace(0, np.nan) - 0.5
    bt = pd.DataFrame(np.repeat(r1[[btc]].values, len(syms), axis=1), index=r1.index, columns=syms)
    mk = pd.DataFrame(np.repeat(r1.clip(-10, 10).mean(axis=1).values[:, None], len(syms), axis=1),
                      index=r1.index, columns=syms)
    ch = [r1.clip(-10, 10), rng.clip(0, 20), vz.clip(-6, 6), tk, bt.clip(-10, 10), mk]
    return np.stack([c.to_numpy(np.float32) for c in ch], axis=2)
