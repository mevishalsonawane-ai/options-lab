"""R135: the ICT / SMC books' claims measured on NSE index minutes (PREREG.md). Descriptive: no trades, no options.

    python research/hunt/r135_ict_facts/r135.py      # -> results/facts.json
"""
from __future__ import annotations

import json
import sys
from math import erf, sqrt
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r131 as R  # noqa: E402

K, D3 = R.K, R.D3
N = R.N
RESULTS = HERE / "results"
TICK = 0.05
DPCT = 0.0025
NCTRL = 20
SHUF = 200
SEED = 135
LAST_EVAL = N - 2          # an evaluation needs at least one minute after it
OR_END = K.row(9, 44)      # opening range 09:15-09:44
OR_SWEEP = (K.row(9, 45), K.row(10, 59))
EARLY = K.row(10, 29)      # "day's extreme in 09:15-10:29"


# ------------------------------------------------------------------------------------------------ the race
def race(h: np.ndarray, l: np.ndarray, r0: int, px: float, sd: int, dpct: float = DPCT):
    """1 = +d (in direction sd) first, 0 = -d first (or both in one minute), None = neither by the close."""
    if r0 >= N or not np.isfinite(px):
        return None
    hh, ll = h[r0:], l[r0:]
    up, dn = px + abs(px) * dpct, px - abs(px) * dpct      # abs: mirrored (negative) prices too
    if sd > 0:
        w = np.flatnonzero(np.nan_to_num(hh, nan=-np.inf) >= up)
        x = np.flatnonzero(np.nan_to_num(ll, nan=np.inf) <= dn)
    else:
        w = np.flatnonzero(np.nan_to_num(ll, nan=np.inf) <= dn)
        x = np.flatnonzero(np.nan_to_num(hh, nan=-np.inf) >= up)
    w0 = w[0] if len(w) else 10 ** 9
    x0 = x[0] if len(x) else 10 ** 9
    if w0 == x0 == 10 ** 9:
        return None
    return 1 if w0 < x0 else 0


def controls(g, d: int, r0: int, sd: int, rng, days_ok: np.ndarray, n: int = NCTRL) -> list:
    """The same race at the same minute on n other design sessions of this index."""
    out = []
    cand = days_ok[days_ok != d]
    for dd in rng.choice(cand, size=min(n, len(cand)), replace=False):
        px = D3._last(g.c[dd][:r0])
        out.append(race(g.h[dd], g.l[dd], r0, px, sd))
    return out


def rate(xs) -> tuple:
    v = [x for x in xs if x is not None]
    return (float(np.mean(v)) if v else float("nan")), len(v), len(xs) - len(v)


def z2(a: list, b: list) -> dict:
    """Two-proportion z test (two-sided) of decided outcomes."""
    a = [x for x in a if x is not None]
    b = [x for x in b if x is not None]
    n1, n2 = len(a), len(b)
    if not (n1 and n2):
        return dict(n1=n1, n2=n2, p=1.0)
    p1, p2 = np.mean(a), np.mean(b)
    pp = (np.sum(a) + np.sum(b)) / (n1 + n2)
    se = sqrt(pp * (1 - pp) * (1 / n1 + 1 / n2)) or 1e-12
    z = (p1 - p2) / se
    return dict(n1=n1, rate1=float(p1), n2=n2, rate2=float(p2), z=float(z), p=float(2 * (1 - 0.5 * (1 + erf(abs(z) / sqrt(2))))))


def z1(k: int, n: int, p0: float) -> dict:
    """One-sample proportion test against an expected rate p0."""
    if not n or not 0 < p0 < 1:
        return dict(n=n, p=1.0)
    ph = k / n
    z = (ph - p0) / sqrt(p0 * (1 - p0) / n)
    return dict(n=n, rate=ph, expected=p0, z=float(z), p=float(2 * (1 - 0.5 * (1 + erf(abs(z) / sqrt(2))))))


