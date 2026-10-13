"""R17 runner (PREREG.md). Stages, in order:

    python3 research/hunt/r17/run.py trades     # simulate every arm under the fixed stop and the 10 stop variants (cached)
    python3 research/hunt/r17/run.py design     # design-only numbers and the frozen picks -> results/design.json, picks.json
    python3 research/hunt/r17/run.py holdout    # the ONE holdout read (refuses a second time) -> results/holdout.json
    python3 research/hunt/r17/run.py tables     # app constants (time-of-day table) -> results/app_constants.json

Design stages never look at a session on or after its index's cut date.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd
from scipy import stats

import config as K
import data as DATA
from arms import StopSpec, run_all
from erange import ERange

UNDS = ["BANKNIFTY", "NIFTY"]
TEST_ARMS = ["orb", "orb_sweep", "range_fade", "vix_div_banknifty", "vix_div_nifty", "hero"]   # ORB Fresh = ORB (cap 1)
SPECS = [StopSpec("fixed")] + [StopSpec(f, k) for f in K.FORMS for k in K.K_GRID]


def _p(name):
    K.OUT.mkdir(parents=True, exist_ok=True)
    return K.OUT / f"{K.SOURCE}_{name}"


def load_all():
    days = {u: DATA.load(u) for u in UNDS}
    ers = {}
    for u in UNDS:
        p = _p(f"erange_{u}.pkl")
        if p.exists():
            ers[u] = pickle.loads(p.read_bytes())
        else:
            ers[u] = ERange(days[u])
            p.write_bytes(pickle.dumps(ers[u], protocol=4))
    return days, ers


def cuts(days):
    return {u: K.holdout_start(u, [d.day for d in days[u]]) for u in UNDS}


def trades():
    days, ers = load_all()
    for sp in SPECS:
        p = _p(f"trades_{sp.name()}.pkl")
        if p.exists():
            continue
        tr = pd.DataFrame(run_all(days, ers, sp))
        tr.to_pickle(p)
        print(sp.name(), len(tr), flush=True)


def load_trades(name, days):
    tr = pd.read_pickle(_p(f"trades_{name}.pkl"))
    c = cuts(days)
    tr["hold"] = [d >= c[u] for d, u in zip(tr.day, tr.und)]
    return tr


# ------------------------------------------------------------------------------------------------ I3
def i3_buckets(tr):
    out = {}
    for arm, g in tr.groupby("arm"):
        g = g[np.isfinite(g.x_ratio)]
        if len(g) < 10:
            out[arm] = dict(n=int(len(g)))
            continue
        q = pd.qcut(g.x_ratio, 5, duplicates="drop")
        t = g.groupby(q, observed=True).agg(n=("net", "size"), x_lo=("x_ratio", "min"), x_hi=("x_ratio", "max"),
                                            net=("net", "mean"), gross=("gross", "mean"), charges=("charges", "mean"))
        out[arm] = [dict(n=int(r.n), x_lo=round(r.x_lo, 3), x_hi=round(r.x_hi, 3), net=round(r.net, 1),
                         gross=round(r.gross, 1), charges=round(r.charges, 1)) for r in t.itertuples()]
    return out


def i3_grid(tr):
    g = tr[tr.arm.isin(TEST_ARMS) & np.isfinite(tr.x_ratio)]
    rows = []
    for x in K.X_GRID:
        sk = g[g.x_ratio > x]
        rows.append(dict(x=x, skipped=int(len(sk)), of=int(len(g)), gain=round(float(-sk.net.sum()), 0),
                         skipped_mean=round(float(sk.net.mean()), 1) if len(sk) else None,
                         kept_mean=round(float(g[g.x_ratio <= x].net.mean()), 1)))
    return rows


def twins(g, mask, rng, n=K.TWINS):
    """Share of random twins (same number skipped in each arm) whose skipped trades lost less than the rule's."""
    real = -g.net[mask].sum()
    by = {a: (np.nonzero((g.arm == a).values)[0], int(mask[(g.arm == a).values].sum())) for a in g.arm.unique()}
    net = g.net.values
    better = 0
    for _ in range(n):
        s = 0.0
        for idx, m in by.values():
            if m:
                s -= net[rng.choice(idx, m, replace=False)].sum()
        better += s < real
    return better / n


