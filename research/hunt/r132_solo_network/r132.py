"""R132 (PREREG.md): does R123's network (N1) improve Solo (midday)'s picks? 29 Oct 2026.

    IRAALGO_LAKE=<lake> R123_OUT=<r123 cache> python research/hunt/r132_solo_network/r132.py wf        # -> results/wf.json
    IRAALGO_LAKE=<lake> R123_OUT=<r123 cache> python research/hunt/r132_solo_network/r132.py holdout   # once -> results/results.json

Solo (midday) is ported from android/engine/.../orb/SoloMidday.kt (signal on the index at 12:00, 4-ITM option, index stop 0.3
ATR, breakeven lock at 75% of 2R, out at 14:30). The network's 12:00 forecasts (row 164) veto or re-pick Solo's trade: F1 EV
veto, F2 quiet-hour veto, F3 direction veto, F4 network re-pick, F5 F1+F2. Every idea is compared with Solo's plain rule on the
same days. Signal source: the underlying index; the option is what is bought.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r128_missing"))
import r128 as T8  # noqa: E402  (puts r123 / r124 on the path)
import r123cfg as K  # noqa: E402
import r123data as D3  # noqa: E402
import r123rules as R3  # noqa: E402

OUT = Path(os.environ.get("R132_OUT", K.CACHE_ROOT / "r132")).expanduser()
RESULTS = HERE / "results"
R123_OUT = T8.R123_OUT
O_, H_, L_, C_, V_ = range(5)
NJ = 16

# ---- Solo (midday), as SoloMidday.kt ---------------------------------------------------------------------------------
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0}
MOVE_ATR, OUTER, ITM_STEPS = 0.5, 0.25, 4
STOP_ATR, TARGET_R, LOCK_AT, LOCK_KEEP = 0.3, 2.0, 0.75, 0.0
MIN_MINUTES, ATR_DAYS = 100, 14
ROW_1159 = K.row(11, 59)          # 164: the bar that closes at 12:00 (the decision bar)
ROW_1200 = K.row(12, 0)           # 165: the entry bar (its open is the 12:00 price)
ENTRY_SLACK = 3                   # option opens 12:00..12:03 (rows 165-168)
ROW_EXIT = K.row(14, 30)          # 315: the walk reads bars starting before 14:30
FIRST_BY = 1                      # the first bar by 09:16
SELL_SLACK = 3                    # the sale: the first traded open of the 3 bars after the deciding bar
EPS = 1e-9

IDEAS = ("F1", "F2", "F3", "F4", "F5")
LABELS = {
    "plain": "Solo (midday) as the app runs it (reference)",
    "F1": "EV veto: take Solo's trade only when the network's best EV for Solo's side (8 cells, ATM option) is >= 0",
    "F2": "quiet-hour veto: skip when the network's 60-minute range forecast is in the bottom third of the index's last 120 sessions",
    "F3": "direction veto: take only when the network's P(up in 60 min) agrees with Solo's side (expected to fail)",
    "F4": "network re-pick: of the signalling indices, take the one with the best Solo-side EV instead of the strongest",
    "F5": "F1 and F2 together",
}
Q_WINDOW, Q_MIN, Q_LEVEL = 120, 20, 1.0 / 3.0
TWINS = 1000
BOOT = 1000
SEED = 132
FOLDS = dict(K.FOLDS)


# ------------------------------------------------------------------------------------------------ the port
def atm(x: float, step: float) -> float:
    return D3.atm(x, step)


def daily_atr(g) -> np.ndarray:
    """ATR14 per session of the grid: the mean true range of the 14 completed daily candles before it (15 sessions needed)."""
    with np.errstate(all="ignore"), __import__("warnings").catch_warnings():
        __import__("warnings").simplefilter("ignore", RuntimeWarning)
        hi = np.nanmax(g.h, 1)
        lo = np.nanmin(g.l, 1)
    cl = np.array([D3._last(g.c[i]) for i in range(len(g.days))])
    pc = np.r_[np.nan, cl[:-1]]
    tr = np.nanmax(np.vstack([hi - lo, np.abs(hi - pc), np.abs(lo - pc)]), 0)
    tr[0] = np.nan                                     # the first session has no previous close
    out = np.full(len(g.days), np.nan)
    for i in range(ATR_DAYS + 1, len(g.days)):
        w = tr[i - ATR_DAYS:i]
        if np.isfinite(w).all():
            out[i] = float(w.mean())
    return out


def signal(o: np.ndarray, h: np.ndarray, l: np.ndarray, c: np.ndarray, atr: float) -> dict | None:
    """SoloMidday.signal on one session's 375-row arrays: reads rows <= 164 only, plus the 12:00 bar's open as the price."""
    if not (np.isfinite(atr) and atr > 0):
        return None
    pre = np.flatnonzero(np.isfinite(c[:ROW_1159 + 1]))
    if not len(pre) or pre[-1] != ROW_1159 or pre[0] > FIRST_BY or len(pre) < MIN_MINUTES:
        return None
    op = float(o[pre[0]])
    cc = float(c[ROW_1159])
    hi = float(np.nanmax(h[:ROW_1159 + 1]))
    lo = float(np.nanmin(l[:ROW_1159 + 1]))
    move = cc - op
    if abs(move) < MOVE_ATR * atr:
        return None
    side = 1 if move > 0 else -1
    pos = (hi - cc) / (hi - lo) if side > 0 else (cc - lo) / (hi - lo)
    if pos > OUTER:
        return None
    index = float(o[ROW_1200]) if np.isfinite(o[ROW_1200]) else cc
    return dict(side=side, strength=abs(move) / atr, open=op, close=cc, high=hi, low=lo, atr=float(atr), index=index,
                position=float(pos))


def walk(side: int, index: float, atr: float, h: np.ndarray, l: np.ndarray, c: np.ndarray) -> tuple[str, int]:
    """SoloMidday.walk over the bars 12:00..14:29: (exit reason, the row it was decided on; 314 for 14:30)."""
    stop = index - side * STOP_ATR * atr
    lock_lvl = index + side * LOCK_KEEP * TARGET_R * STOP_ATR * atr
    earn = LOCK_AT * TARGET_R * STOP_ATR * atr
    best, locked = 0.0, False
    for r in range(ROW_1200, ROW_EXIT):
        if not np.isfinite(c[r]):
            continue
        hh = h[r] if np.isfinite(h[r]) else c[r]
        ll = l[r] if np.isfinite(l[r]) else c[r]
        fav = hh - index if side > 0 else index - ll
        if (c[r] - stop) * side <= 0:
            return "index_stop", r
        if locked and (ll <= lock_lvl if side > 0 else hh >= lock_lvl):
            return "lock", r
        best = max(best, fav)
        if best >= earn - EPS:
            locked = True
    return "time", ROW_EXIT - 1


def strikes_to_try(und: str, side: int, index: float) -> list[float]:
    s = STEP[und]
    w = atm(index, s) - side * ITM_STEPS * s
    return [w, w + side * s, w - side * s]


def first_open(a: np.ndarray, r0: int, r1: int) -> int | None:
    for r in range(r0, min(r1, K.N - 1) + 1):
        if np.isfinite(a[r, O_]) and a[r, O_] > 0:
            return r
    return None


def option_trade(a: np.ndarray, exit_row: int, lot: int, d, und: str) -> dict | None:
    """Buy at the first traded open of rows 165-168; sell at the first traded open of the 3 bars after the deciding bar, else
    the last traded close up to then. Costs('dated'), Fills('liq'); netx2 = the slippage once more."""
    rb = first_open(a, ROW_1200, ROW_1200 + ENTRY_SLACK)
    if rb is None:
        return None
    buy, raw_buy = T8.buy_fill(a, rb, lot)
    rs = first_open(a, max(exit_row + 1, rb + 1), exit_row + SELL_SLACK)
    if rs is not None:
        s = T8.sell_at(a, rb, rs, lot, price=float(a[rs, O_]))
    else:
        rs = min(exit_row + SELL_SLACK, K.N - 1)
        s = T8.sell_at(a, rb, rs, lot)
    if s is None:
        return None
    net, netx2 = T8.leg_net(buy, raw_buy, s[0], s[1], lot, d, und)
    return dict(net=float(net), netx2=float(netx2), buy_row=int(rb), sell_row=int(rs), buy=float(raw_buy), sell=float(s[1]))


def trade_of(opts, und: str, sig: dict, h, l, c, d) -> dict | None:
    """The option Solo would buy (4 ITM, else the nearest within a step, less ITM first) and its outcome; None if untradable."""
    why, xr = walk(sig["side"], sig["index"], sig["atr"], h, l, c)
    if opts is None:
        return None
    for k in strikes_to_try(und, sig["side"], sig["index"]):
        a = opts.contracts.get(("C" if sig["side"] > 0 else "P", float(k)))
        if a is None or first_open(a, ROW_1200, ROW_1200 + ENTRY_SLACK) is None:
            continue
        res = option_trade(a, xr, K.LOT[und], d, und)
        if res is None:
            continue
        return dict(res, strike=float(k), exit_why=why, exit_row=int(xr))
    return None


# ------------------------------------------------------------------------------------------------ per index
def build_index(args) -> pd.DataFrame:
    """Every (und, day) of `days`: Solo's signal at 12:00 and, when it signals off an expiry day, its trade."""
    und, days, last_day = args
    g = D3.load_grid(und, last_day)
    atr = daily_atr(g)
    pos = g.pos()
    LO = D3.LakeOptions(und, last_day)
    exp = LO.expiry_days()
    rows = []
    for d in sorted(days):
        i = pos.get(d)
        rec = dict(und=und, day=d, signal=False, expiry=d in exp, tradable=False)
        if i is None:
            rows.append(rec)
            continue
        sig = signal(g.o[i], g.h[i], g.l[i], g.c[i], atr[i])
        if sig is not None:
            rec.update(signal=True, **sig)
            if not rec["expiry"]:
                opts = LO.day(d, g.c[i])
                t = trade_of(opts, und, sig, g.h[i], g.l[i], g.c[i], d)
                if t is not None:
                    rec.update(tradable=True, **t)
        rows.append(rec)
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------------ the network's columns
def network_rows(F: pd.DataFrame) -> pd.DataFrame:
    """Row 164 of the three indices: best EV per side (8 cells), rg60, pdir60."""
    X = F[(F.row == ROW_1159) & F.und.isin(UNDS)].copy()
    ev = X[[f"ev_{j}" for j in range(NJ)]].values
    X["ev_call"] = ev[:, 0::2].max(1)
    X["ev_put"] = ev[:, 1::2].max(1)
    return X[["und", "day", "rg60", "pdir60", "ev_call", "ev_put"]].reset_index(drop=True)


