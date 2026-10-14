"""F1 rules (PREREG 5-7 and amendment 1): at most 20 rules from the screen, walk-forward on design by year, random
twins, gates; then ONE read of the locked holdout (1 Jan 2023 - 30 Jun 2024) for all of them together.

    python research/hunt/f1/rules.py design     # -> results/rules.json (the frozen list) + results/design.json
    python research/hunt/f1/rules.py holdout    # ONCE: builds the holdout tables, scores the frozen list -> holdout.json
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402
import data as D  # noqa: E402
import screen as S  # noqa: E402

DIR_EXIT = {"ret15": "x1", "ret30": "x1", "ret60": "x2"}


# ------------------------------------------------------------------------------------------------ the rule list
def classic_rules() -> list[dict]:
    return [dict(id="C1", name="Trend stack: 5-min EMA9>EMA21, 5-min Supertrend up, index above option-volume VWAP "
                 "(mirror for puts)", kind="classic", scope=list(K.UNDS), exit="x1", sides=["ce", "pe"]),
            dict(id="C2", name="OI + VWAP: put-writing bias > 0 and index above option-volume VWAP (mirror for puts)",
                 kind="classic", scope=list(K.UNDS), exit="x1", sides=["ce", "pe"])]


def screen_rules(R: pd.DataFrame, n_max: int) -> list[dict]:
    sv = R[R.survivor & R.target.isin(S.DIR_TARGETS + S.ARM_TARGETS)].sort_values("t_sel", ascending=False)
    out, per_name = [], {}
    for _, r in sv.iterrows():
        names = [r.a, r.b] if r.kind == "pair" else [r.feature]
        if any(per_name.get(n, 0) >= 2 for n in names):
            continue
        arm = r.target.startswith("net_")
        lv = None if pd.isna(r.level) else float(r.level)
        if lv is not None and arm and r.sign < 0:
            continue                                    # "trade when the level is absent" is not a rule (amendment 1)
        if arm:
            side, _, ex = r.target[4:].split("_")
            sides, exit_ = [side], ex
        else:
            sides, exit_ = (["ce", "pe"] if lv is None else (["ce"] if r.sign > 0 else ["pe"])), DIR_EXIT[r.target]
        rule = dict(id=f"S{len(out) + 1}", kind="pair" if r.kind == "pair" else ("level" if lv is not None else "single"),
                    feature=r.feature, level=lv, sign=float(r.sign), target=r.target, exit=exit_, sides=sides,
                    scope=list(K.UNDS) if r.scope == "POOLED" else [r.scope], screen_scope=r.scope,
                    ic_sel=float(r.ic_sel), t_sel=float(r.t_sel), q_screen=float(r.q))
        if r.kind == "pair":
            rule.update(a=r.a, la=None if pd.isna(r.la) else float(r.la), sa=float(r.sa),
                        b=r.b, lb=None if pd.isna(r.lb) else float(r.lb), sb=float(r.sb))
        rule["name"] = describe(rule)
        out.append(rule)
        for n in names:
            per_name[n] = per_name.get(n, 0) + 1
        if len(out) == n_max:
            break
    return out


def describe(r: dict) -> str:
    tgt = {"ret15": "next 15 min", "ret30": "next 30 min", "ret60": "next 60 min"}.get(r["target"], r["target"])
    if r["kind"] == "level":
        return f"{r['feature']} == {r['level']:g} -> buy {'/'.join(r['sides']).upper()} ({tgt}, {r['exit']})"
    hi = "high" if r["sign"] > 0 else "low"
    return f"{r['feature']} top quintile ({hi} side) -> {'/'.join(r['sides']).upper()} ({tgt}, {r['exit']})"


# ------------------------------------------------------------------------------------------------ signals
def _x(X, name, lv):
    return S.values(X, name, lv)


def _pct(ref: np.ndarray, x: np.ndarray) -> np.ndarray:
    r = np.sort(ref[np.isfinite(ref)])
    if not len(r):
        return np.full(len(x), np.nan)
    return np.where(np.isfinite(x), np.searchsorted(r, x, side="right") / len(r), np.nan)


def score(rule: dict, X: pd.DataFrame, ref: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """Per row: (score in the rule's direction, the same score on the reference rows)."""
    if rule["kind"] == "pair":
        pa, pb = (_pct(_x(ref, rule[k], rule[l]), _x(X, rule[k], rule[l])) for k, l in (("a", "la"), ("b", "lb")))
        ra, rb = (_pct(_x(ref, rule[k], rule[l]), _x(ref, rule[k], rule[l])) for k, l in (("a", "la"), ("b", "lb")))
        s = rule["sign"]
        return (s * (rule["sa"] * (pa - .5) + rule["sb"] * (pb - .5)) / 2,
                s * (rule["sa"] * (ra - .5) + rule["sb"] * (rb - .5)) / 2)
    return rule["sign"] * X[rule["feature"]].values.astype(float), rule["sign"] * ref[rule["feature"]].values.astype(float)


def signals(rule: dict, X: pd.DataFrame, ref: pd.DataFrame) -> np.ndarray:
    """+1 buy the call, -1 buy the put, 0 nothing, per row of X (one index)."""
    if rule["kind"] == "classic":
        e, st, vw = (X[c].values.astype(float) for c in ("c5_ema9_21_spread_pct", "c5_supertrend_dir", "ovwap_dist_pct"))
        if rule["id"] == "C1":
            up, dn = (e > 0) & (st == 1) & (vw > 0), (e < 0) & (st == -1) & (vw < 0)
        else:
            wb = X["bu_writing_bias_15m"].values.astype(float)
            up, dn = (wb > 0) & (vw > 0), (wb < 0) & (vw < 0)
        return np.where(up, 1, np.where(dn, -1, 0))
    if rule["kind"] == "level":
        hit = X[rule["feature"]].values.astype(float) == rule["level"]
        return np.where(hit, 1 if rule["sides"] == ["ce"] else -1, 0)
    sc, rs = score(rule, X, ref)
    rs = rs[np.isfinite(rs)]
    if len(rs) < 100:
        return np.zeros(len(X), int)
    hi, lo = np.quantile(rs, 1 - K.QUINT), np.quantile(rs, K.QUINT)
    arm = rule["target"].startswith("net_")
    if arm:                                        # an arm target: the top of the signed score trades that side only
        side = 1 if rule["sides"] == ["ce"] else -1
        return np.where(sc >= hi, side, 0)
    return np.where(sc >= hi, 1, np.where(sc <= lo, -1, 0))


def trades(rule: dict, X: pd.DataFrame, sig: np.ndarray) -> pd.DataFrame:
    """First FILLED signal a side a day (rows in time order)."""
    out = []
    X = X.assign(_sig=sig)
    for side, sv in (("ce", 1), ("pe", -1)):
        if side not in rule["sides"]:
            continue
        col = f"net_{side}_itm1_{rule['exit']}"
        cand = X[(X._sig == sv) & X[col].notna()].sort_values(["day", "row"]).drop_duplicates("day")
        out.append(pd.DataFrame(dict(day=cand.day.values, und=cand.und.values, row=cand.row.values, side=side,
                                     net=cand[col].values, cut=cand[f"cut_{side}_itm1_{rule['exit']}"].values,
                                     why=cand[f"why_{side}_itm1_{rule['exit']}"].values)))
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame(columns=["day", "und", "row", "side", "net"])


def twins(rule: dict, T: pd.DataFrame, X: pd.DataFrame, rng) -> np.ndarray:
    """Total net of N_TWINS random-entry twin sets: same day, index, side, strike and exit; entry minute uniform over
    the decision grid's filled rows that day."""
    tot = np.zeros(K.N_TWINS)
    if T.empty:
        return tot
    for (u, side), g in T.groupby(["und", "side"]):
        col = f"net_{side}_itm1_{rule['exit']}"
        Xu = X[(X.und == u) & X[col].notna()]
        pools = Xu.groupby("day")[col].apply(lambda s: s.values.astype(float))
        for d in g.day.values:
            p = pools[d]
            tot += p[rng.integers(len(p), size=K.N_TWINS)]
    return tot


# ------------------------------------------------------------------------------------------------ metrics
def metrics(T: pd.DataFrame, days: list, twin_tot: np.ndarray | None = None) -> dict:
    n = len(T)
    daily = (T.groupby("day").net.sum() if n else pd.Series(dtype=float)).reindex(days, fill_value=0.0)
    eq = daily.cumsum().values
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min()) if len(eq) else 0.0
    w, l = T.net[T.net > 0], T.net[T.net <= 0]
    traded = T.groupby("day").net.sum() if n else pd.Series(dtype=float)
    total = float(T.net.sum()) if n else 0.0
    out = dict(trades=n, days=len(days), trading_days=int(len(traded)), trades_per_day=n / max(len(days), 1),
               win_rate=float((T.net > 0).mean()) if n else np.nan, avg_win=float(w.mean()) if len(w) else np.nan,
               avg_loss=float(l.mean()) if len(l) else np.nan, net=total, pnl_per_day=total / max(len(days), 1),
               green_days_pct=float((traded > 0).mean() * 100) if len(traded) else np.nan,
               worst_day=float(daily.min()) if len(daily) else 0.0, max_dd=dd,
               cut_short=int(np.nansum(T.cut.values)) if n else 0, worst_trade=float(T.net.min()) if n else np.nan)
    if twin_tot is not None:
        out["twin_mean"] = float(twin_tot.mean())
        out["p_twin"] = float((1 + (twin_tot >= total).sum()) / (1 + len(twin_tot)))
    return out


