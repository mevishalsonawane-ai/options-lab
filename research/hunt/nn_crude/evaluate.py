"""NN-CRUDE evaluation.
  python3 evaluate.py            -> dev OOS (walk-forward scores from model.py): thresholds, trade translation, random baseline
  python3 evaluate.py --holdout  -> ONE-TIME locked holdout (>= 2026-04-01): final fit on dev, save weights, score holdout.
Trade rule (pre-registered): score >= dev-OOS 95th pct -> long (CRUDEOILM fut long / buy CRUDEOIL ATM CE);
score <= 5th pct -> short (fut short / buy ATM PE); exit after h minutes; one position at a time; <= 6 trades/day."""
import sys, json, pickle, warnings, os
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *
import model as M
from sklearn.metrics import roc_auc_score, brier_score_loss
warnings.filterwarnings("ignore")
HOLD = "--holdout" in sys.argv
out = open(f"{LOGS}/{'holdout' if HOLD else 'eval_dev'}.txt", "a")
def P(*a):
    s = " ".join(str(x) for x in a); print(s, flush=True); out.write(s + "\n"); out.flush()
pd.set_option("display.width", 250)
GRID = pd.read_parquet(f"{D}/pnl_grid.parquet")
RNG = np.random.default_rng(12345)
MAXDAY = 6


def sim(score, h, lo, hi):
    g = GRID[GRID.h == h].reindex(score.index)
    rows, busy_until, nday, cur = [], None, 0, None
    for t, s in score.items():
        d = t.normalize()
        if d != cur:
            cur, nday = d, 0
        if busy_until is not None and t < busy_until:
            continue
        if nday >= MAXDAY or not (s >= hi or s <= lo):
            continue
        r = g.loc[t]
        side = "long" if s >= hi else "short"
        sg = 1 if side == "long" else -1
        if not np.isfinite(r.fut_gross_long) or not np.isfinite(r[f"opt_gross_{side}"]):
            continue
        rows.append(dict(t=t, day=d, side=side, opt_net_ce=r.opt_gross_long - r.opt_cost_long, opt_net_pe=r.opt_gross_short - r.opt_cost_short, fut_gross=sg * r.fut_gross_long, fut_net=sg * r.fut_gross_long - r.fut_cost,
                         opt_gross=r[f"opt_gross_{side}"], opt_net=r[f"opt_gross_{side}"] - r[f"opt_cost_{side}"],
                         opt_net_stress=r[f"opt_gross_{side}"] - r[f"opt_cost_stress_{side}"], premium=r[f"opt_entry_{side}"] * LOT["CRUDEOIL"]))
        busy_until, nday = t + pd.Timedelta(minutes=h), nday + 1
    return pd.DataFrame(rows)


def random_baseline(tr, h, idx, n=1000):
    """Same number of trades per day, random grid times on those days, random side; identical exits/costs."""
    g = GRID[GRID.h == h].reindex(idx)
    g = g[np.isfinite(g.fut_gross_long) & np.isfinite(g.opt_gross_long) & np.isfinite(g.opt_gross_short)]
    fl = (g.fut_gross_long - g.fut_cost).values; fs = (-g.fut_gross_long - g.fut_cost).values
    ol = (g.opt_gross_long - g.opt_cost_long).values; os_ = (g.opt_gross_short - g.opt_cost_short).values
    days = g.index.normalize()
    pos = {d: np.where(days == d)[0] for d in tr.day.unique()}
    cnt = tr.groupby("day").size()
    fut_tot, opt_tot = np.zeros(n), np.zeros(n)
    for d, k in cnt.items():
        p = pos.get(d)
        if p is None or len(p) == 0: continue
        pick = p[RNG.integers(0, len(p), size=(n, k))]
        side = RNG.random((n, k)) < 0.5
        fut_tot += np.where(side, fl[pick], fs[pick]).sum(1)
        opt_tot += np.where(side, ol[pick], os_[pick]).sum(1)
    return fut_tot, opt_tot