# ------------------------------------------------------------------------------------------------ I5
def daily(tr):
    return tr.groupby("day").net.sum()


def i5_table(trs, period_hold):
    base = trs["fixed"]
    out = {}
    for arm in TEST_ARMS:
        b = base[(base.arm == arm) & (base.hold == period_hold)]
        if b.empty:
            out[arm] = dict(n=0)
            continue
        bd = daily(b)
        rows = [dict(spec="fixed", n=int(len(b)), net=round(float(b.net.sum()), 0), per_trade=round(float(b.net.mean()), 1),
                     stop_median=round(float(np.nanmedian(b.stop_pts)), 1) if b.stop_pts.notna().any() else None)]
        for name, t in trs.items():
            if name == "fixed":
                continue
            v = t[(t.arm == arm) & (t.hold == period_hold)]
            vd = daily(v).reindex(bd.index.union(daily(v).index), fill_value=0.0)
            diff = vd - bd.reindex(vd.index, fill_value=0.0)
            p = float(stats.ttest_1samp(diff, 0.0, alternative="greater").pvalue) if len(diff) > 2 and diff.std() > 0 else 1.0
            rows.append(dict(spec=name, n=int(len(v)), net=round(float(v.net.sum()), 0), per_trade=round(float(v.net.mean()), 1),
                             delta=round(float(diff.sum()), 0), p=round(p, 4), days=int(len(diff)),
                             stop_median=round(float(np.nanmedian(v.stop_pts)), 1) if v.stop_pts.notna().any() else None,
                             stops_hit=int((v.why == "stop").sum())))
        out[arm] = rows
    return out


# ------------------------------------------------------------------------------------------------ I4
def day_frame(days, base):
    """One row per session where both indices have data: features known by 09:16 and the arms' summed net."""
    bn = {d.day: d for d in days["BANKNIFTY"]}
    nf = {d.day: d for d in days["NIFTY"]}
    both = sorted(set(bn) & set(nf))
    prevc = {}
    for u in UNDS:
        ds = days[u]
        for a, b in zip(ds[:-1], ds[1:]):
            prevc[(u, b.day)] = a.ix[-1, 3]
    vix_prev = {d.day: d.vix_prev for d in days["NIFTY"]}
    dl = sorted(vix_prev)
    vix_chg = {b: vix_prev[b] / vix_prev[a] - 1 for a, b in zip(dl[:-1], dl[1:])}
    net = base.groupby("day").net.sum()
    rows = []
    for d in both:
        pc = prevc.get(("BANKNIFTY", d))
        if pc is None:
            continue
        x = bn[d]
        rows.append(dict(day=d, gap=abs(x.ix[0, 0] / pc - 1) * 100, vix=vix_prev[d], vix_chg=vix_chg.get(d, np.nan),
                         event=bool(x.events or nf[d].events),
                         expiry=bool(x.expiry == d or nf[d].expiry == d), dow=d.weekday(), net=float(net.get(d, 0.0))))
    return pd.DataFrame(rows)


def buckets(df, i, W=K.DANGER_WARMUP):
    """Bucket labels of row i from the trailing W rows' cut points (known before the day)."""
    tr = df.iloc[max(0, i - W):i]
    r = df.iloc[i]
    g1, g2 = np.nanquantile(tr.gap, [1 / 3, 2 / 3])
    v1, v2 = np.nanquantile(tr.vix, [1 / 3, 2 / 3])
    vc = r.vix_chg
    return dict(gap="small" if r.gap <= g1 else "mid" if r.gap <= g2 else "large",
                vix="low" if r.vix <= v1 else "mid" if r.vix <= v2 else "high",
                vix_chg="up" if vc > 0.05 else "down" if vc < -0.05 else "flat",
                event="yes" if r.event else "no", expiry="yes" if r.expiry else "no",
                dow=["Mon", "Tue", "Wed", "Thu", "Fri"][int(r.dow)] if r.dow < 5 else "Sat")


