"""RC2 step 1: anatomy of Delta India's BTC/ETH daily options. DESIGN DATA ONLY (expiries before the holdout).
python3 -m research.hunt.rc2.anatomy   -> results/anatomy.md, results/anatomy.json"""
import glob
import gzip
import json
import os

import numpy as np
import pandas as pd

from . import config as C
from . import data

OUTMD = []


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    OUTMD.append(s)


def table(df, floatfmt="{:.3g}"):
    cols = list(df.columns)
    say("| " + " | ".join([df.index.name or ""] + [str(c) for c in cols]) + " |")
    say("|" + "---|" * (len(cols) + 1))
    for i, r in df.iterrows():
        say("| " + " | ".join([str(i)] + [floatfmt.format(v) if isinstance(v, (float, np.floating)) else str(v) for v in r]) + " |")
    say("")


def live_quotes():
    rows = []
    for f in sorted(glob.glob(os.path.join(C.RAW, "delta_live", "snap_*.json.gz"))):
        s = json.load(gzip.open(f, "rt"))
        for r in s["rows"]:
            try:
                K, spot = float(r["K"]), float(r["spot"])
                bid, ask = float(r["bid"] or 0), float(r["ask"] or 0)
            except (TypeError, ValueError):
                continue
            sym = r["sym"]
            exp = pd.Timestamp("20" + sym[-2:] + "-" + sym[-4:-2] + "-" + sym[-6:-4] + " 12:00", tz="UTC")
            rows.append(dict(t=s["t"], coin=sym.split("-")[1], typ=sym[0], K=K, spot=spot, bid=bid, ask=ask,
                             mark=float(r["mark"] or 0), bs=float(r["bs"] or 0), as_=float(r["as_"] or 0),
                             oi=float(r["oi"] or 0), turn=float(r["turn"] or 0),
                             hrs=(exp - pd.Timestamp(s["t"], unit="s", tz="UTC")).total_seconds() / 3600))
    d = pd.DataFrame(rows)
    if d.empty:
        return d
    d["otm"] = np.where(d.typ == "C", d.K / d.spot - 1, 1 - d.K / d.spot)    # + = out of the money
    return d


def section_spreads():
    d = live_quotes()
    say("## 1. Spreads and liquidity on Delta India (live chain)")
    if d.empty:
        say("no snapshots"); return {}
    say(f"{d.t.nunique()} snapshots, {pd.Timestamp(d.t.min(), unit='s')} to {pd.Timestamp(d.t.max(), unit='s')} UTC; "
        f"{len(d)} quotes. Spread = (ask - bid) / mid; both sides quoted only.")
    q = d[(d.bid > 0) & (d.ask > 0)].copy()
    q["mid"] = (q.bid + q.ask) / 2
    q["spr"] = (q.ask - q.bid) / q.mid
    q["mid_bp"] = q.mid / q.spot * 1e4
    q["otm_b"] = pd.cut(q.otm, [-0.2, -0.01, -0.0025, 0.0025, 0.01, 0.02, 0.04, 0.08, 1], labels=[
        "ITM >1%", "ITM .25-1%", "ATM +-.25%", "OTM .25-1%", "OTM 1-2%", "OTM 2-4%", "OTM 4-8%", "OTM >8%"])
    q["hrs_b"] = pd.cut(q.hrs, [0, 6, 24, 48, 24 * 8, 1e5], labels=["<6h", "6-24h", "1-2d", "2-8d", ">8d"])
    out = {}
    for coin in C.COINS:
        g = q[q.coin == coin]
        t = g.groupby(["hrs_b", "otm_b"], observed=True).agg(n=("spr", "size"), spread_med=("spr", "median"),
                                                              mid_bp_of_spot=("mid_bp", "median"),
                                                              ask_size=("as_", "median"))
        t = t[t.n >= 5]
        say(f"### {coin}: median spread by time to expiry and moneyness (n = quotes)")
        t.index = [f"{a} / {b}" for a, b in t.index]
        t.index.name = "expiry / strike"
        table(t)
        out[coin] = t.reset_index().to_dict("records")
        one = d[(d.coin == coin)]
        say(f"{coin}: share of strikes with BOTH bid and ask, by time to expiry: " + ", ".join(
            f"{k}: {v:.0%}" for k, v in one.assign(two=(one.bid > 0) & (one.ask > 0)).groupby(
                pd.cut(one.hrs, [0, 6, 24, 48, 24 * 8, 1e5]), observed=True).two.mean().items()))
        say("")
    # the model the simulator uses: full spread / spot = a + b * mid / spot (near the money, < 8 days)
    n = q[(q.otm.abs() < 0.08) & (q.hrs < 192)]
    model = {}
    for coin, g in n.groupby("coin"):
        A = np.c_[np.ones(len(g)), g.mid / g.spot]
        y = (g.ask - g.bid) / g.spot
        a, b = np.linalg.lstsq(A, y, rcond=None)[0]
        model[coin] = [float(a), float(b)]
        say(f"spread model {coin}: full spread / spot = {a:.6f} + {b:.4f} x mid / spot (n = {len(g)} quotes, "
            f"{g.t.nunique()} snapshots)")
    os.makedirs(C.OUT, exist_ok=True)
    # the simulator's fit is frozen at the pre-registration commit; later polls only go to a "latest" file
    fn = os.path.join(C.OUT, "spread_fit_live.json")
    if os.path.exists(fn):
        fn = os.path.join(C.OUT, "spread_fit_live_latest.json")
    json.dump(model, open(fn, "w"), indent=1)
    say("")
    return out


