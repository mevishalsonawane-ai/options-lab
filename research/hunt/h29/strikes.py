"""h29 test 3: which strike gives the most per rupee at the Liquidity 15+5 signal, using option PRICING at the signal
(extends h13/h16). Liquidity signals = h13's (h4 cached: BANKNIFTY, FINNIFTY, MIDCPNIFTY), arm exits unchanged,
app fills + dated charges + real half-spread (h24). Packs for ITM1 / ATM / OTM1 nearest.

    OBUY_CACHE=<scratch>/hunt/h29/cache flock <scratch>/obuy.lock python3 -I research/hunt/h29/strikes.py build
    OBUY_CACHE=<scratch>/hunt/h29/cache python3 -I research/hunt/h29/strikes.py pre|hold

Rules: R0 ITM1 always; R1 OTM1 when cheap1 is in its lowest tercile (edges per index, pre-holdout signals), else ITM1;
R2 the strike with the lowest own IV among ITM1 / ATM / OTM1. The candidate set is the incumbent's (ITM1 positions,
one at a time per book); the alternative strike is bought on the same signal.
"""
from __future__ import annotations

import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.engine import Execution, StrikeRule, positions, prepare_many  # noqa: E402
from obuy.strategies.liquidity import ARM_EXITS  # noqa: E402
from build import implied  # noqa: E402

OUT = os.path.join(C.CACHE, "h29")
H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "SENSEX": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042}
MONEY = (1, 0, -1)
EXE = Execution(expiry="skip")
BUDGET = 33000.0
pd.set_option("display.width", 250)


def signals():
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    b = b[b.und == "MIDCPNIFTY"]
    s = pd.concat([a, b], ignore_index=True)
    s["strike"] = np.nan
    return s


def build():
    sig = signals()
    jobs = [(sig, StrikeRule(money=mo, series="near"), EXE, 0, None, False) for mo in MONEY]
    packs = prepare_many(jobs)
    res = {}
    for mo, (pk, _) in zip(MONEY, packs):
        tr = pk.run(ARM_EXITS, EXE)
        res[mo] = tr
        print(mo, len(tr), round(tr.net.sum()), flush=True)
    with open(os.path.join(OUT, "strikes_trades.pkl"), "wb") as f:
        pickle.dump(res, f)


def own_iv(T):
    """IV of each trade's own contract at the signal bar, from its entry price (fill) vs F / tmin at sig_min."""
    out = np.full(len(T), np.nan)
    for u, g in T.groupby("und"):
        F = pd.read_parquet(os.path.join(OUT, f"feat_{u}.parquet"), columns=["day", "m", "F", "tmin", "cheap1"])
        F["day"] = F.day.dt.date
        x = g[["day", "sig_min"]].merge(F.rename(columns={"m": "sig_min"}).astype({"sig_min": int}),
                                       on=["day", "sig_min"], how="left")
        cp = np.where(g.side.values > 0, 1, -1)
        out[g.index.values] = implied(g.entry.values, x.F.values, g.strike.values.astype(float),
                                      x.tmin.values / (375 * 252), cp)
        T.loc[g.index, "cheap1"] = x.cheap1.values
    return out


def load():
    res = pickle.load(open(os.path.join(OUT, "strikes_trades.pkl"), "rb"))
    inc = positions(res[1], one_at_a_time=True)
    keys = ["und", "day", "sig_min", "side", "book"]
    cols = keys + ["strike", "entry", "exit", "qty", "lot", "gross", "charges", "net"]
    T = inc[cols].copy()
    for mo, nm in ((0, "atm"), (-1, "otm1")):
        o = res[mo][cols].rename(columns={c: f"{c}_{nm}" for c in cols if c not in keys})
        T = T.merge(o, on=keys, how="left")
    T = T.reset_index(drop=True)
    for sfx in ("", "_atm", "_otm1"):
        hs = T.und.map(HS).values
        T["net_s" + sfx] = T["net" + sfx] - np.maximum(hs - 0.0005, 0) * (T["entry" + sfx] + T["exit" + sfx]) * T["qty" + sfx]
        T["prem" + sfx] = T["entry" + sfx] * T["qty" + sfx]
        T["lots" + sfx] = np.maximum(1, np.floor(BUDGET / T["prem" + sfx]))
    T["iv_itm1"] = own_iv(T)
    for sfx in ("_atm", "_otm1"):
        tmp = T[["und", "day", "sig_min", "side"]].copy()
        tmp["entry"], tmp["strike"] = T["entry" + sfx], T["strike" + sfx]
        T["iv" + sfx] = own_iv(tmp)
    T["dts"] = pd.to_datetime(T.day)
    return T


def apply_rules(T, E):
    e = E.reindex(T.und.values)
    cheapT1 = T.cheap1.values <= e[1 / 3].values
    pick = {"R0": np.zeros(len(T), int), "R1": np.where(cheapT1, 2, 0)}
    ivs = np.c_[T.iv_itm1.values, T.iv_atm.values, T.iv_otm1.values]
    ivs = np.where(np.isfinite(ivs), ivs, np.inf)
    r2 = np.argmin(ivs, axis=1)
    r2[~np.isfinite(ivs).any(axis=1)] = 0
    pick["R2"] = r2
    out = {}
    for nm, k in pick.items():
        sfx = np.array(["", "_atm", "_otm1"])[k]
        g = lambda c: np.array([T[c + s].values[i] for i, s in enumerate(sfx)])  # noqa: E731
        net, prem, lots, gross = g("net_s"), g("prem"), g("lots"), g("gross")
        ok = np.isfinite(net) & np.isfinite(prem)
        out[nm] = pd.DataFrame(dict(day=T.dts, und=T.und, net=np.where(ok, net, T.net_s), gross=np.where(ok, gross, T.gross),
                                    prem=np.where(ok, prem, T.prem), lots=np.where(ok, lots, T.lots), pick=k))
    return out