def summarize(tr, label, ndays, h, idx):
    if tr.empty:
        P(f"{label}: no trades"); return {}
    fut_r, opt_r = random_baseline(tr, h, idx)
    res = {}
    for col, rnd in (("fut", fut_r), ("opt", opt_r)):
        net = tr[f"{col}_net"]
        dn = net.groupby(tr.day).sum()
        eq = net.cumsum(); dd = (eq - eq.cummax()).min()
        boot = [net.sample(len(net), replace=True, random_state=i).mean() for i in range(500)]
        res[col] = dict(trades=len(tr), per_day=round(len(tr) / ndays, 2), win=round((net > 0).mean(), 3),
                        gross_per_trade=round(tr[f"{col}_gross"].mean(), 1), net_per_trade=round(net.mean(), 1),
                        net_total=round(net.sum()), net_per_session_day=round(net.sum() / ndays, 1), max_dd=round(dd),
                        worst_day=round(dn.min()), net_CI95=(round(np.percentile(boot, 2.5), 1), round(np.percentile(boot, 97.5), 1)),
                        random_mean_total=round(rnd.mean()), p_vs_random=round((rnd >= net.sum()).mean(), 3))
    # same-moment random side: buy CE or PE at random at the SAME times (isolates direction from timing/vol)
    ce, pe = tr.opt_net_ce.values, tr.opt_net_pe.values
    ok = np.isfinite(ce) & np.isfinite(pe)
    draws = np.where(RNG.random((1000, ok.sum())) < 0.5, ce[ok], pe[ok]).sum(1)
    res["opt"]["same_time_random_side_mean_total"] = round(draws.mean())
    res["opt"]["p_vs_same_time_random_side"] = round((draws >= tr.opt_net[ok].sum()).mean(), 3)
    fl = tr.fut_gross.abs().values  # futures: random side at same time -> mean gross 0
    res["fut"]["p_vs_same_time_random_side"] = round((np.where(RNG.random((1000, len(fl))) < 0.5, 1, -1) * tr.fut_gross.values[None, :] * 0 + np.where(RNG.random((1000, len(fl))) < 0.5, fl, -fl)).sum(1).__ge__(tr.fut_gross.sum()).mean(), 3)
    res["by_month_net_opt"] = tr.groupby(tr.day.dt.to_period("M")).opt_net.sum().round(0).astype(int).to_dict()
    res["by_month_net_fut"] = tr.groupby(tr.day.dt.to_period("M")).fut_net.sum().round(0).astype(int).to_dict()
    if "opt_net_stress" in tr:
        res["opt"]["net_total_stress3xspread"] = round(tr.opt_net_stress.sum())
    P(f"{label}:")
    for k, v in res.items():
        P(f"   {k}: {v}")
    return res


MIN = pd.read_parquet(f"{D}/mcx_min.parquet").spot
MIN = MIN[~MIN.index.duplicated()]


def bounce_auc(s, h):
    """AUC against the move measured from the NEXT minute's price (t+1 -> t+1+h): removes last-trade bounce."""
    a = MIN.reindex(s.index + pd.Timedelta(minutes=1)).values
    b = MIN.reindex(s.index + pd.Timedelta(minutes=1 + h)).values
    r = np.log(b / a); ok = np.isfinite(r) & (r != 0)
    return roc_auc_score((r[ok] > 0).astype(int), s.values[ok])


def calib(y, s, label):
    b = pd.qcut(s, 10, duplicates="drop")
    t = pd.DataFrame({"pred": s, "y": y}).groupby(b, observed=True).agg(n=("y", "size"), mean_pred=("pred", "mean"), freq_up=("y", "mean"))
    P(f"   calibration {label}:"); P(t.round(3).to_string())


def dev():
    O = pd.read_parquet(f"{D}/oos_dev.parquet")
    choice = json.load(open(f"{MODELS}/choice.json"))
    thr = {}
    P("\n===== DEV walk-forward OOS (Oct 2025 - Mar 2026), trade translation")
    for h in M.HOR:
        best = choice[str(h)]["best_mlp"]
        for mname in (best, "logit", "gbm", "mlp_common", "mlp_pre"):
            o = O[(O.h == h) & (O.model == mname)].sort_index()
            s = o.score
            lo, hi = s.quantile(0.05), s.quantile(0.95)
            thr[f"{h}|{mname}"] = (float(lo), float(hi))
            ndays = s.index.normalize().nunique()
            y = (o.y > 0).astype(int)
            P(f"\n-- h={h} {mname}: AUC {roc_auc_score(y, s):.4f} (from next minute: {bounce_auc(s, h):.4f})  thresholds {lo:.4f}/{hi:.4f}  days {ndays}")
            tr = sim(s, h, lo, hi)
            summarize(tr, f"   trades h={h} {mname}", ndays, h, s.index)
            if mname == best:
                calib(y, s, f"h={h} {mname}")
    json.dump(thr, open(f"{MODELS}/thresholds.json", "w"), indent=1)