FEATS = ["gap", "vix", "vix_chg", "event", "expiry", "dow"]


def danger(df):
    """Walk-forward monthly: each day's score and level from a model fitted on earlier days only."""
    df = df.sort_values("day").reset_index(drop=True)
    B = [buckets(df, i) if i >= K.DANGER_WARMUP else None for i in range(len(df))]
    df["month"] = [(d.year, d.month) for d in df.day]
    score = np.full(len(df), np.nan)
    level = np.array([None] * len(df), dtype=object)
    model_by_month = {}
    for m, idx in df.groupby("month").groups.items():
        first = min(idx)
        if first < K.DANGER_WARMUP + 20:
            continue
        tr = [j for j in range(K.DANGER_WARMUP, first)]
        overall = df.net.iloc[tr].mean()
        model = {}
        for f in FEATS:
            vals = pd.Series([B[j][f] for j in tr])
            nets = df.net.iloc[tr].values
            for b in vals.unique():
                s = nets[(vals == b).values]
                model[(f, b)] = (float(s.mean() - overall) * len(s) / (len(s) + 20), int(len(s)), float(s.mean()))

        def sc(j):
            return sum(model.get((f, B[j][f]), (0.0, 0, 0.0))[0] for f in FEATS)
        past = np.array([sc(j) for j in tr])
        p20, p05 = np.quantile(past, 0.20), np.quantile(past, 0.05)
        for j in idx:
            score[j] = sc(j)
            level[j] = "paper-only" if score[j] <= p05 else "careful" if score[j] <= p20 else "normal"
        model_by_month[f"{m[0]}-{m[1]:02d}"] = dict(overall=round(overall, 1), p20=round(p20, 1), p05=round(p05, 1), n=len(tr))
    df["score"] = score
    df["level"] = level
    return df, model_by_month


def i4_stats(df, rng):
    d = df[df.level.notna()]
    flag = d.level.isin(["careful", "paper-only"]).values
    out = dict(days=int(len(d)), careful=int((d.level == "careful").sum()), paper_only=int((d.level == "paper-only").sum()),
               normal=int((d.level == "normal").sum()))
    if flag.sum() < 3 or (~flag).sum() < 3:
        return out | dict(p=1.0, twin=0.0)
    a, b = d.net.values[flag], d.net.values[~flag]
    out.update(flagged_mean=round(float(a.mean()), 1), normal_mean=round(float(b.mean()), 1),
               p=round(float(stats.ttest_ind(a, b, equal_var=False, alternative="less").pvalue), 4))
    gain = -0.5 * a.sum()
    better = 0
    for _ in range(K.TWINS):
        pick = rng.choice(len(d), int(flag.sum()), replace=False)
        better += (-0.5 * d.net.values[pick].sum()) < gain
    out.update(half_size_gain=round(float(gain), 0), twin=better / K.TWINS)
    for lv in ("normal", "careful", "paper-only"):
        s = d.net[d.level == lv]
        out[f"{lv}_mean"] = round(float(s.mean()), 1) if len(s) else None
        out[f"{lv}_worst"] = round(float(s.min()), 0) if len(s) else None
    return out


def bucket_table(df, period):
    rows = []
    d = df[(df.index >= K.DANGER_WARMUP)]
    d = d[period(d)]
    B = [buckets(df, i) for i in d.index]
    for f in FEATS:
        vals = pd.Series([b[f] for b in B], index=d.index)
        for b in sorted(vals.unique()):
            s = d.net[vals == b]
            rows.append(dict(feature=f, bucket=b, n=int(len(s)), mean=round(float(s.mean()), 1),
                             worst=round(float(s.min()), 0), losing_share=round(float((s < 0).mean()), 2)))
    return rows


# ------------------------------------------------------------------------------------------------ stages
def all_trades(days):
    return {sp.name(): load_trades(sp.name(), days) for sp in SPECS}


