"""h19 analysis on sim.py's trades (app paper fills + SandboxCosts, 1 lot each):
  1. per-arm daily P&L, by month / quarter / year, recent windows (1/3/6 months to the last date)
  2. is the recent window different from the long run? (block-bootstrap of windows, iid permutation)
  3. how often does an arm with its long-run edge show a 1-3 month winning streak by chance?
  4. correlation of daily P&L between arms
  5. day-level profit protection (Boss's "it was up 3-4k and ended negative"): giveback statistics and 16
     pre-registered rules chosen on pre-2025-10-01 data only, holdout once, vs random-trigger placebo
  6. forward record needed to confirm / reject an arm
Writes <scratch>/hunt/h19/analysis.json and prints tables.
"""
from __future__ import annotations

import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h19")
COST = Costs("app")
ARMS = ["orb", "orb_fresh", "orb_sweep", "range_fade", "liq_bn", "liq_fin"]
OLD = ["orb", "orb_fresh", "orb_sweep", "range_fade"]
HOLD = pd.Timestamp("2025-10-01")
RNG = np.random.default_rng(19)


def load():
    tr = pd.read_parquet(os.path.join(OUT, "trades.parquet"))
    with open(os.path.join(OUT, "paths.pkl"), "rb") as f:
        paths = pickle.load(f)
    return tr, paths


def daily(tr, days):
    D = tr.pivot_table(index="day", columns="arm", values="net", aggfunc="sum").reindex(days).fillna(0.0)
    for a in ARMS:
        if a not in D:
            D[a] = 0.0
    return D[ARMS]


# ------------------------------------------------------------------ 2/3: recent vs long run, streaks
def block_windows(x, L, nboot=4000, mean_block=10):
    """Stationary-bootstrap distribution of an L-day window's mean under the long-run process."""
    n = len(x)
    out = np.empty(nboot)
    for b in range(nboot):
        idx = np.empty(L, int)
        i = RNG.integers(n)
        for k in range(L):
            if k and RNG.random() < 1 / mean_block:
                i = RNG.integers(n)
            idx[k] = i
            i = (i + 1) % n
        out[b] = x[idx].mean()
    return out


def recent_tests(D, last):
    res = {}
    for a in ARMS:
        x = D[a].values
        r = {}
        for lab, L in (("1m", 21), ("3m", 63), ("6m", 126)):
            w = x[-L:]
            rest = x[:-L]
            boot = block_windows(rest, L)
            # permutation: is the window's mean different from the rest's (iid days)?
            allx = x.copy()
            obs = w.mean() - rest.mean()
            perm = np.empty(4000)
            for b in range(4000):
                p = RNG.permutation(allx)
                perm[b] = p[-L:].mean() - p[:-L].mean()
            # empirical: share of all historical L-day windows (rolling, before the window) with sum >= observed
            roll = pd.Series(rest).rolling(L).sum().dropna().values
            r[lab] = dict(window_sum=float(w.sum()), window_per_day=float(w.mean()), rest_per_day=float(rest.mean()),
                          p_boot_ge=float((boot >= w.mean()).mean()),     # chance the long-run process gives >= this
                          p_perm_two=float((np.abs(perm) >= abs(obs)).mean()),
                          share_hist_windows_ge=float((roll >= w.sum()).mean()) if len(roll) else None,
                          share_hist_windows_pos=float((roll > 0).mean()) if len(roll) else None)
        res[a] = r
    return res


def streaks(D, M):
    """Chance of a winning month / k-month winning streak for each arm, empirical and under its long-run bootstrap."""
    out = {}
    for a in ARMS:
        m = M[a].values
        x = D[a].values
        emp = {k: float(np.mean([all(m[i:i + k] > 0) for i in range(len(m) - k + 1)])) for k in (1, 2, 3)}
        sim = {1: 0, 2: 0, 3: 0}
        any3in60 = 0
        nb = 2000
        for b in range(nb):
            # 3 consecutive 21-day months from the stationary bootstrap
            idx = []
            i = RNG.integers(len(x))
            for k in range(63):
                if k and RNG.random() < 0.1:
                    i = RNG.integers(len(x))
                idx.append(i); i = (i + 1) % len(x)
            s = x[idx].reshape(3, 21).sum(axis=1)
            for k in (1, 2, 3):
                sim[k] += all(s[:k] > 0)
        out[a] = dict(months=len(m), months_pos=int((m > 0).sum()), emp_streak=emp,
                      boot_streak={k: v / nb for k, v in sim.items()})
        # chance of at least one 3-month winning streak somewhere in 60 months (empirical frequency per start x 58)
    return out


