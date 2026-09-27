"""Multi-timeframe data: resample 1-minute bars to every timeframe, build features per timeframe, and join
them all onto one decision clock by date and time, using only bars that have CLOSED by then."""
import numpy as np
import pandas as pd

from .features import rsi

TIMEFRAMES = {"1m": "1min", "5m": "5min", "15m": "15min", "30m": "30min", "1h": "1h", "3h": "3h", "6h": "6h",
              "12h": "12h", "24h": "1D"}


def resample(m1, rule):
    """OHLCV bars on UTC-aligned bins, indexed by the bar's OPEN time, with a `close_time` column."""
    agg = {"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}
    for extra in ("trades", "taker_buy_volume"):
        if extra in m1:
            agg[extra] = "sum"
    b = m1.resample(rule, label="left", closed="left").agg(agg).dropna(subset=["close"])
    b = b[b["volume"] > 0] if b["volume"].sum() > 0 else b
    b["close_time"] = b.index + pd.tseries.frequencies.to_offset(rule)
    return b


def tf_features(b, prefix):
    """Features of one timeframe, indexed by CLOSE time (when they become known)."""
    c = b["close"]
    r = np.log(c).diff()
    f = pd.DataFrame(index=pd.DatetimeIndex(b["close_time"]))
    f[f"{prefix}_ret1"] = r.values
    f[f"{prefix}_ret3"] = np.log(c / c.shift(3)).values
    f[f"{prefix}_mom20"] = np.log(c / c.shift(20)).values
    f[f"{prefix}_rv10"] = r.rolling(10).std().values
    f[f"{prefix}_rv_ratio"] = (r.rolling(10).std() / r.rolling(50).std()).values
    f[f"{prefix}_rsi14"] = rsi(c).values
    f[f"{prefix}_dist_sma20"] = (c / c.rolling(20).mean() - 1).values
    f[f"{prefix}_range"] = ((b["high"] - b["low"]) / c).values
    f[f"{prefix}_close_pos"] = ((c - b["low"]) / (b["high"] - b["low"]).replace(0, np.nan)).values
    lv = np.log1p(b["volume"])
    f[f"{prefix}_vol_z"] = ((lv - lv.rolling(20).mean()) / lv.rolling(20).std()).values
    if "taker_buy_volume" in b:
        tb = b["taker_buy_volume"] / b["volume"].replace(0, np.nan)
        f[f"{prefix}_buy_ratio"] = (tb - 0.5).values
    f.index.name = "time"
    return f


def micro_features(m1, rule="1h"):
    """What happened inside each hour at 1-minute resolution (indexed by the hour's close time): realised
    variance, jump share (realised minus bipower variation), the biggest run-up and drawdown, buy pressure."""
    r = np.log(m1["close"]).diff()
    g = r.groupby(r.index.floor(rule))
    rv = g.apply(lambda x: float((x ** 2).sum()))
    bpv = g.apply(lambda x: float((np.pi / 2) * (x.abs() * x.abs().shift(1)).sum()))
    cum = r.groupby(r.index.floor(rule)).cumsum()
    run_up = (cum - cum.groupby(cum.index.floor(rule)).cummin()).groupby(cum.index.floor(rule)).max()
    draw = (cum.groupby(cum.index.floor(rule)).cummax() - cum).groupby(cum.index.floor(rule)).max()
    f = pd.DataFrame({"m1_rvar": rv, "m1_jump_share": ((rv - bpv).clip(lower=0) / rv.replace(0, np.nan)),
                      "m1_max_runup": run_up, "m1_max_drawdown": draw})
    if "taker_buy_volume" in m1:
        v = m1["volume"].groupby(m1.index.floor(rule)).sum()
        tb = m1["taker_buy_volume"].groupby(m1.index.floor(rule)).sum()
        f["m1_buy_ratio"] = tb / v.replace(0, np.nan) - 0.5
    if "trades" in m1:
        n = np.log1p(m1["trades"].groupby(m1.index.floor(rule)).sum())
        f["m1_trades_z"] = (n - n.rolling(168).mean()) / n.rolling(168).std()
    f.index = f.index + pd.Timedelta(rule)
    f.index.name = "time"
    return f


def join_asof(clock, frames):
    """For every decision time in `clock`, take each frame's latest row with time <= decision time."""
    base = pd.DataFrame({"time": clock})
    cols = {}
    for f in frames:
        f = f.sort_index()
        f = f[~f.index.duplicated(keep="last")]
        m = pd.merge_asof(base[["time"]], f.reset_index().rename(columns={f.index.name or "index": "time"}),
                          on="time", direction="backward")
        for col in f.columns:
            cols[col] = m[col].values
    out = pd.DataFrame(cols, index=clock)
    out.index.name = "time"
    return out


def build_all(m1):
    """All timeframes from 1-minute bars, plus the joined feature table on the hourly clock."""
    bars = {tf: (m1 if tf == "1m" else resample(m1, rule)) for tf, rule in TIMEFRAMES.items()}
    if "close_time" not in bars["1m"]:
        bars["1m"] = bars["1m"].assign(close_time=bars["1m"].index + pd.Timedelta("1min"))
    feats = [tf_features(b, tf) for tf, b in bars.items()] + [micro_features(m1)]
    clock = bars["1h"]["close_time"]
    X = join_asof(pd.DatetimeIndex(clock), feats)
    return bars, X