def design():
    days, ers = load_all()
    trs = all_trades(days)
    base = trs["fixed"]
    d = base[~base.hold]
    rng = np.random.default_rng(K.SEED)
    res = {"period": {u: f"design < {c}" for u, c in cuts(days).items()}}
    res["arms"] = {a: dict(n=int(len(g)), net=round(float(g.net.sum()), 0), per_trade=round(float(g.net.mean()), 1),
                           charges=round(float(g.charges.mean()), 1), win=round(float((g.net > 0).mean()), 3))
                   for a, g in d.groupby("arm")}
    # I3
    res["i3_buckets"] = i3_buckets(d)
    grid = i3_grid(d)
    res["i3_grid"] = grid
    ok = [r for r in grid if r["skipped"] >= 30]
    pick_x = max(ok, key=lambda r: (r["gain"], r["x"]))["x"] if ok else None
    g = d[d.arm.isin(TEST_ARMS) & np.isfinite(d.x_ratio)]
    if pick_x is not None:
        res["i3_design_twin"] = twins(g.reset_index(drop=True), (g.x_ratio > pick_x).values, rng)
    # I5
    t5 = i5_table(trs, False)
    res["i5"] = t5
    picks5 = {}
    for arm, rows in t5.items():
        if not isinstance(rows, list) or len(rows) < 2:
            picks5[arm] = "fixed"
            continue
        best = max(rows[1:], key=lambda r: r["net"])
        picks5[arm] = best["spec"] if (best["net"] > rows[0]["net"] and best["p"] < 0.10) else "fixed"
    # I4
    df = day_frame(days, base)
    cut_bn = cuts(days)["BANKNIFTY"]
    end_bn = days["BANKNIFTY"][-1].day
    df = df[df.day <= end_bn].reset_index(drop=True)
    dd, months = danger(df)
    dd.to_pickle(_p("danger_days.pkl"))
    res["i4_design"] = i4_stats(dd[dd.day < cut_bn], rng)
    res["i4_buckets_design"] = bucket_table(dd, lambda x: x.day < cut_bn)
    res["i4_models"] = {k: v for k, v in months.items() if date(int(k[:4]), int(k[5:]), 1) < cut_bn}
    picks = dict(i3_x=pick_x, i5=picks5, i4="pre-registered (no pick)", frozen=datetime.now(timezone.utc).isoformat())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "picks.json").write_text(json.dumps(picks, indent=1))
    print(json.dumps(picks, indent=1))


def holdout():
    flag = _p("HOLDOUT_READ")
    if flag.exists():
        sys.exit(f"the holdout was already read ({flag.read_text().strip()}); a new look needs a new pre-registration")
    picks = json.loads((K.RESULTS / "picks.json").read_text())
    flag.write_text(datetime.now(timezone.utc).isoformat())
    days, ers = load_all()
    trs = all_trades(days)
    base = trs["fixed"]
    h = base[base.hold]
    rng = np.random.default_rng(K.SEED + 1)
    res = {"read_at": flag.read_text(), "picks": picks}
    res["arms"] = {a: dict(n=int(len(g)), net=round(float(g.net.sum()), 0), per_trade=round(float(g.net.mean()), 1),
                           charges=round(float(g.charges.mean()), 1), win=round(float((g.net > 0).mean()), 3))
                   for a, g in h.groupby("arm")}
    tests = []
    # I3
    res["i3_buckets"] = i3_buckets(h)
    res["i3_grid"] = i3_grid(h)
    x = picks["i3_x"]
    if x is not None:
        g = h[h.arm.isin(TEST_ARMS) & np.isfinite(h.x_ratio)].reset_index(drop=True)
        m = (g.x_ratio > x).values
        p = float(stats.ttest_ind(g.net[m], g.net[~m], equal_var=False, alternative="less").pvalue) if m.sum() > 2 else 1.0
        tw = twins(g, m, rng)
        res["i3_test"] = dict(x=x, skipped=int(m.sum()), of=int(len(g)), skipped_mean=round(float(g.net[m].mean()), 1) if m.any() else None,
                              kept_mean=round(float(g.net[~m].mean()), 1), gain=round(float(-g.net[m].sum()), 0), p=round(p, 4), twin=tw)
        tests.append(("I3 charges check", p, tw >= 0.95))
    # I5
    t5 = i5_table(trs, True)
    res["i5"] = t5
    for arm, spec in picks["i5"].items():
        if spec == "fixed" or not isinstance(t5.get(arm), list):
            continue
        row = next(r for r in t5[arm] if r["spec"] == spec)
        tests.append((f"I5 {arm} {spec}", row["p"], True))
    # I4
    dd = pd.read_pickle(_p("danger_days.pkl"))
    cut_bn = cuts(days)["BANKNIFTY"]
    s4 = i4_stats(dd[dd.day >= cut_bn], rng)
    res["i4"] = s4
    res["i4_buckets"] = bucket_table(dd, lambda x: x.day >= cut_bn)
    tests.append(("I4 danger meter", s4.get("p", 1.0), s4.get("twin", 0) >= 0.95))
    # BH
    ps = np.array([t[1] for t in tests])
    order = np.argsort(ps)
    q = np.empty(len(ps))
    prev = 1.0
    for rank in range(len(ps), 0, -1):
        i = order[rank - 1]
        prev = min(prev, ps[i] * len(ps) / rank)
        q[i] = prev
    res["tests"] = [dict(test=t[0], p=round(t[1], 4), q=round(float(qq), 4), extra_gate=bool(t[2]),
                         passes=bool(qq <= 0.05 and t[2])) for t, qq in zip(tests, q)]
    (K.RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res["tests"], indent=1))


