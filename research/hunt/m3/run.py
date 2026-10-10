"""M3 runner: signals + simulated trades + random baselines for one option underlying.
usage: python3 run.py SYM   -> scratchpad/hunt/m3/work/trades_SYM.parquet, rand_SYM.parquet
Trades carry gross only; costs/spreads applied in analyze.py."""
import sys, math
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M

SYM = sys.argv[1]
NR = 200
rng = np.random.default_rng(20261008)
m = M.Market(SYM)
fam = M.FAMILY[SYM]
su = m.stop_unit()
print(SYM, "minutes", len(m.ts), "step", m.step, "expiries", [str(x)[:10] for x in m.exp_days], flush=True)

days = [pd.Timestamp(d) for d in m.days]

# ---------------------------------------------------------------- event calendars
def eia_crude():
    e = pd.read_csv(M.S / "hunt" / "strad_crude" / "work" / "eia_ts.csv")
    t = pd.to_datetime(e.iloc[:, 0]).dt.tz_localize(None)
    return {pd.Timestamp(x.normalize()): x.hour * 60 + x.minute for x in t}


def eia_gas():
    """EIA weekly natural gas storage: Thursday 10:30 ET; Thanksgiving week Wed 12:00 ET; Christmas/New Year 2025-26
    weeks Wed 12:00 ET (approximation, pre-registered)."""
    out = {}
    special = {pd.Timestamp("2025-11-27"): (pd.Timestamp("2025-11-26"), 12, 0),
               pd.Timestamp("2025-12-25"): (pd.Timestamp("2025-12-24"), 12, 0),
               pd.Timestamp("2026-01-01"): (pd.Timestamp("2025-12-31"), 12, 0)}
    for d in pd.date_range("2025-08-01", "2026-10-10", freq="W-THU"):
        if d in special:
            dd, hh, mm = special[d]
        else:
            dd, hh, mm = d, 10, 30
        out[dd] = M.us_to_ist_min(dd, hh, mm)
    return out


rules = {}
rules["LIQ15"] = M.liq_signals(m, 15, su)
rules["LIQ5"] = M.liq_signals(m, 5, su)
rules["H60"] = M.h60_signals(m, su)
uo = M.US_OPEN[fam]
win = {}
for d in days:
    o = M.us_to_ist_min(d, *uo)
    win[d.to_datetime64()] = (o, o + 15, o + 120)
rules["USORB"] = M.range_break_signals(m, win, "USORB", 60)
win = {d.to_datetime64(): (540, 570, 720) for d in days}
rules["INORB"] = M.range_break_signals(m, win, "INORB", 60)
if fam in ("crude", "natgas"):
    cal = eia_crude() if fam == "crude" else eia_gas()
    win = {d.to_datetime64(): (r - 15, r, r + 30) for d, r in cal.items()}
    rules["EVT"] = M.range_break_signals(m, win, "EVT", 30)
# --- amendment 1: EVE, MOMA, MOMB
win = {d.to_datetime64(): (17 * 60, 19 * 60, 22 * 60) for d in days}
rules["EVE"] = [dict(x, cut_tod=23 * 60 + 15) for x in M.range_break_signals(m, win, "EVE", 120)]
di = pd.Series(np.arange(len(m.ts))).groupby(m.day)
first_last = {d: (ix.values[0], ix.values[-1]) for d, ix in di}
dl = sorted(first_last)


def at_tod(d, tmin):
    a, b = first_last[d]
    ix = np.arange(a, b + 1)
    ok = ix[m.tod[ix] >= tmin]
    return int(ok[0]) if len(ok) else -1


def time_sig(e, side, book, cut_tod):
    return dict(e=int(e), side=int(side), sigF=float(m.F[e - 1]), level=float(m.F[e - 1]), stopF=-side * 1e12,
                tgtF=np.nan, fb=-1, nl=-1, tstop=-1, tgain=0.0, maxh=10 ** 6, unit=1.0, book=book, cut_tod=cut_tod)


