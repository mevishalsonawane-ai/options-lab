"""R118 report (PREREG.md): the atlas tables, descriptive only.

    python3 research/hunt/r118_expiry_atlas/report.py      # -> results/atlas.md, results/atlas.json

Reads the cache written by `atlas.py build`. Every table gives n. "H1" = expiry days 2021-23, "H2" = 2024-26. A row is
"stable" when its measure moves the same way in both halves (PREREG): see `stable`.
"""
from __future__ import annotations

import json
import pickle
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import atlas as A  # noqa: E402

RES = HERE / "results"
UNDS = A.R.UNDS
SEED = 118
H1_END = pd.Timestamp("2023-12-31").date()


def m_(hh, mm):
    return A.R.minute(hh, mm)


def hm(t: int) -> str:
    x = t + 9 * 60 + 15
    return f"{x // 60:02d}:{x % 60:02d}"


def half(d) -> str:
    return "H1" if d <= H1_END else "H2"


def fmt(x, nd=2):
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "-"
    if isinstance(x, (int, np.integer)):
        return f"{x:,}"
    return f"{x:,.{nd}f}"


def md(df: pd.DataFrame, nd=2) -> str:
    cols = list(df.columns)
    out = ["| " + " | ".join(str(c) for c in cols) + " |", "|" + "---|" * len(cols)]
    for _, r in df.iterrows():
        out.append("| " + " | ".join(fmt(r[c], nd) if not isinstance(r[c], str) else r[c] for c in cols) + " |")
    return "\n".join(out)


def stable(a: float, b: float, base: float | None = None, n1: int = 99, n2: int = 99) -> str:
    """'stable' if both halves sit on the same side of `base` (or have the same sign when base is None) with n >= 30 days
    each; else 'once'."""
    if n1 < 30 or n2 < 30 or not (np.isfinite(a) and np.isfinite(b)):
        return "small"
    ref = 0.0 if base is None else base
    return "stable" if (a - ref) * (b - ref) > 0 else "once"


# ------------------------------------------------------------------------------------------------ load
def load():
    mk, dy, ix, mins = [], [], [], []
    for u in UNDS:
        mk.append(pd.read_parquet(A.OUT / f"marks_{u}.parquet"))
        dy.append(pd.read_parquet(A.OUT / f"days_{u}.parquet"))
        ix.append(pd.read_parquet(A.OUT / f"index_{u}.parquet"))
        mins += pickle.loads((A.OUT / f"minutes_{u}.pkl").read_bytes())
    mk, dy, ix = (pd.concat(x, ignore_index=True) for x in (mk, dy, ix))
    for x in (mk, dy, ix):
        x["half"] = x.day.map(half)
    dy["weekday"] = pd.to_datetime(dy.day).dt.day_name().str[:3]
    dy["year"] = pd.to_datetime(dy.day).dt.year
    mk["otm"] = mk.dist_em >= 0
    mk["b_em"] = A.em_buckets(mk.dist_em.values)
    mk["spot"] = np.where(mk.side == "C", mk.strike / (1 + mk.dist_pct / 100), mk.strike / (1 - mk.dist_pct / 100))
    return mk, dy, ix, mins


PX_EDGES = [0, 1, 3, 5, 10, 20, 50, np.inf]
PX_LABELS = ["<1", "1-3", "3-5", "5-10", "10-20", "20-50", "50+"]
TIME_SLOTS = [("09:30-10:45", m_(9, 30), m_(10, 45)), ("11:00-12:45", m_(11, 0), m_(12, 45)),
              ("13:00-13:45", m_(13, 0), m_(13, 45)), ("14:00-14:30", m_(14, 0), m_(14, 30)),
              ("14:45-15:15", m_(14, 45), m_(15, 15))]


def px_bucket(px):
    return pd.cut(px, PX_EDGES, labels=PX_LABELS, right=False)


def slot(t):
    for lab, a, b in TIME_SLOTS:
        if a <= t <= b:
            return lab
    return None


