"""h47 in-sample analysis: BH, SPA, walk-forward, primary in detail, best days, survivors. Writes an.json + CSVs."""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib import DAY0, IS_END, SCR, np, pd  # noqa: E402
from sim import NDAY, OPT0  # noqa: E402
import detail as D  # noqa: E402

PRIMARY = dict(setup="A", anchor="U", N=3, slope=0, vol=0, hold=0, hours=0, skip=0, win=0, k=0.0)


def bh(p):
    p = np.asarray(p, float)
    p = np.where(np.isfinite(p), p, 1.0)
    o = np.argsort(p)
    q = p[o] * len(p) / np.arange(1, len(p) + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty_like(q)
    out[o] = np.minimum(q, 1)
    return out


def stat_boot_idx(n, B, block, rng):
    """Stationary bootstrap indices (B, n)."""
    p = 1.0 / block
    idx = np.empty((B, n), np.int64)
    idx[:, 0] = rng.integers(0, n, B)
    new = rng.random((B, n)) < p
    rnd = rng.integers(0, n, (B, n))
    for t in range(1, n):
        idx[:, t] = np.where(new[:, t], rnd[:, t], (idx[:, t - 1] + 1) % n)
    return idx


def spa(X, B=500, block=5, seed=1):
    """Hansen SPA (consistent), benchmark 0. X: (k, n) daily P&L. Returns p-value and the best t."""
    k, n = X.shape
    rng = np.random.default_rng(seed)
    idx = stat_boot_idx(n, B, block, rng)
    m = X.mean(1)
    bm = np.empty((B, k), np.float32)
    for b in range(B):
        bm[b] = X[:, idx[b]].mean(1)
    om = np.sqrt(n) * bm.std(0)
    om = np.where(om > 0, om, np.inf)
    tstat = max(float(np.max(np.sqrt(n) * m / om)), 0.0)
    A = om / np.sqrt(n) * np.sqrt(2 * np.log(np.log(n)))
    mu_c = np.where(m <= -A, m, 0.0)          # clearly-bad models keep their negative mean (SPA_c)
    boot = np.maximum(np.max(np.sqrt(n) * (bm - m + mu_c) / om, axis=1), 0.0)
    return float((boot >= tstat).mean()), float(tstat)


def main():
    S = pd.read_csv(os.path.join(SCR, "is_summary.csv"))
    X = np.load(os.path.join(SCR, "is_daily.npy"))
    S["q_bh"] = bh(S.p_rand.values)
    out = {"n_variants": int(len(S))}
    # SPA per instrument
    o0 = (OPT0 - DAY0) // 1440
    spa_res = {}
    for inst in ("FT", "FM", "O"):
        m = (S.inst == inst).values
        Xi = X[m][:, o0:] if inst == "O" else X[m]
        p, t = spa(Xi.astype(np.float64))
        spa_res[inst] = dict(p=p, t=t, k=int(m.sum()))
        print("SPA", inst, spa_res[inst], flush=True)
    out["spa"] = spa_res
    # counts
    cnt = {}
    for inst, g in S.groupby("inst"):
        cnt[inst] = dict(n=len(g), gross_pos=int((g.gross_day > 0).sum()), net_pos=int((g.net_day > 0).sum()),
                         vda_pos=int((g.vda_day > 0).sum()), bus_pos=int((g.bus_day > 0).sum()),
                         beat_rand_p05=int((g.p_rand < 0.05).sum()), q10=int((g.q_bh < 0.10).sum()),
                         best_net_day=float(g.net_day.max()), median_net_day=float(g.net_day.median()),
                         best_gross_tr=float(g.gross_tr.max()), median_gross_tr=float(g.gross_tr.median()))
    out["counts"] = cnt
    print(json.dumps(cnt, indent=1), flush=True)
    # survivors
    surv = S[(S.net_day > 0) & (S.q_bh < 0.10) & S.inst.map(lambda i: spa_res[i]["p"] < 0.10)]
    out["survivors"] = surv.id.tolist()
    out["survivor_rows"] = surv[["id", "inst"]].values.tolist()
    # walk-forward by year, per coin and instrument
    days = pd.to_datetime(np.arange(DAY0 // 1440, DAY0 // 1440 + NDAY), unit="D")
    wf = []
    for (coin, inst), g in S.groupby(["coin", "inst"]):
        Xi = X[g.index.values]
        for y in range(2021, 2026):
            if inst == "O" and y < 2022:
                continue
            start = pd.Timestamp("2021-04-01") if inst == "O" else pd.Timestamp("2020-01-01")
            tr = (days >= start) & (days < pd.Timestamp(f"{y}-01-01"))
            te = (days >= pd.Timestamp(f"{y}-01-01")) & (days < pd.Timestamp(f"{y + 1}-01-01"))
            j = int(np.argmax(Xi[:, tr].mean(1)))
            wf.append(dict(coin=coin, inst=inst, year=y, pick=g.id.values[j], train_day=float(Xi[j, tr].mean()),
                           test_day=float(Xi[j, te].mean()), test_sum=float(Xi[j, te].sum()),
                           test_median_variant_day=float(np.median(Xi[:, te].mean(1)))))
    wf = pd.DataFrame(wf)
    wf.to_csv(os.path.join(SCR, "wf.csv"), index=False)
    print(wf.to_string(), flush=True)
    out["wf_total"] = wf.groupby(["coin", "inst"]).test_sum.sum().to_dict().__repr__()
    # grids for the report
    S.to_csv(os.path.join(SCR, "is_summary_q.csv"), index=False)
    grid = S.groupby(["inst", "setup", "N"]).agg(best=("net_day", "max"), med=("net_day", "median"),
                                                  gross_med=("gross_tr", "median"), tpd=("tpd", "median")).reset_index()
    grid.to_csv(os.path.join(SCR, "grid.csv"), index=False)
    # primary in detail (in-sample)
    th = json.load(open(os.path.join(SCR, "thresholds.json")))
    prim = {}
    dsum = None
    for coin in ("BTC", "ETH"):
        for inst in ("FT", "FM", "O"):
            a = OPT0 if inst == "O" else DAY0
            s, daily, t = D.detail(coin, PRIMARY, th[f"{coin}|3"], inst, a, IS_END)
            s.update(D.day_profile(daily))
            prim[f"{coin}|{inst}"] = s
            if inst == "FT":
                dsum = daily if dsum is None else dsum + daily
                t.to_parquet(os.path.join(SCR, f"prim_is_{coin}.parquet"))
    prim["BOTH|FT|days"] = D.day_profile(dsum)
    out["primary_is"] = prim
    json.dump(out, open(os.path.join(SCR, "an.json"), "w"), indent=1, default=float)
    print(json.dumps(prim, indent=1, default=float))


if __name__ == "__main__":
    main()
