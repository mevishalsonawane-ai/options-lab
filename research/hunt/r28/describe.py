#!/usr/bin/env python3
"""R28 Step 2 (PREREG.md): does the GEX regime separate days? Descriptive, Step-2 design days only (r28cfg.is_describe).

    python3 research/hunt/r28/describe.py        # -> results/describe.json, results/describe.md

Six questions per index (Q1-Q6 of PREREG.md), each with its sample size, a two-sided test and the same comparison inside
VIX terciles and days-to-expiry buckets. BH at q 0.10 across every p-value here. Nothing is chosen from this step.
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd
from scipy import stats as SS

import r28cfg as K
import r28gex as G

N = 375
R10, R0920, R12, R1345, R1515 = (K.CLOCK[k] for k in ("10:00", "09:20", "12:00", "13:45", "15:15"))
LAST_DEC = 13 * 60 + 55 - 555 + 4          # the 5-minute bar labelled 13:55 ends on this row
SQ = 15 * 60 + 10 - 555                    # 15:10


# ------------------------------------------------------------------------------------------------ data
def index_days(name: str) -> dict:
    out = {}
    for y, p in G.files(name, f"index_1m_{name}").items():
        df = G._read(p, date(y, 1, 1), date(y, 12, 31))
        for d, g in df.groupby("d"):
            a = np.full((N, 4), np.nan)
            a[g.minute.values] = g[["open", "high", "low", "close"]].values.astype(float)
            out[d] = a
    return out


def vix_prev(vix: dict) -> dict:
    days = sorted(vix)
    out = {}
    for i in range(1, len(days)):
        c = vix[days[i - 1]][:, 3]
        c = c[np.isfinite(c)]
        if len(c):
            out[days[i]] = float(c[-1])
    return out


def atr14(ix: dict) -> dict:
    days = sorted(ix)
    hlc = []
    for d in days:
        a = ix[d]
        hlc.append((np.nanmax(a[:, 1]), np.nanmin(a[:, 2]), a[np.isfinite(a[:, 3]), 3][-1] if np.isfinite(a[:, 3]).any() else np.nan))
    out = {}
    for i in range(15, len(days)):
        tr = [max(hlc[j][0] - hlc[j][1], abs(hlc[j][0] - hlc[j - 1][2]), abs(hlc[j][1] - hlc[j - 1][2]))
              for j in range(i - 14, i)]
        out[days[i]] = float(np.nanmean(tr))
    return out


# ------------------------------------------------------------------------------------------------ tests
def mw(a, b) -> tuple[float, int, int]:
    a, b = np.asarray(a, float), np.asarray(b, float)
    a, b = a[np.isfinite(a)], b[np.isfinite(b)]
    if len(a) < 5 or len(b) < 5:
        return np.nan, len(a), len(b)
    return float(SS.mannwhitneyu(a, b, alternative="two-sided").pvalue), len(a), len(b)


def prop(k1, n1, k2, n2) -> float:
    if min(n1, n2) < 5:
        return np.nan
    p = (k1 + k2) / (n1 + n2)
    se = np.sqrt(p * (1 - p) * (1 / n1 + 1 / n2))
    if se == 0:
        return np.nan
    z = (k1 / n1 - k2 / n2) / se
    return float(2 * SS.norm.sf(abs(z)))


def strata(df: pd.DataFrame, flag: str, y: str, how="median") -> dict:
    """The comparison (flag True minus False) inside VIX terciles and DTE buckets: values and how many agree in sign."""
    out = {}
    q = df.vix.quantile([1 / 3, 2 / 3]).values if df.vix.notna().sum() > 10 else [np.nan, np.nan]
    df = df.assign(vb=np.where(df.vix <= q[0], "vix_lo", np.where(df.vix <= q[1], "vix_mid", "vix_hi")),
                   db=np.where(df.dte == 0, "dte0", np.where(df.dte <= 2, "dte1-2", "dte3+")))
    for col in ("vb", "db"):
        for k, g in df.groupby(col):
            a, b = g[g[flag]][y].dropna(), g[~g[flag]][y].dropna()
            if len(a) >= 5 and len(b) >= 5:
                out[k] = dict(diff=float(getattr(a, how)() - getattr(b, how)()), n=[len(a), len(b)])
    full = df[df[flag]][y].dropna(), df[~df[flag]][y].dropna()
    s = np.sign(getattr(full[0], how)() - getattr(full[1], how)()) if len(full[0]) and len(full[1]) else 0
    out["agree"] = f"{sum(np.sign(v['diff']) == s for k, v in out.items() if isinstance(v, dict))}/" \
                   f"{sum(1 for v in out.values() if isinstance(v, dict))}"
    return out


# ------------------------------------------------------------------------------------------------ the day table
def day_table(und: str, gx: pd.DataFrame, ix: dict, vx: dict, atr: dict) -> pd.DataFrame:
    rows = []
    gd = dict(tuple(gx.groupby("day")))
    days = sorted(d for d in gd if d in ix)
    for i, d in enumerate(days):
        g = gd[d].set_index("minute")
        a = ix[d]
        c10 = a[R10, 3]
        h, lo = np.nanmax(a[R10 + 1:, 1]), np.nanmin(a[R10 + 1:, 2])
        cl = a[np.isfinite(a[:, 3]), 3][-1] if np.isfinite(a[:, 3]).any() else np.nan
        r = np.diff(np.log(a[R10:, 3]))
        r = r[np.isfinite(r)]
        nxt = days[i + 1] if i + 1 < len(days) else None
        gap = abs(ix[nxt][0, 0] / cl - 1) * 100 if nxt is not None and np.isfinite(ix[nxt][0, 0]) else np.nan
        row = dict(und=und, day=d, vix=vx.get(d, np.nan), dte=g.dte.iloc[0], atr=atr.get(d, np.nan),
                   expiry=bool(g.near_exp.iloc[0] == d),
                   rng=(h - lo) / c10 * 100 if c10 > 0 else np.nan,
                   rv=float(np.sqrt((r ** 2).sum()) * 100) if len(r) > 100 else np.nan,
                   tr=abs(cl - c10) / (h - lo) if h > lo else np.nan, gap=gap,
                   close=cl, next_day=nxt)
        for lab, rr in (("0920", R0920), ("10", R10), ("12", R12), ("1345", R1345), ("1515", R1515)):
            row[f"s1_{lab}"] = g.near_net_cr.get(rr, np.nan)
            row[f"norm_{lab}"] = g.near_norm.get(rr, np.nan)
            row[f"s2_{lab}"] = g.near_s2_cr.get(rr, np.nan)
            row[f"gross_{lab}"] = g.near_gross_cr.get(rr, np.nan)
            row[f"all_{lab}"] = g.all_net_cr.get(rr, np.nan)
        row["wall_1345"] = g.near_wall_abs_strike.get(R1345, np.nan)
        row["spot_1345"] = a[R1345, 3]
        row.update(orb_break(a, atr.get(d, np.nan)))
        rows.append(row)
    return pd.DataFrame(rows)


def orb_break(a: np.ndarray, atr: float) -> dict:
    """First 5-minute close beyond the 09:15-10:00 range on bars 10:00 .. 13:55: RUN (>= 0.5 ATR14 beyond the edge
    before 15:10) or FAIL (a 5-minute close back inside within 6 bars)."""
    if not np.isfinite(atr):
        return dict(brk=0, run=np.nan, fail=np.nan)
    orh, orl = np.nanmax(a[:R10 + 1, 1]), np.nanmin(a[:R10 + 1, 2])
    for end in range(R10 + 5, LAST_DEC + 1, 5):
        c = a[end, 3]
        s = 1 if c > orh else -1 if c < orl else 0
        if not s:
            continue
        edge = orh if s > 0 else orl
        later = a[end + 1:SQ]
        mfe = (np.nanmax(later[:, 1]) - edge) if s > 0 else (edge - np.nanmin(later[:, 2]))
        back = any((s > 0 and a[e, 3] <= orh) or (s < 0 and a[e, 3] >= orl)
                   for e in range(end + 5, min(end + 31, N), 5))
        return dict(brk=1, run=float(mfe >= 0.5 * atr), fail=float(back))
    return dict(brk=0, run=np.nan, fail=np.nan)


# ------------------------------------------------------------------------------------------------ questions
def q1(T: pd.DataFrame) -> list:
    out = []
    for lab in ("0920", "10", "12"):
        for conv, col in (("S1", f"s1_{lab}"), ("S2", f"s2_{lab}"), ("all", f"all_{lab}")):
            df = T.dropna(subset=[col]).assign(neg=lambda x, c=col: x[c] < 0)
            for y in ("rng", "rv", "tr"):
                p, n1, n2 = mw(df[df.neg][y], df[~df.neg][y])
                out.append(dict(q_id="Q1", at=lab, conv=conv, y=y, split="neg vs pos", n=[n1, n2], p=p,
                                neg=float(df[df.neg][y].median()) if n1 else np.nan,
                                pos=float(df[~df.neg][y].median()) if n2 else np.nan,
                                strata=strata(df, "neg", y) if lab == "10" else None))
        # S3 (minus gross): its size only; top vs bottom tercile of the gross
        col = f"gross_{lab}"
        df = T.dropna(subset=[col])
        if len(df) > 30:
            q = df[col].quantile([1 / 3, 2 / 3]).values
            hi, lo = df[df[col] >= q[1]], df[df[col] <= q[0]]
            for y in ("rng", "rv", "tr"):
                p, n1, n2 = mw(hi[y], lo[y])
                out.append(dict(q_id="Q1", at=lab, conv="S3", y=y, split="gross top vs bottom tercile", n=[n1, n2], p=p,
                                neg=float(hi[y].median()), pos=float(lo[y].median()), strata=None))
        # S1 by gex_norm tercile (top vs bottom)
        col = f"norm_{lab}"
        df = T.dropna(subset=[col])
        if len(df) > 30:
            q = df[col].quantile([1 / 3, 2 / 3]).values
            hi, lo = df[df[col] >= q[1]], df[df[col] <= q[0]]
            for y in ("rng", "tr"):
                p, n1, n2 = mw(lo[y], hi[y])
                out.append(dict(q_id="Q1", at=lab, conv="S1 norm", y=y, split="norm bottom vs top tercile", n=[n1, n2],
                                p=p, neg=float(lo[y].median()), pos=float(hi[y].median()), strata=None))
    return out


def q2(T: pd.DataFrame) -> list:
    out = []
    df = T[(T.brk == 1)].dropna(subset=["s1_10"])
    for y in ("run", "fail"):
        a, b = df[df.s1_10 < 0][y], df[df.s1_10 >= 0][y]
        out.append(dict(q_id="Q2", at="10", conv="S1", y=y, split="neg vs pos", n=[len(a), len(b)],
                        neg=float(a.mean()) if len(a) else np.nan, pos=float(b.mean()) if len(b) else np.nan,
                        p=prop(a.sum(), len(a), b.sum(), len(b)),
                        strata=strata(df.assign(neg=df.s1_10 < 0), "neg", y, "mean")))
    return out


def q3(T: pd.DataFrame, step: float) -> list:
    df = T[T.expiry].dropna(subset=["wall_1345", "spot_1345", "close"])
    if len(df) < 10:
        return []
    atm = np.round(df.spot_1345 / step) * step
    dw = (df.close - df.wall_1345).abs() / df.close * 100
    da = (df.close - atm).abs() / df.close * 100
    p = float(SS.wilcoxon(dw, da).pvalue) if (dw != da).sum() >= 10 else np.nan
    out = [dict(q_id="Q3", at="1345", conv="gross wall", y="15:29 close distance to the biggest-gamma strike vs to ATM, %",
                split="paired", n=[len(df)], neg=float(dw.median()), pos=float(da.median()), p=p,
                strata=dict(wall_is_atm=int((df.wall_1345 == atm).sum())))]
    for lab, m in (("S1<0", df.s1_1345 < 0), ("S1>=0", df.s1_1345 >= 0)):
        out[0]["strata"][lab] = dict(n=int(m.sum()), wall=float(dw[m].median()) if m.any() else None,
                                     atm=float(da[m].median()) if m.any() else None)
    return out


def q4(und: str, gx: pd.DataFrame, ix: dict) -> list:
    """Spot crossing the flip (spot_above_flip changes) between 10:00 and 14:59: |move| next 15/30 min vs the same
    minute's mean |move| on all describe days (first cross per 30 minutes)."""
    mv = {15: {}, 30: {}}
    ev = []
    for d, g in gx.groupby("day"):
        if d not in ix:
            continue
        a = ix[d][:, 3]
        ab = g.set_index("minute").near_spot_above_flip.reindex(range(N)).values
        last = -999
        for t in range(R10 + 1, 15 * 60 - 555):
            for h in (15, 30):
                if t + h < N and np.isfinite(a[t]) and np.isfinite(a[t + h]):
                    mv[h].setdefault(t, []).append(abs(np.log(a[t + h] / a[t])) * 1e4)
            if np.isfinite(ab[t]) and np.isfinite(ab[t - 1]) and ab[t] != ab[t - 1] and t - last >= 30:
                ev.append((d, t))
                last = t
    out = []
    for h in (15, 30):
        base = {t: np.mean(v) for t, v in mv[h].items()}
        diffs = []
        for d, t in ev:
            a = ix[d][:, 3]
            if t + h < N and np.isfinite(a[t + h]) and np.isfinite(a[t]):
                diffs.append(abs(np.log(a[t + h] / a[t])) * 1e4 - base[t])
        diffs = np.array(diffs)
        p = float(SS.wilcoxon(diffs).pvalue) if len(diffs) >= 10 else np.nan
        out.append(dict(q_id="Q4", at=f"+{h}m", conv="S1 flip", y="|move| after a flip cross minus the same minute's mean, bp",
                        split="paired", n=[len(diffs)], neg=float(np.mean(diffs)) if len(diffs) else np.nan,
                        pos=0.0, p=p, strata=None))
    return out


