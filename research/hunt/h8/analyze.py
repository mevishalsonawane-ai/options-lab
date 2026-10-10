"""h8 analysis: portfolios of the long-only Liquidity 15+5 stock trades; selection on data before 2025-10-01 only;
BH + White RC / Hansen SPA over every variant; anchored quarterly walk-forward; random-entry baseline (identical exits,
identical portfolio rules); then (mode 'holdout') the ONE chosen variant on the locked holdout.

    python3 -I research/hunt/h8/analyze.py train      # writes scratchpad/hunt/h8/train.json (+ choice)
    python3 -I research/hunt/h8/analyze.py holdout    # reads the choice, evaluates once, writes holdout.json

Costs (net): intraday cash (MIS) charges per order - brokerage min(Rs 20, 0.03%), STT 0.025% on sell, NSE txn
0.00297%, SEBI Rs 10/cr, stamp 0.003% on buy, GST 18% - plus a realistic spread: half-spread 2/3/5/8 bps a side by the
stock's prior 20-day median traded value (>=1000 cr / 300-1000 / 100-300 / <100 cr), floor half a Rs 0.05 tick, x2 on
a resting hard-stop fill, plus market impact 10 x sqrt(order / average 5-minute traded value) bps a side (as h2).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path[:0] = [HERE, os.path.dirname(os.path.dirname(HERE))]
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import liqcash as LC  # noqa: E402
from obuy import overfit as OF  # noqa: E402

MODE = sys.argv[1] if len(sys.argv) > 1 else "train"
N0 = 100_000.0                      # fixed Rs notional per trade for the headline
BOOKS = {"both": (5, 15), "b15": (15,), "b5": (5,)}
TOPN = (50, 100, 214)
CAPS = (5, 10, 20)
VARIANTS = [(b, n, c) for b in BOOKS for n in TOPN for c in CAPS]
FIRST = pd.Timestamp("2024-10-07"); LAST = pd.Timestamp("2026-10-05")


def vid(v):
    return f"{v[0]}|top{v[1]}|cap{v[2]}"


# ------------------------------------------------------------------------------------------------ inputs
def adv_rank():
    """(sym, day) -> rank by prior 20-day median traded value (daily candles), 1 = most liquid."""
    d = os.path.join(LC.DATA, "candles", "daily", "NSE_EQ")
    names = sorted(os.listdir(os.path.join(LC.DATA, "candles", "minute", "NSE_EQ")))
    cols = {}
    for nm in names:
        x = pd.read_parquet(os.path.join(d, nm + ".parquet"))
        ts = x.ts.dt.tz_localize(None).dt.normalize()
        v = pd.Series((x.close * x.volume).to_numpy(), index=ts)
        v = v[~v.index.duplicated()]
        cols[nm] = v[v.index >= "2024-06-01"].rolling(20, min_periods=5).median().shift(1)
    W = pd.DataFrame(cols)
    R = W.rank(axis=1, ascending=False)
    return R.stack().rename("rk")


def trading_days():
    df = LC.load_minutes("IDX_I", "NIFTY")
    c = df.groupby("day").size()
    c = c[(c.index >= FIRST) & (c.index <= LAST) & (c >= 300)]
    return pd.DatetimeIndex(c.index)


def costs_rs(buy_val, sell_val):
    brk = np.minimum(20.0, 0.0003 * buy_val) + np.minimum(20.0, 0.0003 * sell_val)
    exch = 0.0000297 * (buy_val + sell_val)
    sebi = 1e-7 * (buy_val + sell_val)
    return brk + exch + sebi + 0.00025 * sell_val + 0.00003 * buy_val + 0.18 * (brk + exch + sebi)


def pnl(t, N):
    e, x, adv = t.e.to_numpy(), t.x.to_numpy(), t.adv.to_numpy()
    qty = np.floor(N / e)
    adv = np.where(np.isfinite(adv) & (adv > 0), adv, 1e9)
    bps = np.where(adv >= 1e10, 2, np.where(adv >= 3e9, 3, np.where(adv >= 1e9, 5, 8))).astype(float)
    bps = bps + 10.0 * np.sqrt(N / (adv / 75.0))
    bps = np.maximum(bps, 0.025 / e * 1e4)
    bi = e * (1 + bps / 1e4)
    so = x * (1 - np.where(t.why.to_numpy() == 5, 2.0, 1.0) * bps / 1e4)
    gross = qty * (x - e)
    net = qty * (so - bi) - costs_rs(qty * bi, qty * so)
    return gross, net, qty * e


def portfolio(t, v):
    """keep the trades a portfolio with books v[0], universe top v[1], at most v[2] open positions could take."""
    b, n, cap = v
    t = t[t.book.isin(BOOKS[b]) & (t.rk <= n)]
    t = t.sort_values(["day", "ce", "rk"], kind="stable")
    keep = np.zeros(len(t), bool)
    day, ce, xc = t.day.values, t.ce.values, t.xc.values
    cur, opn = None, []
    for i in range(len(t)):
        if day[i] != cur:
            cur, opn = day[i], []
        opn = [x for x in opn if x >= ce[i]]
        if len(opn) >= cap:
            continue
        keep[i] = True
        opn.append(xc[i])
    return t[keep]


def daily(t, days, N=N0):
    g, n_, _ = pnl(t, N)
    G = pd.Series(g, index=t.day.values).groupby(level=0).sum().reindex(days, fill_value=0.0)
    Nn = pd.Series(n_, index=t.day.values).groupby(level=0).sum().reindex(days, fill_value=0.0)
    return G, Nn


def max_dd(s):
    eq = s.cumsum()
    return float((eq - eq.cummax()).min())


def p_losing_month(s, B=5000, seed=7):
    """bootstrap: 21 trading days drawn with replacement (in 5-day blocks) -> P(month sum < 0)."""
    rng = np.random.default_rng(seed)
    x = s.to_numpy()
    T = len(x)
    starts = rng.integers(0, T - 5, (B, 5))
    tot = np.array([x[np.r_[tuple(slice(a, a + 5) for a in row)]][:21].sum() for row in starts])
    return float((tot < 0).mean())


def conc_exposure(t, N=N0):
    """max simultaneous Rs exposure over the period at notional N."""
    mx = 0
    for d, g in t.groupby("day"):
        ev = np.r_[g.ce.to_numpy(), g.xc.to_numpy() + 1]
        sg = np.r_[np.ones(len(g)), -np.ones(len(g))]
        o = np.lexsort((sg, ev))
        mx = max(mx, int(np.cumsum(sg[o]).max()))
    return mx * N, mx


def summary(t, days, N=N0):
    G, Nn = daily(t, days, N)
    mon = Nn.groupby(Nn.index.to_period("M")).sum()
    mong = G.groupby(G.index.to_period("M")).sum()
    return dict(trades=int(len(t)), days=int(len(days)), trades_day=round(len(t) / len(days), 1),
                gross_day=round(G.mean(), 1), net_day=round(Nn.mean(), 1),
                gross_bps=round(float((t.x / t.e - 1).mean() * 1e4), 2),
                worst_day=round(Nn.min(), 0), worst_month=round(mon.min(), 0), worst_month_gross=round(mong.min(), 0),
                maxdd_net=round(max_dd(Nn), 0), maxdd_gross=round(max_dd(G), 0),
                p_lose_month_net=round(p_losing_month(Nn), 3), p_lose_month_gross=round(p_losing_month(G), 3),
                months_neg_net=f"{int((mon < 0).sum())}/{len(mon)}")


def load():
    rk = adv_rank()
    S = pd.read_parquet(os.path.join(LC.OUT, "sig_stocks.parquet"))
    R = pd.read_parquet(os.path.join(LC.OUT, "rand_stocks.parquet"))
    rk = rk.reset_index()
    rk.columns = ["day", "sym", "rk"]
    S = S.merge(rk, on=["day", "sym"], how="left")
    R = R.merge(rk, on=["day", "sym"], how="left")
    S["rk"] = S.rk.fillna(999); R["rk"] = R.rk.fillna(999)
    return S, R


def rand_vs_sig(S, R):
    """trade-level: signal gross bps minus the mean of its 5 random twins; day-clustered bootstrap p (one-sided)."""
    key = ["sym", "day", "book", "level"]
    rb = R.assign(bps=(R.x / R.e - 1) * 1e4).groupby(key).bps.mean().rename("rbps")
    m = S.assign(bps=(S.x / S.e - 1) * 1e4).join(rb, on=key)
    m = m[m.rbps.notna()]
    dd = (m.bps - m.rbps).groupby(m.day).agg(["sum", "count"])
    rng = np.random.default_rng(11)
    idx = rng.integers(0, len(dd), (4000, len(dd)))
    bs = dd["sum"].to_numpy()[idx].sum(1) / dd["count"].to_numpy()[idx].sum(1)
    mean = float(dd["sum"].sum() / dd["count"].sum())
    return dict(sig_bps=round(float(m.bps.mean()), 2), rand_bps=round(float(m.rbps.mean()), 2),
                diff_bps=round(mean, 2), p_sig_better=round(float(((bs - mean) >= mean).mean()), 4),
                ci95=[round(float(np.quantile(bs, .025)), 2), round(float(np.quantile(bs, .975)), 2)])


if __name__ == "__main__":
    S, R = load()
    alld = trading_days()
    pre = alld[alld < LC.HOLD]
    out = {}
    if MODE == "train":
        Sp, Rp = S[S.day < LC.HOLD], R[R.day < LC.HOLD]
        out["trade_level_pre"] = {b: rand_vs_sig(Sp[Sp.book.isin(BOOKS[b])], Rp[Rp.book.isin(BOOKS[b])]) for b in BOOKS}
        rows, Xn, Xg = [], {}, {}
        for v in VARIANTS:
            t = portfolio(Sp, v)
            G, Nn = daily(t, pre)
            Xn[vid(v)], Xg[vid(v)] = Nn, G
            rnet = []
            for k in range(5):
                tr = portfolio(Rp[Rp.rep == k], v)
                rnet.append(daily(tr, pre)[1].mean())
            from scipy import stats as sst
            tt = sst.ttest_1samp(Nn.to_numpy(), 0.0, alternative="greater")
            rows.append(dict(vid=vid(v), trades_day=round(len(t) / len(pre), 1), gross_day=round(G.mean(), 1),
                             net_day=round(Nn.mean(), 1), sd_net=round(Nn.std(), 0), p_net=float(tt.pvalue),
                             rand_net_day=round(float(np.mean(rnet)), 1),
                             rand_beats=int(sum(r >= Nn.mean() for r in rnet))))
            print(rows[-1], flush=True)
        V = pd.DataFrame(rows)
        V["bh_q"] = OF.bh(V.p_net.to_numpy())
        XN = pd.DataFrame(Xn); XG = pd.DataFrame(Xg)
        out["spa_net"] = OF.spa(XN.to_numpy(), B=1000)
        out["spa_gross"] = OF.spa(XG.to_numpy(), B=1000)
        # anchored quarterly walk-forward (train from 2024-10-07, test 2025Q1..Q3)
        wf = []
        oos_n, oos_g = [], []
        for q0, q1 in [("2025-01-01", "2025-04-01"), ("2025-04-01", "2025-07-01"), ("2025-07-01", "2025-10-01")]:
            tr_m = XN.index < q0
            te_m = (XN.index >= q0) & (XN.index < q1)
            best = XN[tr_m].mean().idxmax()
            wf.append(dict(test=q0, picked=best, train_net_day=round(XN[tr_m][best].mean(), 1),
                           test_net_day=round(XN[te_m][best].mean(), 1), test_gross_day=round(XG[te_m][best].mean(), 1)))
            oos_n.append(XN[te_m][best]); oos_g.append(XG[te_m][best])
        out["walk_forward"] = wf
        out["wf_oos_net_day"] = round(pd.concat(oos_n).mean(), 1)
        out["wf_oos_gross_day"] = round(pd.concat(oos_g).mean(), 1)
        best = V.sort_values("net_day", ascending=False).iloc[0]
        out["choice"] = best.vid
        out["n_variants"] = len(VARIANTS)
        out["variants"] = V.to_dict("records")
        bv = [v for v in VARIANTS if vid(v) == best.vid][0]
        out["choice_pre_summary"] = summary(portfolio(Sp, bv), pre)
        # per-quarter of the choice
        Nb = XN[best.vid]
        out["choice_pre_by_quarter_net"] = {str(k): round(v, 1) for k, v in Nb.groupby(Nb.index.to_period("Q")).mean().items()}
        # notional curve (pre): net Rs/day vs Rs per trade (impact grows with size)
        tb = portfolio(Sp, bv)
        out["choice_pre_size_curve"] = {int(N): round(daily(tb, pre, N)[1].mean(), 1) for N in (5e4, 1e5, 2e5, 5e5, 1e6, 2e6)}
        json.dump(out, open(os.path.join(LC.OUT, "train.json"), "w"), indent=1, default=str)
        V.to_csv(os.path.join(LC.OUT, "variants_pre.csv"), index=False)
        print(json.dumps({k: out[k] for k in out if k != "variants"}, indent=1, default=str))
    else:
        tj = json.load(open(os.path.join(LC.OUT, "train.json")))
        bv = [v for v in VARIANTS if vid(v) == tj["choice"]][0]
        hold = alld[alld >= LC.HOLD]
        Sh, Rh = S[S.day >= LC.HOLD], R[R.day >= LC.HOLD]
        th = portfolio(Sh, bv)
        out["choice"] = tj["choice"]
        out["holdout"] = summary(th, hold)
        out["holdout_trade_level"] = rand_vs_sig(Sh[Sh.book.isin(BOOKS[bv[0]])], Rh[Rh.book.isin(BOOKS[bv[0]])])
        rr = [daily(portfolio(Rh[Rh.rep == k], bv), hold) for k in range(5)]
        out["holdout_random_net_day"] = [round(x[1].mean(), 1) for x in rr]
        out["holdout_random_gross_day"] = [round(x[0].mean(), 1) for x in rr]
        G, Nn = daily(th, hold)
        out["holdout_by_month_net"] = {str(k): round(v, 0) for k, v in Nn.groupby(Nn.index.to_period("M")).sum().items()}
        out["holdout_by_month_gross"] = {str(k): round(v, 0) for k, v in G.groupby(G.index.to_period("M")).sum().items()}
        out["holdout_size_curve_net"] = {int(N): round(daily(th, hold, N)[1].mean(), 1) for N in (5e4, 1e5, 2e5, 5e5, 1e6, 2e6)}
        out["holdout_size_curve_gross"] = {int(N): round(daily(th, hold, N)[0].mean(), 1) for N in (1e5, 1e6)}
        exp, mx = conc_exposure(th)
        out["max_concurrent_positions"] = mx
        # per-year (whole sample, the chosen variant; 2024 = Oct-Dec)
        ta = portfolio(S, bv)
        Ga, Na = daily(ta, alld)
        out["by_year_net_day"] = {int(k): round(v, 1) for k, v in Na.groupby(Na.index.year).mean().items()}
        out["by_year_gross_day"] = {int(k): round(v, 1) for k, v in Ga.groupby(Ga.index.year).mean().items()}
        json.dump(out, open(os.path.join(LC.OUT, "holdout.json"), "w"), indent=1, default=str)
        print(json.dumps(out, indent=1, default=str))
