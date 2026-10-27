"""R116 data: which expiry days have the NEXT expiry's 1-minute prints, and those contracts on the store's minute grid.

    python research/hunt/r116_expiry_next_option/r116data.py map          # results/datamap.json (counts only, no P&L)

For every expiry day of every index in the R51 clean stores (the days the arms skip today, 20 Nov 2024 - 13 Oct 2026):
  lake MONTH (NIFTY, SENSEX only; for BANKNIFTY / FINNIFTY / MIDCPNIFTY the MONTH file is byte-identical to WEEK):
      the monthly contract that day, built with R51's own cleaning (Amendment A). It is the EXACT next expiry when the
      monthly is the very next weekly (the week before a monthly expiry), a STAND-IN (2-4 weeks out) on other weekly
      expiry days, and the same expiring contract on a monthly expiry day.
  release archive (NIFTY, BANKNIFTY): "the nearest expiry after the day" at 20 strikes around 09:20, 1-minute OHLCV,
      so the next weekly (NIFTY, to 13 Apr 2026) / next monthly (BANKNIFTY, to 23 Feb 2026) on an expiry day. Stray
      opens and capped highs are fixed with lakeclean step 2 (there is no rolling series, so no foreign-row step).
World N takes the lake's exact contract where it exists, else the archive's (checked to be the next expiry). World M
(stand-in, reported only) takes the lake's later monthly. Nothing here reads a trade or a P&L.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import Counter
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

os.environ["R51_CLEAN"] = "1"
HERE = Path(__file__).resolve().parent
for p in (str(HERE), str(HERE.parent / "r51"), str(HERE.parent)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r116cfg as K  # noqa: E402
import r116lib as L  # noqa: E402
import r51cfg as R51  # noqa: E402
import r51data  # noqa: E402
import lakeclean  # noqa: E402

COLS = ("open", "high", "low", "close", "volume")


def expiry_list(days: dict) -> list:
    return sorted(d for d, D in days.items() if D.expiry)


def con_from_rows(g: pd.DataFrame) -> dict:
    """{(side, strike): (5, 375) float32} from one day's rows (columns side, strike, r, open..volume)."""
    out = {}
    for (s, k), h in g.groupby(["side", "strike"], sort=False):
        out[(s, float(k))] = L.grid(h.r.values, [h[c].values for c in COLS])
    return out


def archive_frame(und: str) -> pd.DataFrame:
    parts = []
    for f in K.ARCHIVE[und]:
        x = pd.read_parquet(K.REL / f, columns=["ts", "right", "expiry", "strike", "open", "high", "low", "close",
                                                "volume"])
        parts.append(x[x.right != "IX"])
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x["r"] = L.minute_row(ts.dt.hour.values, ts.dt.minute.values)
    x["side"] = x.right.astype(str).str[0]
    x["expiry"] = pd.to_datetime(x.expiry).dt.date
    x = x[(x.r >= 0) & (x.r < L.NROWS)].drop(columns=["ts", "right"])
    return x.drop_duplicates(["day", "side", "strike", "r"])


def archive_days(und: str, want: set) -> dict:
    """{day: (stored expiry, con, cleaning counts)} for the archive's days in `want`."""
    x = archive_frame(und)
    x = x[x.day.isin(want)]
    out = {}
    for d, g in x.groupby("day", sort=True):
        g = g.sort_values(["side", "strike", "r"])
        kept, stray, capped = lakeclean.fix_stray(g)
        out[d] = (g.expiry.iloc[0], con_from_rows(kept), dict(rows=len(g), stray_open=len(stray), capped=len(capped)))
    return out


def month_days(und: str) -> dict:
    """The lake MONTH series on R51's clean pipeline (its own expiry flags), index minutes as the store's."""
    days = r51data.index_days(und)
    r51data.add_options(und, days, series="MONTH", clean=True, stale=False)
    return days


def coverage(D, con: dict, und: str) -> float | None:
    """Share of the 09:15-15:05 minutes with a close print, over the 1-ITM call and put from the 09:19 ATM."""
    step = R51.STEP[und]
    k = D.atm[4] if D.atm is not None and np.isfinite(D.atm[4]) else np.nan
    if not np.isfinite(k):
        return None
    v = []
    for side, kk in (("C", k - step), ("P", k + step)):
        c = con.get((side, float(kk)))
        v.append(0.0 if c is None else float(np.isfinite(c[3, :R51.SQ_ROW_EXPIRY]).mean()))
    return round(float(np.mean(v)), 3)


def agreement(a: dict, b: dict) -> float | None:
    """Share of shared (contract, minute) closes within 1% between two contract dicts."""
    hit = tot = 0
    for key in set(a) & set(b):
        x, y = a[key][3], b[key][3]
        ok = np.isfinite(x) & np.isfinite(y) & (y > 0)
        tot += int(ok.sum())
        hit += int((np.abs(x[ok] - y[ok]) / y[ok] < 0.01).sum())
    return round(hit / tot, 3) if tot else None


