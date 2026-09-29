"""One direction model from everything: price action, options (OI walls, GEX, straddle, skew, PCR), breadth, and
(when given) NIFTY lead-lag, India VIX and the futures basis. Walk-forward by month over BOTH years.

    python research/direction_model.py <prev_year_wide.parquet> <year_wide.parquet> [--stocks bank_stocks.parquet]
                                       [--extra extra_series.parquet] [out.md]

Question answered: at a 5-minute decision point (09:30-14:30), which way is the index N minutes later? Reported as
accuracy vs selectivity: overall, and on only the most confident 30 / 20 / 10 / 5% of moments (the "abstain" curve),
next to the classic rules (follow the last candle, follow the day, PCR, OI walls) and a coin flip.
"""
from __future__ import annotations

import os
import sys
import warnings

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from ml_long import grid, N  # noqa: E402

warnings.filterwarnings("ignore")
HORIZONS = (5, 15, 30, 60)
BANKS = ("HDFCBANK", "ICICIBANK", "SBIN", "AXISBANK", "KOTAKBANK")
M0 = 9 * 60 + 15


def load_days(path):
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    df["expiry"] = df.expiry.dt.date
    days = []
    for day, g in df.groupby(df.day.dt.date):
        ix = g[g.right == "IX"].sort_values("ts").set_index("ts")
        if len(ix) < 300:
            continue
        o = g[g.right != "IX"]
        exps = sorted(e for e in o.expiry.unique() if e > day)
        if not exps:
            continue
        ch = o[o.expiry == exps[0]]
        I = grid(ix, ["open", "high", "low", "close"])
        # per-strike OI and close grids (5-minute resolution is enough)
        oi = {}
        px = {}
        for (k, r), s in ch.groupby(["strike", "right"]):
            if len(s) < 60:
                continue
            gg = grid(s.sort_values("ts").set_index("ts"), ["close", "open_interest"])
            oi[(k, r)] = pd.Series(gg["open_interest"]).ffill().fillna(0).values
            px[(k, r)] = gg["close"]
        if not oi:
            continue
        days.append(dict(day=day, exp=exps[0], I=I, oi=oi, px=px, ks=np.array(sorted({k for k, _ in oi}))))
    return days


def add_stocks(days, path):
    st = pd.read_parquet(path)
    st["ts"] = pd.to_datetime(st.ts)
    st["date"] = st.ts.dt.date
    prev = st.groupby(["symbol", "date"]).close.last().groupby(level=0).shift(1)
    by = {d["day"]: d for d in days}
    for date_, s in st.groupby("date"):
        d = by.get(date_)
        if d is None:
            continue
        adv = np.zeros(N)
        hv = np.zeros(N)
        n = 0
        banks = {}
        for sym, g in s.groupby("symbol"):
            gg = grid(g.set_index("ts"), ["close"])
            pc = prev.get((sym, date_), np.nan)
            if np.isnan(pc):
                continue
            adv += (gg["close"] > pc)
            banks[sym] = gg["close"]
            if sym in ("HDFCBANK", "ICICIBANK"):
                hv += (gg["close"] / pc - 1) * 1e4
            n += 1
        d["adv"] = adv
        d["heavy"] = hv / 2
        d["banks"] = banks


def add_extra(days, path):
    ex = pd.read_parquet(path)
    ex["ts"] = pd.to_datetime(ex.ts)
    by = {d["day"]: d for d in days}
    for name in ("NIFTY", "INDIAVIX", "BNFUT"):
        s = ex[ex.series == name]
        for date_, g in s.groupby(s.ts.dt.date):
            d = by.get(date_)
            if d is None:
                continue
            d[name] = grid(g.set_index("ts"), ["close"])["close"]


