"""R59 data: the lake's rolling stock-option rows turned into HELD contracts, with real expiries and lots from NSE bhavcopies.

Lake rows (stkopt_<SYM>_MONTH.parquet) are 1-minute bars of whichever contract was ATM+k (k = -1, 0, +1) at that
minute, for the nearest monthly expiry. A contract = (expiry, strike, side): every row that carried it, at any offset,
de-duplicated on minute. A trade is priced on ONE contract from entry to exit (never the rolling series: R45 / R46).

Grid: session index di (the stock's sessions in the window), minute m 0..374 (09:15 + m), global minute g = di * 375 + m.

Phases: 'design' loads no option or equity row dated after DESIGN_END. 'final' (all rows) is opened only through
`final_key()`, which refuses once results/final_read.json exists (the final test is read once).
"""
from __future__ import annotations

import io
import json
import zipfile
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

import r59cfg as K

N = K.N


# ------------------------------------------------------------------------------------------------ phase lock
class _Key:
    """Proof that the final-test lock was checked (only final_key() makes one)."""


_KEY = None


def final_key():
    global _KEY
    if K.FINAL_LOCK.exists():
        raise RuntimeError(f"refused: the R59 final test was already read ({K.FINAL_LOCK.name} exists)")
    if not K.SPREAD_JSON.exists():
        raise RuntimeError("refused: no frozen design spread estimates (results/spread.json)")
    _KEY = _Key()
    return _KEY


def phase_end(phase: str, key=None) -> date:
    if phase == "design":
        return K.DESIGN_END
    if phase == "final":
        if key is None or key is not _KEY:
            raise RuntimeError("refused: the final phase needs final_key()")
        return date(2099, 1, 1)
    raise ValueError(phase)


def assert_design_days(days) -> None:
    days = [d if isinstance(d, date) else date.fromordinal(int(d)) for d in days]
    if days and max(days) > K.DESIGN_END:
        raise ValueError(f"refused: data up to {max(days)} reaches past the design end {K.DESIGN_END}")


# ------------------------------------------------------------------------------------------------ bhavcopy
def _read_zip_csv(path: Path) -> pd.DataFrame:
    z = zipfile.ZipFile(path)
    n = [m for m in z.namelist() if m.lower().endswith(".csv")][0]
    return pd.read_csv(io.BytesIO(z.read(n)), low_memory=False)


def read_bhav(lake: Path = None, since: date = date(2024, 9, 1)) -> pd.DataFrame:
    """Stock-option expiries and lots of the 10 stocks: rows (sym, expiry, bdate, lot), one per bhavcopy date and expiry.
    Only UDiFF files (they carry NewBrdLotQty). Cached as <lake>/r59_bhav.parquet."""
    lake = Path(lake or K.LAKE)
    cache = lake / "r59_bhav.parquet"
    if cache.exists():
        return pd.read_parquet(cache)
    out = []
    for p in sorted((lake / "bhav").glob("*.csv.zip")):
        bd = date.fromisoformat(p.name[:10])
        if bd < since:
            continue
        df = _read_zip_csv(p)
        if "NewBrdLotQty" not in df.columns:
            continue
        x = df[df.FinInstrmTp.astype(str).str.strip() == "STO"]
        sym = x.TckrSymb.astype(str).str.strip().replace(K.BHAV_ALIAS)
        x = pd.DataFrame(dict(sym=sym, expiry=pd.to_datetime(x.XpryDt).dt.date, lot=x.NewBrdLotQty.astype(int)))
        x = x[x.sym.isin(K.STOCKS)].drop_duplicates()
        x["bdate"] = bd
        out.append(x)
    b = pd.concat(out, ignore_index=True)
    b = b.groupby(["sym", "expiry", "bdate"], as_index=False).lot.max()
    b.to_parquet(cache)
    return b


def expiries_of(bhav: pd.DataFrame, sym: str) -> np.ndarray:
    """Sorted expiry ordinals of the stock's options."""
    return np.array(sorted({e.toordinal() for e in bhav[bhav.sym == sym].expiry}), dtype=np.int64)


@dataclass
class Lots:
    """Lot in force for (expiry, trade date): the bhavcopy row of that expiry dated on or before the date, else the
    earliest one after it."""
    table: dict = field(default_factory=dict)       # expiry ordinal -> (bdate ordinals sorted, lots)

    @classmethod
    def of(cls, bhav: pd.DataFrame, sym: str) -> "Lots":
        t = {}
        x = bhav[bhav.sym == sym].sort_values(["expiry", "bdate"])
        for e, g in x.groupby("expiry"):
            t[e.toordinal()] = (np.array([d.toordinal() for d in g.bdate], dtype=np.int64), g.lot.to_numpy(np.int64))
        return cls(t)

    def lot(self, exp_ord: int, d_ord: int) -> int:
        if exp_ord not in self.table:
            return 0
        bd, lots = self.table[exp_ord]
        k = np.searchsorted(bd, d_ord, side="right") - 1
        return int(lots[k] if k >= 0 else lots[0])


