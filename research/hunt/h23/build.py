"""h23 stage 1 (heavy, one data pass per mode): Liquidity 15+5 (rules unchanged) on 7 indices, 1-ITM nearest, under
"prints only" data (volume-0 option minutes = no print), Roll-measured spread, h14 E2 limit entry, h10/M2 impact.
See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h23/cache flock <scratch>/obuy.lock python3 -I research/hunt/h23/build.py

Before the first run, link the index caches the earlier hunts used (identical signals/expiry flags):
    ln -s <scratch>/hunt/h4/cache/ix_MIDCPNIFTY.pkl <scratch>/obuy_cache/ix_SENSEX.pkl  -> <scratch>/hunt/h23/cache/
Post-hoc sensitivities (info only): H23_SENS=noblock | hs0 (writes trades_<sens>.parquet, no pool).

Writes <scratch>/hunt/h23/: trades.parquet (real, 1 lot, kappa 0.01/0.02/0.04), pool.parquet (random entries),
signals.parquet, funnel.csv (signal -> trade counts), roll.csv (spread per index-month), coverage.csv.
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HUNT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HUNT, "h14"))
sys.path.insert(0, os.path.join(HUNT, "h4"))
import exe  # noqa: E402  (h14; imports h10 cap, h7 sim, obuy)
from exe import cap, sim  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import data as DATA  # noqa: E402
from obuy.costs import Costs, floor_tick  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import StrikeRule, positions, prepare_many  # noqa: E402
import comps  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h23")
H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
NEW = ("BANKEX", "NIFTYNXT50")
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "MIDCPNIFTY": 8.0, "NIFTY": 15.0, "SENSEX": 50.0,
         "BANKEX": 40.0, "NIFTYNXT50": 45.0}
WINDOW = (9 * 60 + 19, 14 * 60 - 1)
KAPPAS = (0.01, 0.02, 0.04)
W = C.W

# --- constants for the two new indices (PREREG): strike step 100; lot fallback only (the day's lot is read from OI)
C.STEP["NIFTYNXT50"] = 100
C.LOT_SCHEDULE.setdefault("NIFTYNXT50", [("2000-01-01", 25)])
comps.IDX_STOP_EXT.update(ISTOP)
comps.BOOKS_EXT.update({"BANKEX": (15, 5), "NIFTYNXT50": (15, 5)})
sim.ISTOP.update(ISTOP)


class BseCosts(Costs):
    def charge(self, buy, price, qty, dn=None, bse=True):
        return super().charge(buy, price, qty, dn, True)


CO_NSE, CO_BSE = exe.CO, BseCosts()

# --- "prints only": volume-0 option minutes become NaN (no print). OI / IV / spot untouched.
MASK = {"on": False}
SENS = os.environ.get("H23_SENS", "")      # post-hoc sensitivities (info only): "noblock" | "hs0"
_orig_init = DATA.Chain.__init__


def _masked_init(self, blk, sl, und, series, d):
    _orig_init(self, blk, sl, und, series, d)
    if MASK["on"]:
        for r in ("C", "P"):
            nop = ~(self.v[r] > 0)
            for f in ("o", "h", "l", "c", "v"):
                a = getattr(self, f)[r]
                a[nop] = np.nan


DATA.Chain.__init__ = _masked_init


def signals(mk):
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    n = comps.liq_ext_signals(mk, unds=NEW)
    s = pd.concat([a, b, n], ignore_index=True)
    s["day"] = pd.to_datetime(s["day"]).dt.date
    return s


def roll_table(pk, tr):
    """Roll spread per (und, month) from 1-min log returns of consecutive prints of the traded contracts (whole day)."""
    rows = []
    m = pk.meta.iloc[tr.row.values]
    crow = m.crow.values
    und = tr.und.values
    mon = pd.DatetimeIndex(tr.day.values).to_period("M").astype(str).values
    seen = set()
    acc = {}
    Cl = pk.store.arr["Cl"]
    for i in range(len(tr)):
        if crow[i] in seen:
            continue
        seen.add(crow[i])
        c = Cl[crow[i]].astype(np.float64)
        c = c[np.isfinite(c) & (c > 0)]
        if len(c) < 4:
            continue
        r = np.diff(np.log(c))
        a = acc.setdefault((und[i], mon[i]), [0.0, 0])
        a[0] += float(np.sum(r[1:] * r[:-1]))
        a[1] += len(r) - 1
    for (u, mo), (s, n) in acc.items():
        cov = s / max(n, 1)
        rows.append(dict(und=u, month=mo, pairs=n, cov=cov, spread=2 * np.sqrt(max(0.0, -cov))))
    return pd.DataFrame(rows).sort_values(["und", "month"]).reset_index(drop=True)


def hs_for(tr, roll):
    """point-in-time half-spread: previous month's estimate of the index (first month: its own)."""
    out = np.full(len(tr), np.nan)
    mon = pd.DatetimeIndex(tr.day.values).to_period("M").astype(str).values
    for u, g in roll.groupby("und"):
        ms, sp = g.month.values, g.spread.values / 2
        idx = np.nonzero(tr.und.values == u)[0]
        for i in idx:
            j = np.searchsorted(ms, mon[i]) - 1        # last month strictly before
            out[i] = sp[j] if j >= 0 else sp[0]
    return np.nan_to_num(out)


