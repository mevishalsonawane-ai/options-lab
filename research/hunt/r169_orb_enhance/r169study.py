"""R169 runner (PREREG.md): parity, B0, the 20 single changes and 3 combinations per arm x lots, walk-forward and the one
final read, twins, BH, gates. Data in: R3's session store (rebuilt to 19 Oct 2026) and R123's N1 prediction rows.

    python r169study.py parity          # PREREG section 1 (must hold before anything else)
    python r169study.py design          # B0 + every change on the WF window -> results/design.json
    python r169study.py final           # the ONE final read (writes results/FINAL_READ first) -> results/final.json

13 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone
from multiprocessing import Pool

import numpy as np
import pandas as pd

import r169cfg as K
import r169lib as X
import r3data as D
import r3engine as E

_S: list = []
_CTX: dict = {}
_NET: dict = {}


# ================================================================================================ data
def sessions() -> list:
    global _S
    if not _S:
        _S = D.load(K.UND)
    return _S


def contexts(ss: list) -> dict:
    """Per-session extras: the contract's expiry date (the next expiry day on or after the day), today's 5-minute ATR and
    its 20-session median at each bar (F3)."""
    if _CTX:
        return _CTX
    exp_days = [s.day for s in ss if s.expiry]
    atrs, prev_b5 = [], None
    for i, s in enumerate(ss):
        b5 = X.bars5(s.ix)
        a = X.atr5(prev_b5, b5)
        prev_b5 = b5
        atrs.append(a)
        hist = atrs[max(0, i - K.BUSY_N):i]
        med = np.array([X.busy_median(hist, b, K.BUSY_NEED) for b in range(len(b5))])
        nxt = next((d for d in exp_days if d >= s.day), None)
        _CTX[s.day] = dict(expiry_date=nxt, atr5=a, busy_med=med)
    return _CTX


def network(final: bool) -> dict:
    """{(day, row): (ev_c, ev_p)} for BANKNIFTY from R123's walk-forward rows (and, for the final read only, its frozen
    holdout rows)."""
    key = "final" if final else "wf"
    if key in _NET:
        return _NET[key]
    files = ["wf_rows_preds_N1.parquet"] + (["holdout_rows_preds_N1.parquet"] if final else [])
    out = {}
    for f in files:
        cols = ["und", "day", "row"] + [f"ev_{i}" for i in range(16)]
        P = pd.read_parquet(K.PREDS / f, columns=cols)
        P = P[P.und == K.UND]
        ev_c = P[[f"ev_{2 * c}" for c in range(8)]].mean(axis=1).values
        ev_p = P[[f"ev_{2 * c + 1}" for c in range(8)]].mean(axis=1).values
        days = pd.to_datetime(P.day).dt.date.values
        for d, r, c, p in zip(days, P.row.astype(int).values, ev_c, ev_p):
            if not final and d >= K.FINAL[0]:
                continue
            out[(d, int(r))] = (float(c), float(p))
    _NET[key] = out
    return out


FIRST_VIEW, VIEW_STEP, LAST_VIEW, MAX_AGE = 4, 5, 339, 5


def view_row(r0: int):
    """r132nn.view_row: the latest view row t <= r0 - 1 (acting from t + 1), at most MAX_AGE minutes old."""
    if r0 - 1 < FIRST_VIEW:
        return None
    t = min(FIRST_VIEW + VIEW_STEP * ((r0 - 1 - FIRST_VIEW) // VIEW_STEP), LAST_VIEW)
    return t if r0 - (t + 1) <= MAX_AGE else None


def net_verdict(net: dict, day, side: int, row: int):
    t = view_row(row + 1)
    if t is None or (day, t) not in net:
        return None
    c, p = net[(day, t)]
    ev = c if side > 0 else p
    return bool(ev > 0) if np.isfinite(ev) else None


# ================================================================================================ the picker (F1)
class Picker:
    def __init__(self, trades: list):
        from sklearn.linear_model import LogisticRegression
        rows = [[t["feats"].get(f, np.nan) for f in X.PICK_FEATS] for t in trades]
        y = np.array([t["net"] > 0 for t in trades], int)
        self.ok = len(trades) >= K.PICK_MIN and 0 < y.sum() < len(y)
        if not self.ok:
            return
        A = np.array(rows, float)
        self.med = np.nanmedian(A, axis=0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        A = np.where(np.isfinite(A), A, self.med)
        self.mu, self.sd = A.mean(0), A.std(0)
        self.sd[self.sd == 0] = 1.0
        self.m = LogisticRegression(C=1.0, max_iter=1000).fit((A - self.mu) / self.sd, y)
        self.base = float(y.mean())

    def keep(self, f: dict):
        if not self.ok:
            return None
        a = np.array([f.get(k, np.nan) for k in X.PICK_FEATS], float)
        a = np.where(np.isfinite(a), a, self.med)
        p = float(self.m.predict_proba(((a - self.mu) / self.sd)[None, :])[0, 1])
        return p >= self.base


def keep_fn(picker, net):
    def fn(s, side, row, f):
        return dict(pick=picker.keep(f) if picker is not None else None,
                    net=net_verdict(net, s.day, side, row) if net is not None else None)
    return fn


# ================================================================================================ runs
def run(cfg: X.Cfg, ss: list, picker=None, net=None) -> list:
    ctx = contexts(sessions())
    kf = keep_fn(picker, net) if ("F1" in cfg.changes or "F2" in cfg.changes) else None
    out = []
    for s in ss:
        out += X.run_session(s, cfg, ctx[s.day], kf)
    return out


def window(w) -> list:
    return [s for s in sessions() if K.in_(s.day, w)]


def b0_history(arm: str, upto: date) -> list:
    """The arm's B0 trades (1 lot) with features, from Aug 2021 to the day before `upto`: F1's training set."""
    ss = [s for s in sessions() if K.HISTORY[0] <= s.day < upto]
    return X.taken(run(X.Cfg(arm), ss))


CONFLICT = ({"S1", "S2", "S3"}, {"X2", "X3", "L1"})


def compatible(chosen: list, c: str) -> bool:
    return not any(c in g and any(x in g for x in chosen) for g in CONFLICT)


def choose_combos(gains: dict) -> dict:
    """PREREG 3d: gains {change: (net gain over B0, dd_ok)} on a training window -> {'C1': (...), 'C2': ..., 'C3': ...}."""
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


def rank_window(arm: str, lots: int, w: tuple, picker, net) -> dict:
    ss = window(w)
    days = [s.day for s in ss]
    b = X.score(X.taken(run(X.Cfg(arm, lots), ss)), days)
    gains = {}
    for c in X.SINGLES:
        sc = X.score(X.taken(run(X.Cfg(arm, lots, (c,)), ss, picker, net)), days)
        gains[c] = (sc["net"] - b["net"], sc["max_dd"] >= b["max_dd"] - 1e-9)
    return gains


def plan(final: bool) -> list:
    """The (period, window, picker cut-off) list: the 4 WF quarters, or the final."""
    if final:
        return [("final", K.FINAL, K.FINAL[0], (K.DESIGN[0], K.DESIGN[1]))]
    out = []
    for i, q in enumerate(K.WF_Q):
        tr = (K.DESIGN[0], date(2025, 3, 31)) if i == 0 else (K.DESIGN[0], date.fromordinal(q[0].toordinal() - 1))
        out.append((f"Q{i + 1}", q, q[0], tr))
    return out


def job(args):
    """One arm x lots: B0 and every change over the WF quarters (or the final). Returns the trade lists per change."""
    arm, lots, final = args
    net = network(final)
    hist_picker = Picker(b0_history(arm, K.DESIGN[0]))
    res = {"B0": [], **{c: [] for c in X.SINGLES}, "C1": [], "C2": [], "C3": []}
    combos = {}
    for name, w, cut, tr in plan(final):
        picker = Picker(b0_history(arm, cut))
        ss = window(w)
        res["B0"] += run(X.Cfg(arm, lots), ss)
        for c in X.SINGLES:
            res[c] += run(X.Cfg(arm, lots, (c,)), ss, picker, net)
        gains = rank_window(arm, lots, tr, hist_picker, net)
        cc = choose_combos(gains)
        combos[name] = dict(chosen={k: list(v) for k, v in cc.items()}, gains={k: [round(g), bool(d)] for k, (g, d) in gains.items()})
        for k, v in cc.items():
            res[k] += run(X.Cfg(arm, lots, v), ss, picker, net)
    return arm, lots, res, combos


def strip(t: dict) -> dict:
    return {k: (v.isoformat() if isinstance(v, date) else v) for k, v in t.items() if k != "feats"}


# ================================================================================================ twins (final)
def exit_twin_table(trades: list, ss_by_day: dict, cfg: X.Cfg) -> list:
    """For each B0 trade: the nets of selling at the next open after each minute between its fill and its exit."""
    out = []
    for t in trades:
        s = ss_by_day[t["day"]]
        a = s.opts[(t["k"], "C" if t["side"] > 0 else "P")].astype(np.float64)
        nets = []
        for r in range(t["entry_row"], max(t["entry_row"] + 1, t["exit_row"])):
            rr, p = E.next_open(a, r + 1)
            if rr < 0:
                continue
            x = float(E.FILLS.sell(np.array([p]), np.array([X.thin_lots(a, rr, s.lot, cfg.lots)]))[0])
            ch = X.charge(cfg, True, t["entry"], t["qty"], s.und, s.day) + X.charge(cfg, False, x, t["qty"], s.und, s.day)
            nets.append((x - t["entry"]) * t["qty"] - ch)
        out.append(np.array(nets) if nets else np.array([t["net"]]))
    return out


def entry_twin_table(trades: list, ss_by_day: dict, cfg: X.Cfg) -> dict:
    """R3's twin: for each (session, side) of the variant's trades, the trade at every bar of the arm's window (same
    strike rule and exits, no filters)."""
    first, last = cfg.base.win
    ctx = contexts(sessions())
    plain = X.with_changes(cfg, [c for c in cfg.changes if c not in X.FILTERS and c != "W1"])
    out = {}
    for t in trades:
        key = (t["day"], t["side"])
        if key in out:
            continue
        s = ss_by_day[t["day"]]
        b5 = X.bars5(s.ix)
        hi, lo = E.opening_range(b5)
        vals = []
        for b in range(first, last + 1):
            row = 5 * b + 4
            k = X.choose_strike(s, plain, t["side"], row, ctx[s.day]["expiry_date"])
            tw = X.simulate(s, plain, t["side"], row, k, K.K3.PTS[s.und], hi, lo)
            if tw is not None:
                vals.append(tw["net"])
        out[key] = np.array(vals)
    return out


def twins(kind: str, var: list, b0: list, ss_by_day: dict, cfg: X.Cfg, metric: str, rng) -> np.ndarray:
    n = K.TWINS
    res = np.zeros(n)
    if kind == "skip":
        nets = np.array([t["net"] for t in b0])
        k = len(var)
        for i in range(n):
            pick = nets[rng.choice(len(nets), size=k, replace=False)] if k <= len(nets) else nets
            res[i] = pick.sum() if metric == "net" else (pick > 0).mean() if len(pick) else 0.0
        return res
    if kind == "exit":
        keyset = {(t["day"], t["side"], t["entry_row"], t["exit_row"]) for t in b0}
        changed = sum((t["day"], t["side"], t["entry_row"], t["exit_row"]) not in keyset for t in var)
        tab = exit_twin_table(b0, ss_by_day, cfg)
        base = np.array([t["net"] for t in b0])
        for i in range(n):
            nets = base.copy()
            idx = rng.choice(len(b0), size=min(changed, len(b0)), replace=False)
            for j in idx:
                nets[j] = tab[j][rng.integers(len(tab[j]))]
            res[i] = nets.sum() if metric == "net" else (nets > 0).mean()
        return res
    tab = entry_twin_table(var, ss_by_day, cfg)
    for i in range(n):
        nets = []
        for t in var:
            v = tab[(t["day"], t["side"])]
            if len(v):
                nets.append(v[rng.integers(len(v))])
        nets = np.array(nets)
        res[i] = nets.sum() if metric == "net" else ((nets > 0).mean() if len(nets) else 0.0)
    return res


def kind_of(changes: tuple) -> str:
    if all(c in X.FILTERS for c in changes):
        return "skip"
    if all(c in X.EXITS for c in changes):
        return "exit"
    return "entry"


# ================================================================================================ commands
def parity() -> dict:
    out = {}
    for arm in K.ARMS:
        t = X.taken(run(X.Cfg(arm, costs="app"), window(K.DESIGN)))
        got = (len(t), round(sum(x["net"] for x in t)))
        out[arm] = dict(got=got, want=K.PARITY[arm], ok=got == K.PARITY[arm])
    print(json.dumps(out))
    if not all(v["ok"] for v in out.values()):
        raise SystemExit("parity failed: R169 stops (PREREG section 1)")
    return out


def collect(final: bool) -> dict:
    jobs = [(a, l, final) for a in K.ARMS for l in K.LOTS]
    sessions()
    contexts(sessions())
    network(final)
    with Pool(4) as p:
        got = p.map(job, jobs)
    return {(a, l): (res, combos) for a, l, res, combos in got}


def summarise(final: bool):
    data = collect(final)
    per = "final" if final else "wf"
    w = K.FINAL if final else K.WF
    days = [s.day for s in window(w)]
    ss_by_day = {s.day: s for s in sessions()}
    out = {"_meta": dict(period=per, window=[w[0].isoformat(), w[1].isoformat()], sessions=len(days),
                         written=datetime.now(timezone.utc).isoformat(timespec="seconds"))}
    rng = np.random.default_rng(K.SEED)
    trades_out = {}
    for (arm, lots), (res, combos) in sorted(data.items()):
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
                if c in ("C1", "C2", "C3") and not changes:
                    row["twin_p"] = 1.0
                else:
                    tw = twins(kind_of(changes), tk, b0, ss_by_day, X.Cfg(arm, lots, changes), metric, rng)
                    real = sc["net"] if metric == "net" else sc["win"]
                    row["twin_p"] = X.twin_p(real, tw)
                    row["twin_metric"] = metric
                    row["twin_kind"] = kind_of(changes)
            rows[c] = row
        out[f"{arm}|{lots}"] = dict(rows=rows, combos=combos)
        trades_out[f"{arm}|{lots}"] = {c: [strip(t) for t in tr] for c, tr in res.items()}
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"{per}.json").write_text(json.dumps(out, indent=1, default=float))
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"trades_{per}.pkl").write_bytes(pickle.dumps(trades_out))
    print("wrote", per)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "parity":
        (K.RESULTS / "parity.json").write_text(json.dumps(parity(), indent=1))
    elif cmd == "design":
        parity()
        summarise(False)
    elif cmd == "final":
        if K.FINAL_FLAG.exists():
            raise SystemExit("the final period was already read once (results/FINAL_READ)")
        K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
        summarise(True)
    else:
        raise SystemExit(__doc__)
