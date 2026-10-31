"""R139: market structure (higher highs / higher lows, lower highs / lower lows), 12 pre-registered rules (PREREG.md),
buying the ATM option.

    python research/hunt/r139_market_structure/r139.py design    # -> results/design.json
    python research/hunt/r139_market_structure/r139.py holdout   # the ONE holdout read -> results/holdout.json

Signal source: the index spot (wicks for every swing, stop, target and touch; 15-minute closes only where a rule says
"closes above"). The option is only what is bought. Reuses R131 (bars, wick pivots, mirrored sides, the option rule,
costs, wick exits, twins) and R132 Amendment A (R123's N1 network filter).

Every detector is written for the BULLISH case on one side's coordinates. The bearish case (lower highs, lower lows,
buy the put) is the same code on R131's mirrored side (high -> -low), so both sides follow one rule.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
import r132nn as NN  # noqa: E402

R = NN.R
K, D3 = R.K, R.D3
N = R.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R139_OUT", K.K3.CACHE_ROOT / "r139")).expanduser()
TICK = 0.05
TF = 15                      # the structure chart
TF_FAST, TF_SLOW = 5, 60     # S7: the 60-minute trend and the 5-minute entry
K_SLOW = 2                   # pivots on the 60-minute chart: 2 bars each side (only 6 bars a session)
SESSIONS_BACK = R.SESSIONS_BACK
MSS_WATCH = 24               # S6: the first higher low must come within 24 bars (6 hours) of the shift
LEG_ATR = 1.0                # S9 / S10: the leg is at least 1 x ATR14
FIRST_HOUR_LAST = K.row(10, 15)
TWINS = 100
SEED = 139

VARIANTS = {
    "S1": dict(det="hl", label="Dow pullback: in a 15-min HH+HL trend, buy when a new higher low is confirmed "
                               "(mirror: buy the put when a new lower high is confirmed in a LH+LL trend)"),
    "S2": dict(det="bos", label="BOS continuation: in a 15-min HH+HL trend, the first 15-min close above the last "
                                "swing high; stop below the last higher low"),
    "S3": dict(det="choch", label="CHoCH reversal: in a 15-min LH+LL trend, the first 15-min close above the last "
                                  "lower high; stop below the lowest low since it"),
    "S4": dict(det="r123", label="1-2-3 reversal (Sperandeo / Ross): LL (1), LH (2), HL (3); buy when a wick takes "
                                 "out point 2; stop below point 3"),
    "S5": dict(det="mss_hl", label="MSS then the first higher low (ICT): after S3's shift, buy when the next swing low "
                                   "above the shift's low is confirmed"),
    "S6": dict(det="mtf", label="60-min HH+HL trend + 5-min CHoCH back in its direction (multi-timeframe)"),
    "S7": dict(det="sfp", label="Swing failure: a 15-min wick below the last swing low closes back above it"),
    "S8": dict(det="eq", label="Equilibrium (ICT discount): after a BOS leg, buy the first touch of the leg's 50%; "
                               "stop below the leg low; target the leg high"),
    "S9": dict(det="shallow", label="Shallow pullback (R135): after a BOS leg, buy the first touch of 25% back; "
                                    "stop beyond 50%; target the leg high"),
    "S10": dict(det="bos", last_entry=FIRST_HOUR_LAST, label="BOS continuation, first hour only (entries to 10:15)"),
    "S11": dict(det="lh_ll", label="Lower-high-after-lower-low: after a 15-min lower low breaks the trend, buy the "
                                   "put when the first lower high is confirmed (mirror: first higher low)"),
    "S12": dict(det="hl", nn=True, label="S1 + R123's N1 network expected-value filter (2023 on)"),
}
GATES = dict(n=300, folds=3, q=0.10, dd=-40000.0, month=-13000.0, holdout_n=50)


@dataclass
class C:
    d: int                       # session index
    r0: int                      # entry minute row (bought at its open)
    stop: float                  # this side's coordinates
    tgt: float | None = None     # None: 2R from the entry open


# ------------------------------------------------------------------------------------------------ structure
def structure(ch: R.Chart, depth: int = 3) -> tuple[np.ndarray, np.ndarray]:
    """The swing points known at the CLOSE of every bar t: Hs[t, j] / Ls[t, j] = the bar index of the (j+1)-th most
    recent confirmed swing high / low (-1 if none). A pivot i is confirmed at the close of bar i + k. Swings older than
    the previous SESSIONS_BACK sessions are dropped (R131)."""
    T, k = ch.T, ch.k
    Hs = np.full((T, depth), -1, dtype=np.int64)
    Ls = np.full((T, depth), -1, dtype=np.int64)
    conf_h = {int(i) + k: int(i) for i in ch.sh}
    conf_l = {int(i) + k: int(i) for i in ch.sl}
    hs: list = []
    ls: list = []
    dprev = None
    for t in range(T):
        d = int(ch.day[t])
        if d != dprev:
            hs = [i for i in hs if ch.day[i] >= d - SESSIONS_BACK]
            ls = [i for i in ls if ch.day[i] >= d - SESSIONS_BACK]
            dprev = d
        if t in conf_h and ch.day[conf_h[t]] >= d - SESSIONS_BACK:
            hs.append(conf_h[t])
        if t in conf_l and ch.day[conf_l[t]] >= d - SESSIONS_BACK:
            ls.append(conf_l[t])
        for j in range(min(depth, len(hs))):
            Hs[t, j] = hs[-1 - j]
        for j in range(min(depth, len(ls))):
            Ls[t, j] = ls[-1 - j]
    return Hs, Ls


def trend(ch: R.Chart, Hs: np.ndarray, Ls: np.ndarray) -> np.ndarray:
    """Dow's trend at the close of every bar: +1 = the last two swing highs AND the last two swing lows rise (HH + HL);
    -1 = both fall (LH + LL); 0 = mixed or not enough swings."""
    ok = (Hs[:, 1] >= 0) & (Ls[:, 1] >= 0)
    h0, h1 = ch.h[np.maximum(Hs[:, 0], 0)], ch.h[np.maximum(Hs[:, 1], 0)]
    l0, l1 = ch.l[np.maximum(Ls[:, 0], 0)], ch.l[np.maximum(Ls[:, 1], 0)]
    up = ok & (h0 > h1) & (l0 > l1)
    dn = ok & (h0 < h1) & (l0 < l1)
    return np.where(up, 1, np.where(dn, -1, 0))


def _entry(ch: R.Chart, t: int) -> int:
    return int(ch.B.end[t]) + 1


def _first_close_above(ch: R.Chart, i: int, t: int) -> bool:
    """Bar t is the first bar after pivot i to close above the pivot's high."""
    lv = ch.h[i]
    return bool(ch.c[t] > lv and (ch.c[i + 1:t] <= lv).all())


