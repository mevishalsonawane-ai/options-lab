"""h42 question engine, step 2: answer every question in questions.csv on PRE-HOLDOUT data only (day < 2025-10-01).
Per question: effect, n, base rate, 95% CI, p (day-clustered), BH q over all questions, half-sample stability, and the
option-buyer translation (1-ITM nearest expiry, entry at the next open, exits on the option's 1-min HIGH/LOW): net and
gross Rs per trade at 1 lot for 38 exits, matched (same index / minute / side) random-entry baseline, BH over all
question x exit option tests.  Output: research/hunt/h42/answers.csv  (+ <scratch>/hunt/h42/exits_pre.csv.gz)
python3 -I research/hunt/h42/answer.py
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (EXITS, HERE, HOLD, OUT, UNDS, bh, cl_diff, cl_mean, entry_col, exit_px, load_panel, load_table,  # noqa: E402
                    p1, p2, pnl)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

MIN_TRADES = 30


class Base:
    """matched random-entry baseline: mean net per (exit, horizon) x (decision minute s, side) over pre-holdout days."""

    def __init__(self, u, P):
        self.u, self.P, self.c = u, P, {}
        self.pre = np.nonzero(P["days"] < HOLD)[0]

    def get(self, ex, hz):
        k = (ex, hz if ex == "time" else "")
        if k not in self.c:
            P = self.P
            nd, ns = len(self.pre), P["E"].shape[1]
            d = np.repeat(self.pre, ns * 2)
            s = np.tile(np.repeat(np.arange(ns), 2), nd)
            sd = np.tile(np.array([0, 1]), nd * ns)
            E = P["E"][d, s, sd]
            ok = np.isfinite(E)
            d, s, sd, E = d[ok], s[ok], sd[ok], E[ok]
            X, st, _ = exit_px(P, ex, hz, d, s, sd)
            ok2 = np.isfinite(X)
            net, _ = pnl(self.u, E[ok2].astype(float), X[ok2].astype(float), st[ok2])
            sums = np.zeros((ns, 2))
            cnt = np.zeros((ns, 2))
            np.add.at(sums, (s[ok2], sd[ok2]), net)
            np.add.at(cnt, (s[ok2], sd[ok2]), 1)
            self.c[k] = sums / np.maximum(cnt, 1)
        return self.c[k]


def trade_eval(u, P, base, ev_days, ev_s, ev_side, hz):
    """ev_side: +1 CE, -1 PE, 0 both. Returns dict per exit of (n, mean_net, se, mean_gross, mean_base, edge, edge_se)."""
    rowmap = {int(dd): i for i, dd in enumerate(P["days"])}
    d = np.array([rowmap.get(int(x), -1) for x in ev_days])
    ok = (d >= 0) & (ev_s >= 0) & (ev_s <= 345)
    d, s, sdv, dy = d[ok], ev_s[ok], ev_side[ok], np.asarray(ev_days)[ok]
    legs = []
    if (sdv == 0).any():
        both = sdv == 0
    else:
        both = np.zeros(len(sdv), bool)
    out = {}
    for ex in EXITS:
        tot_net = np.zeros(len(d))
        tot_gr = np.zeros(len(d))
        tot_base = np.zeros(len(d))
        good = np.ones(len(d), bool)
        B = base.get(ex, hz)
        for leg in (0, 1):                      # leg 0 = CE, 1 = PE
            use = (sdv == (1 if leg == 0 else -1)) | both
            if not use.any():
                continue
            E = P["E"][d[use], s[use], leg].astype(float)
            X, st, _ = exit_px(P, ex, hz, d[use], s[use], np.full(use.sum(), leg))
            X = X.astype(float)
            g = np.isfinite(E) & np.isfinite(X)
            net = np.zeros(use.sum())
            gr = np.zeros(use.sum())
            n_, g_ = pnl(u, np.where(g, E, 1.0), np.where(g, X, 1.0), st)
            net[g], gr[g] = n_[g], g_[g]
            tot_net[use] += net
            tot_gr[use] += gr
            tot_base[use] += B[s[use], leg]
            gg = np.ones(len(d), bool)
            gg[np.nonzero(use)[0][~g]] = False
            good &= gg
        if good.sum() < MIN_TRADES:
            out[ex] = None
            continue
        m, se, G = cl_mean(tot_net[good], dy[good])
        mg, _, _ = cl_mean(tot_gr[good], dy[good])
        e_, ese, _ = cl_mean(tot_net[good] - tot_base[good], dy[good])
        out[ex] = dict(n=int(good.sum()), days=G, net=m, se=se, gross=mg, base=float(tot_base[good].mean()), edge=e_, edge_se=ese)
    legs.append(len(d))
    return out


def main():
    Q = pd.read_csv(os.path.join(HERE, "questions.csv"), dtype=str, keep_default_na=False)
    rows, exrows = [], []
    t0 = time.time()
    tables = {}
    for u in UNDS:
        P = load_panel(u)
        base = Base(u, P)
        for _, q in Q[Q.und == u].iterrows():
            key = (q.table, u)
            if key not in tables:
                df = load_table(q.table, u)
                tables[key] = df[df.day < HOLD].reset_index(drop=True)
            df = tables[key]
            r = dict(id=q.id, category=q.category, und=u, question=q.question)
            try:
                uni = df.eval(q.universe).fillna(False).astype(bool).values if q.universe else np.ones(len(df), bool)
                y = df.eval(q.outcome).astype(float).values
                cond = df.eval(q.cond).fillna(False).astype(bool).values
            except Exception as e:      # noqa: BLE001
                r["error"] = str(e)[:200]
                rows.append(r)
                continue
            v = uni & np.isfinite(y)
            yy, cc, gg = y[v], cond[v], df.day.values[v]
            r["n_cond"] = int(cc.sum())
            r["n_cond_days"] = int(len(np.unique(gg[cc])))
            r["n_comp"] = int((~cc).sum())
            r["base_all"] = float(yy.mean()) if len(yy) else np.nan
            if q.ref.startswith("const"):
                c0 = float(q.ref.split(":")[1])
                m, se, _ = cl_mean(yy[cc], gg[cc]) if cc.sum() >= 2 else (np.nan, np.nan, 0)
                eff = m - c0
                r.update(cond_mean=m, comp_mean=c0, effect=eff, se=se)
            else:
                eff, se, ma, mb = cl_diff(yy, cc, gg) if cc.sum() >= 2 else (np.nan,) * 4
                r.update(cond_mean=ma, comp_mean=mb, effect=eff, se=se)
            r["ci_lo"] = r["effect"] - 1.96 * r["se"] if np.isfinite(r["se"]) else np.nan
            r["ci_hi"] = r["effect"] + 1.96 * r["se"] if np.isfinite(r["se"]) else np.nan
            r["p"] = p2(r["effect"] / r["se"]) if (np.isfinite(r["se"]) and r["se"] > 0) else np.nan
            # half-sample stability
            if len(gg) and cc.sum() >= 10:
                med = np.median(np.unique(gg))
                hs = []
                for half in (gg <= med, gg > med):
                    a, b = cc & half, (~cc) & half
                    if a.sum() < 3 or (q.ref == "comp" and b.sum() < 3):
                        hs.append(np.nan)
                    else:
                        c0 = float(q.ref.split(":")[1]) if q.ref.startswith("const") else yy[b].mean()
                        hs.append(yy[a].mean() - c0)
                r["eff_h1"], r["eff_h2"] = hs
            # option translation
            side = q.side
            if side != "none" and cc.sum() >= MIN_TRADES:
                ev = np.nonzero(v)[0][cc]
                ev_days = df.day.values[ev]
                ev_s = entry_col(df.iloc[ev], q.entry) if q.entry not in df.columns else df[q.entry].values[ev].astype(int)
                if side == "auto":
                    dm = np.nanmean(df.eval(q.dircol).astype(float).values[ev])
                    sd = np.full(len(ev), 1 if dm > 0 else -1)
                    r["side_used"] = "CE" if dm > 0 else "PE"
                elif side.startswith("col:"):
                    sd = np.sign(df[side[4:]].values[ev]).astype(int)
                    r["side_used"] = side
                    keep = sd != 0
                    ev_days, ev_s, sd = ev_days[keep], ev_s[keep], sd[keep]
                elif side == "both":
                    sd = np.zeros(len(ev), int)
                    r["side_used"] = "CE+PE"
                else:
                    sd = np.full(len(ev), 1 if side == "C" else -1)
                    r["side_used"] = "CE" if side == "C" else "PE"
                res = trade_eval(u, P, base, ev_days, ev_s, sd, q.horizon)
                best, bnet = None, -np.inf
                for ex, x in res.items():
                    if x is None:
                        continue
                    pp = p1(x["net"] / x["se"]) if x["se"] and x["se"] > 0 else np.nan
                    pe = p1(x["edge"] / x["edge_se"]) if x["edge_se"] and x["edge_se"] > 0 else np.nan
                    exrows.append(dict(id=q.id, exit=ex, n=x["n"], days=x["days"], net=x["net"], gross=x["gross"],
                                       p_net=pp, base=x["base"], edge=x["edge"], p_edge=pe))
                    if x["net"] > bnet:
                        best, bnet = ex, x["net"]
                pr = res.get("time")
                if pr:
                    r.update(trade_n=pr["n"], trade_days=pr["days"], prim_gross=pr["gross"], prim_net=pr["net"],
                             prim_p=p1(pr["net"] / pr["se"]) if pr["se"] else np.nan, prim_base=pr["base"])
                if best:
                    x = res[best]
                    r.update(best_exit=best, best_gross=x["gross"], best_net=x["net"],
                             best_p=p1(x["net"] / x["se"]) if x["se"] else np.nan, best_base=x["base"], best_edge=x["edge"],
                             best_edge_p=p1(x["edge"] / x["edge_se"]) if x["edge_se"] else np.nan, trade_n=x["n"])
            rows.append(r)
        print(u, "done", f"{time.time() - t0:.0f}s", flush=True)
        del P, base
    A = pd.DataFrame(rows)
    X = pd.DataFrame(exrows)
    A["q"] = bh(A.p.values)
    X["q_net"] = bh(X.p_net.values)
    X["q_edge"] = bh(X.p_edge.values)
    bq = X.merge(A[["id", "best_exit"]], left_on=["id", "exit"], right_on=["id", "best_exit"])[["id", "q_net", "q_edge"]]
    A = A.merge(bq.rename(columns={"q_net": "best_q_opt", "q_edge": "best_q_edge"}), on="id", how="left")
    A["stable"] = (np.sign(A.eff_h1) == np.sign(A.eff_h2)) & (np.sign(A.eff_h1) == np.sign(A.effect))
    A["sig"] = A.q < 0.05
    A["candidate_strict"] = A.sig & (A.best_net > 0) & (A.best_q_opt < 0.05)
    A["candidate_loose"] = A.sig & A.stable & (A.best_net > 0) & (A.best_p < 0.05) & (A.best_edge > 0)
    A.to_csv(os.path.join(HERE, "answers_raw.csv"), index=False)
    X.to_csv(os.path.join(OUT, "exits_pre.csv.gz"), index=False)
    print("questions", len(A), "errors", A.get("error", pd.Series(dtype=str)).notna().sum(), "sig(q<.05)", int(A.sig.sum()),
          "strict", int(A.candidate_strict.sum()), "loose", int(A.candidate_loose.sum()), flush=True)


if __name__ == "__main__":
    main()
