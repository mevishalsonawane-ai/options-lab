"""R144b pure functions (PREREG.md): features at entry, the losing-trade anatomy, the design-only filter choice, random
twins, the lose-less gates and BH. No file, network or lake access here; r144b.py feeds it.

A trade is a dict: arm, und, day (date), er (entry row: the 1-minute bar that STARTS at 09:15 + er), xr, side ('C' |
'P'), net (Rs, 1 lot, after charges), why (exit type), k (strike, optional).
A session is R3's `Sess` (or any object with day, ix (375, 4) O H L C, vix (375,), opts {(k, 'C'|'P'): (375, 6) O H L C
V OI}, prev (O, H, L, C)).
"""
from __future__ import annotations

import math
from collections import Counter, defaultdict
from datetime import date

import numpy as np

N = 375
O, H, L, C, V, OI = range(6)
RATE = 0.065
MIN_N = 30
LOSER_MARGIN = 0.05
MAX_FILTERS = 3
MAX_OVERLAP = 0.50
GATE_P, GATE_Q = 0.05, 0.10
MIN_LEFT, MIN_SKIPPED = 40, 30
OR15 = 15            # rows 0..14 = 09:15-09:30
REF_ROW = 15         # the 09:30 reference for "change since 09:30"

# feature id -> plain name. Signal source of every one: the index (and its options / VIX as context).
NAMES = {
    "P1": "push into the entry (3 five-min bars, ATR, trade's way)", "P2": "signal bar's range / ATR",
    "P3": "beyond yesterday's high (calls) / low (puts), ADR", "P4": "stretch from today's open, ADR, trade's way",
    "P5": "distance from the day's TWAP, ADR, trade's way (no index volume: TWAP, not VWAP)",
    "P6": "09:15-09:30 opening range / ADR", "P7": "gap the trade's way / ADR", "P8": "yesterday's range / ADR",
    "P9": "60-min trend the trade's way / ADR", "P10": "earlier fake pokes today", "P11": "entry minute (row)",
    "P12": "sessions to expiry",
    "V1": "option band volume so far vs its 20-session median", "V2": "traded option's last-5-min volume spike",
    "L1": "entry premium (Rs)", "L2": "zero-volume minutes of the option in the last 15", "L3": "traded contract's OI",
    "O1": "traded contract's OI change since 09:30", "O2": "band put/call OI ratio", "O3": "PCR change since 09:30",
    "O4": "ATM implied volatility", "O5": "ATM IV change since 09:30", "O6": "skew (ATM put IV - call IV)",
    "O7": "ATM straddle / ADR (expected vs usual move)",
    "M1": "India VIX", "M2": "VIX change from yesterday's close", "M3": "VIX 30-min change",
    "M4": "day's range so far / ADR (regime)", "M5": "how directional the day is so far",
    "F1": "PROXY order flow: option tick-direction volume delta, 15 min",
    "F2": "PROXY order flow: option OI-price quadrant, 15 min",
}
CATS = ("P10", "P12", "F2")
FEATS = tuple(NAMES)
QUADS = ("long build-up", "short build-up", "short covering", "long unwinding", "flat")


# ================================================================================================ per-index history
class History:
    """What each session may know about earlier sessions of the same index: ADR (20 sessions before), the next expiry
    day, and the option band's cumulative-volume curve median over the 20 sessions before."""

    def __init__(self, sessions: list):
        self.days = [s.day for s in sessions]
        self.pos = {d: i for i, d in enumerate(self.days)}
        rng = np.array([_range(s) for s in sessions], float)
        self.adr = np.full(len(sessions), np.nan)
        for i in range(len(sessions)):
            w = rng[max(0, i - 20):i]
            w = w[np.isfinite(w)]
            if len(w) >= 10:
                self.adr[i] = float(w.mean())
        self.curves = np.array([band_cumvol(s) for s in sessions], float) if sessions else np.zeros((0, N))
        exp = [bool(getattr(s, "expiry", False)) for s in sessions]
        self.next_exp = [None] * len(sessions)
        nxt = None
        for i in range(len(sessions) - 1, -1, -1):
            if exp[i]:
                nxt = i
            self.next_exp[i] = nxt

    def vol_median(self, i: int, row: int) -> float:
        w = self.curves[max(0, i - 20):i, row]
        w = w[np.isfinite(w) & (w > 0)]
        return float(np.median(w)) if len(w) >= 10 else np.nan

    def sessions_to_expiry(self, i: int):
        j = self.next_exp[i]
        return None if j is None else j - i

    def calendar_days_to_expiry(self, i: int):
        j = self.next_exp[i]
        return None if j is None else (self.days[j] - self.days[i]).days