def quiet_flags(N: pd.DataFrame) -> pd.Series:
    """F2: rg60 below the 33.3rd percentile of the same index's previous 120 sessions (>= 20 needed, else not quiet).
    Reads only earlier sessions."""
    out = pd.Series(False, index=N.index)
    for _, g in N.groupby("und"):
        g = g.sort_values("day")
        v = g.rg60.values
        for k, ix in enumerate(g.index):
            prev = v[max(0, k - Q_WINDOW):k]
            prev = prev[np.isfinite(prev)]
            if len(prev) >= Q_MIN and np.isfinite(v[k]):
                out[ix] = bool(v[k] < np.quantile(prev, Q_LEVEL))
    return out


# ------------------------------------------------------------------------------------------------ the picks
def candidates(S: pd.DataFrame) -> pd.DataFrame:
    """Signalling, tradable indices off their expiry day, strongest first (ties A-Z)."""
    C = S[S.signal & ~S.expiry & S.tradable].copy()
    return C.sort_values(["day", "strength", "und"], ascending=[True, False, True])


def side_ev(C: pd.DataFrame) -> np.ndarray:
    return np.where(C.side.values > 0, C.ev_call.values, C.ev_put.values)


def picks(C: pd.DataFrame, idea: str) -> pd.DataFrame:
    """One trade a day (or none) for the plain rule or an idea. Vetoes apply to Solo's own pick (no passing the turn)."""
    if C.empty:
        return C
    C = C.assign(sev=side_ev(C))
    if idea == "F4":
        T = C.sort_values(["day", "sev", "strength", "und"], ascending=[True, False, False, True]).groupby("day").head(1)
        return T.sort_values("day")
    T = C.groupby("day", sort=True).head(1)
    if idea == "plain":
        return T
    ok = np.ones(len(T), bool)
    if idea in ("F1", "F5"):
        ok &= T.sev.values >= 0
    if idea in ("F2", "F5"):
        ok &= ~T.quiet.values.astype(bool)
    if idea == "F3":
        ok &= np.where(T.side.values > 0, T.pdir60.values > 0.5, T.pdir60.values < 0.5)
    return T[ok]


