"""X1 part 1b: is the forward paper record (1-8 Oct 2026) consistent with each arm's 5-year replay?
Uses h19's replay trades (app rules, app paper fills + SandboxCosts, 1 lot, Aug 2021 - 6 Oct 2026) and, for MIDCPNIFTY,
X1's own replay (replay.py).  For each arm: the distribution of the sum / win rate of N consecutive trades
(rolling windows, all history / holdout / last 6 months) and an iid bootstrap; and the app's Vetting rule
(ira-core Vetting.kt: >=15 trades; HELD_UP = net>0 & PF>=1.2 & worst run <= 0.6 x gains; FAILED = net<=0)
applied from random start points: how often does a long-run loser 'hold up' and a long-run winner 'fail'?
    python3 -I research/hunt/x1/luck.py  -> scratchpad/hunt/x1/luck.json + printed tables"""
import json, os, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
rng = np.random.default_rng(8)
HOLD = pd.Timestamp("2025-10-01")
SIX = pd.Timestamp("2026-04-06")

FWD = {  # Boss's diagnostics 8 Oct (forward paper tests, net after charges)
    "orb": (30, 5067.59, 0.60), "liquidity": (20, -6186.42, 0.30), "orb_sweep": (6, -4497.99, None),
    "range_fade": (6, -1256.14, None), "orb_fresh": (3, 2426.52, None)}


def judge(nets):
    nets = np.asarray(nets, float)
    if len(nets) < 15: return "T"
    net = nets.sum(); gain = nets[nets > 0].sum(); loss = -nets[nets < 0].sum()
    pf = gain / loss if loss > 0 else np.inf
    run = np.cumsum(nets); dd = np.max(np.maximum.accumulate(np.maximum(run, 0)) - run)
    if net <= 0: return "F"
    if pf >= 1.2 and dd <= 0.6 * gain: return "H"
    return "T"


def first_verdict(nets, start, cap=120):
    for n in range(15, min(cap, len(nets) - start) + 1):
        v = judge(nets[start:start + n])
        if v != "T": return v, n
    return "T", cap


def windows(x, n):
    c = np.concatenate([[0], np.cumsum(x)])
    return c[n:] - c[:-n]


def arm_table(name, t, n, fwd_sum, fwd_win):
    out = {}
    for lab, sel in (("all", t.day >= "2000"), ("pre", t.day < HOLD), ("holdout", t.day >= HOLD), ("last6m", t.day >= SIX)):
        x = t[sel].sort_values(["day", "entry_min"]).net.values
        if len(x) < n + 5: continue
        w = windows(x, n)
        wins = windows((x > 0).astype(float), n) / n
        boot = rng.choice(x, size=(20000, n)).sum(axis=1)
        o = dict(trades=len(x), per_trade=round(x.mean(), 1), sd_trade=round(x.std(), 1),
                 win_rate=round((x > 0).mean(), 3),
                 p5=round(np.percentile(w, 5)), p50=round(np.percentile(w, 50)), p95=round(np.percentile(w, 95)),
                 share_windows_ge_fwd=round(float((w >= fwd_sum).mean()), 4) if fwd_sum is not None and fwd_sum > 0 else None,
                 share_windows_le_fwd=round(float((w <= fwd_sum).mean()), 4) if fwd_sum is not None and fwd_sum <= 0 else None,
                 boot_ge_fwd=round(float((boot >= fwd_sum).mean()), 4) if fwd_sum is not None and fwd_sum > 0 else None,
                 boot_le_fwd=round(float((boot <= fwd_sum).mean()), 4) if fwd_sum is not None and fwd_sum <= 0 else None,
                 share_win_ge_fwd=round(float((wins >= fwd_win - 1e-9).mean()), 4) if fwd_win is not None and fwd_win > 0.4 else None,
                 share_win_le_fwd=round(float((wins <= fwd_win + 1e-9).mean()), 4) if fwd_win is not None and fwd_win <= 0.4 else None,
                 share_windows_green=round(float((w > 0).mean()), 3))
        # Vetting from random starts
        st = rng.integers(0, max(1, len(x) - 130), 3000)
        fv = [first_verdict(x, s) for s in st]
        o["vetting_first_HELD_UP"] = round(np.mean([v == "H" for v, _ in fv]), 3)
        o["vetting_first_FAILED"] = round(np.mean([v == "F" for v, _ in fv]), 3)
        o["vetting_at_n_HELD_UP"] = round(float(np.mean([judge(x[s:s + n]) == "H" for s in st])), 3)
        out[lab] = o
    return out


def main():
    t = pd.read_parquet(f"{SCR}/hunt/h19/trades.parquet")
    t["day"] = pd.to_datetime(t.day)

    res = {}
    for arm in ("orb", "orb_fresh", "orb_sweep", "range_fade"):
        n, s, w = FWD[arm]
        res[arm] = {N: arm_table(arm, t[t.arm == arm], N, s if N == n else None, w if N == n else None)
                    for N in sorted({n, 15, 30, 60})}
    liq = t[t.arm.isin(["liq_bn", "liq_fin"])]
    res["liq_bn"] = {N: arm_table("liq_bn", t[t.arm == "liq_bn"], N, -6186.42 if N == 20 else None, 0.30 if N == 20 else None) for N in (15, 20, 60)}
    res["liq_bn_fin"] = {N: arm_table("liq", liq, N, -6186.42 if N == 20 else None, 0.30 if N == 20 else None) for N in (15, 20, 60)}
    # 1-lot equivalent of the forward record: the 7 Oct 2-lot trade (-3,922) counted as one lot
    res["liq_bn_fin_1lot_equiv"] = {20: arm_table("liq", liq, 20, -6186.42 + 3922 / 2, 0.30)}
    json.dump(res, open(f"{SCR}/hunt/x1/luck.json", "w"), indent=1, default=float)
    for a, by in res.items():
        for N, per in by.items():
            for lab, o in per.items():
                print(a, N, lab, o)


if __name__ == "__main__":
    main()
