"""Trade translation of the model scores on Delta Exchange India, after fees, spread, funding and Indian tax,
against a random-entry baseline. Pre-registered rule: scratchpad/hunt/nn_crypto/PREREG.txt.

python3 -I evaluate.py dev     # on the walk-forward test blocks (2022 .. 2026Q1)
python3 -I evaluate.py hold    # on the locked holdout (only after model.py holdout)
"""
from __future__ import annotations

import glob
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.special import ndtr  # noqa: E402

from common import DATA, LOGS, SCR, log  # noqa: E402

USDINR = 96.78
TAX = 0.312                      # 30% + 4% cess
FEE_FUT = 0.0005 * 1.18          # taker 0.05% + 18% GST, each side
CV = {"BTC": 0.001, "ETH": 0.01}
KSTEP = {"BTC": 200.0, "ETH": 20.0}
KB = {60: 4, 240: 16}
MODELS_ = ("nn", "lr", "hgb", "random")
QS = (0.02, 0.05)
B = 1000


def delta_today():
    """Half-spreads of the perps and ATM IV / DVOL ratios (ask, bid) for the nearest >=1-day expiry, from the snapshot."""
    l2 = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "delta_l2_*.parquet")))[-1])
    l2[["price", "size"]] = l2[["price", "size"]].astype(float)
    hs = {}
    for s, a in (("BTCUSD", "BTC"), ("ETHUSD", "ETH")):
        g = l2[l2.symbol == s]
        bb, ba = g[g.side == "buy"].price.max(), g[g.side == "sell"].price.min()
        hs[a] = (ba - bb) / (ba + bb)
    ch = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "delta_chain_*.parquet")))[-1])
    for c in ("strike", "bid", "ask", "bid_iv", "ask_iv", "spot"):
        ch[c] = pd.to_numeric(ch[c], errors="coerce")
    o = ch[ch.ctype.str.contains("options") & (ch.bid > 0)].copy()
    o["exp"] = pd.to_datetime(o.symbol.str.split("-").str[-1], format="%d%m%y") + pd.Timedelta(hours=12)
    ts = pd.to_datetime(pd.to_numeric(o.ts, errors="coerce"), unit="us")
    o["T_h"] = (o.exp - ts).dt.total_seconds() / 3600
    dv = pd.read_parquet(os.path.join(DATA, "deribit_dvol_1h.parquet"))
    ratio = {}
    for a in ("BTC", "ETH"):
        q = o[(o.und == a) & (o.T_h >= 24)]
        e = q.T_h.min()
        q = q[q.exp == q.exp[q.T_h == e].iloc[0]]                      # every strike of that expiry
        q = q[(q.bid_iv > 0.05) & (q.ask_iv > 0.05)]                   # drop one-sided quotes (bid IV ~ 0)
        q = q.assign(dist=(q.strike / q.spot - 1).abs())
        q = q[q.dist == q.dist.min()]                                    # the ATM strike: its call and put
        d = dv[dv.cur == a].c.iloc[-1] / 100
        ratio[a] = (float(q.ask_iv.mean() / d), float(q.bid_iv.mean() / d), float(e))
    return hs, ratio


def bs(S, K, T, v, call):
    v = np.maximum(v, 1e-4)
    T = np.maximum(T, 1e-6)
    d1 = (np.log(S / K) + 0.5 * v * v * T) / (v * np.sqrt(T))
    d2 = d1 - v * np.sqrt(T)
    return np.where(call, S * ndtr(d1) - K * ndtr(d2), K * ndtr(-d2) - S * ndtr(-d1))


