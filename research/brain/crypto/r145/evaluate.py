"""R145 evaluation: the 240 pre-registered tests on the design walk-forward, one BH family, the gates; patterns; the
trading test for what passes. Writes research/brain/crypto/results/r145_design.json.

    python -m research.brain.crypto.r145.evaluate design
    python -m research.brain.crypto.r145.evaluate trade       # only the forecasts that passed (PREREG section 6)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from . import data, models as M, patterns as PT, run, stats as S, trade as TR

RESULTS = Path(__file__).resolve().parents[1] / "results"
IST = pd.Timedelta(hours=5, minutes=30)
F_SETS = [("F1", "N1", "B0"), ("F2", "N2", "B0"), ("F3", "N1", "B2"), ("F4", "N2", "B2"), ("F5", "N1", "B1"),
          ("F6", "N1", "TW")]


def oos(tag="design") -> pd.DataFrame:
    D = pd.read_parquet(run.out_dir() / tag / "rows.parquet")
    parts = []
    for f in range(len(run.FOLDS)):
        p = None
        for m in run.MODELS:
            fp = run.out_dir() / tag / "preds" / f"fold{f}_{m}.parquet"
            if fp.exists():
                x = pd.read_parquet(fp)
                p = x if p is None else p.merge(x, on=["ts", "sym"], how="outer")
        parts.append(p.assign(fold=f))
    P = pd.concat(parts, ignore_index=True)
    keep = ["ts", "sym", "bar", "col", "sig_bar", "fund_rate", "mkt_volz", "btc_r1440", "weekend"] + M.TARGETS
    return P.merge(D[keep], on=["ts", "sym"], how="left")


def _jsonable(x):
    if isinstance(x, dict):
        return {str(k): _jsonable(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [_jsonable(v) for v in x]
    if isinstance(x, (np.floating, float)):
        return None if not np.isfinite(x) else round(float(x), 6)
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.bool_,)):
        return bool(x)
    return x


def model_summary(df) -> dict:
    """Plain scores of every model on every target (AUC, R2 or IC), for the write-up table."""
    out = {}
    for t in M.TARGETS:
        y = df[t].to_numpy(float)
        row = {}
        for m in run.MODELS:
            c = f"{m}|{t}"
            if c not in df:
                continue
            p = df[c].to_numpy(float)
            ok = np.isfinite(y) & np.isfinite(p)
            if t.startswith("y_xs"):
                row[m] = float(S.ic_by_time(df.ts.values[ok], p[ok], y[ok]).mean()) if m != "B0" else 0.0
            elif M.is_binary(t):
                row[m] = S.auc(p[ok], y[ok]) if np.std(p[ok]) > 0 else 0.5
            else:
                yy = y[ok]
                row[m] = float(1 - np.sum((p[ok] - yy) ** 2) / np.sum((yy - yy.mean()) ** 2))
        out[t] = dict(metric="IC" if t.startswith("y_xs") else ("AUC" if M.is_binary(t) else "R2"),
                      n=int(np.isfinite(y).sum()), **row)
    return out


def design(n_boot=2000):
    df = oos()
    tests = []
    for fid, m, c in F_SETS:
        for t in M.TARGETS:
            r = S.compare(df, t, m, c)
            r["id"] = f"{fid}-{t}"
            tests.append(r)
    # P1: by IST hour
    df["ist_hour"] = (pd.DatetimeIndex(df.ts) + IST).hour
    for h in range(24):
        sub = df[df.ist_hour == h]
        r = S.compare(sub, "y_dir_60", "N1", "B0")
        r.update(id=f"P1-dir60-ist{h:02d}", effect=r["model_auc"] - 0.5, min_effect=0.02)
        tests.append(r)
        r = S.compare(sub, "y_xs_60", "N1", "ZERO")
        r.update(id=f"P1-xs60-ist{h:02d}")
        tests.append(r)
    # P2: by coin (weekly-block bootstrap of AUC > 0.5)
    for sym, sub in df.groupby("sym"):
        y = sub.y_dir_60.to_numpy(float)
        p = sub["N1|y_dir_60"].to_numpy(float)
        ok = np.isfinite(y) & np.isfinite(p)
        if ok.sum() < 500:
            tests.append(dict(id=f"P2-dir60-{sym}", n=int(ok.sum()), p=np.nan, effect=np.nan, metric="AUC",
                              note="fewer than 500 rows"))
            continue
        a, pv = S.bootstrap_auc_p(p[ok], y[ok], S.week(sub.ts.values[ok]), n_boot=n_boot)
        fa = [S.auc(g["N1|y_dir_60"].to_numpy(float), g.y_dir_60.to_numpy(float)) for _, g in sub[ok].groupby("fold")]
        tests.append(dict(id=f"P2-dir60-{sym}", n=int(ok.sum()), model_auc=a, effect=a - 0.5, min_effect=0.02, p=pv,
                          metric="AUC", folds_pos=int(np.sum(np.array(fa) > 0.5)), folds=len(fa)))
    # P3: by market state
    cuts = df.mkt_volz.quantile([1 / 3, 2 / 3]).to_numpy()
    states = {"market vol low": df.mkt_volz <= cuts[0],
              "market vol mid": (df.mkt_volz > cuts[0]) & (df.mkt_volz <= cuts[1]),
              "market vol high": df.mkt_volz > cuts[1], "BTC 1-day up": df.btc_r1440 > 0,
              "BTC 1-day down": df.btc_r1440 <= 0, "weekend": df.weekend == 1, "weekday": df.weekend == 0}
    for name, m in states.items():
        sub = df[m.to_numpy()]
        r = S.compare(sub, "y_dir_60", "N1", "B0")
        r.update(id=f"P3-dir60-{name}")
        tests.append(r)
        r = S.compare(sub, "y_xs_60", "N1", "ZERO")
        r.update(id=f"P3-xs60-{name}")
        tests.append(r)
    # P4, P5
    ll_tests, ll_info = PT.leadlag_tests()
    tests += ll_tests
    meta = json.load(open(run.out_dir() / "design" / "meta.json"))
    C5 = PT.closes(meta["symbols"], "2023-12", "2026-03")
    t5, d5 = PT.leadlag_5m(C5, run.DESIGN_ROWS_FROM, pd.Timestamp("2026-04-01"))
    tests.append(t5)
    t_cl, d_cl = PT.cluster_test(C5, run.DESIGN_ROWS_FROM, pd.Timestamp("2026-04-01"))
    tests.append(t_cl)

    q = S.bh([t.get("p", np.nan) for t in tests])
    for t, qq in zip(tests, q):
        t["q"] = qq
        if t.get("metric") in ("corr", "ARI"):
            t["real"] = bool(np.isfinite(qq) and qq < 0.05 and t["effect"] > 0)
        elif t.get("p") is None or not np.isfinite(t.get("p", np.nan)):
            t["real"] = False
        else:
            fb = 4 if t.get("folds") is None or t.get("folds", 6) >= 6 else max(1, int(np.ceil(t["folds"] * 4 / 6)))
            t["real"] = S.passes(t, qq, t.get("min_effect"), folds_bar=fb)
    imp = PT.importance(pd.read_parquet(run.out_dir() / "design" / "rows.parquet"), meta["features"])
    gains = {}
    for f in range(len(run.FOLDS)):
        inf = json.load(open(run.out_dir() / "design" / "preds" / f"fold{f}_B2_info.json"))
        for t, v in inf.items():
            g = np.array([v["gain"][str(i)] if str(i) in v["gain"] else v["gain"][i] for i in range(len(meta["features"]))],
                         float)
            gains.setdefault(t, []).append(g / max(g.sum(), 1e-12))
    tree_top = {t: sorted(zip(meta["features"], np.mean(v, axis=0)), key=lambda x: -x[1])[:8] for t, v in gains.items()}
    out = dict(
        prereg="research/brain/crypto/PREREG_R145_NN.md", n_tests=len(tests),
        rows=int(len(df)), coins=int(df.sym.nunique()), weeks=int(len(np.unique(S.week(df.ts.values)))),
        span=[str(df.ts.min()), str(df.ts.max())],
        scores=model_summary(df), tests=tests, real=[t["id"] for t in tests if t["real"]],
        leadlag=ll_info, leadlag_5m=d5, clusters=d_cl, importance=imp, tree_gain_top=tree_top,
        hour_table=hour_table(df), coin_table=coin_table(df), state_table=None,
    )
    RESULTS.mkdir(exist_ok=True)
    json.dump(_jsonable(out), open(RESULTS / "r145_design.json", "w"), indent=1)
    print(f"{len(tests)} tests; real: {out['real']}")
    return out


def hour_table(df) -> list[dict]:
    rows = []
    for h, sub in df.groupby("ist_hour"):
        y = sub.y_dir_60.to_numpy(float)
        p = sub["N1|y_dir_60"].to_numpy(float)
        ok = np.isfinite(y) & np.isfinite(p)
        v = sub.y_vol_60.to_numpy(float)
        rows.append(dict(ist_hour=int(h), n=int(ok.sum()), dir60_auc=S.auc(p[ok], y[ok]),
                         xs60_ic=float(S.ic_by_time(sub.ts.values, sub["N1|y_xs_60"].to_numpy(float),
                                                    sub.y_xs_60.to_numpy(float)).mean()),
                         mean_abs_ret60_bps=float(np.nanmean(np.abs(sub.y_ret_60)) * 1e4),
                         mean_logvol60=float(np.nanmean(v))))
    return rows


def coin_table(df) -> list[dict]:
    rows = []
    for s, sub in df.groupby("sym"):
        y = sub.y_dir_60.to_numpy(float)
        p = sub["N1|y_dir_60"].to_numpy(float)
        ok = np.isfinite(y) & np.isfinite(p)
        yv, pv = sub.y_vol_60.to_numpy(float), sub["N1|y_vol_60"].to_numpy(float)
        okv = np.isfinite(yv) & np.isfinite(pv)
        r2 = float(1 - np.sum((pv[okv] - yv[okv]) ** 2) / np.sum((yv[okv] - yv[okv].mean()) ** 2)) if okv.sum() > 10 \
            else None
        rows.append(dict(sym=s, n=int(ok.sum()), dir60_auc=S.auc(p[ok], y[ok]) if ok.sum() > 10 else None,
                         vol60_r2_within_coin=r2))
    return rows


# --- trading test -------------------------------------------------------------------------------------------------------
def panel_arrays(symbols, frm, to, grid):
    A = {}
    for f in ("open", "high", "low", "close"):
        A[f] = PT.closes(symbols, frm, to, f).reindex(grid)[symbols].to_numpy(float)
    return A


def select(df: pd.DataFrame, rule: str, h: int, info_q: dict | None) -> pd.DataFrame:
    """TD-h: long the <= 3 highest P(up) at or above the fold's validation 95th percentile, short the <= 3 lowest at
    or below the 5th. TX-h: long the 3 highest predicted ranks, short the 3 lowest."""
    out = []
    if rule == "TD":
        t = f"y_dir_{h}"
        for f, g in df.groupby("fold"):
            lo, hi = info_q[f][t]
            col = f"N1|{t}"
            L = g[g[col] >= hi].sort_values(col, ascending=False).groupby("ts").head(3).assign(side=1)
            Sx = g[g[col] <= lo].sort_values(col).groupby("ts").head(3).assign(side=-1)
            out += [L, Sx]
    else:
        col = f"N1|y_xs_{h}"
        g = df[df.groupby("ts")[col].transform("size") >= 10]
        out = [g.sort_values(col, ascending=False).groupby("ts").head(3).assign(side=1),
               g.sort_values(col).groupby("ts").head(3).assign(side=-1)]
    return pd.concat(out, ignore_index=True) if out else df.iloc[:0].assign(side=0)


def run_trades(df, A, rule, h, window=True, info_q=None, n_twins=1000, label="design"):
    d = df[np.isfinite(df.sig_bar) & (df.sig_bar > 0)]
    if window:
        d = d[TR.in_window(d.ts, h)]
    sel = TR.no_overlap(select(d, rule, h, info_q), h)
    res = {}
    cand = pd.concat([d.assign(side=1), d.assign(side=-1)], ignore_index=True)
    for stop in TR.STOPS:
        for lock in TR.LOCKS:
            key = f"{rule}-{h}{'-window' if window else '-allhours'}-stop{stop:g}-{lock}"
            tt = TR.trades(sel, A["open"], A["high"], A["low"], A["close"], h, stop, lock)
            t2 = TR.trades(sel, A["open"], A["high"], A["low"], A["close"], h, stop, lock, stress=2.0)
            cn = TR.trades(cand, A["open"], A["high"], A["low"], A["close"], h, stop, lock)
            pv, tw = TR.twins_p(float(tt.net.mean()), cn, sel.groupby("ts").size(), n=n_twins)
            sm = TR.summary(tt)
            folds = tt.assign(fold=sel.fold.values).groupby("fold").net.mean() if "fold" in sel else pd.Series()
            res[key] = dict(**sm, net_rs_2x=float(t2.net.mean() * TR.NOTIONAL_RS) if len(t2) else None,
                            twin_p=pv, twin_mean_rs=float(np.mean(tw) * TR.NOTIONAL_RS),
                            folds_pos=int((folds > 0).sum()), folds=int(len(folds)))
            print(label, key, {k: res[key][k] for k in ("trades", "win", "net_rs", "net_rs_2x", "twin_p")
                               if k in res[key]}, flush=True)
    return res


def trade_design(passed: list[str]):
    """Run TD-h / TX-h for every direction / rank forecast of N1 or N2 that passed F1/F2 (PREREG section 6)."""
    df = oos()
    meta = json.load(open(run.out_dir() / "design" / "meta.json"))
    grid = pd.read_parquet(run.out_dir() / "design" / "grid.parquet").grid
    A = panel_arrays(meta["symbols"], "2023-12", "2026-03", pd.DatetimeIndex(grid))
    info_q = {f: json.load(open(run.out_dir() / "design" / "preds" / f"fold{f}_N1_info.json"))["val_q"]
              for f in range(len(run.FOLDS))}
    todo = []
    for pid in passed:
        if pid.startswith(("F1-", "F2-")):
            t = pid.split("-", 1)[1]
            kind, h = t.split("_")[1], int(t.split("_")[2])
            if kind == "dir":
                todo.append(("TD", h))
            elif kind == "xs":
                todo.append(("TX", h))
    todo = sorted(set(todo))
    res = {}
    for rule, h in todo:
        for window in (True, False):
            res.update(run_trades(df, A, rule, h, window, info_q))
    keys = list(res)
    q = S.bh([res[k]["twin_p"] for k in keys])
    for k, qq in zip(keys, q):
        r = res[k]
        r["q"] = qq
        r["passes_design"] = bool(r["trades"] >= 100 and r["net_rs"] > 0 and (r["net_rs_2x"] or -1) > 0 and qq < 0.05
                                  and r["folds_pos"] >= 4 and r["max_dd_rs"] >= -40_000)
    out = dict(todo=todo, variants=res, n_variants=len(res))
    json.dump(_jsonable(out), open(RESULTS / "r145_trade_design.json", "w"), indent=1)
    return out


# --- the ONE holdout read (Amendment 1) ----------------------------------------------------------------------------------
def holdout():
    """Score, once, every design-"real" test and every trading variant that passed design, on 1 Apr - 12 Oct 2026."""
    flag = RESULTS / "r145_HOLDOUT_READ"
    if flag.exists():
        raise SystemExit("R145 holdout already read once; a second read needs a new pre-registration")
    d = json.load(open(RESULTS / "r145_design.json"))
    real = {t["id"]: t for t in d["tests"] if t["real"]}
    D = pd.read_parquet(run.out_dir() / "full" / "rows.parquet")
    P = None
    for m in run.MODELS:
        fp = run.out_dir() / "full" / "preds" / f"fold0_{m}.parquet"
        if fp.exists():
            x = pd.read_parquet(fp)
            P = x if P is None else P.merge(x, on=["ts", "sym"], how="outer")
    keep = ["ts", "sym", "bar", "col", "sig_bar", "fund_rate", "mkt_volz", "btc_r1440", "weekend"] + M.TARGETS
    df = P.merge(D[keep], on=["ts", "sym"], how="left").assign(fold=0)
    assert df.ts.min() >= pd.Timestamp("2026-04-01")
    flag.write_text(f"read {pd.Timestamp.now('UTC')}\n")
    df["ist_hour"] = (pd.DatetimeIndex(df.ts) + IST).hour
    cuts = None
    out = []
    meta = json.load(open(run.out_dir() / "full" / "meta.json"))
    C5 = None
    for tid, t in real.items():
        if tid.startswith("F"):
            fid, tgt = tid.split("-", 1)
            m, c = {f: (mm, cc) for f, mm, cc in F_SETS}[fid]
            r = S.compare(df, tgt, m, c)
        elif tid.startswith("P1-"):
            _, tg, hh = tid.split("-")
            sub = df[df.ist_hour == int(hh[3:])]
            if tg == "dir60":
                r = S.compare(sub, "y_dir_60", "N1", "B0")
                r["effect"] = r["model_auc"] - 0.5
            else:
                r = S.compare(sub, "y_xs_60", "N1", "ZERO")
        elif tid.startswith("P2-"):
            sym = tid.split("-", 2)[2]
            sub = df[df.sym == sym]
            y, p_ = sub.y_dir_60.to_numpy(float), sub["N1|y_dir_60"].to_numpy(float)
            ok = np.isfinite(y) & np.isfinite(p_)
            a, pv = S.bootstrap_auc_p(p_[ok], y[ok], S.week(sub.ts.values[ok]))
            r = dict(n=int(ok.sum()), model_auc=a, effect=a - 0.5, p=pv, metric="AUC")
        elif tid.startswith("P3-"):
            _, tg, name = tid.split("-", 2)
            if cuts is None:
                dd = pd.read_parquet(run.out_dir() / "design" / "rows.parquet", columns=["ts", "mkt_volz"])
                dd = dd[dd.ts >= pd.Timestamp(run.FOLDS[0][0])]
                cuts = dd.mkt_volz.quantile([1 / 3, 2 / 3]).to_numpy()       # the design cut points
            st = {"market vol low": df.mkt_volz <= cuts[0],
                  "market vol mid": (df.mkt_volz > cuts[0]) & (df.mkt_volz <= cuts[1]),
                  "market vol high": df.mkt_volz > cuts[1], "BTC 1-day up": df.btc_r1440 > 0,
                  "BTC 1-day down": df.btc_r1440 <= 0, "weekend": df.weekend == 1, "weekday": df.weekend == 0}[name]
            sub = df[st.to_numpy()]
            r = (S.compare(sub, "y_dir_60", "N1", "B0") if tg == "dir60" else S.compare(sub, "y_xs_60", "N1", "ZERO"))
        elif tid.startswith("P4-1m"):
            tests, _ = PT.leadlag_tests(lo_month="2026-04", hi_month="2026-10")
            r = next(x for x in tests if x["id"] == tid)
        elif tid.startswith("P4-5m"):
            C5 = PT.closes(meta["symbols"], "2026-03", "2026-10") if C5 is None else C5
            r, _ = PT.leadlag_5m(C5, pd.Timestamp("2026-04-01"), pd.Timestamp("2026-10-13"))
        elif tid.startswith("P5"):
            C5 = PT.closes(meta["symbols"], "2026-03", "2026-10") if C5 is None else C5
            r = PT.holdout_cluster_test(C5, d["clusters"]["groups"], pd.Timestamp("2026-04-01"),
                                        pd.Timestamp("2026-10-13"))
        else:
            continue
        r = dict(r)
        r.update(id=tid, design_effect=t["effect"], design_q=t["q"])
        out.append(r)
    q = S.bh([r.get("p", np.nan) for r in out])
    for r, qq in zip(out, q):
        r["q"] = qq
        r["confirmed"] = bool(np.isfinite(qq) and qq < 0.05 and r["effect"] > 0)
    res = dict(amendment="PREREG_R145_NN.md Amendment 1", span=[str(df.ts.min()), str(df.ts.max())],
               rows=int(len(df)), coins=int(df.sym.nunique()), weeks=int(len(np.unique(S.week(df.ts.values)))),
               scores=model_summary(df), tests=out, confirmed=[r["id"] for r in out if r["confirmed"]])
    # trading variants that passed design
    tp = RESULTS / "r145_trade_design.json"
    if tp.exists():
        td = json.load(open(tp))
        passed = [k for k, v in td["variants"].items() if v.get("passes_design")]
        if passed:
            grid = pd.read_parquet(run.out_dir() / "full" / "grid.parquet").grid
            A = panel_arrays(meta["symbols"], "2023-12", "2026-10", pd.DatetimeIndex(grid))
            iq = {0: json.load(open(run.out_dir() / "full" / "preds" / "fold0_N1_info.json"))["val_q"]}
            tr = {}
            for k in passed:
                rule, rest = k.split("-", 1)
                h = int(rest.split("-")[0])
                window = "-window-" in k
                v = run_trades(df, A, rule, h, window, iq, label="holdout")
                tr[k] = v[k]
                v2 = tr[k]
                v2["passes_holdout"] = bool(v2["trades"] >= 30 and (v2.get("net_rs_2x") or -1) > 0
                                            and v2["twin_p"] < 0.10)
            res["trading"] = tr
    json.dump(_jsonable(res), open(RESULTS / "r145_holdout.json", "w"), indent=1)
    print("confirmed:", res["confirmed"])
    return res


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.r145.evaluate")
    ap.add_argument("what", choices=["design", "trade", "holdout"])
    a = ap.parse_args(argv)
    if a.what == "design":
        design()
    elif a.what == "trade":
        d = json.load(open(RESULTS / "r145_design.json"))
        trade_design(d["real"])
    else:
        holdout()
    return 0


if __name__ == "__main__":
    sys.exit(main())
