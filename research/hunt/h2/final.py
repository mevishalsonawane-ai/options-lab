"""Selection on the in-sample only, overfit statistics, then ONE holdout run of the chosen rule.

    python3 -I research/hunt/h2/final.py
Pre-committed selection: among variants with >= 100 in-sample trades and at most 10 positions open at once, the highest
in-sample NET daily Sharpe computed after dropping the single best day (so one crash-day windfall cannot pick the rule).
"""
import ast
import json
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(1, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import lib  # noqa: E402
import strategies as ST  # noqa: E402
from obuy import overfit as OF  # noqa: E402

OUT = os.path.join(lib.SCR, "hunt", "h2")
NOT = 500_000.0
R = {}


def score(x):
    x = np.sort(np.asarray(x))[:-1]
    sd = x.std(ddof=1)
    return x.mean() / sd * np.sqrt(250) if sd > 0 else -9


df = pd.read_csv(os.path.join(OUT, "ins_variants.csv"))
NET = np.load(os.path.join(OUT, "ins_daily_net.npy"))
GRO = np.load(os.path.join(OUT, "ins_daily_gross.npy"))
idays = pd.to_datetime(np.load(os.path.join(OUT, "ins_days.npy")))
ok = (df.n >= 100) & (df.max_conc <= 10)
df["score"] = [score(NET[:, i]) if ok[i] else -9 for i in range(len(df))]
pick = int(df.score.idxmax())
R["n_variants"] = len(df)
R["pick"] = dict(df.loc[pick].drop("params").to_dict(), params=df.params[pick], fam=df.fam[pick])
print("PICK", df.loc[pick].to_dict())
print(df.sort_values("score", ascending=False).head(12)[["vid", "fam", "params", "n", "net_bps", "gross_bps",
                                                          "net_day", "score", "max_conc"]].to_string())

# ---- monthly anchored walk-forward inside the in-sample (train >= 3 months)
mon = idays.to_period("M")
months = mon.unique()
wf_net, wf_gro, wf_pick = [], [], []
for mi in range(3, len(months)):
    tr = (mon < months[mi])
    sc = np.array([score(NET[tr, i]) if (ok[i]) else -9 for i in range(len(df))])
    b = int(sc.argmax())
    te = (mon == months[mi])
    wf_net.append(NET[te, b].sum())
    wf_gro.append(GRO[te, b].sum())
    wf_pick.append((str(months[mi]), b, df.fam[b], df.params[b], round(NET[te, b].sum()), round(GRO[te, b].sum())))
R["wf"] = wf_pick
R["wf_total_net"] = float(sum(wf_net))
R["wf_total_gross"] = float(sum(wf_gro))
print("WF", *wf_pick, sep="\n")

# ---- multiple testing on the in-sample net daily matrix
T = NET.shape[0]
mu, sd = NET.mean(0), NET.std(0, ddof=1)
from scipy import stats as sst  # noqa: E402
t = np.where(sd > 0, mu / sd * np.sqrt(T), 0)
p = 1 - sst.t.cdf(t, T - 1)
q = OF.bh(p)
R["bh_min_q"] = float(q.min())
R["bh_pick_q"] = float(q[pick])
R["n_bh_005"] = int((q < 0.05).sum())
sp = OF.spa(NET, B=1000)
spg = OF.spa(GRO, B=1000)
R["spa_net"] = sp
R["spa_gross"] = spg
pb = OF.pbo(NET, S=12)
R["pbo"] = pb
srs = np.where(sd > 0, mu / sd, 0)
R["dsr"] = OF.dsr(NET[:, pick], srs)
print("BH", R["bh_min_q"], R["bh_pick_q"], "SPA", sp, "SPA gross", spg, "PBO", pb, "DSR", R["dsr"])

# ---- the holdout, once
m = lib.Mkt()
lab = np.load(os.path.join(OUT, "clusters.npy"))
fam, prm = df.fam[pick], ast.literal_eval(df.params[pick])
rng = np.random.default_rng(11)


def run(dmask, impact=False, notional=NOT):
    sig = ST.build(m, fam, prm, dmask, lab)
    tr = lib.simulate(m, sig["s"], sig["d"], sig["te"], sig["stop"], sig["tgt"], sig["tx"])
    keep = np.isfinite(tr.ent) & np.isfinite(tr.ex) & (tr.ent > 0)
    tr = lib.pnl(m, tr[keep], notional, impact)
    sig = {k: v[keep.to_numpy()] for k, v in sig.items()}
    return tr, sig


def random_base(tr, sig, B=300):
    """Same days, entry bars, count, stop distance (%), target rule (R multiple) and time exit; random liquid stock."""
    out = []
    ent = tr.ent.to_numpy()
    sdist = 1 - sig["stop"] / ent
    tdist = sig["tgt"] / ent - 1
    for _ in range(B):
        ss = np.empty(len(tr), np.int64)
        for i, d in enumerate(tr.d.to_numpy()):
            cand = np.nonzero(m.valid[:, d])[0]
            ss[i] = rng.choice(cand)
        e = m.o[ss, tr.d.to_numpy(), tr.te.to_numpy()]
        e = np.where(np.isfinite(e), e, m.cf[ss, tr.d.to_numpy(), tr.te.to_numpy() - 1])
        x = lib.simulate(m, ss, tr.d, tr.te, e * (1 - sdist), e * (1 + tdist), sig["tx"])
        x = x[np.isfinite(x.ent) & np.isfinite(x.ex)]
        x = lib.pnl(m, x, NOT)
        out.append((x.gross / x.notional).mean() * 1e4)
    out = np.array(out)
    real = (tr.gross / tr.notional).mean() * 1e4
    return dict(real_gross_bps=float(real), null_mean=float(out.mean()), null_p95=float(np.quantile(out, 0.95)),
                p=float((1 + (out >= real).sum()) / (B + 1)))


def daily(tr, dm, col):
    s = tr.groupby("d")[col].sum()
    return pd.Series(s.reindex(np.nonzero(dm)[0]).fillna(0).to_numpy(), index=pd.to_datetime(m.days[dm]))


def summ(tr, dm, tag):
    out = {}
    for col in ("gross", "net"):
        s = daily(tr, dm, col)
        eq = s.cumsum()
        mo = s.resample("ME").sum()
        # bootstrap probability of a losing month: 21-day blocks resampled from the daily series
        bs = np.array([s.to_numpy()[rng.integers(0, len(s), 21)].sum() for _ in range(5000)])
        out[col] = dict(total=float(s.sum()), per_day=float(s.mean()), sharpe=float(s.mean() / s.std() * np.sqrt(250)),
                        worst_day=float(s.min()), worst_day_date=str(s.idxmin().date()), best_day=float(s.max()),
                        best_day_date=str(s.idxmax().date()), worst_month=float(mo.min()),
                        worst_month_id=str(mo.idxmin().date())[:7], losing_months=int((mo < 0).sum()),
                        months=len(mo), maxdd=float((eq.cummax() - eq).max()), p_losing_month=float((bs < 0).mean()),
                        total_ex_best_day=float(s.sum() - s.max()),
                        by_year={str(k): float(v) for k, v in s.groupby(s.index.year).sum().items()},
                        by_month={str(k.date())[:7]: round(float(v)) for k, v in mo.items()})
    out["trades"] = len(tr)
    out["days"] = int(dm.sum())
    out["trade_days"] = int(tr.d.nunique())
    out["gross_bps"] = float((tr.gross / tr.notional).mean() * 1e4)
    out["net_bps"] = float((tr.net / tr.notional).mean() * 1e4)
    out["win"] = float((tr.net > 0).mean())
    out["exit_mix"] = tr.why.value_counts(normalize=True).round(3).to_dict()
    print(tag, json.dumps({k: v for k, v in out.items()}, default=str)[:1500])
    return out


ins = m.ism.copy()
hold = ~m.ism
tri, sgi = run(ins)
R["ins"] = summ(tri, ins, "INS")
R["ins_random"] = random_base(tri, sgi)
print("INS random", R["ins_random"])
trh, sgh = run(hold)
R["hold"] = summ(trh, hold, "HOLD")
R["hold_random"] = random_base(trh, sgh)
print("HOLD random", R["hold_random"])

# ---- sizing for Rs 5,000/day net (with the size-dependent impact term), on the holdout and on the in-sample
for tag, dm in (("ins", ins), ("hold", hold)):
    rows = []
    for X in (2e5, 5e5, 1e6, 2e6, 5e6, 1e7, 2e7):
        tr, _ = run(dm, impact=True, notional=X)
        s = daily(tr, dm, "net")
        g = daily(tr, dm, "gross")
        ev = pd.concat([pd.DataFrame(dict(d=tr.d, t=tr.te, x=1)), pd.DataFrame(dict(d=tr.d, t=tr.xb, x=-1))])
        ev = ev.sort_values(["d", "t", "x"])
        conc = ev.groupby("d").x.cumsum().groupby(ev.d).max()
        eq = s.cumsum()
        rows.append(dict(notional=X, net_day=float(s.mean()), gross_day=float(g.mean()), maxdd=float((eq.cummax() - eq).max()),
                         worst_day=float(s.min()), max_conc=int(conc.max()), med_conc=float(conc.median()),
                         margin_needed=float(conc.max() * X / 5)))
    R[f"size_{tag}"] = rows
    print(tag, pd.DataFrame(rows).round(0).to_string())

json.dump(R, open(os.path.join(OUT, "final.json"), "w"), indent=1, default=str)
