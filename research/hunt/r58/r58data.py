"""R58 data: one book per stock, built from the Drive lake (PREREG sections 1-4).

    python3 research/hunt/r58/r58data.py bhav                 # STO rows of the universe from the bhavcopies -> <OUT>/bhav_sto.parquet
    python3 research/hunt/r58/r58data.py build SYM [SYM ...]  # <OUT>/book_<SYM>.pkl (needs stkopt_<SYM>_MONTH.parquet)
    python3 research/hunt/r58/r58data.py refit                # re-fit the spread table of the built books
    python3 research/hunt/r58/r58data.py meta                 # results/lots.json, coverage.json, spread_fit.json, hygiene.json

A book holds, on one session grid (stock sessions with >= 300 one-minute bars, 1 Sep 2024 .. LAST_DAY):
  S [nd, 375, 5]   the stock's adjusted 1-minute O, H, L, C, V (row m = 09:15 + m; NaN when missing)
  N [nd, 375, 4]   NIFTY 1-minute O, H, L, C on the same sessions
  D [nd, 4]        the stock's daily O, H, L, C (equity_daily, adjusted)
  ratio [nd]       median of option `spot` / adjusted stock close that day (splits / bonuses; NaN without options)
  con              HELD contracts: (expiry index, strike, side) -> (first session index, float32 [n, 375, 6] O H L C V IV),
                   every cleaned lake row that carried that contract at any ATM offset
  atm [nd, 375]    the strike nearest the option rows' spot that minute, among the strikes present (NaN: no rows)
  step [nd, 375]   that minute's strike step: the gap between the ATM strike and the nearest other strike present
Cleaning (nothing is filled): stray rows (spot > 3% off the stock's unadjusted price that minute; r47data pattern),
spike minutes (research/data/spikes.py spike_flags), duplicate contract-minutes (one contract on two offsets).
"""
from __future__ import annotations

import glob
import json
import os
import pickle
import sys
import zipfile
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

import r58cfg as K

sys.path.insert(0, str(K.ROOT / "research" / "data"))
import spikes  # noqa: E402

O, H, L, C, V, IV = range(6)


@dataclass
class Book:
    sym: str
    days: list
    S: np.ndarray
    N: np.ndarray
    D: np.ndarray
    ratio: np.ndarray
    exdate: np.ndarray
    bad: np.ndarray
    expiries: list
    exp_of: np.ndarray            # [nd] index of the session's contract expiry (first expiry >= day), -1 none
    exp_di: np.ndarray            # [n_expiries] session index of each expiry day (last session <= expiry), -1 none
    atm: np.ndarray
    step: np.ndarray
    con: dict
    lots: dict                    # (expiry index, strike, side, session index) -> lot
    bhav_lots: dict               # expiry index -> [(bhav date, lot)] sorted
    spread: dict = field(default_factory=dict)    # volume bucket -> fitted half-spread fraction (design)
    cov: dict = field(default_factory=dict)
    hygiene: dict = field(default_factory=dict)

    @property
    def nd(self) -> int:
        return len(self.days)

    def di_of(self, d: date) -> int:
        return self._dix[d]

    def __post_init__(self):
        self._dix = {d: i for i, d in enumerate(self.days)}

    def __setstate__(self, st):
        self.__dict__.update(st)
        self._dix = {d: i for i, d in enumerate(self.days)}

    def lot(self, ei: int, k: float, side: str, di: int) -> int:
        x = self.lots.get((ei, float(k), side, di))
        return x if x else bhav_lot(self.bhav_lots.get(ei, []), self.days[di])


# ------------------------------------------------------------------------------------------------ small helpers
def minute_row(ts: pd.Series) -> np.ndarray:
    return (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).to_numpy()


def bhav_lot(table: list, d: date) -> int:
    """The lot in the latest bhavcopy on or before d; if none, the earliest after (0 when the contract is unknown)."""
    best = 0
    for bd, lot in table:
        if bd <= d:
            best = lot
        elif best == 0:
            return lot
        else:
            break
    return best


