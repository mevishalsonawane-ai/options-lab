"""A learned model on the long BANKNIFTY history: index price action, the option premiums, OI and volume at each 5-minute
decision point -> the rupee result of buying the ATM CE or PE there with the arms' own exits (-40 / +40, 15:10).

Honest by construction:
  * features use only minutes BEFORE the decision; the trade fills on the option's first minute at/after it (+0.5)
  * walk-forward by month: each month is predicted by models trained only on earlier months (expanding window)
  * results below are out-of-sample only, after Rs 40 a trip and 0.5 a side, one lot of 30
Two learners: gradient-boosted trees and a small neural network (MLP), plus their average.
"""
from __future__ import annotations

import os
import sys
import warnings

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import CHG, LOT, SLIP, days_from  # noqa: E402

warnings.filterwarnings("ignore")
M0, N = 9 * 60 + 15, 375                    # minute grid 09:15 .. 15:29
CUT = 15 * 60 + 10 - M0                     # 15:10 square-off
STOP = TGT = 40.0


def grid(df, cols):
    """Minute-grid arrays (length N) for [cols]; close forward-filled, high/low default to the close."""
    m = df.index.hour * 60 + df.index.minute - M0
    ok = (m >= 0) & (m < N)
    out = {}
    for c in cols:
        a = np.full(N, np.nan)
        a[m[ok]] = df[c].values[ok]
        out[c] = a
    c = pd.Series(out["close"]).ffill().bfill().values
    out["close"] = c
    for k in ("open", "high", "low"):
        if k in out:
            out[k] = np.where(np.isnan(out[k]), c, out[k])
    return out


def load(src):
    gen = days_from(src)
    days = []
    for day, ix, opts in gen:
        if ix is None or len(ix) < 300 or opts.empty:
            continue
        I = grid(ix, ["open", "high", "low", "close"])
        spot = I["close"][5]                                   # 09:20
        exps = sorted(e for e in opts.expiry.unique() if e > day)
        if not exps:
            continue
        ch = opts[opts.expiry == exps[0]]
        ks = np.sort(ch.strike.unique())
        if len(ks) == 0:
            continue
        k = ks[np.argmin(np.abs(ks - spot))]
        legs, oi = {}, {"CE": np.zeros(N), "PE": np.zeros(N)}
        vol = {"CE": np.zeros(N), "PE": np.zeros(N)}
        for right in ("CE", "PE"):
            s = ch[(ch.right == right) & (ch.strike == k)].sort_values("ts").set_index("ts")
            if len(s) < 200:
                break
            legs[right] = grid(s, ["open", "high", "low", "close"])
            near = ch[(ch.right == right) & (abs(ch.strike - k) <= 200)]
            for _, g in near.groupby("strike"):
                g = g.sort_values("ts").set_index("ts")
                gg = grid(g, ["close", "open_interest", "volume"])
                oi[right] += pd.Series(gg["open_interest"]).ffill().fillna(0).values
                vol[right] += np.nan_to_num(gg["volume"])
        if len(legs) < 2:
            continue
        days.append(dict(day=day, dte=(exps[0] - day).days, I=I, legs=legs, oi=oi, vol=vol))
    return days


def outcome(leg, t):
    """(rupees, exit minute) for buying [leg] at minute t with -40 / +40 and the 15:10 exit."""
    if t >= CUT:
        return None
    e = leg["open"][t] + SLIP
    lo, hi = leg["low"][t:CUT + 1], leg["high"][t:CUT + 1]
    s = np.flatnonzero(lo <= e - STOP)
    g = np.flatnonzero(hi >= e + TGT)
    s0 = s[0] if len(s) else 10 ** 6
    g0 = g[0] if len(g) else 10 ** 6
    if s0 == g0 == 10 ** 6:
        return (leg["close"][CUT] - SLIP - e) * LOT - CHG, CUT
    if s0 <= g0:                                                # both in one minute: count the stop
        return (-STOP - SLIP) * LOT - CHG, t + s0
    return (TGT - SLIP) * LOT - CHG, t + g0


FEATS = ["tod", "dow", "dte", "r5", "r15", "r30", "r60", "r_open", "gap", "prev_ret", "prev_rng", "rv30", "day_pos",
         "or_pos", "or_w", "twap_d", "strad", "strad_d15", "strad_d30", "skew", "ce_r15", "pe_r15", "oi_ce_d30",
         "oi_pe_d30", "pcr", "vol_lr15"]


