"""R173 Amendment B (PREREG.md, Amendment B): the files' entry filters on the arms' BOUGHT options (B1) and spread variants
on real prices (B2). Signal source: the index for every rule.

    python research/hunt/r173_credit_spread_check/r173b.py      # -> results/amendment_b.json (+ trades/)

Periods as R173 (design / walk-forward / final); the final block is a second look (R173, R171), disclosed.
"""
from __future__ import annotations

import json
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import r173real as R  # noqa: E402
import r173study as S  # noqa: E402

RESULTS = HERE / "results"
M_TOTAL = 3925                      # 3,913 earlier + R173's 2 + Amendment B's 10
MC = 20_000
PERIOD_NAMES = ("design", "walkforward", "final")


# ================================================================================================ B1: filters on the arms
def twap_side(ix: np.ndarray, e: int, side: int) -> bool | None:
    """sign x (index open of minute e - mean of the 1-minute closes 09:15 .. e-1) >= 0; None when not computable."""
    if e <= 0 or e >= ix.shape[0]:
        return None
    o = ix[e, R.O]
    c = ix[:e, R.C]
    c = c[np.isfinite(c)]
    if not math.isfinite(o) or len(c) == 0:
        return None
    return bool(side * (o - c.mean()) >= 0)


def arm_frames(store: dict) -> dict:
    """The arms (R171's rebuild) per trade, re-costed with R17 fills + dated charges, plus the filter flags."""
    import r171arms as A
    cands = A.donchian_candidates(store, use_st=False, breadth=True)
    cset = {(c["und"], c["day"], c["side"], c["row"]) for c in cands}
    out = {}
    for name, ts in (("R72", A.r72_trades(store)), ("LIQ", A.liq_trades(store))):
        rows = []
        days = {(u, d.day): d for u in R.TRADED for d in store[u]}
        for t in ts:
            d = days[(t.und, t.day)]
            arr = d.opts.get((t.strike, t.right))
            lot = t.lot
            vin = R.vol5_lots(arr, t.fill_row, lot) if arr is not None else None
            vout = R.vol5_lots(arr, min(t.exit_row, R.N - 1), lot) if arr is not None else None
            buy = float(R.FILLS.buy(t.entry_raw, vin))
            sell = float(R.FILLS.sell(t.exit_raw, vout, stop=t.reason in ("stop", "lock")))
            dn = R.dnum(t.day)
            net = (sell - buy) * lot - R.charges(False, buy, lot, dn) - R.charges(True, sell, lot, dn)
            fresh = (t.und, t.day, t.side, t.entry_row - 5) not in cset if name == "R72" else None
            tw = twap_side(d.ix, t.entry_row, t.side)
            rows.append(dict(und=t.und, day=t.day, row=t.entry_row, side=t.side, strat=t.strat, net=net,
                             net_quoted=t.net["quoted"], fresh=fresh, twap=tw, period=S.period_of(t.day)))
        out[name] = pd.DataFrame(rows)
    return out


B1_TESTS = {"B1-F": ("R72", lambda r: r.fresh is not False),
            "B1-T": ("R72", lambda r: r.twap is not False),
            "B1-FT": ("R72", lambda r: (r.fresh is not False) and (r.twap is not False)),
            "B1-LT": ("LIQ", lambda r: r.twap is not False)}