# ------------------------------------------------------------------ 5: day-level protection
def day_paths(tr, paths):
    """Per day: list of trade dicts with mtm arrays, and the combined MTM curve."""
    out = {}
    for d, g in tr.groupby("day"):
        items = []
        v = np.zeros(C.W)
        for t in g.itertuples():
            i0, i1 = t.entry_min - C.OPEN_M, t.exit_min - C.OPEN_M
            c = pd.Series(paths[t.tid][0]).ffill().fillna(t.entry).values
            o = pd.Series(paths[t.tid][1]).ffill().fillna(t.entry).values
            m = np.zeros(C.W)
            m[i0:i1] = (c[:i1 - i0] - t.entry) * t.lot - t.buy_chg
            m[i1:] = t.net
            v += m
            items.append((i0, i1, t.entry, t.lot, t.buy_chg, t.net, t.gross, c, o, t.und))
        out[d] = (items, v)
    return out


def close_value(it, T):
    """Net of a trade force-closed at minute T's decision (sold at T+1's open, -5 bps, app charges)."""
    i0, i1, e, lot, bchg, net, gross, c, o, und = it
    j = T + 1 - i0
    p = o[j] if j < len(o) else c[min(T - i0, len(c) - 1)]
    x = float(adverse_bps(np.array([p]), 5, False)[0])
    return (x - e) * lot - bchg - COST.charge_exact(False, x, lot, bse=(und == "SENSEX"))


def apply_rule(items, v, X, kind, T_forced=None):
    """Day P&L under a rule. kind: 'stop' (no new entries once MTM >= X), 'half' (once peak >= X, flat + stop when MTM <=
    X/2), 'be' (same at 0), 'trail' (flat + stop when MTM <= 50% of the running peak, once peak >= X).
    T_forced: placebo - the rule's action ('stop' or 'flat') at this minute regardless of the curve.
    Returns (pnl, fired_minute or None)."""
    T = None
    if T_forced is not None:
        T = T_forced
    elif kind == "stop":
        hit = np.nonzero(v >= X)[0]
        T = int(hit[0]) if len(hit) else None
    else:
        pk = np.maximum.accumulate(v)
        armed = pk >= X
        if kind == "half":
            fl = np.full(C.W, X / 2)
        elif kind == "be":
            fl = np.zeros(C.W)
        else:
            fl = 0.5 * pk
        hit = np.nonzero(armed & (v <= fl))[0]
        T = int(hit[0]) if len(hit) else None
    if T is None:
        return float(sum(it[5] for it in items)), None
    tot = 0.0
    for it in items:
        i0, i1 = it[0], it[1]
        if i0 > T:
            continue                              # no new entries after T
        if kind == "stop" or i1 <= T:
            tot += it[5]
        else:
            tot += close_value(it, T)
    return tot, T


