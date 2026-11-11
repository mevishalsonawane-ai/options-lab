"""R161 (PREREG.md): does the frozen index print (15:15-15:28 since 3 Aug 2026) change Night (R3)'s 15:20 decision?

    R160_LAKE=/path/to/lake python3 -I -m research.hunt.r161_night_frozen.study      (run from the repo root)

Reading A is the index's 15:19 close (what NightRules reads). Reading B is the live market at 15:19: the option-implied
index (median 15:17-15:19) less the day's 14:30-14:59 basis, with futures as a check. Writes results/results.json and
results/nights.csv (bps and flags only, no prices).
"""
from __future__ import annotations

import hashlib
import json
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r160_cas_gap import cfg as K0
from research.hunt.r160_cas_gap import measure as M
from research.hunt.r160_cas_gap import study as S0
from research.hunt.r160_cas_gap import trade as T

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
PREREG = HERE / "PREREG.md"
UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
LOTS = {"NIFTY": [(date(2024, 11, 28), 75), (date(2025, 12, 31), 65)],
        "BANKNIFTY": [(date(2024, 11, 28), 30), (date(2025, 6, 27), 35), (date(2025, 12, 31), 30)]}
R1519 = K0.row(15, 19)
R1520 = K0.row(15, 20)
B_ROWS = (K0.row(15, 17), R1519)
R1514 = K0.row(15, 14)
LOC_UP, LOC_DOWN = 0.6, 0.4          # NightRules.LOC_UP / LOC_DOWN


def lot(u: str, d: date) -> int:
    out = 0
    for s, n in LOTS[u]:
        if d >= s:
            out = n
    return out


# ================================================================================================ pure rules
def atm(x: float, step: float) -> float:
    """NightRules / OrbRules.atmStrike: floor(x / step + 0.5) x step."""
    return float(np.floor(x / step + 0.5) * step)


def loc(x: float, hi: float, lo: float) -> float:
    return (x - lo) / (hi - lo) if hi > lo else 0.5


def condition(x: float, hi: float, lo: float, prev: float) -> int:
    """Night's index part of R17's strong close: +1 (loc >= 0.6, up), -1 (loc <= 0.4, down), 0."""
    if not all(np.isfinite(v) for v in (x, hi, lo, prev)):
        return 0
    lc, mv = loc(x, hi, lo), x / prev - 1
    if lc >= LOC_UP and mv >= 0:
        return 1
    if lc <= LOC_DOWN and mv <= 0:
        return -1
    return 0


def reading_b(f: np.ndarray, idx: np.ndarray) -> float:
    """The live index at 15:19: median derivative-implied over 15:17-15:19 less the 14:30-14:59 basis (2+ minutes)."""
    b = M.basis(f, idx)
    v = f[B_ROWS[0]: B_ROWS[1] + 1]
    v = v[np.isfinite(v)]
    if v.size < 2 or not np.isfinite(b):
        return np.nan
    return float(np.median(v) - b)


def day_inputs(cl: np.ndarray, hi: np.ndarray, lo: np.ndarray, f: np.ndarray, prev: float, step: float) -> dict:
    """A and B for one day: readings, ranges, conditions and strikes. Reads nothing after the 15:19 bar."""
    xa = cl[R1519]
    ha, la = np.nanmax(hi[: R1519 + 1]), np.nanmin(lo[: R1519 + 1])
    xb = reading_b(f, cl)
    hb0, lb0 = np.nanmax(hi[: R1514 + 1]), np.nanmin(lo[: R1514 + 1])
    hb, lb = (max(hb0, xb), min(lb0, xb)) if np.isfinite(xb) else (hb0, lb0)
    return dict(xa=float(xa), xb=float(xb), gap_bps=M.bps(xb, xa),
                ca=condition(xa, ha, la, prev), cb=condition(xb, hb, lb, prev),
                ka=atm(xa, step) if np.isfinite(xa) else np.nan, kb=atm(xb, step) if np.isfinite(xb) else np.nan)


def flip_kind(ca: int, cb: int) -> str:
    if ca == cb:
        return "same"
    if ca != 0 and cb == 0:
        return "A_only"
    if ca == 0 and cb != 0:
        return "B_only"
    return "opposite"


# ================================================================================================ loading
def load_index_ohlc(u: str) -> tuple[dict, dict, dict]:
    parts = []
    for y in (2025, 2026):
        parts.append(S0._naive(pd.read_parquet(K0.LAKE / "index_1m" / f"index_1m_{u}_{y}.parquet",
                                               columns=["ts", "high", "low", "close"])))
    x = pd.concat(parts)
    x["d"] = x.ts.dt.date
    x["r"] = x.ts.dt.hour * 60 + x.ts.dt.minute - (9 * 60 + 15)
    x = x[(x.r >= 0) & (x.r < K0.N)].drop_duplicates(["d", "r"], keep="last")
    return M.to_grid(x, "close"), M.to_grid(x, "high"), M.to_grid(x, "low")


# ================================================================================================ pricing a flip
def price(u: str, d: date, side: int, x: float, bk_d: dict, bk_nd: dict) -> float | None:
    """Night's 1-ITM leg from reading x: bought at the 15:20 open, sold at the 09:16 open (forward to 09:45)."""
    k = atm(x, STEP[u]) - side * STEP[u]
    s = "C" if side > 0 else "P"
    a, nx = bk_d.get((s, k)), bk_nd.get((s, k))
    if a is None or nx is None or not T.guard(a, R1520):
        return None
    for r in range(K0.EXIT_ROW, K0.EXIT_LAST + 1):
        if np.isfinite(nx[0, r]):
            if not T.guard(nx, r):
                return None
            return T.net(T.Leg(d, s, k, lot(u, d), float(a[0, R1520]), float(nx[0, r]), r))
    return None


