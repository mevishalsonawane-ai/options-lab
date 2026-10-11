"""R13b: Boss's exact rule.
"If MR(14) touches 28.57 and the NEXT weekly candle is green (close > open), NIFTY bounces > 1,000 points."

Touch  = new weekly MR14 <= 28.57 (from study.py; both 'cc' and 'co' MR definitions).
Green  = weekly close > weekly open.
Reading STRICT: week right after the touch must be green, else the setup is void.
Reading WAIT  : first green week within 8 weeks after the touch.
Entry  E1 = close of the green week (Friday close);  E2 = next week's first open.
Barrier tests use DAILY high/low after entry; if target and stop are both inside one day, it counts as the STOP (conservative).
Baseline = every green week in the same period (any MR), same entry & tests.
Run: venv/bin/python -I boss.py  (imports study.py, which re-runs and rewrites study_out.md)
"""
import io, contextlib, math, sys, os
import numpy as np, pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
with contextlib.redirect_stdout(io.StringIO()):
    import study as S

W, D, mr, touches = S.W, S.D, S.mr, S.touches
rng = np.random.default_rng(1313)
REPS = 10_000
L = []


def p(*a):
    s = " ".join(str(x) for x in a); L.append(s); print(s)


green = W["close"] > W["open"]
pos = {t: i for i, t in enumerate(W.index)}
dates = D.index
O, H, Lo, C = D["open"].values, D["high"].values, D["low"].values, D["close"].values
WEEKS_TD = {4: 20, 8: 40, 13: 65, 26: 130}


def first_day_after(week_label):
    return dates.searchsorted(week_label, side="right")


def setups(mode, reading):
    m = mr(W, 14, mode)
    new, _ = touches(m, 100 * 4 / 14, "low")
    out = []
    for t in new[new].index:
        k = pos[t]
        g = None
        if reading == "strict":
            if k + 1 < len(W) and green.iloc[k + 1]:
                g = k + 1
            elif k + 1 >= len(W):
                out.append(dict(touch=t, g=None, status="pending (next week not closed)")); continue
        else:
            for j in range(k + 1, min(k + 9, len(W))):
                if green.iloc[j]:
                    g = j; break
            if g is None and k + 8 >= len(W):
                out.append(dict(touch=t, g=None, status="waiting")); continue
        out.append(dict(touch=t, g=g, status="signal" if g is not None else "void"))
    return out, m


def path_stats(g, entry):
    """g = weekly index of the green (signal) week. returns dict of MFE pts / %, barrier outcomes."""
    i0 = first_day_after(W.index[g])
    if i0 >= len(dates):
        return None
    ep = C[i0 - 1] if entry == "E1" else O[i0]
    r = dict(ep=ep, i0=i0)
    for w, n in WEEKS_TD.items():
        if i0 + n > len(dates):
            r[f"mfe{w}"] = np.nan; r[f"mfep{w}"] = np.nan; continue
        hi = H[i0:i0 + n].max()
        r[f"mfe{w}"] = hi - ep; r[f"mfep{w}"] = hi / ep - 1
    # barriers, cap 26 weeks
    for tag, up, dn, pct in [("1000/500", 1000, 500, False), ("1000/1000", 1000, 1000, False),
                             ("4%/2%", .04, .02, True), ("4%/4%", .04, .04, True)]:
        tgt = ep * (1 + up) if pct else ep + up
        stp = ep * (1 - dn) if pct else ep - dn
        res, days = "none", np.nan
        for j in range(i0, min(i0 + 130, len(dates))):
            lo_hit = Lo[j] <= stp; hi_hit = H[j] >= tgt
            if j == i0 and entry == "E1":
                lo_hit = lo_hit or O[j] <= stp; hi_hit = hi_hit or O[j] >= tgt
            if lo_hit:
                res = "stop"; days = j - i0 + 1; break
            if hi_hit:
                res = "target"; days = j - i0 + 1; break
        if res == "none" and i0 + 130 > len(dates):
            res = "open"
        r[tag] = res; r[tag + "_d"] = days
    return r


