"""Fixtures for OptionMath: straight from opengreeks and option_greeks_service."""
import datetime as dt
import random

import numpy as np
from opengreeks import black76 as b

from common import IST, dump, frozen_datetime, identity_round, patched
import services.option_greeks_service as ogs

rng = random.Random(7)
out = {"black": [], "iv": [], "calculate_greeks": [], "time": [], "chain_greeks": [], "expiry_cutoff": {}}

# 1. Model values across a grid (moneyness, tenor, rate, vol, flag).
for F in (24350.0, 51234.5, 83.25, 72000.0):
    for m in (0.8, 0.95, 1.0, 1.02, 1.2):
        K = round(F * m, 2)
        for t in (0.5 / 365, 3 / 365, 30 / 365, 1.0):
            for r in (0.0, 0.065):
                for s in (0.08, 0.14, 0.35, 1.2):
                    for flag in "cp":
                        out["black"].append({
                            "flag": flag, "F": F, "K": K, "t": t, "r": r, "sigma": s,
                            "price": b.black(flag, F, K, t, r, s), "delta": b.delta(flag, F, K, t, r, s),
                            "gamma": b.gamma(flag, F, K, t, r, s), "theta": b.theta(flag, F, K, t, r, s),
                            "vega": b.vega(flag, F, K, t, r, s), "rho": b.rho(flag, F, K, t, r, s),
                        })

# 2. Implied volatility: round trips plus every failure / clamp edge.
def iv_case(price, F, K, r, t, flag, sigma=None):
    try:
        iv = b.implied_volatility(price, F, K, r, t, flag)
        # A deep ITM, near-expiry price whose time value is below double precision
        # carries no volatility information: opengreeks returns noise there too.
        if sigma is not None and abs(iv - sigma) > 1e-7 * sigma:
            return
        out["iv"].append({"price": price, "F": F, "K": K, "r": r, "t": t, "flag": flag, "iv": iv})
    except Exception as e:
        if sigma is not None:  # a model price a rounding error under intrinsic: noise, not a case
            return
        out["iv"].append({"price": price, "F": F, "K": K, "r": r, "t": t, "flag": flag, "error": str(e)})

for c in out["black"][::3]:
    if c["price"] > 1e-7:
        iv_case(c["price"], c["F"], c["K"], c["r"], c["t"], c["flag"], c["sigma"])
F, t, r = 24350.0, 11 / 365, 0.065
df = np.exp(-r * t)
for p in (0.0, 1e-9, 0.05, 0.5, 169.0, 5000.0, 8109.050432012109 + 1, df * F - 1, df * F + 1, 30000.0):
    iv_case(p, F, 24500.0, r, t, "c")
for p in (df * 350 - 1e-6, df * 350 + 1e-6, 350.0, 360.0, df * 24000 - 1, df * 24000 + 1):
    iv_case(p, F, 24000.0, r, t, "c")
    iv_case(p, F, 24700.0, r, t, "p")
iv_case(100.0, 0.0, 24400.0, r, t, "c")
iv_case(-1.0, F, 24400.0, r, t, "c")

