"""The expected range of the next 30 / 60 minutes (R16's 4-input linear model), refit walk-forward by month.

Inputs at the close of minute t (bars 0..t known): tod = mean log range of bars t+1..t+H over the previous 20 sessions
(each scaled by that session's close at t); log range of the last 30 and 60 bars (fewer since 09:15 if the day is
younger) over the close at t; log VIX previous close. Target: log((max high - min low of bars t+1..t+H) / close at t).
Training rows: t = 09:44, 09:59, ... 14:44 (decisions 09:45-14:45, as R16), all earlier sessions less a 5-session
embargo. Pure numpy.
"""
from __future__ import annotations

import numpy as np

import config as K

ROW_T = list(range(29, 330, 15))
FLOOR = 1e-5                          # a range of 0 (a frozen feed) counts as 0.001% of price


def _lr(hi, lo, c):
    return np.log(np.maximum(hi - lo, FLOOR * c) / c)


class ERange:
    def __init__(self, days):
        self.days = days
        self.H = np.stack([d.ix[:, 1] for d in days])
        self.L = np.stack([d.ix[:, 2] for d in days])
        self.C = np.stack([d.ix[:, 3] for d in days])
        self.vix = np.log(np.array([d.vix_prev for d in days], dtype=float))
        self.D = len(days)
        self.coef: dict[tuple[int, int], np.ndarray] = {}     # (horizon, day index) -> coefficients used that day
        self._fut = {h: self._future(h) for h in (30, 60)}
        self._fit()

    def _past(self, di, t, k):
        a = max(0, t - k + 1)
        return _lr(self.H[di, a:t + 1].max(), self.L[di, a:t + 1].min(), self.C[di, t])

    def _future(self, h):
        """(D, 375) log range of bars t+1..t+h over the close at t (NaN where the window is cut by 15:29)."""
        out = np.full((self.D, 375), np.nan)
        for t in range(0, 375 - h):
            hi = self.H[:, t + 1:t + 1 + h].max(axis=1)
            lo = self.L[:, t + 1:t + 1 + h].min(axis=1)
            out[:, t] = _lr(hi, lo, self.C[:, t])
        return out

    def tod(self, di, t, h):
        if di < 20:
            return np.nan
        return float(np.nanmean(self._fut[h][di - 20:di, min(t, 374 - h - 1)]))

    def x(self, di, t, h):
        return np.array([1.0, self.tod(di, t, h), self._past(di, t, 30), self._past(di, t, 60), self.vix[di]])

    def _fit(self):
        months = {}
        for i, d in enumerate(self.days):
            months.setdefault((d.day.year, d.day.month), []).append(i)
        rows = {h: [(di, t) for di in range(self.D) for t in ROW_T] for h in (30, 60)}
        feats = {h: np.array([self.x(di, t, h) for di, t in rows[h]]) for h in (30, 60)}
        ys = {h: np.array([self._fut[h][di, t] for di, t in rows[h]]) for h in (30, 60)}
        rdi = {h: np.array([di for di, _ in rows[h]]) for h in (30, 60)}
        for (y, m), idx in sorted(months.items()):
            first = idx[0]
            if first < K.WARMUP_SESSIONS:
                continue
            for h in (30, 60):
                ok = (rdi[h] < first - K.EMBARGO) & np.isfinite(feats[h]).all(axis=1) & np.isfinite(ys[h])
                if ok.sum() < 200:
                    continue
                b, *_ = np.linalg.lstsq(feats[h][ok], ys[h][ok], rcond=None)
                for i in idx:
                    self.coef[(h, i)] = b

    def predict(self, di, t, h=30) -> float:
        """Expected high-low of the next h minutes in index points at the close of minute t (NaN: none)."""
        b = self.coef.get((h, di))
        if b is None:
            return np.nan
        z = float(self.x(di, t, h) @ b)
        return float(np.exp(z) * self.C[di, t]) if np.isfinite(z) else np.nan

    def realised(self, di, t, h=30) -> float:
        v = self._fut[h][di, t]
        return float(np.exp(v) * self.C[di, t]) if np.isfinite(v) else np.nan