def holdout():
    flag = f"{MODELS}/HOLDOUT_OPENED"
    if os.path.exists(flag) and "--force" not in sys.argv:
        raise SystemExit("holdout already opened once; refusing (see logs/holdout.txt)")
    open(flag, "w").write(pd.Timestamp.now().isoformat())
    choice = json.load(open(f"{MODELS}/choice.json")); thr = json.load(open(f"{MODELS}/thresholds.json"))
    F = M.load(holdout=True)
    dev_mask, hold_mask = F.index < HOLDOUT_START, F.index >= HOLDOUT_START
    P(f"\n===== LOCKED HOLDOUT {F.index[hold_mask].min()} .. {F.index[hold_mask].max()}  (opened once, {pd.Timestamp.now()})")
    nets, psc, _ = M.pretrain_proxy(end=HOLDOUT_START - pd.Timedelta(days=1))
    summary = []
    for h in M.HOR:
        yr = F[f"y_ret{h}"]
        ok = yr.notna() & (yr != 0)
        trm = dev_mask & ok & ((F.index + pd.Timedelta(minutes=h)) < HOLDOUT_START - pd.Timedelta(days=1))
        tem = hold_mask & ok
        y_tr, y_te = (yr[trm] > 0).astype(int), (yr[tem] > 0).astype(int)
        absr = yr.abs(); med = absr[trm].median()
        best = choice[str(h)]["best_mlp"]
        a = float(best.split("_a")[1].split("_h")[0]); hid = tuple(int(x) for x in best.split("_h")[1].split("x"))
        models = {best: (M.MLPEns(hid, a), M.FULL), "logit": (M.make("logit"), M.FULL), "gbm": (M.make("gbm"), M.FULL),
                  "mlp_common": (M.MLPEns((32,), 1e-2), M.COMMON), "mlp_pre": (M.MLPEns(pre=nets), M.COMMON)}
        ndays = F.index[tem].normalize().nunique()
        for mname, (m, feats) in models.items():
            m.fit(F.loc[trm, feats].values, y_tr.values)
            s = pd.Series(m.predict_proba(F.loc[tem, feats].values), index=F.index[tem])
            if mname == best:
                pickle.dump({"model": m, "features": feats, "horizon": h, "config": best, "trained_until": str(HOLDOUT_START.date()),
                             "thresholds": thr[f"{h}|{mname}"]}, open(f"{MODELS}/mlp_h{h}.pkl", "wb"))
                sz = M.MLPEns(hid, a, seeds=(0,)).fit(F.loc[trm, feats].values, (absr[trm] > med).astype(int).values)
                pickle.dump({"model": sz, "features": feats, "target": f"|ret{h}| > dev median {med:.5f}"}, open(f"{MODELS}/mlp_size_h{h}.pkl", "wb"))
                s_sz = sz.predict_proba(F.loc[tem, feats].values)
                auc_sz = roc_auc_score((absr[tem] > med).astype(int), s_sz)
            auc = roc_auc_score(y_te, s); acc = ((s > 0.5).astype(int) == y_te).mean(); br = brier_score_loss(y_te, s)
            per_m = {str(p): round(roc_auc_score(y_te[s.index.to_period('M') == p], s[s.index.to_period('M') == p]), 3) for p in sorted(s.index.to_period("M").unique())}
            P(f"\n-- h={h} {mname}: HOLDOUT AUC {auc:.4f} (from next minute {bounce_auc(s, h):.4f}) acc {acc:.4f} brier {br:.4f} (0.25 = coin) per month {per_m}"
              + (f" | size-AUC {auc_sz:.3f}" if mname == best else ""))
            lo, hi = thr[f"{h}|{mname}"]
            tr = sim(s, h, lo, hi)
            r = summarize(tr, f"   trades h={h} {mname} (frozen dev thresholds {lo:.4f}/{hi:.4f})", ndays, h, s.index)
            if mname == best:
                calib(y_te, s, f"h={h} {mname} holdout")
                tr.to_parquet(f"{D}/holdout_trades_h{h}.parquet", compression="zstd")
                if len(tr):
                    P("   by month net (fut, opt):", tr.groupby(tr.day.dt.to_period("M"))[["fut_net", "opt_net"]].sum().round(0).to_dict())
            summary.append(dict(h=h, model=mname, auc=round(auc, 4), acc=round(acc, 4), brier=round(br, 4),
                                trades=r.get("fut", {}).get("trades", 0), fut_net=r.get("fut", {}).get("net_total"),
                                fut_p=r.get("fut", {}).get("p_vs_random"), opt_net=r.get("opt", {}).get("net_total"),
                                opt_p=r.get("opt", {}).get("p_vs_random")))
        # random-score and always-flat baselines
        rs = RNG.random(tem.sum())
        P(f"-- h={h} random score: AUC {roc_auc_score(y_te, rs):.4f}; always-flat: AUC 0.5, 0 trades, Rs 0; base rate up {y_te.mean():.3f}")
    pd.DataFrame(summary).to_csv(f"{LOGS}/holdout_summary.csv", index=False)
    P(pd.DataFrame(summary).to_string(index=False))


if __name__ == "__main__":
    holdout() if HOLD else dev()
