"""R170 runner (PREREG.md + Amendments A, B): the other Donchian-study rules on ORB / ORB Fresh / ORB Sweep, walk-forward
and one more look at the final, twins, BH and gates. Reuses R169's session store, contexts and twin tables.

    python r170study.py design      # -> results/wf.json
    python r170study.py final       # writes results/FINAL_READ first -> results/final.json

13 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timedelta, timezone
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r170lib as X  # noqa: E402  (puts R169 / R3 on the path)
import r169cfg as K9  # noqa: E402
import r169lib as X9  # noqa: E402
import r169study as S9  # noqa: E402
import r3engine as E  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OUT = K9.OUT / "r170"
M_PRIOR = 3265
N_TESTS = 576
OTHERS = ("NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
CONFLICT = ({"ITM2", "D70"}, {"T45", "NIP30"}, {"Z1", "Z2", "Z3"}, {"G1R", "G2R", "LCK"}, {"Q2K", "QT2K", "QS2K"},
            {"B15", "B20"}, {"LF1", "LF2"}, {"FK3", "FK5"}, {"D1", "D2"}, {"IXA", "IXR"})
_CTX: dict = {}


# ================================================================================================ contexts
def other_b5() -> dict:
    """{und: {day: (75, 4) 5-minute bars}} for the other four indices (E11 breadth)."""
    out = {}
    for und in OTHERS:
        parts = []
        for p in sorted(K9.K3.LAKE.glob(f"index_1m_{und}_*.parquet")):
            df = pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"])
            ts = pd.to_datetime(df.ts)
            if ts.dt.tz is not None:
                ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
            df["d"] = ts.dt.date
            df["m"] = (ts.dt.hour * 60 + ts.dt.minute - 555).astype(int)
            parts.append(df[(df.m >= 0) & (df.m < 375)])
        if not parts:
            continue
        df = pd.concat(parts)
        days = {}
        for d, g in df.groupby("d"):
            a = np.full((375, 4), np.nan)
            a[g.m.values] = g[["open", "high", "low", "close"]].values
            days[d] = X9.bars5(a)
        out[und] = days
    return out


def contexts() -> dict:
    if _CTX:
        return _CTX
    ss = S9.sessions()
    base = S9.contexts(ss)
    b5s = [X9.bars5(s.ix) for s in ss]
    widths = []
    for b5 in b5s:
        hi, lo = E.opening_range(b5)
        widths.append(hi - lo if np.isfinite(hi) and np.isfinite(lo) else np.nan)
    sw5 = [X.swings(b5, 3) for b5 in b5s]
    sw15 = [X.swings(X.group_bars(b5, 3), 3) for b5 in b5s]
    curves = [X.band_cumvol(s) for s in ss]
    oth = other_b5()
    days = [s.day for s in ss]
    for i, s in enumerate(ss):
        c = dict(base[s.day])
        lo6 = max(0, i - 5)
        dlist = b5s[lo6:i + 1]
        c["htf15"] = X.htf_side(dlist, 3)
        c["htf60"] = X.htf_side(dlist, 12)
        allb, off = X.cont_series(dlist)
        c["ema20"] = X.ema(allb[:, 3], 20)[off:]
        c["st5"] = X.supertrend_dir(allb)[off:]
        prev1 = ss[i - 1].ix if i else np.full((375, 4), np.nan)
        c["st1"] = X.supertrend_dir(np.vstack([prev1, s.ix]))[375:]
        w = np.array(widths[max(0, i - 100):i], float)
        w = w[np.isfinite(w)]
        c["width_med"] = float(np.median(w)) if len(w) >= 50 else np.nan
        lv = [x for j in range(max(0, i - 50), i) for x in sw5[j]] + [x for j in range(max(0, i - 60), i) for x in sw15[j]]
        wk = s.day - timedelta(days=s.day.weekday() + 7)
        pw = [j for j in range(max(0, i - 10), i) if wk <= days[j] < wk + timedelta(days=7)]
        if pw:
            lv += [float(np.nanmax([np.nanmax(ss[j].ix[:, 1]) for j in pw])), float(np.nanmin([np.nanmin(ss[j].ix[:, 2]) for j in pw]))]
        c["levels"] = lv
        cv = np.array(curves[max(0, i - 20):i], float)
        c["bandvol_med"] = np.median(cv, axis=0) if len(cv) >= 10 else None
        c["breadth"] = {u: oth.get(u, {}).get(s.day) for u in OTHERS}
        _CTX[s.day] = c
    return _CTX


# ================================================================================================ pickers (K1, K2)
class Picker2:
    def __init__(self, trades: list, kind: str):
        rows = [[t["pf"].get(f, np.nan) for f in X.PICK_FEATS] for t in trades]
        y = np.array([t["net"] > 0 for t in trades], int)
        self.ok = len(trades) >= K9.PICK_MIN and 0 < y.sum() < len(y)
        if not self.ok:
            return
        A = np.array(rows, float)
        self.med = np.nanmedian(A, axis=0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        A = np.where(np.isfinite(A), A, self.med)
        self.mu, self.sd = A.mean(0), A.std(0)
        self.sd[self.sd == 0] = 1.0
        Z = (A - self.mu) / self.sd
        if kind == "K1":
            from sklearn.ensemble import HistGradientBoostingClassifier
            self.m = HistGradientBoostingClassifier(random_state=0).fit(Z, y)
            keep = 0.55
        else:
            from sklearn.neural_network import MLPClassifier
            self.m = MLPClassifier(hidden_layer_sizes=(16,), alpha=1e-3, max_iter=2000, random_state=0).fit(Z, y)
            keep = 0.60
        self.thr = float(np.quantile(self.m.predict_proba(Z)[:, 1], 1 - keep))

    def keep(self, f: dict):
        if not self.ok:
            return None
        a = np.array([f.get(k, np.nan) for k in X.PICK_FEATS], float)
        a = np.where(np.isfinite(a), a, self.med)
        return float(self.m.predict_proba(((a - self.mu) / self.sd)[None, :])[0, 1]) >= self.thr


def b0_hist(arm: str, upto: date) -> list:
    """B0 trades (1 lot) from Aug 2021 to before `upto`, with the pickers' features ('pf')."""
    ctx = contexts()
    out = []
    for s in S9.sessions():
        if not (K9.HISTORY[0] <= s.day < upto):
            continue
        b5 = X9.bars5(s.ix)
        hi, lo = E.opening_range(b5)
        for t in X.taken(X.run_session2(s, X.Cfg(arm), ctx[s.day])):
            t["pf"] = X.pick_features(s, t["side"], t["sig_row"], hi, lo, b5, t["k"], ctx[s.day])
            out.append(t)
    return out