# ------------------------------------------------------------------------------------------------ statistics
def twin_p(C: pd.DataFrame, plain: pd.DataFrame, T: pd.DataFrame, idea: str, rng) -> tuple[float, float]:
    """Random vetoes (same count per fold) or, for F4, a random signalling index on days with two or more."""
    tot = float(T.net.sum()) if len(T) else 0.0
    sums = np.zeros(TWINS)
    if idea == "F4":
        fixed = 0.0
        for d, g in C.groupby("day"):
            if len(g) == 1:
                fixed += float(g.net.iloc[0])
            else:
                sums += rng.choice(g.net.values, TWINS)
        sums += fixed
    else:
        for f, g in plain.groupby("fold"):
            n_keep = int((T.fold == f).sum()) if len(T) else 0
            v = g.net.values
            for t in range(TWINS):
                sums[t] += v[rng.permutation(len(v))[:n_keep]].sum()
    return float((1 + (sums >= tot - 1e-9).sum()) / (1 + TWINS)), float(sums.mean())


def paired(plain: pd.DataFrame, T: pd.DataFrame, days, rng) -> dict:
    """Idea minus plain per day over the universe's days, with a day bootstrap 90% interval."""
    a = plain.groupby("day").net.sum().reindex(days, fill_value=0.0).values
    b = (T.groupby("day").net.sum() if len(T) else pd.Series(dtype=float)).reindex(days, fill_value=0.0).values
    diff = b - a
    if not len(diff):
        return dict(total=0.0, days=0, changed_days=0, ci90=[0.0, 0.0])
    bs = np.array([diff[rng.integers(0, len(diff), len(diff))].sum() for _ in range(BOOT)])
    return dict(total=float(diff.sum()), days=int(len(diff)), changed_days=int((np.abs(diff) > 1e-9).sum()),
                ci90=[float(np.quantile(bs, 0.05)), float(np.quantile(bs, 0.95))])