# ------------------------------------------------------------------------------------------------ detectors (bullish)
def det_hl(S: R.Side) -> list:
    """S1: a new swing low is confirmed at bar t and, with it, the trend is HH + HL. Stop 0.02% below that low."""
    ch = S.chart(TF)
    Hs, Ls = structure(ch)
    tr = trend(ch, Hs, Ls)
    out = []
    for t in range(1, ch.T):
        i = Ls[t, 0]
        if i >= 0 and i + ch.k == t and tr[t] == 1:
            out.append(C(int(ch.day[t]), _entry(ch, t), R.stop_below(float(ch.l[i]))))
    return out


def det_lh_ll(S: R.Side) -> list:
    """S11 (bullish code = the mirror of Boss's lower-high sell): the trend was LH + LL or mixed, a swing high made a
    HIGHER high (the first break of the down-structure), and now the first swing low confirmed after that higher high
    is a HIGHER low (above the last low). In the put's coordinates: a lower low, then the first lower high. Stop below
    the new higher low."""
    ch = S.chart(TF)
    Hs, Ls = structure(ch)
    tr = trend(ch, Hs, Ls)
    out = []
    for t in range(1, ch.T):
        i = Ls[t, 0]
        if i < 0 or i + ch.k != t or Ls[t, 1] < 0 or Hs[t, 1] < 0:
            continue
        hh = Hs[t, 0]
        if not (hh < i and Ls[t, 1] < hh):            # the order: low, higher high, then this low
            continue
        if not (ch.h[hh] > ch.h[Hs[t, 1]] and ch.l[i] > ch.l[Ls[t, 1]]):
            continue
        tb = int(hh) + ch.k                           # the bar when the higher high was confirmed
        if tb - 1 < 0 or tr[tb - 1] == 1:              # the trend before that higher high was not already up
            continue
        out.append(C(int(ch.day[t]), _entry(ch, t), R.stop_below(float(ch.l[i]))))
    return out