def features(days):
    rows = []
    for i, d in enumerate(days):
        I = d["I"]
        c = I["close"]
        prev = days[i - 1] if i else None
        o0 = I["open"][0]
        gap = (o0 / prev["I"]["close"][-1] - 1) * 1e4 if prev else 0.0
        prev_ret = (prev["I"]["close"][-1] / prev["I"]["open"][0] - 1) * 1e4 if prev else 0.0
        tp = (I["high"] + I["low"] + c) / 3
        vw = np.cumsum(tp) / np.arange(1, N + 1)
        ks = d["ks"]
        dte = (d["exp"] - d["day"]).days
        for t in range(15, 316, 5):
            s = c[t - 1]
            lr = lambda a, k: (a[t - 1] / a[max(t - 1 - k, 0)] - 1) * 1e4
            # option-chain features at t-1 (5-min grid -> use t-1 directly, ffilled)
            oi_ce = {k: d["oi"][(k, "CE")][t - 1] for k in ks if (k, "CE") in d["oi"]}
            oi_pe = {k: d["oi"][(k, "PE")][t - 1] for k in ks if (k, "PE") in d["oi"]}
            oi_ce0 = {k: d["oi"][(k, "CE")][max(t - 31, 0)] for k in oi_ce}
            oi_pe0 = {k: d["oi"][(k, "PE")][max(t - 31, 0)] for k in oi_pe}
            up_wall = max(oi_ce, key=lambda k: oi_ce[k]) if oi_ce else s
            dn_wall = max(oi_pe, key=lambda k: oi_pe[k]) if oi_pe else s
            tot_ce, tot_pe = sum(oi_ce.values()), sum(oi_pe.values())
            tot_ce0, tot_pe0 = sum(oi_ce0.values()), sum(oi_pe0.values())
            atm = ks[np.argmin(np.abs(ks - s))]
            ce_px, pe_px = d["px"].get((atm, "CE")), d["px"].get((atm, "PE"))
            strad = (ce_px[t - 1] + pe_px[t - 1]) if ce_px is not None and pe_px is not None else np.nan
            skew = ((ce_px[t - 1] - pe_px[t - 1]) / strad) if strad and strad > 0 else 0.0
            f = dict(
                day=d["day"], t=t, tod=t, dow=d["day"].weekday(), dte=dte,
                r5=lr(c, 5), r15=lr(c, 15), r30=lr(c, 30), r60=lr(c, 60), r_open=(s / o0 - 1) * 1e4,
                gap=gap, prev_ret=prev_ret, rv30=np.std(np.diff(np.log(c[max(t - 31, 0):t]))) * 1e4,
                day_pos=(s - I["low"][:t].min()) / max(I["high"][:t].max() - I["low"][:t].min(), 1),
                vwap_d=(s / vw[t - 1] - 1) * 1e4,
                up_wall_d=(up_wall - s) / s * 1e4, dn_wall_d=(s - dn_wall) / s * 1e4,
                wall_pos=(s - dn_wall) / max(up_wall - dn_wall, 1),
                pcr=tot_pe / max(tot_ce, 1), pcr_d30=(tot_pe / max(tot_ce, 1)) - (tot_pe0 / max(tot_ce0, 1)),
                ce_oi_d30=(tot_ce / max(tot_ce0, 1) - 1) * 100, pe_oi_d30=(tot_pe / max(tot_pe0, 1) - 1) * 100,
                strad=strad / s * 1e4 if strad else np.nan, skew=skew,
                adv=d["adv"][t - 1] if "adv" in d else np.nan, heavy=d["heavy"][t - 1] if "heavy" in d else np.nan,
                nifty_r15=((d["NIFTY"][t - 1] / d["NIFTY"][t - 16] - 1) * 1e4 - lr(c, 15)) if "NIFTY" in d else np.nan,
                nifty_r5=((d["NIFTY"][t - 1] / d["NIFTY"][t - 6] - 1) * 1e4 - lr(c, 5)) if "NIFTY" in d else np.nan,
                vix=d["INDIAVIX"][t - 1] if "INDIAVIX" in d else np.nan,
                **{f"lead_{b}": ((d["banks"][b][t - 1] / d["banks"][b][t - 6] - 1) * 1e4 - lr(c, 5))
                   if "banks" in d and b in d["banks"] else np.nan for b in BANKS},
                vix_d30=(d["INDIAVIX"][t - 1] / d["INDIAVIX"][max(t - 31, 0)] - 1) * 100 if "INDIAVIX" in d else np.nan,
                basis=(d["BNFUT"][t - 1] - s) / s * 1e4 if "BNFUT" in d else np.nan,
                basis_d30=((d["BNFUT"][t - 1] - s) - (d["BNFUT"][max(t - 31, 0)] - c[max(t - 31, 0)])) if "BNFUT" in d else np.nan,
            )
            for h in HORIZONS:
                f[f"y{h}"] = np.sign(c[min(t - 1 + h, N - 1)] - s)
                f[f"m{h}"] = c[min(t - 1 + h, N - 1)] - s
            rows.append(f)
    df = pd.DataFrame(rows)
    return df.replace([np.inf, -np.inf], np.nan)