def tables():
    """The time-of-day term for the app: the median log range of the next 30 / 60 minutes by 15-minute slot, from the
    DESIGN sessions only (the app has no 20-session minute history at entry)."""
    days, ers = load_all()
    out = {}
    for u in UNDS:
        er = ers[u]
        c = cuts(days)[u]
        di = [i for i, d in enumerate(days[u]) if d.day < c]
        out[u] = {}
        for h in (30, 60):
            row = []
            for t in range(14, 345, 15):                 # decisions 09:30, 09:45, ... (row t closes at 09:16 + t)
                v = er._fut[h][di, min(t, 374 - h - 1)]
                row.append(round(float(np.nanmean(v)), 4))
            out[u][str(h)] = row
        out[u]["sessions"] = len(di)
    # The danger meter's model for the app: every session with both indices (design + holdout), the same shrunk bucket
    # means as the walk-forward model, and the trailing-120-session cut points as of the last session.
    dd = pd.read_pickle(_p("danger_days.pkl")).sort_values("day").reset_index(drop=True)
    idx = list(range(K.DANGER_WARMUP, len(dd)))
    B = {i: buckets(dd, i) for i in idx}
    overall = dd.net.iloc[idx].mean()
    model = []
    for f in FEATS:
        vals = pd.Series([B[i][f] for i in idx])
        nets = dd.net.iloc[idx].values
        for b in sorted(vals.unique()):
            s = nets[(vals == b).values]
            model.append(dict(feature=f, bucket=b, n=int(len(s)), mean=round(float(s.mean()), 1),
                              dev=round(float((s.mean() - overall) * len(s) / (len(s) + 20)), 1)))
    sc = np.array([sum(next(m["dev"] for m in model if m["feature"] == f and m["bucket"] == B[i][f]) for f in FEATS) for i in idx])
    tr = dd.iloc[-K.DANGER_WARMUP:]
    out["danger"] = dict(sessions=len(idx), first=str(dd.day.iloc[idx[0]]), last=str(dd.day.iloc[-1]), overall=round(float(overall), 1),
                         p20=round(float(np.quantile(sc, 0.2)), 1), p05=round(float(np.quantile(sc, 0.05)), 1),
                         gap_cuts=[round(float(x), 3) for x in np.nanquantile(tr.gap, [1 / 3, 2 / 3])],
                         vix_cuts=[round(float(x), 2) for x in np.nanquantile(tr.vix, [1 / 3, 2 / 3])], model=model)
    (K.RESULTS / "app_constants.json").write_text(json.dumps(out, indent=1))
    print(json.dumps(out)[:400])


if __name__ == "__main__":
    {"trades": trades, "design": design, "holdout": holdout, "tables": tables}[sys.argv[1]]()