# ------------------------------------------------------------------------------------------------ levels
def period_levels(days: list, h: np.ndarray, l: np.ndarray):
    """For each session: (PDH, PDL, PWH, PWL, PMH, PML) from completed earlier periods (NaN when unknown)."""
    D = len(days)
    dh = np.nanmax(np.where(np.isfinite(h), h, -np.inf), axis=1)
    dl = np.nanmin(np.where(np.isfinite(l), l, np.inf), axis=1)
    dh[~np.isfinite(dh)] = np.nan
    dl[~np.isfinite(dl)] = np.nan
    out = np.full((D, 6), np.nan)
    wk = [tuple(d.isocalendar()[:2]) for d in days]
    mo = [(d.year, d.month) for d in days]
    for key, col in ((wk, 2), (mo, 4)):
        groups = {}
        for i, k in enumerate(key):
            groups.setdefault(k, []).append(i)
        order = list(groups)
        prev = {}
        for a, b in zip(order, order[1:]):
            prev[b] = a
        for i, k in enumerate(key):
            p = prev.get(k)
            if p is not None:
                ix = groups[p]
                out[i, col] = np.nanmax(dh[ix])
                out[i, col + 1] = np.nanmin(dl[ix])
    out[1:, 0], out[1:, 1] = dh[:-1], dl[:-1]
    return out, dh, dl


# ------------------------------------------------------------------------------------------------ the claims
def c1_sweeps(g, days_ok, rng) -> list:
    """Sweeps of PDH/PDL, PWH/PWL, PMH/PML on 5-minute bars; evaluated at the close of bar t+2."""
    lv, _, _ = period_levels(g.days, g.h, g.l)
    B = R.make_bars(g.o, g.h, g.l, g.c, 5)
    first = {}
    for t, d in enumerate(B.day):
        first.setdefault(int(d), t)
    ev = []
    for d in days_ok:
        t0 = first.get(int(d))
        if t0 is None:
            continue
        t1 = t0
        while t1 + 1 < len(B.day) and B.day[t1 + 1] == d:
            t1 += 1
        op = D3._first(g.o[d])
        for tier, (ch, cl) in (("day", (0, 1)), ("week", (2, 3)), ("month", (4, 5))):
            for sd, col in ((1, ch), (-1, cl)):         # sd = side of the level (1 = a high)
                L = lv[d, col]
                if not np.isfinite(L) or not np.isfinite(op) or (op >= L if sd > 0 else op <= L):
                    continue
                hit = [t for t in range(t0, t1 + 1) if (B.h[t] >= L + TICK if sd > 0 else B.l[t] <= L - TICK)]
                if not hit:
                    continue
                t = hit[0]
                if t + 2 > t1:
                    continue
                mn = max(2 * TICK, 1e-4 * L)
                disp = bool(B.l[t] - B.h[t + 2] >= mn) if sd > 0 else bool(B.l[t + 2] - B.h[t] >= mn)
                r0 = int(B.end[t + 2]) + 1
                if r0 > LAST_EVAL:
                    continue
                px = float(B.c[t + 2])
                out = race(g.h[d], g.l[d], r0, px, -sd)
                ev.append(dict(tier=tier, disp=disp, won=out, ctrl=controls(g, d, r0, -sd, rng, days_ok)))
    return ev


def c2_opening(g, days_ok, rng) -> dict:
    ev, reach = [], []
    for d in days_ok:
        H, L = g.h[d], g.l[d]
        orh, orl = np.nanmax(H[:OR_END + 1]), np.nanmin(L[:OR_END + 1])
        if not (np.isfinite(orh) and np.isfinite(orl)):
            continue
        B = R.make_bars(g.o[d:d + 1], g.h[d:d + 1], g.l[d:d + 1], g.c[d:d + 1], 5)
        for t in range(len(B.day)):
            if B.start[t] < OR_SWEEP[0] or B.start[t] > OR_SWEEP[1]:
                continue
            up, dn = B.h[t] >= orh + TICK, B.l[t] <= orl - TICK
            if up and dn:
                break
            if up or dn:
                sd = -1 if up else 1                          # predicted reversal direction
                r0 = int(B.end[t]) + 1
                out = race(H, L, r0, float(B.c[t]), sd)
                ev.append(dict(won=out, ctrl=controls(g, d, r0, sd, rng, days_ok)))
                rest_h, rest_l = np.nanmax(H[r0:]), np.nanmin(L[r0:])
                reach.append(bool(rest_l <= orl - TICK) if up else bool(rest_h >= orh + TICK))
                break
    return dict(events=ev, reach=reach)


