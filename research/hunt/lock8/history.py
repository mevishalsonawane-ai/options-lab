"""Task 3, history: an ACCOUNT-LEVEL day profit lock on many days, not one.

Book 1 (minute paths): h19's replay of the app's arms (ORB, ORB Fresh, ORB Sweep, Range Fade, Liquidity BN 15+5 and FIN 30+5,
1 lot each, app paper fills and charges, Aug 2021 - 6 Oct 2026). Open positions marked at the minute close, net of buy charges
(h19 analysis.day_paths); a forced close sells at the next minute's open less 5 bps with charges (h19 close_value).
Books: all six arms (what the phone runs), the four ORB arms, and Liquidity alone (the only arm with a positive record).
Book 2 (realised only, no paths): h24's Liquidity table trades24.parquet (BANKNIFTY + MIDCPNIFTY, model flat_x1, kappa 0.02,
the h36 Plan B trades): rules on the realised day P&L in exit order (a trade entered after the trigger is skipped).

Rules (several settings, none tuned on this data; today's numbers were not used to choose them):
  entries X      : no new entries once the day's P&L (realised + open) >= +X
  trail X y      : once the day's best >= +X, close everything and stop when the day falls to best x (1 - y)
  floor X        : once the day's best >= +X, close everything and stop when the day falls to +X/2
Placebo (h19's method): the same action at the rule's own trigger minutes on randomly chosen days, 200 draws; p = share of
draws that saved at least as much (a small p means the rule's timing carries information).
Run: python3 -I history.py   (reads <scratch>/hunt/h19/{trades.parquet,paths.pkl}, <scratch>/hunt/h24/trades24.parquet)"""
from __future__ import annotations
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RES); sys.path.insert(0, os.path.join(RES, "hunt", "h19"))
import numpy as np
import pandas as pd
import obuy  # noqa
from obuy import config as C
import analysis as H19

HOLD = pd.Timestamp("2025-10-01")
RNG = np.random.default_rng(808)
BOOKS = {"all six arms": None, "ORB arms only": ["orb", "orb_fresh", "orb_sweep", "range_fade"], "Liquidity only (BN+FIN)": ["liq_bn", "liq_fin"]}
RULES = [("entries", X, None) for X in (3000, 5000, 8000)] + \
        [("trail", X, y) for X in (3000, 5000, 8000) for y in (0.1, 0.2, 0.3, 0.5)] + [("floor", X, None) for X in (3000, 5000, 8000)]


def trigger(v, rule):
    kind, X, y = rule
    if kind == "entries":
        h = np.nonzero(v >= X)[0]; return (int(h[0]), "stop") if len(h) else (None, None)
    pk = np.maximum.accumulate(v)
    fl = pk * (1 - y) if kind == "trail" else np.full(len(v), X / 2)
    h = np.nonzero((pk >= X) & (v <= fl))[0]
    return (int(h[0]), "flat") if len(h) else (None, None)


def apply(items, T, action):
    tot = 0.0
    for it in items:
        i0, i1 = it[0], it[1]
        if i0 > T: continue
        tot += it[5] if (action == "stop" or i1 <= T) else H19.close_value(it, T)
    return tot


def run_book(DP, days):
    base = pd.Series({d: (sum(it[5] for it in DP[d][0]) if d in DP else 0.0) for d in days})
    rows = []
    pool = [d for d in days if d in DP]
    for rule in RULES:
        res, fired = {}, {}
        for d in days:
            if d not in DP: res[d] = 0.0; continue
            T, act = trigger(DP[d][1], rule)
            res[d] = base[d] if T is None else apply(DP[d][0], T, act)
            if T is not None: fired[d] = (T, act)
        s = pd.Series(res)
        for per, sel in (("pre", s.index < HOLD), ("holdout", s.index >= HOLD)):
            b, r = base[sel], s[sel]
            fd = [d for d in fired if (d < HOLD) == (per == "pre")]
            ppool = [d for d in pool if (d < HOLD) == (per == "pre")]
            delta = float((r - b).sum())
            pl = []
            for _ in range(200):
                if not fd: break
                pick = RNG.choice(len(ppool), size=min(len(fd), len(ppool)), replace=False)
                Ts = RNG.permutation([fired[d] for d in fd])
                pl.append(sum(apply(DP[ppool[j]][0], int(T), act) - base[ppool[j]] for j, (T, act) in zip(pick, Ts)))
            pl = np.array(pl)
            big = b.nlargest(max(1, len(b) // 20)).index         # the best 5% of days
            rows.append(dict(rule=f"{rule[0]} X={rule[1]}" + (f" y={rule[2]}" if rule[2] else ""), period=per, days=len(b), fired=len(fd),
                             base_rs_day=b.mean(), rule_rs_day=r.mean(), delta_rs_day=(r - b).mean(), delta_total=delta,
                             placebo_mean=pl.mean() if len(pl) else np.nan,
                             p_placebo=float((1 + (pl >= delta).sum()) / (len(pl) + 1)) if len(pl) else np.nan,
                             best5pct_days_change=float((r[big] - b[big]).sum()),
                             p_loss_day_base=(b < 0).mean(), p_loss_day=(r < 0).mean()))
    return pd.DataFrame(rows)


def realised_liq():
    T = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h24/trades24.parquet"))
    T = T[(T.model == "flat_x1") & (T.kappa == 0.02) & (T.lots > 0)].copy()
    T["day"] = pd.to_datetime(T.day)
    days = sorted(T.day.unique())
    out = []
    for rule in [("entries", X, None) for X in (3000, 5000, 8000)] + [("trail", X, y) for X in (3000, 5000, 8000) for y in (0.2, 0.3, 0.5)]:
        res, base = {}, {}
        for d, g in T.groupby("day"):
            g = g.sort_values("exit_min")
            base[d] = g.net.sum()
            tot, pk, stop_at = 0.0, -1e18, None
            for t in g.itertuples():
                if stop_at is not None and t.entry_min > stop_at: continue
                tot += t.net; pk = max(pk, tot)
                kind, X, y = rule
                if stop_at is None and ((kind == "entries" and tot >= X) or (kind == "trail" and pk >= X and tot <= pk * (1 - y))):
                    stop_at = t.exit_min
            res[d] = tot
        b, r = pd.Series(base), pd.Series(res)
        for per, sel in (("pre", b.index < HOLD), ("holdout", b.index >= HOLD)):
            out.append(dict(rule=f"{rule[0]} X={rule[1]}" + (f" y={rule[2]}" if rule[2] else ""), period=per, trade_days=int(sel.sum()),
                            changed_days=int((r[sel] != b[sel]).sum()), base_total=b[sel].sum(), rule_total=r[sel].sum(),
                            delta_total=(r[sel] - b[sel]).sum()))
    return pd.DataFrame(out)


def main():
    tr, paths = H19.load()
    days = sorted(tr.day.unique())
    allres = []
    for name, arms in BOOKS.items():
        t = tr if arms is None else tr[tr.arm.isin(arms)]
        DP = H19.day_paths(t, paths)
        R = run_book(DP, days); R.insert(0, "book", name); allres.append(R)
        print("\n==", name); print(R.round(3).to_string(index=False), flush=True)
    R = pd.concat(allres); R.to_csv(os.path.join(HERE, "data", "history_paths.csv"), index=False)
    L = realised_liq(); L.to_csv(os.path.join(HERE, "data", "history_liq_realised.csv"), index=False)
    print("\n== Liquidity BN+MIDCP (h24/h36 table), realised-only rules"); print(L.round(0).to_string(index=False))

if __name__ == "__main__":
    main()