rules["MOMA"] = []
for k in range(1, len(dl)):
    d, pd_ = dl[k], dl[k - 1]
    if m.seg[first_last[d][0]] != m.seg[first_last[pd_][1]]:
        continue  # contract roll between the two sessions
    i1 = at_tod(d, 19 * 60 + 30); e = at_tod(d, 22 * 60 + 30)
    if i1 < 0 or e < 0 or m.tod[i1] > 19 * 60 + 35 or m.tod[e] > 22 * 60 + 35:
        continue
    r = m.F[i1] - m.F[first_last[pd_][1]]
    if r == 0:
        continue
    rules["MOMA"].append(time_sig(e, np.sign(r), "MOMA", 23 * 60 + 30))
if fam == "crude":
    rules["MOMB"] = []
    for dd, rel in eia_crude().items():
        d = dd.to_datetime64()
        if d not in first_last:
            continue
        i0, i1 = at_tod(d, rel), at_tod(d, rel + 30)
        endm = m.tod[first_last[d][1]]
        e = at_tod(d, endm - 60)
        if min(i0, i1, e) < 0 or e <= i1:
            continue
        r = m.F[i1] - m.F[i0]
        if r == 0:
            continue
        rules["MOMB"].append(time_sig(e, np.sign(r), "MOMB", 24 * 60))
for k, v in rules.items():
    print(k, "signals", len(v), flush=True)

TF = {"LIQ15": 15, "LIQ5": 5, "H60": 5}


def one_at_a_time(sigs):
    """Within a book, a signal is taken only after the previous trade (as simulated) has exited."""
    return sorted(sigs, key=lambda s: s["e"])


def run_book(rule, sigs, inst):
    rows, busy_until = [], -1
    for s in one_at_a_time(sigs):
        if s["e"] <= busy_until:
            continue
        d = m.day[s["e"]]
        if inst == "OPT" and m.is_exp[s["e"]]:
            continue
        cut = m.day_cut[d]
        if s.get("cut_tod"):
            a_, b_ = first_last[d]
            ix = np.arange(a_, b_ + 1); ix = ix[m.tod[ix] <= s["cut_tod"]]
            cut = min(cut, int(ix[-1]))
        if s["e"] >= cut:
            continue
        r = M.sim_option(m, s, cut) if inst == "OPT" else M.sim_fut(m, s, cut)
        if r is None:
            continue
        busy_until = s["e"] + r["hold"]
        rows.append(dict(s, **r, rule=rule, inst=inst, day=pd.Timestamp(d), tod=int(m.tod[s["e"]])))
    return rows


# random-entry candidate pool by (month, tod) -> minute indices
mon = pd.DatetimeIndex(m.day).to_period("M").astype(str).values
ok_min = (m.tod >= M.FIRST_ENTRY) & (m.tod <= M.LAST_ENTRY) & (np.array([m.day_cut[d] for d in m.day]) > np.arange(len(m.ts)))
by_month = {}
for i in np.where(ok_min)[0]:
    by_month.setdefault(mon[i], []).append(i)
by_month = {k: np.array(v) for k, v in by_month.items()}


def fb_random(e, side, level, tf, cut):
    if tf is None:
        return -1
    F = m.F[e:cut + 1]; t = m.tod[e:cut + 1]
    endbar = ((t - M.OPEN_M) % tf) == tf - 1
    hit = np.where(endbar & (side * (F - level) < 0))[0]
    return int(e + hit[0]) if len(hit) else -1


