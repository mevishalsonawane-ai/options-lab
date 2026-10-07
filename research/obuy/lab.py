"""Run strategies x parameter grids end to end: signals -> one data pass -> every variant -> statistics -> overfitting
controls -> gated ranking -> report (markdown + CSVs under <CACHE>/runs/<name>/).

Gates (all must pass for a strategy family to be PROMOTED):
  G1 walk-forward (anchored, yearly; parameters picked on past years only) net > 0 after costs;
  G2 the walk-forward trades beat the same-exit random-entry baseline: Holm-adjusted p < 0.05 across all families run
     (BH q also shown);
  G3 positive in more than half of the walk-forward test years;
  G4 acceptable drawdown: walk-forward max drawdown at 1 lot <= 20% of Rs 5 lakh, and Monte Carlo P(50% drawdown)
     at 1 lot <= 5%.
Context (not gates): the full-sample best variant's Deflated Sharpe Ratio with N = all variants tried in the run, the
family's PBO (CSCV), White's Reality Check / Hansen's SPA p over all variants, variant-level random-baseline p with BH /
Holm across all variants (variants not tested because they lost money in full sample get p = 1).
"""
from __future__ import annotations

import hashlib
import inspect
import json
import os
import pickle
import time
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from . import config as C
from . import overfit as OF
from . import stats as ST
from .data import market
from .engine import positions, prepare_many

KEEP = ["cand", "und", "book", "day", "year", "sig_min", "gate", "entry_min", "exit_min", "side", "right", "strike", "lot",
        "qty", "entry", "exit", "why", "gross", "charges", "net", "risk_rs", "R"]


@dataclass
class Gates:
    alpha: float = 0.05
    max_dd_frac: float = 0.20
    max_p_dd50: float = 0.05
    train_years: int = 2
    min_train_trades: int = 20


def _hash(*parts):
    return hashlib.sha1(repr(parts).encode()).hexdigest()[:12]


def signals_cached(strategy, params, refresh=False):
    src = inspect.getsource(strategy.signal_fn)
    key = _hash(strategy.signal_fn.__module__, src, sorted(params.items()))
    d = os.path.join(C.CACHE, "signals")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, f"{strategy.signal_fn.__module__.split('.')[-1]}_{key}.pkl")
    if os.path.exists(path) and not refresh:
        return pd.read_pickle(path)
    s = strategy.signal_fn(market(), **params)
    s.to_pickle(path)
    return s


