"""Detailed run of one variant over one period: trades, stats, per-year, best days, random baseline B=1000."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import CV, FX, NOTIONAL, Market, build, daily_atr, load_1m, np, pd, price_at, random_draws, run  # noqa: E402
from sim import OPT0, params, stats  # noqa: E402

_cache = {}


def coin_data(coin):
    if coin not in _cache:
        k = load_1m(coin)
        _cache[coin] = (k, daily_atr(k), Market(coin, k))
    return _cache[coin]


def trades(coin, v, th, a, b):
    k, atr, M = coin_data(coin)
    A = build(coin, v["N"], v["anchor"], k, atr)
    tr = run(A, params(v, th))
    tr = tr[(tr.te >= a) & (tr.te < b)].reset_index(drop=True)
    return tr, M


def pnl(tr, M, inst):
    side = tr.side.values.astype(np.int64)
    if inst == "FT":
        g, net = M.fut(tr)
        keep = np.ones(len(tr), bool)
    elif inst == "FM":
        keep = tr.mk.values
        g, net = M.fut(tr[keep], maker=True)
    else:
        keep = tr.te.values >= OPT0
        t = tr[keep]
        g, net, _ = M.opt(t.te.values, t.tx.values, side[keep], t.ep.values, t.xp.values)
    return keep, g, net


def detail(coin, v, th, inst, a, b, B=1000, seed=7):
    tr, M = trades(coin, v, th, a, b)
    keep, g, net = pnl(tr, M, inst)
    t = tr[keep].reset_index(drop=True)
    side = t.side.values.astype(np.int64)
    rng = np.random.default_rng(seed)
    re, rx = random_draws(M, t.te.values, t.tx.values, side, B, rng)
    if inst == "O":
        _, rn, _ = M.opt(re.ravel(), rx.ravel(), np.repeat(side, B), price_at(M, re).ravel(), price_at(M, rx).ravel())
        rnet = rn.reshape(-1, B)
    else:
        rg = side[:, None] * (price_at(M, rx) / price_at(M, re) - 1)
        rnet = rg - (g - net)[:, None]
    d0 = a // 1440
    nday = (b - a) // 1440
    day = t.te.values // 1440
    s, daily = stats(day, g, net, d0, nday, rnet)
    rm = np.nanmean(rnet, 0) * NOTIONAL
    s["p_rand_emp"] = float((np.sum(rm >= net.mean() * NOTIONAL) + 1) / (B + 1))
    s["gross_win"] = float((g > 0).mean())
    s["rs_contract_net_tr"] = float(np.mean(net * t.ep.values * CV[coin] * FX))
    s["rs_contract_gross_tr"] = float(np.mean(g * t.ep.values * CV[coin] * FX))
    s["hold_min_med"] = float(np.median(t.tx.values - t.te.values))
    t = t.assign(g=g * NOTIONAL, net=net * NOTIONAL, day=day)
    return s, daily, t


def day_profile(daily):
    d = np.asarray(daily, float)
    srt = np.sort(d)
    n = len(d)
    k5 = max(1, int(round(n * 0.05)))
    return dict(days=n, avg=d.mean(), median=float(np.median(d)), best5=srt[-k5:].mean(), worst5=srt[:k5].mean(),
                best10=srt[-10:].mean(), pos=float((d > 0).mean()), ge5000=float((d >= 5000).mean()),
                best=srt[-1], worst=srt[0])