def _range(s) -> float:
    ix = np.asarray(s.ix, float)
    if not np.isfinite(ix[:, H]).any():
        return np.nan
    return float(np.nanmax(ix[:, H]) - np.nanmin(ix[:, L]))


def band_cumvol(s, n: int = N) -> np.ndarray:
    """Cumulative volume of every stored option of the session, minute by minute (the first n rows)."""
    tot = np.zeros(n)
    for a in s.opts.values():
        v = np.asarray(a, float)[:n, V]
        tot[:len(v)] += np.where(np.isfinite(v), v, 0.0)
    return np.cumsum(tot)


# ================================================================================================ helpers
def _last(x: np.ndarray) -> float:
    f = np.where(np.isfinite(x))[0]
    return float(x[f[-1]]) if len(f) else np.nan


def _at_or_before(x: np.ndarray, row: int) -> float:
    return _last(x[:row + 1]) if row >= 0 else np.nan


def bs_price(spot: float, k: float, t: float, sig: float, call: bool) -> float:
    if t <= 0 or sig <= 0:
        return max(0.0, (spot - k) if call else (k - spot))
    d1 = (math.log(spot / k) + (RATE + sig * sig / 2) * t) / (sig * math.sqrt(t))
    d2 = d1 - sig * math.sqrt(t)
    nd = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))  # noqa: E731
    if call:
        return spot * nd(d1) - k * math.exp(-RATE * t) * nd(d2)
    return k * math.exp(-RATE * t) * nd(-d2) - spot * nd(-d1)


def implied_vol(px: float, spot: float, k: float, t: float, call: bool) -> float:
    """Bisection on 0.01-5.0; NaN when the price is outside what any volatility gives."""
    if not all(np.isfinite([px, spot, k, t])) or px <= 0 or spot <= 0 or t <= 0:
        return np.nan
    lo, hi = 0.01, 5.0
    if px < bs_price(spot, k, t, lo, call) or px > bs_price(spot, k, t, hi, call):
        return np.nan
    for _ in range(60):
        mid = (lo + hi) / 2
        if bs_price(spot, k, t, mid, call) < px:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def bars5(ix: np.ndarray) -> np.ndarray:
    """Complete 5-minute bars (O H L C) of the rows given; a bar with a missing minute uses what it has."""
    nb = len(ix) // 5
    out = np.full((nb, 4), np.nan)
    for b in range(nb):
        w = ix[5 * b:5 * b + 5]
        if not np.isfinite(w[:, C]).any():
            continue
        f = np.where(np.isfinite(w[:, C]))[0]
        out[b] = (w[f[0], O], np.nanmax(w[:, H]), np.nanmin(w[:, L]), w[f[-1], C])
    return out


def quadrant(dp: float, doi: float) -> str:
    if not (np.isfinite(dp) and np.isfinite(doi)) or dp == 0 or doi == 0:
        return "flat"
    if dp > 0:
        return "long build-up" if doi > 0 else "short covering"
    return "short build-up" if doi > 0 else "long unwinding"


def dte_bucket(n) -> str | None:
    if n is None:
        return None
    return "0 (expiry day)" if n == 0 else "1" if n == 1 else "2-4" if n <= 4 else "5+"