def lot_from_volume(vol: np.ndarray, known) -> int:
    """R49's rule: the largest known lot dividing >= 90% of the non-zero traded volumes (units); 0 below 50 values."""
    v = vol[np.isfinite(vol) & (vol > 0)]
    if len(v) < 50:
        return 0
    v = np.rint(v).astype(np.int64)
    for lot in sorted(set(known), reverse=True):
        if (v % lot == 0).mean() >= 0.9:
            return int(lot)
    return 0


def ratio_flags(ratio: np.ndarray, jump: float = K.CA_JUMP) -> tuple[np.ndarray, np.ndarray]:
    """(exdate, bad) from the daily ratio. Ex-date: the ratio moves > jump from the previous valid day and the next valid
    day stays within jump of the new level (or there is no next day). Bad: > jump away from both neighbours, which agree."""
    n = len(ratio)
    ex, bad = np.zeros(n, bool), np.zeros(n, bool)
    ok = np.flatnonzero(np.isfinite(ratio))
    for j, i in enumerate(ok):
        if j == 0:
            continue
        p = ratio[ok[j - 1]]
        r = ratio[i]
        if abs(r / p - 1) <= jump:
            continue
        nx = ratio[ok[j + 1]] if j + 1 < len(ok) else np.nan
        if np.isfinite(nx) and abs(nx / r - 1) > jump and abs(nx / p - 1) <= jump:
            bad[i] = True
        else:
            ex[i] = True
    # a bad day must not be mistaken for an ex-date of its neighbour
    for i in np.flatnonzero(bad):
        j = np.searchsorted(ok, i)
        if j + 1 < len(ok):
            ex[ok[j + 1]] = False
    return ex, bad


def stray_mask(spot: np.ndarray, px_adj: np.ndarray, day_ratio: np.ndarray, tol: float = K.STRAY) -> np.ndarray:
    """True for option rows of another underlying or a bad spot (r47data.foreign_rows pattern): `spot` more than tol
    off the stock's unadjusted price that minute (adjusted close x the day's ratio). A missing price counts as stray."""
    with np.errstate(invalid="ignore", divide="ignore"):
        return ~(np.abs(spot / (px_adj * day_ratio) - 1) <= tol)


# ------------------------------------------------------------------------------------------------ lake readers
def lake(*parts) -> str:
    return str(K.LAKE.joinpath(*parts))


def read_bhav() -> pd.DataFrame:
    p = K.OUT / "bhav_sto.parquet"
    if p.exists():
        return pd.read_parquet(p)
    rows = []
    for f in sorted(glob.glob(lake("nse_bhavcopy", "202[456]-*.csv.zip"))):
        if os.path.basename(f)[:10] < "2024-08-01":      # older files use the pre-UDiFF layout; not needed
            continue
        z = zipfile.ZipFile(f)
        x = pd.read_csv(z.open(z.namelist()[0]), usecols=["TradDt", "FinInstrmTp", "TckrSymb", "XpryDt",
                                                          "FininstrmActlXpryDt", "NewBrdLotQty"])
        rows.append(x[(x.FinInstrmTp == "STO") & x.TckrSymb.isin(K.UNIVERSE)])
    b = pd.concat(rows).drop_duplicates()
    K.OUT.mkdir(parents=True, exist_ok=True)
    b.to_parquet(p)
    return b


def read_equity(sym: str) -> pd.DataFrame:
    xs = []
    for f in sorted(glob.glob(lake("equity_1m", "equity_1m_202[456].parquet"))):
        x = pd.read_parquet(f, filters=[("symbol", "==", sym)])
        xs.append(x)
    e = pd.concat(xs)
    e = e[(e.ts.dt.date >= K.WARM_FROM) & (e.ts.dt.date <= K.LAST_DAY)]
    return e.sort_values("ts").drop_duplicates("ts")