def summary(T: pd.DataFrame) -> dict:
    if T.empty:
        return dict(n=0)
    T = T.sort_values("day")
    net = T.net.values
    w = net > 0
    eq = np.cumsum(net)
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    yr = T.assign(y=[d.year for d in T.day]).groupby("y").net.agg(["count", "sum"])
    tot = float(net.sum())
    top5 = np.sort(net)[::-1][:5].sum()
    return dict(n=int(len(T)), win=float(w.mean()), avg_net=float(net.mean()), avg_net_x2=float(T.netx2.mean()),
                avg_win=float(net[w].mean()) if w.any() else 0.0, avg_loss=float(net[~w].mean()) if (~w).any() else 0.0,
                total=tot, max_dd=float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0)),
                worst_month=float(mo.min()), calls=float((T.side > 0).mean()),
                by_und={u: [int((T.und == u).sum()), float(T.net[T.und == u].sum())] for u in UNDS},
                by_year={int(y): [int(r["count"]), float(r["sum"])] for y, r in yr.iterrows()},
                exits={k: int(v) for k, v in T.exit_why.value_counts().items()},
                top5_share=float(top5 / tot) if tot > 0 else None)


def fold_totals(T: pd.DataFrame) -> dict:
    return {f: float(T.net[T.fold == f].sum()) if len(T) else 0.0 for f in FOLDS}


# ------------------------------------------------------------------------------------------------ the runs
def load_preds(part: str) -> pd.DataFrame:
    name = "wf_rows_preds_N1.parquet" if part == "wf" else "holdout_rows_preds_N1.parquet"
    F = pd.read_parquet(R123_OUT / name)
    F["day"] = pd.to_datetime(F.day).dt.date
    return F


