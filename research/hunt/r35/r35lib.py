"""R35: the 1-minute Dual-Supertrend hero framework (PREREG.md), on NIFTY and SENSEX expiries. R26's engine for fills,
exits and charges; R34's iv_rv for S4.

    python3 research/hunt/r35/r35lib.py      # -> results/results.json, results/report.md

Grid: row t = the bar that STARTS at 09:15 + t (closes at t + 1). A decision at row t reads rows <= t only.
"""
from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass, replace
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r34", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r34cfg as K27  # noqa: E402  (puts r25 / r26 on the path; R26's day files)
import r25cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
import r26lib as L26  # noqa: E402
import r34feat as F  # noqa: E402
from r25data import C, H, L, N, O, V, minute  # noqa: E402
from research.brain import bs  # noqa: E402

RESULTS = HERE / "results"
UNDS = ("NIFTY", "SENSEX")
FIRST, LAST = R.close_row(14, 0), R.close_row(14, 45)
HEAVY = ("HDFCBANK", "RELIANCE", "ICICIBANK")
BUDGET = 2000.0
TWINS, SEED = 1000, 28
EQ = K.CACHE.root / "lake" / "eq"


@dataclass(frozen=True)
class Rule:
    id: str
    note: str
    exits: str = "E3"
    heavy: bool = True
    ivrv: float | None = None
    chart: str = "option"          # option | index


S1 = Rule("S1", "Dual-Supertrend on the option premium + option volume 2x EMA10 + index TWAP side + 3 heavyweights, step lock")
RULES = [S1, replace(S1, id="S2", exits="E1", note="S1 held to 15:05"),
         replace(S1, id="S3", heavy=False, note="S1 without the heavyweight check"),
         replace(S1, id="S4", ivrv=1.0, note="S1 plus R34's price check (iv_rv <= 1.0)"),
         replace(S1, id="S5", chart="index", note="S1 with the Supertrends on the index chart")]


