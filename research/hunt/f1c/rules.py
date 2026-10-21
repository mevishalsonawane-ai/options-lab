"""F1C candidate rules (PREREG section 8), fixed after the screen and before any rule P&L. 14 Oct 2026.

Each signal reads the decision frame F of one coin (brain features at 5-minute closes whose entry minute is inside
00:00-09:00 IST; engine.frame) and, where it needs raw prices, the coin's minute bars (engine.Market). It returns
decisions [ts, side]: side +1 = long (perps) / buy a call (options), -1 = short / buy a put. Thresholds are round
numbers fixed in advance, never tuned. Every trade then gets a stop-loss AND a profit lock (engine.Rule.exits).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .engine import Rule

ALL = ("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT")
BTCETH = ("BTCUSDT", "ETHUSDT")


def _dec(F, long_m, short_m) -> pd.DataFrame:
    long_m, short_m = np.asarray(long_m, bool), np.asarray(short_m, bool)
    side = np.where(long_m, 1, np.where(short_m, -1, 0))
    return pd.DataFrame({"ts": F.ts.values, "side": side})[side != 0].reset_index(drop=True)


def _prev(F, col):
    """The previous decision row's value on the same IST day (NaN on the day's first row)."""
    p = F[col].shift(1)
    return p.where(F.ist_day.eq(F.ist_day.shift(1)))


def _first_per_day(D: pd.DataFrame, F) -> pd.DataFrame:
    if D.empty:
        return D
    day = F.set_index("ts").ist_day.reindex(pd.DatetimeIndex(D.ts)).to_numpy()
    return D[~pd.Series(day).duplicated().to_numpy()].reset_index(drop=True)


# --- reversal family: the screen's one confirmed direction signal, strongest inside the window ---------------------
def p01_vwap_fade(F, mk=None):
    return _dec(F, F.vwap24_z < -2, F.vwap24_z > 2)


def p02_dayrange_fade(F, mk=None):
    up = (F.day_range_pos >= 0.95) & (F.ret_60m > F.atr14_1h_pct)
    dn = (F.day_range_pos <= 0.05) & (F.ret_60m < -F.atr14_1h_pct)
    return _dec(F, dn, up)


def p03_ribbon_stretch_fade(F, mk=None):
    return _dec(F, F.ema_rib15_dist_pct < -1.5 * F.atr14_1h_pct, F.ema_rib15_dist_pct > 1.5 * F.atr14_1h_pct)


def p04_hour_reversal(F, mk=None):
    hour_close = (F.ts.dt.minute == 59).to_numpy()
    big = np.abs(F.ret_60m) > 1.5 * F.atr14_1h_pct
    return _dec(F, hour_close & big & (F.ret_60m < 0), hour_close & big & (F.ret_60m > 0))


def p05_taker_exhaustion(F, mk=None):
    return _dec(F, (F.perp_taker_imb_60m < -0.2) & (F.ret_60m < 0), (F.perp_taker_imb_60m > 0.2) & (F.ret_60m > 0))


def p06_window_open_fade(F, mk=None):
    first = (F.ist_min == 0).to_numpy()                      # the 00:00 IST entry only
    return _dec(F, first & (F.vwap24_z < -1), first & (F.vwap24_z > 1))


# --- breakout / trend family -------------------------------------------------------------------------------------------
def p07_prevday_break(F, mk=None):
    up = (F.pdh_dist_pct > 0) & (_prev(F, "pdh_dist_pct") <= 0)
    dn = (F.pdl_dist_pct < 0) & (_prev(F, "pdl_dist_pct") >= 0)
    return _first_per_day(_dec(F, up, dn), F)


def _orb(F, mk):
    """Break of the 00:00-01:00 IST range of the spot minute bars, first 5-minute close outside it from 01:00 to
    06:00 IST; one per day."""
    if mk is None:
        return pd.DataFrame(columns=["ts", "side"])
    sp = pd.Series(mk.spot, index=mk.ts)
    ist = sp.index + pd.Timedelta(hours=5, minutes=30)
    first_hour = ist.hour == 0
    g = pd.Series(sp.values[first_hour], index=ist.normalize()[first_hour])
    hi, lo = g.groupby(level=0).max(), g.groupby(level=0).min()
    day = pd.to_datetime(F.ist_day)
    H = hi.reindex(day).to_numpy()
    L = lo.reindex(day).to_numpy()
    c = sp.reindex(pd.DatetimeIndex(F.ts)).to_numpy()
    ok = ((F.ist_min >= 60) & (F.ist_min <= 360)).to_numpy()
    return _first_per_day(_dec(F, ok & (c > H), ok & (c < L)), F)


def p08_asia_orb(F, mk=None):
    return _orb(F, mk)


def p09_squeeze_break(F, mk=None):
    sq = F.ema_rib15_width_pct < 0.25 * F.atr14_1h_pct
    thr = 0.5 * F.atr14_1h_pct
    return _dec(F, sq & (F.ret_15m > thr), sq & (F.ret_15m < -thr))


def p10_ribbon_trend(F, mk=None):
    up = (F.ema_rib1h_order == 1) & (F.ema_rib15_order == 1) & (_prev(F, "ema_rib15_order") < 1)
    dn = (F.ema_rib1h_order == -1) & (F.ema_rib15_order == -1) & (_prev(F, "ema_rib15_order") > -1)
    return _dec(F, up, dn)


# --- flow / derivatives / options-market family -----------------------------------------------------------------------
def p11_liq_flush_fade(F, mk=None):
    fl = F.liq_flush_60m == 1
    return _dec(F, fl & (F.ret_60m < 0), fl & (F.ret_60m > 0))


def p12_dayextreme_4h(F, mk=None):
    """The screen's only confirmed direction signals that also paid (XRP, 240 minutes): price at the UTC day's high
    tends to come back over 4 hours. Close within 0.1% of the day's high -> short, of its low -> long; one a day."""
    return _first_per_day(_dec(F, F.day_low_dist_pct < 0.1, F.day_high_dist_pct > -0.1), F)


