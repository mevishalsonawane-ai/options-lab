"""X1 part 2: today's ideas checked on the arms' history (h19 replay trades, app fills + SandboxCosts, 1 lot,
Aug 2021 - 6 Oct 2026; pre = before 1 Oct 2025, hold = from 1 Oct 2025).
 A  Liquidity stacking: the replay lets the 5m and 15m books (or a 2nd 5m signal) hold the same index at once; the app's
    AutoExposure guard allows one automatic position per index. Net with one-at-a-time per index (first come).
 B  Jarvis's 'lessons': Liquidity entries before 10:00; ORB entries 13:00-14:00 - by period.
 C  Jarvis's request: pause Liquidity after a bad stretch (last N trades net <= -X) for K sessions.
 D  ORB targets: where the app's after-the-cross market sell lands vs the +40 level (the minute close of the target minute).
    python3 -I research/hunt/x1/history.py"""
import pickle, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
HOLD = pd.Timestamp("2025-10-01")
rng = np.random.default_rng(81)


def per(x, lab=""):
    d = x.groupby("day").net.sum()
    return f"{lab} n={len(x)} net={x.net.sum():+,.0f} per_trade={x.net.mean():+.0f}"


def main():
    t = pd.read_parquet(f"{SCR}/hunt/h19/trades.parquet"); t["day"] = pd.to_datetime(t.day)
    sess = pd.Index(sorted(t.day.unique()))
    nd = {"pre": (sess < HOLD).sum(), "hold": (sess >= HOLD).sum()}
    liq = t[t.arm.isin(["liq_bn", "liq_fin"])].sort_values(["day", "entry_min"]).copy()
    print("== A stacking (one automatic position per index at a time, first come) ==")
    for u, g in liq.groupby("und"):
        keep = []
        for d, gd in g.groupby("day"):
            busy = -1
            for r in gd.sort_values(["entry_min", "book"]).itertuples():
                if r.entry_min >= busy:
                    keep.append(r.Index); busy = r.exit_min
                # else: blocked (index already held)
        k = g.loc[keep]
        for lab, sel_g, sel_k in (("pre", g.day < HOLD, k.day < HOLD), ("hold", g.day >= HOLD, k.day >= HOLD)):
            print(f"  {u} {lab}: replay {len(g[sel_g])} trades {g[sel_g].net.sum():+,.0f} ({g[sel_g].net.sum()/nd[lab]:+.0f}/day) | "
                  f"guarded {len(k[sel_k])} trades {k[sel_k].net.sum():+,.0f} ({k[sel_k].net.sum()/nd[lab]:+.0f}/day) | "
                  f"blocked {len(g[sel_g]) - len(k[sel_k])} worth {g[sel_g].net.sum() - k[sel_k].net.sum():+,.0f}")
    guarded = liq.loc[[i for u, g in liq.groupby("und") for i in g.index if True]]
    print("== B lessons ==")
    for lab, sel in (("pre", liq.day < HOLD), ("hold", liq.day >= HOLD)):
        x = liq[sel]
        e = x[x.entry_min < 600]; l = x[x.entry_min >= 600]
        print(f"  Liquidity {lab}: entries <10:00 {per(e)} | >=10:00 {per(l)}")
    orb = t[t.arm == "orb"]
    for lab, sel in (("pre", orb.day < HOLD), ("hold", orb.day >= HOLD)):
        x = orb[sel]
        a = x[(x.entry_min >= 780) & (x.entry_min < 840)]; b = x[(x.entry_min < 780) | (x.entry_min >= 840)]
        print(f"  ORB {lab}: entries 13:00-14:00 {per(a)} | other {per(b)}")
    print("== C pause Liquidity after a bad stretch ==")
    base = {lab: liq[(liq.day < HOLD) if lab == "pre" else (liq.day >= HOLD)].net.sum() for lab in nd}
    rows = []
    for N in (10, 15, 20):
        for X in (3000, 5000, 7500):
            for K in (5, 10, 20):
                kept = []; pause_until = None; hist = []
                for d, gd in liq.groupby("day"):
                    if pause_until is not None and d <= pause_until:
                        for v in gd.net.values: hist.append(v)   # the arm keeps trading on paper: the shadow record still updates
                        continue
                    pause_until = None
                    for r in gd.itertuples():
                        kept.append(r.Index); hist.append(r.net)
                    if len(hist) >= N and sum(hist[-N:]) <= -X:
                        i = sess.get_loc(d); pause_until = sess[min(i + K, len(sess) - 1)]
                k = liq.loc[kept]
                res = {lab: k[(k.day < HOLD) if lab == "pre" else (k.day >= HOLD)].net.sum() - base[lab] for lab in nd}
                fired = len(liq) - len(k)
                rows.append((N, X, K, fired, res["pre"] / nd["pre"], res["hold"] / nd["hold"]))
    df = pd.DataFrame(rows, columns=["N", "X", "K", "trades_skipped", "pre_Rs_day", "hold_Rs_day"])
    print(df.round(1).to_string(index=False))
    # placebo for the best pre rule: skip the same number of random trades in blocks
    print("  base Liquidity BN+FIN Rs/day: pre %+.0f hold %+.0f" % (base["pre"] / nd["pre"], base["hold"] / nd["hold"]))
    print("== D ORB target fills ==")
    paths = pickle.load(open(f"{SCR}/hunt/h19/paths.pkl", "rb"))
    tg = t[(t.arm.isin(["orb", "orb_fresh", "range_fade"])) & (t.why == "target")]
    over = []
    for r in tg.itertuples():
        c = paths[r.tid][0]
        if len(c):
            over.append(c[-1] - (r.entry + 40.0))
    over = np.array(over)
    print(f"  {len(over)} target exits: target-minute close minus the +40 level: mean {np.nanmean(over):+.2f} pts, median {np.nanmedian(over):+.2f},"
          f" share above {np.nanmean(over > 0):.2f}; Rs/trade at 30 units {30*np.nanmean(over):+.0f}")
    st = t[(t.arm == "orb") & (t.why == "stop")]
    under = []
    for r in st.itertuples():
        c = paths[r.tid][0]
        if len(c): under.append(c[-1] - (r.entry - 40.0))
    under = np.array(under)
    print(f"  {len(under)} stop exits: stop-minute close minus the -40 level: mean {np.nanmean(under):+.2f} pts (a market sell at the close)")


if __name__ == "__main__":
    main()
