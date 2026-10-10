"""h24 part 2 (POST-HOC SENSITIVITY, no rule changes): h17/h23's base plan (BANKNIFTY 1 lot + MIDCPNIFTY 1 lot) and
BANKNIFTY 1 lot alone, re-priced with spread models calibrated to the real 2026-10-06 option-chain snapshot.

    OBUY_CACHE=<scratch>/hunt/h24/cache flock <scratch>/obuy.lock python3 -I research/hunt/h24/sim.py build
    python3 -I research/hunt/h24/sim.py an

Reuses h23's build.py unchanged (prints-only data, h14 E2 limit +0.5% resting 3 min, h10/M2 impact, the "limit not
marketable if hs > 0.5%" rule, app charges) and h23's run.py walk (Rs 1 lakh, fixed 1 lot, free-cash check).
Only the half-spread hs per trade changes:
  h17       hs = 0 (h10 'liq' fill only: 5 bps + 1-4 ticks)                       [h17's model]
  h23roll   hs = previous month's Roll half-spread (h23 roll.csv)                   [h23's model]
  flat x f  hs = the snapshot's median 1-ITM/ATM nearest-monthly half-spread, constant, times f (1, 1.5, 2)
  rollk x f hs = h23roll x k_u x f, k_u = snapshot level / mean h23roll hs of the index's holdout trades
f = 1.5 / 2 are the "spreads widen at breakouts" stress (applied to every aggressive buy AND sell).
"""
from __future__ import annotations

import importlib.util
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = os.path.join(SCR, "hunt/h24")
H23 = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "h23")
UNDS = ("BANKNIFTY", "MIDCPNIFTY")
KAPPAS = (0.02, 0.04)
FACTORS = (1.0, 1.5, 2.0)
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def snap_level():
    s = pd.read_csv(os.path.join(OUT, "snap_summary.csv"))
    s = s[s.money == "1-ITM+ATM"].set_index("und")
    return {u: float(s.loc[u, "hs_med"]) / 100 for u in UNDS}


