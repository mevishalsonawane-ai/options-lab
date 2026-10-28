"""R131 source loaders: each returns a tidy, date-indexed table of ONE source exactly as published (no features yet).
Parsed tables are cached as parquet under R131_OUT/src (never in git).

Dates are pandas Timestamps at midnight (the trading day the row belongs to). Nothing here shifts or aligns: the
publication lags are applied in r131feat.assemble.
"""
from __future__ import annotations

import io
import re
import zipfile

import numpy as np
import pandas as pd

import r131cfg as K

NSE_NAMES = {
    "NIFTY": {"s&p cnx nifty", "cnx nifty", "nifty 50"},
    "BANKNIFTY": {"cnx bank", "nifty bank", "bank nifty"},
    "FINNIFTY": {"cnx finance", "nifty financial services"},
    "MIDCPNIFTY": {"nifty midcap select"},
    "INDIAVIX": {"india vix"},
    "N50USD": {"cnx defty", "s&p cnx defty", "nifty50 usd"},
    "GSEC10": {"nifty 10 yr benchmark g-sec"},
    "MID100": {"cnx midcap", "nifty free float midcap 100", "nifty midcap 100"},
    "SMALL100": {"cnx smallcap", "nifty free float smallcap 100", "nifty smallcap 100"},
}
GROUPS = ("Client", "DII", "FII", "Pro")
PART_COLS = ("fil", "fis", "cl", "pl", "cs", "ps")       # index futures long/short; index call/put long; call/put short


def _cache(name: str, build):
    p = K.OUT / "src" / f"{name}.parquet"
    if p.exists():
        return pd.read_parquet(p)
    df = build()
    p.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(p)
    return df


def _num(x):
    return pd.to_numeric(pd.Series(x).astype(str).str.strip().replace({"-": np.nan, "": np.nan}), errors="coerce")


# ------------------------------------------------------------------------------------------------ index daily
def index_daily() -> pd.DataFrame:
    """index_daily.parquet: symbol, date, open, high, low, close (5 indices + INDIAVIX)."""
    d = pd.read_parquet(K.LAKE / "index_daily" / "index_daily.parquet")
    d["symbol"] = d.symbol.astype(str)
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    d = d[d.symbol.isin(K.UNDS + ("INDIAVIX",))]
    return d.drop_duplicates(["symbol", "date"]).sort_values(["symbol", "date"]).reset_index(drop=True)


def parse_nse_indices_text(text: str) -> pd.DataFrame:
    rows = []
    rd = pd.read_csv(io.StringIO(text), dtype=str)
    rd.columns = [c.strip() for c in rd.columns]
    nm = rd["Index Name"].astype(str).str.strip().str.lower()
    for key, names in NSE_NAMES.items():
        m = nm.isin(names)
        if not m.any():
            continue
        r = rd[m].iloc[0]
        rows.append({"key": key, "date": pd.to_datetime(r["Index Date"], dayfirst=True),
                     "open": _num([r["Open Index Value"]])[0], "high": _num([r["High Index Value"]])[0],
                     "low": _num([r["Low Index Value"]])[0], "close": _num([r["Closing Index Value"]])[0]})
    return pd.DataFrame(rows)


def nse_indices() -> pd.DataFrame:
    """NSE's daily all-index closing files (participants/indices): key, date, open, high, low, close (published after
    the close: class 'evening')."""
    def build():
        out = []
        for p in sorted((K.LAKE / "participants" / "indices").glob("indices_*.zip")):
            z = zipfile.ZipFile(p)
            for f in z.namelist():
                try:
                    out.append(parse_nse_indices_text(z.read(f).decode("latin1")))
                except Exception:          # a malformed day file is skipped (counted in the write-up via coverage)
                    continue
        df = pd.concat(out, ignore_index=True)
        return df.drop_duplicates(["key", "date"]).sort_values(["key", "date"]).reset_index(drop=True)
    return _cache("nse_indices", build)


