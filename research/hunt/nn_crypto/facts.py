"""Plain market facts for BTC/ETH (an Indian resident's view, IST clock). Descriptive only, no trading choices.

python3 -I facts.py [section ...]   sections: hours range persist funding oi events vol options delta money tax
Prints tables and writes LOGS/facts_<section>.csv
"""
from __future__ import annotations

import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from common import DATA, LOGS, log  # noqa: E402

pd.set_option("display.width", 220)
pd.set_option("display.max_columns", 40)
SYMS = {"BTC": "BTCUSDT", "ETH": "ETHUSDT"}
IST = 330          # minutes
USDINR = 96.78     # frankfurter.dev, 8 Oct 2026
_cache = {}


def m1(a):
    if a not in _cache:
        d = pd.read_parquet(os.path.join(DATA, f"bn_perp_{SYMS[a]}_1m.parquet"), columns=["t", "o", "h", "l", "c", "v"])
        _cache[a] = d
    return _cache[a]


def agg(a, minutes, offset=0):
    d = m1(a)
    k = (d.t + offset) // minutes
    g = d.groupby(k)
    b = pd.DataFrame({"o": g.o.first(), "h": g.h.max(), "l": g.l.min(), "c": g.c.last(), "v": (d.v * d.c).groupby(k).sum()})
    b["r"] = np.log(b.c / b.c.shift(1))
    b["start"] = pd.to_datetime((b.index.values * minutes - offset) * 60, unit="s")
    return b


def out(name, df):
    df.to_csv(os.path.join(LOGS, f"facts_{name}.csv"))
    print(f"\n=== {name}\n{df.to_string()}", flush=True)


