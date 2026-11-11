"""R160 study (PREREG.md): build the nightly measures, run the pre-registered tests and trade T1.

    R160_LAKE=/path/to/lake python3 -I -m research.hunt.r160_cas_gap.study      (run from the repo root)

Writes results/results.json (every number, stamped with PREREG's sha256), results/nights.csv (per-night bps measures,
no prices) and results/tables.md. The POST period is read once; results/POST_READ.flag records it.
"""
from __future__ import annotations

import hashlib
import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from . import cfg as K
from . import measure as M
from . import stats as S
from . import trade as T


# ================================================================================================ loading
def _naive(df: pd.DataFrame) -> pd.DataFrame:
    if getattr(df.ts.dt, "tz", None) is not None:
        df["ts"] = df.ts.dt.tz_localize(None)
    return df


def load_index(u: str) -> dict:
    parts = []
    for y in (2025, 2026):
        parts.append(_naive(pd.read_parquet(K.LAKE / "index_1m" / f"index_1m_{u}_{y}.parquet", columns=["ts", "close"])))
    x = pd.concat(parts)
    x["d"] = x.ts.dt.date
    x["r"] = x.ts.dt.hour * 60 + x.ts.dt.minute - (9 * 60 + 15)
    x = x[(x.r >= 0) & (x.r < K.N)].drop_duplicates(["d", "r"], keep="last")
    return M.to_grid(x, "close")


def load_daily(u: str) -> pd.DataFrame:
    dd = pd.read_parquet(K.LAKE / "index_daily" / "index_daily.parquet")
    dd = dd[dd.symbol == u].copy()
    dd["d"] = pd.to_datetime(dd.date).dt.date
    return dd.sort_values("d").drop_duplicates("d", keep="last").set_index("d")[["open", "close"]]


def load_options(u: str) -> pd.DataFrame:
    parts = []
    for y in (2025, 2026):
        parts.append(_naive(pd.read_parquet(K.LAKE / "opt" / f"opt_{K.OPT_BOOK[u]}_{y}.parquet",
                                            columns=["ts", "offset", "side", "open", "high", "low", "close", "strike"])))
    x = pd.concat(parts, ignore_index=True)
    x["side"] = x.side.astype(str)
    return x


def load_futures(u: str) -> dict:
    """{day: per-minute close of the listed contract with the most volume that day} (POST only: PREREG 1)."""
    f = _naive(pd.read_parquet(K.LAKE / "fut" / "fut_1m_live_index.parquet"))
    f = f[f.symbol == u].copy()
    f["d"] = f.ts.dt.date
    f["r"] = f.ts.dt.hour * 60 + f.ts.dt.minute - (9 * 60 + 15)
    f = f[(f.r >= 0) & (f.r < K.N)]
    vol = f.groupby(["d", "expiry"]).volume.sum().reset_index()
    best = vol.sort_values(["d", "volume"]).drop_duplicates("d", keep="last")[["d", "expiry"]]
    f = f.merge(best, on=["d", "expiry"])
    return M.to_grid(f.drop_duplicates(["d", "r"], keep="last"), "close")


# ================================================================================================ nightly table
def nights(u: str, idx: dict, daily: pd.DataFrame, fgrid: dict, fut: dict | None) -> tuple[pd.DataFrame, dict]:
    days = list(daily.index)
    exp = M.expiry_days([d for d in days if d >= date(2025, 9, 1)], K.EXPIRY_WEEKDAY[u], u in K.MONTHLY)
    rows, drop = [], {}
    for i, d in enumerate(days[:-1]):
        per = "POST" if M.in_period(d, K.POST) else "PRE" if M.in_period(d, K.PRE) else None
        if per is None:
            continue
        nd = days[i + 1]
        ia, fa = idx.get(d), fgrid.get(d)
        if ia is None or fa is None:
            drop["no_minutes"] = drop.get("no_minutes", 0) + 1
            continue
        b = M.basis(fa, ia)
        L = M.late_level(fa, b)
        if not np.isfinite(L):
            k = "basis_short" if not np.isfinite(b) else "late_short"
            drop[k] = drop.get(k, 0) + 1
            continue
        nia = idx.get(nd)
        i0920 = nia[K.R0920] if nia is not None else np.nan
        m = M.day_measures(L, float(daily.close[d]), float(daily.open[nd]), float(ia[K.R1514]), float(i0920))
        rec = dict(und=u, d=d, nd=nd, period=per, expiry=d in exp, **m)
        if fut is not None and per == "POST" and d in fut:
            bf = M.basis(fut[d], ia)
            Lf = M.late_level(fut[d], bf)
            mf = M.day_measures(Lf, float(daily.close[d]), float(daily.open[nd]), float(ia[K.R1514]), float(i0920))
            rec.update({f"{k}_fut": v for k, v in mf.items()})
        rows.append(rec)
    return pd.DataFrame(rows), drop


