"""R47 data: index minute panels and FIXED-CONTRACT option stores rebuilt from the lake's rolling rows.

    python3 research/hunt/r47/r47data.py build [UND ...]      # design stores (rows up to DESIGN_END only)

Lake rows (opt_<UND>_<SERIES>_<YEAR>.parquet) are 1-minute bars of the contract that was ATM+k at that minute, for the
series' nearest expiry. A contract = (expiry, strike, side): every row that carried it, whatever its offset, in time
order. So a trade is priced on ONE contract from entry to exit (never the rolling ATM series: R45's artefact).

Phases. 'design' loads no row dated after DESIGN_END (and infers expiries from design rows only). 'full' (design +
embargo + holdout) is only handed to research/hunt/r47/r47holdout.py, through `holdout_key()`, which refuses unless the
frozen candidates file is committed unchanged and the holdout has never been read.

Grid: day index di (the underlying's index sessions from 2021-01-01, warm-up included), minute m 0..374 (09:15 + m),
global minute g = di * 375 + m.
"""
from __future__ import annotations

import json
import pickle
import subprocess
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

import r47cfg as K

WARMUP_FROM = date(2021, 1, 1)
NOFF = 21                                       # offsets -10 .. +10
BIG = np.int64(2 ** 40)


# ------------------------------------------------------------------------------------------------ phase lock
class _Key:
    """Proof that the holdout lock was checked (only holdout_key() makes one)."""


_KEY = None


def holdout_key(check_git: bool = True):
    """The one way to open the 'full' phase: candidates.json must exist and be committed unchanged, holdout.json must
    not exist (the holdout is read once)."""
    global _KEY
    if K.HOLDOUT_JSON.exists():
        raise RuntimeError(f"refused: the R47 holdout was already read ({K.HOLDOUT_JSON.name} exists)")
    if not K.CANDIDATES.exists():
        raise RuntimeError("refused: no frozen candidates (results/candidates.json)")
    if check_git:
        rel = K.CANDIDATES.relative_to(K.ROOT)
        st = subprocess.run(["git", "status", "--porcelain", str(rel)], cwd=K.ROOT, capture_output=True, text=True)
        tracked = subprocess.run(["git", "ls-files", "--error-unmatch", str(rel)], cwd=K.ROOT, capture_output=True)
        if st.stdout.strip() or tracked.returncode != 0:
            raise RuntimeError("refused: candidates.json must be committed unchanged before the holdout read")
    _KEY = _Key()
    return _KEY


def recheck_key():
    """Amendment 2: re-price the SAME frozen candidates on cleaned holdout rows. Allowed only after the one read
    (holdout.json exists) and only when the clean-data selection rerun froze the identical candidates."""
    global _KEY
    if not K.HOLDOUT_JSON.exists():
        raise RuntimeError("refused: a recheck is only for candidates already read once")
    a = json.loads(K.CANDIDATES.read_text())["candidates"]
    b = json.loads((K.RESULTS / "candidates_v2.json").read_text())["candidates"]
    strip = lambda c: {k: v for k, v in c.items() if k not in ("wf", "fb", "t")}  # noqa: E731
    if [strip(x) for x in a] != [strip(x) for x in b]:
        raise RuntimeError("refused: the clean-data rerun chose different candidates (no second holdout read)")
    _KEY = _Key()
    return _KEY


def phase_end(phase: str, key=None) -> date:
    if phase == "design":
        return K.DESIGN_END
    if phase == "full":
        if key is None or key is not _KEY:
            raise RuntimeError("refused: the 'full' phase (holdout rows) needs holdout_key() from r47holdout.py")
        return date(2099, 1, 1)
    raise ValueError(phase)


def assert_design_days(days) -> None:
    """Raise if any date is after DESIGN_END (the look-ahead / holdout guard used by every design computation)."""
    days = list(days)
    if days and max(days) > K.DESIGN_END:
        raise ValueError(f"refused: data up to {max(days)} reaches past the design end {K.DESIGN_END}")


# ------------------------------------------------------------------------------------------------ reading
def _ts(df: pd.DataFrame) -> pd.Series:
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    return ts