def period_days(X: pd.DataFrame, rule: dict, lo: date | None = None, hi: date | None = None) -> list:
    d = X[X.und.isin(rule["scope"])].day
    if lo is not None:
        d = d[d.dt.date >= lo]
    if hi is not None:
        d = d[d.dt.date <= hi]
    return sorted(d.unique())


def evaluate(rule: dict, X: pd.DataFrame, ref_for, periods: dict, rng) -> dict:
    """periods: name -> (lo, hi). ref_for(und, lo) -> the reference rows for thresholds."""
    res = {}
    for pname, (lo, hi) in periods.items():
        parts = []
        for u in rule["scope"]:
            Xu = X[(X.und == u) & (X.day.dt.date >= lo) & (X.day.dt.date <= hi)]
            if Xu.empty:
                continue
            parts.append(trades(rule, Xu, signals(rule, Xu, ref_for(u, lo))))
        T = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=["day", "und", "side", "net", "cut"])
        Xp = X[X.und.isin(rule["scope"]) & (X.day.dt.date >= lo) & (X.day.dt.date <= hi)]
        tw = twins(rule, T, Xp, rng)
        res[pname] = metrics(T, period_days(X, rule, lo, hi), tw)
        res[pname]["_T"] = T
    return res


def load_all(part: str) -> pd.DataFrame:
    return pd.concat([S.frame(u, part) for u in K.UNDS], ignore_index=True)