# ------------------------------------------------------------------------------------------------ participants
def parse_participant_text(text: str, d) -> dict | None:
    """The file's numeric rows in NSE's fixed order (Client, DII, FII, Pro, TOTAL) and fixed column order. Some old
    files have a broken header or shifted row labels, so rows are taken by position and kept only when the four groups
    add up to the TOTAL row in every column used (else the day is dropped)."""
    rows = []
    for line in text.splitlines():
        parts = [p.strip().strip('"') for p in line.split(",")]
        nums = pd.to_numeric(pd.Series(parts[1:15]), errors="coerce")
        if len(parts) >= 15 and nums.notna().all():
            rows.append(nums.values.astype(float))
    if len(rows) != 5:
        return None
    a = np.vstack(rows)
    idx = {"fil": 0, "fis": 1, "cl": 4, "pl": 5, "cs": 6, "ps": 7}
    cols = list(idx.values())
    if not np.allclose(a[:4, cols].sum(0), a[4, cols]):
        return None
    out = {"date": pd.Timestamp(d)}
    for gi, g in enumerate(GROUPS):
        for k, j in idx.items():
            out[f"{g}_{k}"] = float(a[gi, j])
    return out


def participants() -> pd.DataFrame:
    """NSE participant-wise OI (contracts), one row a day (class 'evening')."""
    def build():
        out = []
        for p in sorted((K.LAKE / "participants" / "oi").glob("oi_*.zip")):
            z = zipfile.ZipFile(p)
            for f in z.namelist():
                m = re.search(r"(\d{2})(\d{2})(\d{4})", f)
                if not m:
                    continue
                d = pd.Timestamp(int(m.group(3)), int(m.group(2)), int(m.group(1)))
                r = parse_participant_text(z.read(f).decode("latin1"), d)
                if r:
                    out.append(r)
        return pd.DataFrame(out).drop_duplicates("date").sort_values("date").reset_index(drop=True)
    return _cache("participants", build)


def fpi() -> pd.DataFrame:
    """NSDL FPI equity flows by reporting date (class 'evening+1')."""
    d = pd.read_csv(K.LAKE / "participants" / "fpi" / "fpi_daily.csv")
    d["date"] = pd.to_datetime(d.report_date)
    return d[["date", "eq_total_net_cr"]].dropna().drop_duplicates("date").sort_values("date").reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ bhavcopy chain
def chain_day(g: pd.DataFrame, spot: float) -> dict:
    """End-of-day chain state of ONE und on ONE day from its bhavcopy rows (options of the nearest expiry after the
    day; futures for rollover). spot: the index close that day."""
    out = {k: np.nan for k in ("ch_pcr", "ch_pcr_vol", "ch_maxc", "ch_maxp", "ch_strad", "ch_skew", "fut_roll",
                               "fut_px", "fut_exp")}
    d = g.date.iloc[0]
    f = g[(g.kind == "FUT") & (g.expiry > d)].sort_values("expiry")
    if len(f):
        out["fut_px"] = float(f.close.iloc[0]) if f.close.iloc[0] > 0 else float(f.settle.iloc[0])
        out["fut_exp"] = f.expiry.iloc[0]
        if len(f) >= 2:
            a, b = f.oi_qty.iloc[0], f.oi_qty.iloc[1]
            out["fut_roll"] = b / (a + b) if (a + b) > 0 else np.nan
    o = g[g.kind.isin(["CE", "PE"]) & (g.expiry > d)]
    if o.empty or not np.isfinite(spot) or spot <= 0:
        return out
    e = o.expiry.min()
    o = o[o.expiry == e]
    ce, pe = o[o.kind == "CE"], o[o.kind == "PE"]
    sc, sp = ce.oi_qty.sum(), pe.oi_qty.sum()
    out["ch_pcr"] = sp / sc if sc > 0 else np.nan
    vc, vp = ce.contracts.sum(), pe.contracts.sum()
    out["ch_pcr_vol"] = np.log((vp + 1) / (vc + 1))
    if len(ce) and ce.oi_qty.max() > 0:
        out["ch_maxc"] = (ce.strike.values[ce.oi_qty.values.argmax()] / spot - 1) * 100
    if len(pe) and pe.oi_qty.max() > 0:
        out["ch_maxp"] = (pe.strike.values[pe.oi_qty.values.argmax()] / spot - 1) * 100
    both = ce.set_index("strike").settle.to_frame("c").join(pe.set_index("strike").settle.to_frame("p"), how="inner")
    both = both[(both.c > 0) & (both.p > 0)]
    if len(both):
        k = both.index.values[np.abs(both.index.values - spot).argmin()]
        out["ch_strad"] = (both.loc[k, "c"] + both.loc[k, "p"]) / spot * 100
        kp = both.index.values[np.abs(both.index.values - spot * 0.97).argmin()]
        kc = both.index.values[np.abs(both.index.values - spot * 1.03).argmin()]
        if kp < spot < kc:
            out["ch_skew"] = float(np.log(both.loc[kp, "p"] / both.loc[kc, "c"]))
    return out


