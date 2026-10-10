"""R11 Part B: anatomy of the largest 1-minute moves at 1-second resolution, MCX evening sample (R7 recording,
9 Oct 2026 22:55-23:29 IST, Dhan full feed, 5 levels). CRUDEOIL and NATURALGAS near futures (+ ATM+-1 options' flow).

    python3 -I research/hunt/r11/partB.py   -> <scratch>/hunt/r11/B.log, B_seconds_<SYM>.csv, B_heatmap_<SYM>.csv,
                                                B_footprint.csv, B_minutes.csv

Cleaning follows R7/R8: drop exact repeats, drop older states re-sent (volume below its running max), drop crossed
books. Trade sign = tick rule (R7 section 4: the quote rule fails on this snapshot feed).
ONE EVENING, 34 MINUTES: every result here is an anecdote.
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
R7 = os.path.join(SCR, "hunt", "r7")
OUT = os.path.join(SCR, "hunt", "r11")
IST = 19800
BOOK = [f"{x}{k}" for k in range(5) for x in ("bq", "aq", "bo", "ao", "bp", "ap")]
KEYS = BOOK + ["vol", "ltp", "ltq", "ltt", "tbq", "tsq", "oi"]
LOG = open(os.path.join(OUT, "B.log"), "w")


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    LOG.write(s + "\n")


def clean(g):
    g = g.sort_values("ord")
    g = g[~g[KEYS].eq(g[KEYS].shift()).all(axis=1)]
    g = g[g.vol >= g.vol.cummax()]
    g = g[(g.bp0 > 0) & (g.ap0 > 0) & (g.ap0 > g.bp0)].copy()
    return g


def packet_features(g):
    pb, pa, qb, qa = g.bp0.shift(), g.ap0.shift(), g.bq0.shift(), g.aq0.shift()
    e = (np.where(g.bp0 >= pb, g.bq0, 0) - np.where(g.bp0 <= pb, qb, 0)
         - np.where(g.ap0 <= pa, g.aq0, 0) + np.where(g.ap0 >= pa, qa, 0))
    g["ofi"] = np.nan_to_num(e)
    m = np.zeros(len(g))
    for k in range(5):
        pbk, pak, qbk, qak = g[f"bp{k}"].shift(), g[f"ap{k}"].shift(), g[f"bq{k}"].shift(), g[f"aq{k}"].shift()
        m += np.nan_to_num(np.where(g[f"bp{k}"] >= pbk, g[f"bq{k}"], 0) - np.where(g[f"bp{k}"] <= pbk, qbk, 0)
                           - np.where(g[f"ap{k}"] <= pak, g[f"aq{k}"], 0) + np.where(g[f"ap{k}"] >= pak, qak, 0))
    g["mofi"] = m
    g["mid"] = (g.bp0 + g.ap0) / 2
    g["dv"] = g.vol.diff().clip(lower=0).fillna(0)
    sgn = np.sign(g.ltp.diff()).replace(0, np.nan).ffill().fillna(0)
    g["buy"] = np.where(sgn > 0, g.dv, 0)
    g["sell"] = np.where(sgn < 0, g.dv, 0)
    g["bd5"] = sum(g[f"bq{k}"] for k in range(5))
    g["ad5"] = sum(g[f"aq{k}"] for k in range(5))
    # pulls: size that left a still-visible level without a trade at that price (R8 definition, simplified)
    pull_b = np.zeros(len(g)); pull_a = np.zeros(len(g))
    rows = g[BOOK + ["ltp", "dv"]].values
    cols = {c: i for i, c in enumerate(BOOK + ["ltp", "dv"])}
    prev = None
    for n in range(len(rows)):
        r = rows[n]
        bk = {round(r[cols[f"bp{k}"]], 2): r[cols[f"bq{k}"]] for k in range(5) if r[cols[f"bq{k}"]] > 0}
        ak = {round(r[cols[f"ap{k}"]], 2): r[cols[f"aq{k}"]] for k in range(5) if r[cols[f"aq{k}"]] > 0}
        if prev is not None:
            pbk, pak = prev
            ltp, dv = round(r[cols["ltp"]], 2), r[cols["dv"]]
            lo_b = min(bk) if bk else np.inf
            for p, q0 in pbk.items():
                if p >= lo_b and p <= (max(bk) if bk else -np.inf):
                    q1 = bk.get(p, 0)
                    if q1 < q0:
                        drop = q0 - q1
                        traded = min(dv, drop) if (abs(p - ltp) < 1e-6 and dv > 0) else 0
                        pull_b[n] += drop - traded
            hi_a = max(ak) if ak else -np.inf
            for p, q0 in pak.items():
                if p <= hi_a and p >= (min(ak) if ak else np.inf):
                    q1 = ak.get(p, 0)
                    if q1 < q0:
                        drop = q0 - q1
                        traded = min(dv, drop) if (abs(p - ltp) < 1e-6 and dv > 0) else 0
                        pull_a[n] += drop - traded
        prev = (bk, ak)
    g["pull_b"], g["pull_a"] = pull_b, pull_a
    return g


def seconds(g):
    g = g.copy()
    g["s"] = np.floor(g.ts).astype(np.int64)
    b = g.groupby("s").agg(mid=("mid", "last"), ltp=("ltp", "last"), ofi=("ofi", "sum"), mofi=("mofi", "sum"),
                           buy=("buy", "sum"), sell=("sell", "sum"), vol=("dv", "sum"), prints=("dv", lambda x: (x > 0).sum()),
                           maxprint=("dv", "max"), bd5=("bd5", "last"), ad5=("ad5", "last"), bq0=("bq0", "last"),
                           aq0=("aq0", "last"), pull_b=("pull_b", "sum"), pull_a=("pull_a", "sum"), spread=("ap0", "last"))
    b["spread"] = b.spread - g.groupby("s").bp0.last()
    idx = np.arange(b.index.min(), b.index.max() + 1)
    b = b.reindex(idx)
    for c in ("mid", "ltp", "bd5", "ad5", "bq0", "aq0", "spread"):
        b[c] = b[c].ffill()
    for c in ("ofi", "mofi", "buy", "sell", "vol", "prints", "maxprint", "pull_b", "pull_a"):
        b[c] = b[c].fillna(0)
    b["delta"] = b.buy - b.sell
    b["di5"] = (b.bd5 - b.ad5) / (b.bd5 + b.ad5)
    return b


def main():
    raw = pd.read_parquet(os.path.join(R7, "ticks.parquet"))
    raw["ord"] = np.arange(len(raw))
    say("R11 Part B: MCX evening sample, 1-second anatomy of the largest 1-minute moves (ONE evening, anecdote)")
    allmin, foot = [], []
    for sym in ("CRUDEOIL", "NATURALGAS"):
        fut = raw[(raw.sym == sym) & (raw.kind == "FUT")]
        g = packet_features(clean(fut))
        B = seconds(g)
        # option flow, ATM+-1: calls' tick-rule signed volume minus puts'
        of = pd.Series(0.0, index=B.index)
        for (k, sid), og in raw[(raw.sym == sym) & (raw.kind.isin(["CE", "PE"]))].groupby(["kind", "sid"]):
            og = clean(og)
            og["dv"] = og.vol.diff().clip(lower=0).fillna(0)
            sg = np.sign(og.ltp.diff()).replace(0, np.nan).ffill().fillna(0)
            og["sv"] = sg * og.dv
            og["s"] = np.floor(og.ts).astype(np.int64)
            sv = og.groupby("s").sv.sum().reindex(B.index).fillna(0)
            of += (1 if k == "CE" else -1) * sv
        B["optflow"] = of
        B["ist"] = pd.to_datetime(B.index + IST, unit="s")
        B.to_csv(os.path.join(OUT, f"B_seconds_{sym}.csv"))
        # heatmap data: depth by price over time (5 levels each side), one row per second (last packet)
        g["s"] = np.floor(g.ts).astype(np.int64)
        last = g.groupby("s").tail(1)
        hm = []
        for r in last.itertuples():
            for k in range(5):
                hm.append((r.s, round(getattr(r, f"bp{k}"), 2), getattr(r, f"bq{k}"), "bid"))
                hm.append((r.s, round(getattr(r, f"ap{k}"), 2), getattr(r, f"aq{k}"), "ask"))
        pd.DataFrame(hm, columns=["sec", "price", "qty", "side"]).to_csv(os.path.join(OUT, f"B_heatmap_{sym}.csv"), index=False)
        # minutes (IST grid); a minute counts if fully inside the recording
        B["min"] = (B.index + IST) // 60
        mins = [m for m, x in B.groupby("min") if len(x) >= 55]
        lm = np.log(B.mid.values)
        sidx = {s: n for n, s in enumerate(B.index)}
        # normal scale of the 10-s sums (whole evening) for "first clear signal" timing
        roll = {c: B[c].rolling(10, min_periods=1).sum() for c in ("ofi", "mofi", "delta", "optflow", "pull_a", "pull_b", "vol")}
        sd = {c: roll[c].std() for c in roll}
        mu = {c: roll[c].mean() for c in roll}
        roll = {c: roll[c] - mu[c] for c in roll}
        med_print = np.median(g.dv[g.dv > 0])
        p95_print = np.percentile(g.dv[g.dv > 0], 95)
        for m in mins:
            x = B[B["min"] == m]
            s0, s1 = x.index[0], x.index[-1]
            ret = (np.log(x.mid.iloc[-1]) - np.log(B.mid.loc[s0 - 1] if (s0 - 1) in sidx else x.mid.iloc[0])) * 1e4
            d = 1 if ret >= 0 else -1
            # path of the move inside the minute and the 60 s before
            n0 = sidx[s0]
            lo = max(n0 - 60, 0)
            base = lm[n0 - 1] if n0 > 0 else lm[n0]
            path = (lm[lo:sidx[s1] + 1] - base) * 1e4 * d
            rel = np.arange(lo, sidx[s1] + 1) - n0
            tot = path[-1]
            pin = path[rel >= 0]; rin = rel[rel >= 0]          # the move inside the minute, from the prior second's mid
            t25 = rin[np.argmax(pin >= 0.25 * tot)] if tot > 0 and (pin >= 0.25 * tot).any() else np.nan
            t50 = rin[np.argmax(pin >= 0.5 * tot)] if tot > 0 and (pin >= 0.5 * tot).any() else np.nan
            r = dict(sym=sym, ist=str(pd.to_datetime(s0 + IST, unit="s").time())[:5], ret_bp=ret, dir=d, t25=t25, t50=t50)
            for c in roll:
                z = roll[c].values[lo:sidx[s1] + 1] / sd[c]
                aligned = z * (d if c in ("ofi", "mofi", "delta", "optflow") else 1)
                if c == "pull_a":
                    aligned = z if d > 0 else roll["pull_b"].values[lo:sidx[s1] + 1] / sd["pull_b"]   # pulls on the side in the way
                if c == "pull_b":
                    aligned = z if d < 0 else roll["pull_a"].values[lo:sidx[s1] + 1] / sd["pull_a"]   # pulls on the supporting side
                fs = rel[np.argmax(aligned >= 2)] if (aligned >= 2).any() else np.nan
                key = {"pull_a": "pull_way", "pull_b": "pull_back"}.get(c, c)
                r[f"first2sd_{key}"] = fs
                pre = aligned[(rel >= -60) & (rel <= -1)]
                dur = aligned[rel >= 0]
                r[f"pre_{key}"] = float(np.nanmean(pre)) if len(pre) else np.nan
                r[f"dur_{key}"] = float(np.nanmean(dur))
            # depth in the way (asks for an up move) and behind (bids), 60 s before vs the minute, vs the minute before that
            way, back = ("ad5", "bd5") if d > 0 else ("bd5", "ad5")
            pre = B.iloc[max(n0 - 60, 0):n0]
            r["depth_way_pre"], r["depth_way_dur"] = pre[way].mean(), x[way].mean()
            r["depth_back_pre"], r["depth_back_dur"] = pre[back].mean(), x[back].mean()
            r["di5_aligned_pre"] = d * pre.di5.mean()
            r["di5_aligned_dur"] = d * x.di5.mean()
            r["vol"], r["prints"] = x.vol.sum(), x.prints.sum()
            r["big_prints"] = int(((g.s >= s0) & (g.s <= s1) & (g.dv >= p95_print)).sum())
            r["maxprint"] = x.maxprint.max()
            r["buy_share_aligned"] = (x.buy.sum() if d > 0 else x.sell.sum()) / max(x.buy.sum() + x.sell.sum(), 1)
            r["spread_dur"] = x.spread.mean()
            r["spread_pre"] = pre.spread.mean()
            allmin.append(r)
        say(f"\n== {sym}: {len(B)} s, {len(mins)} full minutes, prints median {med_print:.0f} lots p95 {p95_print:.0f}")
        # footprint for the 3 largest minutes
        # whole-evening lead/lag at 1 s: corr(feature at s-k, mid return at s), k > 0 = the feature came first
        ret1 = pd.Series(np.diff(lm, prepend=lm[0]) * 1e4, index=B.index)
        ll = []
        for c in ("ofi", "mofi", "delta", "optflow", "vol"):
            f = B[c] if c != "vol" else B["vol"] * np.sign(ret1)
            row = {"feat": c}
            for k in (-5, -2, -1, 0, 1, 2, 3, 5, 10):
                row[k] = ret1.corr(f.shift(k))
            # 10-s sums vs the NEXT 10 s / 60 s
            f10 = f.rolling(10).sum()
            for h in (10, 60):
                fw = pd.Series(lm, index=B.index).shift(-h) - pd.Series(lm, index=B.index)
                row[f"next{h}s"] = f10.corr(fw)
            ll.append(row)
        say(f"{sym} 1-s lead/lag, corr(feature at s-k, mid return at s); k>0 = feature first; plus 10-s sum vs next 10/60 s:")
        say(pd.DataFrame(ll).round(3).to_string(index=False))
        M = pd.DataFrame([r for r in allmin if r["sym"] == sym])
        M["abs"] = M.ret_bp.abs()
        for r in M.sort_values("abs", ascending=False).head(3).itertuples():
            m = [mm for mm in mins if str(pd.to_datetime(mm * 60, unit="s").time())[:5] == r.ist][0]
            s0 = m * 60 - IST
            gg = g[(g.s >= s0 - 60) & (g.s < s0 + 60)].copy()
            gg["seg"] = np.where(gg.s < s0, "pre60s", "minute")
            fp = gg.groupby(["seg", "ltp"]).agg(buy=("buy", "sum"), sell=("sell", "sum")).reset_index()
            fp["sym"], fp["minute"] = sym, r.ist
            foot.append(fp)
    M = pd.DataFrame(allmin)
    M.to_csv(os.path.join(OUT, "B_minutes.csv"), index=False)
    pd.concat(foot).to_csv(os.path.join(OUT, "B_footprint.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 60)
    for sym, X in M.groupby("sym"):
        X = X.assign(a=X.ret_bp.abs()).sort_values("a", ascending=False)
        top, rest = X.head(4), X.iloc[4:]
        say(f"\n--- {sym}: the 4 largest 1-minute moves vs the other {len(rest)} minutes (aligned with the move) ---")
        cols = ["ist", "ret_bp", "t25", "t50", "first2sd_mofi", "first2sd_ofi", "first2sd_delta", "first2sd_optflow",
                "first2sd_pull_way", "first2sd_vol", "pre_mofi", "dur_mofi", "pre_delta", "dur_delta", "pre_optflow",
                "dur_optflow", "pre_pull_way", "dur_pull_way", "pre_vol", "dur_vol", "depth_way_pre", "depth_way_dur",
                "depth_back_pre", "depth_back_dur", "di5_aligned_pre", "di5_aligned_dur", "vol", "prints", "big_prints",
                "maxprint", "buy_share_aligned", "spread_pre", "spread_dur"]
        say(top[cols].round(2).T.to_string())
        say("other minutes, mean:")
        say(rest[cols[1:]].abs().mean().round(2).to_string() if False else rest[cols[4:]].mean().round(2).to_string())
        say(f"other minutes |ret| median {rest.ret_bp.abs().median():.1f} bp; share of other minutes where aligned MOFI "
            f"crossed 2 SD in the 60 s before: {(rest.first2sd_mofi < 0).mean():.0%}; delta: {(rest.first2sd_delta < 0).mean():.0%}")


if __name__ == "__main__":
    main()
