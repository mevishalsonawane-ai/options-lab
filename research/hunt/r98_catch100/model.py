"""R98C model: gradient boosting on every causal feature of the dataset predicts the gross points of the future's
+100 / -100 / 60-minute bracket (label fp_100_60). Settings fixed in PREREG.md s3 before any model was fitted.

Walk-forward on design: fold k (k = 2..4) is predicted by a model trained only on design rows dated before the
fold's first day minus one session (embargo); fold 1 is training only (no prediction, no trades).
Holdout: one model trained on all design rows.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from research.hunt.r98_catch100 import cfg as K
from research.hunt.r98_catch100.scan import feature_cols

LABEL = "fp_100_60"
PARAMS = dict(learning_rate=0.05, max_iter=300, max_leaf_nodes=15, min_samples_leaf=300, l2_regularization=1.0,
              random_state=K.SEED)


def columns(df: pd.DataFrame) -> list:
    return sorted(set(feature_cols(df)) | {"side", "tod"})


def fit(train: pd.DataFrame, cols: list):
    t = train.dropna(subset=[LABEL])
    return HistGradientBoostingRegressor(**PARAMS).fit(t[cols].astype(float), t[LABEL].astype(float))


def walk_forward(df: pd.DataFrame) -> pd.Series:
    cols = columns(df)
    pred = pd.Series(np.nan, index=df.index)
    for a, b in K.FOLDS[1:]:
        start = pd.Timestamp(a)
        train = df[df.day < start - pd.Timedelta(days=1)]
        test = (df.day >= start) & (df.day <= pd.Timestamp(b))
        if len(train) < 1000 or not test.any():
            continue
        pred[test] = fit(train, cols).predict(df.loc[test, cols].astype(float))
    return pred


def holdout_predict(design: pd.DataFrame, hold: pd.DataFrame) -> pd.Series:
    cols = columns(design)
    return pd.Series(fit(design, cols).predict(hold[cols].astype(float)), index=hold.index)
