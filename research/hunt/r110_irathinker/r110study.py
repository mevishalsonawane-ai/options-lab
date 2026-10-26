"""R110 study (26 Oct 2026): price IraThinker's own signals on real options (PREREG.md).

    python -I research/hunt/r110_irathinker/r110study.py <release_dir> <bars_dir> <signals_dir> <out_dir>

Inputs: the research-data release files (options + index), r110prep.py's bar CSVs, and R110Runner.java's signal CSVs.
Writes <out_dir>/trades_*.csv, results.json and results.md. Signal source of every rule: the underlying index (CLAUDE.md);
the option is only what is bought (or, S4, sold). Release files are untrusted data: read only, run with python -I.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.abspath(os.path.join(HERE, "..", "..")))   # research/ (obuy)
from obuy.config import LOT_SCHEDULE  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402

IST = "Asia/Kolkata"
OPEN_M = 9 * 60 + 15
W = 375                                   # minutes 09:15 .. 15:29
SQ = 15 * 60 + 15 - OPEN_M                # 15:15 square-off (IraAlgo's ntfy arm)
SQ_EXP = 15 * 60 + 5 - OPEN_M             # 15:05 on the contract's expiry day
DESIGN_END = date(2025, 9, 30)
SEED = 107
BOOT = 10_000
COSTS = Costs("dated")
OPT_FILES = {"NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"],
             "BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"]}
STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50, "SENSEX": 100}
BSE = {"SENSEX"}


# ------------------------------------------------------------------------------------------------ small helpers
def lot(und: str, d: date) -> int:
    out = LOT_SCHEDULE[und][0][1]
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            out = n
    return out


def dn(d: date) -> int:
    return (d - date(1970, 1, 1)).days


def charge(buy: bool, price: float, qty: float, d: date, bse: bool = False) -> float:
    return float(COSTS.charge(buy, np.array([price]), np.array([qty]), np.array([dn(d)]), bse)[0])


def minute_of(ts_epoch: int) -> tuple[date, int]:
    t = datetime.fromtimestamp(int(ts_epoch), tz=pd.Timestamp(0, tz=IST).tz)
    return t.date(), t.hour * 60 + t.minute - OPEN_M


class Fill:
    """'liq' fills; slip multiplies the slippage (gate 4: 2x)."""

    def __init__(self, slip: float = 1.0):
        self.f = Fills(mode="liq")
        self.slip = slip

    def buy(self, p: float, vol5_lots: float, stop: bool = False) -> float:
        x = float(self.f.buy(np.array([p]), np.array([vol5_lots]))[0])
        return round(p + (x - p) * self.slip, 2)

    def sell(self, p: float, vol5_lots: float, stop: bool = False) -> float:
        x = float(self.f.sell(np.array([p]), np.array([vol5_lots]), stop=stop)[0])
        return max(round(p - (p - x) * self.slip, 2), 0.0)


# ------------------------------------------------------------------------------------------------ market data
class Market:
    """One index's minutes (index 375-grid per day) and its option book: (day, expiry, strike, right) -> 5x375."""

    def __init__(self, und: str, bars_dir: str, rel_dir: str | None):
        self.und = und
        b = pd.read_csv(os.path.join(bars_dir, f"{und}_1m.csv"))
        t = pd.to_datetime(b["ts"], unit="s", utc=True).dt.tz_convert(IST)
        b["day"] = t.dt.date
        b["m"] = t.dt.hour * 60 + t.dt.minute - OPEN_M
        self.ix: dict[date, np.ndarray] = {}
        for d, g in b.groupby("day"):
            a = np.full((4, W), np.nan)
            m = g["m"].to_numpy()
            ok = (m >= 0) & (m < W)
            for k, c in enumerate(["open", "high", "low", "close"]):
                a[k, m[ok]] = g[c].to_numpy()[ok]
            self.ix[d] = a
        self.days = sorted(self.ix)
        self.opt: dict[date, dict] = {}
        self.expiry: dict[date, date] = {}
        if rel_dir and und in OPT_FILES:
            for f in OPT_FILES[und]:
                df = pd.read_parquet(os.path.join(rel_dir, f),
                                     columns=["ts", "open", "high", "low", "close", "right", "expiry", "strike", "volume",
                                              "day"])
                df = df[df["right"].isin(["CE", "PE"])]
                df["m"] = df["ts"].dt.hour * 60 + df["ts"].dt.minute - OPEN_M
                df = df[(df["m"] >= 0) & (df["m"] < W)]
                for (d, e, k, r), g in df.groupby([df["day"].dt.date, df["expiry"].dt.date, "strike", "right"]):
                    a = np.full((5, W), np.nan)
                    m = g["m"].to_numpy()
                    for j, c in enumerate(["open", "high", "low", "close", "volume"]):
                        a[j, m] = g[c].to_numpy()
                    a[4] = np.nan_to_num(a[4])
                    self.opt.setdefault(d, {})[(e, float(k), r)] = a
                    self.expiry[d] = e

    def contract(self, d: date, strike: float, right: str):
        """The day's nearest-expiry contract at the listed strike closest to `strike` (ties: the lower strike)."""
        book = self.opt.get(d)
        if not book:
            return None
        e = self.expiry[d]
        ks = sorted(k for (ee, k, r) in book if ee == e and r == right)
        if not ks:
            return None
        k = min(ks, key=lambda x: (abs(x - strike), x))
        if abs(k - strike) > STEP[self.und]:
            return None                                       # not within one strike of what IraThinker named
        return e, k, book[(e, k, right)]