def rows(days):
    out = []
    for i, d in enumerate(days):
        I, L = d["I"], d["legs"]
        c = I["close"]
        prev = days[i - 1] if i else None
        gap = (I["open"][0] / prev["I"]["close"][-1] - 1) * 1e4 if prev else 0.0
        prev_ret = (prev["I"]["close"][-1] / prev["I"]["open"][0] - 1) * 1e4 if prev else 0.0
        prev_rng = (prev["I"]["high"].max() / prev["I"]["low"].min() - 1) * 1e4 if prev else 0.0
        orh, orl = I["high"][:50].max(), I["low"][:50].min()   # 09:15 .. 10:04 = the 09:15-10:00 5-minute bars
        for t in range(30, 316, 5):                            # decisions 09:45 .. 14:30, on the minute grid
            ce, pe = L["CE"]["close"], L["PE"]["close"]
            lr = lambda a, k: (a[t - 1] / a[max(t - 1 - k, 0)] - 1) * 1e4
            strad = ce[t - 1] + pe[t - 1]
            f = dict(
                tod=t, dow=d["day"].weekday(), dte=d["dte"],
                r5=lr(c, 5), r15=lr(c, 15), r30=lr(c, 30), r60=lr(c, 60), r_open=(c[t - 1] / I["open"][0] - 1) * 1e4,
                gap=gap, prev_ret=prev_ret, prev_rng=prev_rng,
                rv30=np.std(np.diff(np.log(c[max(t - 31, 0):t]))) * 1e4,
                day_pos=(c[t - 1] - I["low"][:t].min()) / max(I["high"][:t].max() - I["low"][:t].min(), 1),
                or_pos=((c[t - 1] - orl) / max(orh - orl, 1)) if t >= 50 else 0.5,
                or_w=((orh - orl) / c[t - 1] * 1e4) if t >= 50 else 0.0,
                twap_d=(c[t - 1] / np.mean((I["high"][:t] + I["low"][:t] + c[:t]) / 3) - 1) * 1e4,
                strad=strad / c[t - 1] * 1e4, strad_d15=lr(ce + pe, 15), strad_d30=lr(ce + pe, 30),
                skew=(ce[t - 1] - pe[t - 1]) / strad,
                ce_r15=lr(ce, 15), pe_r15=lr(pe, 15),
                oi_ce_d30=(d["oi"]["CE"][t - 1] / max(d["oi"]["CE"][t - 31], 1) - 1) * 100,
                oi_pe_d30=(d["oi"]["PE"][t - 1] / max(d["oi"]["PE"][t - 31], 1) - 1) * 100,
                pcr=d["oi"]["PE"][t - 1] / max(d["oi"]["CE"][t - 1], 1),
                vol_lr15=np.log((d["vol"]["CE"][t - 15:t].sum() + 1) / (d["vol"]["PE"][t - 15:t].sum() + 1)),
            )
            oc, op = outcome(L["CE"], t), outcome(L["PE"], t)
            if oc is None or op is None:
                continue
            f.update(day=d["day"], t=t, y_ce=oc[0], x_ce=oc[1], y_pe=op[0], x_pe=op[1])
            out.append(f)
    df = pd.DataFrame(out)
    df[FEATS] = df[FEATS].replace([np.inf, -np.inf], np.nan).fillna(0.0)
    return df


def models():
    from sklearn.ensemble import HistGradientBoostingRegressor
    from sklearn.neural_network import MLPRegressor
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    return {
        "trees": lambda: HistGradientBoostingRegressor(max_depth=3, learning_rate=0.05, max_iter=200,
                                                      min_samples_leaf=80, l2_regularization=1.0),
        "neural net": lambda: make_pipeline(StandardScaler(), MLPRegressor(hidden_layer_sizes=(32, 16), alpha=1e-2,
                                            early_stopping=True, max_iter=300, random_state=0)),
    }


