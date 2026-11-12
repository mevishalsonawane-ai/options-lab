"""R163 (PREREG.md): a stronger trade picker for R72 Breadth Donchian and R51 Donchian 20, in the app's walk.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r163_donchian_picker/study.py build   # rows + features
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r163_donchian_picker/study.py wf      # walk-forward only
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r163_donchian_picker/study.py final   # the ONE final read
    python research/hunt/r163_donchian_picker/report.py

Candidates, exits and prices are R151's (its `study.py` units and R101 / R106's walk on R51's clean stores). The app walk:
the plain arm walks every break; a picker only drops trades from that list (a skipped break still holds its side).
Caches (rows, scores, models) go to ~/.cache/iraalgo/r163; small JSON / CSV results to results/.
"""
from __future__ import annotations

import copy
import json
import os
import pickle
import sys
import time
import warnings
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0]
sys.path.insert(0, str(HERE.parents[2]))
sys.path.insert(0, str(HERE.parent / "r151_donchian_winrate60"))
sys.argv = [sys.argv[0], "design"]
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import study as T  # noqa: E402  (R151)

from research.hunt.r163_donchian_picker import chain as CH  # noqa: E402
from research.hunt.r163_donchian_picker import feats as F  # noqa: E402
from research.hunt.r163_donchian_picker import models as M  # noqa: E402

warnings.filterwarnings("ignore")
S, K, B = T.S, T.K, T.B
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r163"
FINAL_FLAG = RESULTS / "FINAL_READ"
ARMS = {"R72": (20, 4), "R51": (20, 3.0)}
LIVE_THR = {"R72": 0.4656, "R51": 0.4469}
FOLDS = T.FOLDS
WF = (FOLDS[0][0], FOLDS[-1][1])
FINAL = (K.FINAL_START, K.FINAL_END)
COLS = list(T.FEATS) + list(F.NEW)
COLS151 = list(range(len(T.FEATS)))
N_PRIOR = 2164                       # R160's tally through R160 (R160 PREREG 7); R161 / R162 join via pvalues.csv
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
LOTS = (1, 2, 3)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ================================================================================================ features of one candidate
class Ctx:
    """Per-process sources: R150's index stores (via R151), VIX minutes, the chain per index, expiry flags."""

    def __init__(self):
        self.chain_df = {}
        self.chain_day = {}
        self.vix = T.vix_days()
        self.vix_dates = sorted(self.vix)
        self.vix_close = {d: F._last(self.vix[d][:, 3]) for d in self.vix_dates}

    def chain(self, und, day):
        if und not in self.chain_df:
            o = CH.read(S.mods()["r51data"].K.LAKE, und,
                        step=S.mods()["r51data"].K.STEP[und])
            self.chain_df[und] = dict(tuple(o.groupby("day", sort=True)))
            log("chain", und, len(o), "rows", len(self.chain_df[und]), "days")
        key = (und, day)
        if key not in self.chain_day:
            g = self.chain_df[und].get(day)
            self.chain_day[key] = CH.Day(g) if g is not None else None
        return self.chain_day[key]


def new_feats(ctx: Ctx, und: str, day, er: int, side: str, strike: float, prev_er) -> dict:
    """F1 - F6 of one candidate at the decision row er - 1."""
    dr = er - 1
    s = 1.0 if side == "C" else -1.0
    x = B.lv(und)
    i = x.pos[day]
    today = x.idx[day]
    hist = [x.idx[d] for d in x.dates[max(0, i - 20): i]]
    out = {}
    out.update(F.f1(today, hist, dr, s))
    sess = []
    for u in UNDS:
        try:
            y = B.lv(u)
        except Exception:  # noqa: BLE001
            continue
        if day in y.pos and y.pos[day] > 0:
            sess.append((y.idx[day], F._last(y.idx[y.dates[y.pos[day] - 1]][3])))
    out.update(F.f2(sess, dr, s))
    vi = [d for d in ctx.vix_dates if d < day]
    out.update(F.f3(ctx.vix.get(day), [ctx.vix_close[d] for d in vi[-250:]], dr))
    price = x.close_at(day, dr)
    D = ctx.chain(und, day)
    lot = S.mods()["r51data"].K.lot(und, day)
    out.update(F.f4(D.grid if D else None, D.contract(side, strike) if D else None, dr, side, price, lot))
    out.update(F.f5(x.levels(day, er, 20), price, s))
    days = S.mods()["r51data"].load(und)
    to_exp = None
    for j in range(i, len(x.dates)):
        dd = days.get(x.dates[j])
        if dd is not None and dd.expiry:
            to_exp = j - i
            break
    prev_close = F._last(x.idx[x.dates[i - 1]][3]) if i > 0 else np.nan
    out.update(F.f6(to_exp, prev_er, er, today, prev_close, dr))
    return out