def entry_e2(lg, ac, n, model, kappa, hs):
    """h14 E2 (copied from exe.entry) with the measured spread: the marketable part is priced at
    max(liq fill, print x (1 + hs)); if hs > 0.5% the limit is not marketable (no immediate fill)."""
    N, r, c0, xc = lg.N, lg.r, lg.c0, lg.xc
    n = np.asarray(n, float).copy()
    valid0 = ~np.isnan(lg.O[r, c0])
    n = np.where(valid0, n, 0.0)
    qcum = np.zeros(N)
    P0 = lg.O[r, c0]
    L = floor_tick(np.nan_to_num(P0) * 1.005)
    base = np.maximum(exe.FL.buy(np.nan_to_num(P0), lg.V5[r, c0]), np.nan_to_num(P0) * (1 + hs))
    v = lg.V5[r, c0]
    qmax = np.where(L > base, np.floor(np.maximum(v, 1.0) * ((L / base - 1) / kappa) ** 2 + 1e-9), 0.0)
    qi = np.minimum(np.minimum(n, exe.capq(v)), qmax)
    if SENS != "noblock":
        qi = np.where(hs > 0.005, 0.0, qi)
    exe._buy(lg, ac, n > 0, c0, qi, model, kappa, qcum, c0)
    agg = qi.copy()
    R = n - qi
    for j in (1, 2, 3):
        k = np.minimum(c0 + j, W - 1)
        lo = lg.L[r, k]
        hit = (k < xc) & (R > 0) & (lo <= L - exe.TICK + 1e-9) & (lg.Vl[r, k] > 0)
        q = np.where(hit, np.minimum(R, exe.capq(lg.Vl[r, k])), 0.0)
        exe._pas(lg, ac, hit, k, q, L, True)
        R = R - q
    return agg


def simulate(b, lg, hs, kappa, bse):
    exe.CO = CO_BSE if bse else CO_NSE
    try:
        N = lg.N
        ac = exe.Acc(N)
        agg = entry_e2(lg, ac, np.ones(N), "M2", kappa, hs)
        buy_raw_agg = np.where(ac.Q > 0, ac.buy_raw * np.where(ac.Q > 0, (ac.Q - ac.pas_buy) / np.maximum(ac.Q, 1e-9), 0), 0)
        exe.exit_(lg, ac, "X0", "M2", kappa)
    finally:
        exe.CO = CO_NSE
    r, c0, xc = lg.r, lg.c0, lg.xc
    P0 = np.nan_to_num(lg.O[r, c0])
    liq_b = np.where(P0 > 0, (exe.FL.buy(P0, lg.V5[r, c0]) - P0) / np.maximum(P0, 1e-9), 0.0)
    Px = np.nan_to_num(lg.exit_raw)
    liq_s = np.where(Px > 0, (Px - exe.FL.sell(Px, lg.V5[r, np.minimum(xc, W - 1)])) / np.maximum(Px, 1e-9), 0.0)
    extra = np.maximum(0, hs - liq_b) * buy_raw_agg + np.maximum(0, hs - liq_s) * ac.sell_raw
    net = ac.sell_cash - ac.buy_cash - ac.chg - extra
    return pd.DataFrame(dict(net=net, gross=ac.sell_raw - ac.buy_raw, charges=ac.chg, spread_extra=extra,
                             lots=ac.Q, prem=ac.buy_cash, agg=agg, pas=ac.pas_buy,
                             end=np.maximum(ac.end, xc) + C.OPEN_M))


def v5_at_c0(pk, tr):
    V = np.nan_to_num(pk.store.arr["V"][pk.meta.crow.values[tr.row.values]].astype(np.float64))
    c0 = tr.c0.values
    cs = np.concatenate([np.zeros((len(tr), 1)), np.cumsum(V, axis=1)], axis=1)
    ri = np.arange(len(tr))
    return (cs[ri, c0] - cs[ri, np.maximum(c0 - 5, 0)]) / tr.lot.values


