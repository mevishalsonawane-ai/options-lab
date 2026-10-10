"""h35: option-BUYING tests of the app's untested support / resistance levels, through the obuy Lab (app fills and
costs, 1-ITM nearest expiry, same-exit random baseline, BH, walk-forward, SPA). The Lab's `net` here is net REAL:
app net minus the real half-spread (research/HUNT_H24.md) on both legs; `net_app` keeps the app-only net.

    OBUY_CACHE=<scratch>/hunt/h35/cache flock <scratch>/obuy.lock python3 -I research/hunt/h35/run.py pre
    OBUY_CACHE=<scratch>/hunt/h35/cache flock <scratch>/obuy.lock python3 -I research/hunt/h35/run.py hold
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import lab as LABM  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule  # noqa: E402
from obuy.lab import Lab  # noqa: E402
from obuy.strategies.base import Strategy  # noqa: E402
from obuy.strategies import liquidity as LQ  # noqa: E402
from obuy.data import market  # noqa: E402

HOLD = pd.Timestamp("2025-10-01").date()
OUT = os.path.join(C.CACHE, "h35")
LOGD = os.environ.get("H35_LOG", os.path.join(C.SCRATCH, "hunt/h35"))
FAMS = ("pivD", "pivW", "brain", "struct", "maxoi", "maxpain", "round", "vix")
MODES = ("brk", "rt", "bo")
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}
HALF = {"BANKNIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "NIFTY": 0.0016, "FINNIFTY": 0.0042, "SENSEX": 0.0020}
SQ = 15 * 60 + 10

EXITS = [Exits(stop_pts=s, tgt_pts=t, sig_levels=False) for t in (15, 20, 25, 30) for s in (10, 15, 20)]   # x0..x11
EXITS += [LQ.ARM_EXITS]                                                                                       # x12
EXITS += [Exits(stop_pts=40, tgt_pts=40, ladder=LADDER, ladder_ref_pts=40, sig_levels=False)]               # x13
EXITS += [Exits(time_stop=m, time_gain=None, sig_levels=False) for m in (15, 30, 60)]                        # x14..16
XNAME = [f"+{t}/-{s}" for t in (15, 20, 25, 30) for s in (10, 15, 20)] + ["Liquidity arm", "ladder -40/+40", "time 15", "time 30", "time 60"]


def spread(tr, k=1.0):
    if not len(tr):
        return tr
    h = tr.und.map(HALF).astype(float) * k
    return h * (tr.entry.astype(float) + tr.exit.astype(float)) * tr.qty.astype(float)


def to_real(tr):
    if tr is None or not len(tr) or "net_app" in tr:
        return tr
    tr = tr.copy()
    tr["net_app"] = tr.net
    tr["net"] = tr.net - spread(tr)
    return tr


# the Lab analyses net REAL: wrap positions() (real trades) and pool_trades() (random alternatives)
_pos = LABM.positions
LABM.positions = lambda tr, **kw: to_real(_pos(tr, **kw))
LABM.KEEP = list(LABM.KEEP) + (["net_app"] if "net_app" not in LABM.KEEP else [])


class RLab(Lab):
    def pool_trades(self, vid):
        if vid not in self.pools:
            self.pools[vid] = to_real(super().pool_trades(vid))
        return self.pools[vid]

    def _write(self):      # small outputs only (disk is tight)
        self.V.to_csv(os.path.join(self.out_dir, "variants.csv"))
        self.F.to_csv(os.path.join(self.out_dir, "families.csv"))
        with open(os.path.join(self.out_dir, "REPORT.md"), "w") as f:
            f.write(self.report())


def signals(mk, fam="pivD", mode="brk", period="pre"):
    E = pd.read_parquet(os.path.join(os.environ.get("OBUY_CACHE", C.CACHE), "h35", "events.parquet"))
    E = E[(E.fam == fam) & (E["mode"] == mode)]
    E = E[E.day < HOLD] if period == "pre" else E[E.day >= HOLD]
    # thinning (declared in the report): only the first 6 signals of a book-day can matter much under max 3 a day,
    # one at a time; later ones are dropped to keep the data pass in memory
    E = E.sort_values(["und", "day", "sig_min"])
    E = E[E.groupby(["und", "day"]).cumcount() < 6]
    out = [dict(und=r.und, day=r.day, sig_min=int(r.sig_min), side=int(r.side), book=f"{fam}_{mode}_{r.und}",
                idx_stop=float(r.lev - r.side * ISTOP[r.und])) for r in E.itertuples()]
    return pd.DataFrame(out)


def strategy(fam, period, modes=MODES, exits=None):
    return Strategy(name=f"h35_{fam}", family=fam, signal_fn=signals,
                    sig_grid=[dict(fam=fam, mode=m, period=period) for m in modes],
                    rules=[StrikeRule(money=1)], exits=exits or EXITS, exe=Execution(expiry="skip"),
                    pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 30, 14 * 60 + 30))


def trading_days(lo, hi):
    mk = market()
    s = set()
    for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"):
        s |= {d for d in mk.index(u).days if lo <= d < hi}
    return sorted(s)


def describe(lab, days, tag):
    """Per-variant extras: gross, app, real, stress; Rs/day over all trading days of the period."""
    rows = []
    nd = len(days)
    for vid, tr in lab.trades.items():
        st, s, r, x = vid.split("|")
        fam = st.replace("h35_", "")
        mode = MODES[int(s[1:])] if tag == "pre" else lab.vinfo[vid]["sig"]
        if not len(tr):
            rows.append(dict(vid=vid, fam=fam, trades=0))
            continue
        stress = tr.net_app - spread(tr, 1.5)
        rows.append(dict(vid=vid, fam=fam, mode=mode, exit=int(x[1:]), trades=len(tr), gross=tr.gross.sum(), net_app=tr.net_app.sum(),
                         net_real=tr.net.sum(), net_stress=stress.sum(), real_day=tr.net.sum() / nd, gross_day=tr.gross.sum() / nd,
                         per_trade_real=tr.net.mean(), win=(tr.net > 0).mean()))
    D = pd.DataFrame(rows).set_index("vid")
    return D.join(lab.V[["p_rand", "rand_mean", "p_rand_bh", "max_dd", "worst_day", "worst_month", "years_pos", "years"]])


def pre(fam):
    os.makedirs(LOGD, exist_ok=True)
    lab = RLab([strategy(fam, "pre")], name=f"h35_pre_{fam}", pool_k=5, B=2000).run()
    days = trading_days(pd.Timestamp("2020-01-01").date(), HOLD)
    D = describe(lab, days, "pre")
    D = D.join(lab.V[["sr_daily"]])
    D["strategy"] = D.index.str.split("|").str[0]
    F = lab.F
    D = D.join(F[["wf_net", "wf_years_pos", "wf_years", "wf_trades", "wf_dd", "p_rand"]].rename(columns={"p_rand": "wf_p"}), on="strategy")
    D.to_csv(os.path.join(LOGD, f"pre_variants_{fam}.csv"))
    dpos = {d: i for i, d in enumerate(days)}
    X = np.zeros((len(days), len(lab.trades)), np.float32)
    pyr = {}
    for k, (vid, tr) in enumerate(lab.trades.items()):
        if len(tr):
            dl = tr.groupby("day").net.sum()
            X[[dpos[d] for d in dl.index], k] = dl.values
            pyr[vid] = {str(a): round(float(b)) for a, b in tr.groupby("year").net.sum().items()}
            # how often the 6-signal thinning could bind: trades taken on the 6th signal of a book-day
    np.savez_compressed(os.path.join(LOGD, f"pre_X_{fam}.npz"), X=X, vids=np.array(list(lab.trades)))
    json.dump(dict(per_year=pyr, wf=lab.oos and {k: v[0] for k, v in lab.oos.items()}), open(os.path.join(LOGD, f"pre_py_{fam}.json"), "w"), default=str)
    print(D.sort_values("net_real", ascending=False).head(8)[["mode", "exit", "trades", "gross", "net_app", "net_real", "real_day", "p_rand", "wf_net"]].round(3).to_string())


def combine():
    from obuy import overfit as OF
    Ds, Xs, vids = [], [], []
    for fam in FAMS:
        Ds.append(pd.read_csv(os.path.join(LOGD, f"pre_variants_{fam}.csv"), index_col=0))
        z = np.load(os.path.join(LOGD, f"pre_X_{fam}.npz"))
        Xs.append(z["X"]); vids += list(z["vids"])
    D = pd.concat(Ds)
    X = np.concatenate(Xs, axis=1).astype(float)
    D["p_bh_all"] = OF.bh(D.p_rand.fillna(1.0).values)
    spa = OF.spa(X, B=1000)
    D["xname"] = [XNAME[int(e)] if e == e else "" for e in D.exit]
    D["S1"] = D.net_real > 0
    D["S2"] = D.p_bh_all < 0.10
    D["S3"] = (D.wf_net > 0) & (D.wf_years_pos > D.wf_years / 2)
    D["S4"] = spa["spa_p"] < 0.10
    D["survive"] = D.S1 & D.S2 & D.S3 & D.S4
    D.to_csv(os.path.join(LOGD, "pre_variants.csv"))
    surv = list(D[D.survive].sort_values("net_real", ascending=False).index)
    json.dump(dict(spa={k: float(v) if np.isscalar(v) else str(v) for k, v in spa.items()}, n_variants=len(D), survivors=surv,
                   best_by_real=D.net_real.idxmax(), n_S1=int(D.S1.sum()), n_S2=int(D.S2.sum()), n_S3=int(D.S3.sum()),
                   n_rawp05=int((D.p_rand < 0.05).sum())),
              open(os.path.join(LOGD, "pre_summary.json"), "w"), indent=1)
    pd.set_option("display.width", 250)
    print("SPA", spa)
    print(D.sort_values("net_real", ascending=False).head(25)[["fam", "mode", "xname", "trades", "gross", "net_app", "net_real", "net_stress", "real_day", "p_rand", "p_bh_all", "wf_net", "wf_years_pos", "survive"]].round(3).to_string())
    print(D.groupby(["fam", "mode"]).net_real.agg(["max", "median", "min"]).round(0).to_string())


def hold():
    S = json.load(open(os.path.join(LOGD, "pre_summary.json")))
    pick = S["survivors"][:3] or [S["best_by_real"]]
    D = pd.read_csv(os.path.join(LOGD, "pre_variants.csv"), index_col=0)
    strats = []
    for v in pick:
        fam, mode, x = D.loc[v, "fam"], D.loc[v, "mode"], int(D.loc[v, "exit"])
        st = strategy(fam, "hold", modes=(mode,), exits=[EXITS[x]])
        st.name = f"h35_{fam}_{mode}_x{x}"
        strats.append(st)
    lab = RLab(strats, name="h35_hold", pool_k=5, B=2000, pool_all=True).run()
    days = trading_days(HOLD, pd.Timestamp("2100-01-01").date())
    out = {}
    for vid, tr in lab.trades.items():
        if not len(tr):
            out[vid] = dict(trades=0)
            continue
        d = tr.groupby("day").net.sum().reindex(days, fill_value=0.0)
        eq = d.cumsum()
        dd = float((eq - eq.cummax()).min())
        mon = tr.groupby(pd.to_datetime(tr.day).dt.to_period("M")).net.sum()
        rd = tr.net.sum() / len(days)
        out[vid] = dict(trades=len(tr), gross=tr.gross.sum(), net_app=tr.net_app.sum(), net_real=tr.net.sum(),
                        net_stress=(tr.net_app - spread(tr, 1.5)).sum(), real_day=rd, gross_day=tr.gross.sum() / len(days),
                        lots_for_5000=(5000 / rd) if rd > 0 else None, max_dd=dd, worst_day=float(d.min()), worst_month=float(mon.min()),
                        losing_months=f"{int((mon < 0).sum())}/{len(mon)}", p_rand=float(lab.V.loc[vid, "p_rand"]), days=len(days))
    json.dump(dict(picked=pick, results=out), open(os.path.join(LOGD, "hold_summary.json"), "w"), indent=1, default=float)
    print(json.dumps(out, indent=1, default=float))


if __name__ == "__main__":
    if sys.argv[1] == "pre":
        pre(sys.argv[2])
    elif sys.argv[1] == "combine":
        combine()
    else:
        hold()