# ================================================================================================ tests
def describe(x: pd.DataFrame) -> dict:
    D, G, R = x.D.to_numpy(), x.G.to_numpy(), x.R.to_numpy()
    big = np.abs(D) >= K.T1_MIN_D
    ratio = G[big] / D[big]
    return dict(
        nights=int(len(x)),
        mean_abs_D=round(float(np.nanmean(np.abs(D))), 2), median_abs_D=round(float(np.nanmedian(np.abs(D))), 2),
        p90_abs_D=round(float(np.nanquantile(np.abs(D), 0.9)), 2),
        mean_abs_G=round(float(np.nanmean(np.abs(G))), 2), mean_abs_R=round(float(np.nanmean(np.abs(R))), 2),
        nights_absD_ge5=int(big.sum()),
        median_G_over_D_absD_ge5=round(float(np.nanmedian(ratio)), 3) if big.any() else None,
        share_open_at_least_half_way_absD_ge5=round(float(np.mean(ratio >= 0.5)), 3) if big.any() else None,
        share_open_past_L_absD_ge5=round(float(np.mean(ratio >= 1.0)), 3) if big.any() else None,
    )


def reg(x: pd.DataFrame, xc: str, yc: str, rng, side: str, perms: int = K.PERMS) -> dict:
    a, b = S.clean(x[xc], x[yc])
    hr, hn, hp = S.hit_rate(a, b, K.HIT_MIN_BPS)
    return dict(n=int(a.size), slope=round(S.slope(a, b), 4), pearson=round(S.pearson(a, b), 4),
                spearman=round(S.spearman(a, b), 4), p=S.perm_p(a, b, perms, rng, side),
                hit=round(hr, 4) if np.isfinite(hr) else None, hit_n=hn, hit_p=hp)