# ================================================================================================ features at entry
def features(s, hist: History, er: int, side: str, k=None) -> dict:
    """PREREG section 3. Everything is read from rows < er (the bars closed before the entry minute) and from earlier
    sessions; the arrays are cut at er first, so nothing later can leak in. NaN / None = unknown."""
    out = {f: np.nan for f in FEATS}
    out.update(P10=None, P12=None, F2=None)
    i = hist.pos.get(s.day)
    if i is None or er < 1:
        return out
    sg = 1.0 if side == "C" else -1.0
    ix = np.asarray(s.ix, float)[:er]
    vix = np.asarray(s.vix, float)[:er] if getattr(s, "vix", None) is not None else np.full(er, np.nan)
    opts = {key: np.asarray(a, float)[:er] for key, a in s.opts.items()}
    adr = hist.adr[i]
    last = _last(ix[:, C])
    fo = np.where(np.isfinite(ix[:, O]))[0]
    day_open = float(ix[fo[0], O]) if len(fo) else np.nan
    pO, pH, pL, pC = (float(x) for x in s.prev)
    out["P11"] = float(er)
    out["P12"] = dte_bucket(hist.sessions_to_expiry(i))
    ok_adr = np.isfinite(adr) and adr > 0
    b5 = bars5(ix)
    good = b5[np.isfinite(b5[:, C])]
    if len(good) >= 4:
        tr = good[:, 1] - good[:, 2]
        atr = float(np.mean(tr[-14:]))
        if atr > 0:
            out["P1"] = sg * (good[-1, 3] - good[-4, 3]) / atr
            out["P2"] = (good[-1, 1] - good[-1, 2]) / atr
    if len(good):
        pokes, ext = 0, None
        for b in good:
            hk, lk = (b[1], b[2]) if sg > 0 else (-b[2], -b[1])
            ck = b[3] * sg
            if ext is not None and hk > ext and ck < (hk + lk) / 2:
                pokes += 1
            ext = hk if ext is None else max(ext, hk)
        out["P10"] = str(min(pokes, 3))
    if ok_adr and np.isfinite(last):
        if np.isfinite(pH) and np.isfinite(pL):
            out["P3"] = (last - pH) / adr if sg > 0 else (pL - last) / adr
            out["P8"] = (pH - pL) / adr
        if np.isfinite(day_open):
            out["P4"] = sg * (last - day_open) / adr
            if np.isfinite(pC):
                out["P7"] = sg * (day_open - pC) / adr
        tp = (ix[:, H] + ix[:, L] + ix[:, C]) / 3
        if np.isfinite(tp).any():
            out["P5"] = sg * (last - float(np.nanmean(tp))) / adr
        if er >= OR15 and np.isfinite(ix[:OR15, H]).any():
            out["P6"] = (np.nanmax(ix[:OR15, H]) - np.nanmin(ix[:OR15, L])) / adr
        ref = _at_or_before(ix[:, C], er - 61) if er > 61 else day_open
        if np.isfinite(ref):
            out["P9"] = sg * (last - ref) / adr
        rng = np.nanmax(ix[:, H]) - np.nanmin(ix[:, L])
        out["M4"] = rng / adr
        if rng > 0 and np.isfinite(day_open):
            out["M5"] = abs(last - day_open) / rng
    # volume, liquidity, OI, order-flow proxies
    cv = band_cumvol(SimpleSess(opts), er)
    med = hist.vol_median(i, er - 1)
    if np.isfinite(med) and med > 0:
        out["V1"] = cv[er - 1] / med
    a = opts.get((k, side)) if k is not None else None
    if a is not None:
        vol = a[:, V]
        v5 = np.nansum(vol[max(0, er - 5):er])
        before = vol[:max(0, er - 5)]
        nb5 = len(before) / 5
        if nb5 >= 1 and np.nansum(before) > 0:
            out["V2"] = v5 / (np.nansum(before) / nb5)
        out["L1"] = _last(a[:, C])
        w = vol[max(0, er - 15):er]
        out["L2"] = float(np.sum(~np.isfinite(w) | (w == 0))) if len(w) else np.nan
        out["L3"] = _last(a[:, OI])
        if er > REF_ROW:
            oi0 = _at_or_before(a[:, OI], REF_ROW)
            if np.isfinite(oi0) and oi0 > 0:
                out["O1"] = out["L3"] / oi0 - 1
        lo15 = max(0, er - 16)
        dp = _last(a[:, C]) - _at_or_before(a[:, C], lo15)
        doi = _last(a[:, OI]) - _at_or_before(a[:, OI], lo15)
        out["F2"] = quadrant(dp, doi)
        cc = a[lo15:er, C]
        dv = a[lo15 + 1:er, V]
        d = np.diff(cc)
        okd = np.isfinite(d) & np.isfinite(dv)
        if okd.any() and np.sum(dv[okd]) > 0:
            out["F1"] = float(np.sum(np.sign(d[okd]) * dv[okd]) / np.sum(dv[okd]))
    out["O2"] = pcr(opts, er - 1)
    if er > REF_ROW:
        out["O3"] = out["O2"] - pcr(opts, REF_ROW)
    cd = hist.calendar_days_to_expiry(i)
    if cd is not None and np.isfinite(last):
        iv_now, sk, strad = atm_iv(opts, last, cd, er - 1)
        out["O4"], out["O6"] = iv_now, sk
        if ok_adr and np.isfinite(strad):
            out["O7"] = strad / adr
        if er > REF_ROW:
            s0 = _at_or_before(ix[:, C], REF_ROW)
            iv0, _, _ = atm_iv(opts, s0, cd, REF_ROW)
            out["O5"] = iv_now - iv0
    out["M1"] = _last(vix)
    vp = float(getattr(s, "vix_prev", np.nan))
    if np.isfinite(out["M1"]) and np.isfinite(vp):
        out["M2"] = out["M1"] - vp
    if er > 31:
        out["M3"] = out["M1"] - _at_or_before(vix, er - 31)
    return out