def read_nifty() -> pd.DataFrame:
    xs = [pd.read_parquet(f) for f in sorted(glob.glob(lake("index_1m", "index_1m_NIFTY_202[456].parquet")))]
    n = pd.concat(xs)
    return n[(n.ts.dt.date >= K.WARM_FROM) & (n.ts.dt.date <= K.LAST_DAY)].sort_values("ts").drop_duplicates("ts")


def read_daily(sym: str) -> pd.DataFrame:
    d = pd.read_parquet(lake("equity_daily", "equity_daily.parquet"))
    d = d[d.symbol == sym].copy()
    d["day"] = d.date.dt.date
    return d.drop_duplicates("day", keep="last").set_index("day")


def grid(df: pd.DataFrame, days: list, cols: list) -> np.ndarray:
    dix = {d: i for i, d in enumerate(days)}
    out = np.full((len(days), K.NMIN, len(cols)), np.nan)
    di = df.ts.dt.date.map(dix)
    m = minute_row(df.ts)
    ok = di.notna().to_numpy() & (m >= 0) & (m < K.NMIN)
    out[di.to_numpy()[ok].astype(int), m[ok]] = df[cols].to_numpy(dtype=np.float64)[ok]
    return out


# ------------------------------------------------------------------------------------------------ the build
def sessions(e: pd.DataFrame) -> list:
    cnt = e.groupby(e.ts.dt.date).size()
    return sorted(d for d, n in cnt.items() if n >= K.MIN_BARS)


def expiry_tables(b: pd.DataFrame, sym: str, days: list):
    x = b[b.TckrSymb == sym].copy()
    x["exp"] = pd.to_datetime(x.FininstrmActlXpryDt.fillna(x.XpryDt)).dt.date
    x["bd"] = pd.to_datetime(x.TradDt).dt.date
    expiries = sorted(x.exp.unique())
    lots = {}
    for ei, e in enumerate(expiries):
        t = x[x.exp == e].groupby("bd").NewBrdLotQty.agg(lambda s: int(s.mode().iloc[0]))
        lots[ei] = [(d, int(v)) for d, v in t.sort_index().items()]
    exp_of = np.full(len(days), -1)
    for i, d in enumerate(days):
        j = np.searchsorted(np.array(expiries, dtype=object), d)    # first expiry >= d
        exp_of[i] = j if j < len(expiries) else -1
    darr = np.array(days, dtype=object)
    exp_di = np.array([int(np.searchsorted(darr, e, side="right")) - 1 if days[0] <= e else -1 for e in expiries])
    exp_di = np.where(np.array([e <= days[-1] for e in expiries]), exp_di, -1)
    return expiries, lots, exp_of, exp_di