# ================================================================================================ build
def charges_fn():
    return sys.modules["engine"].charges


def build() -> None:
    S.mods()["C"].open_final()
    CACHE.mkdir(parents=True, exist_ok=True)
    ctx = Ctx()
    ch = charges_fn()
    for arm, params in ARMS.items():
        us = T.units(arm, params, K.START, K.FINAL_END)
        trades = S.walk(arm, us)
        fx = T.feats_of_units(us)
        prev = {}
        for key, cands, _, _ in us:
            cs = sorted(cands, key=lambda c: int(c["er"]))
            for n, c in enumerate(cs):
                prev[T.cand_key(key[0], key[1], c["er"], c["side"])] = int(cs[n - 1]["er"]) if n else None
        rows = []
        for t in trades:
            k = T.cand_key(t["und"], t["day"], t["er_sig"], t["side"])
            f0 = fx[k]
            nf = new_feats(ctx, t["und"], t["day"], int(t["er_sig"]), t["side"], t["strike"], prev[k])
            n1 = (t["rX"] - t["rE"]) * t["qty"] - float(ch(t["und"], np.array([t["rE"]]), np.array([t["rX"]]), t["qty"], t["day"])[0])
            assert abs(n1 - t["net"]) < 0.05, (n1, t["net"])          # lot scaling parity with the engine's net
            r = dict(arm=arm, und=t["und"], day=t["day"], er=int(t["er_sig"]), side=t["side"], strike=float(t["strike"]),
                     net=float(t["net"]), rE=float(t["rE"]), rX=float(t["rX"]), qty=float(t["qty"]))
            for n_ in (2, 3):
                r[f"net{n_}"] = (t["rX"] - t["rE"]) * t["qty"] * n_ - float(
                    ch(t["und"], np.array([t["rE"]]), np.array([t["rX"]]), t["qty"] * n_, t["day"])[0])
            r.update({c: f0.get(c, np.nan) for c in T.FEATS})
            r.update(nf)
            rows.append(r)
        df = pd.DataFrame(rows).sort_values(["day", "er", "und"], kind="stable").reset_index(drop=True)
        df.to_pickle(CACHE / f"rows_{arm}.pkl")
        log(arm, "trades", len(df), "design", int((df.day <= K.DESIGN_END).sum()), "final", int((df.day >= K.FINAL_START).sum()),
            "missing share (new)", {c: round(float(df[c].isna().mean()), 3) for c in F.NEW if df[c].isna().mean() > 0.05})


def rows(arm: str) -> pd.DataFrame:
    return pd.read_pickle(CACHE / f"rows_{arm}.pkl")


def X_of(df: pd.DataFrame) -> np.ndarray:
    return df[COLS].to_numpy(dtype=float)


def stats_of(df: pd.DataFrame, mask=None) -> dict:
    d = df if mask is None else df[mask]
    out = M.summ(d.net.values, list(d.day))
    for n_ in (2, 3):
        out[f"net_{n_}lots"] = float(d[f"net{n_}"].sum())
    return out


def by_fold(df: pd.DataFrame, mask) -> list:
    return [stats_of(df, mask & (df.day >= f0).values & (df.day <= f1).values) for f0, f1 in FOLDS]