# ------------------------------------------------------------------------------------------------ reading
def _ts(s: pd.Series) -> pd.Series:
    ts = pd.to_datetime(s)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    return ts


def _dm(ts: pd.Series):
    d = np.array([x.toordinal() for x in ts.dt.date], dtype=np.int64) if len(ts) else np.zeros(0, np.int64)
    m = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).to_numpy(np.int32)
    return d, m


def _dm_fast(ts: pd.Series):
    days = (ts.dt.normalize() - pd.Timestamp("1970-01-01")).dt.days.to_numpy(np.int64) + date(1970, 1, 1).toordinal()
    m = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).to_numpy(np.int32)
    return days, m


def read_options(sym: str, start: date, end: date, lake: Path = None) -> pd.DataFrame:
    """Raw rolling rows of one stock, start..end (inclusive), with d (ordinal) and m."""
    lake = Path(lake or K.LAKE)
    df = pd.read_parquet(lake / "stkopt" / f"stkopt_{sym}_MONTH.parquet",
                         columns=["ts", "offset", "side", "open", "high", "low", "close", "volume", "strike", "spot"])
    df["ts"] = _ts(df.ts)
    df = df[(df.ts >= pd.Timestamp(start)) & (df.ts < pd.Timestamp(end + timedelta(days=1)))].copy()
    df["side"] = df.side.astype(str).str[0]
    df["d"], df["m"] = _dm_fast(df.ts)
    df = df[(df.m >= 0) & (df.m < N)]
    return df.reset_index(drop=True)


def read_equity(syms, start: date, end: date, lake: Path = None) -> pd.DataFrame:
    lake = Path(lake or K.LAKE)
    parts = []
    for y in range(start.year, end.year + 1):
        p = lake / "equity_1m" / f"equity_1m_{y}.parquet"
        if p.exists():
            e = pd.read_parquet(p, columns=["symbol", "ts", "close"])
            parts.append(e[e.symbol.isin(syms)])
    e = pd.concat(parts, ignore_index=True)
    e["ts"] = _ts(e.ts)
    e = e[(e.ts >= pd.Timestamp(start)) & (e.ts < pd.Timestamp(end + timedelta(days=1)))].copy()
    e["d"], e["m"] = _dm_fast(e.ts)
    e = e[(e.m >= 0) & (e.m < N)]
    return e.rename(columns={"symbol": "sym"}).reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ cleaning
def expiry_of_rows(d: np.ndarray, exps: np.ndarray) -> np.ndarray:
    """The first expiry on or after each row's date (the rolling series holds the nearest monthly expiry and rolls the
    session after expiry). BIG (int64 max) when past the last known expiry."""
    k = np.searchsorted(exps, d, side="left")
    out = np.full(len(d), np.iinfo(np.int64).max, dtype=np.int64)
    ok = k < len(exps)
    out[ok] = exps[k[ok]]
    return out


def session_ratios(opt: pd.DataFrame, eq: pd.DataFrame) -> pd.DataFrame:
    """Per date: median option spot (offset-0 rows) and median ratio of option spot / equity close on shared minutes."""
    o = opt[opt.offset == 0][["d", "m", "spot"]]
    spot = o.groupby("d").spot.median()
    j = o.merge(eq[["d", "m", "close"]], on=["d", "m"], how="inner")
    ratio = (j.spot / j.close).groupby(j.d).median()
    return pd.DataFrame(dict(spot=spot, ratio=ratio))


def corporate_action_days(sr: pd.DataFrame) -> list[int]:
    """Dates (ordinals) where the median spot/equity ratio moved > 3% from the previous date's, or the median option
    spot moved > 20% (a bonus, split or demerger)."""
    sr = sr.sort_index()
    r, s = sr.ratio.to_numpy(float), sr.spot.to_numpy(float)
    out = []
    for i in range(1, len(sr)):
        jr = np.isfinite(r[i]) and np.isfinite(r[i - 1]) and abs(r[i] / r[i - 1] - 1) > K.CA_RATIO_JUMP
        js = np.isfinite(s[i]) and np.isfinite(s[i - 1]) and abs(s[i] / s[i - 1] - 1) > K.CA_SPOT_JUMP
        if jr or js:
            out.append(int(sr.index[i]))
    return out


