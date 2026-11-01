"""R144d: the two network arms' trades, rebuilt exactly from their engines, with features read before entry.

    python research/hunt/r144d_loss_nn_mcx/nn_arms.py cisd     # -> $R144D_OUT/trades_CISD.parquet
    python research/hunt/r144d_loss_nn_mcx/nn_arms.py s5       # -> $R144D_OUT/trades_S5.parquet
    python research/hunt/r144d_loss_nn_mcx/nn_arms.py features [CISD|S5]  # adds the f_* columns

- CISD + network (R133 S3-V2-E3): R133's own trade list (R131 entries, R132's E3 exit: R131 index stop + 60% lock after
  +Rs 1,000), R132's N-EV filter on R123's out-of-sample N1 predictions (walk-forward folds F1-F4 and the holdout model).
  Nothing re-simulated. Parity with R133's design.json / holdout_seen.json is checked and written.
- S5 Trail (R128 x1 on R123's S5 morning pick, the app's IV + measured offset, s5_app_check.py --cal). Its functions are
  imported, unchanged; only the trade loop is copied to keep the extra columns (cell, EV, P(win), strike, exit row).
  Parity with results/s5_app_check_cal.json is checked and written.

Data: $IRAALGO_LAKE (index_1m, index_daily, options_rolling_1m, equity_1m), $R123_OUT / $R123_PREDS (R123's cleaned
rows, sequences, fold networks and predictions from gdrive derived/r123/cleaned), $R131_OUT / $R133_OUT.
Signal source: the index spot for both arms (the network reads option-chain context only).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R144D_OUT", CACHE / "r144d")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE / "lake")).expanduser()
RESULTS = HERE / "results"
MIN0 = 9 * 60 + 15                     # minute index 0 = 09:15 IST
NM = 375
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
WIN = 15                               # the look-back window (minutes) of the option / order-flow features
# R123's input features at the decision row (computed by R123 from bars up to that row; R123's own tests cover them)
R123_CTX = ["vix", "vix_chg", "br_up", "pcr", "pcr_chg15", "atm_iv", "skew", "c_oi_chg15", "p_oi_chg15", "cp_vol15",
            "to_expiry", "is_expiry"]


# ------------------------------------------------------------------------------------------------ feature function
def entry_features(r0: int, side: int, io, ih, il, ic, prev: pd.DataFrame, opt: dict | None,
                   atm_vol_hist: np.ndarray | None, breadth_row: np.ndarray | None) -> dict:
    """Features of one trade bought at the open of minute r0 (minute 0 = 09:15). Reads ONLY minutes < r0 of today's
    index arrays (io, ih, il, ic), of the option arrays in `opt` (o, h, l, c, v, oi, iv) and of `breadth_row`.
    `prev`: the previous sessions' daily OHLC (oldest first, today excluded). `atm_vol_hist`: the ATM same-side option
    volume of each of the previous <= 20 sessions over minutes [r0 - 15, r0) (already from earlier days).
    side: +1 the trade gains when the index rises (call), -1 when it falls (put)."""
    f = {}
    io, ih, il, ic = (np.asarray(x, float)[:r0] for x in (io, ih, il, ic))
    ok = np.isfinite(ic)
    nan = np.nan
    last = ic[ok][-1] if ok.any() else nan
    op = io[np.isfinite(io)][0] if np.isfinite(io).any() else nan
    pr = prev.tail(20)
    rng20 = float((pr.high - pr.low).mean()) if len(pr) >= 5 else nan
    if len(prev) >= 2:
        pc = prev.close.shift(1)
        tr = np.maximum(prev.high - prev.low, np.maximum((prev.high - pc).abs(), (prev.low - pc).abs()))
        atr = float(tr.tail(14).mean())
    else:
        atr = nan
    good = np.isfinite(atr) and atr > 0
    cl3 = ic[ok][-4:] if ok.sum() >= 4 else None
    f["f_push"] = side * (cl3[-1] - cl3[0]) / atr if (good and cl3 is not None) else nan
    hi_, lo_ = (np.nanmax(ih), np.nanmin(il)) if np.isfinite(ih).any() else (nan, nan)
    f["f_rng_today"] = (hi_ - lo_) / rng20 if np.isfinite(rng20) and rng20 > 0 else nan
    f["f_trend20"] = side * (last - float(pr.close.mean())) / atr if good and len(pr) >= 20 else nan
    pclose = float(prev.close.iloc[-1]) if len(prev) else nan
    f["f_gap"] = side * (op - pclose) / atr if good else nan
    if len(prev) and good:
        f["f_d_pdh"] = (last - float(prev.high.iloc[-1])) / atr
        f["f_d_pdl"] = (last - float(prev.low.iloc[-1])) / atr
    else:
        f["f_d_pdh"] = f["f_d_pdl"] = nan
    f["f_d_open"] = side * (last - op) / atr if good else nan
    f["f_pos_day"] = ((last - lo_) / (hi_ - lo_) if side > 0 else (hi_ - last) / (hi_ - lo_)) \
        if np.isfinite(hi_) and hi_ > lo_ else nan
    f["f_min"] = float(MIN0 + r0)
    f["f_prev_rng"] = float(prev.high.iloc[-1] - prev.low.iloc[-1]) / rng20 if len(prev) and rng20 > 0 else nan
    f["f_ret5"] = side * (pclose - float(prev.close.iloc[-6])) / atr if good and len(prev) >= 6 else nan
    # option (the traded contract), all over minutes [r0 - WIN, r0)
    for k in ("f_prem", "f_prem_pct", "f_opt_spread", "f_opt_notrade", "f_opt_oi", "f_opt_oi_chg", "f_iv_chg",
              "f_opt_vol_rel", "f_od", "f_oiq"):
        f[k] = nan
    if opt is not None:
        a0 = max(r0 - WIN, 0)
        c = np.asarray(opt["c"], float)[:r0]
        h = np.asarray(opt["h"], float)[:r0]
        lo = np.asarray(opt["l"], float)[:r0]
        o = np.asarray(opt["o"], float)[:r0]
        v = np.asarray(opt["v"], float)[:r0]
        oi = np.asarray(opt["oi"], float)[:r0]
        iv = np.asarray(opt["iv"], float)[:r0]
        traded = np.isfinite(c) & (np.nan_to_num(v) > 0)
        if traded.any():
            pc_ = c[traded][-1]
            f["f_prem"] = pc_
            f["f_prem_pct"] = pc_ / last * 100 if np.isfinite(last) and last > 0 else nan
        w = slice(a0, r0)
        tw = traded[w]
        if len(tw):
            f["f_opt_notrade"] = float(1 - tw.mean())
        if tw.any():
            sp = (h[w] - lo[w])[tw] / c[w][tw]
            f["f_opt_spread"] = float(np.median(sp) * 100)
            up = (c[w] > o[w]) & tw
            dn = (c[w] < o[w]) & tw
            tot = float(v[w][tw].sum())
            f["f_od"] = float((v[w][up].sum() - v[w][dn].sum()) / tot) if tot > 0 else nan
        oiv = oi[np.isfinite(oi) & (oi > 0)]
        if len(oiv):
            f["f_opt_oi"] = float(oiv[-1])
            f["f_opt_oi_chg"] = float(oiv[-1] / oiv[0] - 1) * 100
        ivv = iv[np.isfinite(iv) & (iv > 0)]
        if len(ivv) >= 2:
            f["f_iv_chg"] = float(ivv[-1] - ivv[0])
        # OI-price quadrant over the window (proxy): 1 price up & OI up, 2 up & OI down, 3 down & OI up, 4 down & OI down
        cw, oiw = c[w][tw], oi[w][tw]
        if len(cw) >= 2 and np.isfinite(oiw).all():
            pu, ou = cw[-1] >= cw[0], oiw[-1] >= oiw[0]
            f["f_oiq"] = float({(True, True): 1, (True, False): 2, (False, True): 3, (False, False): 4}[(pu, ou)])
        if atm_vol_hist is not None and len(atm_vol_hist) >= 5 and tw.any():
            base = float(np.nanmean(atm_vol_hist))
            f["f_opt_vol_rel"] = float(v[w][tw].sum()) / base if base > 0 else nan
    if breadth_row is not None:
        b = np.asarray(breadth_row, float)[:r0]
        b = b[np.isfinite(b)]
        f["f_breadth_with"] = float(b[-1] if side > 0 else 1 - b[-1]) if len(b) else nan
    else:
        f["f_breadth_with"] = nan
    return f


# ------------------------------------------------------------------------------------------------ lake readers
def index_grid(und: str) -> tuple[list, dict]:
    fs = sorted((LAKE / "index_1m").glob(f"index_1m_{und}_*.parquet"))
    df = pd.concat([pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]) for p in fs])
    df = df[(df.ts.dt.year >= 2021)]
    df["d"] = df.ts.dt.date
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute - MIN0
    df = df[(df.m >= 0) & (df.m < NM)].drop_duplicates(["d", "m"])
    days = sorted(df.d.unique())
    di = {d: i for i, d in enumerate(days)}
    A = {k: np.full((len(days), NM), np.nan) for k in ("open", "high", "low", "close")}
    r = df.d.map(di).values
    for k in A:
        A[k][r, df.m.values] = df[k].values
    return days, A


def daily(und: str) -> pd.DataFrame:
    p = next((LAKE / "index_daily").glob("*.parquet"))
    d = pd.read_parquet(p)
    d = d[d.symbol == und].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    return d.sort_values("date").reset_index(drop=True)


def breadth_grid() -> dict:
    """Share of the lake's 59 stocks (NIFTY 50 + NIFTY BANK) above their 09:15 open, by day and minute (minute close)."""
    out = {}
    for p in sorted((LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        e = pd.read_parquet(p, columns=["symbol", "ts", "open", "close"])
        e["d"] = e.ts.dt.date
        e["m"] = e.ts.dt.hour * 60 + e.ts.dt.minute - MIN0
        e = e[(e.m >= 0) & (e.m < NM)]
        e["symbol"] = e.symbol.astype(str)
        first = e.sort_values("m").groupby(["d", "symbol"]).open.first().rename("o0")
        e = e.join(first, on=["d", "symbol"])
        for d, g in e.groupby("d"):
            pv = g.pivot_table(index="symbol", columns="m", values="close", aggfunc="last")
            pv = pv.reindex(columns=range(NM)).ffill(axis=1)
            o0 = g.groupby("symbol").o0.first().reindex(pv.index)
            up = (pv.values > o0.values[:, None]).astype(float)
            up[~np.isfinite(pv.values)] = np.nan
            out[d] = np.nanmean(up, 0) if np.isfinite(up).any() else np.full(NM, np.nan)
    return out


class OptReader:
    """The traded contract's minute bars (a strike of one series, one side) and the ATM same-side volume profile.
    Keeps at most two series-years in memory."""

    def __init__(self, und: str):
        self.und, self.cache, self.atm = und, {}, {}

    def _year(self, series: str, y: int) -> dict | None:
        k = (series, y)
        if k not in self.cache:
            if len(self.cache) >= 2:
                self.cache.pop(next(iter(self.cache)))
            p = LAKE / "options_rolling_1m" / f"opt_{self.und}_{series}_{y}.parquet"
            if not p.exists():
                self.cache[k] = None
            else:
                o = pd.read_parquet(p, columns=["ts", "offset", "side", "strike", "open", "high", "low", "close",
                                                "volume", "oi", "iv"])
                o["side"] = o.side.astype(str).str[0].str.upper()
                o["d"] = o.ts.dt.date
                o["m"] = (o.ts.dt.hour * 60 + o.ts.dt.minute - MIN0).astype(np.int16)
                o = o[(o.m >= 0) & (o.m < NM)].drop(columns="ts")
                a = o[o.offset == 0]
                for cp in ("C", "P"):
                    pv = a[a.side == cp].pivot_table(index="d", columns="m", values="volume", aggfunc="sum")
                    self.atm[(series, y, cp)] = pv.reindex(columns=range(NM)).fillna(0.0)
                self.cache[k] = dict(tuple(o.groupby("d")))
        return self.cache[k]

    def contract(self, series: str, d: date, cp: str, strike: float) -> dict | None:
        o = self._year(series, d.year)
        g = o.get(d) if o is not None else None
        if g is None:
            return None
        g = g[(g.side == cp) & (np.isclose(g.strike, strike))].drop_duplicates("m")
        if g.empty:
            return None
        out = {}
        for k, c in (("o", "open"), ("h", "high"), ("l", "low"), ("c", "close"), ("v", "volume"), ("oi", "oi"),
                     ("iv", "iv")):
            a = np.full(NM, np.nan)
            a[g.m.values] = g[c].values.astype(float)
            out[k] = a
        nt = ~(np.nan_to_num(out["v"]) > 0)                 # no print: the lake's carried price, never a price (R123)
        for k in ("o", "h", "l", "c"):
            out[k][nt] = np.nan
        return out

    def atm_vol_hist(self, series: str, d: date, cp: str, r0: int, days: list) -> np.ndarray | None:
        """ATM (offset 0) same-side volume over minutes [r0 - 15, r0) on each of the 20 sessions before d."""
        prior = [x for x in days if x < d][-20:]
        vals = []
        for y in sorted({x.year for x in prior}):
            if (series, y, cp) not in self.atm:
                self._year(series, y)
            pv = self.atm.get((series, y, cp))
            if pv is None:
                continue
            ds = [x for x in prior if x.year == y and x in pv.index]
            if ds:
                vals += list(pv.loc[ds, list(range(max(r0 - WIN, 0), r0))].sum(axis=1).values)
        return np.array(vals, float) if vals else None


# ------------------------------------------------------------------------------------------------ CISD + network
def build_cisd() -> pd.DataFrame:
    sys.path.insert(0, str(HUNT / "r133_ict_nn_exits"))
    import r133  # noqa: E402
    NN, K = r133.NN, r133.K
    parts = []
    parity = {}
    pub = {p: json.loads((HUNT / "r133_ict_nn_exits" / "results" / f"{p}.json").read_text())["variants"]["S3-V2-E3"]
           for p in ("design", "holdout_seen")}
    for part, f, pf in (("design", "trades_design.parquet", "wf_rows_preds_N1.parquet"),
                        ("holdout_seen", "trades_holdout_seen.parquet", "holdout_rows_preds_N1.parquet")):
        T = pd.read_parquet(r133.OUT / f)
        T = T[T.v == "S3-V2-E3"].copy()
        T["day"] = pd.to_datetime(T.day).dt.date
        if part == "design":
            T = T[[K.fold_of(d) is not None for d in T.day]]
        P = pd.read_parquet(NN.PREDS / pf)
        M = NN.attach(T, P)
        S = M[NN.keep_mask(M, "NEV")].copy()
        pw_c = P[[f"pw_{2 * c}" for c in range(8)]].mean(axis=1)
        pw_p = P[[f"pw_{2 * c + 1}" for c in range(8)]].mean(axis=1)
        V = P[["und", "day", "row"] + R123_CTX].copy()
        V["pw_c"], V["pw_p"] = pw_c.values, pw_p.values
        S = S.merge(V, on=["und", "day", "row"], how="left")
        S["f_ev"] = S.ev_side
        S["f_conf"] = np.where(S.side > 0, S.pw_c, S.pw_p)
        S["src"] = part
        parity[part] = dict(n=len(S), avg_net=round(float(S.net.mean()), 2), win=round(float((S.net > 0).mean()), 4),
                            published_n=pub[part]["n"], published_avg_net=round(pub[part]["avg_net"], 2),
                            published_win=round(pub[part]["win"], 4))
        parts.append(S)
    T = pd.concat(parts, ignore_index=True)
    T["arm"] = "CISD"
    T["r0"] = T.entry.astype(int)
    T["xr"] = T.exit.astype(int)
    T["mins_held"] = T.xr - T.r0
    T["exit_why"] = T.why
    # R131's series rule: on a weekly-expiry day the trade is in the MONTH series, else WEEK
    sys.path.insert(0, str(HUNT / "r123_neural_70"))
    import r123data as D3  # noqa: E402
    wexp = {u: D3.LakeOptions(u, date(2026, 10, 19)).expiry_days() for u in T.und.unique()}
    T["series"] = ["MONTH" if d in wexp[u] else "WEEK" for u, d in zip(T.und, T.day)]
    _write_parity("CISD", parity)
    return T


# ------------------------------------------------------------------------------------------------ S5 Trail
def build_s5() -> pd.DataFrame:
    sys.path.insert(0, str(HUNT / "r128_missing"))
    import s5_app_check as A  # noqa: E402
    K, D, T_ = A.K, A.D, A.T
    Fd, Sd = D.load("design")
    Fh = pd.read_parquet(K.OUT / "rows_holdout.parquet")
    Sh = dict(np.load(K.OUT / "seq_holdout.npz"))
    morning = list(K.MORNING_ROWS)
    jobs = []
    for f, (a, b) in K.FOLDS.items():
        train = Fd[Fd.day < a - timedelta(days=K.FOLD_EMBARGO_DAYS)]
        test = Fd[(Fd.day >= a) & (Fd.day <= b) & Fd.row.isin(morning)]
        jobs.append((f"wf_{f}", "wf", train, test, Sd, K.DESIGN[1]))
    jobs.append(("holdout", "holdout", Fd, Fh[Fh.row.isin(morning)], Sh, K.HOLDOUT[1]))
    rows = []
    for tag, part, train, test, S, last_day in jobs:
        m = A.Model(tag, train, S)
        test = test.reset_index(drop=True)
        tapp = pd.concat([A.app_chain_iv(test[test.und == u], u, last_day, True) for u in K.UNDS]).loc[test.index]
        ev, P = m.ev(tapp)
        C = A.picks(tapp, ev)
        ix = C.index.values
        C["pw"] = P[ix, C.j.values, 0]
        C["pl"] = P[ix, C.j.values, 1]
        for k in R123_CTX:
            C[k] = tapp.loc[ix, k].values
        C["atm_iv_app"] = tapp.loc[ix, "atm_iv"].values
        C["skew_app"] = tapp.loc[ix, "skew"].values
        for k in ("atm_iv", "skew"):                      # the lake's (Dhan's) values, as R123 stored them
            C[k] = test.loc[ix, k].values
        rows += _s5_trades(A, K, D, T_, C, last_day, part, tag)
        print(tag, len(C), "picks", flush=True)
    T = pd.DataFrame(rows)
    pub = json.loads((HUNT / "r128_missing" / "results" / "s5_app_check_cal.json").read_text())
    parity = {}
    for part in ("wf", "holdout"):
        X = T[T.src == part]
        p = pub[part]["app_iv"]
        parity[part] = dict(n=len(X), avg_net=round(float(X.net.mean()), 2), win=round(float((X.net > 0).mean()), 4),
                            total=round(float(X.net.sum()), 2), published_n=p["n"],
                            published_avg_net=round(p["avg"], 2), published_win=round(p["win"], 4),
                            published_total=round(p["total"], 2))
    _write_parity("S5", parity)
    T["arm"] = "S5"
    return T


def _s5_trades(A, K, D, T_, C, last_day, part, tag) -> list:
    """s5_app_check.trail_trades, copied to keep the extra columns; the rules (one open trade per index, R128 x1 exit,
    5 a day across indices in time order) are unchanged."""
    out = []
    for und, Cu in C.groupby("und"):
        g = D.load_grid(und, last_day)
        LO = D.LakeOptions(und, last_day)
        exp = LO.expiry_days()
        pos, lot, step = g.pos(), K.LOT[und], K.STEP[und]
        for d, Cd in Cu.groupby("day"):
            i = pos.get(d)
            opts = LO.day(d, g.c[i]) if i is not None else None
            if opts is None:
                continue
            last = K.EXIT_LAST_EXPIRY if d in exp else K.EXIT_LAST
            busy = -1
            for tr in Cd.sort_values(["row", "score"], ascending=[True, False]).itertuples(index=False):
                if tr.row <= busy:
                    continue
                x, y, _ = K.CELLS[int(tr.j) // 2]
                side = "C" if int(tr.j) % 2 == 0 else "P"
                c = D._last(g.c[i][:tr.row + 1])
                strike = D.atm(c, step)
                a = opts.contracts.get((side, strike))
                r_ = T_.convex_exit(a, tr.row, last, lot, d, und, x, y, "x1") if a is not None else None
                if r_ is None:
                    continue
                busy = r_["exit"]
                rec = dict(und=und, day=d, row=int(tr.row), side=1 if side == "C" else -1, cp=side, strike=float(strike),
                           y=y, x=x, j=int(tr.j), net=r_["net"], netx2=r_["netx2"], why=r_["why"], xr=int(r_["exit"]),
                           f_ev=float(tr.score), f_conf=float(tr.pw), f_ploss=float(tr.pl), series="WEEK",
                           is_exp_day=d in exp, src=part, fold=tag)
                for k in R123_CTX + ["atm_iv_app", "skew_app"]:
                    rec[k] = getattr(tr, k)
                out.append(rec)
    T = pd.DataFrame(out)
    if T.empty:
        return out
    T = T.sort_values(["day", "row"]).groupby("day").head(5)
    T["r0"] = T.row + 1
    T["mins_held"] = T.xr - T.r0
    T["exit_why"] = T.why
    return T.to_dict("records")


def _write_parity(arm: str, parity: dict):
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / "parity_nn.json"
    cur = json.loads(p.read_text()) if p.exists() else {}
    cur[arm] = parity
    p.write_text(json.dumps(cur, indent=1, default=str))
    print(arm, json.dumps(parity), flush=True)


# ------------------------------------------------------------------------------------------------ features for both
def add_features(T: pd.DataFrame, step: dict, breadth: dict) -> pd.DataFrame:
    rows = []
    for und, Tu in T.groupby("und"):
        days, A = index_grid(und)
        di = {d: i for i, d in enumerate(days)}
        dd = daily(und)
        R = OptReader(und)
        for t in Tu.itertuples():
            i = di.get(t.day)
            if i is None:
                rows.append(dict(_i=t.Index))
                continue
            prev = dd[dd.date < t.day]
            r0 = int(t.r0)
            cp = "C" if t.side > 0 else "P"
            series = t.series if isinstance(getattr(t, "series", None), str) else "WEEK"
            strike = getattr(t, "strike", np.nan)
            if not np.isfinite(strike):
                c = A["close"][i][:r0]
                c = c[np.isfinite(c)]
                strike = float(np.round(c[-1] / step[und]) * step[und]) if len(c) else np.nan
            opt = R.contract(series, t.day, cp, strike) if np.isfinite(strike) else None
            hist = R.atm_vol_hist(series, t.day, cp, r0, days)
            f = entry_features(r0, int(t.side), A["open"][i], A["high"][i], A["low"][i], A["close"][i], prev, opt, hist,
                               breadth.get(t.day))
            f["_i"] = t.Index
            f["f_strike"] = strike
            rows.append(f)
    F = pd.DataFrame(rows).set_index("_i")
    T = T.join(F)
    # R123's context, signed where it has a direction; the network's own inputs at the decision row
    T["f_vix"], T["f_vix_chg"] = T.vix, T.vix_chg
    T["f_br_idx_with"] = np.where(T.side > 0, T.br_up, 1 - T.br_up)      # share of the 5 indices up on the day
    T["f_pcr"], T["f_pcr_chg15"] = T.pcr, T.pcr_chg15
    T["f_atm_iv"] = T.atm_iv
    T["f_skew_with"] = np.where(T.side > 0, -T["skew"], T["skew"])             # + = the bought side's wing is dearer
    T["f_oi_chg_with"] = np.where(T.side > 0, T.c_oi_chg15, T.p_oi_chg15)
    T["f_dte"] = T.to_expiry
    T["f_wd"] = [d.weekday() for d in T.day]
    return T


def main(cmd: str):
    OUT.mkdir(parents=True, exist_ok=True)
    if cmd == "cisd":
        build_cisd().to_parquet(OUT / "trades_CISD_raw.parquet")
    elif cmd == "s5":
        build_s5().to_parquet(OUT / "trades_S5_raw.parquet")
    elif cmd == "features":
        sys.path.insert(0, str(HUNT / "r123_neural_70"))
        import r123cfg as K  # noqa: E402
        bp = OUT / "breadth.parquet"
        if not bp.exists():
            b = breadth_grid()
            pd.DataFrame({str(k): v for k, v in b.items()}).to_parquet(bp)
        bd = pd.read_parquet(bp)
        br = {date.fromisoformat(k): bd[k].values for k in bd.columns}
        for arm in (sys.argv[2:] or ["CISD", "S5"]):
            T = pd.read_parquet(OUT / f"trades_{arm}_raw.parquet")
            T["day"] = pd.to_datetime(T.day).dt.date
            if "strike" not in T:
                T["strike"] = np.nan
            T = add_features(T.reset_index(drop=True), K.STEP, br)
            T.to_parquet(OUT / f"trades_{arm}.parquet")
            print(arm, len(T), "trades with features", flush=True)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "cisd")