# 3. calculate_greeks end to end, with the clock frozen.
NOW = dt.datetime(2025, 11, 18, 10, 17, 23, 500000, tzinfo=IST)
cases = [
    ("NIFTY25NOV2524300CE", "NFO", 24351.4, 132.6, None),
    ("NIFTY25NOV2524300PE", "NFO", 24351.4, 81.05, None),
    ("NIFTY25NOV2525500CE", "NFO", 24351.4, 1.2, None),
    ("NIFTY25NOV2523000PE", "NFO", 24351.4, 0.65, 6.5),
    ("NIFTY25NOV2523000CE", "NFO", 24351.4, 1351.4, None),   # no time value: theoretical
    ("NIFTY25NOV2523000CE", "NFO", 24351.4, 1351.405, None),  # < 0.01 of it: theoretical
    ("NIFTY25NOV2523000CE", "NFO", 24351.4, 1360.0, 10.0),
    ("NIFTY25NOV2525000PE", "NFO", 24351.4, 30000.0, None),  # above maximum: error
    ("BANKNIFTY30DEC2552000PE", "NFO", 51850.0, 1210.5, 7.0),
    ("SENSEX20NOV2581000CE", "BFO", 80900.0, 210.0, None),
    ("USDINR26NOV2588.5CE", "CDS", 88.61, 0.1325, None),
    ("CRUDEOIL17NOV255300CE", "MCX", 5320.0, 12.0, None),    # expired
    ("CRUDEOIL16DEC255300CE", "MCX", 5320.0, 212.0, None),
    ("GOLD26DEC25128000PE", "MCX", 127500.0, 2800.0, None),
    ("NIFTY18NOV2524300CE", "NFO", 24351.4, 60.0, None),     # expiry day
    ("NIFTY25NOV2524300CE", "NFO", 24351.4, 0.0, None),      # no price: error
]
Frozen = frozen_datetime(NOW)
with patched(ogs, "datetime", Frozen):
    for sym, ex, spot, px, rate in cases:
        ok, resp, code = ogs.calculate_greeks(sym, ex, spot, px, interest_rate=rate)
        with patched(ogs, "round", identity_round):
            ok2, raw, _ = ogs.calculate_greeks(sym, ex, spot, px, interest_rate=rate)
        out["calculate_greeks"].append({"symbol": sym, "exchange": ex, "spot": spot, "price": px, "rate": rate,
                                        "ok": ok, "code": code, "response": resp, "raw": raw})
    # expiry-day, near-cutoff and expired times
    for now in (NOW, dt.datetime(2025, 11, 25, 15, 0, tzinfo=IST), dt.datetime(2025, 11, 25, 15, 30, tzinfo=IST),
                dt.datetime(2025, 11, 25, 15, 30, 1, tzinfo=IST), dt.datetime(2025, 11, 24, 23, 59, 59, tzinfo=dt.timezone.utc)):
        for code_, ex in (("25NOV25", "NFO"), ("25NOV25", "MCX"), ("25NOV25", "CDS"), ("26DEC25", "NFO")):
            with patched(ogs, "datetime", frozen_datetime(now)):
                exp = ogs.get_expiry_datetime(code_, ex)
                y, d = ogs.calculate_time_to_expiry(exp)
            out["time"].append({"now": now.isoformat(), "expiry": code_, "exchange": ex, "years": y, "days": d})
for ex in ("NFO", "BFO", "MCX", "CDS", "NSE"):
    out["expiry_cutoff"][ex] = "%02d:%02d" % ogs.get_exchange_expiry_time(ex)

# 4. The vectorised chain path the option chain uses.
strikes = [24000.0 + 50 * i for i in range(15)]
Fc, tc = 24380.25, 6.25 / 365
ce, pe = [], []
for k in strikes:
    s = 0.12 + 0.25 * ((k - Fc) / Fc) ** 2 * 100
    ce.append(round(b.black("c", Fc, k, tc, 0.0, s) + rng.uniform(-0.5, 0.5), 2))
    pe.append(round(b.black("p", Fc, k, tc, 0.0, s) + rng.uniform(-0.5, 0.5), 2))
ce[0] = round(Fc - strikes[0] - 0.5, 2)   # below intrinsic -> theoretical
pe[-1] = None                             # unquoted
ce[3] = 0                                 # zero price
for rate in (None, 6.5):
    cg, pg = ogs.calculate_chain_greeks(strikes, ce, pe, Fc, tc, rate)
    out["chain_greeks"].append({"strikes": strikes, "ce": ce, "pe": pe, "forward": Fc, "t": tc, "rate": rate, "ce_greeks": cg, "pe_greeks": pg})

dump("greeks.json", out)