def det_bos(S: R.Side) -> list:
    """S2 / S10: the trend at bar t-1 is HH + HL; bar t is the first close above the last swing high. Stop 0.02% below
    the last swing low (the higher low)."""
    ch = S.chart(TF)
    Hs, Ls = structure(ch)
    tr = trend(ch, Hs, Ls)
    out = []
    for t in range(1, ch.T):
        if tr[t - 1] != 1:
            continue
        i = Hs[t - 1, 0]
        if _first_close_above(ch, i, t):
            out.append(C(int(ch.day[t]), _entry(ch, t), R.stop_below(float(ch.l[Ls[t - 1, 0]]))))
    return out


def det_choch(S: R.Side, ch: R.Chart | None = None, keep_low: bool = False) -> list:
    """S3: the trend at bar t-1 is LH + LL; bar t is the first close above the last swing high (a lower high). Stop
    0.02% below the lowest low from that high to bar t."""
    ch = S.chart(TF) if ch is None else ch
    Hs, Ls = structure(ch)
    tr = trend(ch, Hs, Ls)
    out = []
    for t in range(1, ch.T):
        if tr[t - 1] != -1:
            continue
        i = Hs[t - 1, 0]
        if _first_close_above(ch, i, t):
            low = float(ch.l[i:t + 1].min())
            c = C(int(ch.day[t]), _entry(ch, t), R.stop_below(low))
            out.append((c, t, low) if keep_low else c)
    return out


def det_r123(S: R.Side) -> list:
    """S4, Sperandeo's 1-2-3 / Ross's: at the close of bar t-1 the swings are point 1 = a lower low (below the low
    before it), point 2 = the swing high after it, a lower high (below the high before point 1), point 3 = the swing
    low after point 2, above point 1. Entry: the first 1-minute wick one tick above point 2 (a buy-stop), in bar t, with
    no earlier bar above point 2 and no bar below point 3 since it. Stop 0.02% below point 3. One trade per point 2."""
    ch = S.chart(TF)
    Hs, Ls = structure(ch)
    out, used = [], set()
    for t in range(1, ch.T):
        p3, p1, p0 = Ls[t - 1, 0], Ls[t - 1, 1], Ls[t - 1, 2]
        p2, hb = Hs[t - 1, 0], Hs[t - 1, 1]
        if min(p3, p1, p0, p2, hb) < 0 or p2 in used:
            continue
        if not (p0 < p1 < p2 < p3 and hb < p1):
            continue
        if not (ch.l[p1] < ch.l[p0] and ch.h[p2] < ch.h[hb] and ch.l[p3] > ch.l[p1]):
            continue
        lv = float(ch.h[p2]) + TICK
        if ch.h[p2 + 1:t].max(initial=-np.inf) >= lv or ch.l[p3 + 1:t].min(initial=np.inf) <= ch.l[p3] - TICK:
            continue
        d = int(ch.day[t])
        a, b = int(ch.B.start[t]), int(ch.B.end[t])
        seg = S.mh[d][a:b + 1]
        hit = np.flatnonzero(np.isfinite(seg) & (seg >= lv))
        if not len(hit):
            continue
        r = a + int(hit[0])
        used.add(p2)
        out.append(C(d, r + 1, R.stop_below(float(ch.l[p3]))))
    return out