def foreign_mask(opt: pd.DataFrame, eq: pd.DataFrame, sr: pd.DataFrame, tol: float = K.FOREIGN_TOL) -> np.ndarray:
    """True for option rows whose own spot is more than `tol` from the stock price at that minute (equity close,
    forward-filled within the session, times the session's median ratio; without equity that session, the session's
    median option spot)."""
    e = eq[["d", "m", "close"]].sort_values(["d", "m"])
    key_o = opt.d.to_numpy(np.int64) * N + opt.m.to_numpy(np.int64)
    key_e = e.d.to_numpy(np.int64) * N + e.m.to_numpy(np.int64)
    k = np.searchsorted(key_e, key_o, side="right") - 1
    ok = k >= 0
    ref = np.full(len(opt), np.nan)
    same_day = np.zeros(len(opt), bool)
    same_day[ok] = e.d.to_numpy(np.int64)[k[ok]] == opt.d.to_numpy(np.int64)[ok]
    ref[same_day] = e.close.to_numpy(float)[k[same_day]]
    ratio = sr.ratio.reindex(opt.d.to_numpy()).to_numpy(float)
    ref = ref * ratio
    fb = ~np.isfinite(ref)
    ref[fb] = sr.spot.reindex(opt.d.to_numpy()[fb]).to_numpy(float)
    with np.errstate(all="ignore"):
        r = opt.spot.to_numpy(float) / ref
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def spike_mask(opt: pd.DataFrame) -> np.ndarray:
    """True for rows on a contract-minute flagged by research/data/spikes.py (contract = strike + side + date)."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("r59_spikes", str(K.RESEARCH / "data" / "spikes.py"))
    sp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sp)
    x, s = sp.spike_flags(opt[["ts", "strike", "side", "close"]])
    bad = x[s.to_numpy()][["ts", "strike", "side"]]
    if not len(bad):
        return np.zeros(len(opt), bool)
    key = pd.MultiIndex.from_frame(opt[["ts", "strike", "side"]])
    return key.isin(pd.MultiIndex.from_frame(bad))


# ------------------------------------------------------------------------------------------------ the book
@dataclass
class Book:
    """One stock's held contracts on its sessions. Rows sorted by (contract, g)."""
    sym: str
    days: np.ndarray            # session ordinals (int64)
    skip: np.ndarray            # bool per session: expiry cut-off or corporate-action window
    g: np.ndarray               # int64 global minute
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray
    cstart: np.ndarray
    cend: np.ndarray
    cexp: np.ndarray            # int64 expiry ordinal
    cstrike: np.ndarray
    cside: np.ndarray           # 0 call, 1 put
    atm: np.ndarray             # int32 [nd, N, 2] contract on offset 0 at that minute, -1 none
    eqc: np.ndarray             # float [nd, N] stock close (NaN none)
    lots: Lots
    info: dict


def trading_calendar(opt_days, eq_days) -> np.ndarray:
    return np.array(sorted(set(int(x) for x in opt_days) | set(int(x) for x in eq_days)), dtype=np.int64)


def cutoff_sessions(days: np.ndarray, exps: np.ndarray, cal: np.ndarray, skip: int = K.EXPIRY_SKIP) -> np.ndarray:
    """True for sessions that are an expiry session E or one of the `skip` trading days before it. Trading days come
    from `cal` (dates with any rows); an expiry past the calendar's end is counted in weekdays."""
    out = np.zeros(len(days), bool)
    calset = cal
    for i, d in enumerate(days):
        k = np.searchsorted(exps, d, side="left")
        if k >= len(exps):
            continue
        e = int(exps[k])
        if e <= int(cal[-1]):
            n = int(np.searchsorted(calset, e, side="right") - np.searchsorted(calset, d, side="right"))
        else:
            n = int(np.searchsorted(calset, cal[-1], side="right") - np.searchsorted(calset, d, side="right"))
            x = date.fromordinal(int(cal[-1]))
            while x.toordinal() < e:
                x += timedelta(days=1)
                if x.weekday() < 5:
                    n += 1
        out[i] = n <= skip           # n = trading days after d up to and including E (0 on E itself)
    return out