def q5(und: str, T: pd.DataFrame) -> list:
    """Expiry afternoons: a 10x blast (an option at Rs 1-10 on the bar closing 13:45 reaching 10x before 15:05) by S1
    sign at 13:45. R25's expiry-day records (r25data), describe days only."""
    if und not in ("NIFTY", "BANKNIFTY", "SENSEX"):
        return []
    sys.path.insert(0, str(K.ROOT / "research" / "hunt" / "r25"))
    import r25data as D25
    s1 = T.set_index("day").s1_1345
    hit = {}
    for d in D25.load(und, "all"):
        if not K.is_describe(d.day) or d.day not in s1.index or not np.isfinite(s1[d.day]):
            continue
        b = False
        for (_, _), a in d.opts.items():
            c = a[R1345, 3]
            if np.isfinite(c) and 1.0 <= c <= 10.0:
                hi = np.nanmax(a[R1345 + 1:15 * 60 + 5 - 555, 1]) if np.isfinite(a[R1345 + 1:350, 1]).any() else np.nan
                if np.isfinite(hi) and hi >= 10 * c:
                    b = True
                    break
        hit[d.day] = (b, s1[d.day] < 0)
    if not hit:
        return []
    v = np.array(list(hit.values()))
    k1, n1 = v[v[:, 1], 0].sum(), v[:, 1].sum()
    k2, n2 = v[~v[:, 1], 0].sum(), (~v[:, 1]).sum()
    return [dict(q_id="Q5", at="1345", conv="S1", y="share of expiry days with a 10x blast", split="neg vs pos",
                 n=[int(n1), int(n2)], neg=float(k1 / n1) if n1 else np.nan, pos=float(k2 / n2) if n2 else np.nan,
                 p=prop(k1, n1, k2, n2), strata=dict(blasts=[int(k1), int(k2)]))]


