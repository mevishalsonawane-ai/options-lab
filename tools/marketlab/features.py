"""Indicators and model features. Every feature at row t uses only data up to and including bar t;
targets look forward from t, so a model fed row t never sees the bar it predicts."""
import numpy as np
import pandas as pd


def rsi(c, n=14):
    d = c.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return 100 - 100 / (1 + up / dn.replace(0, np.nan))


def atr(df, n=14):
    pc = df["close"].shift()
    tr = pd.concat([df["high"] - df["low"], (df["high"] - pc).abs(), (df["low"] - pc).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False).mean()


def realized_vol(ret, n, per_year):
    return ret.rolling(n).std() * np.sqrt(per_year)


def base_features(df, per_year, lags=10):
    """Price-only features for an OHLCV frame (daily or hourly)."""
    c = df["close"]
    r = np.log(c).diff()
    f = pd.DataFrame(index=df.index)
    for k in range(1, lags + 1):
        f[f"ret_l{k}"] = r.shift(k - 1)
    for n in (5, 20, 60):
        f[f"rv{n}"] = realized_vol(r, n, per_year)
        f[f"mom{n}"] = np.log(c / c.shift(n))
    for n in (20, 50, 200):
        f[f"dist_sma{n}"] = c / c.rolling(n).mean() - 1
    f["rsi14"] = rsi(c)
    ema12, ema26 = c.ewm(span=12).mean(), c.ewm(span=26).mean()
    macd = ema12 - ema26
    f["macd_hist"] = (macd - macd.ewm(span=9).mean()) / c
    f["atr_pct"] = atr(df) / c
    f["range_pct"] = (df["high"] - df["low"]) / c
    f["close_pos"] = (c - df["low"]) / (df["high"] - df["low"]).replace(0, np.nan)
    f["dd60"] = c / c.rolling(60).max() - 1
    f["vol_ratio"] = f["rv5"] / f["rv60"]
    if "volume" in df and df["volume"].fillna(0).sum() > 0:
        lv = np.log1p(df["volume"].fillna(0))
        f["vol_z"] = (lv - lv.rolling(20).mean()) / lv.rolling(20).std()
    return f


def calendar_features(index, hourly=False):
    f = pd.DataFrame(index=index)
    dow = index.dayofweek
    f["dow_sin"], f["dow_cos"] = np.sin(2 * np.pi * dow / 7), np.cos(2 * np.pi * dow / 7)
    if hourly:
        h = index.hour
        f["hour_sin"], f["hour_cos"] = np.sin(2 * np.pi * h / 24), np.cos(2 * np.pi * h / 24)
    return f


def targets(df, per_year, horizon_vol=5):
    c = df["close"]
    r = np.log(c).diff()
    t = pd.DataFrame(index=df.index)
    t["next_ret"] = r.shift(-1)
    t["up"] = (t["next_ret"] > 0).astype(float)
    t.loc[t["next_ret"].isna(), "up"] = np.nan
    # realised vol over the next `horizon_vol` bars, annualised, in log form
    fwd = pd.concat([r.shift(-k) for k in range(1, horizon_vol + 1)], axis=1)
    t["fwd_rv"] = np.log(fwd.std(axis=1, ddof=0) * np.sqrt(per_year) + 1e-6)
    t.loc[fwd.isna().any(axis=1), "fwd_rv"] = np.nan
    return t
