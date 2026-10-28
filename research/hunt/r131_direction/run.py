"""R131 runner.

    IRAALGO_LAKE=/tmp/claude-0/lake R123_OUT=/tmp/claude-0/r123 R131_OUT=/tmp/claude-0/r131 \
        python3 research/hunt/r131_direction/run.py build      # rows for every horizon (no model, no result)
        python3 research/hunt/r131_direction/run.py wf         # walk-forward F1-F4 -> results/wf.json
        python3 research/hunt/r131_direction/run.py holdout    # the ONE holdout read + money -> results/holdout.json

Rows: <R131_OUT>/rows_<H>.parquet. Predictions: <R131_OUT>/preds_<stage>.parquet (Drive derived/r131, never git).
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2]))
import r131cfg as K  # noqa: E402
import r131feat as F  # noqa: E402
import r131model as M  # noqa: E402
import r131money as Y  # noqa: E402
import r131src as S  # noqa: E402

os.environ.setdefault("OMP_NUM_THREADS", "1")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ build
def load_sources() -> dict:
    idd = S.index_daily()
    spots = {u: idd[idd.symbol == u].set_index("date").close for u in K.UNDS}
    return {"index_daily": idd, "nse_indices": S.nse_indices(), "participants": S.participants(), "fpi": S.fpi(),
            "fut_daily": S.fut_daily(), "equity_daily": S.equity_daily(), "stock_fut": S.stock_fut_daily(),
            "chain": S.bhav_chain(spots)}


def expiries_from_bhav() -> dict:
    out = {u: set() for u in K.NSE_UNDS}
    for p in sorted((K.LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
        if int(p.stem[-4:]) < K.DAILY_START.year - 1:
            continue
        b = pd.read_parquet(p, columns=["und", "kind", "expiry"])
        b = b[b.kind.astype(str).isin(["CE", "PE"]) & b.und.astype(str).isin(K.NSE_UNDS)]
        for u, g in b.groupby(b.und.astype(str)):
            out[u] |= set(pd.to_datetime(g.expiry.unique()))
    return out


def build_daily(srcs: dict):
    mw, mw_cls, mcx = F.market_wide(srcs)
    ex = expiries_from_bhav()
    budget = S.budget_days()
    rows = {"GAP": [], "C2C": [], "PRE": []}
    morning, checks = {}, {}
    for u in K.UNDS:
        p, cls, chk = F.und_panel(u, srcs, mw, mw_cls, mcx, srcs["chain"], ex)
        checks[u] = chk
        a = F.assemble(p, cls, "close", budget)
        m = F.assemble(p, cls, "morning", budget)
        t = F.daily_targets(p)
        for u_ in K.UNDS:
            a["u_" + u_] = float(u == u_)
            m["u_" + u_] = float(u == u_)
        for h in ("GAP", "C2C"):
            r = a.copy()
            r["move"], r["B1"] = t[h], t["follow"]
            r["und"], r["dday"] = u, r.index
            r["day"] = r.tday
            rows[h].append(r)
        r = m.copy()
        r["move"] = t["GAP"].shift(1)                       # close of E-1 -> open of E
        r["B1"] = t["follow"].shift(1)                      # E-1's close-to-close
        r["und"], r["dday"], r["day"] = u, r.index, r.index
        rows["PRE"].append(r)
        morning[u] = m
    out = {}
    for h, lst in rows.items():
        df = pd.concat(lst).reset_index(drop=True)
        df = df[df.day.notna() & (df.day >= pd.Timestamp(K.DAILY_START)) & df.move.notna() & (df.move != 0)]
        df["y"] = (df.move > 0).astype(int)
        out[h] = df.reset_index(drop=True)
    return out, morning, checks


def build_intra(morning: dict):
    sys.path.insert(0, str(HERE.parent / "r123_neural_70"))
    import r123data as RD  # noqa: E402
    r123 = pd.concat([pd.read_parquet(K.R123_OUT / f"rows_{p}.parquet") for p in ("design", "holdout")])
    keep = sorted({t for v in K.INTRA_ROWS.values() for t in v})
    base = [c for c in r123.columns if not c.startswith(("cls_", "net_", "netx2_", "exit_", "y_"))]
    r123 = r123[r123.row.isin(keep)][base].copy()
    r123["day"] = pd.to_datetime(r123.day)
    ren = {c: pre + c for pre, cs in F.R123_MAP.items() for c in cs}
    r123 = r123.rename(columns=ren)
    # index minute grids for the targets
    last = K.HOLDOUT[1]
    grids = {u: RD.load_grid(u, last) for u in K.UNDS}
    tg = []
    for u, g in grids.items():
        pos = {pd.Timestamp(d): i for i, d in enumerate(g.days)}
        x = r123[r123.und == u]
        for h in K.INTRA_H:
            for t in K.INTRA_ROWS[h]:
                xx = x[x.row == t]
                mv = [F.intra_targets(g.c[pos[d]], t, h) if d in pos else np.nan for d in xx.day]
                tg.append(pd.DataFrame({"und": u, "day": xx.day.values, "row": t, "H": h, "move": mv}))
    tg = pd.concat(tg, ignore_index=True)
    # equity breadth at each (day, row)
    eq = equity_breadth(sorted(r123.day.unique()), keep)
    r123 = r123.merge(eq, on=["day", "row"], how="left")
    # daily features (morning lags) by (und, day)
    md = []
    for u, m in morning.items():
        z = m.drop(columns=[c for c in m.columns if c.startswith("u_") or c == "tday"]).copy()
        z["und"], z["day"] = u, z.index
        md.append(z.reset_index(drop=True))
    md = pd.concat(md, ignore_index=True)
    r123 = r123.merge(md, on=["und", "day"], how="left")
    out = {}
    for h in K.INTRA_H:
        df = tg[tg.H == h].drop(columns="H").merge(r123, on=["und", "day", "row"], how="inner")
        df = df[df.move.notna() & (df.move != 0) & (df.day >= pd.Timestamp(K.INTRA_START))]
        if h == "DAY":
            df["B1"] = np.sign(df.ri_gap.fillna(0) + df.ri_from_open.fillna(0))
        elif h in ("R11", "R13"):
            df["B1"] = np.sign(df.ri_from_open)
        else:
            df["B1"] = np.sign(df.ri_ret60)
        df["y"] = (df.move > 0).astype(int)
        out[h] = df.reset_index(drop=True)
    return out


def equity_breadth(days: list, rows: list) -> pd.DataFrame:
    want = {pd.Timestamp(d).date() for d in days}
    out = []
    prev_close = None
    for p in sorted((K.LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        df = pd.read_parquet(p, columns=["symbol", "open", "close", "ts"])
        ts = pd.to_datetime(df.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df["d"] = ts.dt.date
        df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
        df = df[(df.m >= 0) & (df.m < K.N)]
        df["symbol"] = df.symbol.astype(str)
        syms = sorted(df.symbol.unique()) if prev_close is None else list(prev_close.index.union(df.symbol.unique()))
        si = {s: i for i, s in enumerate(syms)}
        if prev_close is None:
            prev_close = pd.Series(np.nan, index=syms)
        prev_close = prev_close.reindex(syms)
        for d, g in df.groupby("d"):
            c = np.full((len(syms), K.N), np.nan)
            o = np.full((len(syms), K.N), np.nan)
            ii = g.symbol.map(si).values
            c[ii, g.m.values] = g.close.values
            o[ii, g.m.values] = g.open.values
            if d in want:
                pc = prev_close.values.astype(float)
                for t in rows:
                    r = F.eq_breadth(c, o, pc, t)
                    r.update(day=pd.Timestamp(d), row=t)
                    out.append(r)
            lastc = pd.DataFrame(c.T).ffill().values[-1]
            upd = np.isfinite(lastc)
            pcv = prev_close.values.astype(float)
            pcv[upd] = lastc[upd]
            prev_close = pd.Series(pcv, index=syms)
        log("equity breadth", p.name)
    return pd.DataFrame(out)


def cmd_build():
    K.OUT.mkdir(parents=True, exist_ok=True)
    srcs = load_sources()
    log("sources loaded", {k: len(v) for k, v in srcs.items()})
    daily, morning, checks = build_daily(srcs)
    intra = build_intra(morning)
    cov = {}
    for h, df in {**daily, **intra}.items():
        df.to_parquet(K.OUT / f"rows_{h}.parquet")
        cov[h] = {"rows": int(len(df)), "first": str(df.day.min().date()), "last": str(df.day.max().date()),
                  "by_und": {u: int(n) for u, n in df.und.value_counts().items()}}
        log(h, len(df))
    src_cov = {}
    for k, v in srcs.items():
        dcol = "date" if "date" in v else None
        if dcol:
            src_cov[k] = {"rows": int(len(v)), "first": str(pd.to_datetime(v[dcol]).min().date()),
                          "last": str(pd.to_datetime(v[dcol]).max().date())}
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "build.json").write_text(json.dumps({"rows": cov, "sources": src_cov, "checks": checks}, indent=1))


# ------------------------------------------------------------------------------------------------ models
def load_rows(h: str) -> pd.DataFrame:
    return pd.read_parquet(K.OUT / f"rows_{h}.parquet")


def fold_of(day: pd.Series) -> pd.Series:
    f = pd.Series(None, index=day.index, dtype=object)
    for k, (a, b) in K.FOLDS.items():
        f[(day >= pd.Timestamp(a)) & (day <= pd.Timestamp(b))] = k
    return f


def run_one(h: str, df: pd.DataFrame, set_name: str, model: str, stage: str) -> pd.DataFrame:
    cols = F.feature_cols(df, set_name)
    out = []
    periods = ([(f, a, b) for f, (a, b) in K.FOLDS.items()] if stage == "wf" else [("H", *K.HOLDOUT)])
    for f, a, b in periods:
        a, b = pd.Timestamp(a), pd.Timestamp(b)
        if stage == "wf":
            tr = df[df.day < a - pd.Timedelta(days=K.EMBARGO_DAYS)]
        else:
            tr = df[df.day <= pd.Timestamp(K.DESIGN_END)]
        te = df[(df.day >= a) & (df.day <= b)]
        if te.empty or tr.empty:
            continue
        Xtr = tr[cols].to_numpy(np.float64)
        keep = np.isfinite(Xtr).any(0)
        p_tr, p_te = M.fit_predict(model, Xtr[:, keep], tr.y.values, te[cols].to_numpy(np.float64)[:, keep])
        cuts = {k: M.conf_cut(p_tr, k) for k in K.COVERAGES}
        o = te[["und", "day", "row", "move", "y"]].copy() if "row" in te else te[["und", "day", "move", "y"]].copy()
        o["fold"], o["p"] = f, p_te
        for k, c in cuts.items():
            o[f"cut_{int(k * 100)}"] = c
        o["H"], o["set"], o["model"], o["n_train"] = h, set_name, model, len(tr)
        out.append(o)
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


def predictions(stage: str) -> pd.DataFrame:
    from joblib import Parallel, delayed
    jobs = []
    rows = {h: load_rows(h) for h in K.HORIZONS}
    for h in K.HORIZONS:
        for s in K.SETS:
            for m in K.MODELS:
                jobs.append((h, s, m))
    log(stage, len(jobs), "fits x periods")
    res = Parallel(n_jobs=4, verbose=0)(delayed(run_one)(h, rows[h], s, m, stage) for h, s, m in jobs)
    preds = pd.concat(res, ignore_index=True)
    # baselines
    base = []
    for h in K.HORIZONS:
        df = rows[h]
        periods = ([(f, a, b) for f, (a, b) in K.FOLDS.items()] if stage == "wf" else [("H", *K.HOLDOUT)])
        for f, a, b in periods:
            a, b = pd.Timestamp(a), pd.Timestamp(b)
            tr = df[df.day < a - pd.Timedelta(days=K.EMBARGO_DAYS)] if stage == "wf" else df[df.day <= pd.Timestamp(K.DESIGN_END)]
            te = df[(df.day >= a) & (df.day <= b)]
            maj = float(tr.y.mean() >= 0.5)
            for bname in K.BASELINES:
                o = te[["und", "day", "move", "y"]].copy()
                if "row" in te:
                    o["row"] = te.row.values
                if bname == "B0":
                    o["p"] = maj
                else:
                    s = te.B1.values if bname == "B1" else -te.B1.values
                    o["p"] = np.where(s > 0, 1.0, np.where(s < 0, 0.0, np.nan))
                o = o[o.p.notna()]
                o["fold"], o["H"], o["set"], o["model"], o["n_train"] = f, h, "BASE", bname, len(tr)
                for k in K.COVERAGES:
                    o[f"cut_{int(k * 100)}"] = -1.0 if k == 1.0 else np.inf
                base.append(o)
    return pd.concat([preds] + base, ignore_index=True)


def period_p0(rows_all: dict, h: str, stage: str) -> float:
    """Base rate p0 = max(u, 1-u) over ALL rows of the horizon in the test period (F1-F4 pooled, or the holdout)."""
    d = rows_all[h].day
    if stage == "wf":
        m = fold_of(d).notna()
    else:
        m = (d >= pd.Timestamp(K.HOLDOUT[0])) & (d <= pd.Timestamp(K.HOLDOUT[1]))
    return M.base_rate(rows_all[h].y.values[m.values])


def score(preds: pd.DataFrame, rows_all: dict, stage: str) -> list:
    """One result per (H, set, model, coverage)."""
    out = []
    p0s = {h: period_p0(rows_all, h, stage) for h in K.HORIZONS}
    for (h, s, m), g in preds.groupby(["H", "set", "model"]):
        p0 = p0s[h]
        a = M.auc(g.y.values, g.p.values) if s != "BASE" else np.nan
        for k in (K.COVERAGES if s != "BASE" else (1.0,)):
            sel = g[(np.abs(g.p - 0.5) >= g[f"cut_{int(k * 100)}"])] if k < 1.0 else g
            sel = sel.assign(call=(sel.p >= 0.5).astype(int))
            r = M.evaluate(sel, p0, K.TINY[h])
            r.update(H=h, set=s, model=m, k=k, auc=a, coverage=len(sel) / max(len(g), 1))
            out.append(r)
    return out


def cmd_wf():
    preds = predictions("wf")
    preds.to_parquet(K.OUT / "preds_wf.parquet")
    rows_all = {h: load_rows(h) for h in K.HORIZONS}
    res = score(preds, rows_all, "wf")
    q = M.bh(np.array([r["p"] for r in res]))
    for r, qq in zip(res, q):
        r["q"] = float(qq)
        r["gate_wf"] = M.gate(r, None, r["H"])
    (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=0, default=float))
    log("wf done", len(res), "tests; min q", float(q.min()))


# ------------------------------------------------------------------------------------------------ holdout
def holdout_gate(status: str):
    p = K.RESULTS / "HOLDOUT_READ.json"
    old = json.loads(p.read_text()) if p.exists() else None
    if status == "start":
        if old and old.get("status") == "done":
            raise RuntimeError(f"refused: the holdout was already read at {old.get('done_at')}")
        rec = dict(status="started", started_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   resumed=bool(old))
    else:
        rec = dict(old or {}, status="done", done_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    p.write_text(json.dumps(rec))


def money_rows(preds: pd.DataFrame, which: list) -> dict:
    """Trades for each (H, set, model, k) in `which` from the calls in preds."""
    sys.path.insert(0, str(HERE.parent / "r123_neural_70"))
    import r123data as RD  # noqa: E402
    out = {}
    fut = S.bhav_fut_rows()
    fut["date"] = pd.to_datetime(fut.date)
    fut["expiry"] = pd.to_datetime(fut.expiry)
    rows_all = {h: load_rows(h) for h in K.HORIZONS}
    lake = {}
    grids = {}
    for (h, s, m, k) in which:
        g = preds[(preds.H == h) & (preds.set == s) & (preds.model == m)]
        g = g[np.abs(g.p - 0.5) >= g[f"cut_{int(k * 100)}"]] if k < 1.0 else g
        tr, skipped = [], 0
        if h in ("GAP", "C2C"):
            meta = rows_all[h].set_index(["und", "day"]).dday
            for r in g.itertuples():
                if r.und not in K.NSE_UNDS:
                    skipped += 1
                    continue
                d = meta.get((r.und, r.day))
                c = Y.fut_contract(fut, r.und, d, r.day) if d is not None else None
                direction = 1 if r.p >= 0.5 else -1
                net = None
                if c is not None:
                    net = Y.fut_trade(c["entry"], c["open"] if h == "GAP" else c["close"], K.LOT[r.und], direction,
                                      d.date(), r.day.date())
                if net is None:
                    skipped += 1
                    continue
                tr.append({"und": r.und, "day": r.day, "fold": r.fold, "net": net, "side": direction})
        elif h in K.INTRA_H:
            meta = rows_all[h].set_index(["und", "day", "row"])
            for r in g.sort_values(["und", "day"]).itertuples():
                u, d = r.und, r.day.date()
                if u not in grids:
                    grids[u] = RD.load_grid(u, K.HOLDOUT[1])
                    lake[u] = RD.LakeOptions(u, K.HOLDOUT[1])
                gr = grids[u]
                i = gr.pos().get(d)
                if i is None:
                    skipped += 1
                    continue
                opts = lake[u].day(d, closes=gr.c[i])
                is_exp = bool(meta.loc[(u, r.day, r.row)].get("rk_is_expiry", 0) == 1)
                t = int(r.row)
                end = t + 60 if h == "M60" else (K.EXIT_LAST_EXPIRY if is_exp else K.EXIT_LAST)
                if h == "M60" and is_exp and end > K.EXIT_LAST_EXPIRY:
                    end = K.EXIT_LAST_EXPIRY
                side = "C" if r.p >= 0.5 else "P"
                spot = gr.c[i, t] if np.isfinite(gr.c[i, t]) else meta.loc[(u, r.day, r.row)]["close"]
                kk = RD.atm(float(spot), K.STEP[u])
                a = opts.contracts.get((side, kk)) if opts is not None else None
                x = Y.opt_trade(a, t, end, K.LOT[u], d, u, RD.FILLS, RD.charge)
                if x is None:
                    skipped += 1
                    continue
                tr.append({"und": u, "day": r.day, "fold": r.fold, "net": x["net"], "side": side})
        df = pd.DataFrame(tr)
        df.attrs["skipped"] = skipped
        summ = {"all": Y.summarise(df) if len(df) else {"trades": 0}, "skipped": skipped}
        if len(df):
            summ["by_fold"] = {f: Y.summarise(x) for f, x in df.groupby("fold")}
        out[f"{h}|{s}|{m}|{k}"] = summ
        log("money", h, s, m, k, summ["all"])
    return out


def cmd_holdout():
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    holdout_gate("start")
    preds = predictions("holdout")
    preds.to_parquet(K.OUT / "preds_holdout.parquet")
    rows_all = {h: load_rows(h) for h in K.HORIZONS}
    res = score(preds, rows_all, "holdout")
    hk = {(r["H"], r["set"], r["model"], r["k"]): r for r in res}
    gates = []
    for r in wf:
        hr = hk.get((r["H"], r["set"], r["model"], r["k"]))
        r["holdout"] = hr
        r["gate"] = M.gate(r, hr, r["H"]) if hr else None
        if r["gate"] and r["gate"]["pass"]:
            gates.append((r["H"], r["set"], r["model"], r["k"]))
    fixed = [(h, K.MONEY_SET, K.MONEY_MODEL, K.MONEY_K) for h in ("DAY", "R11", "R13", "M60", "GAP", "C2C")]
    which = sorted(set(fixed + gates))
    wfp = pd.read_parquet(K.OUT / "preds_wf.parquet")
    money = {"wf": money_rows(wfp, which), "holdout": money_rows(preds, which)}
    (K.RESULTS / "holdout.json").write_text(json.dumps({"results": wf, "passes": gates, "money": money},
                                                       indent=0, default=float))
    holdout_gate("done")
    log("holdout done; passes:", gates)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    {"build": cmd_build, "wf": cmd_wf, "holdout": cmd_holdout}[cmd]()