# ------------------------------------------------------------------------------------------------ A. census
def sec_a(mk, dy, mins):
    out = ["## A. What is in the data (census and data quality)", ""]
    rows = []
    for u in UNDS:
        d = dy[dy.und == u]
        m = mk[mk.und == u]
        rows.append(dict(index=u, days=len(d), first=str(d.day.min()), last=str(d.day.max()),
                         monthly=int(d.monthly.sum()), strikes_per_day=float(d.n_opts.median()), option_marks=len(m),
                         cleaned_bars=int(d.n_clean.sum()),
                         iv_missing_pct=float(m.iv.isna().mean() * 100),
                         iv_junk_pct=float(((m.iv <= 0) | (m.iv > 300)).mean() * 100),
                         below_intrinsic_pct=float((m.tv < -0.5).mean() * 100)))
    out += [md(pd.DataFrame(rows)), "",
            "- strikes_per_day: stored option series (calls + puts) a day; the lake keeps ATM±10 at each minute.",
            "- option_marks: an option with a print in its last 3 minutes at a 15-minute mark 09:30-15:15.",
            "- iv_junk: vendor IV at 0 or above 300%; below_intrinsic: the close more than Rs 0.50 under intrinsic "
            "value (stale deep-ITM prints).", ""]
    # coverage: share of minutes with no print, by distance bucket and hour
    pres = np.zeros((len(A.EM_LABELS), A.N))
    nop = np.zeros_like(pres)
    for x in mins:
        pres += x["present"]
        nop += x["noprint"]
    hours = [(f"{h:02d}:{'15' if h == 9 else '00'}", m_(h, 15 if h == 9 else 0), m_(h, 59) if h < 15 else A.N - 1)
             for h in range(9, 16)]
    rows = []
    for i, lab in enumerate(A.EM_LABELS):
        r = {"distance (straddles)": lab}
        for hl, a, b in hours:
            p = pres[i, a:b + 1].sum()
            r[hl] = float(nop[i, a:b + 1].sum() / p * 100) if p else np.nan
        rows.append(r)
    out += ["### Minutes with no trade (% of stored option-minutes), by distance and hour", "",
            md(pd.DataFrame(rows), 1), ""]
    # parity
    rows = []
    for u in UNDS:
        arr = np.array([x["parity_bps"] for x in mins if x["und"] == u])
        rows.append({"index": u, **{hm(t): float(np.nanmedian(arr[:, t])) for t in (m_(9, 30), m_(12, 0), m_(15, 0),
                                                                                   m_(15, 25))}})
    out += ["### Put-call parity at the ATM strike: K + call - put - spot, in basis points of spot (median)", "",
            md(pd.DataFrame(rows), 1), "",
            "On expiry day the synthetic forward should equal spot. A positive basis means the call is priced a little "
            "above the put (the futures premium / carry, plus the index's own quoting lag).", ""]
    return out


# ------------------------------------------------------------------------------------------------ B. decay
DECAY_T = [m_(9, 30), m_(10, 30), m_(11, 30), m_(12, 30), m_(13, 30), m_(14, 0), m_(14, 30), m_(15, 0), m_(15, 15),
           m_(15, 25)]


