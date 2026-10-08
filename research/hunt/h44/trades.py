"""h44 stage 1 (heavy): Liquidity 15+5 trade table on 5 indices with h24's REAL flat half-spread, kappa .02/.04,
n = 1 and n = 2 lots. Reuses h23 build.py (prints-only data, h14 E2 limit entry, h10/M2 impact, app charges) and h24's
spread level. Rules / exits unchanged.

    OBUY_CACHE=<scratch>/hunt/h44/cache flock <scratch>/obuy.lock python3 -I research/hunt/h44/trades.py
-> <scratch>/hunt/h44/trades44.parquet
"""
from __future__ import annotations

import importlib.util
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = os.path.join(SCR, "hunt/h44")
HUNT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX")
HS = {"BANKNIFTY": 0.00162087147633297, "MIDCPNIFTY": 0.002133565702731482, "FINNIFTY": 0.0042,
      "NIFTY": 0.0016, "SENSEX": 0.0016}          # BN/MIDCP exactly h24 calib; FIN/NIFTY h24 snapshot; SENSEX assumed
KAPPAS = (0.02, 0.04)


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def simulate_n(B, b, lg, hs, kappa, bse, n):
    """h23 build.simulate with n lots requested (copied; only np.ones(N) -> n * np.ones(N))."""
    exe, C = B.exe, B.C
    exe.CO = B.CO_BSE if bse else B.CO_NSE
    try:
        N = lg.N
        ac = exe.Acc(N)
        agg = B.entry_e2(lg, ac, n * np.ones(N), "M2", kappa, hs)
        buy_raw_agg = np.where(ac.Q > 0, ac.buy_raw * np.where(ac.Q > 0, (ac.Q - ac.pas_buy) / np.maximum(ac.Q, 1e-9), 0), 0)
        exe.exit_(lg, ac, "X0", "M2", kappa)
    finally:
        exe.CO = B.CO_NSE
    r, c0, xc = lg.r, lg.c0, lg.xc
    W = C.W
    P0 = np.nan_to_num(lg.O[r, c0])
    liq_b = np.where(P0 > 0, (exe.FL.buy(P0, lg.V5[r, c0]) - P0) / np.maximum(P0, 1e-9), 0.0)
    Px = np.nan_to_num(lg.exit_raw)
    liq_s = np.where(Px > 0, (Px - exe.FL.sell(Px, lg.V5[r, np.minimum(xc, W - 1)])) / np.maximum(Px, 1e-9), 0.0)
    extra = np.maximum(0, hs - liq_b) * buy_raw_agg + np.maximum(0, hs - liq_s) * ac.sell_raw
    net = ac.sell_cash - ac.buy_cash - ac.chg - extra
    return pd.DataFrame(dict(net=net, gross=ac.sell_raw - ac.buy_raw, lots=ac.Q, prem=ac.buy_cash,
                             end=np.maximum(ac.end, xc) + C.OPEN_M))


def main():
    os.makedirs(OUT, exist_ok=True)
    B = _load("b23", os.path.join(HUNT, "h23", "build.py"))
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
    sig.to_parquet(os.path.join(OUT, "signals44.parquet"))
    mk.release()
    s, pk, _ = B.build(True, sig, UNDS, 0)
    tr = B.sim.base_trades(pk, B.cap.EXE, pos=False)
    tr["v5"] = B.v5_at_c0(pk, tr)
    tr = tr[tr.v5 >= 1].copy()
    tr = positions(tr, one_at_a_time=True).reset_index(drop=True)
    tr["hs"] = tr.und.map(HS).values
    rows = []
    for u in UNDS:
        fu = tr[tr.und == u]
        bk = B.cap.Book(pk, fu)
        lg = B.exe.leg_itm(bk)
        keep = [c for c in ("cand", "und", "book", "day", "side", "entry_min", "exit_min", "why", "lot", "entry", "exit",
                            "v5", "hs") if c in bk.tr]
        base = bk.tr[keep].copy()
        for kap in KAPPAS:
            for n in (1, 2):
                o = simulate_n(B, bk, lg, bk.tr.hs.values, kap, u in B.C.BSE, n)
                for c in ("net", "gross", "lots", "prem", "end"):
                    base[f"{c}{n}_{kap}"] = o[c].values
        rows.append(base)
        print(u, len(base), flush=True)
    T = pd.concat(rows, ignore_index=True)
    # attach the signal row (sig_min, levels) via cand = row of s
    sc = s.reset_index(drop=True)
    for c in ("sig_min", "ref_spot", "strike", "idx_stop", "idx_target"):
        T[c] = sc[c].values[T.cand.values]
    chk = (sc.und.values[T.cand.values] == T.und.values) & (pd.to_datetime(sc.day.values[T.cand.values]) == pd.to_datetime(T.day.values))
    print("cand->signal map ok:", chk.mean(), "entry-sig lag", (T.entry_min - T.sig_min).describe().to_dict(), flush=True)
    T.to_parquet(os.path.join(OUT, "trades44.parquet"))
    # validation vs h24 flat_x1 (BN, MIDCP, 1 lot)
    v = pd.read_parquet(os.path.join(SCR, "hunt/h24/trades24.parquet"))
    for kap in KAPPAS:
        vv = v[(v.model == "flat_x1") & (v.kappa == kap)]
        for u in ("BANKNIFTY", "MIDCPNIFTY"):
            print(f"VALIDATION {u} k{kap}: h44 net {T[T.und == u][f'net1_{kap}'].sum():,.0f} vs h24 "
                  f"{vv[vv.und == u].net.sum():,.0f}; n {int((T.und == u).sum())} vs {int((vv.und == u).sum())}", flush=True)
    print(T.groupby("und")[["net1_0.02", "net2_0.02", "net1_0.04"]].sum().round(0).to_string(), flush=True)


if __name__ == "__main__":
    main()