def baseline(start, entry):
    rows = []
    for k in range(len(W)):
        if W.index[k] < start or not green.iloc[k] or k < 15:
            continue
        s = path_stats(k, entry)
        if s:
            s["wk"] = W.index[k]; rows.append(s)
    return pd.DataFrame(rows)


def hit_rate(df, tag):
    d = df[df[tag].isin(["target", "stop", "none"])]
    return (d[tag] == "target").mean(), len(d), d


def boot_hit(base_bool, n, obs):
    b = base_bool.values.astype(float)
    s = b[rng.integers(0, len(b), size=(REPS, n))].mean(1)
    return (s >= obs - 1e-12).mean()


def boot_mean(base, n, obs):
    b = base.values
    s = b[rng.integers(0, len(b), size=(REPS, n))].mean(1)
    return (s >= obs).mean()


PER = [pd.Timestamp("2006-01-01"), pd.Timestamp("2015-01-01"), pd.Timestamp("2020-01-01")]
p("# R13b Boss rule output\n")
BASE = {(st, e): baseline(st, e) for st in PER for e in ["E1", "E2"]}

for mode in ["cc", "co"]:
    for reading in ["strict", "wait"]:
        su, m = setups(mode, reading)
        p(f"\n## MR mode={mode}, reading={reading}")
        p("| touch week | green week | wait (wks) | status | entry E1 (green close) | MFE 4w pts | 8w | 13w | 26w | MFE 26w % | +1000 before -500? | days | +1000 before -1000? | +4% before -2%? |")
        p("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        rows = []
        for s in su:
            if s["g"] is None:
                p(f"| {s['touch'].date()} | - | - | {s['status']} | | | | | | | | | | |"); continue
            st = path_stats(s["g"], "E1")
            st2 = path_stats(s["g"], "E2")
            if st is None:
                p(f"| {s['touch'].date()} | {W.index[s['g']].date()} | {s['g']-pos[s['touch']]} | signal, entry next week | | | | | | | | | | |"); continue
            st.update(touch=s["touch"], wk=W.index[s["g"]], wait=s["g"] - pos[s["touch"]])
            rows.append((st, st2))
            f = lambda v: "open" if np.isnan(v) else f"{v:+.0f}"
            pct26 = "open" if np.isnan(st["mfep26"]) else f"{st['mfep26']*100:+.1f}%"
            p(f"| {s['touch'].date()} | {st['wk'].date()} | {st['wait']} | signal | {st['ep']:.0f} | {f(st['mfe4'])} | {f(st['mfe8'])} | {f(st['mfe13'])} | {f(st['mfe26'])} | "
              f"{pct26} | {st['1000/500']} | {st['1000/500_d']:.0f} | {st['1000/1000']} | {st['4%/2%']} |")
        nv = sum(1 for s in su if s["status"] == "void")
        p(f"\nsetups: {len(su)} touches -> {len(rows)} signals with outcomes, {nv} void, "
          f"{sum(1 for s in su if s['status'] not in ('signal','void'))} pending/waiting")
        for entry in ["E1", "E2"]:
            for st0 in PER:
                sig = pd.DataFrame([(a if entry == "E1" else {**b, 'wk': a['wk']}) for a, b in rows if a["wk"] >= st0 and b is not None])
                if len(sig) == 0:
                    continue
                base = BASE[(st0, entry)]
                p(f"\n#### mode={mode} {reading} entry={entry} from {st0.date()}: n={len(sig)} signals vs {len(base)} green weeks")
                p("| measure | signals | baseline (all green weeks) | p (bootstrap, signals >= baseline) |")
                p("|---|---|---|---|")
                for w in [4, 8, 13, 26]:
                    s_ = sig[f"mfe{w}"].dropna(); b_ = base[f"mfe{w}"].dropna()
                    if len(s_):
                        p(f"| median max rise within {w}w, points | {s_.median():+.0f} (mean {s_.mean():+.0f}, n={len(s_)}) | {b_.median():+.0f} (mean {b_.mean():+.0f}) | {boot_mean(b_, len(s_), s_.mean()):.3f} |")
                for w in [4, 8, 13, 26]:
                    s_ = sig[f"mfep{w}"].dropna(); b_ = base[f"mfep{w}"].dropna()
                    if len(s_):
                        p(f"| median max rise within {w}w, % | {s_.median()*100:+.1f}% (mean {s_.mean()*100:+.1f}%) | {b_.median()*100:+.1f}% (mean {b_.mean()*100:+.1f}%) | {boot_mean(b_, len(s_), s_.mean()):.3f} |")
                for w in [4, 8, 13, 26]:
                    s_ = sig[f"mfe{w}"].dropna(); b_ = base[f"mfe{w}"].dropna()
                    if len(s_):
                        p(f"| reached +1000 pts within {w}w (any path) | {(s_>=1000).mean()*100:.0f}% | {(b_>=1000).mean()*100:.0f}% | {boot_hit(b_>=1000, len(s_), (s_>=1000).mean()):.3f} |")
                for tag in ["1000/500", "1000/1000", "4%/2%", "4%/4%"]:
                    hs, ns, d_s = hit_rate(sig, tag); hb, nb, d_b = hit_rate(base, tag)
                    if ns == 0:
                        continue
                    tt = d_s[d_s[tag] == "target"][tag + "_d"]
                    tb = d_b[d_b[tag] == "target"][tag + "_d"]
                    p(f"| +{tag.split('/')[0]} before -{tag.split('/')[1]} (26w cap) | {hs*100:.0f}% of {ns}; median {tt.median()/5 if len(tt) else float('nan'):.1f} wks to target | "
                      f"{hb*100:.0f}% of {nb}; median {tb.median()/5 if len(tb) else float('nan'):.1f} wks | {boot_hit(d_b[tag]=='target', ns, hs):.3f} |")


# ---------------------------------------------------------------- trades for the exact rule
def sim(i0, ep, rule, g, m):
    """walk daily bars from i0. returns exit index, price, reason."""
    kind, a, b, cap = rule
    cap_label = W.index[min(g + cap, len(W) - 1)]
    if g + cap >= len(W):
        return None
    last = dates.searchsorted(cap_label, side="right") - 1
    if kind in ("pts", "pct"):
        tgt = ep + a if kind == "pts" else ep * (1 + a)
        stp = ep - b if kind == "pts" else ep * (1 - b)
        for j in range(i0, last + 1):
            if O[j] <= stp: return j, O[j], "stop(gap)"
            if O[j] >= tgt and j > i0: return j, O[j], "target(gap)"
            if Lo[j] <= stp: return j, stp, "stop"
            if H[j] >= tgt: return j, tgt, "target"
        return last, C[last], "time"
    if kind == "mr":
        for kk in range(g + 1, g + cap):
            if m.iloc[kk] > 50:
                j = dates.searchsorted(W.index[kk], side="right")
                return j, O[j], "MR>50"
        return last, C[last], "time"
    return last, C[last], "time"


RULES = {"hold 4w": ("time", 0, 0, 4), "hold 8w": ("time", 0, 0, 8), "hold 13w": ("time", 0, 0, 13),
         "13w or MR>50": ("mr", 0, 0, 13),
         "+1000/-500 pts, 13w cap": ("pts", 1000, 500, 13), "+4%/-2%, 13w cap": ("pct", .04, .02, 13)}


def trades(mode, reading, entry, rule, start):
    su, m = setups(mode, reading)
    out = []; busy = -1
    for s in su:
        if s["g"] is None or W.index[s["g"]] < start:
            continue
        g = s["g"]; i0 = first_day_after(W.index[g])
        if i0 >= len(dates) or i0 <= busy:
            continue
        ep = C[i0 - 1] if entry == "E1" else O[i0]
        r = sim(i0, ep, rule, g, m)
        if r is None:
            continue
        j, xp, why = r
        busy = j
        out.append(dict(sig=W.index[g], entry=dates[i0 - 1] if entry == "E1" else dates[i0], exit=dates[j], ep=ep, xp=xp, why=why, i0=i0, j=j))
    return pd.DataFrame(out)


def opt(t, tenor):
    d0 = t.i0  # buy at first open after green week (E2 timing for options)
    S0 = O[d0]; v0 = D["vix"].ffill().values[d0 - 1]
    if np.isnan(v0):
        return None
    K = round(S0 / 50) * 50
    T0 = tenor / 365
    held = (dates[t.j] - dates[d0]).days
    T1 = max(tenor - held, 0) / 365
    v1 = D["vix"].ffill().values[t.j]
    S1 = t.xp
    c0 = S.bs_call(S0, K, T0, S.RFR, S.DIVY, v0 / 100) * 1.01
    c1 = S.bs_call(S1, K, T1, S.RFR, S.DIVY, v1 / 100) * 0.99
    cost = 40 * 1.18 + 0.001 * c1 * S.LOT + 0.00003 * c0 * S.LOT + 0.0005 * (c0 + c1) * S.LOT
    return dict(ret=c1 / c0 - 1, rs=(c1 - c0) * S.LOT - cost, prem=c0 * S.LOT)


p("\n## Trades for Boss's exact rule (MR cc touch, STRICT and WAIT readings)")
p(f"futures 1 lot = {S.LOT}, same cost model as study.py; ETF Rs 1,00,000 compounding; option = BS approximation with India VIX, 1% spread/side.")
for reading in ["strict", "wait"]:
    for st0 in S.PERIODS:
        p(f"\n### reading={reading}, from {st0.date()}")
        p("| entry | exit rule | trades | win% | avg % | total Rs fut | avg Rs fut | worst Rs | ETF end Rs | ETF CAGR | B&H CAGR | 35d call: win% / avg ret / total Rs | 63d call: win% / avg ret / total Rs |")
        p("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for entry in ["E1", "E2"]:
            for name, rule in RULES.items():
                tr = trades("cc", reading, entry, rule, st0)
                if len(tr) == 0:
                    continue
                tr = S.fut_pnl(tr)
                eq, cagr, mdd, bhc, bhdd, yrs = S.etf_curve(tr, st0)
                oc = []
                for tenor, maxw in [(35, 4), (63, 8)]:
                    os_ = [opt(t, tenor) for _, t in tr.iterrows() if (t.exit - t.entry).days <= maxw * 7 + 3]
                    os_ = [x for x in os_ if x]
                    if os_:
                        o = pd.DataFrame(os_)
                        oc.append(f"{(o.rs>0).mean()*100:.0f}% / {o.ret.mean()*100:+.0f}% / {o.rs.sum():+,.0f} (n={len(o)})")
                    else:
                        oc.append("n/a (hold too long)")
                p(f"| {entry} | {name} | {len(tr)} | {(tr.rs>0).mean()*100:.0f} | {tr.pct.mean()*100:+.2f}% | {tr.rs.sum():+,.0f} | {tr.rs.mean():+,.0f} | {tr.rs.min():+,.0f} | "
                  f"{eq:,.0f} | {cagr*100:+.1f}% | {bhc*100:+.1f}% | {oc[0]} | {oc[1]} |")

tr = S.fut_pnl(trades("cc", "wait", "E2", RULES["+1000/-500 pts, 13w cap"], S.PERIODS[0]))
p("\n### Trade list: WAIT reading, entry next-week open, +1000/-500 pts target/stop, 13w cap")
p("| green week | entry | exit | why | entry px | exit px | pts | Rs 1 lot fut |")
p("|---|---|---|---|---|---|---|---|")
for _, t in tr.iterrows():
    p(f"| {t.sig.date()} | {t.entry.date()} | {t.exit.date()} | {t.why} | {t.ep:.0f} | {t.xp:.0f} | {t.xp-t.ep:+.0f} | {t.rs:+,.0f} |")

p(f"\nLatest weekly bar {W.index[-1].date()}: open {W['open'].iloc[-1]:.0f} close {W['close'].iloc[-1]:.0f} "
  f"({'green' if green.iloc[-1] else 'red'}); MR14 cc = {mr(W,14,'cc').iloc[-1]:.2f}")
open(f"{S.OUT}/boss_out.md", "w").write("\n".join(L))
