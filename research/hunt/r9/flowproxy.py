"""R9 D: delta divergence and absorption, PROXIES only (no tick / bid-ask history exists).
    python3 -I flowproxy.py design | fut     (hold: through holdout.py only)"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

HS = (15, 30, 60)
MOVE = 0.0015


def fut_delta(u):
    z = np.load(os.path.join(R.OUT, f"fut_{u}.npz"))
    P = R.panel(u)
    dd = np.full((P["nd"], R.W), np.nan, np.float32)
    pos = {int(d): i for i, d in enumerate(z["days"])}
    for i, d in enumerate(P["days"]):
        j = pos.get(R.L.D.dnum(d))
        if j is not None:
            dd[i] = np.sign(z["c"][j] - z["o"][j]) * z["v"][j]
    return dd


def spaced(ev):
    """keep events >= 15 min apart per (day, kind, dir)."""
    ev = ev.sort_values(["di", "kind", "dir", "t"])
    keep, last = [], {}
    for r in ev.itertuples():
        k = (r.di, r.kind, r.dir)
        if k not in last or r.t - last[k] >= 15:
            keep.append(r.Index)
            last[k] = r.t
    return ev.loc[keep]


def events_div(P, days, flow):
    rows = []
    for di in days:
        c = P["c"][di]
        f = np.nan_to_num(flow[di])
        cs = np.r_[0, np.cumsum(f)]
        for t in range(30, R.col(14, 45) + 1, 5):
            if not (np.isfinite(c[t]) and np.isfinite(c[t - 30])):
                continue
            r30 = c[t] / c[t - 30] - 1
            if abs(r30) < MOVE:
                continue
            f30 = cs[t + 1] - cs[t - 29]
            if f30 == 0:
                continue
            rev = -np.sign(r30)
            kind = "div" if np.sign(f30) != np.sign(r30) else "ctrl"
            fw = {h: (c[min(t + h, R.W - 1)] / c[t] - 1) * rev * 1e4 for h in HS}
            rows.append(dict(di=di, t=t, kind=kind, dir=int(rev), **{f"f{h}": fw[h] for h in HS}))
    return spaced(pd.DataFrame(rows)) if rows else pd.DataFrame()


def events_abs(P, days, vol):
    nd = P["nd"]
    nb = R.W // 5
    V = np.full((nd, nb), np.nan)
    RG = np.full((nd, nb), np.nan)
    for i in range(nd):
        v = vol[i][:nb * 5].reshape(nb, 5)
        V[i] = np.where(np.isfinite(v).any(1), np.nansum(v, 1), np.nan)
        hh = P["hh"][i][:nb * 5].reshape(nb, 5)
        ll = P["ll"][i][:nb * 5].reshape(nb, 5)
        with np.errstate(all="ignore"):
            RG[i] = np.nanmax(hh, 1) - np.nanmin(ll, 1)
    mv = pd.DataFrame(V).rolling(20, min_periods=10).median().shift(1).values
    mr = pd.DataFrame(RG).rolling(20, min_periods=10).median().shift(1).values
    rows = []
    for di in days:
        c = P["c"][di]
        for k in range(7, R.col(14, 45) // 5):
            s, e = 5 * k, 5 * k + 4
            if not (np.isfinite(c[s - 1]) and np.isfinite(c[s - 31]) and np.isfinite(c[e])):
                continue
            m = c[s - 1] / c[s - 31] - 1
            if abs(m) < MOVE or not (np.isfinite(mv[di, k]) and np.isfinite(V[di, k]) and mv[di, k] > 0):
                continue
            rel = V[di, k] / mv[di, k]
            if rel >= 2 and RG[di, k] <= 0.5 * mr[di, k]:
                kind = "abs"
            elif rel < 1.5:
                kind = "ctrl"
            else:
                continue
            rev = -np.sign(m)
            rows.append(dict(di=di, t=e, kind=kind, dir=int(rev),
                             **{f"f{h}": (c[min(e + h, R.W - 1)] / c[e] - 1) * rev * 1e4 for h in HS}))
    return spaced(pd.DataFrame(rows)) if rows else pd.DataFrame()


def summarize(ev, u, claim, per):
    out = []
    a = ev[ev.kind != "ctrl"]
    b = ev[ev.kind == "ctrl"]
    for h in HS:
        col = f"f{h}"
        diff, p = R.clboot_diff(a[col].values, a.di.values, b[col].values, b.di.values, B=2000) if len(a) > 5 else (np.nan, np.nan)
        m, t = R.L.cl_t(a[col].values, a.di.values) if len(a) > 2 else (np.nan, np.nan)
        out.append(dict(und=u, period=per, claim=claim, horizon=h, n_event=len(a), n_ctrl=len(b), event_bp=a[col].mean(),
                        ctrl_bp=b[col].mean(), diff_bp=diff, p_vs_ctrl=p, t_event=t, hit=(a[col] > 0).mean()))
    return out


def main(per):
    rows = []
    for u in ("NIFTY", "BANKNIFTY"):
        P = R.panel(u)
        if per == "fut":
            days = np.nonzero(P["has_fut"] & P["ok"])[0]
            flow, vol = fut_delta(u), P["futv"]
        else:
            m = P["isdes"] if per == "design" else P["ishold"]
            days = np.nonzero(m & P["ok"] & np.isfinite(P["optv"]).any(1))[0]
            flow, vol = P["flow"], P["optv"]
        rows += summarize(events_div(P, days, flow), u, "D1 delta divergence -> reversal", per)
        rows += summarize(events_abs(P, days, vol), u, "D2 absorption -> reversal", per)
        print(u, per, "done", flush=True)
    df = pd.DataFrame(rows)
    if per == "design":
        df["q"] = R.bh(df.p_vs_ctrl.values)
    df.to_csv(os.path.join(R.OUT, f"D_{per}.csv"), index=False)
    with pd.option_context("display.width", 250):
        print(df.round(4).to_string(index=False))


if __name__ == "__main__":
    per = sys.argv[1]
    if per == "hold" and not os.environ.get("R9_HOLDOUT_OK"):
        sys.exit("holdout runs only through holdout.py")
    main(per)