FEATS = ["tod", "dow", "dte", "r5", "r15", "r30", "r60", "r_open", "gap", "prev_ret", "rv30", "day_pos", "vwap_d",
         "up_wall_d", "dn_wall_d", "wall_pos", "pcr", "pcr_d30", "ce_oi_d30", "pe_oi_d30", "strad", "skew", "adv", "heavy",
         "nifty_r15", "nifty_r5", "vix", "vix_d30", "basis", "basis_d30"] + [f"lead_{b}" for b in BANKS]


def walk_forward(df, h, min_months=6):
    from sklearn.ensemble import HistGradientBoostingClassifier
    df = df[df[f"y{h}"] != 0].copy()
    df["m"] = pd.to_datetime(df.day).dt.strftime("%Y-%m")
    months = sorted(df.m.unique())
    df["p"] = np.nan
    used = [f for f in FEATS if df[f].notna().mean() > 0.3]
    for i, m in enumerate(months):
        if i < min_months:
            continue
        tr, te = df[df.m < m], df.m == m
        fold = [f for f in used if tr[f].notna().mean() > 0.3 and tr[f].nunique() > 2]
        mdl = HistGradientBoostingClassifier(max_depth=3, learning_rate=0.05, max_iter=200, min_samples_leaf=100,
                                             l2_regularization=1.0)
        mdl.fit(tr[fold].values, (tr[f"y{h}"] > 0).astype(int).values)
        used_last = fold
        df.loc[te, "p"] = mdl.predict_proba(df.loc[te, fold].values)[:, 1]
    ev = df.dropna(subset=["p"]).copy()
    ev["conf"] = (ev.p - 0.5).abs()
    ev["pred"] = np.where(ev.p >= 0.5, 1, -1)
    ev["hit"] = ev.pred == ev[f"y{h}"]
    return ev, used_last, mdl