def main() -> None:
    sha = hashlib.sha256(PREREG.read_bytes()).hexdigest()
    RESULTS.mkdir(exist_ok=True)
    res = dict(study="R161", prereg_sha256=sha, run_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"), und={})
    rows = []
    for u in UNDS:
        cl, hi, lo = load_index_ohlc(u)
        daily = S0.load_daily(u)
        opt = S0.load_options(u)
        fg = M.to_grid(M.implied_minutes(opt), "F")
        fut = S0.load_futures(u)
        days = list(daily.index)
        exp = M.expiry_days([d for d in days if d >= date(2025, 9, 1)], K0.EXPIRY_WEEKDAY[u], u in K0.MONTHLY)
        recs = []
        for i, d in enumerate(days[1:-1], start=1):
            per = "POST" if M.in_period(d, K0.POST) else "PRE" if M.in_period(d, K0.PRE) else None
            if per is None or d not in cl or d not in fg:
                continue
            r = day_inputs(cl[d], hi[d], lo[d], fg[d], float(daily.close[days[i - 1]]), STEP[u])
            if not np.isfinite(r["xb"]):
                continue
            r.update(und=u, d=d, nd=days[i + 1], period=per, kind=flip_kind(r["ca"], r["cb"]),
                     tradable=bool(d not in exp and days[i + 1] not in exp))
            if per == "POST" and d in fut:
                rf = day_inputs(cl[d], hi[d], lo[d], fut[d], float(daily.close[days[i - 1]]), STEP[u])
                r.update(cb_fut=rf["cb"], gap_bps_fut=rf["gap_bps"], kind_fut=flip_kind(rf["ca"], rf["cb"]))
            recs.append(r)
        x = pd.DataFrame(recs)
        out = {}
        for per in ("POST", "PRE"):
            p = x[x.period == per]
            g = p.gap_bps.abs()
            out[per] = dict(nights=int(len(p)), mean_abs_gap_bps=round(float(g.mean()), 2),
                            p90_abs_gap_bps=round(float(g.quantile(0.9)), 2),
                            flip_rate=round(float((p.kind != "same").mean()), 4),
                            flips={k: int((p.kind == k).sum()) for k in ("A_only", "B_only", "opposite")},
                            strike_rate=round(float((p.ka != p.kb).mean()), 4),
                            A_signals=int((p.ca != 0).sum()))
            if per == "POST" and "kind_fut" in p:
                pf = p.dropna(subset=["kind_fut"])
                out["POST_fut"] = dict(nights=int(len(pf)), mean_abs_gap_bps=round(float(pf.gap_bps_fut.abs().mean()), 2),
                                       flip_rate=round(float((pf.kind_fut != "same").mean()), 4),
                                       flips={k: int((pf.kind_fut == k).sum()) for k in ("A_only", "B_only", "opposite")})
        # value of each POST flip (upper bound: breadth and OI not applied)
        post = x[(x.period == "POST") & (x.kind != "same") & x.tradable]
        o = opt[(opt.ts >= pd.Timestamp(K0.POST[0])) & (opt.ts < pd.Timestamp(K0.POST[1]) + pd.Timedelta(days=7))]
        by = {dd: g for dd, g in o.groupby(o.ts.dt.date)}
        flips = []
        for _, n in post.iterrows():
            bd, bnd = T.book(by.get(n.d, o.iloc[:0])), T.book(by.get(n.nd, o.iloc[:0]))
            pa = price(u, n.d, int(n.ca), n.xa, bd, bnd) if n.ca else 0.0
            pb = price(u, n.d, int(n.cb), n.xb, bd, bnd) if n.cb else 0.0
            flips.append(dict(d=str(n.d), kind=n.kind, A=int(n.ca), B=int(n.cb), gap_bps=round(n.gap_bps, 1),
                              net_A=None if pa is None else round(pa), net_B=None if pb is None else round(pb)))
        out["post_flips_priced"] = flips
        ok = [f for f in flips if f["net_A"] is not None and f["net_B"] is not None]
        out["post_flips_net"] = dict(priced=len(ok), A_total=sum(f["net_A"] for f in ok),
                                     B_total=sum(f["net_B"] for f in ok), not_tradable=int(((x.period == "POST") &
                                                                                            (x.kind != "same") & ~x.tradable).sum()))
        a, b = out["POST"], out["PRE"]
        out["bar"] = dict(flip_rate_excess_pp=round(100 * (a["flip_rate"] - b["flip_rate"]), 2),
                          gap_ratio=round(a["mean_abs_gap_bps"] / b["mean_abs_gap_bps"], 2) if b["mean_abs_gap_bps"] else None)
        res["und"][u] = out
        rows.append(x)
        print(u, json.dumps({k: v for k, v in out.items() if k != "post_flips_priced"}, default=str), flush=True)
    res["recommend_shadow"] = bool(any(res["und"][u]["bar"]["flip_rate_excess_pp"] >= 5 and
                                       (res["und"][u]["bar"]["gap_ratio"] or 0) >= 2 for u in UNDS))
    xa = pd.concat(rows)
    cols = ["und", "d", "period", "gap_bps", "ca", "cb", "kind", "tradable"] + [c for c in ("cb_fut", "gap_bps_fut", "kind_fut")
                                                                                if c in xa]
    xa[cols].round(3).to_csv(RESULTS / "nights.csv", index=False)
    (RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    print("recommend_shadow", res["recommend_shadow"])


if __name__ == "__main__":
    main()
