"""Build minute table, decision-grid entries, features, realised labels and leg price paths for MCX CRUDEOIL.
Inputs : scratchpad/hunt/strad_crude/cache/c1_*.parquet, c2_ATM_*.parquet (Dhan rollingoption, fetch_crude.py)
Outputs: scratchpad/hunt/strad_crude/work/{minutes.parquet, entries.parquet, paths.npy, expiries.csv}
No P&L here."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import datetime as dt
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.stats import norm

S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/strad_crude")
C, W = S / "cache", S / "work"
W.mkdir(exist_ok=True)
STEP, HMAX, CUTOFF = 50.0, 240, dt.time(23, 25)
GRID = 15
HS = (15, 30, 60, 240)

# ------------------------------------------------------------------ events (approximate calendar, see PREREG)
US_DST = [(dt.date(2025, 3, 9), dt.date(2025, 11, 2)), (dt.date(2026, 3, 8), dt.date(2026, 11, 1))]


def us_dst(d):
    return any(a <= d < b for a, b in US_DST)


_H28 = pd.read_csv("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h28/events.csv")
CPI = [dt.date.fromisoformat(x) for x in _H28[_H28.kind == "uscpi"].date]   # h28 source (BLS/alfred)
FOMC = [dt.date.fromisoformat(x) for x in _H28[_H28.kind == "fomc"].date]   # h28 source (federalreserve.gov)
# EIA WPSR official exceptions 2025-26 (eia.gov schedule, via research/hunt/nn_crude/calendar_events.py)
EIA_EXC = {
    "2025-09-03": ("2025-09-04", "12:00"), "2025-10-15": ("2025-10-16", "12:00"), "2025-11-12": ("2025-11-13", "12:00"),
    "2025-12-24": ("2025-12-29", "17:00"), "2026-01-21": ("2026-01-22", "12:00"), "2026-02-18": ("2026-02-19", "12:00"),
    "2026-05-27": ("2026-05-28", "12:00"), "2026-09-09": ("2026-09-10", "12:00"), "2026-10-14": ("2026-10-15", "12:00")}


def first_sunday(y, m):
    d = dt.date(y, m, 1)
    return d + dt.timedelta(days=(6 - d.weekday()) % 7)


OPEC_SUN = [first_sunday(y, m) for y, m in [(2025, 8), (2025, 9), (2025, 10), (2025, 11), (2026, 1), (2026, 2), (2026, 3),
                                             (2026, 4), (2026, 5), (2026, 6), (2026, 7), (2026, 8), (2026, 9), (2026, 10)]]
OPEC_SUN.append(dt.date(2025, 11, 30))
OPEC_MON = {d + dt.timedelta(days=1) for d in OPEC_SUN}


def eia_ts(days):
    """EIA release timestamps (IST) on the trading days given (Wed 10:30 ET unless an official exception)."""
    from zoneinfo import ZoneInfo
    ET = ZoneInfo("America/New_York")
    dset, out = set(days), []
    for d in days:
        if d.weekday() != 2:
            continue
        k = d.isoformat()
        dd, hm = (dt.date.fromisoformat(EIA_EXC[k][0]), EIA_EXC[k][1]) if k in EIA_EXC else (d, "10:30")
        h, m = map(int, hm.split(":"))
        t = pd.Timestamp(dt.datetime(dd.year, dd.month, dd.day, h, m, tzinfo=ET)).tz_convert("Asia/Kolkata")
        if dd in dset:
            out.append(t)
    return out


def cpi_ts():
    return [pd.Timestamp(dt.datetime.combine(d, dt.time(18 if us_dst(d) else 19, 0)), tz="Asia/Kolkata") for d in CPI]


# ------------------------------------------------------------------ black-76
def b76(F, K, T, sig, call):
    T = np.maximum(T, 1e-8)
    s = sig * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * s * s) / s
    d2 = d1 - s
    c = F * norm.cdf(d1) - K * norm.cdf(d2)
    return np.where(call, c, c - F + K)


def straddle_iv(price, F, K, T):
    lo, hi = np.full_like(price, 0.01), np.full_like(price, 3.0)
    for _ in range(50):
        mid = (lo + hi) / 2
        p = b76(F, K, T, mid, True) + b76(F, K, T, mid, False)
        hi = np.where(p > price, mid, hi)
        lo = np.where(p > price, lo, mid)
    return (lo + hi) / 2


# ------------------------------------------------------------------ load
def load():
    parts = []
    for f in sorted(C.glob("c1_*.parquet")):
        _, off, side = f.stem.split("_")
        d = pd.read_parquet(f, columns=["ts", "strike", "close", "iv", "spot", "volume", "oi"])
        d["side"] = 1 if side == "CALL" else 0
        d["off"] = int(off.replace("ATM", "") or 0)
        parts.append(d)
    L = pd.concat(parts, ignore_index=True)
    L = L[L.close > 0]
    return L


def main():
    L = load()
    L["day"] = L.ts.dt.tz_localize(None).dt.normalize()
    # underlying: spot of ATM series (median across series per minute for robustness)
    Fm = L.groupby("ts").spot.median().rename("F")
    M = Fm.to_frame()
    M["day"] = M.index.tz_localize(None).normalize()
    M["tod"] = M.index.hour * 60 + M.index.minute
    # ATM straddle (rolling ATM series) for expiry detection and descriptive
    atm = L[L.off == 0].pivot_table(index="ts", columns="side", values="close")
    M["strad_atm"] = atm[1] + atm[0]
    M["K_atm_roll"] = L[(L.off == 0) & (L.side == 1)].set_index("ts").strike
    # ---- expiries: day where straddle at first bar jumps vs previous day's last bar
    g = M.groupby("day")
    first, last = g.strad_atm.first(), g.strad_atm.last()
    jump = first / last.shift(1)
    days = list(first.index)
    switch_days = [d for d, j in jump.items() if j > 1.6]
    cand = [days[days.index(d) - 1] for d in switch_days]
    exp_days = []
    for d in cand:  # a vol shock can fake a jump: keep only switches >= 20 days after the previous one
        if exp_days and (d - exp_days[-1]).days < 20:
            continue
        exp_days.append(d)
    exp_days.append(pd.Timestamp("2026-10-15"))  # current contract (instrument master)
    E = pd.DataFrame({"expiry": exp_days})
    E.to_csv(W / "expiries.csv", index=False)
    print("expiries:", [str(x.date()) for x in exp_days])
    dser = pd.Series(days)
    nxt = np.searchsorted(np.array(exp_days, dtype="datetime64[ns]"), dser.values.astype("datetime64[ns]"), side="left")
    day_exp = pd.Series(np.array(exp_days, dtype="datetime64[ns]")[np.minimum(nxt, len(exp_days) - 1)], index=days)
    M["expiry"] = M.day.map(day_exp)
    M["is_expday"] = M.day == M.expiry
    sess_len = g.size()
    M["sess_len"] = M.day.map(sess_len)
    # trading days left (business days, ignoring exchange holidays) after today until expiry inclusive
    bd = {d: np.busday_count(d.date() + dt.timedelta(days=1), (day_exp[d] + pd.Timedelta(days=1)).date()) for d in days}
    M["bd_left"] = M.day.map(bd)
    close_hm = M.groupby("day").tod.max() + 1
    M["min_left_today"] = M.day.map(close_hm) - M.tod
    M["N_rem"] = M.min_left_today + M.bd_left * M.sess_len.clip(upper=895)
    exp_close = M.expiry + pd.Timedelta(hours=23, minutes=30)
    tnow = M.index.tz_localize(None)
    M["tau"] = ((exp_close.values - tnow.values) / np.timedelta64(1, "s")) / (365.0 * 86400)
    M["iv_straddle"] = straddle_iv(M.strad_atm.values, M.F.values, M.K_atm_roll.values, M.tau.clip(lower=1e-6).values)
    M["r"] = np.log(M.F).groupby(M.day).diff()
    M.to_parquet(W / "minutes.parquet")
    print("minutes", len(M), "days", len(days))

    # ------------------------------------------------------------ per-day leg matrices and entries
    ev_eia = eia_ts([d.date() for d in days])
    ev_cpi = cpi_ts()
    pd.Series(ev_eia).to_csv(W / "eia_ts.csv", index=False)
    rows, paths = [], []
    daily_rms = M.groupby("day").r.apply(lambda x: np.sqrt(np.nanmean(x.values ** 2)))
    for di, d in enumerate(days):
        md = M[M.day == d]
        if len(md) < 300:
            continue
        Ld = L[L.day == d]
        ce = Ld[Ld.side == 1].pivot_table(index="ts", columns="strike", values="close").reindex(md.index).ffill(limit=5)
        pe = Ld[Ld.side == 0].pivot_table(index="ts", columns="strike", values="close").reindex(md.index).ffill(limit=5)
        cei = Ld[Ld.side == 1].pivot_table(index="ts", columns="strike", values="iv").reindex(md.index).ffill()
        pei = Ld[Ld.side == 0].pivot_table(index="ts", columns="strike", values="iv").reindex(md.index).ffill()
        F = md.F.values
        tau = md.tau.values
        tod = md.tod.values
        r = md.r.values
        prev = daily_rms.iloc[max(0, di - 5):di]
        cut = CUTOFF.hour * 60 + CUTOFF.minute
        for i in range(len(md)):
            t = tod[i]
            if t < 9 * 60 + 15 or t % GRID or t > cut - 15:
                continue
            K = round(F[i] / STEP) * STEP
            legs = [(K, 1), (K, 0), (K + STEP, 1), (K - STEP, 0)]
            n_ok = min(HMAX, int(np.searchsorted(tod, cut, side="right")) - 1 - i)
            P = np.full((4, HMAX + 1), np.nan, dtype=np.float32)
            fb = 0
            for j, (k, call) in enumerate(legs):
                mat, ivm = (ce, cei) if call else (pe, pei)
                if k in mat.columns:
                    v = mat[k].values[i:i + n_ok + 1].astype(float)
                    ivv = ivm[k].values[i:i + n_ok + 1] / 100.0
                else:
                    v = np.full(n_ok + 1, np.nan)
                    ivv = np.full(n_ok + 1, np.nan)
                if np.isnan(v[0]):
                    break
                miss = np.isnan(v)
                if miss.any():
                    last_iv = pd.Series(ivv).ffill().values
                    last_iv = np.where(np.isnan(last_iv), md.iv_straddle.values[i], last_iv)
                    v[miss] = b76(F[i:i + n_ok + 1][miss], k, tau[i:i + n_ok + 1][miss], last_iv[miss], bool(call))
                    fb += int(miss.sum())
                P[j, :n_ok + 1] = v
            else:
                rp = r[max(1, i - 59):i + 1]
                row = dict(ts=md.index[i], day=d, tod=t, F=F[i], K=K, n_ok=n_ok, fallback_min=fb,
                           tau=tau[i], N_rem=md.N_rem.values[i], dte=md.bd_left.values[i],
                           is_expday=bool(md.is_expday.values[i]), iv=md.iv_straddle.values[i],
                           strad=P[0, 0] + P[1, 0], strang=P[2, 0] + P[3, 0],
                           rv15=np.sqrt(np.nanmean(r[max(1, i - 14):i + 1] ** 2)),
                           rv60=np.sqrt(np.nanmean(rp ** 2)),
                           rvday=np.sqrt(np.nanmean(r[1:i + 1] ** 2)) if i > 1 else np.nan,
                           rvyd=prev.iloc[-1] if len(prev) else np.nan,
                           rv5d=np.sqrt(np.mean(prev.values ** 2)) if len(prev) else np.nan,
                           opec_mon=d.date() in OPEC_MON,
                           fomc_next=any(d.date() == (f + dt.timedelta(days=1 if f.weekday() < 4 else 3)) for f in FOMC),
                           fomc_day=d.date() in FOMC, cpi_day=d.date() in CPI,
                           eia_day=any(e.date() == d.date() for e in ev_eia))
                for h in HS:
                    end = i + h
                    t_end = md.index[i] + pd.Timedelta(minutes=h)
                    row[f"eia_in{h}"] = any(md.index[i] < e <= t_end for e in ev_eia if e.date() == d.date())
                    row[f"cpi_in{h}"] = any(md.index[i] < e <= t_end for e in ev_cpi if e.date() == d.date())
                    if end < len(md) and h <= n_ok:
                        rr = r[i + 1:end + 1]
                        row[f"rvf{h}"] = np.sqrt(np.nanmean(rr ** 2))  # per-minute rms over (t, t+h]
                        row[f"mv{h}"] = abs(np.log(F[end] / F[i]))
                    else:
                        row[f"rvf{h}"] = np.nan
                        row[f"mv{h}"] = np.nan
                rows.append(row)
                paths.append(P)
        if di % 20 == 0:
            print(d.date(), len(rows), flush=True)
    Ent = pd.DataFrame(rows)
    Ent.to_parquet(W / "entries.parquet")
    np.save(W / "paths.npy", np.stack(paths))
    print("entries", len(Ent), "fallback minutes share",
          Ent.fallback_min.sum() / Ent.n_ok.clip(lower=1).mul(4).sum())


if __name__ == "__main__":
    main()