def det_mss_hl(S: R.Side) -> list:
    """S5: after an S3 shift at bar c (its low Lx), the first swing low confirmed after bar c, within MSS_WATCH bars,
    whose low is above Lx. Cancelled if a low goes below Lx first. Stop 0.02% below that new low."""
    ch = S.chart(TF)
    _, Ls = structure(ch)
    out = []
    for _, c, lx in det_choch(S, ch, keep_low=True):
        for t in range(c + 1, min(c + MSS_WATCH, ch.T - 1) + 1):
            if ch.l[t] <= lx - TICK:
                break
            i = Ls[t, 0]
            if i >= 0 and i + ch.k == t and i > c - ch.k:
                if ch.l[i] > lx:
                    out.append(C(int(ch.day[t]), _entry(ch, t), R.stop_below(float(ch.l[i]))))
                break
    return out


def det_mtf(S: R.Side) -> list:
    """S6: the 60-minute trend (pivots of 2 bars each side), as of the last 60-minute bar CLOSED by the end of the
    5-minute bar, is HH + HL; the 5-minute chart gives an S3 shift up (its trend was LH + LL, then the first close above
    its last lower high). Stop 0.02% below the 5-minute low since that lower high."""
    slow = R.Chart(R.make_bars(S.mo, S.mh, S.ml, S.mc, TF_SLOW), k=K_SLOW)
    if not slow.T:
        return []
    Hs, Ls = structure(slow)
    tr_slow = trend(slow, Hs, Ls)
    fast = S.chart(TF_FAST)
    out = []
    for c, t, _ in det_choch(S, fast, keep_low=True):
        j = int(np.searchsorted(slow.gend, fast.gend[t], side="right")) - 1
        if j >= 0 and tr_slow[j] == 1:
            out.append(c)
    return out


def det_sfp(S: R.Side) -> list:
    """S7: bar t's wick goes one tick below the last swing low (untaken since it formed) and bar t closes back above
    it. Stop 0.02% below bar t's low. No trend condition."""
    ch = S.chart(TF)
    _, Ls = structure(ch)
    out = []
    for t in range(1, ch.T):
        i = Ls[t - 1, 0]
        if i < 0:
            continue
        lv = ch.l[i]
        if ch.l[i + 1:t].min(initial=np.inf) <= lv - TICK:
            continue
        if ch.l[t] <= lv - TICK and ch.c[t] > lv:
            out.append(C(int(ch.day[t]), _entry(ch, t), R.stop_below(float(ch.l[t]))))
    return out


def _legs(S: R.Side, frac: float, stop_frac: float | None) -> list:
    """S8 / S9: at the close of bar t-1 the last swing low j is a higher low (above the low before it), and the highest
    high since j (the leg high, bar m < t) is at least one tick above the last swing high BEFORE j (a break of
    structure). The leg = leg high - low(j), at least LEG_ATR x ATR14. Level = leg high - frac x leg. Entry: the first
    1-minute low at or below the level in bar t, with no high above the leg high before it in the bar; no bar after m
    touched the level before. (A minute that also makes a new leg high is not an entry.) Stop 0.02% below low(j) (stop_frac None) or below leg high - stop_frac x leg. Target: the
    leg high. One trade per swing low j."""
    ch = S.chart(TF)
    Hs, Ls = structure(ch)
    out, used = [], set()
    for t in range(2, ch.T):
        j, j0 = Ls[t - 1, 0], Ls[t - 1, 1]
        if j < 0 or j0 < 0 or j in used or not ch.l[j] > ch.l[j0]:
            continue
        hb = next((int(x) for x in Hs[t - 1] if 0 <= x < j), -1)
        if hb < 0 or t - 1 <= j:
            continue
        m = j + 1 + int(np.argmax(ch.h[j + 1:t]))
        top = float(ch.h[m])
        if top < ch.h[hb] + TICK:
            continue
        leg = top - float(ch.l[j])
        if not (np.isfinite(ch.atr[t - 1]) and leg >= LEG_ATR * ch.atr[t - 1]):
            continue
        lv = top - frac * leg
        if ch.l[m + 1:t].min(initial=np.inf) <= lv:
            continue
        stop = R.stop_below(float(ch.l[j]) if stop_frac is None else top - stop_frac * leg)
        d = int(ch.day[t])
        a, b = int(ch.B.start[t]), int(ch.B.end[t])
        r = R._first_touch(S.ml[d], a, b, lv)
        if r is None:
            continue
        hs = S.mh[d][a:r + 1]
        if (np.isfinite(hs) & (hs > top)).any():
            continue                       # a new high first: the leg grows; it is measured again at the next bar
        used.add(j)
        out.append(C(d, r + 1, stop, top))     # a fill below the stop is skipped by sequence() (no room)
    return out