def build(sym: str) -> Book:
    e = read_equity(sym)
    days = sessions(e)
    S = grid(e, days, ["open", "high", "low", "close", "volume"])
    nf = read_nifty()
    N = grid(nf, days, ["open", "high", "low", "close"])
    dd = read_daily(sym)
    D = np.array([[dd.at[d, c] if d in dd.index else np.nan for c in ("open", "high", "low", "close")] for d in days])
    b = read_bhav()
    expiries, bl, exp_of, exp_di = expiry_tables(b, sym, days)

    o = pd.read_parquet(lake("stock_options_rolling_1m", f"stkopt_{sym}_MONTH.parquet"))
    o = o[(o.ts.dt.date >= K.FIRST_DAY) & (o.ts.dt.date <= K.LAST_DAY)].copy()
    hyg = dict(rows_raw=int(len(o)))
    dix = {d: i for i, d in enumerate(days)}
    o["di"] = o.ts.dt.date.map(dix)
    hyg["rows_off_grid"] = int(o.di.isna().sum())
    o = o[o.di.notna()].copy()
    o["di"] = o.di.astype(int)
    o["m"] = minute_row(o.ts)
    o = o[(o.m >= 0) & (o.m < K.NMIN)]
    o["side"] = o.side.astype(str)

    # coverage (PREREG 2): offset-0 CE and PE each >= 300 minutes; +-1 likewise
    cov = {}
    for part in ("design", "final"):
        pd_days = [i for i, d in enumerate(days) if K.period(d) == part]
        cov[part] = {}
        for name, offs in (("atm", (0,)), ("pm1", (-1, 1))):
            ok = np.ones(len(pd_days), bool)
            for off in offs:
                for sd in ("C", "P"):
                    c = o[(o.offset == off) & (o.side == sd)].groupby("di").size().reindex(pd_days).fillna(0)
                    ok &= c.to_numpy() >= 300
            cov[part][name] = round(float(ok.mean()), 3) if len(ok) else None
        cov[part]["sessions"] = len(pd_days)

    # the day's spot / stock ratio and the stray-row filter (r47data pattern)
    sc = S[:, :, 3].copy()
    for i in range(len(days)):                       # stock close carried forward inside the session (no fill across days)
        sc[i] = pd.Series(sc[i]).ffill().to_numpy()
    px = sc[o.di.to_numpy(), o.m.to_numpy()]
    rr = o.spot.to_numpy() / px
    ratio = pd.Series(rr).groupby(o.di.to_numpy()).median().reindex(range(len(days))).to_numpy()
    exd, bad = ratio_flags(ratio)
    stray = stray_mask(o.spot.to_numpy(), px, ratio[o.di.to_numpy()])
    hyg["stray_rows"] = int(stray.sum())
    o = o[~stray]
    x, spk = spikes.spike_flags(o)
    flagged = x.loc[spk.to_numpy(), ["ts", "strike", "side"]].drop_duplicates().assign(_sp=True)
    o = o.merge(flagged, on=["ts", "strike", "side"], how="left")
    sp = o._sp.fillna(False).to_numpy(dtype=bool)
    hyg["spike_rows"] = int(sp.sum())
    o = o[~sp].drop(columns="_sp")
    n0 = len(o)
    o = o.sort_values(["ts", "strike", "side", "offset"], key=lambda s: s.abs() if s.name == "offset" else s)
    o = o.drop_duplicates(["ts", "strike", "side"])
    hyg["duplicate_contract_minutes"] = int(n0 - len(o))
    o["ei"] = exp_of[o.di.to_numpy()]
    o = o[o.ei >= 0]
    hyg["rows_used"] = int(len(o))

    # ATM per minute
    g = o.groupby(["di", "m"])
    spot_med = g.spot.transform("median")
    o["_dist"] = (o.strike - spot_med).abs()
    best = o.loc[o.groupby(["di", "m"])._dist.idxmin(), ["di", "m", "strike"]]
    atm = np.full((len(days), K.NMIN), np.nan)
    atm[best.di.to_numpy(), best.m.to_numpy()] = best.strike.to_numpy()
    # held contracts
    con, lots = {}, {}
    vals = o[["open", "high", "low", "close", "volume", "iv"]].to_numpy(dtype=np.float64)
    known = sorted({lot for t in bl.values() for _, lot in t})
    nlot = dict(volume=0, bhav=0)
    for (ei, k, sd), idx in o.groupby(["ei", "strike", "side"]).indices.items():
        dis = o.di.to_numpy()[idx]
        d0, d1 = int(dis.min()), int(dis.max())
        arr = np.full((d1 - d0 + 1, K.NMIN, 6), np.nan, dtype=np.float32)
        arr[dis - d0, o.m.to_numpy()[idx]] = vals[idx]
        con[(int(ei), float(k), sd)] = (d0, arr)
        mine = {lot for _, lot in bl.get(int(ei), [])}
        for di in np.unique(dis):
            v = arr[di - d0, :, V].astype(np.float64)
            lv = lot_from_volume(v, known)
            if lv and lv in mine:
                lots[(int(ei), float(k), sd, int(di))] = lv
                nlot["volume"] += 1
            else:
                lots[(int(ei), float(k), sd, int(di))] = bhav_lot(bl.get(int(ei), []), days[di])
                nlot["bhav"] += 1
    hyg["lot_source_contract_days"] = nlot
    hyg["exdates"] = [str(days[i]) for i in np.flatnonzero(exd)]
    hyg["bad_days"] = [str(days[i]) for i in np.flatnonzero(bad)]
    hyg["contracts"] = len(con)
    bk = Book(sym=sym, days=days, S=S, N=N, D=D, ratio=ratio, exdate=exd, bad=bad, expiries=expiries, exp_of=exp_of,
              exp_di=exp_di, atm=atm, step=np.full_like(atm, np.nan), con=con, lots=lots, bhav_lots=bl, cov=cov, hygiene=hyg)
    bk.step = minute_step(bk)
    bk.spread = fit_spread(bk)
    return bk