def _minutes(ts: pd.Series):
    m = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).to_numpy(np.int32)
    d = ts.dt.date.map(date.toordinal).to_numpy(np.int64)
    return d, m


def index_panel(und: str, end: date):
    """(days[ordinals], O H L C arrays [nd, 375]) of the index from WARMUP_FROM to `end` (NaN where no minute)."""
    parts = []
    for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet")):
        if int(p.stem[-4:]) < WARMUP_FROM.year or int(p.stem[-4:]) > end.year:
            continue
        parts.append(pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]))
    df = pd.concat(parts, ignore_index=True)
    d, m = _minutes(_ts(df))
    keep = (m >= 0) & (m < K.N) & (d >= WARMUP_FROM.toordinal()) & (d <= end.toordinal())
    df, d, m = df[keep], d[keep], m[keep]
    days = np.unique(d)
    di = np.searchsorted(days, d)
    out = [np.full((len(days), K.N), np.nan) for _ in range(4)]
    for arr, col in zip(out, ("open", "high", "low", "close")):
        arr[di, m] = df[col].to_numpy(np.float64)
    return days, out


def daily_close(days, C) -> np.ndarray:
    """Last minute close of each session (NaN if none)."""
    out = np.full(len(days), np.nan)
    for i in range(len(days)):
        ok = np.flatnonzero(np.isfinite(C[i]))
        if len(ok):
            out[i] = C[i, ok[-1]]
    return out


def _time_value(df: pd.DataFrame, days: np.ndarray) -> np.ndarray:
    """Per session: ATM straddle time value near the close (15:00-15:20, last rows, % of spot) - brain expiry_infer."""
    x = df[(df.offset == 0) & (df.m >= K.mi(15, 0)) & (df.m <= K.mi(15, 20))].sort_values(["d", "m"])
    tv = np.full(len(days), np.nan)
    if x.empty:
        return tv
    last = x.groupby(["d", "side"], observed=True)[["close", "strike", "spot"]].last()
    c = last.xs("C", level="side")
    p = last.xs("P", level="side")
    j = c.join(p, rsuffix="_p", how="inner")
    j = j[j.strike == j.strike_p]
    v = (j.close + j.close_p - (j.spot - j.strike).abs()) / j.spot * 100
    pos = np.searchsorted(days, v.index.to_numpy())
    ok = (pos < len(days)) & (days[np.minimum(pos, len(days) - 1)] == v.index.to_numpy())
    tv[pos[ok]] = v.to_numpy()[ok]
    return tv


def expiries(tv: np.ndarray, weekly_gap: int = 10, monthly_gap: int = 38, days=None) -> list[int]:
    """Expiry session indices from the per-session close time value (R25 method: tv < 0.6% and the next session's
    > 2x). A gap longer than a missed weekly (or monthly) expiry is searched again with a lenient version of the same
    test (tv < 1.2%, next > 1.5x), taking the strongest jump. The last session is never an expiry."""
    n = len(tv)
    nxt = np.r_[tv[1:], np.nan]
    with np.errstate(invalid="ignore", divide="ignore"):
        ratio = nxt / np.clip(tv, 1e-6, None)
        base = np.flatnonzero((tv < 0.6) & (ratio > 2.0) & np.isfinite(nxt))
    out = sorted(set(int(i) for i in base))
    if days is None:
        days = np.arange(n)
    changed = True
    while changed:
        changed = False
        for a, b in zip(out[:-1], out[1:]):
            gap = days[b] - days[a]
            if (weekly_gap < gap < 20) or gap > monthly_gap:
                cand = [i for i in range(a + 2, b - 1) if np.isfinite(tv[i]) and np.isfinite(nxt[i])
                        and tv[i] < 1.2 and ratio[i] > 1.5]
                if cand:
                    out.append(max(cand, key=lambda i: ratio[i]))
                    out = sorted(set(out))
                    changed = True
                    break
    return out