def curve(ev, h, alld_n):
    lines = []
    for q, name in ((0, "all moments"), (0.7, "top 30% most confident"), (0.8, "top 20%"), (0.9, "top 10%"), (0.95, "top 5%")):
        thr = ev.conf.quantile(q)
        x = ev[ev.conf >= thr]
        halves = x.day < sorted(x.day.unique())[len(x.day.unique()) // 2]
        per_day = len(x) / max(x.day.nunique(), 1)
        lines.append(f"| {name} | {len(x)} | {per_day:.1f} | {100 * x.hit.mean():.1f}% | "
                     f"{100 * x.hit[halves].mean():.1f}% / {100 * x.hit[~halves].mean():.1f}% | "
                     f"{(x.pred * x[f'm{h}']).mean():+.1f} |")
    return lines


def rules(df, h):
    y = df[f"y{h}"]
    out = []
    for name, sig in (("follow last 5 min", np.sign(df.r5)), ("follow last 15 min", np.sign(df.r15)),
                      ("follow the day so far", np.sign(df.r_open)), ("fade last 15 min", -np.sign(df.r15)),
                      ("PCR rising -> up (pcr_d30)", np.sign(df.pcr_d30)),
                      ("closer to the put wall -> up (wall_pos < 0.5)", np.sign(0.5 - df.wall_pos)),
                      ("NIFTY led up in last 5 min", np.sign(df.nifty_r5)), ("VIX falling 30 min -> up", -np.sign(df.vix_d30)),
                      ("basis rising 30 min -> up", np.sign(df.basis_d30)), ("breadth: 8+ advancing -> up", np.sign(df.adv - 6)),
                      ("HDFC & ICICI both led the index up/down in last 5 min",
                       np.where((np.sign(df.lead_HDFCBANK) == np.sign(df.lead_ICICIBANK)), np.sign(df.lead_HDFCBANK), 0)),
                      ("top-5 banks: majority led up/down in last 5 min", np.sign(sum(np.sign(df[f"lead_{b}"].fillna(0)) for b in BANKS)))):
        sig = pd.Series(np.asarray(sig, dtype=float), index=df.index)
        m = (sig != 0) & (y != 0) & sig.notna()
        if m.sum() < 100:
            continue
        out.append(f"| {name} | {m.sum()} | {100 * (sig[m] == y[m]).mean():.1f}% |")
    return out


def main():
    opts = {sys.argv[i]: sys.argv[i + 1] for i in range(1, len(sys.argv) - 1) if sys.argv[i].startswith("--")}
    taken = set(opts.values())
    args = [a for a in sys.argv[1:] if not a.startswith("--") and a not in taken]
    paths = [a for a in args if a.endswith(".parquet")]
    out_md = next((a for a in args if a.endswith(".md")), None)
    days = []
    for p in paths:
        days += load_days(p)
    days.sort(key=lambda d: d["day"])
    if "--stocks" in opts:
        add_stocks(days, opts["--stocks"])
    if "--extra" in opts:
        add_extra(days, opts["--extra"])
    df = features(days)
    out = [f"## Direction model, BANKNIFTY {days[0]['day']} .. {days[-1]['day']} ({len(days)} days, {len(df)} decision points)",
           "", "Walk-forward: each month predicted by a model trained only on earlier months (first 6 months are training only).",
           f"Features available: " + ", ".join(f for f in FEATS if df[f].notna().mean() > 0.5), ""]
    for h in HORIZONS:
        ev, used, mdl = walk_forward(df, h)
        out += [f"### {h} minutes ahead", "", f"Base rate (share up): {100 * (ev[f'y{h}'] > 0).mean():.1f}%. Coin flip = 50%.", "",
                "| selectivity | moments | per day | direction correct | 1st / 2nd half | avg move our way (pts) |",
                "|---|---|---|---|---|---|"] + curve(ev, h, len(days)) + ["", "**Classic rules at the same horizon**", "",
                "| rule | moments | correct |", "|---|---|---|"] + rules(ev, h) + [""]
        try:
            from sklearn.inspection import permutation_importance
            last = sorted(ev.m.unique())[-3:]
            te = ev[ev.m.isin(last)]
            imp = permutation_importance(mdl, te[used].values, (te[f"y{h}"] > 0).astype(int).values, n_repeats=3, random_state=0)
            top = sorted(zip(used, imp.importances_mean), key=lambda x: -x[1])[:8]
            out += ["Most useful inputs (last 3 months): " + ", ".join(f"{f} {v:.3f}" for f, v in top), ""]
        except Exception as e:  # noqa: BLE001
            out.append(f"(importance skipped: {e})")
        print("\n".join(out[-24:]), flush=True)
    text = "\n".join(out)
    if out_md:
        open(out_md, "w").write(text)


if __name__ == "__main__":
    main()