def vol5_lots(a: np.ndarray, j: int, lots: int) -> float:
    return float(np.nansum(a[4, max(j - 5, 0):j])) / lots if j > 0 else 0.0


def first_print(a: np.ndarray, j: int, end: int, row: int = 0) -> int:
    """First minute in [j, min(j+3, end)) with a positive price in `row` (0 open, 3 close), else -1."""
    for x in range(j, min(j + 3, end)):
        if np.isfinite(a[row, x]) and a[row, x] > 0:
            return x
    return -1


# ------------------------------------------------------------------------------------------------ one bought option
def long_option(mk: Market, d: date, right: str, strike: float, e_min: int, *, idx_stop=None, idx_target=None,
                prem_stop_mult=None, exit_min=None, fill: Fill, need_expiry=None):
    """Buy one lot at minute e_min's open; exit on (a) the index crossing idx_stop / idx_target (fill at the next
    minute's open), (b) the option's minute close at or under prem_stop_mult x the fill (fill at that close), (c) the
    close of minute exit_min (time exit), (d) the square-off minute's open. Returns a dict, or None (no real price).
    need_expiry: a function(day, expiry) -> bool the contract must satisfy (mom60 / strangle: the monthly)."""
    c = mk.contract(d, strike, right)
    if c is None:
        return None
    exp, k, a = c
    if need_expiry is not None and not need_expiry(d, exp):
        return None
    sq = SQ_EXP if exp == d else SQ
    if e_min >= sq:
        return None
    ef = first_print(a, e_min, sq)
    if ef < 0:
        return None
    lots = lot(mk.und, d)
    qty = lots
    fill_in = fill.buy(a[0, ef], vol5_lots(a, ef, lots))
    ix = mk.ix[d]
    call = right == "CE"
    why, x_min, x_px, stopx = "squareoff", sq, None, False
    for j in range(ef, sq):
        if idx_stop is not None and np.isfinite(ix[1, j]):
            hit_stop = ix[2, j] <= idx_stop if call else ix[1, j] >= idx_stop
            hit_tgt = idx_target is not None and (ix[1, j] >= idx_target if call else ix[2, j] <= idx_target)
            if hit_stop or hit_tgt:
                why, x_min, stopx = ("index_stop", j + 1, True) if hit_stop else ("index_target", j + 1, False)
                break
        if prem_stop_mult is not None and j > ef and np.isfinite(a[3, j]) and a[3, j] <= prem_stop_mult * fill_in:
            why, x_min, x_px, stopx = "prem_stop", j, a[3, j], True
            break
        if exit_min is not None and j >= exit_min:
            why, x_min = "time", j
            x_px = a[3, j] if np.isfinite(a[3, j]) else None
            break
    if x_px is None:
        # a market exit at the open of x_min (or the first print after it; else the last close before it)
        x_min = min(x_min, sq)
        xf = first_print(a, x_min, W) if why != "time" else -1
        if xf >= 0:
            x_px = a[0, xf]
        else:
            last = a[3, :x_min + 1][np.isfinite(a[3, :x_min + 1])]
            if not len(last):
                return None
            x_px = float(last[-1])
    fill_out = fill.sell(x_px, vol5_lots(a, min(x_min, W - 1), lots), stop=stopx)
    gross = (fill_out - fill_in) * qty
    ch = charge(True, fill_in, qty, d) + charge(False, fill_out, qty, d)
    return {"day": d, "expiry": exp, "strike": k, "right": right, "entry_min": ef, "exit_min": x_min, "why": why,
            "fill_in": fill_in, "fill_out": fill_out, "qty": qty, "gross": round(gross, 2), "charges": round(ch, 2),
            "net": round(gross - ch, 2)}