# Exchange expiry rules (weekday 0 = Monday; 'weekly' = every week, 'last' = the month's last such weekday), moved to the
# previous session on a holiday. Used together with the data: a rule date is added unless the data shows an expiry within
# 3 days of it. A wrong extra date only splits one contract in two (a held position is then valued conservatively); a
# missed date would merge two contracts, which is the error to avoid.
RULES = {
    ("NIFTY", "WEEK"): [(date(2016, 1, 1), date(2025, 8, 31), 3, "weekly"), (date(2025, 9, 1), date(2099, 1, 1), 1, "weekly")],
    ("NIFTY", "MONTH"): [(date(2016, 1, 1), date(2025, 8, 31), 3, "last"), (date(2025, 9, 1), date(2099, 1, 1), 1, "last")],
    ("BANKNIFTY", "WEEK"): [(date(2016, 1, 1), date(2023, 9, 3), 3, "weekly"), (date(2023, 9, 4), date(2024, 11, 13), 2, "weekly"),
                            (date(2024, 11, 14), date(2024, 12, 31), 2, "last"), (date(2025, 1, 1), date(2025, 8, 31), 3, "last"),
                            (date(2025, 9, 1), date(2099, 1, 1), 1, "last")],
    ("FINNIFTY", "WEEK"): [(date(2021, 1, 1), date(2024, 11, 19), 1, "weekly"), (date(2024, 11, 20), date(2024, 12, 31), 1, "last"),
                           (date(2025, 1, 1), date(2025, 8, 31), 3, "last"), (date(2025, 9, 1), date(2099, 1, 1), 1, "last")],
    ("SENSEX", "WEEK"): [(date(2023, 5, 15), date(2024, 12, 31), 4, "weekly"), (date(2025, 1, 1), date(2025, 8, 31), 1, "weekly"),
                         (date(2025, 9, 1), date(2099, 1, 1), 3, "weekly")],
    ("SENSEX", "MONTH"): [(date(2023, 5, 15), date(2024, 12, 31), 4, "last"), (date(2025, 1, 1), date(2025, 8, 31), 1, "last"),
                          (date(2025, 9, 1), date(2099, 1, 1), 3, "last")],
}


def rule_expiries(und: str, series: str, days: np.ndarray) -> list[int]:
    """Session indices of the rule expiries within the sessions `days` (ordinals)."""
    from datetime import timedelta
    out = set()
    if len(days) == 0:
        return []
    d0, d1 = date.fromordinal(int(days[0])), date.fromordinal(int(days[-1]))
    dayset = set(int(x) for x in days)
    for a, b, wd, kind in RULES.get((und, series), []):
        x = max(a, d0)
        while x <= min(b, d1):
            hit = x.weekday() == wd and (kind == "weekly" or (x + timedelta(days=7)).month != x.month)
            if hit:
                y = x
                while y.toordinal() not in dayset and (x - y).days < 6:
                    y -= timedelta(days=1)
                if y.toordinal() in dayset:
                    out.add(int(np.searchsorted(days, y.toordinal())))
            x += timedelta(days=1)
    return sorted(out)


def merge_expiries(data_di: list[int], rule_di: list[int], days: np.ndarray, near: int = 3) -> list[int]:
    """The data's expiries plus every rule expiry with no data expiry within `near` calendar days."""
    data_ord = np.array([days[i] for i in data_di], dtype=np.int64)
    out = set(data_di)
    for r in rule_di:
        if len(data_ord) == 0 or np.min(np.abs(data_ord - days[r])) > near:
            out.add(r)
    return sorted(out)


@dataclass
class Store:
    """One series' fixed contracts. Rows sorted by (contract, g)."""
    und: str
    series: str
    g: np.ndarray            # int64 global minute
    o: np.ndarray            # float32
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray            # float32 traded units in the minute
    off: np.ndarray          # int8 offset at which the row was carried
    cstart: np.ndarray       # int64 per contract
    cend: np.ndarray
    cstrike: np.ndarray      # float64
    cside: np.ndarray        # int8: +1 call, -1 put
    cexp: np.ndarray         # int64 expiry session index (BIG if unknown: after the last recovered expiry)
    offcid: np.ndarray       # int32 [nd, 375, 2, 21]: contract at (session, minute, side 0=C 1=P, offset+10), -1 none
    exp_di: np.ndarray       # int64 recovered expiry session indices
    oi_c: np.ndarray         # float64 [nd, 375] call OI summed over the rows of that minute (NaN none)
    oi_p: np.ndarray
    iv_atm: np.ndarray       # float32 [nd, 375] mean of the ATM call and put IV (NaN none)


