"""NN-CRUDE market facts, intraday: hour-of-day activity, ranges, persistence, EIA/CPI behaviour, options, costs."""
import sys, glob
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *

out = open(f"{LOGS}/analyze_intraday.txt", "w")
def P(*a):
    s = " ".join(str(x) for x in a); print(s); out.write(s + "\n")
pd.set_option("display.width", 220)

b = pd.read_parquet(f"{D}/mcx_min.parquet")
b["r1"] = np.log(b.spot).groupby(b.day).diff()
b["hour"] = b.index.hour
P(f"== MCX CRUDEOIL near-month minutes (Dhan rolling 'spot'): {b.day.nunique()} days {b.index.min():%Y-%m-%d} .. {b.index.max():%Y-%m-%d}")
P("session: first bar", b.groupby("day").apply(lambda x: x.index.min().strftime("%H:%M")).value_counts().head(3).to_dict(),
  "last bar", b.groupby("day").apply(lambda x: x.index.max().strftime("%H:%M")).value_counts().head(4).to_dict())

# 1) when do moves happen
fut = pd.concat([pd.read_parquet(f) for f in glob.glob(f"{D}/min_CRUDEOIL_5*.parquet")])
fut = fut[~fut.index.duplicated()]
fv = fut.volume.groupby(fut.index.hour).sum()
h = pd.DataFrame({"share_abs_move_%": (b.r1.abs().groupby(b.hour).sum() / b.r1.abs().sum() * 100).round(1),
                  "share_variance_%": ((b.r1 ** 2).groupby(b.hour).sum() / (b.r1 ** 2).sum() * 100).round(1),
                  "mean_abs_1h_move_%": (b.groupby(["day", "hour"]).r1.sum().abs().groupby("hour").mean() * 100).round(3),
                  "fut_volume_share_%": (fv / fv.sum() * 100).round(1),
                  "opt_volume_share_%": ((b.optvol_c + b.optvol_p).groupby(b.hour).sum() / (b.optvol_c + b.optvol_p).sum() * 100).round(1)})
P("\n== Activity by IST hour (hour 9 = 09:00-09:59). Futures volume = live contracts Jun-Oct 2026 only")
P(h.to_string())

# 2) daily range / gap / high-low timing from minutes
dd = b.groupby("day").agg(o=("spot", "first"), c=("spot", "last"), hi=("spot", "max"), lo=("spot", "min"), roll=("roll_day", "first"))
dd["gap"] = np.log(dd.o / dd.c.shift()); dd.loc[dd.roll, "gap"] = np.nan
dd["rng_pct"] = (dd.hi - dd.lo) / dd.o * 100
dd["rng_rs_lot"] = (dd.hi - dd.lo) * LOT["CRUDEOIL"]
P("\n== Daily range from minutes (LTP; no wick data). By month:")
mm = dd.groupby(dd.index.to_period("M")).agg(days=("o", "count"), range_pct=("rng_pct", "mean"), range_rs_per_lot=("rng_rs_lot", "mean"),
                                             abs_gap_pct=("gap", lambda s: (s.abs() * 100).mean()), close=("c", "last"))
P(mm.round(2).to_string())
hi_t = b.loc[b.groupby("day").spot.idxmax()].hour.value_counts(normalize=True).sort_index() * 100
lo_t = b.loc[b.groupby("day").spot.idxmin()].hour.value_counts(normalize=True).sort_index() * 100
P("\n== Hour in which the day's HIGH / LOW is set (% of days)")
P(pd.DataFrame({"high_%": hi_t.round(1), "low_%": lo_t.round(1)}).T.to_string())

# 3) intraday persistence
P("\n== Intraday persistence: corr of consecutive k-minute returns (same day), and morning->evening")
for k in (5, 15, 30, 60):
    rk = b.spot.groupby(b.day).apply(lambda s: np.log(s.resample(f"{k}min").last().dropna()).diff()).dropna()
    rk = rk.reset_index(level=0, drop=True) if isinstance(rk.index, pd.MultiIndex) else rk
    P(f"k={k:>2}min: lag-1 autocorr {rk.autocorr(1):+.3f}  lag-2 {rk.autocorr(2):+.3f}  n={len(rk)}")
am = b.between_time("09:00", "17:29").groupby("day").spot.agg(["first", "last"])
pm = b.between_time("17:30", "23:59").groupby("day").spot.agg(["first", "last"])
x = pd.DataFrame({"am": np.log(am["last"] / am["first"]), "pm": np.log(pm["last"] / pm["first"])}).dropna()
P(f"corr(09:00-17:30 return, 17:30-close return) = {x.am.corr(x.pm):+.3f}  P(same sign) {(np.sign(x.am) == np.sign(x.pm)).mean():.3f}  n={len(x)}")
# Indian-hours vs US-hours share of daily variance
ind = (b.r1 ** 2)[b.hour < 18].groupby(b.day[b.hour < 18]).sum(); us = (b.r1 ** 2)[b.hour >= 18].groupby(b.day[b.hour >= 18]).sum()
P(f"share of intraday variance 09:00-18:00 IST: {ind.sum() / (ind.sum() + us.sum()):.2f}; 18:00-close: {us.sum() / (ind.sum() + us.sum()):.2f}")

