"""x2: the 5 pre-registered combined rules (see PREREG.md). Option BUYING only, 1 lot, Rs 1 lakh.

    python3 -I research/hunt/x2/run.py pre     # PRE only (< 2025-10-01): tests, baselines, BH/SPA, walk-forward
    python3 -I research/hunt/x2/run.py hold    # locked holdout, ONCE (writes a flag; refuses a second run)

Outcome tables are reused from validated studies (h44 Liquidity trades + features, h31 overnight CE/PE every night,
h26 option OI panels). No entry/exit is re-simulated here; rules only choose WHICH rows are traded.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, timedelta

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt"
OUT = f"{S}/x2"
HOLD = pd.Timestamp("2025-10-01")
HS = {"NIFTY": .0016, "BANKNIFTY": .0016, "FINNIFTY": .0042, "MIDCPNIFTY": .0021, "SENSEX": .0020}
RULES = ["R1", "R2", "R3", "R4", "R5"]
B = 5000


# ---------------------------------------------------------------- data
def load_liq():
    T = pd.read_parquet(f"{S}/h44/trades44.parquet")
    X = pd.read_parquet(f"{S}/h44/feats44.parquet")
    assert len(T) == len(X) and np.allclose(T.sig_min.values, X.tod.values)
    T = pd.concat([T.reset_index(drop=True), X.reset_index(drop=True)], axis=1)
    T["day"] = pd.to_datetime(T.day)
    T["net"] = T["net1_0.02"]
    T["gross"] = T["gross1_0.02"]
    T["prem"] = T["prem1_0.02"]
    T["xmin"] = T["end1_0.02"]
    sp = T.hs * (T.entry + T.exit) * T.lot * T["lots1_0.02"]
    T["net15"] = T.net - 0.5 * sp
    with np.errstate(invalid="ignore", divide="ignore"):
        room = T.room_atr / T.stop_atr
    T["room_st"] = room.fillna(np.inf)                     # no level ahead = infinite room (h7/h12)
    T["oi_against"] = (T.bu15_dir <= -1) | (T.doipc5_dir <= -1)  # NaN -> False
    q = ((T.side == -1).astype(int) + (T.room_st >= 6).astype(int) + (T.gap_dir.abs() >= 0.3).astype(int)
         + (T.vix_chg > 0).astype(int))
    T["quality"] = q
    return T


def load_night():
    f = pd.read_parquet(f"{S}/h31/feat.parquet")
    f["day"] = pd.to_datetime(f.day)
    tr = pd.read_parquet(f"{S}/h31/trades.parquet")
    tr["day"] = pd.to_datetime(tr.day)
    keep = ((tr.und == "NIFTY") & (tr.K == "W")) | ((tr.und == "BANKNIFTY") & (tr.K == "M"))
    tr = tr[keep & (tr.X == "X0916")].copy()
    hs = tr.und.map(HS).values
    sp = (tr.entry.values + tr.exit.values) * tr.qty.values * hs
    tr["net"] = tr.net_app - sp
    tr["net15"] = tr.net_app - 1.5 * sp
    tr["prem"] = tr.efill * tr.qty
    w = tr.pivot_table(index=["und", "day"], columns="side", values=["net", "net15", "gross", "prem"], aggfunc="first")
    w.columns = [f"{a}_{'ce' if b == 1 else 'pe'}" for a, b in w.columns]
    w = w.dropna().reset_index()
    # day-end option OI build-up (h26 BUopen, column 364 = 15:19), z vs the same column over the previous 60 sessions
    bz = []
    for u in ("NIFTY", "BANKNIFTY"):
        z = np.load(f"{S}/h26/cache/h26/opt_{u}.npz")
        days = [pd.Timestamp(date(1970, 1, 1) + timedelta(days=int(x))) for x in z["days"]]
        bu = pd.Series(z["X"][:, 2, 364].astype(float), index=days)
        mu = bu.rolling(60, min_periods=20).mean().shift(1)
        sd = bu.rolling(60, min_periods=20).std().shift(1)
        bz.append(pd.DataFrame(dict(und=u, day=days, buz=((bu - mu) / sd).replace([np.inf, -np.inf], np.nan).values)))
    f = f.merge(pd.concat(bz), on=["und", "day"], how="left")
    w = w.merge(f[["und", "day", "loc", "DAY", "BR", "buz", "fomc", "uscpi"]], on=["und", "day"], how="left")
    loc, day, br = w["loc"].values, w["DAY"].values, w["BR"].values
    r17 = np.where((loc >= .6) & (day >= 0) & (br >= .5), 1, np.where((loc <= .4) & (day <= 0) & (br <= .5), -1, 0))
    w["r17"] = r17
    buz = w.buz.fillna(0).values
    w["r3"] = np.where((r17 != 0) & (r17 * buz >= 0.5), r17, 0)
    votes = ((loc >= .75).astype(int) + (np.nan_to_num(br, nan=.5) >= .6).astype(int) + (buz >= .5).astype(int)
             + (w.fomc.fillna(False).astype(bool) | w.uscpi.fillna(False).astype(bool)).astype(int))
    w["r4"] = np.where(votes >= 2, 1, 0)
    return w


def calendar():
    ix = pd.read_parquet(f"{S}/h40/index_days.parquet")
    ix["day"] = pd.to_datetime(ix.day)
    return ix[["und", "day"]]


# ---------------------------------------------------------------- rules -> trade lists (und, day, net, net15, gross, prem, t0, t1)
def liq_rows(T, mask):
    g = T[mask]
    return pd.DataFrame(dict(und=g.und.values, day=g.day.values, net=g.net.values, net15=g.net15.values,
                             gross=g.gross.values, prem=g.prem.values, t0=g.entry_min.values, t1=g.xmin.values,
                             side=g.side.values))


def night_rows(w, side_col, only_ce=False):
    g = w[w[side_col] != 0]
    s = g[side_col].values
    pick = lambda a, b: np.where(s > 0, g[a].values, g[b].values)  # noqa: E731
    return pd.DataFrame(dict(und=g.und.values, day=g.day.values, net=pick("net_ce", "net_pe"),
                             net15=pick("net15_ce", "net15_pe"), gross=pick("gross_ce", "gross_pe"),
                             prem=pick("prem_ce", "prem_pe"), t0=0, t1=10 ** 6, side=s,
                             alt=pick("net_pe", "net_ce")))


def build(T, W):
    bn = T.und == "BANKNIFTY"
    R = {}
    R["R1"] = liq_rows(T, bn & ~T.oi_against)
    oth = ~bn & (T.quality >= 2) & ~T.oi_against
    R["R2"] = liq_rows(T, bn | oth)
    R["R3"] = night_rows(W, "r3")
    R["R4"] = night_rows(W, "r4")
    R["R5"] = liq_rows(T, bn & ((T.side == -1) | (T.bu15_dir >= 1)))
    base = {"R1": liq_rows(T, bn), "R2": liq_rows(T, bn), "R5": liq_rows(T, bn)}
    unds = {"R1": ["BANKNIFTY"], "R5": ["BANKNIFTY"], "R2": ["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            "R3": ["NIFTY", "BANKNIFTY"], "R4": ["NIFTY", "BANKNIFTY"]}
    return R, base, unds


# ---------------------------------------------------------------- capital walk (Rs 1 lakh, fixed 1 lot)
def walk(rows, cap0=100000.0):
    r = rows.sort_values(["day", "t0"]).reset_index(drop=True)
    eq, open_, took, skipped, ruin, low = cap0, [], [], 0, False, cap0
    cur = None
    for x in r.itertuples():
        if x.day != cur:                              # overnight rows close next morning; intraday close same day
            for (t1, pnl, prem) in open_:
                eq += pnl
            open_, cur = [], x.day
            low = min(low, eq)
            if eq < 25000:
                ruin = True
        if ruin:
            took.append(False); skipped += 1
            continue
        still = []
        for (t1, pnl, prem) in open_:
            if t1 <= x.t0:
                eq += pnl
            else:
                still.append((t1, pnl, prem))
        open_ = still
        tied = sum(p for _, _, p in open_)
        if x.prem > 0 and tied + x.prem > eq:
            took.append(False); skipped += 1
            continue
        open_.append((x.t1, x.net, x.prem)); took.append(True)
    for (t1, pnl, prem) in open_:
        eq += pnl
    return dict(end=eq, low=min(low, eq), skipped=skipped, ruin=ruin, n=len(r))


# ---------------------------------------------------------------- stats
def daily(rows, cal, unds, col="net"):
    c = cal[cal.und.isin(unds)]
    days = pd.DatetimeIndex(sorted(c.day.unique()))
    s = rows.groupby("day")[col].sum() if len(rows) else pd.Series(dtype=float)
    return s.reindex(days, fill_value=0.0)


def mdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(x) else 0.0


def month_stats(d, B=2000, seed=7):
    m = d.groupby(d.index.to_period("M")).sum()
    rng = np.random.default_rng(seed)
    # bootstrap of trading days within a 21-day month
    v = d.values
    sims = rng.choice(v, size=(B, 21)).sum(axis=1)
    return dict(months=len(m), losing=int((m < 0).sum()), worst_month=float(m.min()) if len(m) else 0.0,
                p_lose_month=float((sims < 0).mean()))


def summ(rows, d, d15, dg):
    n = len(d)
    mu, sd = d.mean(), d.std(ddof=1)
    t = mu / sd * np.sqrt(n) if sd > 0 else 0.0
    p = float(stats.t.sf(t, n - 1)) if sd > 0 else 1.0
    yr = d.groupby(d.index.year).sum()
    o = dict(trades=len(rows), days=n, net_day=float(mu), gross_day=float(dg.mean()), net15_day=float(d15.mean()),
             net_total=float(d.sum()), t=float(t), p=p, per_trade=float(rows.net.mean()) if len(rows) else 0.0,
             max_dd=mdd(d.values), worst_day=float(d.min()), years={int(k): round(float(v)) for k, v in yr.items()},
             years_pos=f"{int((yr > 0).sum())}/{int((yr != 0).sum())}",
             lots_for_5k=(5000 / mu) if mu > 0 else None)
    o.update(month_stats(d))
    return o


def anchored_wf(d):
    yr = d.groupby(d.index.year).sum()
    tot, used = 0.0, []
    for i, y in enumerate(yr.index):
        if i == 0:
            continue
        if yr.iloc[:i].sum() > 0:
            tot += yr.iloc[i]; used.append(int(y))
    return dict(wf_total=round(float(tot)), wf_years=used)


# ---------------------------------------------------------------- baselines
def base_skip(base, rule, sel_mask_base, rng, B=B):
    """random skipping: keep the same number of rows of base[sel] as the rule kept; rows outside sel always kept."""
    v_all = base.net.values
    sel = sel_mask_base
    fixed = v_all[~sel].sum()
    pool = v_all[sel]
    k = len(rule) - int((~sel).sum())
    k = max(min(k, len(pool)), 0)
    tot_rule = rule.net.sum()
    sims = np.array([fixed + rng.choice(pool, size=k, replace=False).sum() for _ in range(B)])
    return float((sims >= tot_rule).mean()), float(sims.mean())


def baseline(rid, R, base, T, W, rng, period_mask_T, period_mask_W):
    r = R[rid]
    if rid in ("R1",):
        b = base["R1"]
        return base_skip(b, r, np.ones(len(b), bool), rng)
    if rid == "R5":
        b = base["R5"]
        return base_skip(b, r, (b.side == 1).values, rng)
    if rid == "R2":
        Tp = T[period_mask_T]
        bn = Tp[Tp.und == "BANKNIFTY"].net.sum()
        pools = {u: Tp[Tp.und == u].net.values for u in ["NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]}
        ks = {u: int((r.und == u).sum()) for u in pools}
        sims = np.array([bn + sum(rng.choice(pools[u], size=min(ks[u], len(pools[u])), replace=False).sum()
                                  for u in pools) for _ in range(B)])
        return float((sims >= r.net.sum()).mean()), float(sims.mean())
    if rid == "R3":
        a, bb = r.net.values, r.alt.values
        sims = np.array([np.where(rng.random(len(a)) < .5, a, bb).sum() for _ in range(B)])
        return float((sims >= a.sum()).mean()), float(sims.mean())
    if rid == "R4":
        Wp = W[period_mask_W]
        pools = {u: Wp[Wp.und == u].net_ce.values for u in ("NIFTY", "BANKNIFTY")}
        ks = {u: int((r.und == u).sum()) for u in pools}
        sims = np.array([sum(rng.choice(pools[u], size=min(ks[u], len(pools[u])), replace=False).sum()
                             for u in pools) for _ in range(B)])
        return float((sims >= r.net.sum()).mean()), float(sims.mean())


# ---------------------------------------------------------------- main
def run(period):
    T, W, cal = load_liq(), load_night(), calendar()
    if period == "pre":
        mT, mW, cal = T.day < HOLD, W.day < HOLD, cal[cal.day < HOLD]
    else:
        mT, mW, cal = T.day >= HOLD, W.day >= HOLD, cal[cal.day >= HOLD]
    T, W = T[mT].reset_index(drop=True), W[mW].reset_index(drop=True)
    R, base, unds = build(T, W)
    rng = np.random.default_rng(20261008)
    res, D = {}, {}
    for rid in RULES:
        rows = R[rid]
        # each rule's calendar starts at the first date its source table covers that index
        first = {}
        for u in unds[rid]:
            src = T[T.und == u].day if rid in ("R1", "R2", "R5") else W[W.und == u].day
            if len(src):
                first[u] = src.min()
        c = pd.concat([cal[(cal.und == u) & (cal.day >= first[u])] for u in first]) if first else cal.iloc[:0]
        d, d15, dg = daily(rows, c, unds[rid]), daily(rows, c, unds[rid], "net15"), daily(rows, c, unds[rid], "gross")
        o = summ(rows, d, d15, dg)
        o.update(anchored_wf(d))
        o["by_und"] = {u: dict(trades=int((rows.und == u).sum()), net=round(float(rows[rows.und == u].net.sum())))
                       for u in unds[rid]}
        p_b, mu_b = baseline(rid, R, base, T, W, rng, np.ones(len(T), bool), np.ones(len(W), bool))
        o["base_p"], o["base_mean_total"] = p_b, round(mu_b)
        if rid in base:
            db = daily(base[rid], c, unds[rid])
            o["unfiltered_net_day"] = float(db.mean())
            o["excess_day"] = float(d.mean() - db.mean())
        o["walk1L"] = walk(rows)
        res[rid], D[rid] = o, d
    q = bh([res[r]["p"] for r in RULES])
    for r, qq in zip(RULES, q):
        res[r]["bh_q"] = float(qq)
    allday = sorted(set().union(*[set(D[r].index) for r in RULES]))
    X = np.column_stack([D[r].reindex(allday, fill_value=0).values for r in RULES])
    sp = spa(X, B=2000)
    for r in RULES:
        o = res[r]
        o["PASS"] = bool(o["bh_q"] <= .10 and o["base_p"] <= .05 and o["net15_day"] > 0
                         and int(o["years_pos"].split("/")[0]) >= 0.6 * int(o["years_pos"].split("/")[1])
                         and o.get("excess_day", 1) > 0)
    return res, sp, D


def fmt(res, sp, title):
    L = [f"# x2 {title}", "", f"SPA over the 5: spa_p={sp['spa_p']:.3f} rc_p={sp['rc_p']:.3f} best={RULES[sp['best']]}", ""]
    hdr = ("rule", "trades", "net/day", "gross/day", "net1.5x/day", "excess/day", "t", "BH q", "base p", "years+",
           "maxDD", "worst day", "worst month", "P(lose month)", "lots for 5k", "WF total", "1L end", "PASS")
    L.append("| " + " | ".join(hdr) + " |")
    L.append("|" + "---|" * len(hdr))
    for r in RULES:
        o = res[r]
        L.append("| " + " | ".join(str(x) for x in (
            r, o["trades"], round(o["net_day"], 1), round(o["gross_day"], 1), round(o["net15_day"], 1),
            round(o.get("excess_day", float("nan")), 1), round(o["t"], 2), round(o["bh_q"], 3), round(o["base_p"], 3),
            o["years_pos"], round(o["max_dd"]), round(o["worst_day"]), round(o["worst_month"]),
            round(o["p_lose_month"], 2), None if o["lots_for_5k"] is None else round(o["lots_for_5k"]),
            o["wf_total"], round(o["walk1L"]["end"]), o["PASS"])) + " |")
    L.append("")
    for r in RULES:
        o = res[r]
        L.append(f"- {r}: years {o['years']}; by index {o['by_und']}; base mean total {o['base_mean_total']}; "
                 f"unfiltered/day {o.get('unfiltered_net_day')}; walk {o['walk1L']}; WF years {o['wf_years']}")
    return "\n".join(L)


if __name__ == "__main__":
    mode = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    if mode == "pre":
        res, sp, D = run("pre")
        txt = fmt(res, sp, "PRE (< 2025-10-01)")
        open(f"{OUT}/pre.md", "w").write(txt)
        json.dump(dict(res=res, spa=sp), open(f"{OUT}/pre.json", "w"), indent=1, default=str)
        print(txt)
    elif mode == "hold":
        flag = f"{OUT}/HOLDOUT_RUN.flag"
        if os.path.exists(flag):
            sys.exit("holdout already run once; refusing")
        open(flag, "w").write("run " + pd.Timestamp.now().isoformat())
        res, sp, D = run("hold")
        txt = fmt(res, sp, "HOLDOUT (2025-10-01 .. latest), run once")
        open(f"{OUT}/hold.md", "w").write(txt)
        json.dump(dict(res=res, spa=sp), open(f"{OUT}/hold.json", "w"), indent=1, default=str)
        print(txt)