def build():
    B = _load("b23", os.path.join(H23, "build.py"))
    from obuy.engine import positions
    mk = B.market()
    for u in UNDS:
        ix = mk.index(u)
        print("index", u, ix.source, len(ix.days), ix.days[0], ix.days[-1], flush=True)
    a = pd.read_pickle(os.path.join(B.H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(B.H4, "sig_liq_ext_0.pkl"))
    sig = pd.concat([a, b], ignore_index=True)
    sig = sig[sig.und.isin(UNDS)].reset_index(drop=True)
    sig["day"] = pd.to_datetime(sig["day"]).dt.date
    print("signals", sig.groupby("und").size().to_dict(), flush=True)
    mk.release()
    s, pk, _ = B.build(True, sig, UNDS, 0)
    tr = B.sim.base_trades(pk, B.cap.EXE, pos=False)
    tr["v5"] = B.v5_at_c0(pk, tr)
    tr = tr[tr.v5 >= 1].copy()
    tr = positions(tr, one_at_a_time=True).reset_index(drop=True)
    roll = pd.read_csv(os.path.join(SCR, "hunt/h23/roll.csv"))
    tr["hs_h23roll"] = B.hs_for(tr, roll)
    lvl = snap_level()
    hold = pd.to_datetime(tr.day) >= "2025-10-01"
    k = {u: lvl[u] / tr.loc[hold & (tr.und == u), "hs_h23roll"].mean() for u in UNDS}
    print("snapshot level", lvl, "k_u", k, flush=True)
    models = {"h17": np.zeros(len(tr)), "h23roll": tr.hs_h23roll.values}
    for f in FACTORS:
        models[f"flat_x{f:g}"] = tr.und.map(lvl).values * f
        models[f"rollk_x{f:g}"] = tr.hs_h23roll.values * tr.und.map(k).values * f
    for m, v in models.items():
        tr[f"hs_{m}"] = v
    rows = []
    for u in UNDS:
        fu = tr[tr.und == u]
        bk = B.cap.Book(pk, fu)
        lg = B.exe.leg_itm(bk)
        base = bk.tr[["cand", "und", "book", "day", "side", "entry_min", "exit_min", "why", "lot", "entry", "exit", "v5"]].copy()
        for m in models:
            hs = bk.tr[f"hs_{m}"].values
            for kap in KAPPAS:
                o = B.simulate(bk, lg, hs, kap, False)
                x = base.copy()
                x["model"], x["kappa"], x["hs"] = m, kap, hs
                for c in ("net", "gross", "charges", "spread_extra", "lots", "prem", "end", "agg", "pas"):
                    x[c] = o[c].values
                rows.append(x)
            print(u, m, flush=True)
    T = pd.concat(rows, ignore_index=True)
    T.to_parquet(os.path.join(OUT, "trades24.parquet"))
    pd.DataFrame([dict(und=u, snap_level=lvl[u], k=k[u]) for u in UNDS]).to_csv(os.path.join(OUT, "calib.csv"), index=False)


def an():
    R = _load("r23", os.path.join(H23, "run.py"))
    T = pd.read_parquet(os.path.join(OUT, "trades24.parquet"))
    T["day"] = pd.to_datetime(T["day"])
    val = pd.read_parquet(os.path.join(SCR, "hunt/h23/trades.parquet"))
    val["day"] = pd.to_datetime(val.day)
    # validation: h23roll at kappa .02 must equal h23's trades.parquet; h17 must equal trades_hs0
    for m, f in (("h23roll", "trades.parquet"), ("h17", "trades_hs0.parquet")):
        v = pd.read_parquet(os.path.join(SCR, "hunt/h23", f))
        v = v[v.und.isin(UNDS)]
        t = T[(T.model == m) & (T.kappa == 0.02)]
        print(f"VALIDATION {m} vs h23 {f}: net {t.net.sum():,.0f} vs {v['net_0.02'].sum():,.0f}; "
              f"trades {len(t)} vs {len(v)}", flush=True)
    windows = ((R.START, R.HOLD, "pre 2021-10..2025-09"), (R.HOLD, R.END, "HOLDOUT 2025-10..2026-10"))
    rows, months = [], {}
    for m in T.model.unique():
        for kap in KAPPAS:
            t = T[(T.model == m) & (T.kappa == kap)].copy()
            for c in ("net", "gross", "lots", "prem", "end"):
                t[f"{c}_{kap}"] = t[c]
            for lo, hi, wn in windows:
                for book in (UNDS, ("BANKNIFTY",), ("MIDCPNIFTY",)):
                    s, dn = R.walk(t, book, kap, lo, hi)
                    w = t[t.und.isin(book) & (t.day >= lo) & (t.day < hi)]
                    filled = w[w.lots > 0]
                    rows.append(dict(model=m, kappa=kap, window=wn, book="+".join(x[:5] for x in book),
                                     signals=len(w), missed_fill=int((w.lots == 0).sum()),
                                     miss_pct=100 * float((w.lots == 0).mean()) if len(w) else np.nan,
                                     hs_mean_pct=100 * float(w.hs.mean()) if len(w) else np.nan,
                                     spread_extra_per_trade=float(filled.spread_extra.mean()) if len(filled) else np.nan,
                                     **{kk: vv for kk, vv in s.items() if kk not in ("book", "kappa")}))
                    if kap == 0.02 and wn.startswith("HOLD") and book == UNDS:
                        months[m] = dn.groupby(dn.index.to_period("M")).sum()
    D = pd.DataFrame(rows)
    D.to_csv(os.path.join(OUT, "results.csv"), index=False)
    cols = ["model", "kappa", "book", "signals", "missed_fill", "taken", "skipped_cash", "net_day", "gross_day", "t",
            "boot_p", "maxdd", "worst_day", "green_months", "end_cap", "min_cap", "hs_mean_pct", "spread_extra_per_trade"]
    for wn in (w[2] for w in windows):
        print(f"\n=== {wn} (POST-HOC sensitivity; Rs 1 L, fixed 1 lot, free-cash walk) ===")
        print(D[D.window == wn][cols].to_string(float_format=lambda x: f"{x:,.1f}"))
    M = pd.DataFrame(months).round(0)
    M.to_csv(os.path.join(OUT, "hold_months.csv"))
    print("\nHOLDOUT monthly net, BN1+M1, kappa .02\n", M.to_string())


if __name__ == "__main__":
    {"build": build, "an": an}[sys.argv[1]]()