def stats(x, nd):
    return dict(n=len(x), ret_prem=(x.net / x.prem).mean() * 100, gross_ret=(x.gross / x.prem).mean() * 100,
                net_tr=x.net.mean(), net_day=x.net.sum() / nd, budget_day=(x.net * x.lots).sum() / nd,
                pick_itm1=(x.pick == 0).mean(), pick_atm=(x.pick == 1).mean(), pick_otm1=(x.pick == 2).mean())


def paired_p(a, b, B=5000, seed=5):
    d = a - b
    d = d[np.isfinite(d)]
    rng = np.random.default_rng(seed)
    obs = d.mean()
    bs = np.array([rng.choice(d, len(d)).mean() for _ in range(B)])
    return float((bs <= 0).mean()), obs


def main(mode):
    T = load()
    print("signals with all three strikes priced:", int(np.isfinite(T.net_s_atm + T.net_s_otm1).sum()), "of", len(T))
    print("own IV coverage:", {c: round(T[c].notna().mean(), 3) for c in ("iv_itm1", "iv_atm", "iv_otm1", "cheap1")})
    pre = T[T.dts < HOLD]
    E = pre.groupby("und").cheap1.quantile([1 / 3, 2 / 3]).unstack()
    if mode == "pre":
        X = T[T.dts < HOLD].reset_index(drop=True)
        rr = apply_rules(X, E)
        rows = []
        for hn, s in (("2021-23", X.dts < SPLIT), ("2024-25.09", X.dts >= SPLIT), ("all pre", X.dts < HOLD)):
            nd = X[s].dts.nunique()
            for nm, r in rr.items():
                rows.append(dict(period=hn, rule=nm, **stats(r[s.values], nd)))
        R = pd.DataFrame(rows)
        print("\n=== Test 3, pre-holdout (net incl. real spread; ret_prem = mean net/premium %, budget_day = Rs/day at Rs 33k a trade) ===")
        print(R.round(3).to_string())
        ps = {}
        for nm in ("R1", "R2"):
            a = rr[nm].net / rr[nm].prem
            b = rr["R0"].net / rr["R0"].prem
            ps[nm] = paired_p(a.values, b.values)
        pv = np.array([ps["R1"][0], ps["R2"][0]])
        o = np.argsort(pv)
        qq = np.empty(2)
        qq[o] = np.minimum.accumulate((pv[o] * 2 / np.arange(1, 3))[::-1])[::-1]
        q = dict(R1=float(min(qq[0], 1)), R2=float(min(qq[1], 1)))
        print("paired bootstrap p (R vs R0, ret on premium):", ps, "BH q:", q)
        ad = [nm for nm in ("R1", "R2") if q[nm] < 0.05 and all(
            R[(R.period == hn) & (R.rule == nm)].ret_prem.iloc[0] > R[(R.period == hn) & (R.rule == "R0")].ret_prem.iloc[0]
            for hn in ("2021-23", "2024-25.09"))]
        print("ADOPTED:", ad)
        json.dump(dict(adopted=ad), open(os.path.join(OUT, "strikes_choice.json"), "w"))
        # descriptive: return on premium by cheap1 tercile x strike
        X["ct"] = (X.cheap1.values > E.reindex(X.und.values)[1 / 3].values).astype(int) + \
                  (X.cheap1.values > E.reindex(X.und.values)[2 / 3].values).astype(int)
        D = []
        for k in range(3):
            s = X.ct == k
            for sfx, nm in (("", "ITM1"), ("_atm", "ATM"), ("_otm1", "OTM1")):
                D.append(dict(cheap_tercile=k + 1, strike=nm, n=int(s.sum()),
                              ret_prem=(X[s]["net_s" + sfx] / X[s]["prem" + sfx]).mean() * 100,
                              gross_ret=(X[s]["gross" + sfx] / X[s]["prem" + sfx]).mean() * 100))
        print("\nreturn on premium (%), by cheap1 tercile (1 = cheapest) x strike, pre-holdout:")
        print(pd.DataFrame(D).pivot_table(index="cheap_tercile", columns="strike", values=["ret_prem", "gross_ret"]).round(3).to_string())
    else:
        ad = json.load(open(os.path.join(OUT, "strikes_choice.json")))["adopted"]
        X = T[T.dts >= HOLD].reset_index(drop=True)
        rr = apply_rules(X, E)
        nd = X.dts.nunique()
        print("\n=== HOLDOUT test 3 (adopted:", ad, ") ===")
        print(pd.DataFrame([dict(rule=nm, **stats(r, nd)) for nm, r in rr.items()]).round(3).to_string())


if __name__ == "__main__":
    {"build": build}.get(sys.argv[1], lambda: main(sys.argv[1]))()