class SimpleSess:
    def __init__(self, opts):
        self.opts = opts


def pcr(opts: dict, row: int) -> float:
    if row < 0:
        return np.nan
    oc = op = 0.0
    for (k, sd), a in opts.items():
        x = _at_or_before(a[:, OI], row)
        if np.isfinite(x):
            if sd == "C":
                oc += x
            else:
                op += x
    return op / oc if oc > 0 else np.nan


def atm_iv(opts: dict, spot: float, cal_days: int, row: int) -> tuple:
    """(ATM IV = mean of call and put IV, put IV - call IV, straddle) at the stored strike nearest to spot."""
    if not np.isfinite(spot):
        return np.nan, np.nan, np.nan
    ks = sorted({k for k, sd in opts if (k, "C") in opts and (k, "P") in opts})
    if not ks:
        return np.nan, np.nan, np.nan
    k = min(ks, key=lambda x: abs(x - spot))
    t = (cal_days + (N - 1 - row) / N) / 365.0
    pc, pp = _at_or_before(opts[(k, "C")][:, C], row), _at_or_before(opts[(k, "P")][:, C], row)
    ivc, ivp = implied_vol(pc, spot, k, t, True), implied_vol(pp, spot, k, t, False)
    iv = np.nanmean([ivc, ivp]) if np.isfinite([ivc, ivp]).any() else np.nan
    return iv, ivp - ivc, pc + pp


# ================================================================================================ periods and slices
def in_window(d: date, w: tuple) -> bool:
    return w[0] <= d <= w[1]


def summary(tr: list) -> dict:
    if not tr:
        return dict(n=0)
    nets = np.array([float(t["net"]) for t in tr])
    lo = nets < 0
    return dict(n=len(tr), losers=int(lo.sum()), loser_rate=round(float(lo.mean()), 3), win_rate=round(
        float((nets > 0).mean()), 3), avg=round(float(nets.mean())), net=round(float(nets.sum())),
        sum_losses=round(float(nets[lo].sum())), avg_loss=round(float(nets[lo].mean())) if lo.any() else 0)


def edges(vals) -> np.ndarray:
    v = np.asarray([x for x in vals if x is not None and np.isfinite(x)], float)
    if len(v) < 25:
        return np.array([])
    return np.unique(np.quantile(v, [0.2, 0.4, 0.6, 0.8]))


def fifth(x, ed: np.ndarray):
    """'Q1' (lowest) .. 'Q5'; None when unknown or no cut points."""
    if x is None or not np.isfinite(x) or not len(ed):
        return None
    return f"Q{int(np.searchsorted(ed, x, side='right')) + 1}"


def slice_of(f: str, x, ed):
    if f in CATS:
        return None if x is None else str(x)
    return fifth(x, ed)


def steady(row: dict, base: dict, periods: tuple) -> bool:
    """PREREG section 4: >= 30 trades, loser rate above the arm's and average net < 0 in EVERY period given."""
    if not periods:
        return False
    for p in periods:
        r, b = row.get(p, {}), base.get(p, {})
        if r.get("n", 0) < MIN_N or not b.get("n"):
            return False
        if not (r["loser_rate"] > b["loser_rate"] and r["avg"] < 0):
            return False
    return True