def bhav_chain(spots: dict) -> pd.DataFrame:
    """Per (und, date) chain state + futures rollover from participants/bhav_index (class 'evening').
    spots: und -> Series(date -> index close)."""
    def build():
        out = []
        for p in sorted((K.LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
            y = int(p.stem[-4:])
            if y < K.DAILY_START.year:
                continue
            b = pd.read_parquet(p, columns=["date", "und", "kind", "expiry", "strike", "close", "settle", "contracts",
                                            "oi_qty"])
            b["und"] = b.und.astype(str)
            b["kind"] = b.kind.astype(str)
            b = b[b.und.isin(K.NSE_UNDS)]
            for (u, d), g in b.groupby(["und", "date"]):
                r = chain_day(g, float(spots[u].get(pd.Timestamp(d), np.nan)))
                r.update(und=u, date=pd.Timestamp(d))
                out.append(r)
        return pd.DataFrame(out)
    return _cache("bhav_chain", build)


def bhav_fut_rows() -> pd.DataFrame:
    """Index futures rows (date, und, expiry, open, close, settle) for the money check."""
    def build():
        out = []
        for p in sorted((K.LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
            if int(p.stem[-4:]) < 2022:
                continue
            b = pd.read_parquet(p, columns=["date", "und", "kind", "expiry", "open", "close", "settle"])
            b = b[(b.kind.astype(str) == "FUT") & b.und.astype(str).isin(K.NSE_UNDS)]
            b["und"] = b.und.astype(str)
            out.append(b.drop(columns="kind"))
        return pd.concat(out, ignore_index=True)
    return _cache("bhav_fut", build)


# ------------------------------------------------------------------------------------------------ other dailies
def fut_daily() -> pd.DataFrame:
    d = pd.read_parquet(K.LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d["symbol"] = d.symbol.astype(str)
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    return d[d.expiry_code == 0].drop_duplicates(["symbol", "date"]).sort_values(["symbol", "date"])


def equity_daily() -> pd.DataFrame:
    d = pd.read_parquet(K.LAKE / "equity_daily" / "equity_daily.parquet")
    d["symbol"] = d.symbol.astype(str)
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    return d.drop_duplicates(["symbol", "date"]).sort_values(["symbol", "date"])


def stock_fut_daily() -> pd.DataFrame:
    d = pd.read_parquet(K.LAKE / "stock_fut_daily_continuous" / "stock_fut_daily_continuous.parquet")
    d["symbol"] = d.symbol.astype(str)
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    return d[d.expiry_code == 0].drop_duplicates(["symbol", "date"]).sort_values(["symbol", "date"])


def budget_days() -> set:
    e = pd.read_csv(K.EVENTS_CSV)
    return set(pd.to_datetime(e[e.kind == "budget"].date))
