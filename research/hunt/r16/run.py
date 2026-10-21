"""R16 (PREREG.md) evaluation.

    python3 -I research/hunt/r16/run.py design <scratch>   # DESIGN only (holdout rows are dropped before anything)
    python3 -I research/hunt/r16/run.py hold <scratch>     # ONCE: frozen choices, writes HOLDOUT_RUN.flag

Outputs (scratch): design.json / hold.json (every test), choices.json (frozen in design), preds_*.parquet.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import lib  # noqa: E402

SYMS = ("BANKNIFTY", "NIFTY")
HOLD_INTRA = {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13)}
HOLD_DAILY = date(2026, 4, 9)
LOT = {"BANKNIFTY": 30, "NIFTY": 65}
HS = 0.0016

INTRA_F = ["t", "r1", "r5", "r15", "r30", "r60", "lr5", "lr15", "lr30", "lr60", "lrso", "ropen", "gap", "dvwap", "pos",
           "lvix", "pret", "plr", "m5", "m20", "lrso_rel", "wd", "dte", "isexp", "oice_open", "oipe_open", "oice15",
           "oipe15", "oice30", "oipe30", "dpcr_open"]
VOL_F = INTRA_F + []          # + btod{h} added per horizon
DIR_F = ["t", "r1", "r5", "r15", "r30", "r60", "ropen", "gap", "dvwap", "pos", "lvix", "pret", "wd", "dte",
         "oice_open", "oipe_open", "oice15", "oipe15", "oice30", "oipe30", "dpcr_open"]
DAILY_F = ["lr1", "lr5", "lr22", "lvix", "absret", "wd", "mexp_week"]


def regime_f(t):
    return [f"{k}_{t}" for k in ("er_open", "lrso", "lrso_rel", "ropen", "oice_open", "oipe_open", "dpcr_open", "r15",
                                  "r30", "dvwap", "pos")] + ["gap", "lvix", "plr", "pret", "dte", f"prev_trend{t}", f"prev_er{t}"]


def vix_fit(target, col="lvix"):
    def f(tr, te):
        ok = tr[[target, col]].dropna()
        b, a = np.polyfit(ok[col], ok[target], 1)
        return {"bvix": a + b * te[col].values}
    return f


# ------------------------------------------------------------------------------------------------ magnitude
def cnn_walk(scr, sym, df, feats, target):
    import seq
    S = seq.sequences(scr, sym, df)
    pred = pd.Series(np.nan, index=df.index)
    for m, end, test_days in lib.folds(df.day.values, None):
        tr = df.index[(df.day < end).values]
        te = df.index[df.day.isin(test_days).values]
        if len(tr) < 100 or not len(te):
            continue
        pred.loc[te] = seq.fit_predict(S[tr], df.loc[tr, feats].values.astype(float), df.loc[tr, target].values,
                                       S[te], df.loc[te, feats].values.astype(float))
    return pred


def magnitude(name, df, target, feats, baselines, mode, choices, cut, first_test=None, scr=None, sym=None):
    df = df[df[target].notna()].reset_index(drop=True)
    if mode == "design":
        df = df[df.day < cut].reset_index(drop=True)
    res = {}
    preds = {}
    for kind in ("linear", "lgbm", "cnn"):
        if mode == "hold" and kind != choices[name]["model"]:
            continue
        if kind == "cnn":
            if mode != "hold":
                continue                  # the CNN's DESIGN run is seq.py
            preds["cnn"] = cnn_walk(scr, sym, df, feats, target)
            _, ext = lib.walk(df, feats, target, None, "reg", first_test, extra=vix_fit(target))
        else:
            preds[kind], ext = lib.walk(df, feats, target, kind, "reg", first_test, extra=vix_fit(target))
        preds["bvix"] = ext["bvix"]
    base = {b: (preds["bvix"] if b == "bvix" else df[b]) for b in baselines}
    ok = df.index[preds[list(p for p in preds if p != "bvix")[0]].notna() & preds["bvix"].notna()]
    for b in baselines:
        ok = ok.intersection(df.index[base[b].notna()])
    sel = ok[(df.loc[ok, "day"] >= cut).values] if mode == "hold" else ok
    y = df.loc[sel, target]
    mae = lambda x: float(np.mean(np.abs(x.loc[sel] - y)))
    rmse = lambda x: float(np.sqrt(np.mean((x.loc[sel] - y) ** 2)))
    res["n"], res["sessions"] = int(len(sel)), int(df.loc[sel, "day"].nunique())
    res["baselines"] = {b: dict(mae=mae(base[b]), rmse=rmse(base[b])) for b in baselines}
    res["models"] = {k: dict(mae=mae(preds[k]), rmse=rmse(preds[k])) for k in preds if k != "bvix"}
    if mode == "design":
        bb = min(baselines, key=lambda b: res["baselines"][b]["mae"])
        bm = min(res["models"], key=lambda k: res["models"][k]["mae"])
        choices[name] = dict(model=bm, baseline=bb)
    bb, bm = choices[name]["baseline"], choices[name]["model"]
    d = np.abs(base[bb].loc[sel] - y) - np.abs(preds[bm].loc[sel] - y)
    p, _ = lib.boot_mean_p(d.values, df.loc[sel, "day"].values)
    res.update(best_baseline=bb, model=bm, skill=1 - res["models"][bm]["mae"] / res["baselines"][bb]["mae"], p=p)
    # interval coverage: in-sample residual quantiles are not stored; report the share within +-log(1.5)
    res["within_x1.5"] = float(np.mean(np.abs(preds[bm].loc[sel] - y) <= np.log(1.5)))
    res["within_x1.5_base"] = float(np.mean(np.abs(base[bb].loc[sel] - y) <= np.log(1.5)))
    out = df.loc[sel, ["day", target] + (["t"] if "t" in df else [])].assign(pred=preds[bm].loc[sel], base=base[bb].loc[sel])
    return res, out


# ------------------------------------------------------------------------------------------------ classification
def classify(name, df, target, feats, mode, choices, cut, yday=None, first_test=None):
    df = df[df[target].notna()].reset_index(drop=True)
    if mode == "design":
        df = df[df.day < cut].reset_index(drop=True)
    preds = {}
    rate = {}

    def base_rate(tr, te):
        return {"rate": np.full(len(te), tr[target].mean())}
    for kind in ("linear", "lgbm"):
        if mode == "hold" and kind != choices[name]["model"]:
            continue
        p, ext = lib.walk(df, feats, target, kind, "clf", first_test, extra=base_rate)
        preds[kind] = p
        rate = ext["rate"]
    k0 = list(preds)[0]
    ok = df.index[preds[k0].notna()]
    sel = ok[(df.loc[ok, "day"] >= cut).values] if mode == "hold" else ok
    y = df.loc[sel, target].values
    res = {"models": {}}
    for k in preds:
        m = []
        for i in range(1):
            m = lib.clf_metrics(y, preds[k].loc[sel].values, 0.5)
        r = rate.loc[sel].values
        yy = np.clip(r, 1e-4, 1 - 1e-4)
        m["ll_base"] = float(-np.mean(y * np.log(yy) + (1 - y) * np.log(1 - yy)))
        m["brier_base"] = float(np.mean((r - y) ** 2))
        m["acc_base"] = float(np.mean((r >= 0.5) == y))
        res["models"][k] = m
    if yday is not None:
        yd = df.loc[sel, yday].values
        good = ~np.isnan(yd)
        res["yday_acc"] = float(np.mean(yd[good] == y[good]))
        q = np.clip(yd[good], 0.05, 0.95)
        res["yday_ll"] = float(-np.mean(y[good] * np.log(q) + (1 - y[good]) * np.log(1 - q)))
    if mode == "design":
        choices[name] = dict(model=max(res["models"], key=lambda k: res["models"][k]["auc"]))
    bm = choices[name]["model"]
    p, auc = lib.boot_auc_p(y, preds[bm].loc[sel].values, df.loc[sel, "day"].values)
    res.update(model=bm, p=p, auc=auc, n=int(len(sel)), sessions=int(df.loc[sel, "day"].nunique()),
               calib=lib.calib(y, preds[bm].loc[sel].values).reset_index(drop=True).to_dict("records"))
    out = df.loc[sel].assign(pred=preds[bm].loc[sel])
    return res, out


def trade(out, sym, h, mult=1.0, rng=None):
    """Buy ATM CE when p >= .55, PE when p <= .45; exit at the horizon close. Net Rs per trade, 1 lot."""
    from obuy.costs import Costs
    c = Costs()
    lot = LOT[sym]
    o = out[out[f"ce_in{h}"].notna() & out[f"pe_in{h}"].notna()].copy()

    def net(side):
        ein = np.where(side > 0, o[f"ce_in{h}"], o[f"pe_in{h}"]) * (1 + HS * mult)
        eout = np.where(side > 0, o[f"ce_out{h}"], o[f"pe_out{h}"]) * (1 - HS * mult)
        ch = np.array([c.charge(True, a, lot) + c.charge(False, b, lot) for a, b in zip(ein, eout)])
        return (eout - ein) * lot - ch
    side = np.where(o.pred >= 0.55, 1, np.where(o.pred <= 0.45, -1, 0))
    m = side != 0
    if m.sum() < 10:
        return dict(trades=int(m.sum()))
    o, side = o[m], side[m]
    n = net(side)
    # random twin: same entries, random side (precompute both sides)
    nc, npe = net(np.ones(len(o))), net(-np.ones(len(o)))
    rng = rng or np.random.default_rng(16)
    tw = np.array([np.where(rng.random(len(o)) < 0.5, nc, npe).mean() for _ in range(lib.B)])
    hit = float(np.mean(np.sign(o[f"ret{h}"].values) == side))
    return dict(trades=int(len(n)), per_day=float(len(n) / out.day.nunique()), hit=hit, rs_trade=float(n.mean()),
                t=float(n.mean() / (n.std(ddof=1) / np.sqrt(len(n)))), net=float(n.sum()),
                twin_pct=float(np.mean(tw < n.mean())), twin_mean=float(tw.mean()))


# ------------------------------------------------------------------------------------------------ expiry / max pain
def expiry(scr, sym, mode, choices, cut, maxday=None):
    e = pd.read_csv(os.path.join(scr, "expiries.csv"), parse_dates=["expiry", "prev"])
    e = e[(e.sym == sym) & e.max_pain.notna()].copy()
    if maxday is not None:
        e = e[e.expiry.dt.date < maxday]
    y = pd.read_csv(os.path.join(scr, f"daily_{sym}.csv"), parse_dates=["day"])
    e = e.merge(y.rename(columns={"day": "expiry"}), on="expiry", how="inner")
    e["day"] = e.expiry.dt.date
    e = e.sort_values("day").reset_index(drop=True)
    if mode == "design":
        e = e[e.day < cut].reset_index(drop=True)
    e["dist"] = (e.max_pain - e.open) / e.open * 100
    e["move"] = (e.close - e.open) / e.open * 100
    # P2 shrink weight: least squares of move on dist using only expiries before each one (expanding, 5-expiry embargo)
    w = np.full(len(e), np.nan)
    for i in range(len(e)):
        tr = e.iloc[:max(0, i - 5)]
        if len(tr) >= 20:
            w[i] = float((tr.dist * tr.move).sum() / (tr.dist ** 2).sum())
    e["w"] = w
    e = e[e.w.notna()]
    sel = e[e.day >= cut] if mode == "hold" else e
    l0 = np.abs(sel.move)                                   # no change
    l1 = np.abs(sel.move - sel.dist)                        # close = max pain
    l2 = np.abs(sel.move - sel.w * sel.dist)                # shrink
    res = dict(n=int(len(sel)), mae_nochange=float(l0.mean()), mae_maxpain=float(l1.mean()), mae_shrink=float(l2.mean()),
               w_last=float(sel.w.iloc[-1]), pin_maxpain=float(np.mean(np.abs(sel.close - sel.max_pain) / sel.open <= 0.0025)),
               pin_open=float(np.mean(np.abs(sel.close - sel.open) / sel.open <= 0.0025)),
               med_abs_dist=float(np.median(np.abs(sel.dist))), first=str(sel.day.min()), last=str(sel.day.max()))
    name = f"d_{sym}"
    if mode == "design":
        choices[name] = dict(model="maxpain" if l1.mean() < l2.mean() else "shrink")
    lm = l1 if choices[name]["model"] == "maxpain" else l2
    p, _ = lib.boot_mean_p((l0 - lm).values, np.arange(len(sel)))
    res.update(model=choices[name]["model"], skill=float(1 - lm.mean() / l0.mean()), p=p)
    return res


# ------------------------------------------------------------------------------------------------ analogs
def analogs(S, sym, target, mode, cut, k=25):
    pathc = [f"path{j}" for j in range(12)] + ["gap_n", "vix_pct"]
    S = S.dropna(subset=pathc + [target]).reset_index(drop=True)
    if mode == "design":
        S = S[S.day < cut].reset_index(drop=True)
    rows = []
    for m, train_end, test_days in lib.folds(S.day.values, None):
        tr = S[S.day < train_end]
        te = S[S.day.isin(test_days)]
        if len(tr) < 60:
            continue
        mu, sd = tr[pathc].mean(), tr[pathc].std().replace(0, 1)
        X, Xt = ((tr[pathc] - mu) / sd).values, ((te[pathc] - mu) / sd).values
        ytr = tr[target].values
        q1, q2 = np.quantile(ytr, [1 / 3, 2 / 3])
        bins = lambda v: np.digitize(v, [q1, q2])
        btr = bins(ytr)
        for i, (x, yv, d) in enumerate(zip(Xt, te[target].values, te.day.values)):
            nn = np.argsort(((X - x) ** 2).sum(1))[:k]
            ens = ytr[nn]
            ca, cu = lib.crps_ens(ens, yv), lib.crps_ens(ytr, yv)
            b = bins(np.array([yv]))[0]
            pa = (np.bincount(btr[nn], minlength=3) + 1) / (k + 3)
            pu = (np.bincount(btr, minlength=3) + 1) / (len(btr) + 3)
            rows.append(dict(day=d, ca=ca, cu=cu, la=-np.log(pa[b]), lu=-np.log(pu[b])))
    R = pd.DataFrame(rows)
    sel = R[R.day >= cut] if mode == "hold" else R
    p, _ = lib.boot_mean_p((sel.cu - sel.ca).values, sel.day.values)
    return dict(n=int(len(sel)), crps_analog=float(sel.ca.mean()), crps_uncond=float(sel.cu.mean()),
                skill=float(1 - sel.ca.mean() / sel.cu.mean()), ll_analog=float(sel.la.mean()), ll_uncond=float(sel.lu.mean()), p=p)


# ------------------------------------------------------------------------------------------------ main
def main():
    mode, scr = sys.argv[1], sys.argv[2]
    flag = os.path.join(scr, "HOLDOUT_RUN.flag")
    cf = os.path.join(scr, "choices.json")
    dry = mode == "dry"          # code check of the hold path on DESIGN data only (fake cut 3 months earlier)
    real_intra, real_daily = dict(HOLD_INTRA), HOLD_DAILY
    if dry:
        mode = "hold"
        globals()["HOLD_DAILY"] = (pd.Timestamp(HOLD_DAILY) - pd.DateOffset(months=3)).date()
        for k in HOLD_INTRA:
            HOLD_INTRA[k] = (pd.Timestamp(HOLD_INTRA[k]) - pd.DateOffset(months=3)).date()
        choices = json.load(open(cf))
    elif mode == "hold":
        if os.path.exists(flag):
            sys.exit("holdout already read once (flag exists) - refusing")
        open(flag, "w").write(pd.Timestamp.now().isoformat())
        choices = json.load(open(cf))
    else:
        choices = {}
    R = {}
    for sym in SYMS:
        cut = HOLD_INTRA[sym]
        A = pd.read_parquet(os.path.join(scr, f"rows_{sym}_intra.parquet"))
        S = pd.read_parquet(os.path.join(scr, f"rows_{sym}_day.parquet"))
        Dd = pd.read_parquet(os.path.join(scr, f"rows_{sym}_daily.parquet"))
        for X in (A, S, Dd):
            X["day"] = pd.to_datetime(X.day).dt.date
        if dry:
            A, S = A[A.day < real_intra[sym]], S[S.day < real_intra[sym]]
            Dd = Dd[Dd.day < real_daily]
        # a1 / a2
        for h, name in ((30, "a1"), (60, "a2")):
            df = A[(A.t >= 30) & (A.t <= 360 - h)]
            r, o = magnitude(f"{name}_{sym}", df, f"v{h}", VOL_F + [f"btod{h}"], [f"btod{h}", f"blast{h}", "bvix"],
                             mode, choices, cut, scr=scr, sym=sym)
            R[f"{name}_{sym}"] = r
            o.to_parquet(os.path.join(scr, f"preds_{'dry' if dry else mode}_{name}_{sym}.parquet"))
            print(f"{name}_{sym}", {k: r[k] for k in ("model", "best_baseline", "skill", "p", "n")}, flush=True)
        # a3b rest-of-day range at 09:30
        r, o = magnitude(f"a3b_{sym}", S, "v_rest", VOL_F + ["btod_rest"], ["btod_rest", "bvix"], mode, choices, cut)
        R[f"a3b_{sym}"] = r
        o.to_parquet(os.path.join(scr, f"preds_{'dry' if dry else mode}_a3b_{sym}.parquet"))
        print(f"a3b_{sym}", {k: r[k] for k in ("model", "best_baseline", "skill", "p", "n")}, flush=True)
        # a3 next-day range (daily, long history)
        r, o = magnitude(f"a3_{sym}", Dd, "y", DAILY_F, ["b20", "bvix"], mode, choices, HOLD_DAILY, first_test="2014-01")
        R[f"a3_{sym}"] = r
        o.to_parquet(os.path.join(scr, f"preds_{'dry' if dry else mode}_a3_{sym}.parquet"))
        print(f"a3_{sym}", {k: r[k] for k in ("model", "best_baseline", "skill", "p", "n")}, flush=True)
        # b direction
        for h in (15, 30, 60):
            df = A[A.t <= 360 - h]
            r, o = classify(f"b{h}_{sym}", df, f"y{h}", DIR_F, mode, choices, cut)
            r["trade"] = trade(o, sym, h)
            r["trade_2x"] = trade(o, sym, h, mult=2.0)
            R[f"b{h}_{sym}"] = r
            print(f"b{h}_{sym}", r["model"], round(r["auc"], 4), r["p"], r["trade"], flush=True)
        # c regime
        for t in (30, 45):
            r, o = classify(f"c{t}_{sym}", S, f"trend{t}", regime_f(t), mode, choices, cut, yday=f"prev_trend{t}")
            R[f"c{t}_{sym}"] = r
            print(f"c{t}_{sym}", r["model"], round(r["auc"], 4), r["p"], r["models"][r["model"]]["acc"], r.get("yday_acc"), flush=True)
        # d expiry / max pain
        R[f"d_{sym}"] = expiry(scr, sym, mode, choices, HOLD_DAILY, maxday=real_daily if dry else None)
        print(f"d_{sym}", R[f"d_{sym}"], flush=True)
        # e analogs
        for tg, nm in (("e_ret", "e1"), ("e_rng", "e2")):
            R[f"{nm}_{sym}"] = analogs(S, sym, tg, mode, cut)
            print(f"{nm}_{sym}", R[f"{nm}_{sym}"], flush=True)
    if mode == "design":
        json.dump(choices, open(cf, "w"), indent=1)
    if mode == "hold" and not dry:
        json.dump(choices, open(os.path.join(scr, "choices_used_in_hold.json"), "w"), indent=1)
    # BH over the 24 primary tests
    prim = [k for k in R if k.split("_")[0] in ("a1", "a2", "a3", "a3b", "b15", "b30", "b60", "c30", "c45", "d", "e1", "e2")]
    q = lib.bh([R[k]["p"] for k in prim])
    for k, qq in zip(prim, q):
        R[k]["q"] = float(qq)
    json.dump(R, open(os.path.join(scr, f"{'dry' if dry else mode}.json"), "w"), indent=1, default=str)
    print("tests", len(prim))


if __name__ == "__main__":
    main()