def det_eq(S: R.Side) -> list:
    return _legs(S, 0.5, None)


def det_shallow(S: R.Side) -> list:
    return _legs(S, 0.25, 0.5)


DETECTORS = dict(hl=det_hl, lh_ll=det_lh_ll, bos=det_bos, choch=det_choch, r123=det_r123, mss_hl=det_mss_hl,
                 mtf=det_mtf, sfp=det_sfp, eq=det_eq, shallow=det_shallow)


def candidates(sides: dict, var: dict) -> list:
    out = []
    last = var.get("last_entry", R.LAST_ENTRY)
    for sd in (1, -1):
        out += [(sd, c) for c in DETECTORS[var["det"]](sides[sd]) if c.r0 <= last]
    out.sort(key=lambda x: (x[1].d, x[1].r0, x[0]))
    return out


# ------------------------------------------------------------------------------------------------ trades
def sequence(sides: dict, cands: list, blocked: set, days, keep) -> list:
    """One trade at a time per index (R131's rules: a buy and a sell in the same minute -> neither; an entry while a
    trade is open is skipped; entries to 15:00; out by 15:20 on index wicks, the stop first if both in one minute)."""
    trades, busy, i = [], -1, 0
    while i < len(cands):
        sd, c = cands[i]
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        if len({x[0] for x in same}) == 2 or c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
            continue
        S = sides[sd]
        mh, ml, mo = S.mh[c.d], S.ml[c.d], S.mo[c.d]
        ref = mo[c.r0]
        if not np.isfinite(ref) or ref <= c.stop:
            continue
        tgt = ref + 2 * (ref - c.stop) if c.tgt is None else c.tgt
        if tgt <= ref:
            continue
        ex = R.exit_on(mh, ml, mo, c.r0, c.stop, tgt, R.EXIT_LAST)
        if ex is None:
            continue
        xr, why, xpx = ex
        busy = c.d * N + xr
        if keep(days[c.d]):
            trades.append(dict(d=c.d, day=days[c.d], side=sd, entry=c.r0, exit=xr, why=why,
                               stop_pts=float(ref - c.stop), tgt_pts=float(tgt - ref), pts=float(xpx - ref),
                               R=float((xpx - ref) / (ref - c.stop))))
    return trades


def run_index(args) -> dict:
    und, part = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    sides = R.sides_of(g)
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    sig = {}
    for v, var in VARIANTS.items():
        if var.get("nn"):
            continue
        cands = candidates(sides, var)
        sig[v] = sequence(sides, cands, blocked, g.days, keep)
    by_day = {}
    for v, T in sig.items():
        for t in T:
            by_day.setdefault(t["d"], []).append((v, t))
    rows = {v: [] for v in sig}
    for d in sorted(by_day):
        opts = src_for(g.days[d], g.c[d])
        if opts is None:
            continue
        lastc = R.last_before(g.c[d])
        tcache = {}
        for v, t in by_day[d]:
            if not np.isfinite(lastc[t["entry"]]):
                continue
            cp = "C" if t["side"] > 0 else "P"
            tr = R.option_trade(opts.contracts.get((cp, D3.atm(lastc[t["entry"]], step))), t["entry"], t["exit"],
                                t["why"], lot, g.days[d], und)
            if tr is None:
                continue
            S = sides[t["side"]]
            r1s = rng.integers(t["entry"], R.LAST_ENTRY + 1, size=TWINS)
            tw = R.twin_nets(r1s, lastc, S.mh[d], S.ml[d], S.mo[d], opts.contracts, cp, step, t["stop_pts"],
                             t["tgt_pts"], lot, g.days[d], und, tcache)
            rows[v].append(dict(t, und=und, net=tr[0], netx2=tr[1], twins=tw))
    print(f"R139 {part} {und}: " + ", ".join(f"{k} {len(x)}" for k, x in rows.items()), flush=True)
    return rows