# ------------------------------------------------------------------------------------------------ index points only
def index_points(mk: Market, d: date, up: bool, e_min: int, *, idx_stop=None, idx_target=None, exit_min=None):
    """FINNIFTY / SENSEX (no option data): the index move in the signal's direction from minute e_min's open to the exit,
    x the lot. Descriptive only, before any option cost."""
    ix = mk.ix[d]
    if e_min >= SQ or not np.isfinite(ix[0, e_min]):
        return None
    px_in = ix[0, e_min]
    x_min, why = SQ, "squareoff"
    for j in range(e_min, SQ):
        if idx_stop is not None and np.isfinite(ix[1, j]):
            hs = ix[2, j] <= idx_stop if up else ix[1, j] >= idx_stop
            ht = idx_target is not None and (ix[1, j] >= idx_target if up else ix[2, j] <= idx_target)
            if hs or ht:
                x_min, why = j + 1, "index_stop" if hs else "index_target"
                break
        if exit_min is not None and j >= exit_min:
            x_min, why = j, "time"
            break
    row = 3 if why == "time" else 0
    px_out = ix[row, min(x_min, W - 1)]
    if not np.isfinite(px_out):
        return None
    pts = (px_out - px_in) if up else (px_in - px_out)
    n = lot(mk.und, d)
    return {"day": d, "why": why, "pts": round(pts, 2), "qty": n, "net": round(pts * n, 2)}


# ------------------------------------------------------------------------------------------------ the strangle (sold)
def strangle_real(mk: Market, row: dict, fill: Fill):
    """S4 on real prices: sell the CE and PE strikes IraThinker named at 09:31 on the Monday; buy back at the exit day's
    15:29 close, or settle at intrinsic on the index's expiry-day close when the REAL expiry falls inside Mon-Thu. Only
    when the Monday's nearest listed expiry is a monthly (the studied contract); else None."""
    d = date.fromisoformat(row["monday"])
    if d not in mk.opt:
        return None
    exp = mk.expiry[d]
    if not is_monthly(mk, exp, d):
        return None
    lots = lot(mk.und, d)
    legs = []
    for right, k in (("CE", float(row["ce_strike"])), ("PE", float(row["pe_strike"]))):
        a = mk.opt[d].get((exp, k, right))
        if a is None:
            return None
        ef = first_print(a, 16, W)                            # 09:31
        if ef < 0:
            return None
        legs.append((right, k, fill.sell(a[0, ef], vol5_lots(a, ef, lots))))
    thu = d + timedelta(days=3)
    settle = exp <= thu
    exit_day = exp if settle else max((x for x in mk.days if d <= x <= thu), default=None)
    if exit_day is None or exit_day not in mk.ix:
        return None
    net, ch = 0.0, 0.0
    spot = mk.ix[exit_day][3][np.isfinite(mk.ix[exit_day][3])]
    if not len(spot):
        return None
    for right, k, p_in in legs:
        ch += charge(False, p_in, lots, d)
        if settle:
            p_out = max(0.0, spot[-1] - k) if right == "CE" else max(0.0, k - spot[-1])
        else:
            a = mk.opt.get(exit_day, {}).get((exp, k, right))
            if a is None:
                return None
            cl = a[3][np.isfinite(a[3])]
            if not len(cl):
                return None
            p_out = fill.buy(float(cl[-1]), vol5_lots(a, W - 1, lots))
            ch += charge(True, p_out, lots, exit_day)
        net += (p_in - p_out) * lots
    credit = sum(p for _, _, p in legs)
    return {"day": d, "expiry": exp, "exit_day": exit_day, "settle": settle, "credit": round(credit, 2), "qty": lots,
            "gross": round(net, 2), "charges": round(ch, 2), "net": round(net - ch, 2)}