def build_book(sym: str, opt: pd.DataFrame, eq: pd.DataFrame, bhav: pd.DataFrame, start: date, end: date,
               cal: np.ndarray = None, clean: bool = True) -> Book:
    """Held contracts of one stock from raw rows (opt) and its equity minutes (eq), sessions start..end."""
    info = {}
    exps = expiries_of(bhav, sym)
    opt = opt[(opt.d >= start.toordinal()) & (opt.d <= end.toordinal())]
    eq = eq[(eq.d >= start.toordinal()) & (eq.d <= end.toordinal())]
    nmin = eq.groupby("d").m.nunique()
    odays = np.unique(opt.d.to_numpy())
    days = np.array(sorted(set(int(x) for x in nmin[nmin >= K.MIN_SESSION_MIN].index) & set(int(x) for x in odays)),
                    dtype=np.int64)
    info["sessions"] = int(len(days))
    info["short_or_missing_sessions"] = int(len(set(int(x) for x in odays) - set(int(x) for x in days)))
    if cal is None:
        cal = trading_calendar(odays, eq.d.unique())
    sr = session_ratios(opt, eq)
    ca = corporate_action_days(sr)
    info["corporate_actions"] = [date.fromordinal(x).isoformat() for x in ca]
    opt = opt[np.isin(opt.d.to_numpy(), days)]
    eq = eq[np.isin(eq.d.to_numpy(), days)]
    n0 = len(opt)
    if clean:
        fm = foreign_mask(opt, eq, sr)
        info["foreign_rows"] = int(fm.sum())
        opt = opt[~fm]
        sm = spike_mask(opt)
        info["spike_rows"] = int(sm.sum())
        opt = opt[~sm]
    info["rows_in"], info["rows_kept"] = int(n0), int(len(opt))
    di_of = {int(d): i for i, d in enumerate(days)}
    # skip: expiry cut-off and corporate-action windows
    skip = cutoff_sessions(days, exps, cal)
    info["expiry_cutoff_sessions"] = int(skip.sum())
    ca_skip = np.zeros(len(days), bool)
    for x in ca:
        k = int(np.searchsorted(days, x))
        ca_skip[max(0, k - K.CA_PAD):min(len(days), k + K.CA_PAD + 1)] = True
    info["corporate_action_sessions"] = int((ca_skip & ~skip).sum())
    skip = skip | ca_skip
    # contracts
    di = np.array([di_of[int(x)] for x in opt.d.to_numpy()], dtype=np.int64)
    opt = opt.assign(di=di, g=di * N + opt.m.to_numpy(np.int64), exp=expiry_of_rows(opt.d.to_numpy(np.int64), exps),
                     sd=(opt.side.to_numpy() == "P").astype(np.int8))
    opt = opt.sort_values(["exp", "strike", "sd", "g", "offset"], kind="mergesort")
    rows = opt.drop_duplicates(["exp", "strike", "sd", "g"], keep="first")
    cid = rows.groupby(["exp", "strike", "sd"], sort=False).ngroup().to_numpy(np.int64)
    first = np.r_[True, cid[1:] != cid[:-1]]
    cstart = np.flatnonzero(first)
    cend = np.r_[cstart[1:], len(cid)]
    nd = len(days)
    atm = np.full((nd, N, 2), -1, dtype=np.int32)
    # offset-0 rows -> contract id (map via key)
    key = pd.MultiIndex.from_frame(rows[["exp", "strike", "sd"]].iloc[cstart])
    o0 = opt[opt.offset == 0].drop_duplicates(["g", "sd"])
    ci = key.get_indexer(pd.MultiIndex.from_frame(o0[["exp", "strike", "sd"]]))
    ok = ci >= 0
    atm[o0.di.to_numpy()[ok], o0.m.to_numpy()[ok], o0.sd.to_numpy()[ok]] = ci[ok]
    eqc = np.full((nd, N), np.nan)
    eqc[np.array([di_of[int(x)] for x in eq.d.to_numpy()], dtype=np.int64), eq.m.to_numpy()] = eq.close.to_numpy(float)
    return Book(sym=sym, days=days, skip=skip, g=rows.g.to_numpy(np.int64),
                o=rows.open.to_numpy(float), h=rows.high.to_numpy(float), l=rows.low.to_numpy(float),
                c=rows.close.to_numpy(float), v=rows.volume.to_numpy(float), cstart=cstart, cend=cend,
                cexp=rows.exp.to_numpy(np.int64)[cstart], cstrike=rows.strike.to_numpy(float)[cstart],
                cside=rows.sd.to_numpy(np.int8)[cstart], atm=atm, eqc=eqc, lots=Lots.of(bhav, sym), info=info)


def load_book(sym: str, phase: str, key=None, bhav: pd.DataFrame = None, eq_all: pd.DataFrame = None,
              lake: Path = None) -> Book:
    end = min(phase_end(phase, key), K.LIT_END)
    if bhav is None:
        bhav = read_bhav(lake)
    opt = read_options(sym, K.START, end, lake)
    eq = (eq_all[eq_all.sym == sym] if eq_all is not None else read_equity([sym], K.START, end, lake))
    eq = eq[eq.d <= end.toordinal()]
    if phase == "design":
        assert_design_days(np.unique(opt.d.to_numpy()))
        assert_design_days(np.unique(eq.d.to_numpy()))
    return build_book(sym, opt, eq, bhav, K.START, end)


def save_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")
