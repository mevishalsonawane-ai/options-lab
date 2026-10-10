"""M2 engine: trend/carry signals and fixed-lot futures backtest (Zerodha costs, margin model) on MCX daily."""
import numpy as np, pandas as pd
from prep import SPEC, D

DEV_END = pd.Timestamp("2024-12-31")
HOLD_START = pd.Timestamp("2025-01-01")
CAP0 = 100000.0
SIGNALS = ["TSM21", "TSM63", "TSM126", "TSM252", "DC20", "DC50", "DC100", "MA20_100", "MA50_200", "CARRY", "ENS"]
TREND9 = SIGNALS[:9]

def load(end=None):
    p = pd.read_parquet(f"{D}/panel.parquet")
    out = {}
    for name, g in p.groupby("name"):
        g = g.set_index("date").sort_index()
        if end is not None: g = g[g.index <= end]
        # adjusted (roll-free) point series: cumulative sum of earned points
        gap = (g.open - g.close.shift(1)).where(~g.roll, 0.0).fillna(0.0)
        intra = g.close - g.open
        g["adj"] = g.close.iloc[0] + (gap + intra).cumsum() - (g.close.iloc[0] - g.open.iloc[0])
        r = (gap + intra) / g.close.shift(1).abs()
        g["ret"] = r.fillna(0.0)
        g["vol20"] = g.ret.rolling(20).std()
        g["vol60"] = g.ret.rolling(60).std() * np.sqrt(252)
        out[name] = g
    return out

def month_end(idx):
    s = pd.Series(idx, index=idx)
    return (s.dt.month != s.shift(-1).dt.month).values

def signal(g, name):
    a = g.adj
    if name.startswith("TSM"):
        n = int(name[3:])
        raw = np.sign(a - a.shift(n))
        me = month_end(g.index)
        s = pd.Series(np.where(me, raw, np.nan), index=g.index).ffill().fillna(0.0)
        s[a.shift(n).isna()] = 0.0
        return s
    if name.startswith("DC"):
        n = int(name[2:])
        hi = a.shift(1).rolling(n).max(); lo = a.shift(1).rolling(n).min()
        s = pd.Series(np.where(a > hi, 1.0, np.where(a < lo, -1.0, np.nan)), index=g.index).ffill().fillna(0.0)
        return s
    if name.startswith("MA"):
        f, sl = map(int, name[2:].split("_"))
        mf, ms = a.rolling(f).mean(), a.rolling(sl).mean()
        s = pd.Series(np.where(mf > ms, 1.0, -1.0), index=g.index)
        s[ms.isna()] = 0.0
        return s
    if name == "CARRY":
        me = month_end(g.index)
        raw = np.sign(g.carry).fillna(0.0)
        return pd.Series(np.where(me, raw, np.nan), index=g.index).ffill().fillna(0.0)
    if name == "ENS":
        m = sum(signal(g, k) for k in TREND9) / 9.0
        return np.sign(m)
    raise KeyError(name)

def side_cost(price, n_lots, spec, buy):
    """Zerodha futures charges + half spread + slippage for one order of n_lots at price."""
    notional = abs(price) * spec["pv"] * n_lots
    brok = min(0.0003 * notional, 20.0)
    txn = 0.000021 * notional; sebi = 1e-6 * notional
    gst = 0.18 * (brok + txn + sebi)
    tax = 0.00002 * notional if buy else 0.0001 * notional
    return brok + txn + sebi + gst + tax + n_lots * spec["rt_spread"] / 2 + 0.0002 * notional

def margin_pct(g, spec):
    return np.maximum(spec["floor"], 0.60 * g.vol60.fillna(g.vol60.bfill()).values)

