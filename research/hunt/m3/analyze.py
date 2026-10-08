"""M3 analysis. usage: python3 analyze.py design | holdout   (holdout refuses a second run)
Reads work/trades_*.parquet, rand_*.parquet, spreads.json; writes research/hunt/m3/results/*.csv and logs."""
import sys, json, glob
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M

MODE = sys.argv[1]
RECOST = "--recost" in sys.argv  # re-price an already-opened holdout with new spreads (costs only; no rule choice)
sys.argv = [a for a in sys.argv if a != "--recost"]
RES = M.Path("/home/user/options-lab/research/hunt/m3/results"); RES.mkdir(exist_ok=True)
marker = M.WORK / "HOLDOUT_OPENED"
if MODE == "holdout" and not RECOST:
    if marker.exists():
        sys.exit("holdout already opened: " + marker.read_text())
    marker.write_text(pd.Timestamp.now(tz="UTC").isoformat())
SPREAD_X = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0

SP = json.loads((M.BASE_WORK / "spreads.json").read_text())  # {sym: {"am": rel full spread, "pm": ...}}, fut: {sym: Rs}


def rel_spread(sym, tod):
    s = SP["opt"].get(sym) or SP["opt"]["_default"]
    return SPREAD_X * (s["am"] if tod < 17 * 60 else s["pm"])


def fut_spread_rs(sym, price):
    v = SP["fut"].get(sym)
    if v is None:
        v = max(M.TICK[sym], 0.0001 * price)
    return SPREAD_X * v


def costs(df):
    """Adds gross, net (Rs per lot) to a trades/randoms frame with columns sym, inst, side, fill, exit, tod."""
    mult = df.sym.map(M.MULT).values.astype(float)
    opt = (df.inst == "OPT").values
    buy = df.fill.values * mult; sell = df.exit.values * mult
    g_opt = sell - buy
    g_fut = df.side.values * (df.exit.values - df.fill.values) * mult
    gross = np.where(opt, g_opt, g_fut)
    am = df.sym.map(lambda s: (SP["opt"].get(s) or SP["opt"]["_default"])["am"]).values
    pm = df.sym.map(lambda s: (SP["opt"].get(s) or SP["opt"]["_default"])["pm"]).values
    rs = SPREAD_X * np.where(df.tod.values < 17 * 60, am, pm)
    c_opt = M.opt_charges(buy, sell) + rs / 2 * (buy + sell)
    fb, fs = np.where(df.side.values > 0, buy, sell), np.where(df.side.values > 0, sell, buy)
    fv = df.sym.map(lambda s: SP["fut"].get(s, np.nan)).values.astype(float)
    tick = df.sym.map(M.TICK).values.astype(float)
    fsp = SPREAD_X * np.where(np.isfinite(fv), fv, np.maximum(tick, 0.0001 * df.fill.values)) * mult
    c_fut = M.fut_charges(fb, fs) + fsp
    df = df.copy()
    df["gross"] = gross
    df["cost"] = np.where(opt, c_opt, c_fut)
    df["net"] = gross - df["cost"]
    df["prem_rs"] = np.where(opt, buy, np.nan)
    return df


T = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(str(M.WORK / "trades_*.parquet")))], ignore_index=True)
T["j"] = T.groupby(["und", "inst", "rule"]).cumcount()
R = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(str(M.WORK / "rand_*.parquet")))], ignore_index=True)
T = costs(T); R = costs(R)
T["aff"] = (T.inst == "FUT") | (T.prem_rs <= 1e5)
R["aff"] = (R.inst == "FUT") | (R.prem_rs <= 1e5)
if MODE == "design":
    T = T[T.day <= M.DESIGN_END]
else:
    T = T[T.day >= M.HOLD_START]
T = T[T.aff]  # headline: affordable at Rs 1 lakh (futures: margin noted separately)

# sessions per underlying in the period (from the minute data's day list in trades/random)
sess = {}
for und in T.und.unique():
    d = pd.concat([pd.read_parquet(M.WORK / f"rand_{und}.parquet", columns=["day"]).day,
                   T[T.und == und].day]).drop_duplicates()
    d = d[(d <= M.DESIGN_END)] if MODE == "design" else d[d >= M.HOLD_START]
    sess[und] = d.nunique()


def maxdd(x):
    c = np.cumsum(x); return float((np.maximum.accumulate(np.r_[0, c])[1:] - c).max()) if len(x) else 0.0