def universe(part: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """The network's row-164 rows of `part` (quiet flags from earlier sessions only) and Solo per (und, day) on them."""
    from multiprocessing import Pool
    W = network_rows(load_preds("wf"))
    if part == "wf":
        N, last = W, K.DESIGN[1]
    else:
        H = network_rows(load_preds("holdout"))
        N, last = pd.concat([W, H], ignore_index=True), K.HOLDOUT[1]
    N["quiet"] = quiet_flags(N)
    if part == "holdout":
        N = N[[K.HOLDOUT[0] <= d <= K.HOLDOUT[1] for d in N.day]].reset_index(drop=True)
    jobs = [(u, set(N.day[N.und == u]), last) for u in UNDS]
    with Pool(len(UNDS)) as pool:
        S = pd.concat(pool.map(build_index, jobs), ignore_index=True)
    S = S.merge(N, on=["und", "day"], how="left")
    S["fold"] = [K.fold_of(d) or "holdout" for d in S.day]
    return N, S


def evaluate(S: pd.DataFrame, rng) -> tuple[dict, dict]:
    C = candidates(S)
    days = sorted(S.day.unique())
    plain = picks(C, "plain")
    res = {"plain": dict(label=LABELS["plain"], summary=summary(plain), folds=fold_totals(plain),
                         folds_positive=int(sum(v > 0 for v in fold_totals(plain).values())))}
    res["plain"]["universe"] = dict(index_days=int(len(S)), days=int(len(days)), signals=int(S.signal.sum()),
                                    signal_on_expiry=int((S.signal & S.expiry).sum()),
                                    untradable=int((S.signal & ~S.expiry & ~S.tradable).sum()),
                                    days_two_plus=int((C.groupby("day").size() >= 2).sum()) if len(C) else 0)
    trades = {"plain": plain}
    for idea in IDEAS:
        T = picks(C, idea)
        trades[idea] = T
        p, tm = twin_p(C, plain, T, idea, rng)
        ft = fold_totals(T)
        vet = plain[~plain.day.isin(set(T.day))] if idea != "F4" else plain.iloc[0:0]
        res[idea] = dict(label=LABELS[idea], summary=summary(T), folds=ft, folds_positive=int(sum(v > 0 for v in ft.values())),
                         p_twins=p, twin_mean_total=tm, paired=paired(plain, T, days, rng),
                         vetoed=dict(n=int(len(vet)), avg_net=float(vet.net.mean()) if len(vet) else None))
    return res, trades


def gates_wf(r: dict, q: float) -> dict:
    s = r["summary"]
    return dict(n=s.get("n", 0) >= K.G_WF_N, net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                folds=r["folds_positive"] >= K.G_WF_FOLDS, q=q <= K.G_Q)


def gates_ho(r: dict) -> dict:
    s = r["summary"]
    return dict(n=s.get("n", 0) >= K.G_HO_N, net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                account=s.get("max_dd", -1e9) > K.ACCOUNT_DD and s.get("worst_month", -1e9) > K.ACCOUNT_MONTH)


def flag(status: str) -> None:
    p = RESULTS / "HOLDOUT_READ.json"
    old = json.loads(p.read_text()) if p.exists() else {}
    if status == "start" and (old.get("status") == "done" or (RESULTS / "results.json").exists()):
        raise RuntimeError("refused: R132's holdout was already read")
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    rec = dict(status="started", started_at=now, resumed=bool(old)) if status == "start" else dict(old, status="done", done_at=now)
    p.write_text(json.dumps(rec))


def run(part: str) -> None:
    rng = np.random.default_rng(SEED)
    RESULTS.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    if part == "wf":
        _, S = universe("wf")
        S = S[S.fold.isin(list(FOLDS))]
        res, trades = evaluate(S, rng)
        q = R3.bh([res[i]["p_twins"] for i in IDEAS])
        for i, qq in zip(IDEAS, q):
            res[i]["q"] = float(qq)
            res[i]["gates_wf"] = gates_wf(res[i], qq)
            res[i]["passes_wf"] = bool(all(res[i]["gates_wf"].values()))
        res["plain"]["gates_wf"] = gates_wf(res["plain"], 0.0)
        res["plain"]["gates_wf"].pop("q")
        (RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))
        pd.concat([t.assign(idea=k) for k, t in trades.items()], ignore_index=True).to_parquet(OUT / "trades_wf.parquet")
        S.to_parquet(OUT / "solo_wf.parquet")
        _print(res)
        return
    wf = json.loads((RESULTS / "wf.json").read_text())
    flag("start")
    _, S = universe("holdout")
    res, trades = evaluate(S, rng)
    out = dict(wf=wf, holdout=res)
    for i in IDEAS:
        res[i]["gates_ho"] = gates_ho(res[i])
        wf[i]["passes"] = bool(wf[i]["passes_wf"] and all(res[i]["gates_ho"].values()))
        wf[i]["improves_solo"] = bool(wf[i]["paired"]["total"] > 0 and res[i]["paired"]["total"] > 0)
    res["plain"]["gates_ho"] = gates_ho(res["plain"])
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    pd.concat([t.assign(idea=k) for k, t in trades.items()], ignore_index=True).to_parquet(OUT / "trades_holdout.parquet")
    S.to_parquet(OUT / "solo_holdout.parquet")
    flag("done")
    _print(res)


def _print(res: dict) -> None:
    for k, r in res.items():
        s = r["summary"]
        print(k, "n", s.get("n"), "win", round(s.get("win", 0), 3), "avg", round(s.get("avg_net", 0)), "x2",
              round(s.get("avg_net_x2", 0)), "folds", r.get("folds_positive"), "p", r.get("p_twins"), "q", r.get("q"),
              "paired", (r.get("paired") or {}).get("total"), flush=True)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "wf")
