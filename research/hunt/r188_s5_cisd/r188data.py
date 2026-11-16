"""R188 step 1: the option bars both arms need, read from the lake exactly as R123 / R131 read it (lakeclean steps 1-3),
plus the bought option's raw OI (for FT3PO). Plain-English notes, 17 Nov 2026.

    python research/hunt/r188_s5_cisd/r188data.py cisd       # CISD candidates on every index -> trades/cisd_cands.parquet
    python research/hunt/r188_s5_cisd/r188data.py extract U  # bars of index U -> bars/U.pkl

The weekly files of 2023 and the monthly files are not in the local lake: `extract` copies one file at a time from the
Drive lake (read only) into a scratch folder, reads it and deletes it, so the disk never holds more than one.

Signal source: the index (R131's CISD detector; R123's network rows). The option bars are only what is bought.
"""
from __future__ import annotations

import pickle
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r131 as R  # noqa: E402

K, D3 = R.K, R.D3
lakeclean = D3.lakeclean
SCRATCH = C.OUT / "scratch"
DRIVE_OPT = "gdrive:IraAlgo-data-lake/options_rolling_1m"
COLS = ["ts", "offset", "side", "strike", "open", "high", "low", "close", "volume", "oi", "iv", "spot"]


# ------------------------------------------------------------------------------------------------ one session's bars
def year_frame(files: list, year: int, last_day) -> dict:
    """{day: raw frame} of every file of that year (the gap file too; the earlier file wins a duplicate minute)."""
    ps = [p for p in files if p.stem.endswith(str(year))]
    if not ps:
        return {}
    o = pd.concat([D3._ts_frame(p, COLS) for p in ps], ignore_index=True)
    o = o[o.d <= last_day]
    o["side"] = o.side.astype(str).str[0].str.upper()
    o = o.drop_duplicates(["d", "m", "strike", "side"], keep="first")
    return dict(tuple(o.groupby("d")))


def clean_day(g: pd.DataFrame, d, closes: np.ndarray, step: float) -> dict:
    """R123's LakeOptions.day + DayOptions on one session: {(side, strike): (375, 6) open, high, low, close, volume,
    oi}. Price columns are NaN in a minute with no trade (Amendment 2, as R123); OI is the raw lake value."""
    w = g.rename(columns={"d": "day", "m": "r"})
    kept, _, _ = lakeclean.clean(w, {d: closes}, stale=True)
    g = kept.rename(columns={"day": "d", "r": "m"})
    if g.empty:
        return {}
    g = g[(g.strike - g.spot).abs() <= 12 * step]
    g = g.drop_duplicates(["m", "strike", "side"], keep="first")
    out = {}
    for (s, k), h in g.groupby(["side", "strike"], observed=True):
        a = np.full((C.N, 6), np.nan)
        mm = h.m.values
        for j, col in enumerate(("open", "high", "low", "close", "volume", "oi")):
            a[mm, j] = h[col].values.astype(float)
        a[~(a[:, 4] > 0), :5] = np.nan
        out[(str(s), float(k))] = a
    return out


