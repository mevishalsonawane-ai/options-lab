#!/usr/bin/env python3
"""R20b: support / resistance levels as the ORB range (PREREG.md). Research only.

    python3 research/hunt/r20b/study.py prep              # per-day inputs + the option outcome tables (cached in OUT)
    python3 research/hunt/r20b/study.py design            # design period only -> results/design.json (frozen)
    python3 research/hunt/r20b/study.py holdout           # the ONE holdout read -> results/holdout.json (refuses twice)

Data and costs are R17's (research/hunt/r17: data.py loader, arms.py fills / charges / exits); the holdout cut dates are
R17's. The outcome table: for every decision bar k (10:05 .. 13:55 bars) and side, the ORB trade it would open (strike =
the 09:20 bar's close, the nearest expiry after the day, next-minute entry, premium floor, -stop / +target, ladder,
15:10), so every variant reads the same trades and differs only in when it fires.
"""
from __future__ import annotations

import json
import math
import os
import pickle
import sys
from dataclasses import dataclass, field

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
R17 = os.path.abspath(os.path.join(HERE, "..", "r17"))
for p in (R17, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

import config as K17  # noqa: E402  (R17: paths, SOURCE, holdout cuts; sets sys.path to the repo)
import levels as LV  # noqa: E402

RESULTS = os.path.join(HERE, "results")
OUT = os.path.join(str(K17.CACHE.root), "r20b")
FLAG = os.path.join(OUT, "HOLDOUT_READ")

K0, K1 = 10, 56                      # decision bars: labelled 10:05 .. 13:55
ARM = {"BANKNIFTY": dict(step=100, stop=40.0, tgt=40.0, floor=40.0), "NIFTY": dict(step=50, stop=20.0, tgt=20.0, floor=20.0)}
WIDE_K = 0.75
NEAR = 0.10                          # levels within 0.1 x ATR14 of P0 are ignored in (a) and (b)
CONFIRM_SPAN = 0.50                  # (c): the level to clear lies within 0.5 x ATR14 beyond the range edge
TOUCH = 0.05                         # (d): a touch is within 0.05 x ATR14 of the level
VARIANTS = ["A-ALL", "A-PIV", "A-OI", "A-OIi", "B", "C", "D"]
IDS = {"A-ALL": "r20sr-a-all", "A-PIV": "r20sr-a-piv", "A-OI": "r20sr-a-oi", "A-OIi": "r20sr-a-oii", "B": "r20sr-b",
       "C": "r20sr-c", "D": "r20sr-d"}
TWINS = 1000
SEED = 2020


# ------------------------------------------------------------------------------------------------ per-day inputs
@dataclass
class DayIn:
    day: object
    und: str
    i: int
    atr: float
    orh: float
    orl: float
    p0: float
    bh: np.ndarray                   # 5-minute bar highs / lows / closes (75)
    bl: np.ndarray
    bc: np.ndarray
    piv: list
    oi: list
    oii: list
    liq: LV.LiqDay
    table: dict = field(default_factory=dict)   # (k, side) -> (net, exit_row) or absent: no trade possible

    @property
    def w45(self):
        return self.orh - self.orl

    @property
    def wide(self):
        return self.w45 > WIDE_K * self.atr


def five(ix):
    bh = ix[:, 1].reshape(75, 5).max(axis=1)
    bl = ix[:, 2].reshape(75, 5).min(axis=1)
    bc = ix[4::5, 3].copy()
    return bh, bl, bc


def outcome_table(d, und):
    """(k, side) -> (net Rs, exit row) for every decision bar and side (R17's ORB trade, R20's arm settings)."""
    import arms as A
    a = ARM[und]
    bc = d.ix[4::5, 3]
    strike = float(math.floor(bc[1] / a["step"] + 0.5) * a["step"])
    out = {}
    for s in (1, -1):
        exp, op = A.contract(d, strike, s, same_day_ok=False)
        if op is None:
            continue
        for k in range(K0, K1 + 1):
            sig = 5 * k + 4
            c0, px = A.next_open(op, sig + 1)
            if c0 > sig + 3 or px <= a["floor"]:
                continue
            e = A.buy_fill(px, op, c0, d.lot)
            x, xr, why = A.sim_fixed_pts(op, c0, e, a["stop"], a["tgt"], d.lot, d.lot)
            net = (x - e) * d.lot - A.charges(e, x, d.lot)
            out[(k, s)] = (float(net), int(xr))
    return out


def oii_cache(und):
    """{day: [support, resistance]} from the release's minute OI (built once; cached)."""
    p = os.path.join(OUT, f"oii_{K17.SOURCE}_{und}.json")
    if os.path.exists(p):
        return {k: v for k, v in json.load(open(p)).items()}
    ad = K17.adapter()
    ss = ad.sessions(und)
    out = {}
    for dd in ad.load(und, ss[0], ss[-1]):
        out[str(dd.day)] = LV.oii_walls(dd.options, dd.day)
    os.makedirs(OUT, exist_ok=True)
    json.dump(out, open(p, "w"))
    return out


def bhav_days(und):
    import oi as OI
    ds = []
    for f in OI.bhav_dir().glob(f"{und}_*.csv"):
        s = f.stem.split("_")[1]
        ds.append(__import__("datetime").date(int(s[:4]), int(s[4:6]), int(s[6:])))
    return sorted(ds)


def tables(und):
    """{day: outcome_table} for every session (cached; no level enters it)."""
    p = os.path.join(OUT, f"tables_{K17.SOURCE}_{und}.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    import data as D17
    out = {}
    for n, d in enumerate(D17.load(und)):
        out[d.day] = outcome_table(d, und)
        if n % 100 == 0:
            print(und, "tables", n, d.day, flush=True)
    os.makedirs(OUT, exist_ok=True)
    with open(p, "wb") as f:
        pickle.dump(out, f, protocol=4)
    return out


def prep(und):
    p = os.path.join(OUT, f"prep_{K17.SOURCE}_{und}.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    import data as D17
    days = D17.load(und)
    oii = oii_cache(und)
    bd = set(bhav_days(und))
    tb = tables(und)
    out = []
    for i, d in enumerate(days):
        atr = LV.atr14(days, i)
        if not np.isfinite(atr):
            continue
        bh, bl, bc = five(d.ix)
        prev_b = days[i - 1].day if days[i - 1].day in bd else None     # the previous session's file, never an older one
        di = DayIn(d.day, und, i, atr, float(bh[:10].max()), float(bl[:10].min()), float(d.ix[LV.P0_ROW, 3]), bh, bl, bc,
                   LV.piv_levels(days[i - 1].ix), LV.oi_walls(und, d.day, prev_b), oii.get(str(d.day), []),
                   LV.LiqDay(LV.liq_history(days, i), d.ix))
        di.table = tb[d.day]
        out.append(di)
        if len(out) % 100 == 0:
            print(und, len(out), d.day, flush=True)
    with open(p, "wb") as f:
        pickle.dump(out, f, protocol=4)
    return out


# ------------------------------------------------------------------------------------------------ levels per variant
class Levels:
    """The level sets one day's variants read, with an optional mirror (the random-level twin)."""

    def __init__(self, di: DayIn, rng=None):
        self.di = di
        self.rng = rng
        self.signs: dict = {}

    def _t(self, key, v):
        if self.rng is None:
            return v
        s = self.signs.get(key)
        if s is None:
            s = 1 if self.rng.random() < 0.5 else -1
            self.signs[key] = s
        return LV.mirror(v, self.di.p0, s)

    def static(self, src):
        vals = getattr(self.di, src)
        return [self._t((src, n), v) for n, v in enumerate(vals)]

    def liq(self, k):
        return [self._t(("liq",) + key, v) for v, key in self.di.liq.edges(k)]

    def all(self, k):
        return self.static("piv") + self.static("oi") + self.liq(k)


def range_from_levels(di, lv):
    """(a): R = nearest level above P0 + NEAR x ATR, S = nearest below P0 - NEAR x ATR; opening-range edge when none."""
    r = LV.nearest_above(lv, di.p0 + NEAR * di.atr)
    s = LV.nearest_below(lv, di.p0 - NEAR * di.atr)
    return (r if np.isfinite(r) else di.orh), (s if np.isfinite(s) else di.orl)


def clipped_range(di, lv):
    """(b): on a wide day, the range's high / low clipped to the nearest level inside it beyond P0 +- NEAR x ATR."""
    if not di.wide:
        return di.orh, di.orl
    hi = LV.nearest_above([v for v in lv if v < di.orh], di.p0 + NEAR * di.atr)
    lo = LV.nearest_below([v for v in lv if v > di.orl], di.p0 - NEAR * di.atr)
    return (hi if np.isfinite(hi) else di.orh), (lo if np.isfinite(lo) else di.orl)


def signal_fn(variant, di: DayIn, L: Levels):
    """The variant's signal on bar k (+1 call, -1 put, 0) and its caps (entries a day, entries a side)."""
    bc, bh, bl = di.bc, di.bh, di.bl
    if variant == "V0":
        return (lambda k: 1 if bc[k] > di.orh else -1 if bc[k] < di.orl else 0), 99, 1
    if variant.startswith("A-"):
        src = {"A-ALL": None, "A-PIV": "piv", "A-OI": "oi", "A-OIi": "oii"}[variant]
        lv = L.all(K0 - 1) if src is None else L.static(src)
        hi, lo = range_from_levels(di, lv)
        return (lambda k: 1 if bc[k] > hi else -1 if bc[k] < lo else 0), 99, 1
    if variant == "B":
        hi, lo = clipped_range(di, L.all(K0 - 1))
        return (lambda k: 1 if bc[k] > hi else -1 if bc[k] < lo else 0), 99, 1
    if variant == "C":
        def f(k):
            s = 1 if bc[k] > di.orh else -1 if bc[k] < di.orl else 0
            if not s:
                return 0
            lv = L.all(k)
            span = CONFIRM_SPAN * di.atr
            if s > 0:
                near = [v for v in lv if di.orh <= v <= di.orh + span]
                return 1 if not near or bc[k] > min(near) else 0
            near = [v for v in lv if di.orl - span <= v <= di.orl]
            return -1 if not near or bc[k] < max(near) else 0
        return f, 99, 1
    if variant == "D":
        if not di.wide:
            return signal_fn("V0", di, L)
        tol = TOUCH * di.atr

        def g(k):
            lv = [v for v in L.all(k) if di.orl <= v <= di.orh] + [di.orh, di.orl]
            pc = bc[k - 1]
            up = any(bl[k] <= v + tol and bc[k] > v for v in lv if v < pc)
            dn = any(bh[k] >= v - tol and bc[k] < v for v in lv if v > pc)
            return 0 if up == dn else 1 if up else -1
        return g, 2, 0
    raise ValueError(variant)


def run_day(variant, di: DayIn, rng=None):
    """The day's trades [(k, side, net, exit_row)] under the variant: R17's ORB loop (caps, resume after the exit's bar)."""
    f, cap, side_cap = signal_fn(variant, di, Levels(di, rng))
    out = []
    per = {1: 0, -1: 0}
    block = -1
    for k in range(K0, K1 + 1):
        if 5 * k <= block or len(out) >= cap:
            continue
        s = f(k)
        if not s or (side_cap and per[s] >= side_cap):
            continue
        o = di.table.get((k, s))
        if o is None:
            continue
        out.append((k, s, o[0], o[1]))
        per[s] += 1
        block = o[1] - (o[1] % 5)
    return out


# ------------------------------------------------------------------------------------------------ scores
def daily(variant, ds, rng=None):
    return [run_day(variant, di, rng) for di in ds]


def maxdd(x):
    c = np.cumsum(x)
    return float((np.maximum.accumulate(np.concatenate([[0.0], c]))[1:] - c).max()) if len(c) else 0.0


def score(trades_by_day, base_by_day=None):
    net = np.array([sum(t[2] for t in ts) for ts in trades_by_day])
    td = np.array([len(ts) > 0 for ts in trades_by_day])
    n = len(net)
    out = dict(sessions=n, trades=int(sum(len(ts) for ts in trades_by_day)), trade_days=int(td.sum()),
               total=round(float(net.sum())), per_session=round(float(net.mean()), 1) if n else 0.0,
               green_pct=round(100 * float((net[td] > 0).mean()), 1) if td.any() else 0.0,
               worst_day=round(float(net.min())) if n else 0, max_dd=round(maxdd(net)),
               blocks=[round(float(b.sum())) for b in np.array_split(net, 4)])
    if base_by_day is not None:
        bt = np.array([len(ts) > 0 for ts in base_by_day])
        new = td & ~bt
        lost = bt & ~td
        out.update(new_trade_days=int(new.sum()), new_days_net=round(float(net[new].sum())),
                   new_days_trades=int(sum(len(trades_by_day[i]) for i in np.nonzero(new)[0])),
                   dropped_trade_days=int(lost.sum()),
                   dropped_days_base_net=round(float(sum(sum(t[2] for t in base_by_day[i]) for i in np.nonzero(lost)[0]))))
    if n > 1 and net.std(ddof=1) > 0:
        t = float(net.mean() / (net.std(ddof=1) / math.sqrt(n)))
        from scipy import stats
        out.update(t=round(t, 2), p_t=float(stats.t.sf(t, n - 1)))
    else:
        out.update(t=None, p_t=1.0)
    return out


def twin_p(variant, ds, total, twins=TWINS, seed=SEED):
    rng = np.random.default_rng(seed + VARIANTS.index(variant))
    ge = 0
    tot = []
    for _ in range(twins):
        x = sum(t[2] for ts in daily(variant, ds, rng) for t in ts)
        tot.append(x)
        ge += x >= total - 1e-9
    return (1 + ge) / (twins + 1), float(np.mean(tot))


def describe(ds):
    n = len(ds)
    if not n:
        return {}
    src = {"piv": [], "oi": [], "oii": [], "liq@10:05": []}
    above, below = [], []
    for di in ds:
        src["piv"].append(len(di.piv)); src["oi"].append(len(di.oi)); src["oii"].append(len(di.oii))
        lv = Levels(di).all(K0 - 1)
        src["liq@10:05"].append(len(di.liq.edges(K0 - 1)))
        a = LV.nearest_above(lv, di.p0 + NEAR * di.atr)
        b = LV.nearest_below(lv, di.p0 - NEAR * di.atr)
        if np.isfinite(a):
            above.append((a - di.p0) / di.atr)
        if np.isfinite(b):
            below.append((di.p0 - b) / di.atr)
    return dict(sessions=n, levels_per_day={k: round(float(np.mean(v)), 2) for k, v in src.items()},
                days_without_oi=int(sum(1 for di in ds if not di.oi)), days_without_oii=int(sum(1 for di in ds if not di.oii)),
                nearest_above_atr_median=round(float(np.median(above)), 3) if above else None, n_above=len(above),
                nearest_below_atr_median=round(float(np.median(below)), 3) if below else None, n_below=len(below),
                wide_days=int(sum(di.wide for di in ds)), w45_atr_median=round(float(np.median([di.w45 / di.atr for di in ds])), 3))


def oct13_like(ds, res):
    """Wide days on which V0 took no trade: what each variant did there."""
    base = res["V0"]
    idx = [i for i, di in enumerate(ds) if di.wide and not base[i]]
    out = dict(days=len(idx))
    for v in VARIANTS:
        tr = [res[v][i] for i in idx]
        out[v] = dict(trade_days=sum(1 for t in tr if t), trades=sum(len(t) for t in tr), net=round(sum(x[2] for t in tr for x in t)))
    return out


def split(und, ds):
    cut = K17.holdout_start(und, [di.day for di in ds])
    return [di for di in ds if di.day < cut], [di for di in ds if di.day >= cut], str(cut)


def run_period(ds, twins):
    res = {v: daily(v, ds) for v in ["V0"] + VARIANTS}
    sc = {v: score(res[v], res["V0"] if v != "V0" else None) for v in res}
    if twins:
        for v in VARIANTS:
            p, m = twin_p(v, ds, sc[v]["total"], twins)
            sc[v].update(p_twin=p, twin_mean_total=round(m))
    return res, sc


def bh(pvals, q=0.05):
    """Benjamini-Hochberg: the set of indices rejected at q."""
    m = len(pvals)
    order = sorted(range(m), key=lambda i: pvals[i])
    k = 0
    for r, i in enumerate(order, 1):
        if pvals[i] <= q * r / m:
            k = r
    return set(order[:k])


def selector(design_sc, hold_sc):
    """Walk-forward pick (reported, not a gate): before each block, the best variant on all earlier design blocks."""
    names = ["V0"] + VARIANTS
    out = []
    for b in range(1, 4):
        pick = max(names, key=lambda v: sum(design_sc[v]["blocks"][:b]))
        out.append(dict(block=b + 1, pick=pick, net=design_sc[pick]["blocks"][b]))
    if hold_sc:
        pick = max(names, key=lambda v: design_sc[v]["total"])
        out.append(dict(block="holdout", pick=pick, net=hold_sc[pick]["total"]))
    return out


def main(cmd):
    os.makedirs(RESULTS, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    if cmd == "prep":
        for u in ("BANKNIFTY", "NIFTY"):
            if len(sys.argv) > 2 and sys.argv[2] == "tables":
                tables(u)
                continue
            ds = prep(u)
            print(u, len(ds), "days")
        return
    if cmd == "design":
        out = {}
        for u in ("BANKNIFTY", "NIFTY"):
            des, _, cut = split(u, prep(u))
            res, sc = run_period(des, twins=0)
            out[u] = dict(cut=cut, first=str(des[0].day), last=str(des[-1].day), scores=sc, describe=describe(des),
                          oct13_like=oct13_like(des, res), selector=selector(sc, None),
                          gates_design={v: dict(G1=sc[v]["total"] > 0 and sc[v]["total"] > sc["V0"]["total"],
                                                G2=sum(b > 0 for b in sc[v]["blocks"]) >= 3) for v in VARIANTS})
        json.dump(out, open(os.path.join(RESULTS, "design.json"), "w"), indent=1, default=str)
        print(json.dumps({u: {v: (o["scores"][v]["total"], o["scores"][v]["trades"]) for v in o["scores"]} for u, o in out.items()}))
        return
    if cmd == "holdout":
        if os.path.exists(FLAG):
            sys.exit("R20b holdout already read (" + FLAG + "): a second look needs a new pre-registration")
        if not os.path.exists(os.path.join(RESULTS, "design.json")):
            sys.exit("freeze results/design.json first")
        open(FLAG, "w").write("read once\n")
        design = json.load(open(os.path.join(RESULTS, "design.json")))
        out = {}
        for u in ("BANKNIFTY", "NIFTY"):
            _, hold, cut = split(u, prep(u))
            res, sc = run_period(hold, twins=TWINS if u == "BANKNIFTY" else 0)
            out[u] = dict(cut=cut, first=str(hold[0].day), last=str(hold[-1].day), scores=sc, describe=describe(hold),
                          oct13_like=oct13_like(hold, res), selector=selector(design[u]["scores"], sc))
        sc = out["BANKNIFTY"]["scores"]
        pv = [sc[v]["p_t"] for v in VARIANTS] + [sc[v]["p_twin"] for v in VARIANTS]
        own = bh(pv)
        cons = bh(pv + [1.0] * 14)
        gates = {}
        dsc = design["BANKNIFTY"]["scores"]
        for i, v in enumerate(VARIANTS):
            g = dict(G1=dsc[v]["total"] > 0 and dsc[v]["total"] > dsc["V0"]["total"],
                     G2=sum(b > 0 for b in dsc[v]["blocks"]) >= 3,
                     G3_raw=sc[v]["p_t"] < 0.05, G4_raw=sc[v]["p_twin"] < 0.05,
                     G3G4_bh_own=(i in own and i + 7 in own), G3G4_bh_combined_conservative=(i in cons and i + 7 in cons),
                     G5=sc[v]["total"] > sc["V0"]["total"])
            g["PASS"] = g["G1"] and g["G2"] and g["G3G4_bh_combined_conservative"] and g["G5"]
            gates[v] = g
        out["gates"] = gates
        out["pvalues"] = dict(order=[f"{v} G3" for v in VARIANTS] + [f"{v} G4" for v in VARIANTS], p=pv)
        json.dump(out, open(os.path.join(RESULTS, "holdout.json"), "w"), indent=1, default=str)
        print(json.dumps(gates, indent=1))
        return
    raise SystemExit(__doc__)


if __name__ == "__main__":
    import study as _self                         # pickle DayIn as study.DayIn, not __main__.DayIn
    _self.main(sys.argv[1] if len(sys.argv) > 1 else "")