rows, daily = [], {}
for (und, sym, inst, rule), g in T.groupby(["und", "sym", "inst", "rule"]):
    g = g.sort_values("e")
    n = len(g); nd = sess[und]
    mon = g.groupby(g.day.dt.to_period("M")).net.sum()
    rr = R[(R.und == und) & (R.inst == inst) & (R.rule == rule) & R.j.isin(g.j) & R.aff]
    rm = rr.groupby("r").net.mean()
    p = (1 + (rm >= g.net.mean()).sum()) / (1 + len(rm)) if len(rm) else np.nan
    rows.append(dict(und=und, sym=sym, inst=inst, rule=rule, trades=n, per_day=n / nd, win=(g.net > 0).mean(),
                     gross_tr=g.gross.mean(), net_tr=g.net.mean(), cost_tr=g.cost.mean(), gross_day=g.gross.sum() / nd,
                     net_day=g.net.sum() / nd, net_total=g.net.sum(), maxdd=maxdd(g.net.values),
                     green=f"{(mon > 0).sum()}/{len(mon)}", green_frac=(mon > 0).mean(), rand_net_tr=rm.mean(),
                     rand_gross_tr=rr.groupby("r").gross.mean().mean(), p_rand=p,
                     med_prem=g.prem_rs.median(), worst_trade=g.net.min(), hold_med=g.hold.median()))
    daily[(und, inst, rule)] = g.groupby("day").net.sum()
S_ = pd.DataFrame(rows)

# BH across all cells
ps = S_.p_rand.fillna(1).values; o = np.argsort(ps); m_ = len(ps)
q = np.empty(m_); q[o] = np.minimum.accumulate((ps[o] * m_ / np.arange(1, m_ + 1))[::-1])[::-1]
S_["bh_q"] = np.minimum(q, 1)

# Hansen SPA (studentized, stationary bootstrap, mean block 5) on daily net, cells with >= 30 trades
def spa(cols, B=2000, blk=5, seed=7):
    X = pd.concat(cols, axis=1).fillna(0.0).values
    n, k = X.shape
    mu = X.mean(0); sd = X.std(0, ddof=1) + 1e-9
    t_obs = (np.sqrt(n) * mu / sd).max()
    rng = np.random.default_rng(seed)
    thr = sd / np.sqrt(n) * np.sqrt(2 * np.log(np.log(n)))
    mu_c = np.where(mu >= -thr, mu, 0.0)
    cnt = 0
    for _ in range(B):
        idx = np.empty(n, dtype=int); i = rng.integers(n)
        for t in range(n):
            idx[t] = i
            i = rng.integers(n) if rng.random() < 1 / blk else (i + 1) % n
        Xb = X[idx]
        tb = (np.sqrt(n) * (Xb.mean(0) - mu_c) / sd).max()
        cnt += tb >= t_obs
    return t_obs, (1 + cnt) / (1 + B)


big = S_[S_.trades >= 30]
alld = pd.date_range(min(T.day), max(T.day), freq="B")
cols = [daily[(r.und, r.inst, r.rule)].reindex(alld).fillna(0) for r in big.itertuples()]
spa_t, spa_p = spa(cols) if cols else (np.nan, np.nan)
S_["candidate"] = (S_.net_tr > 0) & (S_.p_rand < 0.05) & (S_.bh_q < 0.10) & (S_.green_frac >= 0.6) & (S_.trades >= 30)
tag = f"{MODE}" + ("" if SPREAD_X == 1 else f"_x{SPREAD_X:g}") + ("" if M.PRINTS else "_allrows") + ("_recost" if RECOST else "")
S_.to_csv(RES / f"cells_{tag}.csv", index=False)
mon = T.groupby(["und", "inst", "rule", T.day.dt.to_period("M")]).agg(trades=("net", "size"), gross=("gross", "sum"),
                                                                       net=("net", "sum")).reset_index()
mon.to_csv(RES / f"months_{tag}.csv", index=False)
T.to_csv(RES / f"trades_{tag}.csv.gz", index=False)
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 500)
show = ["und", "inst", "rule", "trades", "per_day", "win", "gross_tr", "net_tr", "net_day", "maxdd", "green", "rand_net_tr",
        "p_rand", "bh_q", "med_prem", "candidate"]
print(f"MODE {tag}  cells {len(S_)}  SPA over {len(cols)} cells: t {spa_t:.2f} p {spa_p:.3f}")
print(S_[show].round(2).sort_values(["und", "inst", "rule"]).to_string(index=False))
# exit reasons
print(T.groupby(["rule", "why"]).size().unstack(fill_value=0).to_string())
# by entry hour, options, all rules: rule gross/net vs random gross/net per trade
T["h"] = T.tod // 60
R2 = R[(R.day <= M.DESIGN_END) if MODE == "design" else (R.day >= M.HOLD_START)]
R2 = R2[R2.aff]; R2["h"] = R2.tod // 60
hrs = pd.concat([T.groupby(["und", "inst", "h"]).net.agg(["size", "mean"]).rename(columns={"size": "n_rule", "mean": "net_rule"}),
                 R2.groupby(["und", "inst", "h"]).gross.mean().rename("gross_rand"),
                 R2.groupby(["und", "inst", "h"]).net.mean().rename("net_rand")], axis=1).reset_index()
hrs.to_csv(RES / f"hours_trades_{tag}.csv", index=False)
json.dump(dict(spa_t=spa_t, spa_p=spa_p, ncells=len(S_), spa_cells=len(cols)), open(RES / f"spa_{tag}.json", "w"))