def q6(T: pd.DataFrame) -> list:
    df = T.dropna(subset=["s1_1515", "gap"]).assign(neg=lambda x: x.s1_1515 < 0)
    p, n1, n2 = mw(df[df.neg].gap, df[~df.neg].gap)
    out = [dict(q_id="Q6", at="1515", conv="S1", y="next morning |gap| %", split="neg vs pos", n=[n1, n2], p=p,
                neg=float(df[df.neg].gap.median()) if n1 else np.nan, pos=float(df[~df.neg].gap.median()) if n2 else np.nan,
                strata=strata(df, "neg", "gap"))]
    d2 = T.dropna(subset=["norm_1515", "gap"])
    if len(d2) > 30:
        q = d2.norm_1515.quantile([1 / 3, 2 / 3]).values
        lo, hi = d2[d2.norm_1515 <= q[0]], d2[d2.norm_1515 >= q[1]]
        p, n1, n2 = mw(lo.gap, hi.gap)
        out.append(dict(q_id="Q6", at="1515", conv="S1 norm", y="next morning |gap| %", split="norm bottom vs top tercile",
                        n=[n1, n2], p=p, neg=float(lo.gap.median()), pos=float(hi.gap.median()), strata=None))
    return out


def bh(ps) -> list:
    ps = np.asarray(ps, float)
    q = np.full(len(ps), np.nan)
    ok = np.isfinite(ps)
    p = ps[ok]
    m = len(p)
    if not m:
        return q.tolist()
    o = np.argsort(p)
    r = np.empty(m)
    r[o] = p[o] * m / (np.arange(m) + 1)
    r[o] = np.minimum.accumulate(r[o][::-1])[::-1]
    q[ok] = np.minimum(r, 1.0)
    return q.tolist()