def c2_extremes(g, days_ok, rng) -> dict:
    """Share of sessions whose closing-path high or low is in 09:15-10:29, vs shuffled minute returns."""
    k, n, exp = 0, 0, []
    for d in days_ok:
        c = g.c[d]
        ok = np.isfinite(c)
        if ok.sum() < 300:
            continue
        idx = np.flatnonzero(ok)
        cc = c[idx]
        early = idx <= EARLY
        k += bool(early[np.argmax(cc)] or early[np.argmin(cc)])
        n += 1
        r = np.diff(cc)
        hits = 0
        for _ in range(SHUF):
            p = np.r_[cc[0], cc[0] + np.cumsum(rng.permutation(r))]
            hits += bool(early[np.argmax(p)] or early[np.argmin(p)])
        exp.append(hits / SHUF)
    return dict(k=k, n=n, expected=float(np.mean(exp)) if exp else float("nan"))


def c3_weekly(g, days_ok, rng) -> dict:
    o = np.array([D3._first(g.o[d]) for d in range(len(g.days))])
    c = np.array([D3._last(g.c[d]) for d in range(len(g.days))])
    with np.errstate(all="ignore"):
        hi = np.nanmax(g.h, axis=1)
        lo = np.nanmin(g.l, axis=1)
    wk = {}
    for d in days_ok:
        wk.setdefault(tuple(g.days[d].isocalendar()[:2]), []).append(int(d))
    k, n, exp = 0, 0, []
    for ix in wk.values():
        if len(ix) != 5 or not all(np.isfinite(x) for i in ix for x in (o[i], c[i], hi[i], lo[i])):
            continue
        k += int(np.argmax(hi[ix]) <= 1 or np.argmin(lo[ix]) <= 1)
        n += 1
        gap = np.array([o[i] - (c[i - 1] if i >= 1 and np.isfinite(c[i - 1]) else o[i]) for i in ix])
        rel = np.array([(hi[i] - o[i], lo[i] - o[i], c[i] - o[i]) for i in ix])
        hits = 0
        for _ in range(SHUF):
            p = rng.permutation(5)
            lvl, H, L = 0.0, [], []
            for j in p:
                lvl += gap[j]
                H.append(lvl + rel[j, 0])
                L.append(lvl + rel[j, 1])
                lvl += rel[j, 2]
            hits += int(np.argmax(H) <= 1 or np.argmin(L) <= 1)
        exp.append(hits / SHUF)
    return dict(k=k, n=n, expected=float(np.mean(exp)) if exp else float("nan"))


def _side_swings(S, days_ok_set):
    ch = S.chart(15)
    return ch, ch.sh, ch.sl


def c4_strong_weak(g, days_ok, rng) -> dict:
    """First 15-minute close beyond a swing high (bullish side) or low (mirrored), by the swing's strength."""
    res = {"strong": [], "weak": []}
    keep = set(int(d) for d in days_ok)
    for sd, S in R.sides_of(g).items():
        ch = S.chart(15)
        sh, sl, k = ch.sh, ch.sl, ch.k
        for i in sh:
            i = int(i)
            if int(ch.day[i]) not in keep:
                continue
            Hi = ch.h[i]
            last_t = min(ch.T - 1, int(np.searchsorted(ch.day, ch.day[i] + R.SESSIONS_BACK + 1)) - 1)
            t = next((x for x in range(i + k + 1, last_t + 1) if ch.c[x] > Hi), None)
            if t is None:
                continue
            # swept liquidity: an earlier swing high, untaken until bar i, exceeded by bar i's high
            prev = [j for j in sh[sh < i - k] if ch.day[j] >= ch.day[i] - R.SESSIONS_BACK]
            swept = any(ch.h[i] >= ch.h[j] + TICK and (ch.h[j + 1:i].max(initial=-np.inf) < ch.h[j] + TICK)
                        for j in prev)
            # broke structure: before bar t, a close below the last swing low formed before i (known by t)
            lows = sl[(sl < i) & (sl + k < t)]
            broke = bool(len(lows)) and bool((ch.c[i + 1:t] < ch.l[int(lows[-1])]).any())
            d = int(ch.day[t])
            r0 = int(ch.B.end[t]) + 1
            if r0 > LAST_EVAL:
                continue
            out = race(S.mh[d], S.ml[d], r0, float(ch.c[t]), 1)
            res["strong" if (swept and broke) else "weak"].append(out)
    return res