def slices(trades: list, label, windows: dict, judge: tuple) -> dict:
    groups = defaultdict(list)
    for t in trades:
        s = label(t)
        if s is not None:
            groups[str(s)].append(t)
    base = {p: summary([t for t in trades if in_window(t["day"], w)]) for p, w in windows.items()}
    out = {}
    for s, tr in sorted(groups.items()):
        row = {p: summary([t for t in tr if in_window(t["day"], w)]) for p, w in windows.items()}
        row["steady_losing"] = steady(row, base, judge)
        out[s] = row
    return out


# ================================================================================================ anatomy
def clustering(trades: list) -> dict:
    by_day = defaultdict(list)
    for t in trades:
        by_day[(t["day"], t["und"])].append(float(t["net"]))
    lose = [t for t in trades if t["net"] < 0]
    all_loss = {k for k, v in by_day.items() if all(x < 0 for x in v)}
    seq = [float(t["net"]) < 0 for t in sorted(trades, key=lambda t: (t["day"], int(t["er"]), t["und"]))]
    streak = best = 0
    for x in seq:
        streak = streak + 1 if x else 0
        best = max(best, streak)
    return dict(index_days=len(by_day), all_loss_index_days=len(all_loss),
                losers_on_all_loss_days=round(sum(1 for t in lose if (t["day"], t["und"]) in all_loss) / len(lose), 3)
                if lose else 0, longest_losing_streak=best)


def money_by_exit(trades: list) -> dict:
    out = defaultdict(lambda: dict(n=0, losers=0, lost=0.0, net=0.0))
    for t in trades:
        r = out[str(t["why"])]
        r["n"] += 1
        r["net"] += float(t["net"])
        if t["net"] < 0:
            r["losers"] += 1
            r["lost"] += float(t["net"])
    return {k: dict(v, lost=round(v["lost"]), net=round(v["net"])) for k, v in sorted(out.items())}


def worst(trades: list, feats: dict, n: int = 20) -> list:
    keep = ("P1", "P2", "P3", "P7", "P10", "P12", "V2", "L1", "O4", "M1", "M4", "F2")
    out = []
    for t in sorted(trades, key=lambda t: float(t["net"]))[:n]:
        f = feats.get(key(t), {})
        out.append(dict(day=str(t["day"]), und=t["und"], side=t["side"], er=int(t["er"]), xr=int(t["xr"]),
                        net=round(float(t["net"])), why=t["why"],
                        **{k: (round(float(f[k]), 3) if isinstance(f.get(k), (float, int)) and np.isfinite(f[k])
                               else f.get(k)) for k in keep}))
    return out


def key(t) -> tuple:
    return (t["und"], t["day"], int(t["er"]), t["side"])


# ================================================================================================ filters
def candidates(trades_design: list, feats: dict, eds: dict, halves: dict) -> list:
    """PREREG section 5, steps 1-3: eligible slices, most costly first."""
    base = {h: summary([t for t in trades_design if in_window(t["day"], w)]) for h, w in halves.items()}
    out = []
    for f in FEATS:
        ed = eds.get(f, np.array([]))
        labels = defaultdict(list)
        for t in trades_design:
            s = slice_of(f, feats.get(key(t), {}).get(f), ed)
            if s is not None:
                labels[s].append(t)
        allowed = [s for s in labels if f in CATS or s in ("Q1", f"Q{len(ed) + 1}")]
        for s in allowed:
            tr = labels[s]
            ok = True
            for h, w in halves.items():
                r = summary([t for t in tr if in_window(t["day"], w)])
                if r.get("n", 0) < MIN_N or r["loser_rate"] < base[h]["loser_rate"] + LOSER_MARGIN or r["avg"] >= 0:
                    ok = False
                    break
            if ok:
                out.append(dict(feature=f, slice=s, name=NAMES[f], cut=_cut(f, s, ed),
                                design_net=round(float(sum(t["net"] for t in tr))), design_n=len(tr),
                                keys=[key(t) for t in tr]))
    return sorted(out, key=lambda c: c["design_net"])


def _cut(f: str, s: str, ed: np.ndarray) -> dict:
    if f in CATS:
        return dict(kind="cat", value=s)
    if s == "Q1":
        return dict(kind="low", below=float(ed[0]))
    return dict(kind="high", at_or_above=float(ed[-1]))