def coverage(und: str, gx: pd.DataFrame) -> dict:
    x = gx[gx.minute.isin([R10, R1345])].dropna(subset=["near_edge_share"])
    by = {}
    for k, g in x.groupby(np.where(x.dte == 0, "expiry day", np.where(x.dte <= 2, "1-2 days", "3+ days"))):
        by[k] = dict(n=int(len(g)), gamma_edge_median=float(g.near_edge_share.median()),
                     gamma_edge_p90=float(g.near_edge_share.quantile(0.9)), oi_edge_median=float(g.near_oi_edge_share.median()))
    return dict(rows=int(len(x)), gamma_edge_median=float(x.near_edge_share.median()) if len(x) else None,
                oi_edge_median=float(x.near_oi_edge_share.median()) if len(x) else None, by_dte=by,
                flip_found_share=float(gx[gx.minute == R10].near_flip_dist_pct.notna().mean()),
                s1_negative_share_10=float((gx[gx.minute == R10].near_net_cr < 0).mean()))


def main():
    vix = index_days("INDIAVIX")
    vx = vix_prev(vix)
    res, cov, sizes = [], {}, {}
    for und in K.UNDS:
        gx = G.load(und)
        cov[und] = coverage(und, gx)          # coverage is a property of the data, not an outcome: all days
        gx = gx[gx.day.map(K.is_describe)]
        ix = index_days(und)
        T = day_table(und, gx, ix, vx, atr14(ix))
        sizes[und] = dict(days=int(len(T)), first=str(T.day.min()), last=str(T.day.max()),
                          expiry_days=int(T.expiry.sum()), orb_breaks=int(T.brk.sum()))
        rows = q1(T) + q2(T) + q3(T, K.STEP[und]) + q4(und, gx, ix) + q5(und, T) + q6(T)
        for r in rows:
            r["und"] = und
        res += rows
        print(und, sizes[und], flush=True)
    qs = bh([r["p"] for r in res])
    for r, q in zip(res, qs):
        r["q"] = q
    K.RESULTS.mkdir(exist_ok=True)
    out = dict(sizes=sizes, coverage=cov, tests=res, m=int(np.isfinite([r["p"] for r in res]).sum()),
               passed=[f"{r['und']} {r['q_id']} q={r['q']:.3f} {r['at']} {r['conv']} {r['y']}" for r in res if r["q"] is not None
                       and np.isfinite(r["q"]) and r["q"] < K.BH_Q])
    json.dump(_js(out), open(K.RESULTS / "describe.json", "w"), indent=1)
    write_md(out)