def is_monthly(mk: Market, exp: date, d: date | None = None) -> bool:
    """Amendment 1 (26 Oct, after the first run showed holiday-moved weeklies read as monthlies): whether `exp`, the
    nearest listed expiry on day d, is the index's MONTHLY contract.
    - BANKNIFTY: only from 14 Nov 2024, when its weeklies had ended and the nearest contract is always the monthly.
    - NIFTY: within 3 days before the month's last Thursday (to Aug 2025) or last Tuesday (from Sep 2025): the
      monthly, holiday-shifted or not. A weekly moved back from the next month (30 Apr 2025) falls after it.
    - Anything else: no (no real monthly price)."""
    if mk.und == "BANKNIFTY":
        return d is not None and d >= date(2024, 11, 14)
    if mk.und == "NIFTY":
        wd = 3 if exp <= date(2025, 8, 31) else 1          # Thursday / Tuesday
        last = date(exp.year + exp.month // 12, exp.month % 12 + 1, 1) - timedelta(days=1)
        while last.weekday() != wd:
            last -= timedelta(days=1)
        return last - timedelta(days=3) <= exp <= last
    return False


# ------------------------------------------------------------------------------------------------ statistics
def boot_p(day_net: pd.Series, n_trades: int, rng: np.random.Generator) -> float:
    """Day-block bootstrap: resample days with replacement; p = share of draws whose mean per trade <= 0."""
    v = day_net.to_numpy()
    if len(v) < 2:
        return 1.0
    idx = rng.integers(0, len(v), size=(BOOT, len(v)))
    means = v[idx].sum(axis=1) / max(n_trades, 1)
    return float((means <= 0).mean())


def bh(ps: list[float]) -> list[float]:
    """Benjamini-Hochberg q-values."""
    n = len(ps)
    order = np.argsort(ps)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, ps[i] * n / rank)
        q[i] = prev
    return [float(x) for x in q]


def summarize(t: pd.DataFrame, rng: np.random.Generator) -> dict:
    if t.empty:
        return {"n": 0}
    dnet = t.groupby("day")["net"].sum()
    best5 = dnet.sort_values(ascending=False).iloc[5:].sum() if len(dnet) > 5 else float("nan")
    month = t.groupby(t["day"].map(lambda d: f"{d.year}-{d.month:02d}"))["net"].sum()
    return {"n": int(len(t)), "days": int(t["day"].nunique()), "win": round(float((t["net"] > 0).mean()) * 100, 1),
            "mean": round(float(t["net"].mean()), 1), "total": round(float(t["net"].sum()), 0),
            "p": round(boot_p(dnet, len(t), rng), 4), "ex_best5": round(float(best5), 0),
            "worst_month": round(float(month.min()), 0), "worst_day": round(float(dnet.min()), 0)}