def design_expiries(coin):
    return [E for E in data.expiries_available(coin) if E < C.HOLDOUT_START]


def section_decay_and_hero(idx):
    say("## 2. Daily-expiry premium decay through the day (Delta marks, design expiries)")
    hrs_marks = [24, 18, 12, 9, 6, 4, 3, 2, 1, 0.5, 1 / 6]
    hero_rows, decay_rows, move_rows = [], [], []
    for coin in C.COINS:
        sc = idx[coin].c
        for E in design_expiries(coin):
            P = data.panel(coin, E)
            if P is None:
                continue
            # ATM straddle (strike nearest spot at each mark time), as % of spot
            for h in hrs_marks:
                T = E - pd.Timedelta(hours=h)
                T = T.floor("5min")
                i = P.bar(T)
                s = sc.get(T - C.BAR, np.nan)
                if i is None or not np.isfinite(s):
                    continue
                ks = [k for k in P.strikes["C"] if k in P.strikes["P"] and P.tradable(("C", k), i) and P.tradable(("P", k), i)]
                if not ks:
                    continue
                k = min(ks, key=lambda x: abs(x - s))
                if abs(k / s - 1) > 0.006:
                    continue
                st = P.arr[("C", k)][i, 0] + P.arr[("P", k)][i, 0]
                decay_rows.append(dict(coin=coin, E=E, h=h, straddle_pct=st / s * 100, K=k, s=s))
            # realised move from 00:00 UTC (12 h left) to settlement vs the 00:00 straddle
            T0 = E - pd.Timedelta(hours=12)
            s0, sE = sc.get(T0 - C.BAR, np.nan), sc.get(E - C.BAR, np.nan)
            st0 = [r for r in decay_rows[-len(hrs_marks):] if r["E"] == E and r["coin"] == coin and r["h"] == 12]
            if st0 and np.isfinite(s0) and np.isfinite(sE):
                move_rows.append(dict(coin=coin, E=E, move_pct=abs(sE / s0 - 1) * 100, straddle_pct=st0[0]["straddle_pct"],
                                      payoff_ratio=abs(sE - st0[0]["K"]) / (st0[0]["straddle_pct"] / 100 * s0)))
            # zero-to-hero: every OTM option whose mark is 0.02%-0.10% of spot at each start time
            for start_h in (6, 4, 3, 2, 1):
                T = E - pd.Timedelta(hours=start_h)
                i = P.bar(T)
                s = sc.get(T - C.BAR, np.nan)
                if i is None or not np.isfinite(s):
                    continue
                dl = P.bar(E - C.LAST_EXIT_BEFORE_EXPIRY)
                best = 0
                for (typ, k), a in P.arr.items():
                    if not P.tradable((typ, k), i):
                        continue
                    px = a[i, 0]
                    otm = k > s if typ == "C" else k < s
                    if not otm or not np.isfinite(px) or not (0.0002 * s <= px <= 0.0010 * s):
                        continue
                    mx = np.nanmax(a[i:dl, 3]) / px      # 5-minute CLOSES: 0.08% of mark bars have a high > 2x open and close
                    end = a[dl, 0] / px
                    hero_rows.append(dict(coin=coin, E=E, start_h=start_h, typ=typ, otm=abs(k / s - 1), px_bp=px / s * 1e4,
                                          mult_max=mx, mult_end=end))
    dec = pd.DataFrame(decay_rows)
    for coin in C.COINS:
        g = dec[dec.coin == coin]
        t = g.groupby("h").straddle_pct.agg(["size", "median", lambda x: x.quantile(0.25), lambda x: x.quantile(0.75)])
        t.columns = ["n days", "median %", "p25 %", "p75 %"]
        t["share of the 24h value"] = t["median %"] / t["median %"].iloc[-1]
        t = t.sort_index(ascending=False)
        t.index = [f"{h:g} h left" for h in t.index]
        t.index.name = f"{coin} ATM straddle"
        say(f"### {coin}: ATM straddle (call + put) as % of spot")
        table(t)
    mv = pd.DataFrame(move_rows)
    say("### Move from 00:00 UTC to settlement vs the 00:00 straddle (what a buyer of the 12-hour straddle got back)")
    for coin in C.COINS:
        g = mv[mv.coin == coin]
        say(f"- {coin}: {len(g)} days; median |move| {g.move_pct.median():.2f}%, median straddle {g.straddle_pct.median():.2f}%; "
            f"payoff / price: mean {g.payoff_ratio.mean():.2f}, median {g.payoff_ratio.median():.2f}; "
            f"share of days the straddle paid back more than its price {(g.payoff_ratio > 1).mean():.0%}")
    say("")
    he = pd.DataFrame(hero_rows)
    say("## 3. Zero to hero: how often does a cheap OTM option (0.02-0.10% of spot) multiply before 11:50 UTC?")
    say("Every such option on every design day counted (per option), and per day (did ANY such option get there). No strike in its first 15 minutes.")
    res = {}
    for coin in C.COINS:
        g = he[he.coin == coin]
        t = g.groupby("start_h").agg(options=("mult_max", "size"), days=("E", "nunique"),
                                     opt_3x=("mult_max", lambda x: (x >= 3).mean()),
                                     opt_5x=("mult_max", lambda x: (x >= 5).mean()),
                                     opt_10x=("mult_max", lambda x: (x >= 10).mean()),
                                     end_value_mean=("mult_end", "mean"), end_zero=("mult_end", lambda x: (x < 0.05).mean()))
        dd = g.groupby(["start_h", "E"]).mult_max.max().groupby("start_h")
        t["any_3x_days"] = dd.apply(lambda x: (x >= 3).mean())
        t["any_5x_days"] = dd.apply(lambda x: (x >= 5).mean())
        t["any_10x_days"] = dd.apply(lambda x: (x >= 10).mean())
        t = t.sort_index(ascending=False)
        t.index = [f"{h} h before (UTC {12 - h:02d}:00)" for h in t.index]
        t.index.name = f"{coin} bought"
        say(f"### {coin}")
        table(t, "{:.2f}")
        res[coin] = t.reset_index().to_dict("records")
    say("- opt_Nx: share of cheap options whose 5-minute mark CLOSE reached N x the entry mark before 11:50 UTC (before costs).")
    say("- end_value_mean: the mark at 11:50 / entry (mean); end_zero: share worth < 5% of entry at 11:50.")
    say("")
    return dec, mv, he


