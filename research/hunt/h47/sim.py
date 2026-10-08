"""h47 in-sample run of every pre-registered variant (PREREG.md). Holdout trades are never computed here.

python3 -I sim.py            # all coins, timeframes, anchors, setups -> scratchpad/hunt/h47/is_summary.csv, is_daily.npy
"""
from __future__ import annotations

import itertools
import json
import os
import sys
import time
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import (ANCHORS, COINS, CV, DAY0, FX, IS_END, NOTIONAL, SCR, TAX, TFS, Market, Params, build,  # noqa: E402
                 daily_atr, load_1m, np, pd, price_at, random_draws, run, thresholds)
from scipy.stats import norm  # noqa: E402

OPT0 = int(pd.Timestamp("2021-04-01").value // 60_000_000_000)
NDAY = (IS_END - DAY0) // 1440
SKIPS = (0, 1, 2, 4, 7)


def variants(N):
    for an in ANCHORS:
        for slope, volf, H, hours, sk in itertools.product((0, 1, 2), (0, 1), (0, 15, 30), (0, 1), SKIPS):
            yield dict(setup="A", anchor=an, N=N, slope=slope, vol=volf, hold=H, hours=hours, skip=sk, win=0, k=0.0)
        for win, sk in itertools.product((1, 2), SKIPS):
            yield dict(setup="B", anchor=an, N=N, slope=0, vol=0, hold=0, hours=0, skip=sk, win=win, k=0.0)
        for kk, hours in itertools.product((0.3, 0.5, 0.75), (0, 1)):
            yield dict(setup="C", anchor=an, N=N, slope=0, vol=0, hold=0, hours=hours, skip=0, win=0, k=kk)


def params(v, th):
    return Params({"A": 0, "B": 1, "C": 2}[v["setup"]], v["slope"], v["vol"], int(np.ceil(v["hold"] / v["N"])),
                  v["hours"], v["skip"], v["win"], v["k"], th["f30"], th["e15"], th["d80"], th["f60"])


def vid(coin, v):
    return f"{coin}|{v['setup']}|{v['anchor']}|{v['N']}|s{v['slope']}v{v['vol']}h{v['hold']}t{v['hours']}k{v['skip']}w{v['win']}c{v['k']}"


def fy(day):
    d = pd.to_datetime(day, unit="D")
    return np.where(d.month >= 4, d.year, d.year - 1)


def stats(day, g, net, d0, nday, rnd=None):
    """day: trade day index (days since epoch). g, net in return units (x NOTIONAL = Rs at Rs 1 lakh)."""
    G, Nt = g * NOTIONAL, net * NOTIONAL
    n = len(Nt)
    daily = np.zeros(nday, np.float64)
    if n:
        np.add.at(daily, day - d0, Nt)
    cum = np.cumsum(daily)
    dd = float(np.max(np.maximum.accumulate(np.r_[0, cum]) - np.r_[0, cum]))
    vda = TAX * np.clip(Nt, 0, None).sum()
    if n:
        f = pd.Series(Nt).groupby(fy(day)).sum()
        bus = TAX * np.clip(f.values, 0, None).sum()
    else:
        bus = 0.0
    mo = pd.Series(daily, index=pd.to_datetime(np.arange(d0, d0 + nday), unit="D")).resample("MS").sum()
    yrs = pd.Series(Nt).groupby(pd.to_datetime(day, unit="D").year).sum() if n else pd.Series(dtype=float)
    out = dict(n=n, tpd=n / nday, win=float((Nt > 0).mean()) if n else np.nan, gross_tr=G.mean() if n else np.nan,
               net_tr=Nt.mean() if n else np.nan, gross_day=G.sum() / nday, net_day=Nt.sum() / nday,
               vda_day=(Nt.sum() - vda) / nday, bus_day=(Nt.sum() - bus) / nday, maxdd=dd,
               green_m=float((mo > 0).mean()), sd_tr=Nt.std() if n > 1 else np.nan)
    for y in range(2020, 2027):
        out[f"y{y}"] = float(yrs.get(y, 0.0))
    if rnd is not None and n:
        rm = np.nanmean(rnd, 0) * NOTIONAL           # B random means (Rs/trade)
        mu, sd = rm.mean(), rm.std(ddof=1)
        out.update(rnd_tr=mu, rnd_sd=sd, z=(Nt.mean() - mu) / sd if sd > 0 else np.nan,
                   p_rand=float(norm.sf((Nt.mean() - mu) / sd)) if sd > 0 else np.nan)
    return out, daily.astype(np.float32)


def job(arg):
    coin, N = arg
    rng = np.random.default_rng(1000 * COINS.index(coin) + N)
    k = load_1m(coin)
    atr = daily_atr(k)
    M = Market(coin, k)
    th = thresholds(build(coin, N, "U", k, atr))
    rows, dailies = [], []
    BF, BO = 100, 30
    for an in ANCHORS:
        A = build(coin, N, an, k, atr)
        for v in variants(N):
            if v["anchor"] != an:
                continue
            tr = run(A, params(v, th))
            tr = tr[tr.te < IS_END].reset_index(drop=True)
            side = tr.side.values.astype(np.int64)
            day = tr.te.values // 1440
            base = dict(id=vid(coin, v), coin=coin, **v)
            # futures taker
            g, net = M.fut(tr)
            re, rx = random_draws(M, tr.te.values, tr.tx.values, side, BF, rng)
            pe, px = price_at(M, re), price_at(M, rx)
            rg = side[:, None] * (px / pe - 1)
            rnet = rg - (g - net)[:, None]
            s, d = stats(day, g, net, DAY0 // 1440, NDAY, rnet)
            rows.append(dict(base, inst="FT", **s)); dailies.append(d)
            # futures maker entry
            mk = tr.mk.values
            g2, net2 = M.fut(tr[mk], maker=True)
            s, d = stats(day[mk], g2, net2, DAY0 // 1440, NDAY, rg[mk] - (g2 - net2)[:, None])
            s["fill"] = float(mk.mean()) if len(mk) else np.nan
            rows.append(dict(base, inst="FM", **s)); dailies.append(d)
            # options
            om = tr.te.values >= OPT0
            to = tr[om]
            so = side[om]
            go, no, prem = M.opt(to.te.values, to.tx.values, so, to.ep.values, to.xp.values)
            reo, rxo = re[om][:, :BO], rx[om][:, :BO]
            _, rno, _ = M.opt(reo.ravel(), rxo.ravel(), np.repeat(so, BO), price_at(M, reo).ravel(), price_at(M, rxo).ravel())
            dd = np.zeros(NDAY, np.float32)
            s, d = stats(day[om], go, no, OPT0 // 1440, (IS_END - OPT0) // 1440, rno.reshape(-1, BO))
            s["prem_pct"] = float(np.mean(prem)) if len(prem) else np.nan
            dd[(OPT0 - DAY0) // 1440:] = d
            rows.append(dict(base, inst="O", **s)); dailies.append(dd)
        print(time.strftime("%H:%M:%S"), coin, N, an, "done", flush=True)
    return rows, np.stack(dailies), th


if __name__ == "__main__":
    jobs = [(c, N) for N in TFS for c in COINS]
    with Pool(3) as p:
        res = p.map(job, jobs, chunksize=1)
    rows = [r for x in res for r in x[0]]
    daily = np.concatenate([x[1] for x in res])
    ths = {f"{c}|{N}": x[2] for (c, N), x in zip(jobs, res)}
    df = pd.DataFrame(rows)
    df.to_csv(os.path.join(SCR, "is_summary.csv"), index=False)
    np.save(os.path.join(SCR, "is_daily.npy"), daily)
    json.dump(ths, open(os.path.join(SCR, "thresholds.json"), "w"), indent=1)
    print("variants", len(df), "daily", daily.shape)