def protection(DP, days, last_pre):
    kinds = ("stop", "half", "be", "trail")
    Xs = (2000, 3000, 4000, 5000)
    base = pd.Series({d: sum(it[5] for it in DP[d][0]) if d in DP else 0.0 for d in days})
    rows = []
    res = {}
    for kind in kinds:
        for X in Xs:
            pnl, fired = {}, {}
            for d in days:
                if d not in DP:
                    pnl[d] = 0.0
                    continue
                p, T = apply_rule(DP[d][0], DP[d][1], X, kind)
                pnl[d] = p
                if T is not None:
                    fired[d] = T
            res[(kind, X)] = (pd.Series(pnl), fired)
    # placebo: same action on randomly chosen days at the rule's own trigger minutes (matched count per period)
    out = []
    for (kind, X), (s, fired) in res.items():
        for per, sel in (("pre", base.index < HOLD), ("hold", base.index >= HOLD)):
            b, r = base[sel], s[sel]
            fd = [d for d in fired if (d < HOLD) == (per == "pre")]
            Ts = [fired[d] for d in fd]
            delta = (r - b).sum()
            pl = []
            pool = [d for d in b.index if d in DP]
            for _ in range(200):
                ds = RNG.choice(len(pool), size=len(fd), replace=False) if len(fd) <= len(pool) else []
                dd = 0.0
                for j, T in zip(ds, RNG.permutation(Ts) if Ts else []):
                    d = pool[j]
                    p, _ = apply_rule(DP[d][0], DP[d][1], X, "stop" if kind == "stop" else "flat", T_forced=int(T))
                    dd += p - b[d]
                pl.append(dd)
            pl = np.array(pl)
            m = r.groupby(r.index.to_period("M")).sum()
            mb = b.groupby(b.index.to_period("M")).sum()
            out.append(dict(rule=kind, X=X, period=per, days=len(r), fired=len(fd), per_day=r.mean(), base_per_day=b.mean(),
                            delta_total=delta, placebo_delta_mean=pl.mean() if len(pl) else np.nan,
                            p_vs_placebo=float((1 + (pl >= delta).sum()) / (len(pl) + 1)),
                            worst_day=r.min(), base_worst_day=b.min(), p_loss_day=(r < 0).mean(),
                            base_p_loss_day=(b < 0).mean(), p_loss_month=(m < 0).mean(), base_p_loss_month=(mb < 0).mean()))
    return pd.DataFrame(out), res, base