def hours():
    rows = []
    for a in SYMS:
        b = agg(a, 60, IST)                    # IST-aligned hourly bars
        b["hour"] = ((b.index.values * 60) // 60) % 24
        b["year"] = b.start.dt.year
        b["dow"] = ((b.index.values) // 24 + 3) % 7       # 1970-01-01 was a Thursday (3)
        b = b.dropna(subset=["r"])
        tot = (b.r ** 2).groupby(b.year).sum()
        g = b.groupby("hour")
        t = pd.DataFrame({"mean_abs_%": g.r.apply(lambda x: x.abs().mean() * 100),
                          "var_share_%": g.r.apply(lambda x: (x ** 2).sum()) / (b.r ** 2).sum() * 100,
                          "drift_bp": g.r.mean() * 1e4, "t_drift": g.r.mean() / (g.r.std() / np.sqrt(g.r.count())),
                          "usd_vol_share_%": g.v.sum() / b.v.sum() * 100})
        # stability: variance share by hour per year (rank corr between years)
        vs = b.assign(r2=b.r ** 2).pivot_table(index="hour", columns="year", values="r2", aggfunc="sum") / tot * 100
        t["var_share_min_yr"] = vs.min(axis=1)
        t["var_share_max_yr"] = vs.max(axis=1)
        t.insert(0, "asset", a)
        rows.append(t)
        wd = b.assign(r2=b.r ** 2).groupby("dow").agg(mean_abs=("r", lambda x: x.abs().mean() * 100), n=("r", "size"),
                                                     drift_bp=("r", lambda x: x.mean() * 1e4))
        wd.index = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        out(f"weekday_{a}", wd.round(3))
    out("hours", pd.concat(rows).round(3))


def drange():
    rows = []
    for a in SYMS:
        d = agg(a, 1440, IST)
        d["rng"] = (d.h / d.l - 1) * 100
        d["oc"] = (d.c / d.o - 1).abs() * 100
        d["year"] = d.start.dt.year
        d["wkend"] = ((d.index.values + 3) % 7) >= 5
        g = d.groupby("year")
        t = pd.DataFrame({"days": g.rng.size(), "median_range_%": g.rng.median(), "p90_range_%": g.rng.quantile(0.9),
                          "max_range_%": g.rng.max(), "median_|open-close|_%": g.oc.median(),
                          "wkday_med_range_%": d[~d.wkend].groupby("year").rng.median(),
                          "wkend_med_range_%": d[d.wkend].groupby("year").rng.median(),
                          "days_over_5%": g.rng.apply(lambda x: (x > 5).mean() * 100)})
        t.insert(0, "asset", a)
        rows.append(t)
    out("range", pd.concat(rows).round(2))


def persist():
    """Variance ratios and autocorrelation of non-overlapping returns by horizon, per year (5-min base)."""
    rows = []
    for a in SYMS:
        b = agg(a, 5)
        b["year"] = b.start.dt.year
        for y, g in b.groupby("year"):
            r = g.r.dropna().values
            v1 = r.var()
            for q, nm in ((3, "15m"), (12, "1h"), (48, "4h"), (288, "1d"), (2016, "1w")):
                n = len(r) // q
                if n < 30:
                    continue
                rq = r[: n * q].reshape(n, q).sum(axis=1)
                ac = np.corrcoef(rq[:-1], rq[1:])[0, 1]
                same = np.mean(np.sign(rq[:-1]) == np.sign(rq[1:]))
                rows.append(dict(asset=a, year=y, horizon=nm, VR=rq.var() / (q * v1), autocorr=ac,
                                 ac_se=1 / np.sqrt(n), same_sign_next_pct=same * 100, n=n))
    t = pd.DataFrame(rows)
    out("persist", t.pivot_table(index=["asset", "horizon"], columns="year", values="VR").round(2))
    out("persist_ac", t.pivot_table(index=["asset", "horizon"], columns="year", values="autocorr").round(3))
    t.to_csv(os.path.join(LOGS, "facts_persist_long.csv"), index=False)


def price_at(a, ts_sec):
    d = m1(a)
    tm = ts_sec // 60
    i = np.searchsorted(d.t.values, tm)
    i = np.clip(i, 0, len(d) - 1)
    ok = d.t.values[i] == tm
    return np.where(ok, d.o.values[i], np.nan)


def funding():
    fu = pd.read_parquet(os.path.join(DATA, "bn_funding.parquet"))
    rows, summ = [], []
    for a, s in SYMS.items():
        f = fu[fu.sym == s].copy()
        f["ts"] = (f.ts // 1000 // 60) * 60
        f["bp"] = f.rate.astype(float) * 1e4
        p0 = price_at(a, f.ts.values)
        for h in (8, 24, 72):
            f[f"fwd{h}h_%"] = (np.log(price_at(a, f.ts.values + h * 3600) / p0)) * 100
        f["year"] = pd.to_datetime(f.ts, unit="s").dt.year
        summ.append(f.groupby("year").bp.describe(percentiles=[.05, .5, .95])[["mean", "5%", "50%", "95%", "max", "min"]]
                    .assign(asset=a, ann_mean_pct=lambda x: x["mean"] * 3 * 365 / 100))
        f["bucket"] = pd.cut(f.bp, [-1e9, -1, 0, 1, 1.5, 3, 5, 1e9],
                             labels=["<-1bp", "-1..0", "0..1", "1..1.5", "1.5..3", "3..5", ">5bp"])
        g = f.groupby("bucket", observed=True)
        t = g[["fwd8h_%", "fwd24h_%", "fwd72h_%"]].mean()
        t["n"] = g.size()
        t["t_24h"] = g["fwd24h_%"].mean() / (g["fwd24h_%"].std() / np.sqrt(g.size() / 3))   # /3: overlapping 24h
        t["up24h_%"] = g["fwd24h_%"].apply(lambda x: (x > 0).mean() * 100)
        t.insert(0, "asset", a)
        rows.append(t)
    out("funding_dist", pd.concat(summ).round(2))
    out("funding_fwd", pd.concat(rows).round(3))


def oi():
    mt = pd.read_parquet(os.path.join(DATA, "bn_metrics.parquet"))
    rows = []
    for a, s in SYMS.items():
        m = mt[mt.sym == s].sort_values("ts")
        m = m[m.ts % 3600 == 0].copy()                    # hourly samples
        m["d24"] = np.log(m.oiv / m.oiv.shift(24)) * 100
        p0 = price_at(a, m.ts.values)
        m["pr24"] = np.log(p0 / price_at(a, m.ts.values - 86400)) * 100
        m["fwd24"] = np.log(price_at(a, m.ts.values + 86400) / p0) * 100
        m = m.replace([np.inf, -np.inf], np.nan).dropna(subset=["d24", "pr24", "fwd24"])
        m["case"] = np.select([(m.d24 > m.d24.quantile(.9)) & (m.pr24 > 0), (m.d24 > m.d24.quantile(.9)) & (m.pr24 < 0),
                               (m.d24 < m.d24.quantile(.1)) & (m.pr24 > 0), (m.d24 < m.d24.quantile(.1)) & (m.pr24 < 0)],
                              ["OI up big, price up", "OI up big, price down", "OI down big, price up",
                               "OI down big, price down"], "middle")
        g = m.groupby("case")
        t = pd.DataFrame({"n_hours": g.size(), "fwd24_mean_%": g.fwd24.mean(), "fwd24_up_%": g.fwd24.apply(lambda x: (x > 0).mean() * 100),
                          "t_(÷24 overlap)": g.fwd24.mean() / (g.fwd24.std() / np.sqrt(g.size() / 24)),
                          "|fwd24|_mean_%": g.fwd24.apply(lambda x: x.abs().mean())})
        t.insert(0, "asset", a)
        rows.append(t)
    out("oi", pd.concat(rows).round(3))


def events():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from features import event_times
    ev = event_times()
    ev = ev[(ev.ts >= 1580000000) & (ev.ts < 1791400000)]
    rows = []
    for a in SYMS:
        d = m1(a)
        ts = ev.ts.values
        p0 = price_at(a, ts)
        res = {}
        for nm, a0, a1 in (("pre24h", -86400, 0), ("first15m", 0, 900), ("first1h", 0, 3600), ("1h_to_4h", 3600, 14400),
                           ("4h_to_24h", 14400, 86400)):
            res[nm] = np.log(price_at(a, ts + a1) / price_at(a, ts + a0)) * 100
        r = pd.DataFrame(res)
        r["kind"] = ev.kind.values
        # control: same clock time on the 20 prior non-event weekdays
        ctl = []
        for k in range(1, 29):
            t2 = ts - k * 86400
            ctl.append(np.abs(np.log(price_at(a, t2 + 3600) / price_at(a, t2))) * 100)
        r["ctl_abs_1h"] = np.nanmedian(np.vstack(ctl), axis=0)
        g = r.groupby("kind")
        t = pd.DataFrame({"n": g.size(), "med_|first1h|_%": g.first1h.apply(lambda x: x.abs().median()),
                          "control_med_|1h|_%": g.ctl_abs_1h.median(),
                          "ratio": g.first1h.apply(lambda x: x.abs().median()) / g.ctl_abs_1h.median(),
                          "pre24h_mean_%": g.pre24h.mean(), "pre24h_up_%": g.pre24h.apply(lambda x: (x > 0).mean() * 100),
                          "first15m_mean_%": g.first15m.mean(),
                          "same_sign_15m_vs_1h->4h_%": [np.mean(np.sign(x.first15m) == np.sign(x["1h_to_4h"])) * 100 for _, x in g],
                          "same_sign_1h_vs_4h->24h_%": [np.mean(np.sign(x.first1h) == np.sign(x["4h_to_24h"])) * 100 for _, x in g]})
        t.insert(0, "asset", a)
        rows.append(t)
    out("events", pd.concat(rows).round(3))


def vol():
    """DVOL (30-day implied) vs the realised vol of the NEXT 30 days, from 5-min perp returns."""
    dv = pd.read_parquet(os.path.join(DATA, "deribit_dvol_1h.parquet"))
    rows = []
    for a in SYMS:
        b = agg(a, 5)
        r2 = (b.r ** 2).fillna(0).values
        cs = np.r_[0, np.cumsum(r2)]
        tm = b.index.values * 5                   # bar start minute
        d = dv[(dv.cur == a) & (dv.ts % 86400000 == 0)].copy()
        d["t"] = d.ts // 60000
        i0 = np.searchsorted(tm, d.t.values)
        i1 = np.searchsorted(tm, d.t.values + 30 * 1440)
        ok = i1 < len(tm)
        d = d[ok].copy()
        i0, i1 = i0[ok], i1[ok]
        d["rv_next30"] = np.sqrt((cs[i1] - cs[i0]) * 365 / 30) * 100
        j0 = np.searchsorted(tm, d.t.values - 30 * 1440)
        d["rv_prev30"] = np.sqrt((cs[i0] - cs[j0]) * 365 / 30) * 100
        d["year"] = pd.to_datetime(d.ts, unit="ms").dt.year
        d["gap"] = d.c - d.rv_next30
        g = d.groupby("year")
        t = pd.DataFrame({"days": g.size(), "DVOL_mean": g.c.mean(), "RV_next30_mean": g.rv_next30.mean(),
                          "IV-RV_mean_pts": g.gap.mean(), "IV>RV_%": g.gap.apply(lambda x: (x > 0).mean() * 100),
                          "var_ratio_RV2/IV2": g.apply(lambda x: (x.rv_next30 ** 2).mean() / (x.c ** 2).mean())})
        t.loc["all"] = [len(d), d.c.mean(), d.rv_next30.mean(), d.gap.mean(), (d.gap > 0).mean() * 100,
                        (d.rv_next30 ** 2).mean() / (d.c ** 2).mean()]
        t.insert(0, "asset", a)
        rows.append(t)
    out("iv_rv", pd.concat(rows).round(2))


def options():
    """Deribit option trade SAMPLE: effective spread paid by takers, and buy-and-hold-to-expiry result of bought options."""
    p = os.path.join(DATA, "deribit_opt_trades_sample.parquet")
    if not os.path.exists(p):
        print("no option trade sample yet")
        return
    f = pd.read_parquet(p)
    dl = pd.read_parquet(os.path.join(DATA, "deribit_delivery.parquet"))
    dl["cur"] = dl.idx.str[:3].str.upper()
    dl["expiry"] = (pd.to_datetime(dl.date) + pd.Timedelta(hours=8)).astype("datetime64[ms]").astype("int64")
    f = f.merge(dl[["cur", "expiry", "delivery_price"]], on=["cur", "expiry"], how="left")
    f["T_days"] = (f.expiry - f.timestamp) / 86400000
    F = f.index_price.astype(float)
    sd = f.iv.astype(float) / 100 * np.sqrt(np.maximum(f.T_days, 1 / 24) / 365)
    f["mny_sd"] = np.log(f.strike / F) / sd * np.where(f.cp == "C", 1, -1)     # >0 = out of the money
    f["mny"] = pd.cut(f.mny_sd, [-99, -1, -0.25, 0.25, 1, 2, 99], labels=["ITM>1sd", "ITM", "ATM", "OTM", "OTM>1sd", "far OTM"])
    f["tenor"] = pd.cut(f.T_days, [0, 1, 7, 30, 1e4], labels=["<=1d", "1-7d", "7-30d", ">30d"])
    f["eff_half_spread_%"] = np.where(f.direction == "buy", f.price / f.mark_price - 1, 1 - f.price / f.mark_price) * 100
    S = f.delivery_price.astype(float)
    payoff = np.where(f.cp == "C", np.maximum(S - f.strike, 0), np.maximum(f.strike - S, 0))
    cost = f.price.astype(float) * F                       # USD premium (inverse options quoted in coin)
    f["buy_ret_%"] = (payoff / cost - 1) * 100
    f["year"] = pd.to_datetime(f.timestamp, unit="ms").dt.year
    b = f[(f.direction == "buy") & f.delivery_price.notna() & (cost > 0)]
    g = b.groupby(["cur", "tenor", "mny"], observed=True)
    t = pd.DataFrame({"n": g.size(), "mean_ret_%": g["buy_ret_%"].mean(), "median_ret_%": g["buy_ret_%"].median(),
                      "win_%": g["buy_ret_%"].apply(lambda x: (x > 0).mean() * 100),
                      "prem-weighted_ret_%": g.apply(lambda x: ((x["buy_ret_%"] / 100 + 1) * x.price * x.index_price * x.amount).sum()
                                                     / (x.price * x.index_price * x.amount).sum() * 100 - 100),
                      "eff_half_spread_%": g["eff_half_spread_%"].median()})
    out("opt_buy_to_expiry", t[t.n >= 30].round(1))
    g2 = b[b.mny == "ATM"].groupby(["cur", "year"])
    b = b.assign(after_vda=b["buy_ret_%"] - 31.2 * np.maximum(b["buy_ret_%"], 0) / 100)
    g2 = b[b.mny == "ATM"].groupby(["cur", "year"])
    out("opt_buy_ATM_by_year", pd.DataFrame({"n": g2.size(), "mean_ret_%": g2["buy_ret_%"].mean(),
                                             "median_ret_%": g2["buy_ret_%"].median(),
                                             "after_31.2%_tax_on_wins_%": g2.after_vda.mean(),
                                             "win_%": g2["buy_ret_%"].apply(lambda x: (x > 0).mean() * 100)}).round(1))
    g4 = b.groupby(["cur", "tenor", "mny"], observed=True)
    t4 = pd.DataFrame({"n": g4.size(), "mean_%": g4["buy_ret_%"].mean(), "after_tax_%": g4.after_vda.mean(),
                       "day_clustered_se_%": g4.apply(lambda x: x.groupby(x.timestamp // 86400000)["buy_ret_%"].mean().std()
                                                     / np.sqrt(x.timestamp.floordiv(86400000).nunique()))})
    out("opt_buy_after_tax", t4[t4.n >= 300].round(1))
    g3 = f.groupby(["cur", "mny"], observed=True)["eff_half_spread_%"]
    out("opt_eff_spread", pd.DataFrame({"median": g3.median(), "p75": g3.quantile(.75), "n": g3.size()}).round(2))


def delta():
    """Today's Delta India option chain: quoted spreads and IV vs Deribit."""
    ch = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "delta_chain_*.parquet")))[-1])
    for c in ("strike", "bid", "ask", "mark", "mark_iv", "bid_iv", "ask_iv", "spot", "oi", "volume", "turnover_usd", "delta"):
        ch[c] = pd.to_numeric(ch[c], errors="coerce")
    o = ch[ch.ctype.str.contains("options")].copy()
    o["exp"] = pd.to_datetime(o.symbol.str.split("-").str[-1], format="%d%m%y")
    o["mid"] = (o.bid + o.ask) / 2
    o["spr_%"] = (o.ask - o.bid) / o["mid"] * 100
    o["dist"] = (o.strike / o.spot - 1).abs()
    atm = o[o.bid > 0].sort_values("dist").groupby(["und", "exp", "ctype"]).head(1)
    atm = atm.sort_values(["und", "exp", "ctype"])
    cols = ["und", "exp", "ctype", "strike", "spot", "bid", "ask", "spr_%", "bid_iv", "mark_iv", "ask_iv", "oi", "turnover_usd"]
    out("delta_atm_chain", atm[cols].round(4))
    perp = ch[ch.ctype == "perpetual_futures"]
    l2 = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "delta_l2_*.parquet")))[-1])
    l2["price"] = l2.price.astype(float)
    l2["size"] = l2["size"].astype(float)
    rows = []
    for s, g in l2.groupby("symbol"):
        bb = g[g.side == "buy"].price.max()
        ba = g[g.side == "sell"].price.min()
        rows.append(dict(symbol=s, bid=bb, ask=ba, spread_bp=(ba - bb) / ((ba + bb) / 2) * 1e4,
                         top_bid_contracts=g[(g.side == "buy") & (g.price == bb)]["size"].sum(),
                         top_ask_contracts=g[(g.side == "sell") & (g.price == ba)]["size"].sum()))
    out("delta_perp_book", pd.DataFrame(rows).round(3))
    print(perp[["symbol", "bid", "ask", "mark", "funding", "oi", "turnover_usd"]].to_string())
    der = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "deribit_chain_*.parquet")))[-1])
    der["exp"] = pd.to_datetime(der.instrument_name.str.split("-").str[1], format="%d%b%y")
    der["K"] = der.instrument_name.str.split("-").str[2].astype(float)
    der["dist"] = (der.K / der.underlying_price - 1).abs()
    da = der.sort_values("dist").groupby(["base_currency", "exp"]).head(2).groupby(["base_currency", "exp"]).agg(
        deribit_atm_mark_iv=("mark_iv", "mean"), deribit_spr_pct=("bid_price", "size"))
    da["deribit_spr_pct"] = der.sort_values("dist").groupby(["base_currency", "exp"]).head(2).assign(
        s=lambda x: (x.ask_price - x.bid_price) / ((x.ask_price + x.bid_price) / 2) * 100).groupby(["base_currency", "exp"]).s.mean()
    out("deribit_atm", da.head(30).round(2))