# ================================================================================================ walk-forward
def oof(df: pd.DataFrame, method: str, keep_models: bool = False):
    """Out-of-fold scores for the WF rows (design only). Training: every trade of a day before the fold starts."""
    X, yw, yn = X_of(df), (df.net.values > 0).astype(int), df.net.values
    sc = np.full(len(df), np.nan)
    fitted = []
    for f0, f1 in FOLDS:
        tr = M.train_mask(df.day.values, f0)
        te = ((df.day >= f0) & (df.day <= f1)).values
        p, mdl = M.fit_score(method, X[tr], yw[tr], yn[tr], X[te], COLS151)
        sc[te] = p
        fitted.append((f0, f1, mdl, te))
    return (sc, fitted) if keep_models else sc


def curve_of(df: pd.DataFrame, sc: np.ndarray, wfm: np.ndarray) -> list:
    out = []
    for keep, thr in M.keep_thresholds(sc[wfm]):
        m = stats_of(df, wfm & (sc >= thr))
        m.update(keep=keep, thr=thr)
        out.append(m)
    return out


def perm_importance(df, fitted, method, rng, repeats=5) -> list:
    X, y = X_of(df), (df.net.values > 0).astype(int)
    wf = np.zeros(len(df), bool)
    base_p = np.full(len(df), np.nan)
    for f0, f1, mdl, te in fitted:
        base_p[te] = mdl.predict_proba(X[te])[:, 1]
        wf |= te
    base = M.auc(base_p[wf], y[wf])
    out = []
    for j, c in enumerate(COLS):
        drops = []
        for _ in range(repeats):
            p = np.full(len(df), np.nan)
            for f0, f1, mdl, te in fitted:
                Xt = X[te].copy()
                Xt[:, j] = Xt[rng.permutation(len(Xt)), j]
                p[te] = mdl.predict_proba(Xt)[:, 1]
            drops.append(base - M.auc(p[wf], y[wf]))
        out.append({"feature": c, "auc_drop": float(np.mean(drops))})
    return sorted(out, key=lambda r: -r["auc_drop"])


def wf() -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(M.SEED)
    out = {"_meta": dict(phase="wf", written=datetime.now(timezone.utc).isoformat(), folds=[[str(a), str(b)] for a, b in FOLDS],
                         n_features=len(COLS), features=COLS)}
    scores = {}
    for arm in ARMS:
        df = rows(arm)
        df = df[df.day <= K.DESIGN_END].reset_index(drop=True)        # nothing of FINAL is read here
        wfm = ((df.day >= WF[0]) & (df.day <= WF[1])).values
        y = (df.net.values > 0).astype(int)
        res = {"design_trades": int(len(df)), "plain": stats_of(df, wfm), "plain_folds": by_fold(df, wfm)}
        sc151 = oof(df, "P151")
        k151 = wfm & (sc151 >= LIVE_THR[arm])
        res["P151"] = {"thr": LIVE_THR[arm], "wf": stats_of(df, k151), "folds": by_fold(df, k151),
                       "auc": M.auc(sc151[wfm], y[wfm]), "brier": M.brier(sc151[wfm], y[wfm]),
                       "calibration": M.calibration(sc151[wfm], y[wfm])}
        bar = res["P151"]["wf"]
        scores[(arm, "P151")] = sc151
        log(arm, "plain wf", res["plain"]["trades"], round(res["plain"]["win"], 3), round(res["plain"]["net"]),
            "| P151", bar["trades"], round(bar["win"], 3), round(bar["net"]))
        res["methods"] = {}
        for method in M.METHODS:
            if method == "G1":
                sc, fitted = oof(df, method, keep_models=True)
            else:
                sc = oof(df, method)
            scores[(arm, method)] = sc
            cur = curve_of(df, sc, wfm)
            pick = M.choose(cur, bar["net"])
            r = {"curve": cur, "pick": pick}
            if pick is not None:
                km = wfm & (sc >= pick["thr"])
                r["folds"] = by_fold(df, km)
                r["s1_pass"] = bool(pick["win"] >= bar["win"] + 0.05 and pick["net"] >= bar["net"])
            else:
                r["s1_pass"] = False
                best = sorted(cur, key=lambda m: (-m["win"], m["thr"]))[0]
                r["report_thr"] = best["thr"]                 # for FINAL reporting only (labelled), never a pass
            if method != "G2":
                r.update(auc=M.auc(sc[wfm], y[wfm]), brier=M.brier(sc[wfm], y[wfm]), calibration=M.calibration(sc[wfm], y[wfm]))
            else:
                r.update(spearman_net=float(pd.Series(sc[wfm]).corr(pd.Series(df.net.values[wfm]), method="spearman")))
            if method == "G1":
                r["perm_importance"] = perm_importance(df, fitted, method, rng)[:20]
            res["methods"][method] = r
            pk = pick
            log(arm, method, "pick", None if pk is None else (pk["keep"], pk["trades"], round(pk["win"], 3), round(pk["net"]), round(pk["thr"], 4)),
                "S1", r["s1_pass"], "auc", round(r.get("auc", float("nan")), 3))
        out[arm] = res
    (CACHE / "wf_scores.pkl").write_bytes(pickle.dumps(scores))
    (RESULTS / "wf.json").write_text(json.dumps(out, indent=1, default=str))