def minute_step(bk: Book) -> np.ndarray:
    """That minute's strike step: the gap between the ATM strike and the nearest other strike with a print."""
    st = np.full(bk.nd * K.NMIN, np.inf)
    for (ei, k, sd), (d0, arr) in bk.con.items():
        dd, mm = np.nonzero(np.isfinite(arr[:, :, C]))
        di = dd + d0
        dist = np.abs(k - bk.atm[di, mm])
        pos = dist > 1e-9                                   # NaN atm compares False
        np.minimum.at(st, (di * K.NMIN + mm)[pos], dist[pos])
    return np.where(np.isfinite(st), st, np.nan).reshape(bk.nd, K.NMIN)


# ------------------------------------------------------------------------------------------------ spread fit (design only)
BUCKETS = ("none", "thin", "liquid")          # 5-minute volume before the order: 0 lots, < 20 lots, >= 20 lots


def bucket_of(lots5: float) -> int:
    return 0 if not lots5 > 0 else (1 if lots5 < 20 else 2)


CLIP = 0.20


def fit_spread(bk: Book) -> dict:
    """Roll half-spread (fraction of price) per 5-minute volume bucket, from 1-minute close changes of contracts while
    they are ATM, on DESIGN sessions only (PREREG 4). Amendment 1 (before any P&L): one-minute changes above 20% are
    left out (R47's stray-print rule; a few such prints made the raw estimate 12-500% of the price), and the buckets
    are made monotone (`half_used`: thin >= liquid, none >= thin), so a thinner market never costs less."""
    xs = {b: [] for b in range(3)}
    for (ei, k, sd), (d0, arr) in bk.con.items():
        for j in range(arr.shape[0]):
            di = d0 + j
            if K.period(bk.days[di]) != "design":
                continue
            c = arr[j, :, C].astype(np.float64)
            v = np.nan_to_num(arr[j, :, V].astype(np.float64))
            lot = bk.lot(ei, k, sd, di) or 1
            isatm = bk.atm[di] == k
            r = np.full(K.NMIN, np.nan)
            r[1:] = c[1:] / c[:-1] - 1                   # r[t] = return into minute t
            t = np.arange(7, K.NMIN)
            ok = (np.isfinite(r[t - 1]) & np.isfinite(r[t]) & isatm[t] & isatm[t - 1]
                  & (np.abs(r[t - 1]) <= CLIP) & (np.abs(r[t]) <= CLIP))
            if not ok.any():
                continue
            cs = np.concatenate([[0.0], np.cumsum(v)])
            lots5 = (cs[t] - cs[t - 5]) / lot             # volume of minutes t-5 .. t-1
            bk_ = np.where(lots5 > 0, np.where(lots5 < 20, 1, 2), 0)
            for bi in range(3):
                m = ok & (bk_ == bi)
                if m.any():
                    xs[bi].append(np.stack([r[t - 1][m], r[t][m]], axis=1))
    out = {}
    for bi, name in enumerate(BUCKETS):
        p = np.concatenate(xs[bi]) if xs[bi] else np.zeros((0, 2))
        if len(p) < 200:
            out[name] = dict(n=len(p), half=0.0, cov=None)
            continue
        cv = float(np.mean(p[:, 0] * p[:, 1]) - p[:, 0].mean() * p[:, 1].mean())
        half = float(np.sqrt(-cv)) if cv < 0 else 0.0     # Roll: spread = 2 sqrt(-cov) -> half = sqrt(-cov)
        out[name] = dict(n=int(len(p)), half=round(half, 6), cov=cv)
    run = 0.0
    for name in reversed(BUCKETS):
        run = max(run, out[name]["half"])
        out[name]["half_used"] = round(run, 6)
    return out