def design(n_screen: int = K.MAX_RULES - 2) -> dict:
    R = pd.read_csv(K.RESULTS / "screen.csv")
    rules = classic_rules() + screen_rules(R, n_screen)
    X = load_all("design")
    rng = np.random.default_rng(K.SEED)

    def ref_for(u, lo):
        return X[(X.und == u) & (X.day.dt.date < lo)]
    out = []
    for r in rules:
        ev = evaluate(r, X, ref_for, K.FOLDS, rng)
        Ts = [ev[f].pop("_T") for f in K.FOLDS]
        T = pd.concat(Ts, ignore_index=True)
        days = sorted(set().union(*[set(period_days(X, r, *K.FOLDS[f])) for f in K.FOLDS]))
        tw = twins(r, T, X[X.und.isin(r["scope"]) & X.day.isin(days)], rng)
        allm = metrics(T, days, tw)
        folds_pos = sum(ev[f]["net"] > 0 for f in K.FOLDS)
        gate = dict(net_pos=allm["net"] > 0, folds_pos=int(folds_pos), folds_ok=folds_pos >= 3,
                    p_ok=allm["p_twin"] < 0.05, trades_ok=allm["trades"] >= K.MIN_TRADES)
        gate["pass"] = all(v for k, v in gate.items() if k.endswith("_ok") or k == "net_pos")
        out.append(dict(rule=r, design=allm, folds=ev, gate=gate,
                        by_index=index_split(T, X, r, days), exits=T.why.value_counts().to_dict() if len(T) else {}))
        print(f"{r['id']:4s} trades {allm['trades']:5d} net {allm['net']:>11,.0f} folds+ {folds_pos} "
              f"p {allm['p_twin']:.3f} {'PASS' if gate['pass'] else 'fail'}  {r['name'][:80]}")
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "rules.json").write_text(json.dumps(rules, indent=1, default=float))
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=_js))
    return dict(rules=rules, results=out)