def foreign_rows(spot, di, m, C, tol: float = K.FOREIGN_TOL) -> np.ndarray:
    """Amendment 2: True for option rows whose own `spot` is more than `tol` away from the underlying's index close at
    that minute (or, if the index has no close then, from its last close that session). The lake's rolling files carry
    rows of ANOTHER underlying (FINNIFTY files hold rows at MIDCPNIFTY's level, BANKNIFTY MONTH rows at NIFTY's ...)."""
    ref = C[di, m]
    miss = ~np.isfinite(ref)
    if miss.any():
        filled = pd.DataFrame(C).ffill(axis=1).to_numpy()
        ref = np.where(miss, filled[di, m], ref)
    with np.errstate(all="ignore"):
        r = np.asarray(spot, dtype=np.float64) / ref
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def build_store(und: str, series: str, days: np.ndarray, end: date, C=None) -> Store:
    parts = []
    for p in sorted(K.LAKE.glob(f"opt_{und}_{series}_*.parquet")):
        if int(p.stem[-4:]) > end.year:
            continue
        parts.append(pd.read_parquet(p, columns=["ts", "offset", "side", "open", "high", "low", "close", "volume",
                                                 "strike", "oi", "iv", "spot"]))
    df = pd.concat(parts, ignore_index=True)
    d, m = _minutes(_ts(df))
    df["d"], df["m"] = d, m
    df = df[(df.m >= 0) & (df.m < K.N) & (df.d <= end.toordinal()) & (df.d >= days[0])]
    df = df[np.isin(df.d.to_numpy(), days)]
    df["side"] = df.side.astype(str).str[0]
    df = df.drop_duplicates(["d", "m", "side", "offset"])
    nd = len(days)
    if C is not None:
        bad = foreign_rows(df.spot.to_numpy(), np.searchsorted(days, df.d.to_numpy()), df.m.to_numpy(), C)
        df = df[~bad]
    di = np.searchsorted(days, df.d.to_numpy())
    # expiries
    tv = _time_value(df, days)
    exp_di = np.array(merge_expiries(expiries(tv, days=days), rule_expiries(und, series, days), days), dtype=np.int64)
    exp_di = exp_di[exp_di < nd - 1] if len(exp_di) else exp_di      # the last session is never called an expiry
    # expiry session of each session: the first recovered expiry on/after it (BIG after the last one)
    if len(exp_di):
        k = np.searchsorted(exp_di, np.arange(nd))
        exp_of_day = np.where(k < len(exp_di), exp_di[np.minimum(k, len(exp_di) - 1)], BIG)
    else:
        exp_of_day = np.full(nd, BIG, dtype=np.int64)
    row_exp = exp_of_day[di]
    side = np.where(df.side.to_numpy() == "C", 1, -1).astype(np.int8)
    strike = df.strike.to_numpy(np.float64)
    key = pd.MultiIndex.from_arrays([row_exp, strike, side])
    codes, uniq = pd.factorize(key)
    g = di.astype(np.int64) * K.N + df.m.to_numpy(np.int64)
    order = np.lexsort((g, codes))
    codes_s = codes[order]
    bounds = np.r_[0, np.flatnonzero(np.diff(codes_s)) + 1, len(codes_s)]
    nc = len(uniq)
    cid_of_code = np.empty(nc, dtype=np.int64)
    cid_of_code[codes_s[bounds[:-1]]] = np.arange(nc)
    cexp = np.array([u[0] for u in uniq], dtype=np.int64)[codes_s[bounds[:-1]]]
    cstrike = np.array([u[1] for u in uniq], dtype=np.float64)[codes_s[bounds[:-1]]]
    cside = np.array([u[2] for u in uniq], dtype=np.int8)[codes_s[bounds[:-1]]]
    offcid = np.full((nd, K.N, 2, NOFF), -1, dtype=np.int32)
    offs = df.offset.to_numpy(np.int64)
    okoff = (offs >= -10) & (offs <= 10)
    offcid[di[okoff], df.m.to_numpy()[okoff], (side[okoff] == -1).astype(np.int64), offs[okoff] + 10] = cid_of_code[codes[okoff]]
    # chain aggregates
    oi_c = np.full((nd, K.N), np.nan)
    oi_p = np.full((nd, K.N), np.nan)
    agg = pd.DataFrame(dict(di=di, m=df.m.to_numpy(), s=side, oi=df.oi.to_numpy(np.float64)))
    a = agg.groupby(["di", "m", "s"]).oi.sum()
    for s_, arr in ((1, oi_c), (-1, oi_p)):
        x = a.xs(s_, level="s")
        arr[x.index.get_level_values(0), x.index.get_level_values(1)] = x.to_numpy()
    iv = np.full((nd, K.N, 2), np.nan, dtype=np.float32)
    atm = df.offset.to_numpy() == 0
    ivv = df.iv.to_numpy(np.float32)
    ivv = np.where(ivv > 0, ivv, np.nan)
    iv[di[atm], df.m.to_numpy()[atm], (side[atm] == -1).astype(np.int64)] = ivv[atm]
    with np.errstate(all="ignore"):
        iv_atm = np.nanmean(iv, axis=2)
    return Store(und, series, g[order], *(df[c].to_numpy(np.float32)[order] for c in ("open", "high", "low", "close")),
                 df.volume.to_numpy(np.float32)[order], df.offset.to_numpy(np.int8)[order],
                 bounds[:-1].astype(np.int64), bounds[1:].astype(np.int64), cstrike, cside, cexp, offcid, exp_di,
                 oi_c, oi_p, iv_atm.astype(np.float32))