def run(cfg: X.Cfg, ss: list, pk: dict | None = None) -> list:
    ctx = contexts()
    need = [c for c in ("K1", "K2") if c in cfg.changes]
    kf = None
    if need and pk:
        def kf(s, side, row, f):
            return {"pick_" + c: (pk[c].keep(f) if pk.get(c) else None) for c in need}
    out = []
    for s in ss:
        out += X.run_session2(s, cfg, ctx[s.day], kf)
    return out


def compatible(chosen, c):
    return not any(c in g and any(x in g for x in chosen) for g in CONFLICT)


def choose_combos(gains: dict) -> dict:
    ok = sorted([c for c, (g, dd) in gains.items() if dd], key=lambda c: -gains[c][0])

    def pick(pool, n):
        out = []
        for c in pool:
            if compatible(out, c):
                out.append(c)
            if len(out) == n:
                break
        return tuple(out) if len(out) == n else ()
    f = [c for c in ok if c in X.FILTERS]
    x = [c for c in ok if c in X.EXITS]
    return {"C1": pick(ok, 2), "C2": pick(ok, 3), "C3": (f[0], x[0]) if f and x else ()}


def job(args):
    arm, lots, final = args
    hist = b0_hist(arm, K9.DESIGN[0])
    hist_pk = {c: Picker2(hist, c) for c in ("K1", "K2")}
    res = {"B0": [], **{c: [] for c in X.SINGLES}, "C1": [], "C2": [], "C3": []}
    combos = {}
    for name, w, cut, tr in S9.plan(final):
        tr_hist = b0_hist(arm, cut)
        pk = {c: Picker2(tr_hist, c) for c in ("K1", "K2")}
        ss = S9.window(w)
        res["B0"] += run(X.Cfg(arm, lots), ss)
        for c in X.SINGLES:
            res[c] += run(X.Cfg(arm, lots, (c,)), ss, pk)
        tss = S9.window(tr)
        tdays = [s.day for s in tss]
        b = X.score(X.taken(run(X.Cfg(arm, lots), tss)), tdays)
        gains = {}
        for c in X.SINGLES:
            sc = X.score(X.taken(run(X.Cfg(arm, lots, (c,)), tss, hist_pk)), tdays)
            gains[c] = (sc["net"] - b["net"], sc["max_dd"] >= b["max_dd"] - 1e-9)
        cc = choose_combos(gains)
        combos[name] = dict(chosen={k: list(v) for k, v in cc.items()}, gains={k: [round(g), bool(d)] for k, (g, d) in gains.items()})
        for k, v in cc.items():
            res[k] += run(X.Cfg(arm, lots, v), ss, pk)
    return arm, lots, res, combos


# ================================================================================================ twins
def kind_of(changes: tuple) -> str:
    if all(c in X.FILTERS or c == "DCAP" for c in changes):
        return "skip"
    if all(c in X.EXITS for c in changes):
        return "exit"
    return "entry"