@dataclass
class Lab:
    strategies: list
    name: str = "run"
    pool_k: int = 10
    B: int = 2000
    gates: Gates = field(default_factory=Gates)
    verbose: bool = True
    pool_all: bool = False          # run the random baseline for every variant (default: only those with net > 0)

    def log(self, *a):
        if self.verbose:
            print(time.strftime("%H:%M:%S"), *a, flush=True)

    # -------------------------------------------------------------------------------------------- run
    def run(self):
        t0 = time.time()
        self.out_dir = os.path.join(C.CACHE, "runs", self.name)
        os.makedirs(self.out_dir, exist_ok=True)
        jobs, jmeta = [], []
        for st in self.strategies:
            for si, sp in enumerate(st.sig_grid):
                sig = signals_cached(st, sp)
                market().release()
                self.log(f"signals {st.name} s{si} {sp}: {len(sig)}")
                for ri, rule in enumerate(st.rules):
                    jobs.append((sig, rule, st.exe, self.pool_k, st.window, st.pool_same_side))
                    jmeta.append((st, si, ri))
        t1 = time.time()
        self.log(f"preparing {len(jobs)} packs in one data pass")
        packs = prepare_many(jobs)
        t2 = time.time()
        self.log(f"packs ready ({t2 - t1:.0f}s); store {packs[0][0].store.nbytes() / 1e6:.0f} MB")
        self.trades, self.vinfo, self.pools, self.packs = {}, {}, {}, {}
        for (pk, pool), (st, si, ri) in zip(packs, jmeta):
            for xi, ex in enumerate(st.exits):
                vid = f"{st.name}|s{si}|r{ri}|x{xi}"
                tr = pk.run(ex, st.exe) if len(pk) else pd.DataFrame(columns=KEEP)
                tr = positions(tr, **st.pos)[KEEP] if len(tr) else pd.DataFrame(columns=KEEP)
                self.trades[vid] = tr
                self.packs[vid] = (pool, ex, st.exe)
                self.vinfo[vid] = dict(vid=vid, strategy=st.name, family=st.family, sig=json.dumps(st.sig_grid[si], default=str),
                                       rule=st.rules[ri].label(), exits=ex.label())
        t3 = time.time()
        self.log(f"{len(self.trades)} variants run ({t3 - t2:.0f}s)")
        self.timing = dict(signals_s=t1 - t0, prepare_s=t2 - t1, variants_s=t3 - t2, n_variants=len(self.trades))
        self._analyse()
        self.timing["total_s"] = time.time() - t0
        self._write()
        return self

    def pool_trades(self, vid):
        if vid not in self.pools:
            pool, ex, exe = self.packs[vid]
            self.pools[vid] = pool.run(ex, exe) if pool is not None and len(pool) else None
        return self.pools[vid]

    # -------------------------------------------------------------------------------------------- analysis
    def _days(self, st_name):
        tr = [t for v, t in self.trades.items() if v.startswith(st_name + "|") and len(t)]
        if not tr:
            return []
        allt = pd.concat(tr)
        unds = set(allt.und)
        lo, hi = allt.day.min(), allt.day.max()
        days = sorted({d for u in unds for d in market().index(u).days if lo <= d <= hi})
        return days

    def _analyse(self):
        ta = time.time()
        g = self.gates
        rows = []
        self.days = {}
        for st in self.strategies:
            self.days[st.name] = self._days(st.name)
        all_days = sorted({d for v in self.days.values() for d in v})
        dpos = {d: i for i, d in enumerate(all_days)}
        vids = list(self.trades)
        X = np.zeros((len(all_days), len(vids)))
        for k, vid in enumerate(vids):
            tr = self.trades[vid]
            st = vid.split("|")[0]
            s = ST.summary(tr, self.days[st])
            r = dict(self.vinfo[vid], **s)
            if len(tr):
                dl = tr.groupby("day").net.sum()
                X[[dpos[d] for d in dl.index], k] = dl.values
            rows.append(r)
        V = pd.DataFrame(rows).set_index("vid")
        # variant-level random baseline (two-stage: only variants that made money)
        tb = time.time()
        pv = []
        for vid in vids:
            if (V.loc[vid, "net"] > 0 or self.pool_all) and V.loc[vid, "trades"] >= 10:
                rb = OF.random_baseline(self.trades[vid], self.pool_trades(vid), B=self.B)
            else:
                rb = dict(p=1.0, obs=np.nan, null_mean=np.nan, n_used=0)
            pv.append(rb)
        V["p_rand"] = [x["p"] for x in pv]
        V["rand_mean"] = [x.get("null_mean", np.nan) for x in pv]
        V["p_rand_bh"] = OF.bh(V.p_rand.values)
        V["p_rand_holm"] = OF.holm(V.p_rand.values)
        tc = time.time()
        # SPA / RC over all variants (daily P&L vs not trading)
        self.spa = OF.spa(X, B=1000) if len(vids) > 1 else dict(rc_p=np.nan, spa_p=np.nan)
        # Sharpe per period of every trial, for the DSR
        sd = X.std(axis=0, ddof=1)
        srs = np.where(sd > 0, X.mean(axis=0) / np.where(sd > 0, sd, 1), np.nan)
        V["sr_daily"] = srs
        td = time.time()
        # families: walk-forward, OOS random baseline, gates
        fam = []
        self.oos = {}
        for st in self.strategies:
            fv = [v for v in vids if v.startswith(st.name + "|")]
            by_year = pd.DataFrame({v: ST.per_year(self.trades[v]) for v in fv}).T.fillna(0.0)
            counts = pd.DataFrame({v: self.trades[v].groupby("year").size() if len(self.trades[v]) else pd.Series(dtype=float)
                                   for v in fv}).T.fillna(0)
            if by_year.empty:
                fam.append(dict(strategy=st.name, family=st.family, n_variants=len(fv), wf_net=0.0, verdict="no trades"))
                continue
            by_year = by_year.reindex(columns=sorted(by_year.columns), fill_value=0.0)
            counts = counts.reindex(index=by_year.index, columns=by_year.columns, fill_value=0)
            if len(fv) == 1:
                # a single fixed variant: the walk-forward IS the variant (nothing is picked); test years as for grids
                yrs = sorted(by_year.columns)[g.train_years:]
                tr = self.trades[fv[0]]
                oos = tr[tr.year.isin(yrs)].assign(picked=fv[0])
                wf_rows = [dict(year=y, picked=fv[0], test_net=float(by_year.loc[fv[0], y])) for y in yrs]
            else:
                wf_rows, oos = OF.walk_forward(by_year, self.trades, g.train_years, min_trades=g.min_train_trades, counts=counts)
            self.oos[st.name] = (wf_rows, oos)
            days_oos = [d for d in self.days[st.name] if len(oos) and d.year >= min(r["year"] for r in wf_rows)] if wf_rows else []
            s_oos = ST.summary(oos, days_oos) if len(oos) else ST.summary(oos)
            # OOS random baseline: each test year's trades against the picked variant's pool
            if len(oos):
                pool_parts = []
                for r in wf_rows:
                    pt = self.pool_trades(r["picked"])
                    if pt is not None and len(pt):
                        pool_parts.append(pt[pt.year == r["year"]].assign(parent=lambda x: x.parent.astype(str) + f"|{r['picked']}"))
                real = oos.assign(cand=oos.cand.astype(str) + "|" + oos.picked)
                rb = OF.random_baseline(real, pd.concat(pool_parts) if pool_parts else None, B=self.B)
            else:
                rb = dict(p=1.0, obs=np.nan, null_mean=np.nan)
            mc = ST.monte_carlo(oos, days_oos) if len(oos) >= 10 else {}
            yrs_pos = sum(1 for r in wf_rows if r["test_net"] > 0)
            best = V.loc[fv].net.idxmax()
            fsr = srs[[vids.index(v) for v in fv]]
            fsr = fsr[np.isfinite(fsr)]
            var_sr = max(fsr.var(ddof=1) if len(fsr) >= 5 else 0.0, 1.0 / len(X))
            d = OF.dsr(X[:, vids.index(best)], srs, n_trials=len(vids), var_sr=var_sr)
            pb = OF.pbo(X[:, [vids.index(v) for v in fv]], S=16, max_combos=4000) if len(fv) >= 2 else dict(pbo=np.nan)
            fam.append(dict(strategy=st.name, family=st.family, n_variants=len(fv), wf_years=len(wf_rows), wf_years_pos=yrs_pos,
                            wf_trades=s_oos["trades"], wf_net=s_oos["net"], wf_per_year=s_oos["per_year"], wf_pf=s_oos["pf"],
                            wf_win=s_oos["win"], wf_dd=s_oos["max_dd"], wf_sharpe=s_oos["sharpe"], wf_worst_month=s_oos["worst_month"],
                            p_rand=rb["p"], rand_obs=rb.get("obs"), rand_null=rb.get("null_mean"),
                            mc_p_profit=mc.get("1 lot", {}).get("p_profit"), mc_p_dd20=mc.get("1 lot", {}).get("p_dd20"),
                            mc_p_dd50=mc.get("1 lot", {}).get("p_dd50"),
                            mc_1pct_p_dd20=mc.get("1% risk", {}).get("p_dd20"), mc_2pct_p_dd50=mc.get("2% risk", {}).get("p_dd50"),
                            best_variant=best, best_net=float(V.loc[best, "net"]), best_dsr=d["dsr"], pbo=pb["pbo"],
                            picks=";".join(f"{r['year']}:{r['picked'].split('|', 1)[1]}" for r in wf_rows)))
        F = pd.DataFrame(fam).set_index("strategy")
        if "p_rand" in F:
            F["p_rand_holm"] = OF.holm(F.p_rand.fillna(1.0).values)
            F["p_rand_bh"] = OF.bh(F.p_rand.fillna(1.0).values)
            cap = ST.CAPITAL
            F["G1_wf_net"] = F.wf_net > 0
            F["G2_beats_random"] = F.p_rand_holm < g.alpha
            F["G3_years"] = F.wf_years_pos > F.wf_years / 2
            F["G4_drawdown"] = (F.wf_dd >= -g.max_dd_frac * cap) & (F.mc_p_dd50.fillna(1.0) <= g.max_p_dd50)
            F["promoted"] = F.G1_wf_net & F.G2_beats_random & F.G3_years & F.G4_drawdown
            F = F.sort_values(["promoted", "wf_net"], ascending=False)
        self.V, self.F, self.X, self.all_days, self.vids = V, F, X, all_days, vids
        self.timing.update(baseline_s=tc - tb, spa_s=td - tc, analyse_s=time.time() - ta)

    # -------------------------------------------------------------------------------------------- output
    def _write(self):
        self.V.to_csv(os.path.join(self.out_dir, "variants.csv"))
        self.F.to_csv(os.path.join(self.out_dir, "families.csv"))
        allt = pd.concat([t.assign(vid=v) for v, t in self.trades.items() if len(t)], ignore_index=True) if self.trades else None
        if allt is not None and len(allt) < 3_000_000:
            allt.to_csv(os.path.join(self.out_dir, "trades.csv.gz"), index=False, compression="gzip")
        with open(os.path.join(self.out_dir, "REPORT.md"), "w") as f:
            f.write(self.report())

    def report(self):
        rs = lambda x: "-" if x is None or (isinstance(x, float) and not np.isfinite(x)) else ("-" if x < 0 else "+") + f"{abs(x):,.0f}"  # noqa: E731
        pc = lambda x: "-" if x is None or not np.isfinite(x) else f"{x * 100:.0f}%"  # noqa: E731
        L = [f"# obuy run '{self.name}'", "",
             f"{len(self.strategies)} strategies, {len(self.vids)} variants, {len(self.all_days)} trading days "
             f"({self.all_days[0] if self.all_days else '-'} .. {self.all_days[-1] if self.all_days else '-'}). "
             f"Run time {self.timing['total_s']:.0f}s (data pass {self.timing['prepare_s']:.0f}s, variants "
             f"{self.timing['variants_s']:.0f}s, random baselines {self.timing['baseline_s']:.0f}s, SPA {self.timing['spa_s']:.0f}s).",
             "", f"White's Reality Check p = {self.spa.get('rc_p', np.nan):.3f}, Hansen SPA p = {self.spa.get('spa_p', np.nan):.3f} "
             f"(H0: no variant beats not trading after costs; {len(self.vids)} variants, daily P&L, stationary bootstrap).", ""]
        L += ["## Families (walk-forward out of sample, 1 lot, after costs)", "",
              "| strategy | variants | WF trades | WF net | per year | PF | win | max DD | Sharpe | years + | p random (raw / Holm / BH) | MC P(profit 1y) | MC P(DD>=20%) | best variant net | DSR | PBO | promoted |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for s, r in self.F.iterrows():
            if "wf_trades" not in r or pd.isna(r.get("wf_trades")):
                L.append(f"| {s} | {r.n_variants} | 0 | | | | | | | | | | | | | | no |")
                continue
            L.append(f"| {s} | {r.n_variants} | {int(r.wf_trades)} | {rs(r.wf_net)} | {rs(r.wf_per_year)} | {r.wf_pf:.2f} | {pc(r.wf_win)} | "
                     f"{rs(r.wf_dd)} | {r.wf_sharpe:.2f} | {int(r.wf_years_pos)}/{int(r.wf_years)} | {r.p_rand:.3f} / {r.p_rand_holm:.3f} / {r.p_rand_bh:.3f} | "
                     f"{pc(r.mc_p_profit)} | {pc(r.mc_p_dd20)} | {rs(r.best_net)} | {r.best_dsr:.2f} | {pc(r.pbo)} | {'**yes**' if r.promoted else 'no'} |")
        L += ["", "Walk-forward picks: " + "; ".join(f"{s}: {r.picks}" for s, r in self.F.iterrows() if isinstance(r.get("picks"), str)), ""]
        L += ["## Top variants (full sample, in-sample - selection-biased, for orientation only)", "",
              "| variant | trades | net | per year | PF | win | avg R | max DD | worst month | Sharpe | years + | p random | BH q | Holm |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for vid, r in self.V.sort_values("net", ascending=False).head(25).iterrows():
            L.append(f"| {vid} {r.rule} {r.exits[:60]} {r.sig[:60]} | {r.trades} | {rs(r.net)} | {rs(r.per_year)} | {r.pf:.2f} | {pc(r.win)} | "
                     f"{r.avg_r:+.2f} | {rs(r.max_dd)} | {rs(r.worst_month)} | {r.sharpe:.2f} | {r.years_pos}/{r.years} | {r.p_rand:.3f} | "
                     f"{r.p_rand_bh:.3f} | {r.p_rand_holm:.3f} |")
        return "\n".join(L) + "\n"
