"""x3: recompute one week of h24 Plan A (BANKNIFTY Liquidity, 1 lot, flat_x1 spread, kappa .02) trades by hand from the
raw Dhan parquet (prints only = volume-0 minutes dropped, as h23), and compare with trades24.parquet.

    python3 -I research/hunt/x3/a2_handweek.py 2026-01-05 2026-01-09
"""
import sys, os, glob, math
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd, pyarrow.parquet as pq
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
D = f"{S}/dhan/repo/dhan-data/options/BANKNIFTY"
lo, hi = pd.Timestamp(sys.argv[1]), pd.Timestamp(sys.argv[2])
HS, KAP, TICK = 0.0016, 0.02, 0.05

def r2(x):  # round half-even to paise like Px.adverse for whole-paise prices
    return float(np.round(x, 2))
def adverse(p, bps, buy):
    P = int(round(p * 100)); num = P * (10000 + bps if buy else 10000 - bps); q, r = divmod(num, 10000)
    up = r > 5000 or (r == 5000 and q % 2 == 1); return (q + up) / 100
def ticks(v5): return 1 if v5 >= 20 else (2 if v5 > 0 else 4)
def floor_tick(x): return round(math.floor(x / TICK + 1e-9) * TICK, 2)
def charge(buy, price, qty):
    v = price * qty; b, s = (v, 0) if buy else (0, v)
    txn = (b + s) * 0.0003553; sebi = (b + s) * 1e-6
    return round(20 + s * 0.0015 + txn + sebi + b * 0.00003 + (20 + txn + sebi) * 0.18, 2)

T = pd.read_parquet(f"{S}/hunt/h24/trades24.parquet"); T["day"] = pd.to_datetime(T.day)
T = T[(T.model == "flat_x1") & (T.kappa == KAP) & (T.und == "BANKNIFTY") & (T.day >= lo) & (T.day <= hi)]
sig = pd.read_parquet(f"{S}/hunt/h23/signals.parquet"); sig["day"] = pd.to_datetime(sig.day)
ser = "MONTH" if lo >= pd.Timestamp("2024-11-29") else "WEEK"
rows = []
for side in ("CALL", "PUT"):
    for y in (lo.year - 1, lo.year):
        f = f"{D}/{ser}/{side}/{y}.parquet"
        if os.path.exists(f):
            t = pq.read_table(f).to_pandas(); t["right"] = side[0]; rows.append(t)
A = pd.concat(rows); A["ts"] = A.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
A = A[(A.ts >= lo) & (A.ts < hi + pd.Timedelta(days=1))]
A["day"] = A.ts.dt.normalize(); A["m"] = A.ts.dt.hour * 60 + A.ts.dt.minute; A["k"] = A.strike.round().astype(int)
A = A.sort_values(["right", "k", "ts", "offset"]).drop_duplicates(["right", "k", "ts"])
out = []
for r in T.itertuples():
    s = sig[(sig.day == r.day) & (sig.side == r.side) & (sig.gate <= r.entry_min) & (sig.gate >= r.entry_min - 2)]
    k = int(s.strike.iloc[0]); right = "C" if r.side > 0 else "P"; lot = int(r.lot)
    c = A[(A.day == r.day) & (A.k == k) & (A.right == right)].set_index("m").sort_index()
    c = c[c.volume > 0]                                                  # prints only
    v = (c.volume / lot).reindex(range(555, 930), fill_value=0.0)
    v5 = lambda m: float(v.loc[m - 5:m - 1].sum())
    m0 = r.entry_min; P0 = float(np.round(c.open.loc[m0], 2))
    liqb = r2(adverse(P0, 5, True) + ticks(v5(m0)) * TICK)
    imp_b = min(0.10, KAP * math.sqrt(1 / max(v5(m0), 1)))
    L = floor_tick(P0 * 1.005); base = max(liqb, P0 * (1 + HS))
    qmax = math.floor(max(v5(m0), 1) * ((L / base - 1) / KAP) ** 2 + 1e-9) if L > base else 0
    if qmax >= 1:
        pb = liqb * (1 + imp_b); extra_b = max(0, HS - (liqb - P0) / P0) * P0 * lot; how = "agg"
    else:
        pb, extra_b, how = L, 0.0, "passive"   # (passive fill at L within 3 min, assumed found)
    xm = r.exit_min; xb = r.exit             # engine's exit fill (base)
    Px = float(np.round(c.open.loc[xm], 2)) if r.why != "stop" else min(floor_tick(r.entry * 0.85), float(c.open.get(xm, np.inf)))
    imp_s = min(0.10, KAP * math.sqrt(1 / max(v5(xm), 1)))
    ps = xb * (1 - imp_s)
    liqs = (Px - r2(max(adverse(Px, 5, False) - ticks(v5(xm)) * TICK, 0))) / Px
    extra_s = max(0, HS - liqs) * Px * lot
    chg = charge(True, pb, lot) + charge(False, ps, lot)
    net = (ps - pb) * lot - chg - extra_b - extra_s
    out.append(dict(day=r.day.date(), side=r.side, K=k, in_m=m0, out_m=xm, why=r.why, P0=P0, buy=round(pb, 2), how=how,
                    exit_raw=Px, sell=round(ps, 2), lot=lot, chg=round(chg, 2), spread=round(extra_b + extra_s, 2),
                    net_hand=round(net, 2), net_h24=round(r.net, 2), diff=round(net - r.net, 2)))
O = pd.DataFrame(out)
pd.set_option("display.width", 250)
print(O.to_string())
print("sum hand", O.net_hand.sum().round(2), "sum h24", O.net_h24.sum().round(2), "max |diff|", O["diff"].abs().max())