# ------------------------------------------------------------------------------------------------ indicators
def supertrend(h: np.ndarray, l: np.ndarray, c: np.ndarray, n: int, m: float) -> np.ndarray:
    """+1 green / -1 red / 0 not yet, per bar of a gap-free series (Wilder ATR; standard final-band recursion). Bar i
    depends on bars <= i only."""
    k = len(c)
    out = np.zeros(k)
    if k == 0:
        return out
    tr = np.empty(k)
    tr[0] = h[0] - l[0]
    tr[1:] = np.maximum(h[1:] - l[1:], np.maximum(np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])))
    atr = np.full(k, np.nan)
    if k >= n:
        atr[n - 1] = tr[:n].mean()
        for i in range(n, k):
            atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    hl2 = (h + l) / 2
    up, dn = hl2 + m * atr, hl2 - m * atr
    fu, fd = up.copy(), dn.copy()
    trend = 0
    for i in range(k):
        if np.isnan(atr[i]):
            continue
        if i > 0 and not np.isnan(atr[i - 1]):
            fu[i] = up[i] if (up[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
            fd[i] = dn[i] if (dn[i] > fd[i - 1] or c[i - 1] < fd[i - 1]) else fd[i - 1]
            if c[i] > fu[i - 1]:
                trend = 1
            elif c[i] < fd[i - 1]:
                trend = -1
            elif trend == 0:
                trend = 1 if c[i] >= hl2[i] else -1
        out[i] = trend
    return out


def ema_prev(v: np.ndarray, n: int = 10) -> np.ndarray:
    """EMA of v over bars < i (alpha 2/(n+1)); NaN before the first bar."""
    out = np.full(len(v), np.nan)
    a = 2.0 / (n + 1)
    e = math.nan
    for i in range(len(v)):
        out[i] = e
        e = v[i] if math.isnan(e) else a * v[i] + (1 - a) * e
    return out


def twap(day) -> np.ndarray:
    tp = (day.ix[:, 1] + day.ix[:, 2] + day.ix[:, 3]) / 3
    ok = np.isfinite(tp)
    cs = np.cumsum(np.where(ok, tp, 0.0))
    cn = np.cumsum(ok)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cn > 0, cs / cn, np.nan)


# ------------------------------------------------------------------------------------------------ heavyweights
_EQ: dict = {}


def heavy_bars(d) -> dict | None:
    """{symbol: (N, 2) open / close grid} for the day; None when the files are absent."""
    y = d.year
    if y not in _EQ:
        p = EQ / f"equity_1m_{y}.parquet"
        if not p.exists():
            _EQ[y] = None
        else:
            df = pd.read_parquet(p, columns=["symbol", "open", "close", "ts"], filters=[("symbol", "in", list(HEAVY))])
            ts = pd.to_datetime(df.ts)
            if ts.dt.tz is not None:
                ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
            df["d"] = ts.dt.date
            df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
            df = df[(df.m >= 0) & (df.m < N)]
            _EQ[y] = {k: g for k, g in df.groupby(["d", "symbol"], observed=True)}
    if _EQ[y] is None:
        return None
    out = {}
    for s in HEAVY:
        g = _EQ[y].get((d, s))
        a = np.full((N, 2), np.nan)
        if g is not None:
            a[g.m.values] = g[["open", "close"]].values
        out[s] = a
    return out


# ------------------------------------------------------------------------------------------------ the trigger
def st_pair_at(a: np.ndarray, t: int) -> tuple[int, int, int, int] | None:
    """(ST7 now, ST10 now, ST7 previous bar, ST10 previous bar) on the option's own traded bars up to row t."""
    rows = np.nonzero((np.nan_to_num(a[: t + 1, V]) > 0) & np.isfinite(a[: t + 1, C]))[0]
    if len(rows) < 12 or rows[-1] != t:
        return None
    h, l, c = a[rows, H], a[rows, L], a[rows, C]
    s7, s10 = supertrend(h, l, c, 7, 3.0), supertrend(h, l, c, 10, 2.0)
    return int(s7[-1]), int(s10[-1]), int(s7[-2]), int(s10[-2])


def vol_ok(a: np.ndarray, t: int) -> bool:
    rows = np.nonzero(np.isfinite(a[: t + 1, C]))[0]
    if len(rows) < 11 or rows[-1] != t:
        return False
    v = np.nan_to_num(a[rows, V])
    e = ema_prev(v)[-1]
    return bool(e > 0 and v[-1] >= 2.0 * e - 1e-9)


class Ctx:
    """Per-day caches (point-in-time arrays)."""

    def __init__(self, day, ratios: np.ndarray | None):
        self.tw = twap(day)
        self.hv = heavy_bars(day.day)
        self.ratios = ratios
        ix = day.ix
        ok = np.isfinite(ix[:, 3])
        self.ix_rows = np.nonzero(ok)[0]
        self.ist7 = np.zeros(N)
        self.ist10 = np.zeros(N)
        if len(self.ix_rows):
            r = self.ix_rows
            self.ist7[r] = supertrend(ix[r, 1], ix[r, 2], ix[r, 3], 7, 3.0)
            self.ist10[r] = supertrend(ix[r, 1], ix[r, 2], ix[r, 3], 10, 2.0)


def heavy_ok(ctx: Ctx, t: int, sgn: int) -> bool:
    if ctx.hv is None:
        return False
    for s in HEAVY:
        o, c = ctx.hv[s][t]
        if not (np.isfinite(o) and np.isfinite(c)) or (c - o) * sgn <= 0:
            return False
    return True


def ivrv_at(day, k: float, sgn: int, p: float, t: int, ratio: float) -> float:
    vs = F.vol_state(day, t, ratio)
    s = R.spot(day, t)
    T = max(F.mins_left(t), 1) / F.YEAR_MIN
    iv = float(bs.implied_vol(np.array([p]), np.array([s]), np.array([k]), np.array([T]), np.array([sgn > 0]))[0])
    fc = vs["rv_fc"]
    return iv * 100 / fc if iv == iv and fc == fc and fc > 0 else math.nan


def nearest_otm(day, t: int, sgn: int):
    s = R.spot(day, t)
    if math.isnan(s):
        return None
    k = R.atm(day, s) + sgn * K.STEP[day.und]
    a = day.opts.get((k, "C" if sgn > 0 else "P"))
    return (k, a) if a is not None else None


def fires(day, rule: Rule, ctx: Ctx, t: int, sgn: int):
    """(strike, price) when every condition holds at row t for direction sgn; None otherwise."""
    s = R.spot(day, t)
    if math.isnan(s) or math.isnan(ctx.tw[t]) or (s - ctx.tw[t]) * sgn <= 0:
        return None
    ko = nearest_otm(day, t, sgn)
    if ko is None:
        return None
    k, a = ko
    if rule.chart == "option":
        st = st_pair_at(a, t)
        if st is None or not (st[0] == 1 and st[1] == 1 and not (st[2] == 1 and st[3] == 1)):
            return None
    else:
        r = ctx.ix_rows[ctx.ix_rows <= t]
        if len(r) < 2 or r[-1] != t:
            return None
        now = ctx.ist7[t] == sgn and ctx.ist10[t] == sgn
        prev = ctx.ist7[r[-2]] == sgn and ctx.ist10[r[-2]] == sgn
        if not (now and not prev):
            return None
    if not vol_ok(a, t):
        return None
    if rule.heavy and not heavy_ok(ctx, t, sgn):
        return None
    p = float(a[t, C])
    if not (p > 0):
        return None
    if rule.ivrv is not None:
        j = (t - F.DECISIONS[0]) // 5
        ratio = float(ctx.ratios[min(max(j, 0), len(F.DECISIONS) - 1)]) if ctx.ratios is not None else 1.0
        x = ivrv_at(day, k, sgn, p, t, ratio)
        if not (x == x and x <= rule.ivrv + 1e-9):
            return None
    return k, p


def signal(day, rule: Rule, ctx: Ctx):
    for t in range(FIRST, LAST + 1):
        for sgn in (1, -1):
            f = fires(day, rule, ctx, t, sgn)
            if f is not None:
                return t, sgn, f[0], f[1]
    return None


def trade(day, k: float, sgn: int, t: int, p: float, exits: str, mult: float = 1.0):
    """None when not filled or when one lot costs more than Rs 2,000 (the spec's skip); the trade otherwise."""
    f = R.enter(day, k, "C" if sgn > 0 else "P", t, p, mult)
    if f is None:
        return None, False
    if f[1] * day.lot > BUDGET + 1e-9:
        return None, True
    return L26.simulate(day, k, sgn, t, p, exits, BUDGET, mult), False


def twins(base: list, rule: Rule, n: int = TWINS, seed: int = SEED) -> np.ndarray:
    rng = np.random.default_rng(seed)
    cache: dict = {}
    out = np.zeros(n)
    for i in range(n):
        tot = 0.0
        for day, sgn in base:
            t = int(rng.integers(FIRST, LAST + 1))
            key = (day.und, day.day, t, sgn)
            if key not in cache:
                net = 0.0
                ko = nearest_otm(day, t, sgn)
                if ko is not None:
                    k, a = ko
                    pr, row = R.ltp(a, t)
                    if row >= t - 4 and pr == pr and pr > 0:
                        tr, _ = trade(day, k, sgn, t, pr, rule.exits)
                        net = tr.net if tr else 0.0
                cache[key] = net
            tot += cache[key]
        out[i] = tot
    return out


class _R:
    unds = UNDS


def main():
    days = []
    ratios = {}
    for u in UNDS:
        ds = sorted(D.load(u, "all"), key=lambda d: d.day)
        rat = F.all_ratios(ds)
        for d, r in zip(ds, rat):
            ratios[(u, d.day)] = r
        days += ds
    days.sort(key=lambda d: (d.day, d.und))
    ctxs = {(d.und, d.day): Ctx(d, ratios[(d.und, d.day)]) for d in days}
    res = {}
    for rule in RULES:
        trs, base, skips = [], [], 0
        for d in days:
            s = signal(d, rule, ctxs[(d.und, d.day)])
            if s is None:
                continue
            t, sgn, k, p = s
            tr, sk = trade(d, k, sgn, t, p, rule.exits)
            skips += sk
            if tr is not None:
                tr.sig = t
                trs.append(tr)
                base.append((d, sgn))
        m = RU.metrics(trs, days, _R())
        n = len(trs)
        for x in (2, 4, 10):
            m[f"x{x}_pct"] = round(100 * sum(t.peak_mult >= x for t in trs) / n, 1) if n else 0.0
        tw = twins(base, rule)
        x2 = 0.0
        for d, t_ in zip([b[0] for b in base], trs):
            tr2, _ = trade(d, t_.strike, t_.side, t_.sig, float(d.opts[(t_.strike, 'C' if t_.side > 0 else 'P')][t_.sig, C]), rule.exits, 2.0)
            x2 += tr2.net if tr2 else 0.0
        by = lambda f: RU.metrics([t for t in trs if f(t.und, t.day)], [d for d in days if f(d.und, d.day)], _R())  # noqa: E731
        res[rule.id] = {
            "note": rule.note, "m": m, "skips_1lot_over_2000": skips,
            "index": {u: by(lambda uu, dd, u=u: uu == u) for u in UNDS},
            "blocks": {"2021-22": by(lambda uu, dd: dd.year <= 2022), "2023-26": by(lambda uu, dd: dd.year >= 2023)},
            "years": {str(y): by(lambda uu, dd, y=y: dd.year == y) for y in range(2021, 2027)},
            "direction": {"CE": sum(t.side > 0 for t in trs), "PE": sum(t.side < 0 for t in trs)},
            "x2_spread": round(x2, 0),
            "twins": {"p": round(float((tw >= m["net"] - 1e-9).mean()), 4) if n else 1.0, "mean": round(float(tw.mean()), 0)},
            "trades_list": [dict(und=t.und, day=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike, sig=t.sig,
                                 entry=t.entry, lots=t.lots, peak=round(t.peak_mult, 2), net=round(t.net, 0)) for t in trs]}
        print(rule.id, n, round(m["net"]), "skips", skips, "p", res[rule.id]["twins"]["p"], flush=True)
    # the earlier 39 p-values (R21 9 + R25 9 + R26 10 + R34 11) and R35's 5
    p39 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())["rules"]
    fam = {k: v["p"] for k, v in p39.items()} | {f"R34-{k}": v["twins"]["p"] for k, v in r34.items()} | \
          {f"R35-{k}": v["twins"]["p"] for k, v in res.items()}
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid, v in res.items():
        v["q"] = round(qs[f"R35-{rid}"], 4)
        m = v["m"]
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 blocks > 0": all(v["blocks"][b]["net"] > 0 for b in v["blocks"] if v["blocks"][b]["trades"] > 0),
             "4 BH q < 0.05": v["q"] < 0.05, "5 x2 spread > 0": v["x2_spread"] > 0,
             "6 w/o best day > 0": m["net_without_best_day"] > 0, "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = {"bh_size": len(fam), "bh_min_q": round(min(qs.values()), 4), "expiries": {u: sum(d.und == u for d in days) for u in UNDS},
           "rules": res}
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH", len(fam), "min q", out["bh_min_q"], "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