def run_trade(u: str, x: pd.DataFrame, opt: pd.DataFrame, rng) -> dict:
    post = x[(x.period == "POST") & (~x.expiry) & (np.abs(x.D) >= K.T1_MIN_D)]
    opt = opt[(opt.ts >= pd.Timestamp(K.POST[0])) & (opt.ts < pd.Timestamp(K.POST[1]) + pd.Timedelta(days=7))]
    by_day = {d: g for d, g in opt.groupby(opt.ts.dt.date)}
    legs, why = [], {}
    for _, n in post.iterrows():
        od, ond = by_day.get(n.d), by_day.get(n.nd)
        if od is None or ond is None:
            why["no_option_day"] = why.get("no_option_day", 0) + 1
            continue
        k = T.atm_strike(od, K.ENTRY_ROW)
        if k is None:
            why["no_atm"] = why.get("no_atm", 0) + 1
            continue
        bd, bnd = T.book(od), T.book(ond)
        pair = {}
        for s in ("C", "P"):
            lg, w = T.price_leg(n.d, s, k, K.lot(u, n.d), bd, bnd)
            if lg is None:
                why[w] = why.get(w, 0) + 1
                break
            pair[s] = lg
        if len(pair) == 2:
            legs.append((n.d, 1 if n.D > 0 else -1, pair))
    if not legs:
        return dict(trades=0, dropped=why)
    act = np.array([T.net(p["C" if sd > 0 else "P"]) for _, sd, p in legs])
    act15 = np.array([T.net(p["C" if sd > 0 else "P"], K.STRESS) for _, sd, p in legs])
    both = np.array([[T.net(p["C"]), T.net(p["P"])] for _, _, p in legs])
    pick = rng.integers(0, 2, (K.TWINS, len(legs)))
    twins = np.take_along_axis(np.broadcast_to(both, (K.TWINS,) + both.shape), pick[..., None], axis=2)[..., 0].sum(1)
    eq = np.cumsum(act)
    return dict(
        trades=int(len(act)), per_trade=round(float(act.mean())), win=round(float((act > 0).mean()), 3),
        total=round(float(act.sum())), worst=round(float(act.min())), best=round(float(act.max())),
        max_dd=round(float((eq - np.maximum.accumulate(np.concatenate([[0], eq]))[1:]).min())),
        per_trade_stress=round(float(act15.mean())), total_stress=round(float(act15.sum())),
        calls=int(sum(1 for _, sd, _ in legs if sd > 0)), twin_p=S.twin_p(float(act.sum()), twins),
        twin_mean_total=round(float(twins.mean())), both_sides_per_night=round(float(both.sum(1).mean())),
        dropped=why,
        detail=[dict(d=str(d), side="C" if sd > 0 else "P", strike=p["C"].strike, e=(p["C"] if sd > 0 else p["P"]).e,
                     x=(p["C"] if sd > 0 else p["P"]).x, net=round(float(a))) for (d, sd, p), a in zip(legs, act)],
    )