def entry_table(trades: list, ss_by_day: dict, cfg: X.Cfg) -> dict:
    first, last = cfg.base.win
    ctx = contexts()
    plain = X.Cfg(cfg.arm, cfg.lots, tuple(c for c in cfg.changes if c not in X.FILTERS and c != "DCAP"))
    out = {}
    for t in trades:
        key = (t["day"], t["side"])
        if key in out:
            continue
        s = ss_by_day[t["day"]]
        b5 = X9.bars5(s.ix)
        vals = []
        for b in range(first, last + 1):
            row = 5 * b + 4
            k = X.choose_strike2(s, plain, t["side"], row, ctx[s.day]["expiry_date"])
            tw = X.simulate2(s, plain, t["side"], row, k, K9.K3.PTS[s.und], b5, ctx[s.day])
            if tw is not None:
                vals.append(tw["net"])
        out[key] = np.array(vals)
    return out


def twins(kind, var, b0, ss_by_day, cfg, metric, rng):
    n = K9.TWINS
    res = np.zeros(n)
    if kind == "skip":
        nets = np.array([t["net"] for t in b0])
        k = min(len(var), len(nets))
        for i in range(n):
            pick = nets[rng.choice(len(nets), size=k, replace=False)]
            res[i] = pick.sum() if metric == "net" else ((pick > 0).mean() if len(pick) else 0.0)
        return res
    if kind == "exit":
        keys = {(t["day"], t["side"], t["entry_row"], t["exit_row"]) for t in b0}
        changed = sum((t["day"], t["side"], t["entry_row"], t["exit_row"]) not in keys for t in var)
        tab = S9.exit_twin_table(b0, ss_by_day, cfg)
        base = np.array([t["net"] for t in b0])
        for i in range(n):
            nets = base.copy()
            for j in rng.choice(len(b0), size=min(changed, len(b0)), replace=False):
                nets[j] = tab[j][rng.integers(len(tab[j]))]
            res[i] = nets.sum() if metric == "net" else (nets > 0).mean()
        return res
    tab = entry_table(var, ss_by_day, cfg)
    for i in range(n):
        nets = np.array([tab[(t["day"], t["side"])][rng.integers(len(tab[(t["day"], t["side"])]))]
                         for t in var if len(tab[(t["day"], t["side"])])])
        res[i] = nets.sum() if metric == "net" else ((nets > 0).mean() if len(nets) else 0.0)
    return res


# ================================================================================================ commands
def summarise(final: bool):
    S9.sessions()
    contexts()
    jobs = [(a, l, final) for a in K9.ARMS for l in K9.LOTS]
    with Pool(4) as p:
        got = p.map(job, jobs)
    per = "final" if final else "wf"
    w = K9.FINAL if final else K9.WF
    days = [s.day for s in S9.window(w)]
    ss_by_day = {s.day: s for s in S9.sessions()}
    rng = np.random.default_rng(170)
    out = {"_meta": dict(period=per, window=[w[0].isoformat(), w[1].isoformat()], sessions=len(days),
                         written=datetime.now(timezone.utc).isoformat(timespec="seconds"))}
    trades_out = {}
    for arm, lots, res, combos in sorted(got, key=lambda x: (x[0], x[1])):
        b0 = X.taken(res["B0"])
        bs = X.score(b0, days)
        rows = {"B0": dict(score=bs)}
        for c, tr in res.items():
            if c == "B0":
                continue
            tk = X.taken(tr)
            sc = X.score(tk, days)
            row = dict(score=sc, skipped=sum(1 for t in tr if t.get("skipped")))
            if final:
                ok, branch = X.gate1(sc, bs)
                metric = branch or "net"
                changes = (c,) if c in X.SINGLES else tuple(combos["final"]["chosen"][c])
                if not changes:
                    row["twin_p"] = 1.0
                else:
                    kd = kind_of(changes)
                    tw = twins(kd, tk, b0, ss_by_day, X.Cfg(arm, lots, changes), metric, rng)
                    row.update(twin_p=X.twin_p(sc["net"] if metric == "net" else sc["win"], tw), twin_metric=metric, twin_kind=kd)
            rows[c] = row
        out[f"{arm}|{lots}"] = dict(rows=rows, combos=combos)
        trades_out[f"{arm}|{lots}"] = {c: [S9.strip(t) for t in tr] for c, tr in res.items()}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{per}.json").write_text(json.dumps(out, indent=1, default=float))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"trades_{per}.pkl").write_bytes(pickle.dumps(trades_out))
    print("wrote", per)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        S9.parity()
        summarise(False)
    elif cmd == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("R170's final look was already taken (results/FINAL_READ)")
        RESULTS.mkdir(parents=True, exist_ok=True)
        FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
        summarise(True)
    else:
        raise SystemExit(__doc__)
