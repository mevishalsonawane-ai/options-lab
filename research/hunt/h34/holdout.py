"""h34 holdout (2025-10-01 ..), run ONCE after analyse.py: the PASSING strategies' chosen variants (choice.json), and
as REFERENCE only (PREREG section 5): Liquidity 15+5 BN+FIN with its original exits and with the menu exit its
walk-forward picked for 2025, plus the random baseline's holdout means (rand.py --hold must have run).

    flock <scratch>/obuy.lock python3 -I research/hunt/h34/holdout.py
"""
from __future__ import annotations

import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402
from obuy.data import market, dnum  # noqa: E402
from obuy.engine import prepare_many, positions  # noqa: E402
from obuy.lab import signals_cached  # noqa: E402


def cells_hold():
    C = {}
    for u in K.UNDS5:
        f = os.path.join(K.OUT, f"rand_{u}_hold_cells.npz")
        if os.path.exists(f):
            z = np.load(f)
            C[u] = {k: z[k] for k in z.files}
    return C


def matched(tr, cells, ri, xi):
    obs = mu = var = 0.0
    n = 0
    for u, g in tr.groupby("und"):
        c = cells.get(u)
        if c is None:
            continue
        cid = np.array([dnum(d) for d in g.day]) * 10 + K.bucket(g.entry_min.values)
        U = c[f"cid_r{ri}"]
        pos = np.clip(np.searchsorted(U, cid), 0, len(U) - 1)
        cnt = c[f"cnt_r{ri}"][pos].astype(float)
        ok = (U[pos] == cid) & (cnt >= 2)
        if not ok.any():
            continue
        cn = cnt[ok]
        m = c[f"s1_r{ri}"][pos[ok], xi] / cn
        v = np.maximum(c[f"s2_r{ri}"][pos[ok], xi] / cn - m ** 2, 0) * cn / (cn - 1)
        obs += g.NET.values[ok].sum()
        mu += m.sum()
        var += v.sum()
        n += int(ok.sum())
    if n < 5 or var <= 0:
        return np.nan, np.nan
    return float(1 - sst.norm.cdf((obs - mu) / np.sqrt(var))), mu / n


def stats(tr, unds, label):
    mk = market()
    days = sorted(set().union(*[set(d for d in mk.index(u).days if d >= K.HOLD) for u in unds]))
    dl = tr.groupby("day").NET.sum().reindex(days, fill_value=0.0) if len(tr) else pd.Series(0.0, index=days)
    c = dl.cumsum().values
    dd = float((c - np.maximum.accumulate(np.concatenate([[0], c]))[1:]).min())
    mo = dl.groupby([d.strftime("%Y-%m") for d in dl.index]).sum()
    net, gross, stress = (float(tr[k].sum()) if len(tr) else 0.0 for k in ("NET", "gross_mid", "STRESS"))
    per_day = net / len(days)
    return dict(label=label, trades=len(tr), days=len(days), NET=net, GROSS=gross, STRESS=stress, net_day=per_day,
                gross_day=gross / len(days), lots_5k=(5000 / per_day if per_day > 0 else None), max_dd=dd,
                worst_day=float(dl.min()), months_pos=f"{int((mo > 0).sum())}/{len(mo)}",
                months=" ".join(f"{k[2:]}:{v / 1000:+.1f}k" for k, v in mo.items()))


def run_one(st, si, rule, ex, menu_ri=None, menu_xi=None, orig=False, label=""):
    raw = signals_cached(st, st.sig_grid[si])
    market().release()
    sig = K.period(raw, pre=False) if orig else K.prep_signals(raw, pre=False)
    exe = K.exe_h34(st.exe)
    pk, _ = prepare_many([(sig, rule, exe, 0, None, False)])[0]
    tr = pk.run(ex, exe) if len(pk) else pd.DataFrame()
    tr = K.post(positions(tr, **st.pos)) if len(tr) else tr
    unds = sorted(set(sig.und)) if len(sig) else ["NIFTY"]
    r = stats(tr, unds, label)
    if menu_xi is not None and len(tr):
        r["p_rand"], r["rand_mean"] = matched(tr, cells_hold(), menu_ri, menu_xi)
        r["mean"] = r["NET"] / max(r["trades"], 1)
    return r


def main():
    S = dict(K.strategies())
    ch = json.load(open(os.path.join(K.OUT, "choice.json")))
    F = pd.read_csv(os.path.join(K.OUT, "strategies.csv")).set_index("strategy")
    out = []
    for c in ch.get("candidates", []):
        name, rest = c["vid"].split("|", 1)
        tag, rl, x = rest.split("|")
        isb = json.load(open(os.path.join(K.OUT, "is_best.json")))[name]
        si = 0 if tag == "def" else isb["si"]
        ri, xi = K.RLAB.index(rl), int(x[1:])
        out.append(dict(kind="CANDIDATE", **run_one(S[name], si, K.RULES[ri], K.MENU[xi], ri, xi, label=c["vid"])))
    # reference: Liquidity BN+FIN, original exits and the menu exit its walk-forward picked for 2025
    st = S["liquidity15_5"]
    out.append(dict(kind="REFERENCE", **run_one(st, 0, st.rules[0], st.exits[0], orig=True, label="liquidity15_5 original arm exits")))
    picks = str(F.loc["liquidity15_5", "wf_picks"]).split("; ")
    p25 = [p for p in picks if p.startswith("2025:")]
    if p25:
        rl, exl = p25[0][5:].split(" ")[:2]
        ri, xi = K.RLAB.index(rl), K.MLAB.index(exl)
        out.append(dict(kind="REFERENCE", **run_one(st, 0, K.RULES[ri], K.MENU[xi], ri, xi,
                                                    label=f"liquidity15_5 WF-2025 menu pick {rl} {exl}")))
    # random baseline holdout means
    A = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(os.path.join(K.OUT, "rand_*_hold_agg.parquet")))], ignore_index=True)
    rb = []
    for (u, ru), d in A.groupby(["und", "rule"]):
        e = d.groupby("exit")[["n", "net", "gross"]].sum()
        rb.append(dict(und=u, rule=ru, n=int(e.n.iloc[0]), net_t_mean=float((e.net / e.n).mean()),
                       net_t_best=float((e.net / e.n).max()), best=K.MLAB[(e.net / e.n).idxmax()],
                       gross_t_mean=float((e.gross / e.n).mean()), share_pos=float(((e.net / e.n) > 0).mean())))
    res = dict(rows=out, random=rb)
    with open(os.path.join(K.OUT, "holdout.json"), "w") as f:
        json.dump(res, f, indent=1, default=float)
    for r in out:
        print(r)
    print(pd.DataFrame(rb).to_string())


if __name__ == "__main__":
    main()