def main() -> None:
    prereg_sha = hashlib.sha256(K.PREREG.read_bytes()).hexdigest()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    flag = K.RESULTS / "POST_READ.flag"
    if flag.exists() and "--again" not in sys.argv:
        sys.exit("POST was already read (results/POST_READ.flag). Pass --again only to reproduce the same numbers.")
    res = dict(study="R160", prereg_sha256=prereg_sha, run_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"),
               periods=dict(POST=[str(K.POST[0]), str(K.POST[1])], PRE=[str(K.PRE[0]), str(K.PRE[1])]), und={})
    tables, family = [], []
    opt_cache = {}
    for ui, u in enumerate(K.UNDS):
        idx, daily, opt = load_index(u), load_daily(u), load_options(u)
        opt_cache[u] = opt
        fgrid = M.to_grid(M.implied_minutes(opt), "F")
        fut = load_futures(u)
        x, drop = nights(u, idx, daily, fgrid, fut)
        tables.append(x)
        post, pre = x[x.period == "POST"], x[x.period == "PRE"]
        rng = lambda k: np.random.default_rng([K.SEED, ui, k])  # noqa: E731
        r = dict(dropped=drop, describe=dict(POST=describe(post), PRE=describe(pre)))
        r["H1_post"] = reg(post, "D", "G", rng(1), "greater")
        r["H1_pre"] = reg(pre, "D", "G", rng(2), "greater")
        r["H1_diff_ci95"] = S.boot_diff(post.D, post.G, pre.D, pre.G, K.BOOT, rng(3))
        r["H2_post"] = reg(post, "D", "R", rng(4), "two")
        r["H2_pre"] = reg(pre, "D", "R", rng(5), "two")
        r["A_RM_post"] = reg(post, "M", "R", rng(6), "two")
        r["A_GM_post"] = reg(post, "M", "G", rng(7), "two")
        r["A_RM_pre"] = reg(pre, "M", "R", rng(8), "two")
        r["A_GM_pre"] = reg(pre, "M", "G", rng(9), "two")
        r["H1_post_G20"] = reg(post.rename(columns={"G": "Gx", "G20": "G"}), "D", "G", rng(10), "greater")
        r["H1_pre_G20"] = reg(pre.rename(columns={"G": "Gx", "G20": "G"}), "D", "G", rng(11), "greater")
        if "D_fut" in post:
            pf = post.dropna(subset=["D_fut"])
            r["H1_post_fut"] = reg(pf, "D_fut", "G_fut", rng(12), "greater")
            r["H2_post_fut"] = reg(pf, "D_fut", "R_fut", rng(13), "two")
            r["fut_vs_opt_D_corr"] = round(S.pearson(pf.D, pf.D_fut), 3)
            r["describe_fut_POST"] = dict(nights=int(len(pf)), mean_abs_D=round(float(np.abs(pf.D_fut).mean()), 2))
        ex, ne = post[post.expiry], post[~post.expiry]
        r["B_expiry"] = dict(expiry_days=[str(d) for d in ex.d], n_expiry=int(len(ex)), n_other=int(len(ne)),
                             mean_abs_D_expiry=round(float(np.abs(ex.D).mean()), 2) if len(ex) else None,
                             mean_abs_D_other=round(float(np.abs(ne.D).mean()), 2) if len(ne) else None,
                             H1_expiry=reg(ex, "D", "G", rng(14), "greater", 20_000) if len(ex) >= 3 else None,
                             H1_other=reg(ne, "D", "G", rng(15), "greater") if len(ne) >= 3 else None)
        family += [(f"H1 {u}", r["H1_post"]["p"]), (f"H2 {u}", r["H2_post"]["p"]), (f"A {u}", r["A_RM_post"]["p"])]
        res["und"][u] = r
        print(u, "POST", len(post), "PRE", len(pre), "H1", r["H1_post"], "H2", r["H2_post"], flush=True)
    xa = pd.concat(tables)
    res["T1"] = run_trade(K.PRIMARY, xa[xa.und == K.PRIMARY], opt_cache[K.PRIMARY], np.random.default_rng([K.SEED, 99]))
    if res["T1"]["trades"]:
        family.append(("T1 twin NIFTY", res["T1"]["twin_p"]))
    names, ps = zip(*family)
    q_in, q_all = S.bh(list(ps)), S.bh(list(ps), K.N_PRIOR + len(ps))
    res["family"] = [dict(test=n, p=p, q_r160=round(float(a), 4), q_all=round(float(b), 4))
                     for n, p, a, b in zip(names, ps, q_in, q_all)]
    res["m_total"] = K.N_PRIOR + len(ps)
    res["verdict"] = verdict(res)
    xa[["und", "d", "nd", "period", "expiry", "D", "G", "R", "M", "G20"] +
       [c for c in xa.columns if c.endswith("_fut")]].round(3).to_csv(K.RESULTS / "nights.csv", index=False)
    (K.RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    flag.write_text(f"POST read {res['run_utc']} (prereg {prereg_sha})\n")
    print(json.dumps(res["family"], indent=1), json.dumps(res["verdict"], indent=1))


def verdict(res: dict) -> dict:
    """PREREG 5 and 8."""
    q = {f["test"]: f["q_all"] for f in res["family"]}
    h1 = res["und"][K.PRIMARY]["H1_post"]
    h2 = res["und"][K.PRIMARY]["H2_post"]
    h1_ok = bool(h1["slope"] >= K.H1_MIN_BETA and q[f"H1 {K.PRIMARY}"] <= K.GATE_Q and (h1["hit"] or 0) >= K.H1_MIN_HIT)
    t = res["T1"]
    t_ok = bool(h1_ok and h2["slope"] > 0 and t.get("trades", 0) >= K.T1_MIN_TRADES and t.get("total_stress", -1) > 0
                and t.get("total", -1) > 0 and t.get("twin_p", 1) <= 0.05)
    why = []
    if h1["slope"] < K.H1_MIN_BETA:
        why.append(f"H1 slope {h1['slope']} < {K.H1_MIN_BETA}")
    if q[f"H1 {K.PRIMARY}"] > K.GATE_Q:
        why.append(f"H1 BH q {q[f'H1 {K.PRIMARY}']} > {K.GATE_Q} (m = {res['m_total']})")
    if (h1["hit"] or 0) < K.H1_MIN_HIT:
        why.append(f"H1 hit-rate {h1['hit']} < {K.H1_MIN_HIT}")
    return dict(H1_supported=h1_ok, T1_passed=t_ok, H1_fails=why)


if __name__ == "__main__":
    main()
