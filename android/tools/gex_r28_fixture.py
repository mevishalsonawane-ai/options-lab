"""The R28 GEX parity fixture for ira-core's GammaMorningTest (14 Oct 2026): three one-minute chains judged by
research/brain/gex.py session() (R28's one GEX core), written as the text the test reads.

    python3 android/tools/gex_r28_fixture.py . > android/ira-core/src/test/resources/gex/r28_parity.txt
"""
import sys
from datetime import date
import numpy as np, pandas as pd
sys.path.insert(0, sys.argv[1])
from research.brain import gex, bs, config


def case(name, spot, step, center, ivc, oi_c, oi_p, day, expiry, drop=(), stale=(), tiny=(), zero_oi=()):
    rows = []
    T = gex.years_to_expiry(day, np.array([44]), expiry)[0]
    for o in range(-10, 11):
        k = center + o * step
        for side in ("C", "P"):
            key = (o, side)
            if key in drop:
                continue
            iv = ivc + 0.0006 * abs(o) ** 2 + (0.01 if side == "P" and o < 0 else 0.0)
            px = float(bs.price(spot, k, T, iv, side == "C", config.RISK_FREE, config.DIV_YIELD))
            px = round(max(px, 0.05) / 0.05) * 0.05
            if key in tiny:
                px = 0.05
            age = 45.0 if key in stale else 1.0
            oi = 0.0 if key in zero_oi else float((oi_c if side == "C" else oi_p)(o))
            rows.append(dict(minute=44, offset=o, side=side, strike=float(k), px=round(px, 2), age=age, oi=oi))
    df = pd.DataFrame(rows)
    sp = np.full(375, np.nan)
    sp[44] = spot
    out = gex.session(df, sp, day, expiry).iloc[44]
    conv = lambda v: None if pd.isna(v) else float(v)
    return dict(name=name, spot=spot, day=str(day), expiry=str(expiry),
                options=[dict(strike=r["strike"], right="CE" if r["side"] == "C" else "PE", ltp=r["px"], age=r["age"], oi=int(r["oi"])) for r in rows],
                expect={k: conv(out[k]) for k in ("net_cr", "gross_cr", "n_contracts", "flip_dist_pct", "wall_pos_strike", "wall_neg_strike")})


d, e = date(2026, 10, 14), date(2026, 10, 20)
cases = [
    case("nifty-positive", 25012.3, 50, 25000, 0.12, lambda o: 400000 + 90000 * max(0, 6 - abs(o - 3)) * 3,
         lambda o: 350000 + 60000 * max(0, 6 - abs(o + 4)) * 2, d, e,
         stale={(7, "C"), (-8, "P")}, tiny={(10, "C")}, zero_oi={(9, "P")}, drop={(-10, "C")}),
    case("banknifty-negative", 54980.0, 100, 55000, 0.14, lambda o: 60000 + 9000 * max(0, 5 - abs(o - 2)),
         lambda o: 120000 + 40000 * max(0, 6 - abs(o + 1)) * 2, d, date(2026, 10, 28)),
    case("expiry-day", 25040.0, 50, 25050, 0.11, lambda o: 900000 if o == 2 else 300000, lambda o: 800000 if o == -3 else 280000, e, e),
]
out = ["# R28 GEX parity fixture: made by research/brain/gex.py session() (one minute, row 44 = the bar closing 10:00), 14 Oct 2026.",
       "# case <name> <spot> <day> <expiry>; opt <strike> <right> <ltp> <age minutes> <oi>; expect <key> <value or NaN>"]
for c in cases:
    out.append(f"case {c['name']} {c['spot']!r} {c['day']} {c['expiry']}")
    for o in c["options"]:
        out.append(f"opt {o['strike']!r} {o['right']} {o['ltp']!r} {o['age']!r} {o['oi']}")
    for k, v in c["expect"].items():
        out.append(f"expect {k} {'NaN' if v is None else repr(v)}")
print("\n".join(out))