def walk_forward(df, min_train_months=4):
    df = df.copy()
    df["m"] = pd.to_datetime(df.day).dt.strftime("%Y-%m")
    months = sorted(df.m.unique())
    for name in models():
        df[f"p_ce_{name}"] = np.nan
        df[f"p_pe_{name}"] = np.nan
    for i, m in enumerate(months):
        if i < min_train_months:
            continue
        tr, te = df[df.m < m], df.m == m
        for name, make in models().items():
            for side in ("ce", "pe"):
                mdl = make().fit(tr[FEATS].values, tr[f"y_{side}"].values)
                df.loc[te, f"p_{side}_{name}"] = mdl.predict(df.loc[te, FEATS].values)
        print(f"predicted {m} from {len(tr)} rows", flush=True)
    for side in ("ce", "pe"):
        df[f"p_{side}_blend"] = (df[f"p_{side}_trees"] + df[f"p_{side}_neural net"]) / 2
    return df


def trade(df, name, thr, maxn=2):
    out = []
    for day, g in df.dropna(subset=[f"p_ce_{name}"]).groupby("day"):
        n, busy = 0, -1
        for _, r in g.sort_values("t").iterrows():
            if n >= maxn or r.t <= busy:
                continue
            side = "ce" if r[f"p_ce_{name}"] >= r[f"p_pe_{name}"] else "pe"
            if r[f"p_{side}_{name}"] < thr:
                continue
            out.append(dict(day=day, m=r.m, net=r[f"y_{side}"]))
            n += 1
            busy = r[f"x_{side}"]
    return pd.DataFrame(out, columns=["day", "m", "net"])


def summary(tr, label):
    if tr.empty:
        return f"| {label} | 0 | - | - | - | - |"
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    gm = (tr.groupby("m").net.sum() > 0)
    return f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {t:.2f} | {gm.sum()}/{len(gm)} |"


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "local"
    days = load(src)
    df = rows(days)
    print(f"{len(days)} days, {len(df)} decision points", flush=True)
    out = [f"## Learned model on {len(days)} BANKNIFTY sessions {days[0]['day']} .. {days[-1]['day']}", "",
           f"{len(df)} decision points (every 5 minutes 09:45-14:30). Every decision point, bought blind: "
           f"CE averages Rs {df.y_ce.mean():,.0f}, PE Rs {df.y_pe.mean():,.0f} a trade; "
           f"{100 * (df.y_ce > 0).mean():.0f}% / {100 * (df.y_pe > 0).mean():.0f}% winners.", ""]
    wf = walk_forward(df, 1 if src == "local" else 4)
    ev = wf.dropna(subset=["p_ce_trees"])
    out += ["Out-of-sample: does the prediction rank the outcome? (Spearman correlation of predicted vs actual rupees)", ""]
    for name in ("trees", "neural net", "blend"):
        ic = [ev[f"p_{s}_{name}"].corr(ev[f"y_{s}"], method="spearman") for s in ("ce", "pe")]
        out.append(f"- {name}: CE {ic[0]:+.3f}, PE {ic[1]:+.3f}")
    out += ["", "Out-of-sample trading (take the better side when its predicted rupees clear the bar; max 2 a day, one at a time):",
            "", "| model / bar | trades | win | net Rs | t | green months |", "|---|---|---|---|---|---|"]
    for name in ("trees", "neural net", "blend"):
        for thr in (0, 200, 400, 600):
            out.append(summary(trade(ev, name, thr), f"{name} > Rs {thr}"))
    rnd = ev.assign(net=np.where(np.random.default_rng(0).random(len(ev)) < 0.5, ev.y_ce, ev.y_pe))
    out.append(summary(rnd.groupby("day").head(2), "coin flip, first 2 points a day (baseline)"))
    try:
        from sklearn.inspection import permutation_importance
        last = sorted(ev.m.unique())[-1]
        tr = wf[wf.m < last]
        mdl = models()["trees"]().fit(tr[FEATS].values, tr.y_ce.values)
        te = wf[wf.m == last]
        imp = permutation_importance(mdl, te[FEATS].values, te.y_ce.values, n_repeats=5, random_state=0)
        top = sorted(zip(FEATS, imp.importances_mean), key=lambda x: -x[1])[:8]
        out += ["", "What the trees lean on (CE, last month, permutation importance): " +
                ", ".join(f"{f} {v:.3f}" for f, v in top)]
    except Exception as e:  # noqa: BLE001
        out.append(f"(importance skipped: {e})")
    text = "\n".join(out)
    print("\n" + text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
            fh.write(text + "\n")


if __name__ == "__main__":
    main()