def _js(o):
    if isinstance(o, dict):
        return {str(k): _js(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_js(v) for v in o]
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else round(float(o), 5)
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    return o


def _f(x, nd=3):
    return "-" if x is None or (isinstance(x, float) and not np.isfinite(x)) else f"{x:.{nd}f}"


def write_md(out: dict):
    L = ["# R28 Step 2: describe (Step-2 design days only)", "",
         "Generated by `describe.py`. Days: 16 Jan - 17 Dec 2023 and 15 Apr 2025 - 12 Oct 2026 (both holdouts and "
         "embargoes excluded). 'neg' / 'pos' columns are the medians (shares for Q2/Q5) of the first / second group "
         f"of the split. BH across all {out['m']} p-values, q < 0.10 marked **.", "",
         "## Sample sizes", "", "| index | days | first | last | expiry days | ORB breaks |", "|---|---|---|---|---|---|"]
    for u, s in out["sizes"].items():
        L.append(f"| {u} | {s['days']} | {s['first']} | {s['last']} | {s['expiry_days']} | {s['orb_breaks']} |")
    L += ["", "## Coverage gap (all lake days, 10:00 and 13:45 rows; only ATM+-10 is stored)", "",
          "| index | rows | gamma at the edge strikes (median) | OI at the edge (median) | expiry day | 1-2 days | 3+ days | flip found at 10:00 | S1 < 0 at 10:00 |",
          "|---|---|---|---|---|---|---|---|---|"]
    for u, c in out["coverage"].items():
        b = c["by_dte"]
        g = lambda k: _f(b.get(k, {}).get("gamma_edge_median"))  # noqa: E731
        L.append(f"| {u} | {c['rows']} | {_f(c['gamma_edge_median'])} | {_f(c['oi_edge_median'])} | {g('expiry day')} | "
                 f"{g('1-2 days')} | {g('3+ days')} | {_f(c['flip_found_share'], 2)} | {_f(c['s1_negative_share_10'], 2)} |")
    L += ["", "## Tests", "", "| index | Q | at | convention | outcome | split | n | first | second | p | BH q | strata agree |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in out["tests"]:
        st = r.get("strata") or {}
        mark = " **" if r["q"] is not None and r["q"] < K.BH_Q else ""
        n = "/".join(str(x) for x in r["n"])
        L.append(f"| {r['und']} | {r['q_id']} | {r['at']} | {r['conv']} | {r['y']} | {r['split']} | {n} | "
                 f"{_f(r['neg'])} | {_f(r['pos'])} | {_f(r['p'], 4)} | {_f(r['q'], 3)}{mark} | {st.get('agree', '')} |")
    md = "\n".join(L) + "\n"
    (K.RESULTS / "describe.md").write_text(md)


if __name__ == "__main__":
    main()
