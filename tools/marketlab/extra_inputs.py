"""Extra market data for the all-inputs network, each frame indexed by the time its values became KNOWN
(publication lags applied here, so the as-of join can never see the future)."""
import os

import numpy as np
import pandas as pd

from .io import read

H, D = pd.Timedelta(hours=1), pd.Timedelta(days=1)


def _r(path):
    return read(path) if os.path.exists(path) else None


def _shift(df, lag):
    df = df.copy()
    df.index = df.index + lag
    return df


def btc(root):
    x, dat = os.path.join(root, "crypto", "data_extra"), os.path.join(root, "crypto", "data")
    out = {}
    s = _r(f"{x}/options_surface_hourly.csv")
    if s is not None:
        s = s.ffill(limit=6)
        o = pd.DataFrame(index=s.index)
        for c in ("atm_iv_0_2d", "atm_iv_2_10d", "atm_iv_10_45d", "atm_iv_45_120d", "rr25", "fly25"):
            o[c] = s[c]
        o["term_slope"] = s["atm_iv_10_45d"] - s["atm_iv_2_10d"]
        o["rr25_24h"] = s["rr25"].rolling(24, min_periods=6).mean()
        o["pc_notional_24h"] = np.log((s["p_notional"].rolling(24).sum() / s["c_notional"].rolling(24).sum()).replace(0, np.nan))
        tot = s["notional_usd"].rolling(24).sum().replace(0, np.nan)
        o["net_call_taker_24h"] = s["c_net_taker"].rolling(24).sum() / tot
        o["net_put_taker_24h"] = s["p_net_taker"].rolling(24).sum() / tot
        o["block_share_24h"] = s["block_notional"].rolling(24).sum() / tot
        o["activity_z"] = (np.log1p(s["notional_usd"]) - np.log1p(s["notional_usd"]).rolling(168).mean()) / np.log1p(s["notional_usd"]).rolling(168).std()
        out["opt"] = _shift(o, H)  # the hour's trades are known at the hour's end
    dv = _r(f"{x}/dvol_1h.csv")
    if dv is not None:
        o = pd.DataFrame({"level": dv["close"], "chg24h": dv["close"].diff(24), "chg1h": dv["close"].diff()})
        out["dvol"] = _shift(o, H)
    bf, df_ = _r(f"{x}/binance_funding.csv"), _r(f"{x}/deribit_funding_1h.csv")
    f = pd.DataFrame()
    if bf is not None:
        f = pd.DataFrame({"binance_rate": bf["funding_rate"], "binance_7d": bf["funding_rate"].rolling(21).mean()})  # known when paid
    if df_ is not None and "interest_8h" in df_:
        f = f.join(pd.DataFrame({"deribit_8h": df_["interest_8h"]}), how="outer") if len(f) else pd.DataFrame({"deribit_8h": df_["interest_8h"]})
    if len(f):
        out["fund"] = f.sort_index()
    m = _r(f"{x}/binance_metrics_1h.csv")
    if m is not None:
        o = pd.DataFrame(index=m.index)
        if "sum_open_interest_value" in m:
            o["oi_chg_24h"] = np.log(m["sum_open_interest_value"]).diff(24)
            o["oi_chg_1h"] = np.log(m["sum_open_interest_value"]).diff()
        for c in ("count_toptrader_long_short_ratio", "sum_toptrader_long_short_ratio", "count_long_short_ratio", "sum_taker_long_short_vol_ratio"):
            if c in m:
                o[c] = np.log(m[c])
        out["pos"] = _shift(o, H)
    spot = _r(f"{dat}/btc_1h.csv")
    pp, cb = _r(f"{x}/binance_perp_1h.csv"), _r(f"{x}/coinbase_1h.csv")
    o = pd.DataFrame()
    if pp is not None and spot is not None:
        o = pd.DataFrame({"perp_basis": pp["close"] / spot["close"].reindex(pp.index) - 1})
    if cb is not None and spot is not None:
        cbp = pd.DataFrame({"coinbase_premium": cb["close"] / spot["close"].reindex(cb.index) - 1})
        o = o.join(cbp, how="outer") if len(o) else cbp
    if len(o):
        o["coinbase_premium_24h"] = o.get("coinbase_premium", pd.Series(dtype=float)).rolling(24, min_periods=6).mean()
        out["flow"] = _shift(o, H)
    fg = _r(f"{x}/fear_greed.csv")
    if fg is not None:
        out["sent"] = _shift(pd.DataFrame({"fear_greed": fg["value"].astype(float)}), D)
    cross = {}
    for name, fn in (("gold", "context_gold"), ("spx", "context_spx"), ("dxy", "context_dxy")):
        c = _r(f"{dat}/{fn}.csv")
        if c is not None:
            cross[f"{name}_ret1d"] = np.log(c["close"]).diff()
            cross[f"{name}_ret5d"] = np.log(c["close"]).diff(5)
    if cross:
        out["cross"] = _shift(pd.DataFrame(cross), D)  # a daily close is used from the next day
    return out


def gold(root):
    x, dat = os.path.join(root, "forex", "data_extra"), os.path.join(root, "forex", "data")
    out = {}
    v = {}
    for name in ("gvz", "vix"):
        c = _r(f"{x}/{name}_1d.csv")
        if c is not None:
            v[f"{name}"] = c["close"]
            v[f"{name}_chg5"] = c["close"].diff(5)
    if v:
        out["vol"] = _shift(pd.DataFrame(v), D)
    mac = {}
    dxy = _r(f"{x}/dxy_1d.csv")
    if dxy is not None:
        mac["dxy_ret1d"] = np.log(dxy["close"]).diff()
        mac["dxy_ret20d"] = np.log(dxy["close"]).diff(20)
    for s, name in (("DFII10", "real_yield"), ("T10YIE", "breakeven")):
        c = _r(f"{x}/fred_{s}.csv")
        if c is not None:
            mac[name] = c["value"]
            mac[f"{name}_chg20"] = c["value"].diff(20)
    if mac:
        out["macro"] = _shift(pd.DataFrame(mac), D)
    cot = _r(f"{x}/cot_gold.csv")
    if cot is not None and "mm_long" in cot:
        net = (cot["mm_long"] - cot["mm_short"]) / cot["open_interest"]
        out["cot"] = _shift(pd.DataFrame({"mm_net": net, "mm_net_chg4w": net.diff(4)}), pd.Timedelta(days=4))  # Tuesday data, out Friday
    cross = {}
    sil = _r(f"{x}/silver_1d.csv")
    if sil is not None:
        cross["silver_ret1d"] = np.log(sil["close"]).diff()
        cross["silver_ret5d"] = np.log(sil["close"]).diff(5)
    spx = _r(f"{dat}/context_spx.csv")
    if spx is not None:
        cross["spx_ret1d"] = np.log(spx["close"]).diff()
    cr = _shift(pd.DataFrame(cross), D) if cross else None
    btc_h = _r(os.path.join(root, "crypto", "data", "btc_1h.csv"))
    if btc_h is not None:
        b = pd.DataFrame({"btc_ret1h": np.log(btc_h["close"]).diff(), "btc_ret24h": np.log(btc_h["close"]).diff(24)})
        b = _shift(b, H)
        cr = cr.join(b, how="outer") if cr is not None else b
    if cr is not None:
        out["cross"] = cr.sort_index()
    return out