def section_time_of_day(idx, dvol):
    say("## 4. Intraday move sizes vs implied, by hour (UTC); design span only")
    out = {}
    for coin in C.COINS:
        b = idx[coin]
        b = b[(b.index < C.HOLDOUT_START)]
        c = b.c
        r1 = np.log(c).diff(12)                      # 1-hour log return ending at each bar
        hourly = r1[b.index.minute == 55]            # the hour ending at HH:00
        T = hourly.index + C.BAR
        dv = dvol[coin].copy()
        dv.index = dv.index + pd.Timedelta(hours=1)
        iv = dv.reindex(dv.index.union(T)).ffill().reindex(T).to_numpy() / 100
        imp = iv * np.sqrt(1 / 8760)
        df = pd.DataFrame({"hour_end": (T.hour), "abs_ret": np.abs(hourly.to_numpy()), "imp": imp}).dropna()
        t = df.groupby("hour_end").agg(n=("abs_ret", "size"), median_abs_move_pct=("abs_ret", lambda x: x.median() * 100),
                                       realised_vs_implied=("abs_ret", lambda x: np.sqrt((x ** 2).mean())))
        t["realised_vs_implied"] = t["realised_vs_implied"] / df.groupby("hour_end").imp.apply(lambda x: np.sqrt((x ** 2).mean()))
        t.index = [f"{(h - 1) % 24:02d}-{h:02d} UTC ({((h - 1) % 24 * 60 + 330) // 60 % 24:02d}:{30:02d} IST)" for h in t.index]
        t.index.name = f"{coin} hour"
        say(f"### {coin}: |1-hour move| and realised / DVOL-implied (RMS), by hour")
        table(t, "{:.3f}")
        out[coin] = t.reset_index().to_dict("records")
    return out


