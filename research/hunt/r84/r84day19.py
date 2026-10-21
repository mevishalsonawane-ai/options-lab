"""R84: 19 Oct 2026 rebuilt (PREREG section 8). The lake ends 14 Oct and the Dhan token is expired, so the INDEX side
comes from Yahoo 1-minute bars (8-19 Oct; fetched to /dev/shm/yahoo1m.json, never committed) after the lake's index
minutes for warm-up. Each variant's cost is the sum of the app's own paper losses of the positions it would keep.

    python research/hunt/r84/r84day19.py fetch      # Yahoo's last 8 days of 1-minute index bars (works up to ~7 days on)
    python research/hunt/r84/r84study.py day19

Signal source: the indices' own 5-minute bars (R72 C1 on the Panel, break size in 5-minute ATR, NIFTY's shock ratio).
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r72"))
sys.path.insert(0, str(HERE.parent / "r71"))
import r84rules as P  # noqa: E402

DAY = date(2026, 10, 19)
YAHOO = Path("/dev/shm/yahoo1m.json")
NROWS, OPEN_MIN = 375, 9 * 60 + 15
# the app's paper record of 19 Oct (Boss's book, as reported): R72 Breadth Donchian, 1-ITM puts, entries 09:30:33-09:31:54
APP_LOSS = {"BANKNIFTY": -2440.0, "SENSEX": -1765.0, "MIDCPNIFTY": -2908.0, "FINNIFTY": -2609.0}
EXPIRY_TODAY = {"NIFTY"}


# ================================================================================================ pure
def bars5(days: dict) -> tuple:
    """fam_r44.bars5 on {day: (4, 375)} arrays: (d, b, o, h, l, c) of the 5-minute bars with a close."""
    d_, b_, o_, h_, l_, c_ = [], [], [], [], [], []
    for d in sorted(days):
        a = days[d]
        for b in range(75):
            seg = a[:, 5 * b:5 * b + 5]
            ok = np.isfinite(seg[3])
            if not ok.any():
                continue
            d_.append(d)
            b_.append(b)
            o_.append(seg[0][ok][0])
            h_.append(np.nanmax(seg[1]))
            l_.append(np.nanmin(seg[2]))
            c_.append(seg[3][ok][-1])
    return (np.array(d_, dtype=object), np.array(b_), np.array(o_, float), np.array(h_, float), np.array(l_, float),
            np.array(c_, float))


def costs(rows: dict, shock: float | None, loss: dict = APP_LOSS) -> dict:
    """{variant: 19 Oct cost} from the breakers {und: size} that traded (eligible) and the app's losses; RB's premium
    risk is approximated by each position's own loss."""
    el = [dict(und=u, size=s, risk=abs(loss[u])) for u, s in rows.items() if u in loss]
    out = {}
    for v in ("K0",) + P.ALL:
        ch = P.choose("K2-first" if v == "K2-open" else v, el, shock)   # nothing else of Breadth was open at 09:30
        out[v] = dict(keep=[c["und"] for c in ch], cost=round(sum(loss[c["und"]] for c in ch)))
    return out


# ================================================================================================ io
def _days_from_frame(x) -> dict:
    import pandas as pd
    ts = pd.to_datetime(x["ts"])
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    r = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).to_numpy()
    x = x.assign(d=ts.dt.date.to_numpy(), r=r)
    x = x[(x.r >= 0) & (x.r < NROWS) & (x.close > 0)].drop_duplicates(["d", "r"], keep="first")
    out = {}
    for d, g in x.groupby("d", sort=True):
        a = np.full((4, NROWS), np.nan)
        for i, col in enumerate(("open", "high", "low", "close")):
            a[i, g.r.to_numpy()] = g[col].to_numpy(float)
        out[d] = a
    return out


def load(und: str, lake: Path) -> tuple:
    """({day: (4, 375)}, {day: max |lake - yahoo| close gap in %} on the overlap) for one index."""
    import pandas as pd
    lk = _days_from_frame(pd.read_parquet(lake / f"index_1m_{und}_2026.parquet", columns=["ts", "open", "high", "low",
                                                                                          "close"]))
    lk = {d: a for d, a in lk.items() if d >= date(2026, 8, 1)}
    y = json.loads(YAHOO.read_text())[und]
    q = y["q"]
    yf = pd.DataFrame(dict(ts=pd.to_datetime(y["ts"], unit="s", utc=True), open=q["open"], high=q["high"],
                           low=q["low"], close=q["close"])).dropna()
    yd = _days_from_frame(yf)
    gap = {}
    for d in sorted(set(lk) & set(yd)):
        a, b = lk[d][3], yd[d][3]
        ok = np.isfinite(a) & np.isfinite(b)
        gap[str(d)] = round(float(np.max(np.abs(a[ok] / b[ok] - 1)) * 100), 3) if ok.any() else None
    days = dict(lk)
    for d, a in yd.items():
        if d not in days:
            days[d] = a
    return days, gap