def run_futures(data, sigs, mode="LS", start=None, end=None, constrained=True):
    """sigs: dict name -> pd.Series target position at close (-1/0/1). Returns daily DataFrame (pnl, margin, trades)."""
    names = list(sigs)
    idx = sorted(set().union(*[data[n].index for n in names]))
    idx = pd.DatetimeIndex(idx)
    if start is not None: idx = idx[idx >= start]
    if end is not None: idx = idx[idx <= end]
    T = len(idx)
    cols = {}
    for n in names:
        g = data[n].reindex(idx)
        s = sigs[n].reindex(idx).ffill().fillna(0.0).values.copy()
        if mode == "LO": s = np.maximum(s, 0.0)
        cols[n] = dict(o=g.open.values, c=g.close.values, cl=g.close.ffill().values, roll=g.roll.fillna(False).values.astype(bool),
                       has=g.open.notna().values, s=s, mp=np.maximum(SPEC[n]["floor"], 0.60 * np.nan_to_num(g.vol60.ffill().values, nan=0.3)),
                       v20=np.nan_to_num(g.vol20.ffill().values, nan=0.02), spec=SPEC[n])
    eq = CAP0
    pos = {n: 0.0 for n in names}; blocked = {n: None for n in names}; force = set()
    pnl = np.zeros(T); costs = np.zeros(T); marg = np.zeros(T); ntr = np.zeros(T); held = np.zeros(T)
    stopped = False
    for t in range(T):
        day_pnl = 0.0; day_cost = 0.0
        for n in names:
            k = cols[n]; sp = k["spec"]; L = sp["lots"]; pv = sp["pv"] * L
            if not k["has"][t]:
                continue
            prev = pos[n]
            want = k["s"][t - 1] if t > 0 else 0.0
            if stopped: want = 0.0
            if blocked[n] is not None:
                if want == blocked[n] and want != 0: want = 0.0
                else: blocked[n] = None
            if n in force:
                blocked[n] = want if want != 0 else None; want = 0.0; force.discard(n)
            if constrained and prev == 0 and want != 0:
                pc = k["cl"][t - 1] if t > 0 else k["o"][t]
                if k["v20"][t - 1] * abs(pc) * pv > 0.04 * eq:
                    want = 0.0
                else:
                    cur = sum(abs(pos[m]) * abs(cols[m]["cl"][t - 1]) * cols[m]["spec"]["pv"] * cols[m]["spec"]["lots"] * cols[m]["mp"][t - 1] for m in names if m != n)
                    need = abs(pc) * pv * k["mp"][t - 1]
                    if cur + need > eq: want = 0.0
            o, c = k["o"][t], k["c"][t]
            cprev = k["cl"][t - 1] if t > 0 else o
            if not k["roll"][t]:
                day_pnl += prev * (o - cprev) * pv
            elif prev != 0:
                day_cost += side_cost(cprev, L, sp, True) + side_cost(cprev, L, sp, False)
            if want != prev:
                d = want - prev
                for _ in range(int(abs(d))):
                    day_cost += side_cost(o, L, sp, d > 0)
                if want != 0: ntr[t] += 1
            day_pnl += want * (c - o) * pv
            pos[n] = want
        pnl[t] = day_pnl - day_cost; costs[t] = day_cost
        eq += pnl[t]
        m = 0.0; big, bigm = None, 0.0
        for n in names:
            k = cols[n]
            if pos[n] != 0:
                mm = abs(k["cl"][t]) * k["spec"]["pv"] * k["spec"]["lots"] * k["mp"][t]
                m += mm
                if mm > bigm: big, bigm = n, mm
        marg[t] = m; held[t] = sum(abs(v) for v in pos.values())
        if constrained and m > eq and big is not None: force.add(big)
        if constrained and eq < 25000: stopped = True
    return pd.DataFrame(dict(pnl=pnl, cost=costs, margin=marg, trades=ntr, held=held), index=idx)

def stats(df, cap=CAP0):
    p = df.pnl
    if len(p) < 20: return {}
    eq = cap + p.cumsum()
    yrs = (p.index[-1] - p.index[0]).days / 365.25
    cagr = (eq.iloc[-1] / cap) ** (1 / yrs) - 1 if eq.iloc[-1] > 0 else -1.0
    dd = eq - eq.cummax(); ddp = (eq / eq.cummax() - 1)
    mon = p.groupby(p.index.to_period("M")).sum()
    sh = p.mean() / p.std() * np.sqrt(252) if p.std() > 0 else 0.0
    return dict(cagr=100 * cagr, rs_month=mon.mean(), maxdd=dd.min(), maxdd_pct=100 * ddp.min(), worst_month=mon.min(),
                green=100 * (mon > 0).mean(), sharpe=sh, trades_yr=df.trades.sum() / yrs, cost_yr=df.cost.sum() / yrs,
                avg_margin=df.margin[df.held > 0].mean() if (df.held > 0).any() else 0.0, final=eq.iloc[-1], worst_day=p.min())

def leg_arrays(g, n):
    sp = SPEC[n]; pv = sp["pv"] * sp["lots"]
    c = g.close.values; o = g.open.values; cp = np.r_[o[0], c[:-1]]
    gap = np.where(g.roll.values, 0.0, o - cp) * pv
    intra = (c - o) * pv
    notional = np.abs(o) * pv
    side = (np.minimum(0.0003 * notional, 20) * 1.18 + notional * (0.000021 + 1e-6) * 1.18 + notional * 0.00006
            + sp["lots"] * sp["rt_spread"] / 2 + 0.0002 * notional)
    rollc = np.where(g.roll.values, 2 * side, 0.0)
    return gap, intra, side, rollc

def vec_pnl(arrs, s):
    """s: target at close (numpy). e_t = s_{t-1}."""
    gap, intra, side, rollc = arrs
    e = np.r_[0.0, s[:-1]]; ep = np.r_[0.0, e[:-1]]
    return ep * gap + e * intra - np.abs(e - ep) * side - (ep != 0) * rollc

def runs_shuffle(s, rng, flip=True):
    """Permute runs of equal value; random sign for non-zero runs if flip."""
    s = np.asarray(s); b = np.r_[0, np.flatnonzero(np.diff(s)) + 1, len(s)]
    runs = [(s[b[i]], b[i + 1] - b[i]) for i in range(len(b) - 1)]
    order = rng.permutation(len(runs)); out = np.empty(len(s)); k = 0
    for j in order:
        v, L = runs[j]
        if flip and v != 0: v = rng.choice([-1.0, 1.0])
        out[k:k + L] = v; k += L
    return out
