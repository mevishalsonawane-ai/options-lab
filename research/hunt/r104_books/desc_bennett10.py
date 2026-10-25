"""R104 descriptive (bennett-10, not a test): does the 09:20 skew add anything to ATM IV in explaining the rest-of-day
and next-day range? OLS of range (% of spot) on ATM IV, then on ATM IV + skew; HAC-free t-stats (daily, labelled).

    python research/hunt/r104_books/desc_bennett10.py      # results/desc_bennett10.json
"""
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import f8_vol as V  # noqa: E402
import r104cfg as K  # noqa: E402


def ols(y, X):
    X = np.c_[np.ones(len(y)), X]
    b, *_ = np.linalg.lstsq(X, y, rcond=None)
    e = y - X @ b
    s2 = e @ e / (len(y) - X.shape[1])
    se = np.sqrt(np.diag(s2 * np.linalg.inv(X.T @ X)))
    r2 = 1 - (e @ e) / ((y - y.mean()) @ (y - y.mean()))
    return b.tolist(), (b / se).tolist(), float(r2)


out = {}
for und in ("NIFTY", "BANKNIFTY"):
    ch = V.chain(und)
    rows = []
    for i, d in enumerate(ch.dates[:-1]):
        D = ch.D(d)
        iv, k0 = V.atm_iv(ch, D, d, 5)
        if iv != iv:
            continue
        ivp = V.leg_iv(ch, D, d, "P", k0 - 5 * ch.step, 5)
        ivc = V.leg_iv(ch, D, d, "C", k0 + 5 * ch.step, 5)
        if ivp != ivp or ivc != ivc:
            continue
        S = V.spot_at(D, 5)
        rest = (np.nanmax(D.idx[1, 5:]) - np.nanmin(D.idx[2, 5:])) / S
        Dn = ch.D(ch.dates[i + 1])
        nxt = (np.nanmax(Dn.idx[1]) - np.nanmin(Dn.idx[2])) / S
        rows.append((iv, ivp - ivc, rest, nxt))
    a = np.array(rows)
    res = {}
    for j, name in ((2, "rest_of_day"), (3, "next_day")):
        b1, t1, r1 = ols(a[:, j], a[:, [0]])
        b2, t2, r2 = ols(a[:, j], a[:, [0, 1]])
        res[name] = dict(n=len(a), iv_only=dict(coef=b1, t=t1, r2=r1), iv_and_skew=dict(coef=b2, t=t2, r2=r2))
    res["corr_iv_skew"] = float(np.corrcoef(a[:, 0], a[:, 1])[0, 1])
    out[und] = res
    print(und, json.dumps(res)[:400], flush=True)
(K.RESULTS / "desc_bennett10.json").write_text(json.dumps(out, indent=1))
