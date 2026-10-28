"""R124 part A (PREREG section 4, amendment 1): the zigzag on candle highs/lows, the big-move census and the 20 factors.

Everything is on wicks: a leg runs from the lowest low to the highest high (or the reverse), and the turn-back that
ends it is measured from the extreme's wick. Factors are read from bars before the start, plus the start's own wick
(distances).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

import r124cfg as K
import r123data as D3


# ------------------------------------------------------------------------------------------------ holdout lock
class HoldoutError(RuntimeError):
    pass


def _flag():
    return K.OUT / "HOLDOUT_READ.json"


def holdout_gate(status: str) -> None:
    """R124's ONE holdout read. 'start' refuses after a finished read or a committed results/holdout.json."""
    import json
    from datetime import datetime, timezone
    p = _flag()
    old = json.loads(p.read_text()) if p.exists() else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (K.RESULTS / "holdout.json").exists() or (old and old.get("status") == "done"):
            raise HoldoutError("refused: the R124 holdout was already read")
        rec = dict(status="started", started_at=now, resumed=bool(old))
    else:
        rec = dict(old or {}, status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def holdout_open() -> bool:
    import json
    p = _flag()
    return p.exists() and json.loads(p.read_text()).get("status") == "started"


# ------------------------------------------------------------------------------------------------ the zigzag
def zigzag(h: np.ndarray, l: np.ndarray, rev: float) -> list[dict]:
    """Legs of one session on highs/lows. A leg ends when price comes back `rev` from its extreme (a later bar's low
    for an up leg, a later bar's high for a down leg); `confirm` is that bar. The last leg is unconfirmed (None).
    Bars without a print are skipped. Returns dicts: dir (+1/-1), start, start_px, end, end_px, confirm, size."""
    bars = [i for i in range(len(h)) if np.isfinite(h[i]) and np.isfinite(l[i])]
    legs: list[dict] = []
    if not bars:
        return legs
    i0 = bars[0]
    hi, hi_i, lo, lo_i = h[i0], i0, l[i0], i0
    d = 0
    piv = piv_i = ext = ext_i = None
    for i in bars[1:]:
        if d == 0:
            if h[i] > hi:
                hi, hi_i = h[i], i
            if l[i] < lo:
                lo, lo_i = l[i], i
            if hi - lo >= rev:
                if hi_i > lo_i:
                    d, piv, piv_i, ext, ext_i = 1, lo, lo_i, hi, hi_i
                elif lo_i > hi_i:
                    d, piv, piv_i, ext, ext_i = -1, hi, hi_i, lo, lo_i
                else:                                    # one bar spans both: wait for a later bar
                    continue
            continue
        while True:
            if d == 1:
                if h[i] > ext:
                    ext, ext_i = h[i], i
                    break
                seg = l[ext_i + 1:i + 1]
                if not np.isfinite(seg).any():
                    break
                j = ext_i + 1 + int(np.nanargmin(seg))
                if ext - l[j] >= rev:
                    legs.append(dict(dir=1, start=piv_i, start_px=float(piv), end=ext_i, end_px=float(ext), confirm=i))
                    d, piv, piv_i, ext, ext_i = -1, ext, ext_i, l[j], j
                    continue                             # the new down leg may already have turned (same bars)
                break
            else:
                if l[i] < ext:
                    ext, ext_i = l[i], i
                    break
                seg = h[ext_i + 1:i + 1]
                if not np.isfinite(seg).any():
                    break
                j = ext_i + 1 + int(np.nanargmax(seg))
                if h[j] - ext >= rev:
                    legs.append(dict(dir=-1, start=piv_i, start_px=float(piv), end=ext_i, end_px=float(ext), confirm=i))
                    d, piv, piv_i, ext, ext_i = 1, ext, ext_i, h[j], j
                    continue
                break
    if d != 0:
        legs.append(dict(dir=d, start=piv_i, start_px=float(piv), end=ext_i, end_px=float(ext), confirm=None))
    for g in legs:
        g["size"] = abs(g["end_px"] - g["start_px"])
    return legs


def thresholds(und_open: float, nifty_open: float) -> tuple[float, float]:
    """(move size, turn-back) in this index's points: NIFTY 30 / 10; the others the same share of price."""
    f = und_open / nifty_open if np.isfinite(und_open) and np.isfinite(nifty_open) and nifty_open > 0 else np.nan
    return K.NIFTY_PTS * f, K.REVERSAL_PTS * f


# ------------------------------------------------------------------------------------------------ factors
def _wick(h, l, p, dirn):
    return l[p] if dirn > 0 else h[p]


def factors_at(I, i: int, p: int, dirn: int, thr: float, ctx: dict, ch, others: dict) -> dict:
    """The 20 factors for a (real or control) start at bar p of session i with direction dirn (+1 up, -1 down).
    Reads bars < p of this session (and earlier sessions' daily values), plus the start's own wick (bar p) for the
    distances. ch: the session's option chain (bars <= p-1 are read)."""
    g = I.g
    h, l = g.h[i], g.l[i]
    s = p - 1
    w = _wick(h, l, p, dirn)
    f = {"slot": (p // K.SLOT)}
    with np.errstate(all="ignore"):
        hh, ll = np.nanmax(h[max(s - 29, 0):s + 1]), np.nanmin(l[max(s - 29, 0):s + 1])
        avg20 = float(np.nanmean(I.d_range[max(i - 20, 0):i])) if i > 0 else np.nan
        f["F2"] = ((hh - ll) / w * 100) / avg20 if avg20 and np.isfinite(avg20) else np.nan
        for k, name in ((5, "F3"), (15, "F4")):
            a, b = max(s - k + 1, 0), s + 1
            if dirn > 0:
                f[name] = (w - np.nanmax(h[a:b])) / w * 100
            else:
                f[name] = (np.nanmin(l[a:b]) - w) / w * 100
        o0 = I.d_open[i]
        pc = I.d_close[i - 1] if i > 0 else np.nan
        ph = np.nanmax(g.h[i - 1]) if i > 0 else np.nan
        pl = np.nanmin(g.l[i - 1]) if i > 0 else np.nan
        f["F5"] = abs(w - o0) / thr
        f["F6"] = abs(w - ph) / thr
        f["F7"] = abs(w - pl) / thr
        f["F8"] = abs(w - pc) / thr
        if p >= 15:
            orh, orl = np.nanmax(h[:15]), np.nanmin(l[:15])
            f["F9"] = min(abs(w - orh), abs(w - orl)) / thr
        else:
            f["F9"] = np.nan
        f["F10"] = abs(w - round(w / 100.0) * 100.0) / thr
        vrow = D3.ffill_rows(I.vix[i, :s + 1][None])[0]
        f["F11"] = vrow[-1]
        f["F12"] = D3._lr(vrow[-1], vrow[s - 30]) if s >= 30 else np.nan
        br = []
        for u, (oh, ol) in others.items():
            a = max(s - 14, 0)
            hp, lp = oh[i][p], ol[i][p]
            if dirn > 0 and np.isfinite(lp) and np.isfinite(oh[i][a:s + 1]).any():
                br.append((lp - np.nanmax(oh[i][a:s + 1])) / lp * 100)
            elif dirn < 0 and np.isfinite(hp) and np.isfinite(ol[i][a:s + 1]).any():
                br.append((np.nanmin(ol[i][a:s + 1]) - hp) / hp * 100)
        f["F13"] = float(np.mean(br)) if br else np.nan
    cf = D3.chain_features(ch, s, K.LOT[I.und], w) if s >= 0 else {}
    f["F14"] = cf.get("pcr_chg15", np.nan)
    f["F15"] = cf.get("c_oi_chg15", np.nan)
    f["F16"] = cf.get("p_oi_chg15", np.nan)
    f["F17"] = cf.get("strad_pct", np.nan)
    f["F18"] = float(ctx["is_expiry"])
    f["F19"] = abs(D3._lr(o0, pc)) if np.isfinite(pc) else np.nan
    last = ctx["last_confirm"](p)
    f["F20"] = (p - last[0]) if last is not None else np.nan
    f["F20_cont"] = (float(last[1] == dirn) if last is not None else np.nan)
    return f


# ------------------------------------------------------------------------------------------------ the census
def census(part: str, und: str = "NIFTY", stride: int = 2, log=print) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(moves, rows): every leg of `und` in `part` (with sizes, times, big flag) and the factor rows for every big-move
    start plus control minutes (every `stride`-th minute not within CONTROL_GAP of a big start, a random direction)."""
    if part == "holdout" and not holdout_open():
        raise HoldoutError("the R124 holdout is read only inside r124run.py's one holdout read")
    last_day = K.DESIGN[1] if part == "design" else K.HOLDOUT[1]
    grids = {u: D3.load_grid(u, last_day) for u in K.UNDS}
    vix = D3.load_grid("INDIAVIX", last_day)
    I = D3.IndexData(und, grids, vix)
    nifty = I if und == "NIFTY" else D3.IndexData("NIFTY", grids, vix)
    npos = nifty.dpos
    LO = D3.LakeOptions(und, last_day)
    exp = LO.expiry_days()
    others = {}
    for u in K.UNDS:
        if u == und:
            continue
        gu, pu = grids[u], grids[u].pos()
        oh = np.full((len(I.days), K.N), np.nan)
        ol = np.full((len(I.days), K.N), np.nan)
        for ii, d in enumerate(I.days):
            j = pu.get(d)
            if j is not None:
                oh[ii], ol[ii] = gu.h[j], gu.l[j]
        others[u] = (oh, ol)
    rng = np.random.default_rng(K.SEED)
    moves, rows = [], []
    for i, d in enumerate(I.days):
        if K.part(d) != part:
            continue
        ni = npos.get(d)
        thr, rev = thresholds(I.d_open[i], nifty.d_open[ni] if ni is not None else np.nan)
        if not np.isfinite(thr):
            continue
        legs = zigzag(I.g.h[i], I.g.l[i], rev)
        conf = sorted((g["confirm"], g["dir"]) for g in legs if g["confirm"] is not None and g["size"] >= thr)
        conf_end = {}
        for g in legs:
            if g["confirm"] is not None and g["size"] >= thr:
                conf_end[g["confirm"]] = (g["end"], g["dir"])

        def last_confirm(p, _c=conf_end):
            ks = [c for c in _c if c <= p - 1]
            return _c[max(ks)] if ks else None

        ctx = dict(is_expiry=d in exp, last_confirm=last_confirm)
        opts = LO.day(d, I.g.c[i])
        ch = opts.chain if opts is not None else None
        starts = set()
        for g in legs:
            big = g["size"] >= thr
            moves.append(dict(day=d, und=und, dir=g["dir"], start=g["start"], end=g["end"], confirm=g["confirm"],
                              size_pts=g["size"], size_moves=g["size"] / thr, minutes=g["end"] - g["start"] + 1,
                              big=big, thr=thr))
            if big and g["start"] >= 1:
                starts.add(g["start"])
                f = factors_at(I, i, g["start"], g["dir"], thr, ctx, ch, others)
                rows.append(dict(day=d, p=g["start"], is_start=1, dir=g["dir"], **f))
        near = set()
        for s0 in starts:
            near.update(range(s0 - K.CONTROL_GAP, s0 + K.CONTROL_GAP + 1))
        for p in range(1, 360, stride):
            if p in near or not np.isfinite(I.g.h[i, p]):
                continue
            dirn = 1 if rng.random() < 0.5 else -1
            f = factors_at(I, i, p, dirn, thr, ctx, ch, others)
            rows.append(dict(day=d, p=p, is_start=0, dir=dirn, **f))
    log(f"census {und} {part}: {len(moves)} legs, {sum(m['big'] for m in moves)} big, {len(rows)} factor rows")
    return pd.DataFrame(moves), pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------------ statistics
def factor_stats(R: pd.DataFrame, stride: int, n_boot: int = K.BOOT, seed: int = K.SEED) -> dict:
    """Per factor: start-vs-control standardised difference (within time slot, weighted by starts), quintile lifts
    (within slot), a day-block bootstrap CI and two-sided p. Controls are every `stride`-th minute, so the rate of a
    start per minute is starts / (controls * stride + starts)."""
    out = {}
    days = np.array(sorted(R.day.unique()))
    rng = np.random.default_rng(seed)
    boot_idx = [rng.integers(0, len(days), len(days)) for _ in range(n_boot)]
    by_day = {d: g for d, g in R.groupby("day")}

    def std_diff(df, col):
        tot, wsum = 0.0, 0.0
        for s, g in df.groupby("slot"):
            a, b = g[col][g.is_start == 1].dropna(), g[col][g.is_start == 0].dropna()
            if len(a) < 3 or len(b) < 10:
                continue
            sd = b.std()
            if not sd > 0:
                continue
            tot += len(a) * (a.mean() - b.mean()) / sd
            wsum += len(a)
        return tot / wsum if wsum else np.nan

    for col in [c for c in K.FACTORS if c != "F1"]:
        sd0 = std_diff(R, col)
        bs = []
        for ix in boot_idx[:200]:
            df = pd.concat([by_day[days[k]] for k in ix], ignore_index=True)
            bs.append(std_diff(df, col))
        bs = np.array([b for b in bs if np.isfinite(b)])
        p = float(min(1.0, 2 * min((bs <= 0).mean(), (bs >= 0).mean()) + 1 / (len(bs) + 1))) if len(bs) else 1.0
        # quintile lifts within slot
        lifts = np.zeros(5)
        wt = np.zeros(5)
        for s, g in R.groupby("slot"):
            x = g[col]
            ok = x.notna()
            if ok.sum() < 50:
                continue
            q = pd.qcut(x[ok].rank(method="first"), 5, labels=False)
            st = g.is_start[ok].values
            w = np.where(st == 1, 1.0, float(stride))
            base = st.sum() / w.sum()
            if base <= 0:
                continue
            for k in range(5):
                m = q.values == k
                rate = st[m].sum() / w[m].sum() if w[m].sum() else np.nan
                if np.isfinite(rate):
                    lifts[k] += (rate / base) * w[m].sum()
                    wt[k] += w[m].sum()
        lift = (lifts / np.where(wt > 0, wt, np.nan)).tolist()
        st_ = R[R.is_start == 1][col].dropna()
        co_ = R[R.is_start == 0][col].dropna()
        out[col] = dict(name=K.FACTORS[col], std_diff=float(sd0), ci=[float(np.percentile(bs, 2.5)),
                        float(np.percentile(bs, 97.5))] if len(bs) else None, p=p, lift_q1_to_q5=lift,
                        start_median=float(st_.median()) if len(st_) else None,
                        control_median=float(co_.median()) if len(co_) else None,
                        n_starts=int(len(st_)), n_controls=int(len(co_)))
        if col in K.SIGNED:
            out[col]["note"] = "signed by the coming move (description only; controls signed by a random direction)"
    # F1: rate by slot
    sl = R.groupby("slot").apply(lambda g: g.is_start.sum() / (g.is_start.sum() + stride * (g.is_start == 0).sum()))
    overall = R.is_start.sum() / (R.is_start.sum() + stride * (R.is_start == 0).sum())
    from scipy.stats import chi2_contingency
    tab = R.groupby("slot").is_start.agg(["sum", "count"])
    chi = chi2_contingency(np.column_stack([tab["sum"], (tab["count"] - tab["sum"]) * stride]))
    out["F1"] = dict(name=K.FACTORS["F1"], lift_by_slot={f"{9 + (15 + 30 * int(s)) // 60:02d}:{(15 + 30 * int(s)) % 60:02d}":
                     float(v / overall) for s, v in sl.items()}, p=float(chi[1]))
    # continuation vs reversal of the last confirmed big move
    c = R[(R.is_start == 1)].F20_cont.dropna()
    out["F20"]["continuation_share_of_starts"] = float(c.mean()) if len(c) else None
    out["F20"]["continuation_share_n"] = int(len(c))
    from r123rules import bh
    keys = list(out)
    for k, q in zip(keys, bh([out[k]["p"] for k in keys])):
        out[k]["q"] = q
    return out


def move_stats(M: pd.DataFrame) -> dict:
    B = M[M.big]
    days = M.day.nunique()
    t = B.start.map(lambda s: f"{9 + (15 + 30 * (s // 30)) // 60:02d}:{(15 + 30 * (s // 30)) % 60:02d}")
    return dict(
        sessions=int(days), legs=int(len(M)), big=int(len(B)), big_per_day=float(len(B) / max(days, 1)),
        up_share=float((B.dir > 0).mean()), size_median=float(B.size_pts.median()),
        size_p90=float(B.size_pts.quantile(0.9)), minutes_median=float(B.minutes.median()),
        minutes_p90=float(B.minutes.quantile(0.9)),
        size_buckets={str(k): int(v) for k, v in pd.cut(B.size_moves, [1, 1.5, 2, 3, 5, 100], right=False)
                      .value_counts().sort_index().items()},
        by_start_slot={k: int(v) for k, v in t.value_counts().sort_index().items()},
        days_with_none=int(days - B.day.nunique()))