def c5_retrace(g, days_ok) -> dict:
    res = {"deep": [], "shallow": []}
    keep = set(int(d) for d in days_ok)
    for sd, S in R.sides_of(g).items():
        ch = S.chart(15)
        piv = sorted([(int(i), "L") for i in ch.sl] + [(int(i), "H") for i in ch.sh])
        for (a, ta), (b, tb), (c, tc) in zip(piv, piv[1:], piv[2:]):
            if (ta, tb, tc) != ("L", "H", "L") or int(ch.day[c]) not in keep:
                continue
            A, Bh, C = ch.l[a], ch.h[b], ch.l[c]
            if not A < C < Bh:
                continue
            r = (Bh - C) / (Bh - A)
            last_t = min(ch.T - 1, int(np.searchsorted(ch.day, ch.day[c] + R.SESSIONS_BACK + 1)) - 1)
            out = None
            for t in range(c + ch.k + 1, last_t + 1):
                took, held = ch.l[t] <= C - TICK, ch.h[t] > Bh
                if took:
                    out = 0
                    break
                if held:
                    out = 1
                    break
            res["deep" if r >= 0.5 else "shallow"].append(out)
    return res


def c6_fvg(g, days_ok, rng) -> dict:
    keep = set(int(d) for d in days_ok)
    fill50_same, fill50_5, ev = [], [], []
    for sd, S in R.sides_of(g).items():
        ch = S.chart(15)
        for j in range(1, ch.T - 1):
            if not (ch.same(j - 1, j + 1) and int(ch.day[j]) in keep):
                continue
            lo_edge, hi_edge = ch.h[j - 1], ch.l[j + 1]
            if hi_edge - lo_edge < R.fvg_min(hi_edge):
                continue
            mid = (lo_edge + hi_edge) / 2
            last_t = min(ch.T - 1, int(np.searchsorted(ch.day, ch.day[j] + 5 + 1)) - 1)
            seg = range(j + 2, last_t + 1)
            f50 = next((t for t in seg if ch.l[t] <= mid), None)
            fill50_5.append(f50 is not None)
            fill50_same.append(f50 is not None and ch.day[f50] == ch.day[j])
            full = next((t for t in seg if ch.l[t] <= lo_edge), None)
            if full is None:
                continue
            d = int(ch.day[full])
            r0 = int(ch.B.end[full]) + 1
            if r0 > LAST_EVAL:
                continue
            out = race(S.mh[d], S.ml[d], r0, float(ch.c[full]), 1)
            ctrl = controls(_mirror_grid(g, sd), d, r0, 1, rng, np.array(sorted(keep)))
            ev.append(dict(won=out, ctrl=ctrl))
    return dict(fill50_same=fill50_same, fill50_5=fill50_5, events=ev)


def _mirror_grid(g, sd):
    if sd > 0:
        return g
    from types import SimpleNamespace
    return SimpleNamespace(days=g.days, o=-g.o, h=-g.l, l=-g.h, c=-g.c)


def c7_twenty(g, days_ok) -> dict:
    lv, dh, dl = period_levels(g.days, g.h, g.l)
    c = np.array([D3._last(g.c[d]) for d in range(len(g.days))])
    out = {"PMH": [], "PML": []}
    keep = set(int(d) for d in days_ok)
    for d in range(len(g.days) - 22):
        if d not in keep:
            continue
        for name, col, sd in (("PMH", 4, 1), ("PML", 5, -1)):
            L = lv[d, col]
            if not np.isfinite(L):
                continue
            swept = dh[d] >= L + TICK if sd > 0 else dl[d] <= L - TICK
            disp = (dl[d] > dh[d + 2]) if sd > 0 else (dl[d + 2] > dh[d])
            if swept and disp and np.isfinite(c[d + 2]) and np.isfinite(c[d + 22]):
                out[name].append(float(np.sign(c[d + 22] - c[d + 2]) * -sd))   # +1 = reversed over 20 sessions
    return out