def p13_oi_divergence(F, mk=None):
    oi = F.oi_chg_60m_pct.where(F.oi_chg_60m_pct.abs() < 20)    # the lake has a few zero-OI prints (-100% / inf)
    up = F.ret_60m > F.atr14_1h_pct
    dn = F.ret_60m < -F.atr14_1h_pct
    falling = oi < -0.5
    return _dec(F, dn & falling, up & falling)                  # a move on falling OI (forced exits) is faded


def p14_ribbon_slope_fade(F, mk=None):
    thr = 0.5 * F.atr14_1h_pct
    return _dec(F, F.ema_rib15_slope_pct < -thr, F.ema_rib15_slope_pct > thr)


def p15_consensus_reversal(F, mk=None):
    """Four confirmed reversal features agree that price is stretched: 24-h VWAP z, place in the day's range, distance
    from the 15-minute ribbon, 60-minute perp taker imbalance."""
    a = F.atr14_1h_pct
    up = (F.vwap24_z > 1) & (F.day_range_pos > 0.8) & (F.ema_rib15_dist_pct > 0.5 * a) & (F.perp_taker_imb_60m > 0.1)
    dn = (F.vwap24_z < -1) & (F.day_range_pos < 0.2) & (F.ema_rib15_dist_pct < -0.5 * a) & (F.perp_taker_imb_60m < -0.1)
    return _dec(F, dn, up)


def o19_vol_burst(F, mk=None):
    implied = F.dvol / 100 * np.sqrt(15 / (365 * 1440)) * 100      # DVOL's 15-minute move, %
    burst = np.abs(F.ret_15m) >= 2.5 * implied
    return _dec(F, burst & (F.ret_15m > 0), burst & (F.ret_15m < 0))