def money():
    """What Rs 1,00,000 holds on Delta India today (contract specs from the API, prices from the chain snapshot)."""
    ch = pd.read_parquet(sorted(glob.glob(os.path.join(DATA, "delta_chain_*.parquet")))[-1])
    for c in ("strike", "bid", "ask", "mark", "spot"):
        ch[c] = pd.to_numeric(ch[c], errors="coerce")
    cap_usd = 100000 / USDINR
    rows = []
    for u, cv in (("BTC", 0.001), ("ETH", 0.01)):
        p = ch[(ch.ctype == "perpetual_futures") & (ch.und == u)].iloc[0]
        notional = float(p.mark) * cv
        rows.append(dict(item=f"{u} perp, 1 contract ({cv} {u})", usd=notional, inr=notional * USDINR,
                         contracts_at_1x=cap_usd / notional, contracts_at_5x=5 * cap_usd / notional,
                         fee_round_trip_inr=notional * 0.0005 * 2 * 1.18 * USDINR))
        o = ch[ch.ctype.str.contains("options") & (ch.und == u) & (ch.bid > 0)].copy()
        o["exp"] = pd.to_datetime(o.symbol.str.split("-").str[-1], format="%d%m%y")
        o["dist"] = (o.strike / o.spot - 1).abs()
        for e in sorted(o.exp.unique())[:3]:
            q = o[(o.exp == e) & o.symbol.str.startswith("C")].sort_values("dist").head(1)
            if not len(q):
                continue
            q = q.iloc[0]
            prem = q.ask * cv
            fee = min(0.0001 * float(q.spot) * cv, 0.035 * prem) * 1.18
            rows.append(dict(item=f"{u} ATM call {q.symbol} (ask)", usd=prem, inr=prem * USDINR,
                             contracts_at_1x=cap_usd / prem, contracts_at_5x=np.nan, fee_round_trip_inr=2 * fee * USDINR,
                             spread_cost_inr=(q.ask - q.bid) * cv * USDINR))
    out("money", pd.DataFrame(rows).round(2))