# ------------------------------------------------------------------------------------------------ the tests
def entry_min_after(ts_epoch: int, bar_sec: int) -> tuple[date, int]:
    """The minute after the signal bar closes (a 5-minute bar: +300 s; a 1-minute bar: +60 s)."""
    return minute_of(int(ts_epoch) + bar_sec)


def run_directional(mk: Market, rows: pd.DataFrame, kind: str, fill: Fill, real: bool) -> pd.DataFrame:
    out = []
    for _, r in rows.iterrows():
        if kind in ("S1", "S1R", "S1A", "S2"):
            d, e = entry_min_after(r["signal_ts"], 300)
            up = int(r["up"]) == 1
            tgt = {"S1": r["target2r"], "S1R": r["target3r"], "S1A": r["target2r"], "S2": r["target1r"]}[kind]
            kw = dict(idx_stop=float(r["stop"]), idx_target=float(tgt))
            strike = round(float(r["entry"]) / STEP[mk.und]) * STEP[mk.und]
            tr = (long_option(mk, d, "CE" if up else "PE", strike, e, fill=fill, **kw) if real
                  else index_points(mk, d, up, e, **kw))
        elif kind == "S3":
            d, e = minute_of(int(r["entry_ts"]))
            up = int(r["call"]) == 1
            _, xm = minute_of(int(r["exit_ts"]) - 60)          # the bar whose close reaches exit_ts
            tr = (long_option(mk, d, "CE" if up else "PE", float(r["strike"]), e, prem_stop_mult=0.70, exit_min=xm,
                              fill=fill, need_expiry=lambda dd, ex: is_monthly(mk, ex, dd)) if real
                  else index_points(mk, d, up, e, exit_min=xm))
        elif kind == "S5":
            if int(r["dir60"]) == 0:
                continue
            d, e = minute_of(int(r["asof_ts"]))
            up = int(r["dir60"]) > 0
            ix = mk.ix.get(d)
            if ix is None or not np.isfinite(ix[0, e]):
                continue
            strike = round(ix[0, e] / STEP[mk.und]) * STEP[mk.und]
            tr = (long_option(mk, d, "CE" if up else "PE", strike, e, exit_min=e + 59, fill=fill) if real
                  else index_points(mk, d, up, e, exit_min=e + 59))
        else:
            raise ValueError(kind)
        if tr is not None:
            tr["test"] = kind
            out.append(tr)
    return pd.DataFrame(out)


def select(kind: str, orb: pd.DataFrame) -> pd.DataFrame:
    if kind in ("S1", "S1R"):
        return orb[(orb["kind"] == "ORB") & (orb["alert_grade"] == 1)]
    if kind == "S1A":
        return orb[orb["kind"] == "ORB"]
    return orb[orb["kind"] == "FADE"]