def index_split(T, X, r, days):
    out = {}
    for u in r["scope"]:
        Tu = T[T.und == u] if len(T) else T
        du = sorted(X[(X.und == u) & X.day.isin(days)].day.unique())
        out[u] = dict(trades=len(Tu), net=float(Tu.net.sum()) if len(Tu) else 0.0, days=len(du))
    return out


def _js(o):
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return float(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, pd.Timestamp):
        return str(o.date())
    return str(o)


def holdout(resume: bool = False) -> dict:
    """The ONE read of the locked holdout for the frozen rule list (PREREG 6)."""
    done = K.RESULTS / "holdout.json"
    assert not done.exists(), "the F1 holdout has been read already; it is never read again"
    assert (K.RESULTS / "rules.json").exists() and (K.RESULTS / "design.json").exists(), "design step first"
    if K.HOLD_FLAG.exists() and not resume:
        raise SystemExit("holdout flag exists without results: re-run with --resume (same frozen computation)")
    K.HOLD_FLAG.parent.mkdir(parents=True, exist_ok=True)
    K.HOLD_FLAG.write_text(f"opened {pd.Timestamp.now()}\n")
    rules = json.loads((K.RESULTS / "rules.json").read_text())
    for u in K.UNDS:
        D.build(u, "holdout")
        S.featrows(u, ("holdout",))
    X = load_all("holdout")
    REF = load_all("design")
    rng = np.random.default_rng(K.SEED + 1)

    def ref_for(u, lo):
        return REF[REF.und == u]                    # all design data (PREREG 5)
    out = []
    for r in rules:
        ev = evaluate(r, X, ref_for, {"holdout": K.HOLDOUT}, rng)
        T = ev["holdout"].pop("_T")
        m = ev["holdout"]
        out.append(dict(id=r["id"], holdout=m, by_index=index_split(T, X, r, period_days(X, r)),
                        exits=T.why.value_counts().to_dict() if len(T) else {}))
    q = S.bh(np.array([o["holdout"]["p_twin"] for o in out]))
    for o, qq in zip(out, q):
        m = o["holdout"]
        o["q_bh"] = float(qq)
        o["gate"] = dict(net_pos=m["net"] > 0, p_ok=m["p_twin"] < 0.05, q_ok=qq <= K.BH_Q,
                         dd_ok=m["max_dd"] >= -K.MAX_DD, trades_ok=m["trades"] >= K.MIN_TRADES)
        o["gate"]["pass"] = all(o["gate"].values())
        print(f"{o['id']:4s} holdout trades {m['trades']:5d} net {m['net']:>11,.0f} p {m['p_twin']:.3f} q {qq:.3f} "
              f"{'PASS' if o['gate']['pass'] else 'fail'}")
    done.write_text(json.dumps(out, indent=1, default=_js))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["design", "holdout"])
    ap.add_argument("--resume", action="store_true")
    a = ap.parse_args(argv)
    design() if a.cmd == "design" else holdout(a.resume)


if __name__ == "__main__":
    main()