# ------------------------------------------------------------------------------------------------ store
def path(sym: str):
    return K.OUT / f"book_{sym.replace('&', '_')}.pkl"


class _Unpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if name == "Book":
            return Book
        return super().find_class(module, name)


def load(sym: str) -> Book:
    with open(path(sym), "rb") as f:
        return _Unpickler(f).load()


def refit():
    """Re-derive the minute step and the spread table of every built book (no lake access needed)."""
    for s in K.UNIVERSE:
        bk = load(s)
        bk.step = minute_step(bk)
        bk.spread = fit_spread(bk)
        path(s).write_bytes(pickle.dumps(bk, protocol=pickle.HIGHEST_PROTOCOL))
        print(s, {b: (x["n"], x["half"], x["half_used"]) for b, x in bk.spread.items()}, flush=True)


def meta():
    lots, cov, spr, hyg = {}, {}, {}, {}
    for s in K.UNIVERSE:
        bk = load(s)
        lots[s] = {str(bk.expiries[ei]): [(str(d), l) for d, l in t] for ei, t in bk.bhav_lots.items()}
        cov[s] = bk.cov
        spr[s] = bk.spread
        st = bk.step[np.isfinite(bk.step)]
        vals, cnt = np.unique(np.round(st, 2), return_counts=True)
        hyg[s] = dict(bk.hygiene, sessions=bk.nd, first=str(bk.days[0]), last=str(bk.days[-1]),
                      steps={str(v): int(c) for v, c in zip(vals, cnt) if c >= 0.01 * len(st)})
    note = ("lot of each (stock, expiry) contract by bhavcopy date (NewBrdLotQty in the NSE F&O UDiFF bhavcopies on "
            "Drive, one day a week); a trade uses the contract's lot read from its traded volumes when that is one of "
            "these, else the latest bhav lot on or before the trade date")
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "lots.json").write_text(json.dumps(dict(source=note, lots=lots), indent=1))
    (K.RESULTS / "coverage.json").write_text(json.dumps(cov, indent=1))
    (K.RESULTS / "spread_fit.json").write_text(json.dumps(spr, indent=1))
    (K.RESULTS / "hygiene.json").write_text(json.dumps(hyg, indent=1))


if __name__ == "__main__":
    import r58data as _self                 # pickle the module's Book class, not __main__.Book
    build, meta, read_bhav, path, refit = _self.build, _self.meta, _self.read_bhav, _self.path, _self.refit
    K.OUT.mkdir(parents=True, exist_ok=True)
    cmd = sys.argv[1]
    if cmd == "bhav":
        print(read_bhav().shape)
    elif cmd == "build":
        for s in sys.argv[2:]:
            bk = build(s)
            path(s).write_bytes(pickle.dumps(bk, protocol=pickle.HIGHEST_PROTOCOL))
            print(s, bk.nd, bk.days[0], bk.days[-1], bk.hygiene, bk.cov, bk.spread, flush=True)
    elif cmd == "meta":
        meta()
    elif cmd == "refit":
        refit()