def randoms(tr, inst):
    """NR random entries per trade: same month, same time of day +/-30 min, random side, same exit distances."""
    out = []
    for j, s in enumerate(tr):
        pool = by_month[mon[s["e"]]]
        pool = pool[np.abs(m.tod[pool] - s["tod"]) <= 30]
        if inst == "OPT":
            pool = pool[~m.is_exp[pool]]
        if len(pool) == 0:
            continue
        so = s["side"]
        dl = so * (s["sigF"] - s["level"]); ds = so * (s["sigF"] - s["stopF"])
        dtg = so * (s["tgtF"] - s["sigF"]) if np.isfinite(s["tgtF"]) else np.nan
        picks = rng.choice(pool, NR); sides = rng.choice([-1, 1], NR)
        for r_, (e, sd) in enumerate(zip(picks, sides)):
            e = int(e); sd = int(sd)
            cut = m.day_cut[m.day[e]]
            if s.get("cut_tod"):
                a_, b_ = first_last[m.day[e]]
                ix = np.arange(a_, b_ + 1); ix = ix[m.tod[ix] <= s["cut_tod"]]
                cut = min(cut, int(ix[-1]))
                if e >= cut:
                    continue
            sigF = m.F[e - 1]
            level = sigF - sd * dl
            g = dict(e=e, side=sd, sigF=sigF, level=level, stopF=sigF - sd * ds,
                     tgtF=sigF + sd * dtg if np.isfinite(dtg) else np.nan,
                     fb=fb_random(e, sd, level, TF.get(s["rule"]), cut), nl=-1, tstop=s["tstop"], tgain=s["tgain"],
                     maxh=s["maxh"], unit=s["unit"])
            if s["rule"] in ("MOMA", "MOMB"):
                g["stopF"] = -sd * 1e12; g["level"] = sigF
            r = M.sim_option(m, g, cut) if inst == "OPT" else M.sim_fut(m, g, cut)
            if r is None:
                continue
            out.append((j, r_, e, sd, r["fill"], r["exit"], int(m.tod[e]), pd.Timestamp(m.day[e])))
    return pd.DataFrame(out, columns=["j", "r", "e", "side", "fill", "exit", "tod", "day"])


insts = ["OPT"]
if SYM in M.MINI_FUT.values() or (SYM == "CRUDEOIL"):
    insts.append("FUT")  # mini futures signals come from the mini's own (or, for crude, CRUDEOIL's identical) price
allT, allR = [], []
for inst in insts:
    fsym = M.MINI_FUT[fam] if inst == "FUT" else SYM
    for rule, sigs in rules.items():
        tr = run_book(rule, sigs, inst)
        if not tr:
            continue
        T = pd.DataFrame(tr)
        T["sym"] = fsym; T["und"] = SYM
        R = randoms(tr, inst)
        R["rule"] = rule; R["inst"] = inst; R["sym"] = fsym; R["und"] = SYM
        allT.append(T); allR.append(R)
        print(SYM, inst, rule, "trades", len(T), "random", len(R), flush=True)
T = pd.concat(allT, ignore_index=True)
R = pd.concat(allR, ignore_index=True)
T.to_parquet(M.WORK / f"trades_{SYM}.parquet", index=False)
R.to_parquet(M.WORK / f"rand_{SYM}.parquet", index=False, compression="zstd")
# descriptive data for "best hours": hourly abs futures move and option volume share by IST hour (design only)
d = pd.DataFrame({"day": m.day, "tod": m.tod, "F": m.F})
d = d[d.day <= M.DESIGN_END.to_datetime64()]
d["h"] = d.tod // 60
hh = d.groupby(["day", "h"]).F.agg(["first", "last", "max", "min"])
hh["absmove"] = (hh["last"] / hh["first"] - 1).abs() * 100
hh["range"] = (hh["max"] / hh["min"] - 1) * 100
H = hh.groupby("h")[["absmove", "range"]].mean()
O = m.O.copy(); O["h"] = m.tod[O.i.values] // 60; O["day"] = m.day[O.i.values]
O = O[(O.day <= M.DESIGN_END.to_datetime64()) & (O.k.abs() <= 1)]
H["optvol_share"] = O.groupby("h").volume.sum() / O.volume.sum() * 100
H["sym"] = SYM
H.reset_index().to_csv(M.WORK / f"hours_{SYM}.csv", index=False)
# premium level for capital fit: ATM call premium x lot at 15:00 per day
P = pd.DataFrame({"day": m.day, "tod": m.tod, "s": m.strad})
P = P[P.tod == 15 * 60].dropna()
P["atm_call_rs"] = P.s / 2 * M.MULT[SYM]
P["sym"] = SYM
P[["day", "sym", "atm_call_rs"]].to_csv(M.WORK / f"prem_{SYM}.csv", index=False)
print("done", SYM, flush=True)
