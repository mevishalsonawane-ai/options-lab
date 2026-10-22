"""R102 parts B: the 70 pre-registered predictive tests (PREREG.md), design first, the holdout once.

    python research/hunt/r102_deep/r102pred.py design     # results/pred_design.json
    python research/hunt/r102_deep/r102pred.py holdout    # ONE read: writes results/HOLDOUT_READ, then pred_holdout.json

Every test is a sign rule with no fitted parameter: effect = mean next return (bps of the index) signed by the rule,
95% CI, t-stat, the share of calendar years with the same sign (walk-forward), 1,000 random twins (the rule on a
shuffled or circularly shifted signal; p = share of twins with |effect| at least as large), BH q over all 70.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r102data as D  # noqa: E402
from r102desc import HORIZONS_MIN, bar_returns, daily_frame, participant_signals  # noqa: E402

TWINS = 1000
SEED = 102
BH_Q = 0.10
LEVEL_WINDOW, LEVEL_MIN = 250, 120   # trailing median of PAST days for the level rules
MIN_SHIFT = 10              # a circular shift of at least this many rows keeps the twin's autocorrelation but breaks the link
# Cost of expressing a daily-horizon call in one ATM option, in bps of the index: R16's round trip (research/knowledge/
# baselines.yaml: NIFTY Rs 98, BANKNIFTY Rs 133 per lot, charges and spread) through an ATM delta of 0.5 and the lot.
ROUND_TRIP_RS = {"NIFTY": 98.0, "BANKNIFTY": 133.0}
LOT = {"NIFTY": 65, "BANKNIFTY": 35}      # research/obuy/config.py LOT_SCHEDULE, lots in force in 2026


def cost_bps(und: str, spot: float) -> float:
    return ROUND_TRIP_RS[und] / (LOT[und] * 0.5) / spot * 1e4


def summarise(signed: np.ndarray, years: np.ndarray, twin_effects: np.ndarray) -> dict:
    """signed: per-observation signed next return in bps (positive = the rule was right)."""
    signed = np.asarray(signed, dtype=float)
    ok = np.isfinite(signed)
    signed, years = signed[ok], np.asarray(years)[ok]
    n = len(signed)
    if n < 20:
        return {"n": int(n), "effect_bps": None, "p_twin": None}
    eff = signed.mean()
    se = signed.std(ddof=1) / np.sqrt(n)
    by_year = {}
    for y in sorted(set(years)):
        s = signed[years == y]
        if len(s) >= 10:
            by_year[int(y)] = {"n": int(len(s)), "effect_bps": round(float(s.mean()), 3)}
    same = [v["effect_bps"] * np.sign(eff) > 0 for v in by_year.values()]
    p_twin = float((np.abs(twin_effects) >= abs(eff)).mean()) if len(twin_effects) else None
    return {"n": int(n), "effect_bps": round(float(eff), 3), "ci95_bps": [round(float(eff - 1.96 * se), 3), round(float(eff + 1.96 * se), 3)],
            "t": round(float(eff / se), 2) if se > 0 else None, "hit_rate": round(float((signed > 0).mean()), 4),
            "years_same_sign": f"{int(sum(same))}/{len(same)}", "by_year": by_year, "p_twin": p_twin,
            "twin_sd_bps": round(float(np.std(twin_effects)), 3) if len(twin_effects) else None}


def shift_twins(rng: np.random.Generator, sig: np.ndarray, ret: np.ndarray, n_twins: int) -> np.ndarray:
    """Circular shifts of the signal against the returns (keeps both series' own autocorrelation)."""
    n = len(sig)
    if n < 2 * MIN_SHIFT + 1:
        return np.array([])
    out = np.empty(n_twins)
    for i in range(n_twins):
        k = int(rng.integers(MIN_SHIFT, n - MIN_SHIFT))
        s = np.roll(sig, k)
        out[i] = np.nanmean(s * ret)
    return out


# ---- B1 trend vs mean reversion by horizon --------------------------------------------------------------------------

def b1(und: str, part: str, rng: np.random.Generator) -> list[dict]:
    tests = []
    m = D.cut(D.minutes(und), part)
    for k, h in HORIZONS_MIN.items():
        rr = bar_returns(m, h) * 100   # bps
        prev = rr.groupby(level=0).shift(1)
        j = pd.DataFrame({"r": rr, "p": prev}).dropna()
        sig = np.sign(j.p.values)
        signed = sig * j.r.values
        years = np.array([d.year for d in j.index.get_level_values(0)])
        # twins: the previous-bar sign circularly shifted against the returns (amendment 1: a within-day shuffle could
        # hand a twin the sign of the bar's OWN return, which is look-ahead; worst at 1h with only 5 pairs a day)
        days = j.index.get_level_values(0).values
        tw = shift_twins(rng, sig, j.r.values, TWINS)
        res = summarise(signed, years, tw)
        res.update({"id": f"B1-{und}-{k}", "group": "B1", "und": und, "horizon": k,
                    "rule": f"follow the previous {k} bar's sign (negative effect = reversal)", "first_day": str(min(days)), "last_day": str(max(days))})
        tests.append(res)
    d = daily_frame(und, part)
    for k, ser, yrs in (("1d", d.ret.dropna() * 100, None), ("1w", d.close.resample("W-FRI").last().pct_change().dropna() * 1e4, None)):
        prev = ser.shift(1)
        j = pd.DataFrame({"r": ser, "p": prev}).dropna()
        sig, ret = np.sign(j.p.values), j.r.values
        years = j.index.year.values
        tw = shift_twins(rng, sig, ret, TWINS)
        res = summarise(sig * ret, years, tw)
        res.update({"id": f"B1-{und}-{k}", "group": "B1", "und": und, "horizon": k,
                    "rule": f"follow the previous {k} return's sign (negative effect = reversal)", "first_day": str(j.index.min().date()), "last_day": str(j.index.max().date())})
        tests.append(res)
    return tests


# ---- B2 participants -------------------------------------------------------------------------------------------------

def fwd_returns(und: str, part: str) -> pd.DataFrame:
    d = D.daily(und)
    out = pd.DataFrame(index=d.index)
    out["r1"] = (d.close.shift(-1) / d.close - 1) * 1e4
    out["r5"] = (d.close.shift(-5) / d.close - 1) * 1e4
    out["day"] = out.index.date
    # the forward return must end inside the period (no peeking past the design end)
    end = D.DESIGN_END if part == "design" else D.HOLDOUT_END
    idx = np.searchsorted(d.index.values, np.datetime64(pd.Timestamp(end)), side="right")
    lim1 = d.index[min(idx - 1 - 1, len(d) - 1)] if idx >= 2 else d.index[0]
    lim5 = d.index[min(idx - 1 - 5, len(d) - 1)] if idx >= 6 else d.index[0]
    out.loc[out.index > lim1, "r1"] = np.nan
    out.loc[out.index > lim5, "r5"] = np.nan
    return D.cut(out, part)


def participant_rules() -> dict[str, pd.Series]:
    """Signal series (date index): +1 / -1 / 0. Level rules compare with the trailing-250-day median of PAST days only."""
    s = participant_signals()
    rules = {}
    for g in ("FII", "Client", "Pro"):
        x = s[f"{g}_fut_net_share"]
        med = x.shift(1).rolling(LEVEL_WINDOW, min_periods=LEVEL_MIN).median()
        rules[f"{g}-fut-level"] = np.sign(x - med)
        rules[f"{g}-fut-change"] = np.sign(x.diff())
    x = s["FII_opt_net_share"]
    med = x.shift(1).rolling(LEVEL_WINDOW, min_periods=LEVEL_MIN).median()
    rules["FII-opt-level"] = np.sign(x - med)
    rules["FII-opt-change"] = np.sign(x.diff())
    f = D.fpi()
    rules["FPI-cash-sign"] = np.sign(f.eq_exch_net_cr)
    return rules


def b2(part: str, rng: np.random.Generator) -> list[dict]:
    tests = []
    rules = participant_rules()
    for und in D.UNDS:
        fr = fwd_returns(und, part)
        for name, sig in rules.items():
            for h in ("r1", "r5"):
                j = pd.DataFrame({"s": sig, "r": fr[h]}).dropna()
                j = j[j.s != 0]
                if len(j) < 20:
                    continue
                sg, ret = j.s.values, j.r.values
                tw = shift_twins(rng, sg, ret, TWINS)
                res = summarise(sg * ret, j.index.year.values, tw)
                res.update({"id": f"B2-{und}-{name}-{h}", "group": "B2", "und": und, "horizon": {"r1": "1d", "r5": "5d"}[h],
                            "rule": f"go with the sign of {name} (participant file of day t; return from close t to close t+{h[1]})",
                            "first_day": str(j.index.min().date()), "last_day": str(j.index.max().date())})
                tests.append(res)
    return tests


# ---- B3 calendar -----------------------------------------------------------------------------------------------------

def _dayset_test(ret: pd.Series, mask: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Mean return on the masked days; twins = random day sets of the same size."""
    r = ret.values
    k = int(mask.sum())
    tw = np.array([r[rng.choice(len(r), k, replace=False)].mean() for _ in range(TWINS)]) if k >= 20 else np.array([])
    return r[mask], ret.index.year.values[mask], tw


def b3(und: str, part: str, rng: np.random.Generator) -> list[dict]:
    tests = []
    d = daily_frame(und, part).dropna(subset=["ret"])
    ret = d.ret * 100
    for w in ("Mon", "Tue", "Wed", "Thu", "Fri"):
        mask = (d.wd == w).values
        s, y, tw = _dayset_test(ret, mask, rng)
        res = summarise(s, y, tw)
        res.update({"id": f"B3-{und}-{w}", "group": "B3", "und": und, "horizon": "1d", "rule": f"{w} close-to-close return (sign = direction)",
                    "first_day": str(d.day.min()), "last_day": str(d.day.max())})
        tests.append(res)
    exp = D.expiries(und)
    since = d[d.day >= exp.day.min()]
    exp_days = set(exp[exp.is_expiry].day)
    mask = since.day.isin(exp_days).values
    s, y, tw = _dayset_test(since.ret * 100, mask, rng)
    res = summarise(s, y, tw)
    res.update({"id": f"B3-{und}-expiry", "group": "B3", "und": und, "horizon": "1d", "rule": "expiry-day close-to-close return",
                "first_day": str(since.day.min()), "last_day": str(since.day.max())})
    tests.append(res)
    us = D.event_sessions(["FOMC", "USCPI"])
    span = d[(d.day >= min(us)) & (d.day <= max(us))]
    mask = span.day.isin(us).values
    s, y, tw = _dayset_test(span.ret * 100, mask, rng)
    res = summarise(s, y, tw)
    res.update({"id": f"B3-{und}-us-event", "group": "B3", "und": und, "horizon": "1d", "rule": "the NSE session after a FOMC decision or a US CPI print",
                "first_day": str(span.day.min()), "last_day": str(span.day.max())})
    tests.append(res)
    return tests


# ---- B4 gap fade -----------------------------------------------------------------------------------------------------

def b4(und: str, part: str, rng: np.random.Generator) -> list[dict]:
    """Gap fade: 2 thresholds x gap up / gap down x index = 8 tests (amendment 1: the pooled 4 were run first)."""
    tests = []
    d = daily_frame(und, part).dropna(subset=["gap", "oc"])
    for thr in (0.3, 0.6):
        for side, mask in (("up", d.gap >= thr), ("down", d.gap <= -thr)):
            x = d[mask]
            sig, ret = -np.sign(x.gap.values), x.oc.values * 100
            tw = shift_twins(rng, sig, ret, TWINS)
            res = summarise(sig * ret, x.index.year.values, tw)
            res.update({"id": f"B4-{und}-gap{side}{thr}", "group": "B4", "und": und, "horizon": "open-to-close",
                        "rule": f"fade a gap {side} of at least {thr}% (positive effect = the day moves back toward the previous close)",
                        "first_day": str(x.day.min()) if len(x) else None, "last_day": str(x.day.max()) if len(x) else None})
            tests.append(res)
    return tests


# ---- run -------------------------------------------------------------------------------------------------------------

def run(part: str) -> dict:
    rng = np.random.default_rng(SEED)
    tests = []
    for und in D.UNDS:
        tests += b1(und, part, rng)
    tests += b2(part, rng)
    for und in D.UNDS:
        tests += b3(und, part, rng)
    for und in D.UNDS:
        tests += b4(und, part, rng)
    ps = [t["p_twin"] if t.get("p_twin") is not None else 1.0 for t in tests]
    qs = D.bh(ps)
    spot = {u: float(D.cut(D.daily(u).assign(day=D.daily(u).index.date), "design").close.iloc[-1]) for u in D.UNDS}
    for t, q in zip(tests, qs):
        t["q_bh"] = round(q, 4)
        t["cost_bps_one_atm_option"] = round(cost_bps(t["und"], spot[t["und"]]), 2)
        ys = t.get("years_same_sign", "0/0").split("/")
        wf = int(ys[1]) > 0 and int(ys[0]) / int(ys[1]) >= 0.7
        t["pass_design"] = bool(t.get("p_twin") is not None and q <= BH_Q and wf)
        t["beats_cost"] = bool(t.get("effect_bps") is not None and abs(t["effect_bps"]) > t["cost_bps_one_atm_option"])
    return {"part": part, "n_tests": len(tests), "twins": TWINS, "seed": SEED, "bh_q": BH_Q,
            "passed_design": [t["id"] for t in tests if t["pass_design"]], "tests": tests}


def main(argv) -> int:
    part = argv[1] if len(argv) > 1 else "design"
    D.OUT.mkdir(parents=True, exist_ok=True)
    if part == "holdout":
        if D.HOLDOUT_FLAG.exists():
            print("the holdout was already read once (results/HOLDOUT_READ); PREREG.md allows no second read", file=sys.stderr)
            return 2
        D.HOLDOUT_FLAG.write_text(f"read on {date.today().isoformat()} by r102pred.py holdout\n")
    res = run(part)
    if part == "holdout":
        design = json.loads((D.OUT / "pred_design.json").read_text())
        passed = set(design["passed_design"])
        for t in res["tests"]:
            t["passed_design"] = t["id"] in passed
            t["confirmed"] = bool(t["passed_design"] and t.get("p_twin") is not None and t["p_twin"] <= 0.10
                                  and np.sign(t["effect_bps"] or 0) == np.sign(next(x["effect_bps"] for x in design["tests"] if x["id"] == t["id"]) or 0))
        res["confirmed"] = [t["id"] for t in res["tests"] if t["confirmed"]]
    (D.OUT / f"pred_{part}.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(f"{part}: {res['n_tests']} tests; passed design: {res['passed_design']}" + (f"; confirmed: {res.get('confirmed')}" if part == "holdout" else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