def main(rel: str, bars: str, sig: str, out: str) -> None:
    os.makedirs(out, exist_ok=True)
    rng = np.random.default_rng(SEED)
    results = []
    for und in ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]:
        real = und in OPT_FILES
        mk = Market(und, bars, rel if real else None)
        orb = pd.read_csv(os.path.join(sig, f"orb_{und}.csv"))
        mom = pd.read_csv(os.path.join(sig, f"mom60_{und}.csv"))
        pred_path = os.path.join(sig, f"predict_{und}.csv")
        pred = pd.read_csv(pred_path) if os.path.exists(pred_path) else pd.DataFrame()
        jobs = [(k, select(k, orb)) for k in ("S1", "S1R", "S1A", "S2")] + [("S3", mom)]
        if not pred.empty:
            jobs.append(("S5", pred))
        for kind, rows in jobs:
            signals = len(rows) if kind != "S5" else int((rows["dir60"] != 0).sum())
            t1 = run_directional(mk, rows, kind, Fill(1.0), real)
            t2 = run_directional(mk, rows, kind, Fill(2.0), real) if real else pd.DataFrame()
            t1.to_csv(os.path.join(out, f"trades_{kind}_{und}.csv"), index=False)
            results.append(block(und, kind, real, signals, t1, t2, rng))
            print(und, kind, results[-1]["design"].get("n"), results[-1]["design"].get("mean"), flush=True)
        if und == "BANKNIFTY":
            st = pd.read_csv(os.path.join(sig, "strangle_BANKNIFTY.csv"))
            t1 = pd.DataFrame([x for x in (strangle_real(mk, r, Fill(1.0)) for r in st.to_dict("records")) if x])
            t2 = pd.DataFrame([x for x in (strangle_real(mk, r, Fill(2.0)) for r in st.to_dict("records")) if x])
            t1.to_csv(os.path.join(out, "trades_S4_BANKNIFTY.csv"), index=False)
            res = block(und, "S4", True, len(st), t1, t2, rng)
            # IraThinker's own BS paper tape (lot 30, its flat 1pp cost) beside the real result
            st["day"] = pd.to_datetime(st["monday"]).dt.date
            res["irathinker_tape"] = {"weeks": int(st["pnl_rs_lot30"].notna().sum()),
                                      "mean_net_pct": round(float(st["net_pct"].mean()), 2),
                                      "total_rs_lot30": round(float(st["pnl_rs_lot30"].sum()), 0)}
            results.append(res)
        # IraThinker's own mom60 BS tape (per trade, % of premium, no cost) beside the real result
        for r in results:
            if r["index"] == und and r["test"] == "S3":
                r["irathinker_tape"] = {"trades": int(len(mom)), "mean_pnl_pct": round(float(mom["pnl_pct"].mean()), 2),
                                        "win": round(float((mom["pnl_pct"] > 0).mean()) * 100, 1)}
    # BH over every real-price test's design p (gate 3)
    real_ix = [i for i, r in enumerate(results) if r["real"] and r["design"].get("n", 0) > 0]
    qs = bh([results[i]["design"]["p"] for i in real_ix])
    for i, q in zip(real_ix, qs):
        results[i]["design"]["q"] = round(q, 4)
    for r in results:
        r["gates"], r["pass_design"] = gates(r)
        if r["pass_design"]:
            r["holdout_read"] = True
            h = r["holdout"]
            r["pass_holdout"] = bool(h.get("n", 0) >= 10 and h.get("mean", -1) > 0)
        else:
            r["holdout_read"] = False
            r["holdout"] = {"n": r["holdout"].get("n", 0), "note": "not read (failed design)"}
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump(results, f, indent=1, default=str)
    print("done", len(results), "tests")


def block(und, kind, real, signals, t1, t2, rng) -> dict:
    if t1.empty:
        return {"index": und, "test": kind, "real": real, "signals": signals, "design": {"n": 0}, "holdout": {"n": 0}}
    des = t1[t1["day"] <= DESIGN_END]
    hol = t1[t1["day"] > DESIGN_END]
    r = {"index": und, "test": kind, "real": real, "signals": signals, "priced": int(len(t1)),
         "design": summarize(des, rng), "holdout": summarize(hol, rng)}
    if real and not t2.empty:
        r["design"]["net_2x_slip"] = round(float(t2[t2["day"] <= DESIGN_END]["net"].sum()), 0)
    return r


def gates(r: dict) -> tuple[dict, bool]:
    d = r["design"]
    if not r["real"]:
        return {"note": "index points only (no option data): descriptive, cannot pass"}, False
    if d.get("n", 0) == 0:
        return {"note": "no real-priced trade"}, False
    g = {"1_n40": d["n"] >= 40, "2_mean_p": d["mean"] > 0 and d["p"] < 0.05, "3_bh": d.get("q", 1) <= 0.10,
         "4_slip2x": d.get("net_2x_slip", -1) > 0, "5_ex_best5": d["ex_best5"] > 0,
         "6_worst_month": d["worst_month"] >= -13_000}
    return g, all(g.values())


if __name__ == "__main__":
    main(*sys.argv[1:5])