def bar_tables():
    f = pd.read_parquet(os.path.join(DATA, "feat_15m.parquet"), columns=["asset", "b", "T", "c", "dvol"])
    fu = pd.read_parquet(os.path.join(DATA, "bn_funding.parquet"))
    out = {}
    for a, s in (("BTC", "BTCUSDT"), ("ETH", "ETHUSDT")):
        g = f[f.asset == a].sort_values("b")
        u = fu[fu.sym == s].sort_values("ts")
        out[a] = dict(b=g.b.values, T=g["T"].values, c=g.c.values, dvol=g.dvol.ffill().values,
                      fts=(u.ts.values // 1000), fcs=np.r_[0, np.cumsum(u.rate.astype(float).values)])
    return out


def pnl_tables(bt, hs, ratio):
    """Per bar, per asset, per horizon: net USD P&L of a long and a short (futures), and of a bought call / put
    (options), for 1 contract, before tax. Also gross."""
    P = {}
    for a, d in bt.items():
        c, T, n = d["c"], d["T"], len(d["c"])
        for h, k in KB.items():
            i = np.arange(n - k)
            e, x = c[i], c[i + k]
            cv = CV[a]
            fsum = d["fcs"][np.searchsorted(d["fts"], T[i + k], side="right")] - d["fcs"][np.searchsorted(d["fts"], T[i], side="right")]
            gross_l = (x - e) * cv
            cost = FEE_FUT * (e + x) * cv + hs[a] * (e + x) * cv
            fund_l = -fsum * e * cv
            P[(a, h, "fut")] = dict(gl=gross_l, gs=-gross_l, nl=gross_l - cost + fund_l, ns=-gross_l - cost - fund_l)
            # options: nearest daily expiry (12:00 UTC) at least 24h after entry
            texp = (np.floor((T[i] / 3600 - 12) / 24) * 24 + 12) * 3600
            texp = np.where(texp < T[i], texp + 86400, texp)
            texp = np.where(texp - T[i] < 86400, texp + 86400, texp)
            Te = (texp - T[i]) / (365 * 86400)
            Tx = (texp - T[i + k]) / (365 * 86400)
            K = np.round(e / KSTEP[a]) * KSTEP[a]
            ra, rb, _ = ratio[a]
            dv0, dv1 = d["dvol"][i], d["dvol"][i + k]
            res = {}
            for side, call in (("l", True), ("s", False)):
                pa = bs(e, K, Te, dv0 * ra, call) * cv
                pb = bs(x, K, Tx, dv1 * rb, call) * cv
                pm0 = bs(e, K, Te, dv0 * (ra + rb) / 2, call) * cv
                pm1 = bs(x, K, Tx, dv1 * (ra + rb) / 2, call) * cv
                fee = (np.minimum(1e-4 * e * cv, 0.035 * pa) + np.minimum(1e-4 * x * cv, 0.035 * pb)) * 1.18
                res["g" + side] = pm1 - pm0
                res["n" + side] = pb - pa - fee
                res["prem" + side] = pa
            P[(a, h, "opt")] = res
    return P


def bh(p):
    """Benjamini-Hochberg q-values."""
    p = np.asarray(p, float)
    o = np.argsort(p)
    q = p[o] * len(p) / np.arange(1, len(p) + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty_like(q)
    out[o] = np.minimum(q, 1)
    return out


def run(stage):
    hs, ratio = delta_today()
    log("half-spreads", hs, "IV/DVOL ratios (ask, bid, hours to expiry)", ratio)
    bt = bar_tables()
    P = pnl_tables(bt, hs, ratio)
    files = sorted(glob.glob(os.path.join(SCR, "preds", f"preds_{'dev' if stage == 'dev' else 'hold'}_*.parquet")))
    pr = pd.concat([pd.read_parquet(x) for x in files], ignore_index=True)
    rng = np.random.default_rng(11)
    rows, trades_all = [], []
    for a in ("BTC", "ETH"):
        bpos = {int(b): j for j, b in enumerate(bt[a]["b"])}
        for h, k in KB.items():
            for inst in ("fut", "opt"):
                PP = P[(a, h, inst)]
                nmax = len(PP["nl"])
                for m in MODELS_:
                    for q in QS:
                        tr = []
                        for fold, g in pr[pr.asset == a].groupby("fold", sort=False):
                            va, te = g[g.part == "va"], g[g.part == "te"].sort_values("b")
                            col = f"{m}_p{h}"
                            lo, hi = np.quantile(va[col], q), np.quantile(va[col], 1 - q)
                            sig = np.where(te[col] >= hi, 1, np.where(te[col] <= lo, -1, 0))
                            free = -1
                            for b, s, T in zip(te.b.values[sig != 0], sig[sig != 0], te["T"].values[sig != 0]):
                                j = bpos[int(b)]
                                if b < free or j >= nmax:
                                    continue
                                if inst == "opt" and not np.isfinite(PP["nl"][j]):
                                    continue
                                free = b + k
                                tr.append((fold, j, s, T))
                        if not tr:
                            continue
                        t = pd.DataFrame(tr, columns=["fold", "j", "side", "T"])
                        L = t.side.values > 0
                        t["gross"] = np.where(L, PP["gl"][t.j], PP["gs"][t.j]) * USDINR
                        t["net"] = np.where(L, PP["nl"][t.j], PP["ns"][t.j]) * USDINR
                        t["vda"] = t.net - TAX * np.maximum(t.net, 0)
                        t["day"] = t["T"] // 86400
                        # random entries: same count per day, random bars that day, random side
                        T_all = bt[a]["T"][:nmax]
                        dayb = T_all // 86400
                        okb = np.isfinite(PP["nl"])
                        cnt = t.groupby("day").size()
                        st = np.searchsorted(dayb, cnt.index.values)
                        en = np.searchsorted(dayb, cnt.index.values, side="right")
                        ra, rn = np.repeat(st, cnt.values), np.repeat(en - st, cnt.values)
                        means = np.empty(B)
                        for z in range(B):
                            jj = ra + (rng.random(len(ra)) * rn).astype(int)
                            jj = jj[okb[jj]]
                            sd = rng.random(len(jj)) < 0.5
                            nn = np.where(sd, PP["nl"][jj], PP["ns"][jj]) * USDINR
                            means[z] = np.mean(nn - TAX * np.maximum(nn, 0))
                        real = t.vda.mean()
                        yearly = t.groupby(t.fold).net.sum()
                        biz = float(sum(v - TAX * max(v, 0) for v in yearly))
                        rows.append(dict(asset=a, h=h, inst=inst, model=m, q=q, trades=len(t), win_pct=(t.net > 0).mean() * 100,
                                         gross_rs=t.gross.sum(), net_rs=t.net.sum(), after_tax_vda_rs=t.vda.sum(),
                                         after_tax_business_rs=biz, gross_per_trade=t.gross.mean(), net_per_trade=t.net.mean(),
                                         vda_per_trade=real, random_vda_per_trade=means.mean(),
                                         p_vs_random=(1 + (means >= real).sum()) / (B + 1),
                                         **{f"net_{fo}": float(v) for fo, v in yearly.items()}))
                        trades_all.append(t.assign(asset=a, h=h, inst=inst, model=m, q=q))
    R = pd.DataFrame(rows)
    R["bh_q"] = bh(R.p_vs_random.values)
    R.to_csv(os.path.join(LOGS, f"trades_{stage}.csv"), index=False)
    pd.concat(trades_all).to_parquet(os.path.join(SCR, "preds", f"trades_{stage}.parquet"), compression="zstd", index=False)
    show = ["asset", "h", "inst", "model", "q", "trades", "win_pct", "gross_rs", "net_rs", "after_tax_vda_rs",
            "after_tax_business_rs", "gross_per_trade", "vda_per_trade", "random_vda_per_trade", "p_vs_random", "bh_q"]
    pd.set_option("display.width", 250)
    print(R[show].round(2).to_string())
    print("\nbase rates per bar (all bars, Rs per 1 contract, before tax):")
    for key, PP in P.items():
        print(key, "mean |gross|", round(float(np.nanmean(np.abs(PP["gl"]))) * USDINR, 2),
              "mean net of a coin-flip side", round(float(np.nanmean((PP["nl"] + PP["ns"]) / 2)) * USDINR, 2),
              "mean premium", round(float(np.nanmean(PP["preml"])) * USDINR, 2) if "preml" in PP else "")


if __name__ == "__main__":
    run(sys.argv[1])