YAHOO_SYM = {"NIFTY": "^NSEI", "BANKNIFTY": "^NSEBANK", "FINNIFTY": "NIFTY_FIN_SERVICE.NS",
             "MIDCPNIFTY": "NIFTY_MID_SELECT.NS", "SENSEX": "^BSESN"}


def fetch():
    """Yahoo's public chart endpoint, 1-minute bars, the last 8 days (no key); written outside git."""
    import urllib.parse
    import urllib.request
    out = {}
    for u, s in YAHOO_SYM.items():
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(s)}?interval=1m&range=8d"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=30) as r:
            res = json.load(r)["chart"]["result"][0]
        out[u] = dict(ts=res["timestamp"], q=res["indicators"]["quote"][0])
    YAHOO.write_text(json.dumps(out))


def run(lake: Path | None = None) -> dict:
    import os
    import r72rules as X
    from r71rules import day_starts
    lake = lake or Path(os.environ.get("IRAALGO_LAKE", "/dev/shm/lake"))
    data, gaps = {}, {}
    for u in P.ORDER:
        days, gaps[u] = load(u, lake)
        data[u] = bars5(days)
    Pn = X.Panel(data)
    sig = X.breadth_breakout(Pn, 4)
    d, b, o, h, l, c = data["NIFTY"]
    mv = P.ten_min_moves(o, c, day_starts(d))
    usual = P.usual_by_day(d, mv)
    out = dict(source="Yahoo 1-minute index bars for days after the lake (15, 16, 19 Oct) + lake index minutes",
               yahoo_vs_lake_max_close_gap_pct=gaps, bars={}, signals=[])
    for bar in range(0, 8):                                    # 09:15 .. 09:55
        row = {}
        for u in P.ORDER:
            f = Pn.f[u]
            ix = np.flatnonzero((f["d"] == DAY) & (f["b"] == bar))
            if not len(ix):
                continue
            j = int(ix[0])
            row[u] = dict(close=round(float(f["c"][j]), 2), down=bool(f["dn"][j]), up=bool(f["up"][j]),
                          size_put=round(P.break_size("P", f["c"][j], f["chi"][j], f["clo"][j], f["atr"][j]), 3))
        jn = int(np.flatnonzero((d == DAY) & (b == bar))[0])
        out["bars"][f"{9 + (15 + 5 * bar) // 60:02d}:{(15 + 5 * bar) % 60:02d}"] = dict(
            indices=row, n_down=sum(r["down"] for r in row.values()), n_up=sum(r["up"] for r in row.values()),
            nifty_10min_move_pct=round(float(mv[jn]) * 100, 3),
            nifty_shock_ratio=P.shock_ratio(float(mv[jn]), usual.get(DAY)))
    out["nifty_usual_10min_move_pct"] = round(usual.get(DAY, float("nan")) * 100, 4)
    for u, lst in sig.items():
        f = Pn.f[u]
        for j, s, _inv in lst:
            if f["d"][j] == DAY:
                out["signals"].append(dict(und=u, bar=int(f["b"][j]), side="C" if s > 0 else "P"))
    first = min((x["bar"] for x in out["signals"] if x["side"] == "P"), default=None)
    if first is not None:
        key = f"{9 + (15 + 5 * first) // 60:02d}:{(15 + 5 * first) % 60:02d}"
        br = out["bars"][key]
        sizes = {u: r["size_put"] for u, r in br["indices"].items() if r["down"] and u not in EXPIRY_TODAY}
        out["first_put_signal_bar"] = key
        out["costs"] = costs(sizes, br["nifty_shock_ratio"])
    out["app_total"] = round(sum(APP_LOSS.values()))
    (HERE / "results").mkdir(exist_ok=True)
    (HERE / "results" / "day19.json").write_text(json.dumps(out, indent=1, default=str) + "\n")
    print(json.dumps({k: out[k] for k in ("first_put_signal_bar", "costs", "nifty_usual_10min_move_pct") if k in out},
                     indent=1))
    return out


if __name__ == "__main__":
    if sys.argv[1:] == ["fetch"]:
        fetch()
    else:
        run()
