import numpy as np

from research.brain import bs


def test_iv_round_trip_and_parity():
    rng = np.random.default_rng(0)
    n = 500
    S = rng.uniform(40000, 60000, n)
    K = S * rng.uniform(0.9, 1.1, n)
    T = rng.uniform(1 / 365 / 24, 60 / 365, n)
    sig = rng.uniform(0.08, 0.8, n)
    call = rng.random(n) < 0.5
    P = bs.price(S, K, T, sig, call, 0.065, 0.0)
    iv = bs.implied_vol(P, S, K, T, call, 0.065, 0.0, min_vega=0.05)
    tradable = P >= 0.10                         # the cube solves IV only from two ticks up (config.IV_MIN_PRICE)
    ok = np.isfinite(iv) & tradable
    assert ok.sum() / tradable.sum() > 0.95
    assert np.allclose(iv[ok], sig[ok], atol=1e-5)
    c = bs.price(S, K, T, sig, True, 0.065, 0.0)
    p = bs.price(S, K, T, sig, False, 0.065, 0.0)
    assert np.allclose(c - p, S - K * np.exp(-0.065 * T), atol=1e-6)


def test_iv_nan_outside_no_arbitrage_band():
    # below intrinsic, zero/negative price, expired
    assert np.isnan(bs.implied_vol([100.0], [50000.0], [49000.0], [0.01], [True])[0])
    assert np.isnan(bs.implied_vol([0.0], [50000.0], [51000.0], [0.01], [True])[0])
    assert np.isnan(bs.implied_vol([50.0], [50000.0], [50000.0], [0.0], [True])[0])


def test_greeks_match_finite_differences():
    S, K, T, s = 50000.0, 50500.0, 7 / 365, 0.15
    for call in (True, False):
        d, g, th, v = (float(x) for x in bs.greeks(S, K, T, s, call, 0.065, 0.0))
        f = lambda **kw: float(bs.price(kw.get("S", S), K, kw.get("T", T), kw.get("s", s), call, 0.065, 0.0))
        h = 1.0
        assert abs(d - (f(S=S + h) - f(S=S - h)) / (2 * h)) < 1e-4
        assert abs(g - (f(S=S + h) - 2 * f() + f(S=S - h)) / h ** 2) < 1e-6
        assert abs(v - (f(s=s + 1e-4) - f(s=s - 1e-4)) / 2e-4 / 100) < 1e-4
        assert abs(th - (f(T=T - 1 / 365) - f()) ) < 0.05 * abs(th) + 0.5
