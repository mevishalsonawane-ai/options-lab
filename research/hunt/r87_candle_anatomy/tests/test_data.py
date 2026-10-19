import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import cfg
from research.hunt.r87_candle_anatomy import data as D


def _opt():
    t0 = pd.Timestamp("2025-01-02 09:15")
    rows = []
    for i, spot in enumerate([20010.0, 20060.0]):            # ATM moves from 20000 to 20050
        ts = t0 + pd.Timedelta(minutes=i)
        atm = round(spot / 50) * 50
        for off in (-1, 0, 1):
            for side, oi in (("C", 1000.0), ("P", 400.0)):
                k = atm + 50 * off
                rows.append(dict(ts=ts, offset=off, side=side, o=100 + i, h=101 + i, l=99 + i, c=100.5 + i, iv=15.0,
                                 v=10.0 * (off == 0) + 1, strike=k, oi=oi, spot=spot))
    return pd.DataFrame(rows)


def test_chain_minute_uses_atm_pair_only():
    ch = D.chain_minute(_opt()).set_index("ts")
    assert (ch.act == 22).all()                                # ATM C + P volume (11 each), not the 6 series
    assert (ch.coi == 1000).all() and (ch.poi == 400).all()
    assert list(ch.atm) == [20000.0, 20050.0] and np.allclose(ch.straddle, [201.0, 203.0])


def test_option_day_stitches_by_strike():
    con = D.option_day(_opt())
    A = con[("C", 20050.0)]                                    # offset +1 at minute 0, ATM at minute 1
    assert A.shape == (5, cfg.NROWS) and A[3, 0] == 100.5 and A[3, 1] == 101.5 and np.isnan(A[3, 2])
    assert ("P", 19950.0) in con and np.isnan(con[("P", 19950.0)][3, 1])


def test_gex_tilt_sign():
    o = _opt()
    g = D.gex_sign_proxy(o[o.offset == 0])
    assert (g.gex_ratio > 0).all()                            # call OI > put OI at ATM -> positive tilt
    o2 = o.assign(oi=np.where(o.side == "C", 100.0, 900.0))
    assert (D.gex_sign_proxy(o2[o2.offset == 0]).gex_ratio < 0).all()


def test_clean_sessions_drops_frozen_and_weekend_days():
    rows = []
    for d, flat, n in (("2021-08-06", False, 375), ("2021-08-07", False, 375), ("2021-08-09", True, 375),
                       ("2021-08-10", False, 60)):
        for r in range(n):
            ts = pd.Timestamp(d) + pd.Timedelta(minutes=cfg.OPEN_MIN + r)
            px = 100.0 if flat else 100.0 + r % 3
            rows.append(dict(ts=ts, o=px, h=px + (0 if flat else 1), l=px, c=px))
    out = D.clean_sessions(pd.DataFrame(rows))
    assert sorted(out.ts.dt.date.astype(str).unique()) == ["2021-08-06"]