def tax():
    """After-tax arithmetic, 31.2% on gains (30% + 4% cess), with and without loss set-off."""
    rows = []
    for win in (0.35, 0.45, 0.5, 0.55, 0.65):
        for rr in (0.5, 1.0, 2.0, 3.0):
            # per-trade: win +rr units, loss -1 unit. Gross expectancy E = win*rr - (1-win)
            E = win * rr - (1 - win)
            vda = win * rr * (1 - 0.312) - (1 - win)                   # every winning trade taxed, losses ignored
            biz = E * (1 - 0.312) if E > 0 else E                       # yearly net taxed only if positive
            # breakeven win rate for this rr under VDA
            be_vda = 1 / (1 + rr * 0.688)
            be_gross = 1 / (1 + rr)
            rows.append(dict(win_rate=win, reward_risk=rr, gross_exp_R=E, net_VDA_R=vda, net_business_R=biz,
                             breakeven_win_gross=be_gross, breakeven_win_VDA=be_vda))
    out("tax", pd.DataFrame(rows).round(3))


def basis():
    """Delta India perp vs Binance USDT perp: price gap, 60-min return agreement, funding comparison."""
    rows = []
    fu = pd.read_parquet(os.path.join(DATA, "bn_funding.parquet"))
    for a in SYMS:
        d = pd.read_parquet(os.path.join(DATA, f"delta_perp_{a}USD_1m.parquet"))
        d["t"] = d.time // 60
        b = m1(a)[["t", "c"]]
        x = d.merge(b, on="t", suffixes=("_d", "_b"))
        x = x.set_index("t")
        gap = (x.close / x.c - 1) * 1e4
        h = x[x.index % 60 == 0]
        rd = np.log(h.close).diff()
        rb = np.log(h.c).diff()
        ok = h.index.to_series().diff() == 60
        df = pd.read_parquet(os.path.join(DATA, f"delta_funding_{a}USD.parquet"))
        bf = fu[fu.sym == SYMS[a]].copy()
        bf["time"] = bf.ts // 1000
        mf = df.merge(bf, on="time")
        rows.append(dict(asset=a, minutes=len(x), first=pd.to_datetime(x.index.min() * 60, unit="s"),
                         gap_median_bp=gap.median(), gap_p5_bp=gap.quantile(.05), gap_p95_bp=gap.quantile(.95),
                         corr_1h_returns=np.corrcoef(rd[ok].fillna(0), rb[ok].fillna(0))[0, 1],
                         mean_abs_1h_ret_diff_bp=(rd[ok] - rb[ok]).abs().mean() * 1e4,
                         delta_volume_share_of_minutes_zero=(d.volume == 0).mean(),
                         funding_matched=len(mf), delta_funding_mean=mf.close.mean(),
                         binance_funding_mean_pct=mf.rate.astype(float).mean() * 100,
                         funding_corr=np.corrcoef(mf.close, mf.rate.astype(float))[0, 1] if len(mf) > 10 else np.nan))
    out("basis", pd.DataFrame(rows).T)


SECTIONS = dict(basis=basis, hours=hours, range=drange, persist=persist, funding=funding, oi=oi, events=events, vol=vol,
                options=options, delta=delta, money=money, tax=tax)

if __name__ == "__main__":
    for s in (sys.argv[1:] or SECTIONS):
        SECTIONS[s]()