RULES = {
    "P01": Rule("P01", "VWAP-24h stretch fade: |vwap24_z| > 2, against it", "perp", ALL, p01_vwap_fade, 60, 2),
    "P02": Rule("P02", "Day-extreme fade: near the UTC day's high/low after a 1-ATR hour, against it", "perp", ALL,
                p02_dayrange_fade, 120, 2),
    "P03": Rule("P03", "Ribbon stretch fade: close > 1.5 ATR from the 15-min ribbon's middle, against it", "perp",
                ALL, p03_ribbon_stretch_fade, 60, 2),
    "P04": Rule("P04", "Hour reversal: an hour that moved > 1.5 ATR, against it at the hour's close", "perp", ALL,
                p04_hour_reversal, 60, 2),
    "P05": Rule("P05", "Taker exhaustion: 60-min perp taker imbalance beyond +-0.2 with the move, against it", "perp",
                ALL, p05_taker_exhaustion, 60, 2),
    "P06": Rule("P06", "Window-open fade (C2 W4 as a rule): at 00:00 IST against the side of the 24-h VWAP (|z| > 1)",
                "perp", ALL, p06_window_open_fade, None, 1),
    "P07": Rule("P07", "Previous UTC day high/low break, with it (RC2 R8 on perps)", "perp", ALL, p07_prevday_break,
                180, 1),
    "P08": Rule("P08", "Asia opening range (00:00-01:00 IST) break, 01:00-06:00 IST, with it", "perp", ALL,
                p08_asia_orb, None, 1),
    "P09": Rule("P09", "Ribbon squeeze break: 15-min ribbon width < 0.25 ATR and a 0.5-ATR 15-min move, with it",
                "perp", ALL, p09_squeeze_break, 120, 2),
    "P10": Rule("P10", "Ribbon trend: 1-h ribbon fully ordered and the 15-min ribbon turning fully ordered, with it",
                "perp", ALL, p10_ribbon_trend, 180, 2),
    "P11": Rule("P11", "Liquidation-flush fade: OI -1%, price 1%, volume 2x in 60 min, against the move", "perp", ALL,
                p11_liq_flush_fade, 120, 1),
    "P12": Rule("P12", "Day-extreme 4-hour fade: close within 0.1% of the UTC day's high (short) / low (long)", "perp",
                ALL, p12_dayextreme_4h, 240, 1),
    "P13": Rule("P13", "OI divergence: a 1-ATR hour on falling OI (-0.5%), against it", "perp", ALL,
                p13_oi_divergence, 120, 2),
    "P14": Rule("P14", "Ribbon slope fade: the 15-min ribbon's middle EMA moved > 0.5 ATR in 60 min, against it",
                "perp", ALL, p14_ribbon_slope_fade, 60, 2),
    "P15": Rule("P15", "Consensus reversal: VWAP z, day range, ribbon distance and taker imbalance all stretched, "
                "against them", "perp", ALL, p15_consensus_reversal, 60, 2),
    "O16": Rule("O16", "Delta India options: P01's VWAP stretch fade, buy the ATM call/put", "option", BTCETH,
                p01_vwap_fade, 60, 2),
    "O17": Rule("O17", "Delta India options: P04's hour reversal, buy the ATM call/put", "option", BTCETH,
                p04_hour_reversal, 60, 2),
    "O18": Rule("O18", "Delta India options: P07's previous-day break, buy the ATM call/put", "option", BTCETH,
                p07_prevday_break, 180, 1),
    "O19": Rule("O19", "Delta India options: volatility burst (|15-min move| >= 2.5x DVOL's), with it", "option",
                BTCETH, o19_vol_burst, 120, 2),
    "O20": Rule("O20", "Delta India options: P08's Asia opening-range break, buy the ATM call/put", "option", BTCETH,
                p08_asia_orb, None, 1),
}