# 4) EIA / CPI
ev = pd.read_parquet(f"{D}/events.parquet")
P("\n== Around scheduled releases: mean |return| in the window after the release vs the same clock time on other weekdays")
for kind in ("eia", "uscpi"):
    e = ev[(ev.kind == kind) & (ev.ts_ist >= b.index.min()) & (ev.ts_ist <= b.index.max())]
    rows = []
    for _, r in e.iterrows():
        t0 = r.ts_ist
        if t0 not in b.index: continue
        for (a, z, lab) in ((-30, 0, "30m before"), (0, 5, "0-5m"), (0, 15, "0-15m"), (0, 60, "0-60m"), (60, 180, "60-180m")):
            ta, tz = t0 + pd.Timedelta(minutes=a), t0 + pd.Timedelta(minutes=z)
            if ta in b.index and tz in b.index:
                ev_r = abs(np.log(b.spot[tz] / b.spot[ta]))
                # controls: same clock time on the other weekdays in +-10 days
                ctrl = []
                for dlt in range(-10, 11):
                    if dlt == 0: continue
                    ca, cz = ta + pd.Timedelta(days=dlt), tz + pd.Timedelta(days=dlt)
                    if ca in b.index and cz in b.index and ca.normalize() not in set(e.ts_ist.dt.normalize()):
                        ctrl.append(abs(np.log(b.spot[cz] / b.spot[ca])))
                rows.append(dict(win=lab, ev=ev_r, ctrl=np.mean(ctrl) if ctrl else np.nan))
    t = pd.DataFrame(rows).groupby("win", sort=False).agg(n=("ev", "count"), event_abs_ret_pct=("ev", lambda s: s.mean() * 100),
                                                         control_abs_ret_pct=("ctrl", lambda s: s.mean() * 100))
    t["ratio"] = t.event_abs_ret_pct / t.control_abs_ret_pct
    P(f"-- {kind} ({len(e)} releases in range; times IST {sorted(e.ts_ist.dt.strftime('%H:%M').unique())})")
    P(t.round(3).to_string())
# minute profile around EIA: mean |1-min return| by minute offset, EIA days vs other days same clock time
prof = []
eia_t = [t for t in ev[ev.kind == "eia"].ts_ist if t in b.index]
eia_days = set(t.normalize() for t in eia_t)
for off in range(-10, 31):
    ev_v = [abs(b.r1.get(t + pd.Timedelta(minutes=off), np.nan)) for t in eia_t]
    ct = []
    for t in eia_t:
        for dlt in (-2, -1, 1, 2, 5, -5, 7, -7):
            tt = t + pd.Timedelta(days=dlt, minutes=off)
            if tt.normalize() not in eia_days and tt in b.index: ct.append(abs(b.r1[tt]))
    prof.append((off, np.nanmean(ev_v) * 100, np.nanmean(ct) * 100))
pr = pd.DataFrame(prof, columns=["min_from_release", "eia_abs_1m_pct", "ctrl_abs_1m_pct"]).set_index("min_from_release")
pr["ratio"] = pr.eia_abs_1m_pct / pr.ctrl_abs_1m_pct
P("EIA minute profile (bar labelled t covers t..t+1min):"); P(pr.round(3).T.to_string())
# EIA direction continuation: does the first 5 min after EIA predict the next 55?
rows = []
for t0 in ev[ev.kind == "eia"].ts_ist:
    t5, t60 = t0 + pd.Timedelta(minutes=5), t0 + pd.Timedelta(minutes=60)
    if all(t in b.index for t in (t0, t5, t60)):
        rows.append((np.log(b.spot[t5] / b.spot[t0]), np.log(b.spot[t60] / b.spot[t5])))
x = pd.DataFrame(rows, columns=["first5", "next55"])
P(f"EIA: corr(first 5 min, next 55 min) = {x.first5.corr(x.next55):+.3f}; P(same sign) {(np.sign(x.first5) == np.sign(x.next55)).mean():.2f}; n={len(x)}")

# 5) options: ATM premium, IV by DTE / hour
b["straddle_pct"] = (b.ce + b.pe) / b.spot * 100
s = b.groupby("day").agg(strad=("straddle_pct", "median"), iv=("iv", "median"))
s["next"] = s.strad.shift(-1)
exp_days = s.index[(s.next / s.strad > 1.6)]
P("\n== Option expiries inferred (ATM straddle jumps next day):", [d.strftime("%Y-%m-%d") for d in exp_days])
import bisect
el = list(exp_days) + [pd.Timestamp("2026-10-15"), pd.Timestamp("2026-11-17")]
b["dte"] = [ (el[bisect.bisect_left(el, d)] - d).days if bisect.bisect_left(el, d) < len(el) else np.nan for d in b.day ]
q = b.groupby(pd.cut(b.dte, [-1, 0, 2, 5, 10, 20, 40])).agg(atm_call_pct=("ce", lambda v: np.nan), straddle_pct=("straddle_pct", "median"), iv=("iv", "median"))
q["atm_one_side_rs_per_lot"] = (b.groupby(pd.cut(b.dte, [-1, 0, 2, 5, 10, 20, 40])).apply(lambda x: ((x.ce + x.pe) / 2).median()) * LOT["CRUDEOIL"]).round(0)
P("ATM option by days-to-expiry (median straddle % of spot, IV %, one ATM option premium per 100-bbl lot):")
P(q.drop(columns="atm_call_pct").round(2).to_string())
b[["dte"]].to_parquet(f"{D}/mcx_dte.parquet", compression="zstd")