def tv_rows(files: list, last_day) -> pd.Series:
    """R123's LakeOptions.tv_1515 over the given files (ATM time value at 15:15, % of spot, by session)."""
    tv = []
    for p in files:
        o = D3._ts_frame(p, ["ts", "offset", "side", "strike", "close", "spot"])
        o = o[o.d <= last_day]
        o["side"] = o.side.astype(str).str[0].str.upper()
        x = o[(o.offset == 0) & (o.m == K.row(15, 15))]
        if x.empty:
            continue
        pv = x.pivot_table(index="d", columns="side", values="close", aggfunc="first")
        sp = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
        j = pv.join(sp)
        if "C" in j and "P" in j:
            tv.append((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100)
    t = pd.concat(tv).sort_index() if tv else pd.Series(dtype=float)
    return t[~t.index.duplicated()]


def expiry_from_tv(t: pd.Series) -> set:
    """R123's expiry rule on a time-value series (LakeOptions.expiry_days)."""
    t = t.sort_index()
    t = t[~t.index.duplicated()]
    nxt = t.shift(-1)
    ok = (t < 0.6) & (nxt > 2.0 * t.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


# ------------------------------------------------------------------------------------------------ candidates
def cisd_candidates(und: str) -> pd.DataFrame:
    """R131 S3-V2 setups on one index after R131 / R132's exit-free filters (not both sides at one minute, entry at or
    before 15:00, an index price at entry beyond the stop, a structural target). One-at-a-time is NOT applied here:
    it depends on each variant's exits (r188sim)."""
    g = D3.load_grid(und, C.END)
    sides = R.sides_of(g)
    cands = [(sd, c) for sd, c in R.candidates(sides, R.ALL_VARIANTS[C.CISD]) if C.period_of(g.days[c.d])]
    by = {}
    for sd, c in cands:
        by.setdefault((c.d, c.r0), []).append((sd, c))
    rows = []
    for (d, r0), same in sorted(by.items()):
        day = g.days[d]
        base = dict(und=und, d=d, day=day, entry=r0)
        if len({s for s, _ in same}) == 2:
            rows.append(dict(base, side=0, why="both"))
            continue
        sd, c = same[0]
        if r0 > C.CISD_LAST_ENTRY:
            rows.append(dict(base, side=sd, why="late"))
            continue
        S = sides[sd]
        ref = S.mo[d][r0]
        if not np.isfinite(ref):
            rows.append(dict(base, side=sd, why="no_price"))
            continue
        if ref <= c.stop:
            rows.append(dict(base, side=sd, why="no_room"))
            continue
        tgt = R.struct_target(S, S.chart(c.tf), d, r0, ref)
        if tgt is None or tgt <= ref:
            rows.append(dict(base, side=sd, why="no_target"))
            continue
        lastc = R.last_before(g.c[d])
        k = float(D3.atm(lastc[r0], C.STEP[und])) if np.isfinite(lastc[r0]) else np.nan
        rows.append(dict(base, side=sd, why="ok", ref=float(ref), stop=float(c.stop), strike=k))
    return pd.DataFrame(rows)


def s5_rows(P: pd.DataFrame, use_cls: bool = True) -> pd.DataFrame:
    """The morning rows whose best fillable cell-side has EV >= Rs 300 (S5's candidates), with that pick. use_cls=False
    ignores R123's fillable flags (a superset, for choosing which bars to keep; the new month has no flags)."""
    M = P[P.row.isin(C.MORNING_ROWS)].copy()
    ev = M[[f"ev_{j}" for j in range(16)]].values.copy()
    if use_cls:
        ev[~(M[[f"cls_{j}" for j in range(16)]].values >= 0)] = -np.inf
    j = ev.argmax(1)
    M["j"] = j
    M["score"] = ev[np.arange(len(M)), j]
    return M[M.score >= C.EV_MIN]


# ------------------------------------------------------------------------------------------------ extraction
def drive_copy(name: str) -> Path | None:
    SCRATCH.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["rclone", "copy", f"{DRIVE_OPT}/{name}", str(SCRATCH)], capture_output=True, text=True)
    p = SCRATCH / name
    return p if r.returncode == 0 and p.exists() else None


def extract(und: str, need: dict) -> dict:
    """need: {(series, day): {(side, strike)}}. Returns {"bars": {(series, day, side, strike): array},
    "wexp": set, "mexp": set}. The local weekly files are read in place; 2023's weekly and every monthly file are
    copied from Drive one at a time and deleted after use."""
    g = D3.load_grid(und, C.END)
    pos = g.pos()
    step = C.STEP[und]
    lake_opt = Path(K.K3.LAKE) / "options_rolling_1m"
    out, tvs = {}, {"WEEK": [], "MONTH": []}
    wexp = None
    for series in ("WEEK", "MONTH"):
        for year in (2023, 2024, 2025, 2026):
            local = sorted(lake_opt.glob(f"opt_{und}_{series}_*{year}.parquet"))
            tmp = []
            if not local:
                p = drive_copy(f"opt_{und}_{series}_{year}.parquet")
                if p is not None:
                    tmp = [p]
            files = local or tmp
            if not files:
                continue
            tvs[series].append(tv_rows(files, C.END))
            if series == "MONTH" and wexp is None:
                wexp = expiry_from_tv(pd.concat(tvs["WEEK"]) if tvs["WEEK"] else pd.Series(dtype=float))
            frames = year_frame(files, year, C.END)
            for (s_, d), keys in need.items():
                if s_ != series or d.year != year or d not in frames or d not in pos:
                    continue
                if series == "MONTH" and d not in wexp:          # the monthly contract is used only then
                    continue
                bars = clean_day(frames[d], d, g.c[pos[d]], step)
                for key in keys:
                    if key in bars:
                        out[(series, d, *key)] = bars[key]
            for p in tmp:
                p.unlink()
            print(f"R188 extract {und} {series} {year}: {len(out)} contracts so far", flush=True)
    t_w = pd.concat(tvs["WEEK"]) if tvs["WEEK"] else pd.Series(dtype=float)
    t_m = pd.concat(tvs["MONTH"]) if tvs["MONTH"] else pd.Series(dtype=float)
    return dict(bars=out, wexp=expiry_from_tv(t_w), mexp=expiry_from_tv(t_m), tv_week=t_w, tv_month=t_m)


def load_preds() -> pd.DataFrame:
    P = pd.concat([pd.read_parquet(C.R123 / "wf_slim.parquet"), pd.read_parquet(C.R123 / "holdout_slim.parquet")],
                  ignore_index=True)
    nw = C.R123 / "new_slim.parquet"
    if nw.exists():
        P = pd.concat([P, pd.read_parquet(nw)], ignore_index=True)
    P["day"] = pd.to_datetime(P.day).dt.date
    return P


def need_for(und: str, P: pd.DataFrame, cands: pd.DataFrame, wexp_guess: set) -> dict:
    """Which contracts to keep: S5 picks (both sides' ATM at the decision, so a variant can re-pick) and every CISD
    setup's ATM contract on its side (weekly; on a weekly expiry day the monthly as well)."""
    g = D3.load_grid(und, C.END)
    pos = g.pos()
    need: dict = {}
    for r in s5_rows(P[P.und == und], use_cls=False).itertuples(index=False):
        i = pos.get(r.day)
        if i is None:
            continue
        c = D3._last(g.c[i][:r.row + 1])
        if np.isfinite(c):
            k = float(D3.atm(c, C.STEP[und]))
            need.setdefault(("WEEK", r.day), set()).update({("C", k), ("P", k)})
    for r in cands[(cands.und == und) & (cands.why == "ok")].itertuples(index=False):
        if not np.isfinite(r.strike):
            continue
        key = ("C" if r.side > 0 else "P", float(r.strike))
        need.setdefault(("WEEK", r.day), set()).add(key)
        need.setdefault(("MONTH", r.day), set()).add(key)       # used only if the day is a weekly expiry
    return need


def main():
    cmd = sys.argv[1]
    C.TRADES.mkdir(parents=True, exist_ok=True)
    C.BARS.mkdir(parents=True, exist_ok=True)
    if cmd == "cisd":
        from multiprocessing import Pool
        with Pool(4) as pool:
            res = pool.map(cisd_candidates, C.UNDS)
        pd.concat(res, ignore_index=True).to_parquet(C.TRADES / "cisd_cands.parquet")
        print("cisd candidates", sum(len(x) for x in res))
    elif cmd == "extract":
        und = sys.argv[2]
        global SCRATCH
        SCRATCH = C.OUT / "scratch" / und                     # one scratch folder per index (runs in parallel)
        P = load_preds()
        cands = pd.read_parquet(C.TRADES / "cisd_cands.parquet")
        cands["day"] = pd.to_datetime(cands.day).dt.date
        res = extract(und, need_for(und, P, cands, set()))
        with open(C.BARS / f"{und}.pkl", "wb") as f:
            pickle.dump(res, f, protocol=pickle.HIGHEST_PROTOCOL)
        shutil.rmtree(SCRATCH, ignore_errors=True)
        print(und, "contracts", len(res["bars"]), "week expiries", len(res["wexp"]), "month", len(res["mexp"]))


if __name__ == "__main__":
    main()