# ------------------------------------------------------------------------------------------------ statistics
def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    if not len(T):
        return dict(n=0)
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s = R.money(T)
    s["by_year"] = {int(y): R.money(X) for y, X in T.groupby([d.year for d in T.day])}
    s["by_und"] = {u: R.money(X) for u, X in T.groupby("und")}
    if by_fold:
        f = [K.fold_of(d) for d in T.day]
        s["by_fold"] = {k: R.money(T[[x == k for x in f]]) for k in K.FOLDS}
    for blk in ("by_year", "by_und", "by_fold"):
        for x in s.get(blk, {}).values():
            for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                x.pop(drop, None)
    s["win_vs_65"] = bool(s["win"] >= 0.65)
    return s


def nn_filter(T: pd.DataFrame, part: str):
    P = pd.read_parquet(NN.PREDS / ("wf_rows_preds_N1.parquet" if part == "design" else "holdout_rows_preds_N1.parquet"))
    T = T.copy()
    if part == "design":
        T = T[[K.fold_of(d) is not None for d in T.day]]
    M = NN.attach(T, P)
    return M, NN.keep_mask(M, "NEV")


def lock(status: str):
    p = OUT / "HOLDOUT_READ.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R139 holdout was already read")
        if not (RESULTS / "design.json").exists():
            raise R.HoldoutError("refused: commit the design results first")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def gates(s: dict, q: float) -> dict:
    folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
    g = dict(n=s.get("n", 0) >= GATES["n"], net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
             twins=s.get("p", 1.0) < 0.05, folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"],
             account=s.get("max_dd", -1e9) > GATES["dd"] and s.get("worst_month", -1e9) > GATES["month"])
    return g


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        lock("start")
    with Pool(int(os.environ.get("R139_PROCS", "4"))) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    frames = {v: pd.DataFrame([r for x in res for r in x[v]]) for v in res[0]}
    rng = np.random.default_rng(SEED + 7 + (100 if part != "design" else 0))
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)], variants={})
    for v, var in VARIANTS.items():
        base = frames["S1"] if var.get("nn") else frames[v]
        if not len(base):
            out["variants"][v] = dict(n=0, p=1.0, label=var["label"])
            continue
        if var.get("nn"):
            M, kp = nn_filter(base, part)
            s = stats(M[kp], part == "design") if kp.any() else dict(n=0)
            strata = np.array([K.fold_of(d) or "H" for d in M.day])
            s["p"] = NN.subset_p(M.net.values, kp, strata, rng) if kp.any() else 1.0
            s["unfiltered_n"] = int(len(M))
            s["unfiltered"] = R.money(M) if len(M) else dict(n=0)
            s["unfiltered"].pop("exits", None)
        else:
            s = stats(base, part == "design")
            s["p"], s["twin_mean_total"], _ = R.twin_p(base, SEED)
        s["label"] = var["label"]
        out["variants"][v] = s
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = float(q)
            s["gates"] = gates(s, q)
            s["passes_design"] = all(s["gates"].values())
    else:
        for s in out["variants"].values():
            s["passes_holdout"] = (s.get("n", 0) >= GATES["holdout_n"] and s.get("avg_net", -1) > 0
                                   and s.get("avg_net_x2", -1) > 0)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    for v, f in frames.items():
        if len(f):
            f.drop(columns=["twins"]).to_parquet(OUT / f"trades_{v}_{part}.parquet")
    if part == "holdout":
        lock("done")
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