def section_big_days(idx, dvol, funding):
    say("## 5. What separates big-move days (UTC day range in its top 20%) from the rest? Known at 00:00 UTC")
    rows = []
    for coin in C.COINS:
        b = idx[coin][idx[coin].index < C.HOLDOUT_START]
        g = b.groupby(b.index.floor("D"))
        day = pd.DataFrame({"rng": (g.h.max() / g.l.min() - 1) * 100, "n": g.c.size(), "ret": (g.c.last() / g.o.first() - 1) * 100})
        day = day[day.n == 288]
        day["prev_rng"] = day.rng.shift(1)
        day["prev_abs_ret"] = day.ret.abs().shift(1)
        dv = dvol[coin]
        day["dvol_0"] = dv.reindex(day.index - pd.Timedelta(hours=1)).to_numpy()
        day["dvol_chg_24h"] = day.dvol_0 - dv.reindex(day.index - pd.Timedelta(hours=25)).to_numpy()
        f = funding[coin]
        day["funding_8h_bp"] = f.reindex(day.index - pd.Timedelta(hours=1)).to_numpy() * 1e4
        day["weekend"] = day.index.dayofweek >= 5
        day["coin"] = coin
        day["big"] = day.rng >= day.rng.quantile(0.8)
        rows.append(day)
    d = pd.concat(rows).dropna()
    t = d.groupby(["coin", "big"])[["rng", "prev_rng", "prev_abs_ret", "dvol_0", "dvol_chg_24h", "funding_8h_bp", "weekend"]].median()
    t.loc[:, "weekend"] = d.groupby(["coin", "big"]).weekend.mean()
    t["n days"] = d.groupby(["coin", "big"]).size()
    t.index = [f"{c} {'big' if b else 'other'}" for c, b in t.index]
    t.index.name = "medians (weekend = share)"
    table(t, "{:.2f}")
    # simple rank correlations with the day's range
    for coin in C.COINS:
        g = d[d.coin == coin]
        cs = {k: g[k].rank().corr(g.rng.rank()) for k in ["prev_rng", "prev_abs_ret", "dvol_0", "dvol_chg_24h", "funding_8h_bp"]}
        say(f"- {coin} rank correlation with the day's range (n = {len(g)} days): " + ", ".join(f"{k} {v:+.2f}" for k, v in cs.items()))
    say("")
    return d


def main():
    idx = {c: data.index5m(c) for c in C.COINS}
    dvol = {c: data.dvol1h(c) for c in C.COINS}
    funding = {c: data.funding1h(c) for c in C.COINS}
    say("# RC2 anatomy (design data only: Delta expiries before 2026-04-13)")
    say("")
    out = {"spreads": section_spreads()}
    dec, mv, he = section_decay_and_hero(idx)
    out["hero"] = he.groupby(["coin", "start_h"]).mult_max.describe().reset_index().to_dict("records")
    out["time_of_day"] = section_time_of_day(idx, dvol)
    section_big_days(idx, dvol, funding)
    days = {c: len(design_expiries(c)) for c in C.COINS}
    say(f"Design expiries with Delta chains: {days}")
    os.makedirs(C.OUT, exist_ok=True)
    open(os.path.join(C.OUT, "anatomy.md"), "w").write("\n".join(OUTMD) + "\n")
    json.dump(out, open(os.path.join(C.OUT, "anatomy.json"), "w"), indent=1, default=str)


if __name__ == "__main__":
    main()