def choose(cands: list) -> list:
    """Step 4: up to 3, skipping one whose skipped trades overlap a chosen one's by > 50%."""
    chosen = []
    for c in cands:
        ks = set(c["keys"])
        if any(len(ks & set(x["keys"])) > MAX_OVERLAP * min(len(ks), len(x["keys"])) for x in chosen):
            continue
        chosen.append(c)
        if len(chosen) == MAX_FILTERS:
            break
    return [{k: v for k, v in c.items() if k != "keys"} for c in chosen]


def skips(flt: dict, f: dict) -> bool:
    """Does the frozen filter skip a trade with these features? Unknown never skips."""
    x = f.get(flt["feature"])
    cut = flt["cut"]
    if cut["kind"] == "cat":
        return x is not None and str(x) == cut["value"]
    if x is None or not isinstance(x, (int, float, np.floating)) or not np.isfinite(x):
        return False
    return x < cut["below"] if cut["kind"] == "low" else x >= cut["at_or_above"]


# ================================================================================================ gates
def loss_stats(trades: list) -> dict:
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in trades:
        daily[t["day"]] += float(t["net"])
        monthly[(t["day"].year, t["day"].month)] += float(t["net"])
    nets = np.array([float(t["net"]) for t in trades]) if trades else np.zeros(0)
    eq = np.cumsum([daily[d] for d in sorted(daily)]) if daily else np.zeros(0)
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0.0], eq])) - np.concatenate([[0.0], eq]))) \
        if len(eq) else 0.0
    return dict(trades=len(trades), net=round(float(nets.sum())), win_rate=round(float((nets > 0).mean()), 3)
                if len(nets) else 0.0, loss_sum=round(float(sum(v for v in daily.values() if v < 0))),
                max_dd=round(dd), worst_day=round(float(min(daily.values()))) if daily else 0,
                worst_month=round(float(min(monthly.values()))) if monthly else 0)


def loss_sum(trades: list) -> float:
    daily = defaultdict(float)
    for t in trades:
        daily[t["day"]] += float(t["net"])
    return float(sum(v for v in daily.values() if v < 0))


def twin_p(trades: list, n_skip: int, rule_loss_sum: float, n: int, rng, walk=None) -> float:
    """Share of random twins (drop n_skip trades at random; `walk(kept_list)` re-runs the arm when given) whose sum of
    losing days is at least as good as the rule's; (1 + hits) / (1 + n)."""
    if n_skip <= 0 or n_skip >= len(trades):
        return 1.0
    hits = 0
    idx = np.arange(len(trades))
    for _ in range(n):
        gone = set(rng.choice(idx, size=n_skip, replace=False).tolist())
        kept = [t for i, t in enumerate(trades) if i not in gone]
        if walk is not None:
            kept = walk(kept)
        if loss_sum(kept) >= rule_loss_sum:
            hits += 1
    return (1 + hits) / (1 + n)


def gates(rule: dict, base: dict, skipped: int, p: float, q: float) -> dict:
    fails = []
    if not rule["loss_sum"] > base["loss_sum"]:
        fails.append("losing days not smaller")
    if rule["max_dd"] > base["max_dd"]:
        fails.append("drawdown worse")
    if rule["worst_day"] < base["worst_day"]:
        fails.append("worst day worse")
    if rule["worst_month"] < base["worst_month"]:
        fails.append("worst month worse")
    if not rule["net"] > 0:
        fails.append("the arm no longer makes money")
    if not p <= GATE_P:
        fails.append(f"twin p {p:.3g} > {GATE_P}")
    if not q <= GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    if rule["trades"] < MIN_LEFT:
        fails.append(f"< {MIN_LEFT} trades left")
    if skipped < MIN_SKIPPED:
        fails.append(f"< {MIN_SKIPPED} trades skipped")
    return dict(passed=not fails, fails=fails)


def bh(ps: list) -> list:
    """Benjamini-Hochberg q-values, in the input order."""
    m = len(ps)
    if not m:
        return []
    order = np.argsort(ps)
    q = np.empty(m)
    prev = 1.0
    for rank in range(m, 0, -1):
        i = order[rank - 1]
        prev = min(prev, ps[i] * m / rank)
        q[i] = prev
    return [float(x) for x in q]


def exit_counts(trades: list) -> dict:
    return dict(Counter(str(t["why"]) for t in trades))