# ------------------------------------------------------------------------------------------------ run
def run():
    rng = np.random.default_rng(SEED)
    agg = dict(c1=[], c2=[], reach=[], c2x=[], c3=[], c4={"strong": [], "weak": []}, c5={"deep": [], "shallow": []},
               c6f_same=[], c6f_5=[], c6=[], c7={"PMH": [], "PML": []})
    for und in K.UNDS:
        g = D3.load_grid(und, K.DESIGN[1])
        days_ok = np.array([i for i, d in enumerate(g.days) if K.DESIGN[0] <= d <= K.DESIGN[1]])
        agg["c1"] += [dict(e, und=und) for e in c1_sweeps(g, days_ok, rng)]
        o = c2_opening(g, days_ok, rng)
        agg["c2"] += o["events"]
        agg["reach"] += o["reach"]
        agg["c2x"].append(c2_extremes(g, days_ok, rng))
        agg["c3"].append(c3_weekly(g, days_ok, rng))
        for kk, v in c4_strong_weak(g, days_ok, rng).items():
            agg["c4"][kk] += v
        for kk, v in c5_retrace(g, days_ok).items():
            agg["c5"][kk] += v
        f = c6_fvg(g, days_ok, rng)
        agg["c6f_same"] += f["fill50_same"]
        agg["c6f_5"] += f["fill50_5"]
        agg["c6"] += f["events"]
        for kk, v in c7_twenty(g, days_ok).items():
            agg["c7"][kk] += v
        print("R135", und, "done", flush=True)
    out, tests = {}, {}
    for tier in ("day", "week", "month"):
        for disp in (True, False):
            E = [e for e in agg["c1"] if e["tier"] == tier and e["disp"] == disp]
            key = f"C1-{tier}-{'disp' if disp else 'nodisp'}"
            tests[key] = z2([e["won"] for e in E], [x for e in E for x in e["ctrl"]])
        A = [e["won"] for e in agg["c1"] if e["tier"] == tier and e["disp"]]
        Bn = [e["won"] for e in agg["c1"] if e["tier"] == tier and not e["disp"]]
        out[f"C1-{tier}-disp-vs-nodisp"] = z2(A, Bn)
    tests["C2-or-sweep"] = z2([e["won"] for e in agg["c2"]], [x for e in agg["c2"] for x in e["ctrl"]])
    out["C2-or-reach-opposite"] = dict(n=len(agg["reach"]), rate=float(np.mean(agg["reach"])) if agg["reach"] else None)
    k = sum(x["k"] for x in agg["c2x"])
    n = sum(x["n"] for x in agg["c2x"])
    p0 = float(np.average([x["expected"] for x in agg["c2x"]], weights=[x["n"] for x in agg["c2x"]]))
    tests["C2-extreme-early"] = z1(k, n, p0)
    k = sum(x["k"] for x in agg["c3"])
    n = sum(x["n"] for x in agg["c3"])
    p0 = float(np.average([x["expected"] for x in agg["c3"]], weights=[x["n"] for x in agg["c3"]]))
    tests["C3-week-mon-tue"] = z1(k, n, p0)
    tests["C4-strong-vs-weak"] = z2(agg["c4"]["strong"], agg["c4"]["weak"])
    tests["C5-deep-vs-shallow"] = z2(agg["c5"]["deep"], agg["c5"]["shallow"])
    tests["C6-full-fill"] = z2([e["won"] for e in agg["c6"]], [x for e in agg["c6"] for x in e["ctrl"]])
    out["C6-fill50"] = dict(n=len(agg["c6f_5"]), same_session=float(np.mean(agg["c6f_same"])),
                            within_5=float(np.mean(agg["c6f_5"])))
    out["C7-twenty"] = {kk: dict(n=len(v), reversed_share=float(np.mean([x > 0 for x in v])) if v else None)
                        for kk, v in agg["c7"].items()}
    qs = R.R3.bh([t.get("p", 1.0) for t in tests.values()])
    for (kk, t), q in zip(tests.items(), qs):
        t["q"] = q
    res = dict(period=[str(x) for x in K.DESIGN], d_pct=DPCT, primary=tests, reported=out,
               c1_counts={f"{t}-{d}": sum(1 for e in agg["c1"] if e["tier"] == t and e["disp"] == d)
                          for t in ("day", "week", "month") for d in (True, False)})
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "facts.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    run()