@dataclass
class Und:
    und: str
    phase: str
    days: np.ndarray         # ordinals
    O: np.ndarray
    H: np.ndarray
    L: np.ndarray
    C: np.ndarray
    dclose: np.ndarray
    vixC: np.ndarray         # INDIAVIX minute closes on the same sessions [nd, 375]
    vix_dclose: np.ndarray
    stores: dict             # series -> Store
    last_di: int             # last session index of the phase

    @property
    def dates(self):
        return [date.fromordinal(int(x)) for x in self.days]


def _vix_on(days, end):
    vd, (vo, vh, vl, vc) = index_panel("INDIAVIX", end)
    out = np.full((len(days), K.N), np.nan)
    pos = np.searchsorted(vd, days)
    ok = (pos < len(vd)) & (vd[np.minimum(pos, len(vd) - 1)] == days)
    out[ok] = vc[pos[ok]]
    return out


def load(und: str, phase: str = "design", key=None, cache: bool = True) -> Und:
    end = phase_end(phase, key)
    path = K.OUT / f"r47_{und}_{phase}_v2.pkl"          # v2: foreign rows dropped (amendment 2)
    if cache and path.exists():
        u = pickle.loads(path.read_bytes())
    else:
        days, (O, H, L, C) = index_panel(und, end)
        stores = {s: build_store(und, s, days, end, C) for s in K.SERIES[und]}
        vixC = _vix_on(days, end)
        u = Und(und, phase, days, O, H, L, C, daily_close(days, C), vixC, daily_close(days, vixC), stores, len(days) - 1)
        if cache:
            K.OUT.mkdir(parents=True, exist_ok=True)
            path.write_bytes(pickle.dumps(u, protocol=pickle.HIGHEST_PROTOCOL))
    if phase == "design":
        assert_design_days([date.fromordinal(int(u.days[-1]))])
    return u


def main(argv):
    if not argv or argv[0] != "build":
        print(__doc__)
        return 2
    for und in (argv[1:] or K.UNDS):
        u = load(und, "design")
        info = {s: dict(rows=int(len(st.g)), contracts=int(len(st.cstart)), expiries=int(len(st.exp_di)),
                        first=str(date.fromordinal(int(u.days[0]))), last=str(date.fromordinal(int(u.days[-1]))))
                for s, st in u.stores.items()}
        print(und, json.dumps(info))
    return 0


if __name__ == "__main__":
    import r47data  # noqa: E402  (pickles name the module, never __main__)
    sys.exit(r47data.main(sys.argv[1:]))
