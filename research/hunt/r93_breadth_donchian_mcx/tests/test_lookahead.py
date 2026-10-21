"""R93 look-ahead guard (PREREG section 8). Every signal at or before a cut must be the same when every row after the
cut is deleted, and when every row after the cut is perturbed: 5-minute bars, channels, the segment rule, I-A / I-B /
I-C sides, the driver bars, the daily roll rows, back-adjusted channel, ATR and D-B breadth. A deliberately leaky
signal (the next bar's side) must be caught."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r93_breadth_donchian_mcx import cfg as K
from research.hunt.r93_breadth_donchian_mcx import rules as R
from research.hunt.r93_breadth_donchian_mcx import study as S

DAYS = ("2026-07-01", "2026-07-02", "2026-07-03", "2026-07-06")


def walk(seed, n, vol=0.6):
    rng = np.random.default_rng(seed)
    return 1000 + np.cumsum(rng.normal(0, vol, n))


def make_market(seed=1, trend=0.0):
    """Four synthetic families on MCX minutes 09:00-12:59, a contract change on day 3, a common factor so breadth fires;
    plus drivers for two of them on round-the-clock minutes."""
    ts = np.concatenate([(pd.Timestamp(d) + pd.to_timedelta(540 + np.arange(240), unit="m")).values for d in DAYS])
    n = len(ts)
    rng = np.random.default_rng(seed)
    common = np.cumsum(rng.normal(trend, 0.8, n))
    series, drivers = {}, {}
    for k, fam in enumerate(("GOLD", "SILVER", "COPPER", "CRUDE", "NATGAS")):
        F = 1000 + common * (1 + 0.2 * k) + walk(seed + 10 + k, n, 0.4) - 1000
        seg = (pd.DatetimeIndex(ts).normalize() >= pd.Timestamp(DAYS[2])).astype(int)
        F = F + seg * 15.0                                                        # a roll jump on day 3
        series[fam] = S.Series(fam, ts, F, seg)
    dts = pd.date_range(DAYS[0] + " 00:00", DAYS[-1] + " 23:59", freq="min")
    for fam in ("GOLD", "CRUDE"):
        drivers[fam] = (dts.values, 50 + np.cumsum(np.random.default_rng(seed + 99).normal(0, 0.05, len(dts))))
    return series, drivers


def pipeline(series, drivers, cut=None, perturb=False):
    """Signals of every bar ending at or before `cut`: {(fam, day, start): (A, B, C)}."""
    rng = np.random.default_rng(7)
    sub, drv = {}, {}
    for f, s in series.items():
        ts, F, seg = s.ts, s.F.copy(), s.seg
        if cut is not None:
            after = ts > cut
            if perturb:
                F[after] = F[after] * (1 + rng.normal(0, 0.02, after.sum()))
            else:
                ts, F, seg = ts[~after], F[~after], seg[~after]
        sub[f] = S.Series(f, ts, F, seg)
    for f, (ts, c) in drivers.items():
        c = c.copy()
        if cut is not None:
            after = ts > cut
            if perturb:
                c[after] = c[after] * (1 + rng.normal(0, 0.02, after.sum()))
            else:
                ts, c = ts[~after], c[~after]
        drv[f] = R.driver_bars(ts, c)
    bars = {f: S.family_bars(s) for f, s in sub.items()}
    conf = S.intraday_confirm(bars, drv)
    out = {}
    for f, b in bars.items():
        for i in range(len(b)):
            out[(f, b.day.values[i], int(b.start.values[i]))] = (int(conf["I-A"][f][i]), int(conf["I-B"][f][i]),
                                                                 int(conf["I-C"][f][i]), float(b.chi.values[i]))
    return out, bars


def check(full, other, cut):
    bad = []
    for k, v in other.items():
        f, day, start = k
        end = pd.Timestamp(day) + pd.Timedelta(minutes=start + K.BAR_MIN - 1)
        if end <= cut:
            w = full.get(k)
            if w is None or w[:3] != v[:3] or not (np.isnan(w[3]) and np.isnan(v[3]) or w[3] == v[3]):
                bad.append((k, w, v))
    return bad


@pytest.mark.parametrize("cut", ["2026-07-01 11:07", "2026-07-02 09:44", "2026-07-03 10:02", "2026-07-06 12:31"])
@pytest.mark.parametrize("perturb", [False, True])
def test_intraday_signals_do_not_look_ahead(cut, perturb):
    series, drivers = make_market(seed=3, trend=0.05)
    full, bars = pipeline(series, drivers)
    assert sum(v[0] != 0 for v in full.values()) > 20                 # the test has breaks to check
    assert sum(v[1] != 0 for v in full.values()) > 5                  # and breadth confirmations
    cut = np.datetime64(pd.Timestamp(cut))
    other, _ = pipeline(series, drivers, cut, perturb)
    # a bar is compared only when complete in the cut data: its last minute <= cut and the minute after not needed
    complete = {k: v for k, v in other.items()
                if pd.Timestamp(k[1]) + pd.Timedelta(minutes=k[2] + K.BAR_MIN - 1) <= pd.Timestamp(cut)}
    assert complete and not check(full, complete, pd.Timestamp(cut))


def test_guard_catches_a_leaky_signal():
    series, drivers = make_market(seed=3, trend=0.05)
    cut = pd.Timestamp("2026-07-02 11:02")

    def leaky(series_):
        out = {}
        for f, s in series_.items():
            b = S.family_bars(s)
            nxt = np.r_[b.side.values[1:], 0]                       # uses the NEXT bar: look-ahead
            for i in range(len(b)):
                out[(f, b.day.values[i], int(b.start.values[i]))] = (int(nxt[i]), 0, 0, 0.0)
        return out

    full = leaky(series)
    cut64 = np.datetime64(cut)
    trunc = {f: S.Series(f, s.ts[s.ts <= cut64], s.F[s.ts <= cut64], s.seg[s.ts <= cut64]) for f, s in series.items()}
    assert check(full, leaky(trunc), cut)                           # caught


@pytest.mark.parametrize("cut_row", [60, 140, 233])
@pytest.mark.parametrize("perturb", [False, True])
def test_daily_signals_do_not_look_ahead(cut_row, perturb):
    rng = np.random.default_rng(11)
    n = 260
    fams = {}
    for k, f in enumerate(("GOLD", "SILVER", "COPPER", "ZINC", "ALUMINIUM")):
        c = 100 + np.cumsum(rng.normal(0.05, 1, n))
        oi = np.full(n, 1000.0)
        oi[[40, 90, 150, 200]] = 3000.0                             # rolls
        c[40:] += 3
        fams[f] = pd.DataFrame({"date": pd.bdate_range("2020-01-01", periods=n), "open": c + rng.normal(0, .2, n),
                                "high": c + 1, "low": c - 1, "close": c, "oi": oi})

    def run(cut=None):
        d = {}
        for f, x in fams.items():
            x = x.copy()
            if cut is not None:
                if perturb:
                    for col in ("open", "high", "low", "close"):
                        x.loc[cut + 1:, col] *= 1 + rng.normal(0, 0.03, n - cut - 1)
                    x.loc[cut + 1:, "oi"] *= rng.uniform(0.3, 3, n - cut - 1)
                else:
                    x = x.iloc[:cut + 1]
            d[f] = R.daily_series(x)
        conf = S.daily_confirm(d)
        return d, conf

    full, fc = run()
    assert sum(int((v.side != 0).sum()) for v in full.values()) > 20
    part, pc = run(cut_row)
    for f in fams:
        a, b = full[f].iloc[:cut_row + 1], part[f].iloc[:cut_row + 1]
        assert list(a.roll) == list(b.roll)
        assert list(a.side) == list(b.side)
        np.testing.assert_allclose(a.atr.values, b.atr.values, equal_nan=True)
        assert list(fc["D-B"][f][:cut_row + 1]) == list(pc["D-B"][f][:cut_row + 1])