def main():
    tr, paths = load()
    tr = tr[tr.day <= "2026-10-05"]           # full days only for the long-run stats (6 Oct is partial)
    ix_days = sorted(pd.to_datetime(tr.day.unique()))
    # all BANKNIFTY sessions with data (days with no trade count as 0)
    import obuy.data as OD
    mk = OD.market()
    allbn = [pd.Timestamp(d) for d in mk.index("BANKNIFTY").days if pd.Timestamp(d) >= tr.day.min() and pd.Timestamp(d) <= pd.Timestamp("2026-10-05")]
    days = pd.DatetimeIndex(sorted(set(allbn) | set(ix_days)))
    D = daily(tr, days)
    D["old4"] = D[OLD].sum(axis=1)
    D["liq"] = D[["liq_bn", "liq_fin"]].sum(axis=1)
    D["all"] = D["old4"] + D["liq"]
    M = D.groupby(D.index.to_period("M")).sum()
    Q = D.groupby(D.index.to_period("Q")).sum()
    Y = D.groupby(D.index.year).sum()
    pd.set_option("display.width", 250)
    print("TRADES/NET by arm", tr.groupby("arm").agg(n=("net", "size"), net=("net", "sum"), gross=("gross", "sum")).round(0))
    print("\nYEAR\n", Y.round(0).to_string())
    print("\nQUARTER\n", Q.round(0).to_string())
    print("\nMONTH (last 18)\n", M.tail(18).round(0).to_string())
    rt = recent_tests(D[ARMS], days[-1])
    st = streaks(D[ARMS], M[ARMS])
    corr = D[ARMS].corr().round(3)
    print("\nCORR daily\n", corr.to_string())
    # conditional: on Liquidity's best days (top decile) what did the old arms do
    q = D["liq"].quantile(0.9)
    cond = dict(liq_top10_days=int((D.liq >= q).sum()), old4_on_liq_top10=float(D.old4[D.liq >= q].mean()),
                old4_other=float(D.old4[D.liq < q].mean()),
                corr_old4_liq=float(D.old4.corr(D.liq)),
                share_all_top20_days_old4_pos=float((D.old4[D["all"] >= D["all"].quantile(0.8)] > 0).mean()))
    print("\nCOND", cond)
    # 6: forward record needed (daily and per-trade sd)
    fwd = {}
    for a in ARMS:
        x = D[a].values
        t = tr[tr.arm == a].net.values
        mu, sd = x.mean(), x.std(ddof=1)
        tpd = len(t) / len(x)
        # days for 80% power, one-sided 5%: n = ((1.645+0.84) sd / |mu|)^2 (to tell the long-run edge from 0)
        n80 = ((1.645 + 0.84) * sd / abs(mu)) ** 2 if mu else np.inf
        # days until a 95% CI on the mean excludes +Rs 500/day (a useful edge) if the truth is mu
        fwd[a] = dict(mean_day=mu, sd_day=sd, trades_per_day=tpd, mean_trade=t.mean(), sd_trade=t.std(ddof=1),
                      days_80pct_power=n80, trades_80pct_power=n80 * tpd,
                      p_positive_after_20d=float(_p_pos(x, 20)), p_positive_after_60d=float(_p_pos(x, 60)))
    print("\nFWD\n", pd.DataFrame(fwd).T.round(2).to_string())
    print("\nRECENT\n", json.dumps(rt, indent=1, default=float)[:6000])
    print("\nSTREAK\n", json.dumps(st, indent=1, default=float))
    # 5: protection
    DP = day_paths(tr, paths)
    vv = {d: v for d, (it, v) in DP.items()}
    gb = []
    for d, v in vv.items():
        gb.append(dict(day=d, peak=v.max(), close=v[-1], trough=v.min(), giveback=v.max() - v[-1]))
    G = pd.DataFrame(gb).set_index("day")
    gstats = {}
    for X in (2000, 3000, 4000, 5000):
        s = G[G.peak >= X]
        gstats[X] = dict(days=len(s), share_of_days=len(s) / len(days), median_close=float(s.close.median()) if len(s) else None,
                         mean_giveback=float(s.giveback.mean()) if len(s) else None,
                         closed_negative=float((s.close < 0).mean()) if len(s) else None,
                         closed_below_half=float((s.close < X / 2).mean()) if len(s) else None)
    print("\nGIVEBACK (all arms, 1 lot each)\n", pd.DataFrame(gstats).T.round(2).to_string())
    P, res, base = protection(DP, days, None)
    print("\nPROTECTION\n", P.round(3).to_string())
    pre = P[P.period == "pre"].sort_values("per_day", ascending=False)
    pick = pre.iloc[0]
    print("\nPICK (best pre-holdout Rs/day):", pick.rule, pick.X)
    print(P[(P.rule == pick.rule) & (P.X == pick.X)].round(3).to_string())
    # the named days
    named = {}
    for ds in ("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-05"):
        d = pd.Timestamp(ds)
        if d not in DP:
            continue
        rr = {f"{k}{X}": round(res[(k, X)][0][d], 0) for (k, X) in res}
        named[ds] = dict(base=round(base[d], 0), peak=round(G.peak[d], 0), **rr)
    print("\nNAMED DAYS\n", pd.DataFrame(named).T.to_string())
    D.to_csv(os.path.join(OUT, "daily.csv"))
    M.to_csv(os.path.join(OUT, "monthly.csv"))
    P.to_csv(os.path.join(OUT, "protection.csv"))
    G.to_csv(os.path.join(OUT, "giveback.csv"))
    with open(os.path.join(OUT, "analysis.json"), "w") as f:
        json.dump(dict(recent=rt, streak=st, cond=cond, fwd=fwd, giveback=gstats, named=named,
                       corr=corr.to_dict()), f, default=float, indent=1)


def _p_pos(x, n, nb=4000):
    """Chance an n-day stretch of this arm sums > 0 (stationary bootstrap of its own long-run days)."""
    c = 0
    for _ in range(nb):
        idx = []
        i = RNG.integers(len(x))
        for k in range(n):
            if k and RNG.random() < 0.1:
                i = RNG.integers(len(x))
            idx.append(i); i = (i + 1) % len(x)
        c += x[idx].sum() > 0
    return c / nb


if __name__ == "__main__":
    main()