def period(d: date) -> str:
    return "final" if d >= K.FINAL_START else "design"


def build(und: str) -> list:
    week = r51data.load(und)
    wexp_all = expiry_list(week)
    xdays = [d for d in wexp_all if K.START <= d <= K.FINAL_END]
    rows, nxt, standin = [], {}, {}
    month = month_days(und) if und in K.LAKE_MONTH else None
    mexp = expiry_list(month) if month is not None else []
    arch = archive_days(und, set(xdays)) if und in K.ARCHIVE else {}
    for d in xdays:
        D = week[d]
        row = dict(und=und, day=str(d), period=period(d), wf=bool(K.WF_START <= d <= K.DESIGN_END),
                   next_expiry_calendar=str(L.next_after(wexp_all, d)))
        lake_exact = None
        if month is not None:
            cls, m = L.classify_month(d, wexp_all, mexp)
            row["lake_month"] = cls
            row["lake_month_expiry"] = str(m) if m else None
            M = month.get(d)
            mcon = M.con if M is not None else {}
            row["lake_month_contracts"] = len(mcon)
            if cls == "exact" and mcon:
                lake_exact = dict(src="lake_month", expiry=m, con=mcon)
            elif cls == "standin" and mcon:
                standin[d] = dict(src="lake_month", expiry=m, dte=(m - d).days, con=mcon)
                row["standin_dte"] = (m - d).days
                row["standin_cov"] = coverage(D, mcon, und)
        a = arch.get(d)
        arch_ok = None
        if a is not None:
            ok = L.archive_ok(d, a[0], L.next_after(wexp_all, d), und in K.WEEKLY)
            row.update(archive_expiry=str(a[0]), archive_next=bool(ok), archive_contracts=len(a[1]), archive_clean=a[2])
            if ok and a[1]:
                arch_ok = dict(src="archive", expiry=a[0], con=a[1])
        if lake_exact is not None and arch_ok is not None:
            row["lake_vs_archive_within_1pct"] = agreement(lake_exact["con"], arch_ok["con"])
        pick = lake_exact or arch_ok
        if pick is not None:
            nxt[d] = dict(pick, dte=(pick["expiry"] - d).days)
            row.update(next_src=pick["src"], next_expiry=str(pick["expiry"]), next_dte=(pick["expiry"] - d).days,
                       next_cov=coverage(D, pick["con"], und), same_cov=coverage(D, D.con, und))
        else:
            row["next_src"] = None
        rows.append(row)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"next_{und}.pkl", "wb") as f:
        pickle.dump(nxt, f, protocol=pickle.HIGHEST_PROTOCOL)
    with open(K.OUT / f"standin_{und}.pkl", "wb") as f:
        pickle.dump(standin, f, protocol=pickle.HIGHEST_PROTOCOL)
    return rows


def summary(rows: list) -> dict:
    out = {}
    for und in K.UNDS:
        rs = [r for r in rows if r["und"] == und]
        s = {}
        for per in ("design", "wf", "final"):
            pr = [r for r in rs if (r["wf"] if per == "wf" else r["period"] == per)]
            src = Counter(r["next_src"] or "none" for r in pr)
            cov = [r["next_cov"] for r in pr if r.get("next_cov") is not None]
            s[per] = dict(expiry_days=len(pr), next_days=sum(1 for r in pr if r["next_src"]), by_source=dict(src),
                          standin_days=sum(1 for r in pr if "standin_dte" in r),
                          next_cov_median=float(np.median(cov)) if cov else None)
        dts = [r["next_dte"] for r in rs if r.get("next_dte") is not None]
        s["next_dte_range"] = [min(dts), max(dts)] if dts else None
        out[und] = s
    return out


_NEXT: dict = {}


def load_next(und: str, world: str) -> dict:
    """{day: {src, expiry, dte, con}} of world N (next) or M (stand-in)."""
    key = (und, world)
    if key not in _NEXT:
        p = K.OUT / (f"next_{und}.pkl" if world == "N" else f"standin_{und}.pkl")
        _NEXT[key] = pickle.loads(p.read_bytes()) if p.exists() else {}
    return _NEXT[key]


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "map":
        rows = []
        for u in sys.argv[2:] or K.UNDS:
            rows += build(u)
            print(u, "done", flush=True)
        K.RESULTS.mkdir(parents=True, exist_ok=True)
        out = dict(summary=summary(rows), days=rows)
        (K.RESULTS / "datamap.json").write_text(json.dumps(out, indent=1, default=str))
        print(json.dumps(out["summary"], indent=1))
    else:
        raise SystemExit(__doc__)