def sec_b(mk, dy, mins):
    out = ["## B. Time decay: how fast the premium melts", ""]
    rows, rows2 = [], []
    for u in (*UNDS, "ALL"):
        xs = [x for x in mins if u == "ALL" or x["und"] == u]
        e = np.array([x["em_pct"] for x in xs])
        base = e[:, m_(9, 30)]
        rows.append({"index": u, "days": len(xs), **{hm(t): float(np.nanmedian(e[:, t])) for t in DECAY_T}})
        rows2.append({"index": u, **{hm(t): float(np.nanmedian(e[:, t] / base) * 100) for t in DECAY_T[1:]}})
    out += ["### The ATM straddle, % of spot (median)", "", md(pd.DataFrame(rows), 3), "",
            "### The straddle left, % of its 09:30 value (median of each day's ratio)", "", md(pd.DataFrame(rows2), 0), ""]
    # by half
    e = {h: np.array([x["em_pct"] for x in mins if half(x["day"]) == h]) for h in ("H1", "H2")}
    r = []
    for h, a in e.items():
        r.append({"half": h, "days": len(a), **{hm(t): float(np.nanmedian(a[:, t] / a[:, m_(9, 30)]) * 100)
                                                for t in DECAY_T[1:]}})
    out += ["By half (all indices):", "", md(pd.DataFrame(r), 0), ""]
    allx = np.array([x["em_pct"] for x in mins])
    rows = []
    for t in DECAY_T[1:]:
        left = (A.N - t) / (A.N - m_(9, 30))
        rows.append({"time": hm(t), "minutes left to 15:30": A.N - t, "straddle left % (median)":
                     float(np.nanmedian(allx[:, t] / allx[:, m_(9, 30)]) * 100), "sqrt(time left) %": np.sqrt(left) * 100,
                     "time left %": left * 100})
    out += ["### Does the straddle decay like the square root of time left? (all days)", "", md(pd.DataFrame(rows), 0),
            "", "Option theory says an ATM straddle is worth about (volatility) x sqrt(time left). Equal columns mean the "
            "market keeps the same volatility through the day.", ""]
    # each OTM option's own premium vs its 09:30 value, by its 09:30 distance
    base = mk[(mk.t == m_(9, 30))][["und", "day", "side", "strike", "px", "b_em", "dist_em"]].rename(
        columns={"px": "px0", "b_em": "b0", "dist_em": "d0"})
    j = mk.merge(base, on=["und", "day", "side", "strike"])
    j["ratio"] = j.px / j.px0
    rows = []
    for b in range(1, len(A.EM_LABELS)):
        g = j[j.b0 == b]
        r = {"09:30 distance (straddles, OTM)": A.EM_LABELS[b], "options": int((g.t == m_(9, 30)).sum())}
        for t in (m_(11, 0), m_(12, 30), m_(13, 30), m_(14, 30), m_(15, 0)):
            r[hm(t)] = float(g[g.t == t].ratio.median() * 100)
        en = g[g.t == m_(9, 30)]
        r["end"] = float((en.y_end / en.px0).median() * 100)
        rows.append(r)
    out += ["### An OTM option's premium, % of its own 09:30 price (median), by its distance at 09:30", "",
            md(pd.DataFrame(rows), 0), "",
            "Read with care: options that drifted out of the stored ATM±10 band drop out of the later columns "
            "(the 'end' column keeps them, valued at intrinsic).", ""]
    # ITM time value
    itm = mk[(mk.dist_em < 0) & (mk.dist_em > -3)]
    r = itm.groupby(itm.t.map(slot)).apply(lambda g: pd.Series(dict(marks=len(g), tv_rs=g.tv.median(),
                                                                    tv_pct_of_px=(g.tv / g.px).median() * 100)))
    out += ["### ITM options (within 3 straddles): time value left", "", md(r.reset_index().rename(columns={"t": "time"}), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ C. priced vs realised
C_T = [m_(9, 30), m_(11, 0), m_(12, 0), m_(13, 0), m_(14, 0), m_(14, 30), m_(15, 0)]


def realised(mins):
    rows = []
    for x in mins:
        sp = x["spot"]
        ok = ~np.isnan(sp)
        if not ok.any():
            continue
        end = sp[ok][-1]
        for t in C_T:
            s = sp[t]
            em = x["em_pct"][t] * s / 100 if s == s else np.nan
            if not (s == s and em == em and em > 0):
                continue
            fut = sp[t + 1:]
            fut = fut[~np.isnan(fut)]
            if not len(fut):
                continue
            rows.append(dict(und=x["und"], day=x["day"], t=t, end_em=abs(end - s) / em, signed_em=(end - s) / em,
                             best_em=max(fut.max() - s, s - fut.min()) / em))
    return pd.DataFrame(rows)


def sec_c(rz):
    out = ["## C. The move the market priced against the move that came", "",
           "At each time t: the ATM straddle (em) against the index's move from t to its last minute ('end') and its "
           "best one-way move after t ('best'), both in straddles. A straddle buyer holding to the close needs end > 1 "
           "(before costs).", ""]
    rows = []
    for t in C_T:
        g = rz[rz.t == t]
        r = {"time": hm(t), "days": len(g), "end: median": g.end_em.median(), "end > 1": (g.end_em > 1).mean() * 100,
             "end > 2": (g.end_em > 2).mean() * 100, "best: median": g.best_em.median(),
             "best > 2": (g.best_em > 2).mean() * 100, "best > 3": (g.best_em > 3).mean() * 100}
        for h in ("H1", "H2"):
            gg = g[g.day.map(half) == h]
            r[f"end > 1 ({h})"] = (gg.end_em > 1).mean() * 100
        r["stable?"] = stable(r["end > 1 (H1)"], r["end > 1 (H2)"], 50.0)
        rows.append(r)
    out += [md(pd.DataFrame(rows), 2), ""]
    rows = []
    for u in UNDS:
        g = rz[(rz.und == u) & (rz.t == m_(12, 0))]
        rows.append({"index": u, "days": len(g), "end median": g.end_em.median(), "end > 1 %": (g.end_em > 1).mean() * 100,
                     "best > 2 %": (g.best_em > 2).mean() * 100, "up minus down (mean signed, straddles)":
                     g.signed_em.mean()})
    out += ["### By index, from 12:00", "", md(pd.DataFrame(rows), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ D. IV
def sec_d(mk, mins):
    out = ["## D. Implied volatility (the vendor's IV column)", ""]
    rows = []
    for u in UNDS:
        a = np.array([x["atm_iv"] for x in mins if x["und"] == u])
        rows.append({"index": u, **{hm(t): float(np.nanmedian(a[:, t])) for t in DECAY_T}})
    out += ["### ATM IV through the day, % (median)", "", md(pd.DataFrame(rows), 1), ""]
    g = mk[(mk.t >= m_(11, 0)) & (mk.t <= m_(13, 0)) & mk.iv_ratio.between(0.05, 20)]
    rows = []
    for b in range(0, len(A.EM_LABELS)):
        r = {"distance (straddles)": A.EM_LABELS[b]}
        for s in ("C", "P"):
            x = g[(g.b_em == b) & (g.side == s)]
            r[f"{s} marks"] = len(x)
            r[f"{s} IV / ATM IV"] = x.iv_ratio.median()
            for h in ("H1", "H2"):
                r[f"{s} {h}"] = x[x.half == h].iv_ratio.median()
        r["puts above calls?"] = stable(r["P H1"] - r["C H1"], r["P H2"] - r["C H2"])
        rows.append(r)
    out += ["### The smile at 11:00-13:00: IV / ATM IV by distance (median)", "", md(pd.DataFrame(rows), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ E. volume and OI
def sec_e(mk, dy, mins):
    out = ["## E. Volume and open interest", ""]
    # volume by 15-minute slot, share of the day
    sl = list(range(0, A.N, 15))
    sh = []
    for x in mins:
        v = x["vol_b"].sum(axis=(0, 1))
        tot = v.sum()
        if tot > 0:
            sh.append([v[a:a + 15].sum() / tot * 100 for a in sl])
    sh = np.array(sh)
    r = pd.DataFrame({"slot": [hm(a) for a in sl], "median % of day's volume": np.median(sh, 0),
                      "mean %": sh.mean(0)})
    out += [f"### When the day's option volume trades (all options, {len(sh)} days)", "", md(r, 1), ""]
    # by distance and side
    vb = sum(x["vol_b"].sum(axis=2) for x in mins)
    tb = sum(x["turn_b"].sum(axis=2) for x in mins)
    rows = []
    for i, lab in enumerate(A.EM_LABELS):
        rows.append({"distance (straddles)": lab, "call volume %": vb[0, i] / vb.sum() * 100,
                     "put volume %": vb[1, i] / vb.sum() * 100, "call turnover %": tb[0, i] / tb.sum() * 100,
                     "put turnover %": tb[1, i] / tb.sum() * 100})
    out += ["### Where the volume is (whole day, all days): units and rupee turnover", "", md(pd.DataFrame(rows), 1), "",
            "Turnover = volume x price: where the money changes hands, not just the count of contracts.", ""]
    # by premium bucket at the marks
    g = mk[mk.otm].copy()
    g["pxb"] = px_bucket(g.px)
    r = g.groupby("pxb", observed=True).agg(marks=("px", "size"), vol5=("vol5", "sum")).reset_index()
    r["share of OTM 5-min volume %"] = r.vol5 / r.vol5.sum() * 100
    out += ["### OTM volume by premium (5-minute volume at the marks)", "",
            md(r[["pxb", "marks", "share of OTM 5-min volume %"]].rename(columns={"pxb": "premium Rs"}), 1), ""]
    # most-traded strike per side at each mark
    idx = mk.groupby(["und", "day", "t", "side"]).vol5.idxmax()
    top = mk.loc[idx.dropna().astype(int)]
    r = top.groupby([top.t.map(slot), "side"]).agg(n=("dist_em", "size"), dist_em=("dist_em", "median"),
                                                     px=("px", "median")).reset_index()
    out += ["### The most-traded strike at each mark: its distance (straddles, + = OTM) and price (median)", "",
            md(r.rename(columns={"t": "time"}), 2), ""]
    # walls
    d = dy.dropna(subset=["em930", "cw930", "pw930"]).copy()
    d["cw_em"] = (d.cw930 - d.s930) / d.em930
    d["pw_em"] = (d.s930 - d.pw930) / d.em930
    rows = []
    for u in (*UNDS, "ALL"):
        x = d if u == "ALL" else d[d.und == u]
        rows.append({"index": u, "days": len(x), "call wall above spot (straddles)": x.cw_em.median(),
                     "put wall below spot (straddles)": x.pw_em.median(), "PCR (OI) 09:30": x.pcr930.median(),
                     "PCR 12:00": x.pcr1200.median()})
    out += ["### OI walls at 09:30 (the strike with the most OI in the stored chain) and the put/call OI ratio", "",
            md(pd.DataFrame(rows), 2), ""]
    # OI change through the day by distance
    g = mk[(mk.t == m_(15, 0))]
    r = g.groupby([g.b_em.map(lambda b: A.EM_LABELS[b] if b >= 0 else "?"), "side"]).oi_chg.agg(["size", "median"])
    out += ["### OI at 15:00 against the same option's OI at 09:30 (median change, by the option's 15:00 distance)", "",
            md(r.reset_index().rename(columns={"b_em": "distance", "size": "options", "median": "OI change"}), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ F. pinning
def sec_f(dy, n_shuffle=500):
    out = ["## F. Does the index get pinned at the close?", ""]
    rng = np.random.default_rng(SEED)
    d = dy.copy()
    rows = []
    for u in (*UNDS, "ALL"):
        x = d if u == "ALL" else d[d.und == u]
        fr = lambda s: np.abs(((s / x.step) + 0.5) % 1 - 0.5)  # noqa: E731  distance to nearest strike, in steps
        rows.append({"index": u, "days": len(x), "close": fr(x.s_end).mean(), "13:00 (placebo)": fr(x.s1300).mean(),
                     "12:00 (placebo)": fr(x.s1200).mean(), "close within 10% of a step %": (fr(x.s_end) < 0.1).mean() * 100})
    out += ["### Distance of the index from the nearest strike, in strike steps (0.25 = no pinning, uniform)", "",
            md(pd.DataFrame(rows), 3), ""]
    # pull toward max pain / walls
    rows = []
    for lab, s0, x0, e0 in (("max pain at 09:30", "s930", "mp930", "em930"), ("max pain at 12:00", "s1200", "mp1200", "em1200"),
                            ("call wall at 09:30", "s930", "cw930", "em930"), ("put wall at 09:30", "s930", "pw930", "em930")):
        x = d.dropna(subset=[s0, x0, e0])
        x = x[x[x0] != x[s0]]
        pull = ((x.s_end - x[s0]) * np.sign(x[x0] - x[s0]) / x[e0]).values
        off = (x[x0] - x[s0]).values
        # placebo: the same index's offsets from other days
        pl = []
        for _ in range(n_shuffle):
            o = np.empty_like(off)
            for u in x.und.unique():
                ii = np.nonzero((x.und == u).values)[0]
                o[ii] = rng.permutation(off[ii])
            pl.append(np.mean((x.s_end.values - x[s0].values) * np.sign(o) / x[e0].values))
        pl = np.array(pl)
        h = {hh: ((x.s_end - x[s0]) * np.sign(x[x0] - x[s0]) / x[e0])[x.half == hh].mean() for hh in ("H1", "H2")}
        rows.append({"target": lab, "days": len(x), "mean pull (straddles)": pull.mean(),
                     "moved toward it %": (pull > 0).mean() * 100,
                     "placebo 2.5-97.5%": f"{np.percentile(pl, 2.5):+.3f} .. {np.percentile(pl, 97.5):+.3f}",
                     "H1": h["H1"], "H2": h["H2"], "stable?": stable(h["H1"], h["H2"])})
    out += ["### Does the index move toward max pain / the OI walls after 09:30 or 12:00?", "",
            "pull = the index's move from then to the close, in straddles, signed + toward the target. The placebo gives "
            "each day another day's offset (same index), so it shows what a random target would get.", "",
            md(pd.DataFrame(rows), 3), ""]
    x = d.dropna(subset=["cw930", "pw930"])
    inside = ((x.s_end >= x.pw930) & (x.s_end <= x.cw930)).mean() * 100
    start = ((x.s930 >= x.pw930) & (x.s930 <= x.cw930)).mean() * 100
    out += [f"- The close ended between the 09:30 put wall and call wall on {inside:.0f}% of {len(x)} days "
            f"(the 09:30 index was between them on {start:.0f}%).", ""]
    return out


# ------------------------------------------------------------------------------------------------ G. index expiry vs normal
def sec_g(ix):
    out = ["## G. The index on expiry days against normal days (index minutes, same years)", ""]
    rows = []
    for u in UNDS:
        x = ix[ix.und == u]
        e = x[x.expiry]
        x = x[(x.day >= e.day.min()) & (x.day <= e.day.max())]
        for lab, g in (("expiry", x[x.expiry]), ("normal", x[~x.expiry])):
            rows.append({"index": u, "days": lab, "n": len(g), "range %": g.range_pct.median(), "trend (|C-O|/range)":
                         g.trend.median(), "last 60 min move %": g.last60_pct.median(),
                         "last 30 min move %": g.last30_pct.median(), "first-hour range %": g.first60_range_pct.median(),
                         "high or low in last hour %": ((g.t_high >= m_(14, 30)) | (g.t_low >= m_(14, 30))).mean() * 100,
                         "high or low in first hour %": ((g.t_high < 60) | (g.t_low < 60)).mean() * 100})
    out += [md(pd.DataFrame(rows), 2), ""]
    rows = []
    for h in ("H1", "H2"):
        for lab, flag in (("expiry", True), ("normal", False)):
            g = ix[(ix.half == h) & (ix.expiry == flag)]
            rows.append({"half": h, "days": lab, "n": len(g), "range %": g.range_pct.median(),
                         "last 60 min move %": g.last60_pct.median(), "last 30 min move %": g.last30_pct.median()})
    out += ["By half (all indices; 'normal' = every other session of the lake years):", "", md(pd.DataFrame(rows), 3), ""]
    return out


# ------------------------------------------------------------------------------------------------ H. option paths
def sec_h(mk):
    out = ["## H. What happens to an option after each mark (OTM options, outcomes are AFTER the mark)", "",
           "x2 / x5 / x10: the option's value (its close, never below intrinsic) later reaches 2 / 5 / 10 times its price "
           "at the mark, at any minute to 15:29. 'dies' = ends under Rs 0.10. 'end/now' = median end value / price.", ""]
    g = mk[mk.otm].copy()
    g["pxb"] = px_bucket(g.px)
    g["slot"] = g.t.map(slot)
    for k in (2, 5, 10):
        g[f"x{k}"] = g.y_max >= k * g.px
    g["dies"] = g.y_end < 0.10
    g["endr"] = g.y_end / g.px
    rows = []
    for (s, b), x in g.groupby(["slot", "pxb"], observed=True):
        rows.append({"time": s, "premium Rs": b, "marks": len(x), "days": x[["und", "day"]].drop_duplicates().shape[0],
                     "x2 %": x.x2.mean() * 100, "x5 %": x.x5.mean() * 100, "x10 %": x.x10.mean() * 100,
                     "dies %": x.dies.mean() * 100, "end/now": x.endr.median(),
                     "x2 H1 %": x[x.half == "H1"].x2.mean() * 100, "x2 H2 %": x[x.half == "H2"].x2.mean() * 100})
    out += ["### By time and premium", "", md(pd.DataFrame(rows), 1), "",
            "Under Rs 1 a single tick (Rs 0.05) is a 5-100% move, so the '<1' doubling rates are mostly tick noise, "
            "worst in the last half hour.", ""]
    rows = []
    for (s, b), x in g.groupby(["slot", "b_em"]):
        rows.append({"time": s, "distance (straddles)": A.EM_LABELS[b], "marks": len(x), "median Rs": x.px.median(),
                     "x2 %": x.x2.mean() * 100, "x5 %": x.x5.mean() * 100, "x10 %": x.x10.mean() * 100,
                     "dies %": x.dies.mean() * 100})
    out += ["### By time and distance", "", md(pd.DataFrame(rows), 1), ""]
    # when the best price comes
    x = g[g.x2]
    tb = pd.cut(x.y_tmax, [0, m_(11, 0), m_(13, 0), m_(14, 0), m_(14, 30), m_(15, 0), A.N],
                labels=["<11:00", "11-13", "13-14", "14-14:30", "14:30-15", "15:00+"], right=False)
    r = (tb.value_counts(normalize=True).sort_index() * 100).reset_index()
    r.columns = ["time of the best value", "% of the OTM marks that later doubled"]
    out += [f"### When options that doubled hit their best value ({len(x):,} marks)", "", md(r, 1), ""]
    # empirical delta and leverage over the next 5 minutes
    y = mk[mk.y_px5.notna() & mk.y_s5.notna() & (mk.dist_em > -2)].copy()
    ds = y.y_s5 - y.spot
    y = y[(ds.abs() / y.spot) > 0.0003]
    ds = (y.y_s5 - y.spot) * np.where(y.side == "C", 1, -1)
    y["delta"] = (y.y_px5 - y.px) / ds
    y["lev"] = ((y.y_px5 - y.px) / y.px) / (ds / y.spot)
    y["slot"] = y.t.map(slot)
    r = y[y.px >= 1].groupby(["slot", y.b_em.map(lambda b: A.EM_LABELS[b])]).agg(
        n=("delta", "size"), delta=("delta", "median"), leverage=("lev", "median")).reset_index()
    out += ["### Empirical delta and leverage over the next 5 minutes (options priced Rs 1+, index move > 0.03%)", "",
            "delta = option rupees per index point in the option's direction; leverage = option % move per index % move.",
            "", md(r.rename(columns={"b_em": "distance"}), 2), ""]
    return out


# ------------------------------------------------------------------------------------------------ I. calls vs puts
def sec_i(mk, rz):
    out = ["## I. Calls against puts", ""]
    g = mk[mk.otm & (mk.t == m_(12, 0))].copy()
    g["prem_em"] = g.px / (g.em_pct * g.spot / 100)
    for k in (2, 10):
        g[f"x{k}"] = g.y_max >= k * g.px
    rows = []
    for b in range(1, len(A.EM_LABELS)):
        r = {"12:00 distance (straddles)": A.EM_LABELS[b]}
        for s in ("C", "P"):
            x = g[(g.b_em == b) & (g.side == s)]
            r[f"{s} n"] = len(x)
            r[f"{s} premium / straddle"] = x.prem_em.median()
            r[f"{s} x2 %"] = x.x2.mean() * 100
            r[f"{s} x10 %"] = x.x10.mean() * 100
        rows.append(r)
    out += ["### OTM calls and puts at the same distance at 12:00", "", md(pd.DataFrame(rows), 3), ""]
    z = rz[rz.t == m_(12, 0)]
    out += [f"- From 12:00 the index ended up on {(z.signed_em > 0).mean() * 100:.0f}% of {len(z)} days; the mean "
            f"signed move was {z.signed_em.mean():+.3f} straddles (H1 {z[z.day.map(half) == 'H1'].signed_em.mean():+.3f}, "
            f"H2 {z[z.day.map(half) == 'H2'].signed_em.mean():+.3f}).", ""]
    return out


# ------------------------------------------------------------------------------------------------ J. conditions
def day_measures(mk, dy, rz):
    d = dy.copy()
    z = rz[rz.t == m_(12, 0)].set_index(["und", "day"])
    d = d.join(z[["end_em", "best_em"]], on=["und", "day"])
    g = mk[mk.otm & (mk.t >= m_(11, 0)) & mk.px.between(1, 10)]
    hero = g[g.y_max >= 10 * g.px].groupby(["und", "day"]).size()
    d["hero10"] = [((u, dd) in hero.index) for u, dd in zip(d.und, d.day)]
    d["vix_b"] = pd.cut(d.vix_prev, [0, 12, 14, 17, 22, 100], labels=["<12", "12-14", "14-17", "17-22", "22+"])
    d["gap_b"] = pd.cut(d.gap_pct, [-100, -1, -0.5, 0.5, 1, 100], labels=["<=-1%", "-1..-0.5", "flat", "+0.5..1", ">=+1%"])
    return d


def sec_j(d):
    out = ["## J. Does anything at the open change the day? (day-level)", "",
           "end>1: the index's move 12:00-close exceeded the 12:00 straddle. hero10: some OTM option priced Rs 1-10 at a "
           "mark from 11:00 later reached 10x. range: the index's high-low in % of the open.", ""]
    for col, lab in (("und", "index"), ("weekday", "weekday"), ("monthly", "monthly expiry"), ("year", "year"),
                     ("vix_b", "previous-close VIX"), ("gap_b", "opening gap")):
        rows = []
        for k, g in d.groupby(col, observed=True):
            h1, h2 = g[g.half == "H1"], g[g.half == "H2"]
            rows.append({lab: str(k), "days": len(g), "end>1 %": (g.end_em > 1).mean() * 100,
                         "hero10 %": g.hero10.mean() * 100, "range % (median)": g.range_pct.median(),
                         "straddle 09:30 % of spot": (g.em930 / g.s930 * 100).median(),
                         "hero10 H1 %": h1.hero10.mean() * 100 if len(h1) else np.nan,
                         "hero10 H2 %": h2.hero10.mean() * 100 if len(h2) else np.nan,
                         "n H1/H2": f"{len(h1)}/{len(h2)}"})
        out += [f"### By {lab}", "", md(pd.DataFrame(rows), 1), ""]
    base = d.hero10.mean() * 100
    out += [f"All days: hero10 on {base:.1f}% of {len(d)} days; end>1 on {(d.end_em > 1).mean() * 100:.1f}%.", ""]
    return out


# ------------------------------------------------------------------------------------------------ K. stability
def sec_k(mk, dy, ix, mins, rz):
    """The headline claims, each measured in both halves."""
    out = ["## K. The headline patterns, checked in both halves (H1 2021-23, H2 2024-26)", ""]
    rows = []

    def add(claim, f, base, unit=""):
        v = {h: f(h) for h in ("H1", "H2")}
        n = {h: int((dy.half == h).sum()) for h in ("H1", "H2")}
        rows.append({"claim": claim, "H1": v["H1"], "H2": v["H2"], "test": f"vs {base}{unit}",
                     "stable?": stable(v["H1"], v["H2"], base, n["H1"], n["H2"])})
    z = rz[rz.t == m_(12, 0)]
    add("index moved more than the 12:00 straddle by the close, % of days",
        lambda h: (z[z.day.map(half) == h].end_em > 1).mean() * 100, 50.0, "%")
    for t in (m_(12, 30), m_(14, 30), m_(15, 0)):
        left = np.sqrt((A.N - t) / (A.N - m_(9, 30))) * 100
        add(f"straddle left at {hm(t)} (% of 09:30) minus sqrt(time left) {left:.0f}%",
            lambda h, t=t, left=left: float(np.nanmedian(np.array([x["em_pct"][t] / x["em_pct"][m_(9, 30)]
                                                                  for x in mins if half(x["day"]) == h])) * 100 - left),
            0.0, " points")
    g = mk[(mk.t >= m_(11, 0)) & (mk.t <= m_(13, 0)) & mk.iv_ratio.between(0.05, 20) & mk.b_em.between(3, 6)]
    add("OTM put IV / ATM IV minus call's, 0.5-3 straddles out, 11:00-13:00",
        lambda h: g[(g.half == h) & (g.side == "P")].iv_ratio.median() - g[(g.half == h) & (g.side == "C")].iv_ratio.median(),
        0.0)
    d = dy.dropna(subset=["em930", "cw930", "pw930"])
    add("call wall distance minus put wall distance at 09:30 (straddles)",
        lambda h: ((d.cw930 - d.s930) / d.em930)[d.half == h].median() - ((d.s930 - d.pw930) / d.em930)[d.half == h].median(),
        0.0)
    add("put/call OI ratio at 09:30 minus 1", lambda h: d[d.half == h].pcr930.median() - 1, 0.0)
    g = mk[(mk.t == m_(15, 0)) & mk.dist_em.between(0, 1)]
    add("near-money OTM OI at 15:00 vs the same option at 09:30 (median change)",
        lambda h: g[g.half == h].oi_chg.median(), 0.0)

    def vol_share(h, a, b):
        sh = []
        for x in mins:
            if half(x["day"]) != h:
                continue
            v = x["vol_b"].sum(axis=(0, 1))
            if v.sum() > 0:
                sh.append(v[a:b].sum() / v.sum())
        return float(np.median(sh) * 100)
    add("share of the day's option volume 14:00-15:15 minus 09:15-10:30 (points)",
        lambda h: vol_share(h, m_(14, 0), m_(15, 15)) - vol_share(h, 0, m_(10, 30)), 0.0)
    add("close's distance to the nearest strike (steps) minus 0.25 (pinning if < 0)",
        lambda h: float(np.abs(((dy.s_end / dy.step) + 0.5) % 1 - 0.5)[dy.half == h].mean() - 0.25), 0.0)
    add("pull toward 09:30 max pain (straddles)",
        lambda h: float(((dy.s_end - dy.s930) * np.sign(dy.mp930 - dy.s930) / dy.em930)[dy.half == h].mean()), 0.0)

    def last30(h):
        x = ix[ix.half == h]
        return x[x.expiry].last30_pct.median() - x[~x.expiry].last30_pct.median()
    add("last 30 minutes' index move, expiry minus normal days (% of spot)", last30, 0.0)
    g = mk[mk.otm & (mk.t == m_(12, 0)) & mk.b_em.between(4, 6)].copy()
    g["x10"] = g.y_max >= 10 * g.px
    add("12:00 OTM 1-3 straddles out: calls reaching 10x minus puts (points)",
        lambda h: (g[(g.half == h) & (g.side == "C")].x10.mean() - g[(g.half == h) & (g.side == "P")].x10.mean()) * 100, 0.0)
    g2 = mk[mk.otm & (mk.y_max >= 2 * mk.px) & (mk.px >= 1)]
    add("OTM options (Rs 1+) that later doubled: % with their best value after 15:00",
        lambda h: (g2[g2.half == h].y_tmax >= m_(15, 0)).mean() * 100, 25.0, "%")
    out += [md(pd.DataFrame(rows), 3), ""]
    return out


def main():
    mk, dy, ix, mins = load()
    rz = realised(mins)
    d = day_measures(mk, dy, rz)
    parts = ["# R118 atlas: every option on every expiry day (generated by report.py)", "",
             f"Expiry days: {len(dy)} ({', '.join(f'{u} {int((dy.und == u).sum())}' for u in UNDS)}); option-marks: "
             f"{len(mk):,}; H1 = 2021-23 ({int((dy.half == 'H1').sum())} days), H2 = 2024-26 "
             f"({int((dy.half == 'H2').sum())} days). Signal source: none (descriptive); the index's minutes and the "
             "options' own prints.", ""]
    for f in (lambda: sec_a(mk, dy, mins), lambda: sec_b(mk, dy, mins), lambda: sec_c(rz), lambda: sec_d(mk, mins),
              lambda: sec_e(mk, dy, mins), lambda: sec_f(dy), lambda: sec_g(ix), lambda: sec_h(mk),
              lambda: sec_i(mk, rz), lambda: sec_j(d), lambda: sec_k(mk, dy, ix, mins, rz)):
        parts += f()
    RES.mkdir(exist_ok=True)
    (RES / "atlas.md").write_text("\n".join(parts) + "\n")
    print("\n".join(parts))


if __name__ == "__main__":
    main()