def build(mask, sig, unds, pool_k):
    MASK["on"] = mask
    mk = market()
    mk.release()
    s = sig[sig.und.isin(unds)].reset_index(drop=True)
    t0 = time.time()
    (pk, pool), = prepare_many([(s, StrikeRule(money=1), cap.EXE, pool_k, WINDOW, False)])
    print("mask", mask, "packs", round(time.time() - t0), "s", len(pk.meta), "cands", flush=True)
    return s, pk, pool


def main():
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    for u in ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX") + NEW:   # index + expiry flags BEFORE masking
        ix = mk.index(u)
        print("index", u, ix.source, len(ix.days), ix.days[0], ix.days[-1], flush=True)
    sig = signals(mk)
    sig.to_parquet(os.path.join(OUT, "signals.parquet"))
    print("signals", sig.groupby("und").size().to_dict(), flush=True)
    mk.release()
    unds = sorted(sig.und.unique())

    # unmasked pass for the two new indices only: how many signals the "prints only" rule removes
    if SENS:
        nomask = {}
    else:
        _, pku, _ = build(False, sig, NEW, 0)
        nomask = pku.meta.groupby("und").size().to_dict()
        del pku
    s, pk, pool = build(True, sig, unds, 0 if SENS else 5)
    tr = sim.base_trades(pk, cap.EXE, pos=False)
    tr["v5"] = v5_at_c0(pk, tr)
    roll = roll_table(pk, tr)
    if not SENS:
        roll.to_csv(os.path.join(OUT, "roll.csv"), index=False)
    if SENS:
        tp = tr.iloc[:0].copy()
    else:
        tp = sim.base_trades(pool, cap.EXE, pos=False)
        tp["v5"] = v5_at_c0(pool, tp)

    # funnel + coverage
    F = []
    cov = []
    for u in unds:
        su = s[s.und == u]
        ix = mk.index(u)
        exp = sum(1 for d in su.day if ix.d.get(d, {}).get("exp", False))
        tu = tr[tr.und == u]
        thin = int((tu.v5 < 1).sum())
        F.append(dict(und=u, signals=len(su), on_expiry_day=exp, cands_unmasked=nomask.get(u, np.nan),
                      cands=int((pk.meta.und == u).sum()), thin=thin))
        rows = pk.meta.crow.values[tr.row.values[tr.und.values == u]]
        Cl = pk.store.arr["Cl"][np.unique(rows)]
        cov.append(dict(und=u, contracts=len(rows), print_minutes_share=float(np.isfinite(Cl).mean()) if len(rows) else np.nan,
                        print_minutes_share_after_entry=np.nan))
    tr = tr[tr.v5 >= 1].copy()
    tp = tp[tp.v5 >= 1].copy()
    tr = positions(tr, one_at_a_time=True).reset_index(drop=True)
    tr["hs"] = 0.0 if SENS == "hs0" else hs_for(tr, roll)
    tp = tp.reset_index(drop=True)
    tp["hs"] = hs_for(tp, roll)
    for f in F:
        f["trades_after_book_rule"] = int((tr.und == f["und"]).sum())
    T, P = [], []
    for u in unds:
        bse = u in C.BSE
        for kind, frame, pkk, kap in (("real", tr, pk, KAPPAS), ("pool", tp, pool, (0.02, 0.04))):
            fu = frame[frame.und == u]
            if fu.empty:
                continue
            b = cap.Book(pkk, fu)
            lg = exe.leg_itm(b)
            base = b.tr[[c for c in ("cand", "parent", "und", "book", "day", "side", "entry_min", "exit_min", "why", "lot",
                                     "entry", "exit", "hs", "v5") if c in b.tr]].copy()
            for k in kap:
                o = simulate(b, lg, b.tr.hs.values, k, bse)
                for c in ("net", "gross", "charges", "spread_extra", "lots", "prem", "end", "agg", "pas"):
                    base[f"{c}_{k}"] = o[c].values
            (T if kind == "real" else P).append(base)
            print(u, kind, len(fu), flush=True)
    T = pd.concat(T, ignore_index=True)
    P = pd.concat(P, ignore_index=True) if P else pd.DataFrame()
    for f in F:
        f["filled_k02"] = int(((T.und == f["und"]) & (T["lots_0.02"] > 0)).sum())
    if SENS:
        T.to_parquet(os.path.join(OUT, f"trades_{SENS}.parquet"))
        return
    T.to_parquet(os.path.join(OUT, "trades.parquet"))
    P.to_parquet(os.path.join(OUT, "pool.parquet"))
    pd.DataFrame(F).to_csv(os.path.join(OUT, "funnel.csv"), index=False)
    pd.DataFrame(cov).to_csv(os.path.join(OUT, "coverage.csv"), index=False)
    print(pd.DataFrame(F).to_string(), flush=True)
    print(roll.groupby("und").spread.describe().to_string(), flush=True)


if __name__ == "__main__":
    main()
