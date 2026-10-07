"""h7: does pyramiding into Liquidity 15+5 winners (or sizing by room to the next level) beat simply trading more flat lots?

    OBUY_CACHE=<scratch>/hunt/h7/cache python3 -I research/hunt/h7/analyze.py pre       # choose on data < 2025-10-01
    OBUY_CACHE=<scratch>/hunt/h7/cache python3 -I research/hunt/h7/analyze.py holdout   # ONE test of the frozen choice

PRE-REGISTERED GRID (written before any overlay result was computed; 32 variants, all counted):
  P  pyramid only (1 initial lot):
       prem X in {0.10, 0.20, 0.30} x max adds m in {1, 3}                       6
       idx  Y in {0.25 (m 3 -> 25/50/75%), 0.25 m1, 0.33 (33/67%), 0.50 (50%)}   4
  S  room sizing only (no adds): terc = 1/2/3 lots by pre-holdout room terciles (no level = top);
                                 med  = 1/2 lots below/above the pre-holdout median                2
  B  both: every P x every S                                                                     20
Benchmark for every variant: FLAT lots on every trade with the same exposure:
  'lots'  k = the variant's mean lots per trade (pre-holdout, real)        <- selection criterion
  'prem'  k = the variant's premium deployed / the 1-lot premium deployed  (adds are bought dearer)
Selection: the variant with the largest pre-holdout excess net over its lots-matched flat benchmark ('real').
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import sim  # noqa: E402  (imports obuy first)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.engine import Execution  # noqa: E402
import portfolio as PF  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
START = pd.Timestamp("2021-10-01")
ALL3 = pd.Timestamp("2023-06-01")      # MIDCPNIFTY live from May 2023: sizing period with all three books
TARGET = 5000.0
CAL = pd.read_csv(os.path.join(sim.C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
OUT = sim.OUT

PYR = [("prem", 0.10, 1), ("prem", 0.10, 3), ("prem", 0.20, 1), ("prem", 0.20, 3), ("prem", 0.30, 1), ("prem", 0.30, 3),
       ("idx", 0.25, 3), ("idx", 0.25, 1), ("idx", 1 / 3, 2), ("idx", 0.50, 1)]
SIZ = ["terc", "med"]


def pname(p):
    k, s, m = p
    return f"{k}{round(s * 100)}m{m}"


VARIANTS = ([dict(name="P_" + pname(p), pyr=p, siz=None) for p in PYR] + [dict(name="S_" + s, pyr=None, siz=s) for s in SIZ]
            + [dict(name=f"B_{pname(p)}_{s}", pyr=p, siz=s) for p in PYR for s in SIZ])


def exe_of(en):
    fl, co = sim.EXES[en]
    return Execution(expiry="skip", fills=fl, costs=co)


def edges(base_real):
    r = base_real[base_real.day < HOLD].room.values
    r = np.where(np.isfinite(r), r, 1e9)
    return dict(terc=list(np.quantile(r, [1 / 3, 2 / 3])), med=[float(np.median(r))])


def n0_of(tr, siz, ed):
    if siz is None:
        return np.ones(len(tr))
    r = np.where(np.isfinite(tr.room.values), tr.room.values, 1e9)
    return 1.0 + np.searchsorted(np.array(ed[siz]), r, side="right")


def run_variant(pk, tr, exe, v, ed, cache):
    key = (id(pk), v["pyr"])
    if v["pyr"] is None:
        al = []
    else:
        if key not in cache:
            kind, s, m = v["pyr"]
            cache[key] = sim.adds(pk, tr, exe, kind, s, m)
        al = cache[key]
    o = sim.overlay(tr, exe, n0_of(tr, v["siz"], ed), al)
    o.index = tr.index
    return o


def flat(tr, exe, k):
    return sim.overlay(tr, exe, np.full(len(tr), float(k)), [])


def daily(tr, net, lo=None, hi=None):
    s = pd.Series(np.asarray(net), index=tr.day.values).groupby(level=0).sum().reindex(CAL, fill_value=0.0)
    if lo is not None:
        s = s[s.index >= lo]
    if hi is not None:
        s = s[s.index < hi]
    return s


def fmt(d):
    return {a: ((round(b, 3) if abs(b) < 10 else round(b)) if isinstance(b, (float, np.floating)) else b) for a, b in d.items()}


def setup():
    P = sim.load()
    base, pool = {}, {}
    for en in sim.EXES:
        base[en] = sim.base_trades(P[en][0], exe_of(en))
    pool = sim.base_trades(P["real"][1], exe_of("real"), pos=False)
    return P, base, pool


def pre():
    P, base, pool = setup()
    ed = edges(base["real"])
    exe = exe_of("real")
    tr = base["real"]
    pm = tr.day < HOLD
    trp = pool[pool.day < HOLD]
    cache = {}
    rows, X, pre_daily = [], [], {}
    b1 = flat(tr, exe, 1.0)
    for v in VARIANTS:
        o = run_variant(P["real"][0], tr, exe, v, ed, cache)
        k_l = o.lots[pm].mean()
        k_p = o.prem[pm].sum() / b1.prem[pm].sum()
        fl_l, fl_p = flat(tr, exe, k_l), flat(tr, exe, k_p)
        dv = daily(tr, o.net, START, HOLD)
        dl = daily(tr, fl_l.net, START, HOLD)
        dp = daily(tr, fl_p.net, START, HOLD)
        exl = dv - dl
        X.append(exl.values)
        pre_daily[v["name"]] = dv
        # random entries: the same overlay on the pool (no position filter, as obuy's baseline)
        po = run_variant(P["real"][1], pool, exe, v, ed, cache)
        pfl = flat(pool, exe, k_l)
        pmask = (pool.day < HOLD).values
        up_rand = (po.net.values - pfl.net.values)[pmask].mean()
        up_real = (o.net.values - fl_l.net.values)[pm.values].mean()
        rb = OF.random_baseline(tr[pm].assign(net=o.net[pm].values), trp.assign(net=po.net.values[pmask]))
        sp1 = OF.spa(exl.values[:, None], B=1000)
        st_v, st_f = PF.stats_row(dv.values, dv.index), PF.stats_row(dl.values, dl.index)
        rows.append(dict(variant=v["name"], lots_per_trade=k_l, prem_k=k_p, adds_per_trade=o.nadd[pm].mean(),
                         net_day=dv.mean(), flat_lots_day=dl.mean(), flat_prem_day=dp.mean(),
                         excess_day=exl.mean(), excess_vs_prem_day=(dv - dp).mean(),
                         excess_per_trade=up_real, excess_per_trade_random=up_rand,
                         sharpe=st_v["sharpe"], sharpe_flat=st_f["sharpe"], maxdd=st_v["maxdd"], maxdd_flat=st_f["maxdd"],
                         p_excess=sp1["spa_p"], p_random=rb["p"], rand_mean=rb["null_mean"], obs_mean=rb["obs"]))
    R = pd.DataFrame(rows)
    R["q_excess_BH"] = OF.bh(R.p_excess.values)
    S = OF.spa(np.column_stack(X), B=2000)
    # anchored walk-forward on the excess (lots-matched)
    Xd = pd.DataFrame(np.column_stack(X), index=daily(tr, b1.net, START, HOLD).index, columns=R.variant)
    wf = {}
    for y in (2023, 2024, 2025):
        past = Xd[Xd.index < pd.Timestamp(f"{y}-01-01")].sum()
        pick = past.idxmax()
        test = Xd[(Xd.index >= pd.Timestamp(f"{y}-01-01")) & (Xd.index < min(pd.Timestamp(f"{y + 1}-01-01"), HOLD))][pick]
        wf[y] = dict(pick=pick, excess_total=float(test.sum()), excess_day=float(test.mean()))
    choice = R.sort_values("excess_day", ascending=False).iloc[0].variant
    # per-year excess of the choice and the base
    yr = Xd[choice].groupby(Xd.index.year).sum().round(0).to_dict()
    base_yr = daily(tr, b1.net, START, HOLD).groupby(lambda d: d.year).sum().round(0).to_dict()
    json.dump(dict(choice=choice, edges=ed, n_variants=len(VARIANTS), spa=S, wf=wf, choice_excess_by_year=yr,
                   base_by_year=base_yr), open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)
    R.to_csv(os.path.join(OUT, "pre_variants.csv"), index=False)
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 30)
    print(R.round(3).to_string())
    print("SPA over", len(VARIANTS), "excess series:", S)
    print("walk-forward:", wf)
    print("room edges:", ed)
    print("CHOICE:", choice, "excess by year", yr, "base by year", base_yr)


def outlay_rows(tr, o):
    t = tr[["day", "entry_min", "exit_min"]].copy()
    t["entry"] = o.prem.values
    t["qty"] = 1.0
    return t


def holdout():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    name, ed = ch["choice"], ch["edges"]
    v = [x for x in VARIANTS if x["name"] == name][0]
    P, base, pool = setup()
    res = {}
    for en in sim.EXES:
        exe = exe_of(en)
        tr = base[en]
        o = run_variant(P[en][0], tr, exe, v, ed, {})
        pm = (tr.day < HOLD).values
        k_l = o.lots[pm].mean()
        k_p = o.prem[pm].sum() / flat(tr, exe, 1).prem[pm].sum()
        res[en] = dict(tr=tr, o=o, fl=flat(tr, exe, k_l), flp=flat(tr, exe, k_p), b1=flat(tr, exe, 1), k_l=k_l, k_p=k_p)
    # size on pre-holdout real, all three books live
    r = res["real"]
    sz = TARGET / daily(r["tr"], r["o"].net, ALL3, HOLD).mean()
    szf = TARGET / daily(r["tr"], r["fl"].net, ALL3, HOLD).mean()
    sz1 = TARGET / daily(r["tr"], r["b1"].net, ALL3, HOLD).mean()
    print("choice", name, "lots-matched k", round(r["k_l"], 3), "prem-matched k", round(r["k_p"], 3))
    print("scale for Rs 5,000/day (pre, real): variant x%.2f, flat-matched x%.2f, flat 1-lot base x%.2f" % (sz, szf, sz1))
    out = {}
    for en in sim.EXES:
        x = res[en]
        for lab, ser, k in (("variant", x["o"].net, sz), ("flat_lots_matched", x["fl"].net, szf), ("flat_prem_matched", x["flp"].net, None),
                            ("flat_1lot", x["b1"].net, sz1)):
            for per, lo, hi in (("pre", ALL3, HOLD), ("hold", HOLD, None)):
                d = daily(x["tr"], ser, lo, hi)
                row = dict(per_day_1unit=d.mean())
                if k:
                    st = PF.stats_row(d.values * k, d.index)
                    row.update({f"size_{a}": b for a, b in st.items() if a in ("mean_day", "worst_day", "worst_month", "losing_months", "maxdd", "sharpe", "pos_days")})
                    if en == "real":
                        row["P_losing_month"] = PF.p_losing_month(d.values * k)
                out[(en, lab, per)] = row
                print(en, lab, per, fmt(row))
    # per-year, per-index (real, 1 base unit)
    x = res["real"]
    t = x["tr"].assign(v=x["o"].net.values, f=x["fl"].net.values, b=x["b1"].net.values)
    print("per year real (variant / lots-matched flat / 1 lot):")
    print(t.groupby(t.day.dt.year)[["v", "f", "b"]].sum().round(0))
    print(t[t.day >= HOLD].groupby("und")[["v", "f", "b"]].sum().round(0))
    # random entries in the holdout: same overlay
    po = run_variant(P["real"][1], pool, exe_of("real"), v, ed, {})
    pfl = flat(pool, exe_of("real"), x["k_l"])
    hm = (pool.day >= HOLD).values
    hr = (t.day >= HOLD).values
    rb = OF.random_baseline(t[hr].assign(net=t.v.values[hr]), pool[hm].assign(net=po.net.values[hm]))
    print("holdout random-entry: real mean/trade %.0f, random %.0f, p %.4f" % (rb["obs"], rb["null_mean"], rb["p"]))
    print("holdout excess/trade vs lots-flat: real %.0f, random %.0f" % ((t.v - t.f)[hr].mean(), (po.net.values - pfl.net.values)[hm].mean()))
    ex = daily(t, t.v - t.f, HOLD)
    print("holdout excess/day vs lots-flat %.0f, bootstrap p %.3f" % (ex.mean(), OF.spa(ex.values[:, None], B=2000)["spa_p"]))
    # capital: premium tied up at once (whole position's premium held entry->exit), at size
    O = PF.outlay(outlay_rows(t, x["o"]), CAL) * sz
    Of = PF.outlay(outlay_rows(t, x["fl"]), CAL) * szf
    print("outlay at size: variant p95 %.0f max %.0f | flat p95 %.0f max %.0f" % (O[O > 0].quantile(.95), O.max(), Of[Of > 0].quantile(.95), Of.max()))
    # fill realism: lots per order at size vs the contract's traded volume (lots) in the 5 min before the entry / day
    pk = P["real"][0]
    V = pk.store.arr["V"][pk.meta.crow.values[t.row.values]].astype(float)
    lot = t.lot.values.astype(float)
    c0 = t.c0.values
    cs = np.cumsum(np.nan_to_num(V), axis=1)
    v5 = (cs[np.arange(len(t)), c0 - 1] - cs[np.arange(len(t)), np.maximum(c0 - 6, 0)]) / lot
    vday = cs[:, -1] / lot
    n0 = n0_of(t, v["siz"], ed)
    rr = pd.DataFrame(dict(und=t.und, entry_lots=np.round(n0 * sz), add_lots=np.round(sz), exit_lots=np.round(x["o"].lots.values * sz),
                           v5=v5, vday=vday))
    fr = rr.groupby("und").agg(entry_lots=("entry_lots", "median"), add_lots=("add_lots", "median"), exit_lots_p90=("exit_lots", lambda s: s.quantile(.9)),
                               v5_med=("v5", "median"), v5_p10=("v5", lambda s: s.quantile(.1)), vday_med=("vday", "median"))
    fr["entry_vs_v5_med"] = fr.entry_lots / fr.v5_med
    print("fill size vs traded volume (lots):"); print(fr.round(2))
    hs = {f"{a}|{b}|{c}": d for (a, b, c), d in out.items()}
    json.dump(dict(choice=name, scale=dict(variant=sz, flat=szf, one=sz1), stats=hs, fill=fr.round(2).to_dict()),
              open(os.path.join(OUT, "holdout.json"), "w"), indent=1, default=float)


def sized():
    """Integer-lot sizing for ~Rs 5,000/day (chosen on PRE-holdout real net, 2023-06..2025-09), charges at the real
    order sizes (brokerage once per order). Variant: base s lots (room buckets s/2s/3s), adds of s lots. Flat: N lots."""
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    name, ed = ch["choice"], ch["edges"]
    v = [x for x in VARIANTS if x["name"] == name][0]
    P, base, pool = setup()
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 30)
    pick = {}
    al = {en: sim.adds(P[en][0], base[en], exe_of(en), *v["pyr"]) for en in sim.EXES}
    def var_at(en, s):
        tr = base[en]
        return sim.overlay(tr, exe_of(en), n0_of(tr, v["siz"], ed) * s, al[en], add_n=float(s))
    def flat_at(en, n):
        return flat(base[en], exe_of(en), float(n))
    tr = base["real"]
    sv = min(range(1, 12), key=lambda s: abs(daily(tr, var_at("real", s).net, ALL3, HOLD).mean() - TARGET))
    nf = min(range(1, 40), key=lambda n: abs(daily(tr, flat_at("real", n).net, ALL3, HOLD).mean() - TARGET))
    print("integer sizes: variant base s =", sv, "(buckets", sv, 2 * sv, 3 * sv, "+ adds of", sv, ") | flat N =", nf)
    rows = []
    for en in sim.EXES:
        for lab, o in (("variant", var_at(en, sv)), ("flat", flat_at(en, nf))):
            for per, lo, hi in (("pre 2023-06..2025-09", ALL3, HOLD), ("holdout 2025-10..2026-10", HOLD, None)):
                d = daily(base[en], o.net, lo, hi)
                st = PF.stats_row(d.values, d.index)
                r = dict(exe=en, rule=lab, period=per, rs_day=st["mean_day"], worst_day=st["worst_day"], worst_month=st["worst_month"],
                         losing_months=st["losing_months"], maxdd=st["maxdd"], sharpe=st["sharpe"])
                if en == "real":
                    r["P_lose_month"] = PF.p_losing_month(d.values)
                    r["P_dd_10L_1y"] = PF.p_dd(d.values, 10_00_000, B=2000)
                rows.append(r)
                if en == "real":
                    mk = (base[en].day >= lo) & ((base[en].day < hi) if hi is not None else True)
                    O = PF.outlay(outlay_rows(base[en][mk], o[mk.values]), CAL)
                    O = O[O > 0]
                    print(lab, per, "premium tied up p95 %.0f max %.0f; lots per trade mean %.1f max %.0f" % (O.quantile(.95), O.max(), o.lots[mk.values].mean(), o.lots[mk.values].max()))
    R = pd.DataFrame(rows)
    print(R.round(3).to_string())
    R.to_csv(os.path.join(OUT, "sized.csv"), index=False)
    # concentration: share of holdout net from the best 5 days
    for lab, o in (("variant", var_at("real", sv)), ("flat", flat_at("real", nf))):
        d = daily(tr, o.net, HOLD)
        print(lab, "holdout net %.0f, best 5 days %.0f, without them %.0f" % (d.sum(), d.nlargest(5).sum(), d.sum() - d.nlargest(5).sum()))
    # fill realism at size: order lots vs the contract's traded lots (5 min before the order; whole day)
    pk = P["real"][0]
    V = pk.store.arr["V"][pk.meta.crow.values[tr.row.values]].astype(float)
    lot = tr.lot.values.astype(float)
    cs = np.cumsum(np.nan_to_num(V), axis=1)
    ri = np.arange(len(tr))
    c0, xc = tr.c0.values, np.minimum(tr.xcol.values, 374)
    v5e = (cs[ri, c0 - 1] - cs[ri, np.maximum(c0 - 6, 0)]) / lot
    v5x = (cs[ri, xc - 1] - cs[ri, np.maximum(xc - 6, 0)]) / lot
    o = var_at("real", sv)
    n_e = n0_of(tr, v["siz"], ed) * sv
    fr = pd.DataFrame(dict(und=tr.und, n_entry=n_e, n_exit=o.lots.values, v5e=v5e, v5x=v5x, vday=cs[:, -1] / lot,
                           r_e=n_e / np.maximum(v5e, 1e-9), r_x=o.lots.values / np.maximum(v5x, 1e-9)))
    g = fr.groupby("und").agg(entry_lots_max=("n_entry", "max"), exit_lots_p90=("n_exit", lambda s: s.quantile(.9)), exit_lots_max=("n_exit", "max"),
                              vol5_entry_med=("v5e", "median"), vol5_entry_p10=("v5e", lambda s: s.quantile(.1)),
                              vol5_exit_med=("v5x", "median"), vday_med=("vday", "median"),
                              entry_over_vol5_med=("r_e", "median"), exit_over_vol5_p90=("r_x", lambda s: s.quantile(.9)),
                              share_exit_gt_20pct_vol5=("r_x", lambda s: (s > 0.2).mean()))
    print("fill size vs traded volume, in lots (variant at size):"); print(g.round(3).to_string())
    g.to_csv(os.path.join(OUT, "fill.csv"))
    # POST-HOC diagnostic (after the one holdout test; not used for any choice): every variant's holdout excess vs lots-flat
    exe = exe_of("real")
    hm = (tr.day >= HOLD).values
    pm = ~hm
    out = []
    for vv in VARIANTS:
        o = run_variant(P["real"][0], tr, exe, vv, ed, {})
        k = o.lots[pm].mean()
        f = flat(tr, exe, k)
        out.append(dict(variant=vv["name"], hold_excess_day=daily(tr, o.net - f.net, HOLD).mean(), hold_net_day=daily(tr, o.net, HOLD).mean(),
                        hold_flat_day=daily(tr, f.net, HOLD).mean()))
    D = pd.DataFrame(out)
    print("POST-HOC holdout excess of all variants (1 base unit):"); print(D.round(0).to_string())
    D.to_csv(os.path.join(OUT, "posthoc_hold_all.csv"), index=False)


if __name__ == "__main__":
    {"pre": pre, "holdout": holdout, "sized": sized}[sys.argv[1]]()
