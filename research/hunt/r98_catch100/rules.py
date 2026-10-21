"""R98 rules (PREREG.md s3). Signal source: the MCX gold future (GOLDM near future, minute closes); XAUUSD's 15-min
move vs the future's is a filter on the future's own move. Signals are read on the dataset's 5-minute grid
(dataset.py), from causal features only. R98A-D, F trade the GOLDM FUTURE (1 lot); R98E buys the ATM GOLDM option on
R98A's signal (signal on the underlying, trade the option).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r98_catch100 import cfg as K


class Rule:
    rid = ""
    instrument = "future"
    max_per_day = 4
    window = (K.FIRST_TOD, K.LAST_TOD)

    def mask(self, df: pd.DataFrame) -> pd.Series:
        raise NotImplementedError

    def signals(self, df: pd.DataFrame) -> pd.DataFrame:
        w = (df.tod >= self.window[0]) & (df.tod <= self.window[1])
        return df[w & self.mask(df).fillna(False)].sort_values(["i_sig", "side"])


class XauLead(Rule):
    """R98A: XAUUSD's last 15 min beat the future's by >= 10 bp in the trade's direction, and the future's last three
    5-min candles did not already move that way (mom3 <= 0)."""
    rid = "R98A"

    def mask(self, df):
        return (df.xau_lead15_bp >= 10) & (df.c5_mom3 <= 0)


class XauLeadAfternoon(XauLead):
    """R98B: R98A from 12:00 IST only."""
    rid = "R98B"
    window = (12 * 60, K.LAST_TOD)


class StretchXau(Rule):
    """R98D: the future is >= 600 points from the session's average price AGAINST the trade (stretched) and XAUUSD is
    >= 5 bp ahead in the trade's direction."""
    rid = "R98D"

    def mask(self, df):
        return (df.twap_dev <= -600) & (df.xau_lead15_bp >= 5)


class CandleFade(Rule):
    """R98F: two or more 15-min candles against the trade in a row and the last 5-min candle's body >= 58 points
    against it: fade the push (the candle finding of step B)."""
    rid = "R98F"

    def mask(self, df):
        return (df.c15_streak <= -2) & (df.c5_body <= -58)


class XauLeadOption(XauLead):
    """R98E: R98A's signal, buying the ATM GOLDM option; exits on the future's bracket (+100 / -100 / 60 min)."""
    rid = "R98E"
    instrument = "option"


class Model(Rule):
    """R98C: walk-forward gradient boosting (model.py) predicts the gross points of the +100 / -100 bracket; trade when
    the prediction >= MODEL_EDGE. Predictions are out of sample: each fold's model is trained only on earlier days."""
    rid = "R98C"
    MODEL_EDGE = 55.0

    def mask(self, df):
        if "pred" not in df:
            return pd.Series(False, index=df.index)
        return df.pred >= self.MODEL_EDGE


RULES = {r.rid: r for r in (XauLead(), XauLeadAfternoon(), Model(), StretchXau(), XauLeadOption(), CandleFade())}
ORDER = ("R98A", "R98B", "R98C", "R98D", "R98E", "R98F")
NAN = float("nan")
