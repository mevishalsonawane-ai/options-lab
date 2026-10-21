"""OF1 loaders: the raw NSE / NSDL files (fetch.py, fetch_fpi.py) -> tidy tables. No network.

    participants(kind)   one row per (date, group): the 14 contract counts of NSE's participant file
    index_closes()       NIFTY, BANKNIFTY, VIX daily OHLC from NSE's all-index file (Dhan's index_daily as a fallback)
    fpi()                NSDL daily FPI equity flows by reporting date
    bhav_days()          the sorted dates that have an index-only bhavcopy parquet
    bhav(d)              one day's index futures/options rows

All raw files are untrusted input: parsed as data only, never executed. A file that does not parse is reported and
skipped, never guessed.
"""
from __future__ import annotations

import os
import re
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd

CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
RAW = Path(os.environ.get("OF1_RAW", CACHE / "of1" / "raw")).expanduser()
DRIVE = CACHE / "of1" / "drive"          # copies of the Drive lake's index_daily.parquet (rclone), optional

GROUPS = ("Client", "DII", "FII", "Pro")
COLS = ["fut_idx_long", "fut_idx_short", "fut_stk_long", "fut_stk_short",
        "idx_call_long", "idx_put_long", "idx_call_short", "idx_put_short",
        "stk_call_long", "stk_put_long", "stk_call_short", "stk_put_short", "total_long", "total_short"]
_GROUP_KEY = {"CLIENT": "Client", "DII": "DII", "FII": "FII", "PRO": "Pro", "TOTAL": "TOTAL"}


def parse_participant(text: str) -> pd.DataFrame:
    """One participant file's text -> rows Client, DII, FII, Pro, TOTAL with the 14 counts (int).

    NSE's layout (2012-2026): a title line, a header line (first cell CLIENT_TYPE / Client Type), then one line per group.
    Cells may carry stray spaces and tabs. Raises ValueError on anything else.
    """
    lines = [ln for ln in text.replace("\r", "").split("\n") if ln.strip().strip(",")]
    hi = next((i for i, ln in enumerate(lines) if re.match(r'^\s*"?client[ _]type', ln, re.I)), None)
    if hi is None:
        raise ValueError("no header line")
    head = [c.strip().strip('"').lower() for c in lines[hi].split(",")]
    if len(head) < 15 or "future index long" not in head[1] or "total short" not in head[14]:
        raise ValueError(f"unexpected header {head[:3]}")
    rows = {}
    for ln in lines[hi + 1:]:
        cells = [c.strip().strip('"') for c in ln.split(",")]
        g = _GROUP_KEY.get(cells[0].upper())
        if g is None:
            continue
        rows[g] = [int(float(c)) for c in cells[1:15]]
    missing = set(_GROUP_KEY.values()) - set(rows)
    if missing:
        raise ValueError(f"missing rows {sorted(missing)}")
    return pd.DataFrame.from_dict(rows, orient="index", columns=COLS)


TOL = 2                                   # contracts: NSE's files are often off by one between the groups and TOTAL


def check_participant(df: pd.DataFrame, tol: int = TOL) -> list[str]:
    """Consistency of one day's file: the groups add up to TOTAL, and every long total equals its short total, within
    `tol` contracts (NSE's own rounding leaves differences of one contract on many days)."""
    errs = []
    s = df.loc[list(GROUPS)].sum()
    t = df.loc["TOTAL"]
    for c in COLS:
        if abs(s[c] - t[c]) > tol:
            errs.append(f"{c}: groups {s[c]} != total {t[c]}")
    for a, b in [("fut_idx_long", "fut_idx_short"), ("fut_stk_long", "fut_stk_short"), ("idx_call_long", "idx_call_short"),
                 ("idx_put_long", "idx_put_short"), ("total_long", "total_short")]:
        if abs(t[a] - t[b]) > tol:
            errs.append(f"TOTAL {a} {t[a]} != {b} {t[b]}")
    return errs


def participants(kind: str = "oi", raw: Path = RAW) -> tuple[pd.DataFrame, list[str]]:
    """All days' participant files of `kind` ('oi' or 'vol') -> long table (date, group, 14 counts), and problems found."""
    folder = raw / "participants" / kind
    out, problems = [], []
    for p in sorted(folder.glob(f"fao_participant_{kind}_*.csv")):
        d = datetime.strptime(p.stem.rsplit("_", 1)[1], "%d%m%Y").date()
        try:
            df = parse_participant(p.read_text(encoding="utf-8", errors="replace"))
        except (ValueError, IndexError) as e:
            problems.append(f"{p.name}: {e}")
            continue
        for e in check_participant(df):
            problems.append(f"{p.name}: {e}")
        df = df.reset_index(names="group")
        df.insert(0, "date", d)
        out.append(df)
    if not out:
        return pd.DataFrame(columns=["date", "group"] + COLS), problems
    t = pd.concat(out, ignore_index=True)
    return t.sort_values(["date", "group"]).reset_index(drop=True), problems