# ================================================================================================ final (read once)
def final() -> None:
    if FINAL_FLAG.exists():
        raise SystemExit(f"FINAL already read: {FINAL_FLAG.read_text()}")
    w = json.loads((RESULTS / "wf.json").read_text())
    FINAL_FLAG.write_text(json.dumps({"read_utc": datetime.now(timezone.utc).isoformat(timespec="seconds")}))
    S.mods()["C"].open_final()
    rng = np.random.default_rng(M.SEED + 1)
    out = {"_meta": dict(phase="final", lo=str(FINAL[0]), hi=str(FINAL[1]), written=datetime.now(timezone.utc).isoformat(),
                         n_prior=N_PRIOR)}
    pv = []
    frozen = {}
    for arm, params in ARMS.items():
        df = rows(arm)
        des = (df.day <= K.DESIGN_END).values
        fin = ((df.day >= FINAL[0]) & (df.day <= FINAL[1])).values
        X, yw, yn = X_of(df), (df.net.values > 0).astype(int), df.net.values
        res = {"plain": stats_of(df, fin), "final_trades": int(fin.sum())}
        wins_total, n_total = int(yw[fin].sum()), int(fin.sum())
        # P151: the exported model's recipe (all design trades)
        p151, m151 = M.fit_score("P151", X[des], yw[des], yn[des], X[fin], COLS151)
        frozen[(arm, "P151")] = m151
        k151 = np.zeros(len(df), bool)
        k151[fin] = p151 >= LIVE_THR[arm]
        bar = stats_of(df, k151)
        nums = T.PK.model_numbers(m151, T.FEATS, LIVE_THR[arm])
        ref = json.loads((T.RESULTS / f"{arm.lower()}_picker_model.json").read_text())
        res["P151"] = {"final": bar, "coef_max_abs_diff_vs_exported": float(np.max(np.abs(np.array(nums["coef"]) - np.array(ref["coef"])))),
                       "auc": M.auc(p151, yw[fin]), "brier": M.brier(p151, yw[fin]), "calibration": M.calibration(p151, yw[fin]),
                       "hyper_p": M.hyper_p(n_total, wins_total, int(k151.sum()), int(yw[k151].sum()))}
        # P151 in R151's research walk (skipped breaks removed before the walk): the effect of the app's rule
        us = T.units(arm, params, FINAL[0], FINAL[1])
        fx = T.feats_of_units(us)
        ck = [T.cand_key(k[0], k[1], c["er"], c["side"]) for k, cs, _, _ in us for c in cs]
        sc_all = dict(zip(ck, m151.predict_proba(T.matrix(ck, fx))[:, 1]))
        rw = [t for t in T.kept_walk(arm, us, sc_all, LIVE_THR[arm]) if FINAL[0] <= t["day"] <= FINAL[1]]
        res["P151"]["research_walk"] = M.summ([t["net"] for t in rw], [t["day"] for t in rw])
        log(arm, "plain", res["plain"]["trades"], round(res["plain"]["win"], 3), round(res["plain"]["net"]), "| P151 app", bar["trades"],
            round(bar["win"], 3), round(bar["net"]), "| research walk", res["P151"]["research_walk"])
        res["methods"] = {}
        for method in M.METHODS:
            wm = w[arm]["methods"][method]
            sc, mdl = M.fit_score(method, X[des], yw[des], yn[des], X[fin], COLS151)
            frozen[(arm, method)] = mdl
            thr = wm["pick"]["thr"] if wm["pick"] else wm["report_thr"]
            km = np.zeros(len(df), bool)
            km[fin] = sc >= thr
            m = stats_of(df, km)
            kw = int(yw[km].sum())
            hp = M.hyper_p(n_total, wins_total, int(km.sum()), kw)
            ntp = M.net_twin_p(yn[fin], int(km.sum()), m["net"], rng)
            r = {"thr": thr, "wf_picked": wm["pick"] is not None, "final": m, "hyper_p": hp, "net_twin_p": ntp}
            if method != "G2":
                r.update(auc=M.auc(sc, yw[fin]), brier=M.brier(sc, yw[fin]), calibration=M.calibration(sc, yw[fin]))
            else:
                r.update(spearman_net=float(pd.Series(sc).corr(pd.Series(yn[fin]), method="spearman")))
            if method in ("G1", "G2"):
                gain = mdl.booster_.feature_importance(importance_type="gain")
                r["gain_importance"] = sorted([{"feature": c, "gain": float(g)} for c, g in zip(COLS, gain)], key=lambda z: -z["gain"])[:20]
            fails = []
            if not wm["s1_pass"]:
                fails.append("WF: no threshold reached P151's net" if wm["pick"] is None else "WF: win not >= P151 + 5 pts or net below P151")
            if not (m["win"] >= bar["win"] + 0.05):
                fails.append(f"FINAL win {m['win']:.3f} < P151 {bar['win']:.3f} + 0.05")
            if not (m["net"] >= bar["net"]):
                fails.append(f"FINAL net {m['net']:.0f} < P151 {bar['net']:.0f}")
            if m["trades"] < 40:
                fails.append("< 40 FINAL trades")
            per = [m] + (wm.get("folds") or [])
            if wm["pick"] is None or any(p_["net"] <= 0 for p_ in per):
                fails.append("not positive in every period (WF quarters + FINAL)")
            r["fails"] = fails
            res["methods"][method] = r
            pv.append({"test": f"r163-{arm.lower()}-{method.lower()}", "arm": arm, "method": method, "p_raw": hp,
                       "trades": m["trades"], "win": m["win"], "net": m["net"]})
            log(arm, method, "thr", round(thr, 4), m["trades"], round(m["win"], 3), round(m["net"]), "hyper p", round(hp, 5), fails)
        out[arm] = res
    q_in = M.bh([r["p_raw"] for r in pv])
    q_all = M.bh([r["p_raw"] for r in pv], N_PRIOR + len(pv))
    for r, a, b in zip(pv, q_in, q_all):
        r["q_r163"], r["q_all"] = float(a), float(b)
        rr = out[r["arm"]]["methods"][r["method"]]
        rr["q_r163"], rr["q_all"] = float(a), float(b)
        if b > 0.10:
            rr["fails"].append(f"BH q {b:.3g} > 0.10 (m = {N_PRIOR + len(pv)})")
        rr["pass"] = not rr["fails"]
        r["pass"] = rr["pass"]
    (CACHE / "frozen.pkl").write_bytes(pickle.dumps(frozen))
    pd.DataFrame(pv).to_csv(RESULTS / "pvalues.csv", index=False)
    (RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"build": build, "wf": wf, "final": final}[CMD]()