def random_skip_p(values: np.ndarray, keep: np.ndarray, rng) -> tuple[float, float]:
    """One-sided p that a random subset of the same size sums at least as high: finite-population normal approximation
    and a Monte-Carlo check."""
    from scipy.stats import norm
    x = np.asarray(values, float)
    n, k = len(x), int(keep.sum())
    if k == 0 or k == n:
        return 1.0, 1.0
    actual = float(x[keep].sum())
    mu = k * x.mean()
    sd = math.sqrt(k * (n - k) / (n - 1) * x.var())
    p_norm = float(1 - norm.cdf((actual - mu) / sd)) if sd > 0 else 1.0
    hits = 0
    for _ in range(MC // 1000):
        idx = np.argsort(rng.random((1000, n)), axis=1)[:, :k]
        hits += int((x[idx].sum(axis=1) >= actual).sum())
    return p_norm, (hits + 1) / (MC + 1)


def b1(store: dict, rng) -> dict:
    frames = arm_frames(store)
    res = {}
    for tid, (arm, f) in B1_TESTS.items():
        df = frames[arm]
        df = df[df.period.notna()].copy()
        df["keep"] = [bool(f(r)) for r in df.itertuples()]
        per = {}
        for p in PERIOD_NAMES:
            g = df[df.period == p]
            kept = g[g.keep]
            per[p] = dict(arm=S.summ(g), kept=S.summ(kept), arm_quoted=S.summ(g, "net_quoted"),
                          kept_quoted=S.summ(kept, "net_quoted"),
                          gain_per_trade=round(float(kept.net.mean() - g.net.mean()), 1) if len(kept) else None,
                          gain_per_trade_quoted=round(float(kept.net_quoted.mean() - g.net_quoted.mean()), 1) if len(kept) else None)
        fin = df[df.period == "final"]
        p_norm, p_mc = random_skip_p(fin.net.to_numpy(), fin.keep.to_numpy(), rng)
        per["random_skip"] = dict(p_normal=p_norm, p_mc=p_mc)
        res[tid] = per
    return res


# ================================================================================================ B2: spread variants
def pick_delta(spot, sg, step, d, e, target=0.28):
    """V1: the OTM strike whose implied |delta| at the last close before minute e is closest to 0.28; wing 3 steps out."""
    right = "P" if sg > 0 else "C"
    if d.exp is None:
        return None
    t = max((d.exp - d.day).days, 0) / 365.0 + (R.N - e) / R.N * 6.25 / 24 / 365
    best = None
    for (k, r), A in d.opts.items():
        if r != right or (sg > 0 and k >= spot) or (sg < 0 and k <= spot):
            continue
        px = R.last_close(A, e - 1)
        if not math.isfinite(px):
            continue
        dl = R.bs_delta(px, spot, k, right, t)
        if not math.isfinite(dl):
            continue
        gap = abs(abs(dl) - target)
        if best is None or gap < best[0]:
            best = (gap, k)
    if best is None:
        return None
    ks = best[1]
    return ks, ks - sg * 3 * step, right


def pick_wide(spot, sg, step, d, e):
    ks, _, right = R.strikes(spot, sg, step)
    return ks, ks - sg * 6 * step, right


def pick_debit(spot, sg, step, d, e):
    atm = math.floor(spot / step + 0.5) * step
    right = "C" if sg > 0 else "P"
    return atm + sg * 3 * step, atm, right          # (short further OTM, long ATM)


VARIANTS = {"V1-delta": pick_delta, "V2-wide": pick_wide, "V3-debit": pick_debit}


def walk(ents: list, bk: dict, pick, r72: bool) -> tuple[list, dict]:
    out, skipped = [], {"no_strike": 0, "no_fill": 0}
    taken: dict = {}
    for en in sorted(ents, key=lambda x: (x["day"], x["e"], x["und"])) if r72 else ents:
        key = (en["und"], en["day"], en["side"])
        if r72 and taken.get(key):
            continue
        r = R.simulate(en, bk[en["und"]], "B2", pick)
        if isinstance(r, str):
            skipped[r] += 1
            continue
        taken[key] = True
        out.append(r)
    return out, skipped


def twins(trades: list, ents: list, bk: dict, pick, rng) -> dict:
    from scipy.stats import norm
    by_key = {(e["und"], e["day"], e["side"], e["e"]): e for e in ents}
    pools = []
    for t in trades:
        ent = by_key[(t.und, t.day, t.side, t.e)]
        I = bk[t.und].I[ent["k"]]
        vals = []
        for e in S.SLOTS:
            ix = float(I["open"][e])
            tw = dict(ent, e=e, ix0=ix, target=ix + (ent["target"] - ent["ix0"]), stop=ix + (ent["stop"] - ent["ix0"]))
            r = R.simulate(tw, bk[t.und], "twin", pick)
            if not isinstance(r, str):
                vals.append(r.net)
        pools.append(np.array(vals) if vals else np.array([t.net]))
    actual = float(sum(t.net for t in trades))
    tot = np.zeros(S.SETS)
    for p in pools:
        tot += p[rng.integers(0, len(p), S.SETS)]
    mu = float(sum(p.mean() for p in pools))
    sd = float(math.sqrt(sum(p.var() for p in pools)))
    p_norm = float(1 - norm.cdf((actual - mu) / sd)) if sd > 0 else 1.0
    return dict(actual=round(actual), random_mean=round(mu), random_sd=round(sd), p_normal=p_norm,
                p_mc=float(((tot >= actual).sum() + 1) / (S.SETS + 1)))


def b2(bk: dict, rng) -> dict:
    res = {}
    sigs = R.r72_signals(bk)
    for vname, pick in VARIANTS.items():
        for sname in ("R72", "LIQ2"):
            tid = f"{vname}/{sname}"
            per, frames = {}, []
            for p in PERIOD_NAMES:
                a, b_ = S.PERIODS[p]
                ents = R.r72_entries(bk, sigs, a, b_) if sname == "R72" else R.liq_entries(bk, a, b_)
                ts, sk = walk(ents, bk, pick, sname == "R72")
                acct, miss = R.account(ts)
                df, dfa = R.to_frame(ts), R.to_frame(acct)
                per[p] = dict(all=S.summ(df), account=S.summ(dfa), account_quoted=S.summ(dfa, "net_quoted") if len(dfa) else {},
                              not_taken_margin=miss, skipped=sk,
                              margin_median=round(float(df.margin.median())) if len(df) else None,
                              exit_mix={k: int(v) for k, v in df.why.value_counts().items()} if len(df) else {})
                if len(dfa):
                    frames.append(dfa)
                    S._save(dfa, f"B2_{vname}_{sname}_{p}_account")
                if p == "final":
                    per["random"] = twins(acct, ents, bk, pick, rng)
            whole = pd.concat(frames) if frames else pd.DataFrame()
            per["whole_account"] = S.summ(whole) if len(whole) else dict(n=0)
            res[tid] = per
    return res


# ================================================================================================ gates
def gates(b1r: dict, b2r: dict) -> dict:
    ps = {k: v["random_skip"]["p_normal"] for k, v in b1r.items()}
    ps.update({k: v["random"]["p_normal"] for k, v in b2r.items()})
    ranked = sorted(ps.items(), key=lambda kv: kv[1])
    q = {}
    for i, (k, _) in enumerate(ranked, 1):
        q[k] = min(1.0, min(pp * M_TOTAL / r for r, (_, pp) in enumerate(ranked, 1) if r >= i))
    out = {}
    for k, v in b1r.items():
        f, d_, w = v["final"], v["design"], v["walkforward"]
        g = {"1 >= 40 final kept": f["kept"].get("n", 0) >= 40,
             "2 net a trade above the arm (final)": (f["gain_per_trade"] or -1) > 0,
             "3 random-skip Monte-Carlo p <= 0.01": v["random_skip"]["p_mc"] <= 0.01,
             "4 BH q <= 0.10": q[k] <= 0.10,
             "5 gain > 0 in design and walk-forward": (d_["gain_per_trade"] or -1) > 0 and (w["gain_per_trade"] or -1) > 0,
             "6 gain > 0 under the quoted law (final)": (f["gain_per_trade_quoted"] or -1) > 0,
             "7 max DD and worst month not worse (final)": f["kept"].get("max_dd", -1e9) >= f["arm"].get("max_dd", 0)
             and f["kept"].get("worst_month", -1e9) >= f["arm"].get("worst_month", 0)}
        out[k] = dict(q=q[k], gates=g, passed=all(g.values()))
    for k, v in b2r.items():
        nets = [v[p]["account"].get("net", -1) for p in PERIOD_NAMES]
        wh = v["whole_account"]
        g = {"1 net > 0 design / WF / final": all(x > 0 for x in nets),
             "2 beats random entries (Monte-Carlo p <= 0.01)": v["random"]["p_mc"] <= 0.01,
             "3 BH q <= 0.10": q[k] <= 0.10,
             "4 >= 40 final trades": v["final"]["account"].get("n", 0) >= 40,
             "5 max DD > -40k, no month < -13k": wh.get("max_dd", -1e9) > -40_000 and wh.get("worst_month", -1e9) > -13_000,
             "6 sign holds under the quoted law": all(v[p]["account_quoted"].get("net", -1) > 0 for p in PERIOD_NAMES)}
        out[k] = dict(q=q[k], gates=g, passed=all(g.values()))
    return out


def main() -> dict:
    import r171data as D
    store = D.load()
    bk = R.books(store)
    rng = np.random.default_rng(17302)
    b1r = b1(store, rng)
    b2r = b2(bk, rng)
    res = dict(B1=b1r, B2=b2r, gates=gates(b1r, b2r), m=M_TOTAL)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "amendment_b.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    r = main()
    print(json.dumps(r["gates"], indent=1, default=str))