# 6) chain snapshots: spreads
ch = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(f"{D}/chain_2*.parquet"))], ignore_index=True)
qt = pd.read_parquet(f"{D}/quotes.parquet") if os.path.exists(f"{D}/quotes.parquet") else None
# the chain 'und' field is the previous settlement; use parity: ATM = strike minimising |CE-PE| per snapshot/expiry
def atm_of(g):
    c = g[g.type == "CE"].set_index("strike").ltp; p = g[g.type == "PE"].set_index("strike").ltp
    k = (c - p).abs().dropna(); return k.idxmin() if len(k) else np.nan
ch = ch[(ch.bid > 0) & (ch.ask > 0)]
atmk = ch.groupby(["snap", "sym", "expiry"]).apply(atm_of).rename("atmk")
ch = ch.join(atmk, on=["snap", "sym", "expiry"]).dropna(subset=["atmk"])
step = 50
ch["mny"] = ((ch.strike - ch.atmk) / step).round().astype(int) * np.where(ch.type == "CE", 1, -1)  # + = OTM
ch["spread_pct"] = (ch.ask - ch.bid) / ((ch.ask + ch.bid) / 2) * 100
ch["spread_rs"] = ch.ask - ch.bid
ch["bucket"] = pd.cut(ch.mny, [-100, -7, -3, -1, 0, 2, 6, 100], labels=["ITM>7", "ITM4-7", "ITM2-3", "ATM/1ITM", "OTM1-2", "OTM3-6", "OTM>6"])
P(f"\n== Option bid/ask from {ch.snap.nunique()} chain snapshots ({sorted(ch.snap.unique())})")
t = ch.groupby(["sym", "expiry", "bucket"], observed=True).agg(n=("spread_pct", "count"), med_spread_pct=("spread_pct", "median"),
     med_spread_rs=("spread_rs", "median"), med_mid=("ltp", "median"), med_oi=("oi", "median"), med_vol=("vol", "median"))
P(t.round(2).to_string())
ch.to_parquet(f"{D}/chain_all.parquet", compression="zstd")
if qt is not None:
    qt["spread"] = qt.ask - qt.bid
    P("\n== Futures top-of-book (Dhan /marketfeed/quote snapshots): spread in Rs/bbl (median, max), n")
    P(qt.groupby("sid").spread.agg(["median", "max", "count"]).to_string(), "\n(569900/573422/576264 = CRUDEOIL Oct/Nov/Dec; 569901/573423 = CRUDEOILM Oct/Nov)")

# 7) costs per round trip and capital
px = float(b.spot.iloc[-1])
atm_prem = float(((b.ce + b.pe) / 2)[b.index >= "2026-09-01"].median())
fut_hs = qt.groupby("sid").spread.median().to_dict() if qt is not None else {569900: 3, 569901: 2}
atm_sp = ch[(ch.sym == "CRUDEOIL") & (ch.bucket == "ATM/1ITM")].groupby("expiry").spread_rs.median()
atm_spm = ch[(ch.sym == "CRUDEOILM") & (ch.bucket == "ATM/1ITM")].groupby("expiry").spread_rs.median()
P(f"\n== Zerodha round-trip cost, 1 lot, at spot {px:.0f}, ATM premium {atm_prem:.0f} (median since Sep 2026)")
rows = []
for name, kind, pxb, qty, spr in (("CRUDEOIL fut (100 bbl)", "fut", px, 100, fut_hs.get(569900, 3)),
                                  ("CRUDEOILM fut (10 bbl)", "fut", px, 10, fut_hs.get(569901, 2)),
                                  ("CRUDEOIL ATM option (100 bbl)", "opt", atm_prem, 100, float(atm_sp.iloc[0]) if len(atm_sp) else 1.0),
                                  ("CRUDEOILM ATM option (10 bbl)", "opt", atm_prem, 10, float(atm_spm.iloc[0]) if len(atm_spm) else 1.0)):
    chg = zerodha_costs(kind, pxb, pxb, qty)
    rows.append(dict(instrument=name, notional_or_premium_rs=round(pxb * qty), charges_rs=round(chg, 1), spread_rs=round(spr * qty, 1),
                     total_rs=round(chg + spr * qty, 1), total_pct_of_notional=round((chg + spr * qty) / (pxb * qty) * 100, 3),
                     breakeven_move_rs_per_bbl=round((chg + spr * qty) / qty, 2)))
P(pd.DataFrame(rows).to_string(index=False))