def wide(part: pd.DataFrame) -> pd.DataFrame:
    """Long participant table -> one row a date, columns '<group>.<count>' plus derived nets (see PREREG §2)."""
    p = part.pivot(index="date", columns="group")
    p.columns = [f"{g}.{c}" for c, g in p.columns]
    for g in GROUPS + ("TOTAL",):
        p[f"{g}.fut_net"] = p[f"{g}.fut_idx_long"] - p[f"{g}.fut_idx_short"]
        p[f"{g}.long_share"] = p[f"{g}.fut_idx_long"] / (p[f"{g}.fut_idx_long"] + p[f"{g}.fut_idx_short"]).replace(0, np.nan)
        p[f"{g}.call_net"] = p[f"{g}.idx_call_long"] - p[f"{g}.idx_call_short"]
        p[f"{g}.put_net"] = p[f"{g}.idx_put_long"] - p[f"{g}.idx_put_short"]
        p[f"{g}.opt_stance"] = p[f"{g}.call_net"] - p[f"{g}.put_net"]
        p[f"{g}.put_write"] = p[f"{g}.idx_put_short"] - p[f"{g}.idx_put_long"]
        p[f"{g}.stkfut_net"] = p[f"{g}.fut_stk_long"] - p[f"{g}.fut_stk_short"]
    p.index = pd.to_datetime(p.index)
    return p.sort_index()


def bad_days(wide: pd.DataFrame, problems: list[str]) -> dict[pd.Timestamp, str]:
    """Days to leave out, with the reason (never repaired or guessed):
    - a file that does not parse or whose groups do not add up to TOTAL (beyond NSE's rounding), from `problems`;
    - a day whose DII index-option longs exceed the FII's. In 2012-2026 that is the case on just three days, where the
      rows are plainly damaged (2 Jan 2012: FII and DII swapped; 23 Jan 2012: FII index futures long = 4; 12 Dec 2019:
      the groups do not add up), against FII > DII on every other day."""
    out: dict[pd.Timestamp, str] = {}
    for pr in problems:
        name = pr.split(":", 1)[0]
        m = re.search(r"_(\d{8})\.csv$", name)
        if m:
            out[pd.Timestamp(datetime.strptime(m.group(1), "%d%m%Y"))] = pr.split(":", 1)[1].strip()
    sus = wide[(wide["DII.idx_call_long"] + wide["DII.idx_put_long"]) > (wide["FII.idx_call_long"] + wide["FII.idx_put_long"])]
    for d in sus.index:
        out.setdefault(d, "DII index-option longs above FII's (damaged rows)")
    return out


# ---- index closes ------------------------------------------------------------------------------------------------------
_IDX_NAMES = {
    "NIFTY": {"nifty 50", "cnx nifty", "s&p cnx nifty", "nifty"},
    "BANKNIFTY": {"nifty bank", "bank nifty", "cnx bank", "cnx bank index", "nifty bank index"},
    "VIX": {"india vix", "india vix index"},
}


def _num(x) -> float:
    try:
        return float(str(x).replace(",", "").strip())
    except ValueError:
        return np.nan


def parse_index_close(text: str) -> dict[str, tuple[float, float, float, float]]:
    """NSE ind_close_all text -> {NIFTY/BANKNIFTY/VIX: (open, high, low, close)} for the names it carries."""
    out = {}
    for ln in text.replace("\r", "").split("\n")[1:]:
        cells = [c.strip().strip('"') for c in ln.split(",")]
        if len(cells) < 6:
            continue
        name = cells[0].lower()
        for k, names in _IDX_NAMES.items():
            if name in names and k not in out:
                o, h, l, c = (_num(v) for v in cells[2:6])
                out[k] = (o, h, l, c)
    return out


def index_closes(raw: Path = RAW, drive: Path = DRIVE) -> pd.DataFrame:
    """Daily OHLC for NIFTY, BANKNIFTY, VIX: columns '<IDX>_open' ... '<IDX>_close' and 'src' (nse / dhan)."""
    rows = {}
    for p in sorted((raw / "indices").glob("ind_close_all_*.csv")):
        d = datetime.strptime(p.stem.rsplit("_", 1)[1], "%d%m%Y").date()
        got = parse_index_close(p.read_text(encoding="utf-8", errors="replace"))
        if "NIFTY" in got:
            rows[d] = {f"{k}_{f}": v for k, t in got.items() for f, v in zip(("open", "high", "low", "close"), t)}
            rows[d]["src"] = "nse"
    dp = drive / "index_daily.parquet"
    if dp.exists():
        x = pd.read_parquet(dp)
        x = x[x.symbol.isin(["NIFTY", "BANKNIFTY", "INDIAVIX"])]
        for d, g in x.groupby("date"):
            d = pd.Timestamp(d).date()
            r = {}
            for _, row in g.iterrows():
                k = "VIX" if row.symbol == "INDIAVIX" else row.symbol
                for f in ("open", "high", "low", "close"):
                    r[f"{k}_{f}"] = float(row[f])
            if d in rows:
                # NSE's file wins; Dhan only fills a field it lacks (India VIX is absent from NSE's files in 2012-2014)
                for k, v in r.items():
                    if pd.isna(rows[d].get(k, np.nan)):
                        rows[d][k] = v
                continue
            if "NIFTY_close" in r:
                rows[d] = {**r, "src": "dhan"}
    df = pd.DataFrame.from_dict(rows, orient="index").sort_index()
    df.index = pd.to_datetime(df.index)
    return df


# ---- FPI -------------------------------------------------------------------------------------------------------------
def fpi(raw: Path = RAW) -> pd.DataFrame:
    p = raw / "fpi" / "fpi_daily.csv"
    if not p.exists():
        return pd.DataFrame(columns=["eq_exch_net_cr"])
    df = pd.read_csv(p, parse_dates=["report_date"]).set_index("report_date").sort_index()
    return df


# ---- bhavcopy ----------------------------------------------------------------------------------------------------------
def bhav_days(raw: Path = RAW) -> list[date]:
    return sorted(date.fromisoformat(p.stem) for p in (raw / "bhav").glob("*.parquet"))


def bhav(d: date, raw: Path = RAW) -> pd.DataFrame:
    return pd.read_parquet(raw / "bhav" / f"{d}.parquet")
