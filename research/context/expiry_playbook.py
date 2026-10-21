"""I10 Expiry-day playbook: straddle decay by time, max pain (a re-check of R16: NOT a forecast), pin and the last hour,
and the pre-registered short-straddle checks X1-X4 with real costs (PREREG.md, I10).

    python -m research.context.expiry_playbook   -> results/expiry_playbook.json
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats

from . import config as C, data as D, lib as L

TIMES = {"09:20": 5, "10:00": 45, "11:00": 105, "12:00": 165, "13:00": 225, "14:00": 285, "14:30": 315, "15:00": 345, "15:20": 365}
SELL_AT = {"10:00": 45, "12:00": 165, "13:30": 255, "14:30": 315}
BACK = 365


def max_pain(oi: dict) -> float | None:
    ks = sorted({k for k, _ in oi})
    if len(ks) < 10:
        return None
    best, kb = None, None
    for x in ks:
        pain = sum(v * max(0.0, x - k) for (k, s), v in oi.items() if s == "C") + \
               sum(v * max(0.0, k - x) for (k, s), v in oi.items() if s == "P")
        if best is None or pain < best:
            best, kb = pain, x
    return kb


def max_oi(oi: dict, side: str) -> float | None:
    x = {k: v for (k, s), v in oi.items() if s == side}
    return max(x, key=x.get) if x else None


def straddle(s: dict, m: int) -> float:
    return s["C_c"][m] + s["P_c"][m]


def run():
    out, ps = {}, {}
    for und in C.UNDS:
        ss = D.load(und)
        d = D.daily(ss)
        hold, h0 = L.split(d)
        hset = set(d.day[hold])
        ex = [s for s in ss if s["is_expiry"] and "C_c" in s and np.isfinite(straddle(s, 5))]
        res = dict(expiry_sessions=len(ex), design=sum(s["day"] not in hset for s in ex), holdout=sum(s["day"] in hset for s in ex),
                   holdout_from=str(h0))
        # straddle decay, % of the 09:20 value
        dec = pd.DataFrame([{t: straddle(s, m) / straddle(s, 5) * 100 for t, m in TIMES.items()} for s in ex])
        res["straddle_pct_of_0920"] = {t: dict(median=round(float(dec[t].median()), 1), p25=round(float(dec[t].quantile(.25)), 1),
                                               p75=round(float(dec[t].quantile(.75)), 1)) for t in TIMES}
        # time value only (straddle minus intrinsic at that minute's index): the theta part, without the move
        def tv(s, m):
            return straddle(s, m) - abs(s["c"][m] - s["atm"])
        tvd = pd.DataFrame([{t: tv(s, m) / tv(s, 5) * 100 for t, m in TIMES.items()} for s in ex if tv(s, 5) > 0])
        res["time_value_pct_of_0920"] = {t: dict(median=round(float(tvd[t].median()), 1), p25=round(float(tvd[t].quantile(.25)), 1),
                                                 p75=round(float(tvd[t].quantile(.75)), 1)) for t in TIMES}
        res["straddle_0920_pct_of_spot"] = round(float(np.median([straddle(s, 5) / s["c"][5] * 100 for s in ex])), 3)
        # the same on the day BEFORE expiry, for comparison of decay speed
        # last hour: |14:30 -> 15:25| on expiry vs non-expiry sessions
        lh = (d.c1525 - d.c1430).abs() / d.c1430 * 100
        res["last_hour_abs_pct"] = dict(expiry=round(float(lh[d.is_expiry].median()), 3), other=round(float(lh[~d.is_expiry].median()), 3),
                                        n_expiry=int(d.is_expiry.sum()), n_other=int((~d.is_expiry).sum()),
                                        p_mw=float(stats.mannwhitneyu(lh[d.is_expiry].dropna(), lh[~d.is_expiry].dropna()).pvalue))
        res["day_range_pct"] = dict(expiry=round(float(d.range_pct[d.is_expiry].median()), 3), other=round(float(d.range_pct[~d.is_expiry].median()), 3))
        # max pain (09:20 OI) and the pin
        mp_rows = []
        for s in ex:
            oi = s.get("oi_open") or {}
            mp = max_pain(oi)
            close = s["c"][np.isfinite(s["c"])][-1]
            op = s["c"][5]
            kc, kp = max_oi(oi, "C"), max_oi(oi, "P")
            step = s["step"]
            near = lambda k: k is not None and abs(close - k) <= step / 2
            mp_rows.append(dict(err_mp=abs(close - mp) / op * 100 if mp else np.nan, err_open=abs(close - op) / op * 100,
                                pin_oi=near(kc) or near(kp), within_quarter_mp=(abs(close - mp) / op * 100 <= 0.25) if mp else False,
                                within_quarter_open=abs(close - op) / op * 100 <= 0.25))
        mp = pd.DataFrame(mp_rows)
        # by chance the close sits within half a step of SOME strike always; of one of 2 named strikes ~ 2 / strikes in the move
        res["max_pain"] = dict(n=int(mp.err_mp.notna().sum()), mean_err_close_vs_maxpain_pct=round(float(mp.err_mp.mean()), 3),
                               mean_err_close_vs_open_pct=round(float(mp.err_open.mean()), 3),
                               close_within_025pct_of_maxpain=round(float(mp.within_quarter_mp.mean() * 100), 1),
                               close_within_025pct_of_open=round(float(mp.within_quarter_open.mean() * 100), 1),
                               note="R16: max pain is not a forecast; this re-check uses the 09:20 OI of the release's +-1,000-point strikes")
        res["pin"] = dict(close_within_half_step_of_0920_max_oi_strike_pct=round(float(mp.pin_oi.mean() * 100), 1), n=len(mp))
        # X1-X4: short ATM straddle sold at T, bought back 15:20, costs both legs both ways
        xs = {}
        for t, m in SELL_AT.items():
            nets = []
            for s in ex:
                q = L.lot(und, s["day"])
                legs = []
                for side in ("C", "P"):
                    px, vol = s[f"{side}_c"], s[f"{side}_v"]
                    if not (np.isfinite(px[m]) and np.isfinite(px[BACK])):
                        legs = None
                        break
                    sell = float(L.FILLS.sell(np.array([px[m]]), np.array([vol[m - 5:m].sum() / q]))[0])
                    buy = float(L.FILLS.buy(np.array([max(px[BACK], 0.05)]), np.array([vol[BACK - 5:BACK].sum() / q]))[0])
                    ch = float(L.COSTS.charge(False, np.array([sell]), np.array([q]))[0] + L.COSTS.charge(True, np.array([buy]), np.array([q]))[0])
                    legs.append((sell - buy) * q - ch)
                if legs:
                    nets.append(dict(day=s["day"], net=sum(legs), hold=s["day"] in hset))
            n = pd.DataFrame(nets)
            r = {}
            for part, sel in (("design", ~n.hold), ("holdout", n.hold)):
                x = n[sel].net if len(n) else pd.Series(dtype=float)
                p = float(stats.ttest_1samp(x, 0, alternative="greater").pvalue) if len(x) >= 5 else float("nan")
                r[part] = dict(n=len(x), mean=round(float(x.mean()), 0) if len(x) else None, total=round(float(x.sum()), 0),
                               win_pct=round(float((x > 0).mean() * 100), 1) if len(x) else None,
                               worst=round(float(x.min()), 0) if len(x) else None, p=p)
            xs[t] = r
            ps[f"{und}:sell{t}"] = r["design"]["p"]
        res["short_straddle_to_1520"] = xs
        out[und] = res
    q = L.bh(ps)
    out["bh"] = {k: dict(p=ps[k], q=q[k]) for k in ps}
    out["confirmed"] = {k: out[k.split(":")[0]]["short_straddle_to_1520"][k.split("sell")[1]]["holdout"]["p"] < 0.10
                        for k, v in out["bh"].items() if v["q"] < C.BH_Q}
    L.save("expiry_playbook.json", out)
    return out


if __name__ == "__main__":
    import json
    print(json.dumps(run(), indent=1, default=str))
