"""R58 rules (PREREG section 5): 38 variants in 6 families. Every signal is read from the STOCK (and NIFTY for
relative strength); option data is never a signal input. A rule turns one stock book into time-ordered entry Specs.

Decision rows: a signal decided on the close of row s fills from row s + 1. Fixed-time rules: '10:15' means the close
of the 10:14 minute (row 59), filled at 10:15. 5-minute bar b = rows 5b .. 5b+4 (its close is row 5b+4).
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field

import numpy as np

import r58cfg as K
from r58data import C, H, L, O, Book
from r58engine import Spec, vwap_of

sys.path.insert(0, str(K.ROOT / "research" / "hunt" / "h37"))


@dataclass(frozen=True)
class Rule:
    id: str
    family: str
    kind: str
    p: dict = field(default_factory=dict, hash=False, compare=False)
    hold: int = 1
    itm: int = 0
    per_side: bool = False       # one open position a side (F6 Donchian) instead of one per stock
    max_side_day: int = 1

    def twin_rows(self) -> list[int]:
        """The rule's decision window (rows) for random-entry twins (PREREG 7)."""
        k, p = self.kind, self.p
        if k == "orb":
            return [5 * b + 4 for b in range(p["M"] // 5, 45)]
        if k == "vol":
            return [5 * b + 4 for b in range(5, 63)]
        if k == "dc5":
            return [5 * b + 4 for b in range(0, 71)]
        return [p["s"]]


# ------------------------------------------------------------------------------------------------ per-book features
def bars5(bk: Book) -> np.ndarray:
    """[nd, 75, 5] 5-minute O, H, L, C, V from the 1-minute bars (NaN when the bar has no minute)."""
    b = getattr(bk, "_b5", None)
    if b is None:
        S = bk.S.reshape(bk.nd, 75, 5, 5)
        with np.errstate(all="ignore"):
            hi = np.nanmax(np.where(np.isfinite(S[..., H]), S[..., H], -np.inf), axis=2)
            lo = np.nanmin(np.where(np.isfinite(S[..., L]), S[..., L], np.inf), axis=2)
        ok = np.isfinite(S[..., C])
        first = np.argmax(ok, axis=2)
        last = 4 - np.argmax(ok[..., ::-1], axis=2)
        any_ = ok.any(axis=2)
        ii, jj = np.indices(first.shape)
        op = np.where(any_, S[ii, jj, first, O], np.nan)
        cl = np.where(any_, S[ii, jj, last, C], np.nan)
        vol = np.where(any_, np.nansum(S[..., 4], axis=2), np.nan)
        b = np.stack([op, np.where(any_, hi, np.nan), np.where(any_, lo, np.nan), cl, vol], axis=-1)
        bk._b5 = b
    return b


def day_open(bk: Book, di: int) -> float:
    o = bk.S[di, :, O]
    f = np.flatnonzero(np.isfinite(o))
    return float(o[f[0]]) if len(f) else np.nan


def nifty_close(bk: Book, di: int) -> float:
    c = bk.N[di, :, 3]
    f = np.flatnonzero(np.isfinite(c))
    return float(c[f[-1]]) if len(f) else np.nan


def entry_days(bk: Book, part: str) -> list[int]:
    return [i for i, d in enumerate(bk.days) if K.period(d) == part]


# ------------------------------------------------------------------------------------------------ signals (one book)
def sig_orb(bk, di, p):
    M = p["M"]
    r = bk.S[di, :M]
    if np.isfinite(r[:, C]).sum() < M - 5:
        return []
    hi, lo = np.nanmax(r[:, H]), np.nanmin(r[:, L])
    b5 = bars5(bk)[di]
    for b in range(M // 5, 45):
        c = b5[b, 3]
        if not np.isfinite(c):
            continue
        side = 1 if c > hi else -1 if c < lo else 0
        if side:
            sp = dict(s=5 * b + 4, side=side)
            if p["stop"] == "range":
                sp["ustop"] = lo if side > 0 else hi
            else:
                sp["pstop"] = 0.30
            if p.get("tgt"):
                sp["ptgt"] = p["tgt"]
            return [sp]
    return []


def sig_rs_i(bk, di, p):
    s = p["s"]
    op, no = day_open(bk, di), bk.N[di, 0, O]
    c, nc = bk.S[di, s, C], bk.N[di, s, 3]
    if not all(np.isfinite([op, no, c, nc])):
        return []
    rs, rn = c / op - 1, nc / no - 1
    if rs >= p["thr"] and rn <= 0.001:
        return [dict(s=s, side=1, ustop=op, pstop=0.30)]
    if rs <= -p["thr"] and rn >= -0.001:
        return [dict(s=s, side=-1, ustop=op, pstop=0.30)]
    return []


def sig_rs_d(bk, di, p):
    t, Lb = di - 1, p["L"]
    if t - Lb < 0:
        return []
    cs, cs0 = bk.D[t, 3], bk.D[t - Lb, 3]
    n1, n0 = nifty_close(bk, t), nifty_close(bk, t - Lb)
    if not all(np.isfinite([cs, cs0, n1, n0])):
        return []
    rs, rn = cs / cs0 - 1, n1 / n0 - 1
    X = 0.02 if Lb == 1 else 0.03
    if rs - rn >= X and rn <= 0:
        return [dict(s=4, side=1, ustop=bk.D[t, 2], pstop=0.40)]
    if rs - rn <= -X and rn >= 0:
        return [dict(s=4, side=-1, ustop=bk.D[t, 1], pstop=0.40)]
    return []


def sig_gap(bk, di, p):
    if di < 1:
        return []
    pc = bk.D[di - 1, 3]
    r = bk.S[di, :15]
    o, c = bk.S[di, 0, O], bk.S[di, 14, C]
    if not all(np.isfinite([pc, o, c])):
        return []
    hi, lo = np.nanmax(r[:, H]), np.nanmin(r[:, L])
    gap = o / pc - 1
    th = p["th"]
    side = 0
    if p["how"] == "go":
        side = 1 if (gap >= th and c > o) else -1 if (gap <= -th and c < o) else 0
    else:
        side = -1 if (gap >= th and c < o) else 1 if (gap <= -th and c > o) else 0
    if not side:
        return []
    sp = dict(s=14, side=side)
    if p.get("ps"):
        sp["pstop"] = p["ps"]
    else:
        sp["ustop"] = lo if side > 0 else hi
    if p["how"] == "fade":
        sp["utgt"] = pc
    return [sp]


def slot_avg(bk: Book) -> np.ndarray:
    a = getattr(bk, "_slot", None)
    if a is None:
        v = bars5(bk)[:, :, 4]
        a = np.full_like(v, np.nan)
        for di in range(20, bk.nd):
            w = v[di - 20:di]
            n = np.isfinite(w).sum(axis=0)
            with np.errstate(all="ignore"):
                a[di] = np.where(n >= 15, np.nanmean(w, axis=0), np.nan)
        bk._slot = a
    return a


def sig_vol(bk, di, p):
    b5 = bars5(bk)[di]
    av = slot_avg(bk)[di]
    for b in range(5, 63):
        o_, h_, l_, c_, v_ = b5[b]
        if not (np.isfinite(c_) and np.isfinite(av[b]) and av[b] > 0):
            continue
        if v_ < p["k"] * av[b]:
            continue
        with np.errstate(all="ignore"):
            hh, ll = np.nanmax(b5[:b, 1]), np.nanmin(b5[:b, 2])
        if c_ > hh:
            side = 1
        elif c_ < ll:
            side = -1
        else:
            continue
        return [dict(s=5 * b + 4, side=side, ustop=l_ if side > 0 else h_, tmin=p["tmin"])]
    return []


def sig_vwap(bk, di, p):
    s = p["s"]
    vw = vwap_of(bk)[di]
    c = bk.S[di, s, C]
    if not all(np.isfinite([c, vw[s], vw[s - 30]])):
        return []
    if c > vw[s] * 1.002 and vw[s] > vw[s - 30]:
        return [dict(s=s, side=1, vwap=True, pstop=0.30)]
    if c < vw[s] * 0.998 and vw[s] < vw[s - 30]:
        return [dict(s=s, side=-1, vwap=True, pstop=0.30)]
    return []


def sig_dc20(bk, di, p):
    t = di - 1
    if t < 20:
        return []
    c = bk.D[t, 3]
    hh, ll = np.nanmax(bk.D[t - 20:t, 1]), np.nanmin(bk.D[t - 20:t, 2])
    if not np.isfinite(c):
        return []
    if c > hh:
        return [dict(s=4, side=1, ustop=np.nanmin(bk.D[t - 9:t + 1, 2]), pstop=0.40)]
    if c < ll:
        return [dict(s=4, side=-1, ustop=np.nanmax(bk.D[t - 9:t + 1, 1]), pstop=0.40)]
    return []


def sig_mom(bk, di, p):
    t = di - 1
    if t < 20:
        return []
    c, c10 = bk.D[t, 3], bk.D[t - 10, 3]
    sma = np.nanmean(bk.D[t - 19:t + 1, 3])
    if not all(np.isfinite([c, c10, sma])):
        return []
    r = c / c10 - 1
    if r >= 0.05 and c > sma:
        return [dict(s=4, side=1, ustop=bk.D[t, 2], pstop=0.40)]
    if r <= -0.05 and c < sma:
        return [dict(s=4, side=-1, ustop=bk.D[t, 1], pstop=0.40)]
    return []


def dc5_signals(bk: Book) -> dict:
    """R44 ind.donchian(20) on the stock's continuous 5-minute bars (bars with >= 1 minute), 100 warm-up bars:
    {session index: [(bar, side), ...]}."""
    x = getattr(bk, "_dc5", None)
    if x is None:
        b5 = bars5(bk)
        dd, bb = np.nonzero(np.isfinite(b5[:, :, 3]))
        h, l, c = b5[dd, bb, 1], b5[dd, bb, 2], b5[dd, bb, 3]
        n = 20
        T = len(c)
        lo_, sh = np.zeros(T, bool), np.zeros(T, bool)
        from numpy.lib.stride_tricks import sliding_window_view as sw
        hh = sw(h, n).max(axis=1)[:-1]
        ll = sw(l, n).min(axis=1)[:-1]
        lo_[n:] = c[n:] > hh
        sh[n:] = c[n:] < ll
        lo_[:100] = False
        sh[:100] = False
        x = {}
        for i in np.flatnonzero(lo_ | sh):
            x.setdefault(int(dd[i]), []).append((int(bb[i]), 1 if lo_[i] else -1))
        bk._dc5 = x
    return x


def sig_dc5(bk, di, p):
    out = []
    for b, side in dc5_signals(bk).get(di, []):
        if 5 * (b + 1) < 5 or 5 * (b + 1) > K.row(15, 10):
            continue
        out.append(dict(s=5 * b + 4, side=side, pstop=p["stop"], ptgt=p["tgt"], tmin=p["tmin"]))
    return out


def h37_levels(bk: Book) -> np.ndarray:
    """research/hunt/h37/sigs.profile_levels on the stock's minutes (TPO 30-min, 70% VA): [nd, 3] POC, VAH, VAL."""
    lev = getattr(bk, "_mp", None)
    if lev is None:
        import importlib.util
        spec = importlib.util.spec_from_file_location("r58_h37_sigs", K.ROOT / "research" / "hunt" / "h37" / "sigs.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        lev = mod.profile_levels(bk.S[:, :, H], bk.S[:, :, L], bk.S[:, :, C])
        bk._mp = lev
    return lev


def sig_h37(bk, di, p):
    if di < 1:
        return []
    poc, vah, val = h37_levels(bk)[di - 1]
    op = day_open(bk, di)
    c0 = bars5(bk)[di, 0, 3]
    if not all(np.isfinite([vah, val, op, c0])):
        return []
    if op > vah and c0 > vah:
        return [dict(s=4, side=1, pstop=0.30, tmin=p["tmin"])]
    if op < val and c0 < val:
        return [dict(s=4, side=-1, pstop=0.30, tmin=p["tmin"])]
    return []


SIG = dict(orb=sig_orb, rs_i=sig_rs_i, rs_d=sig_rs_d, gap=sig_gap, vol=sig_vol, vwap=sig_vwap, dc20=sig_dc20,
           mom=sig_mom, dc5=sig_dc5, h37=sig_h37)


# ------------------------------------------------------------------------------------------------ the 38 variants
def rules() -> list[Rule]:
    R = []
    for M in (15, 30):
        R.append(Rule(f"orb{M}-rs", "F1", "orb", dict(M=M, stop="range")))
        R.append(Rule(f"orb{M}-ps", "F1", "orb", dict(M=M, stop="prem")))
        R.append(Rule(f"orb{M}-rs-t", "F1", "orb", dict(M=M, stop="range", tgt=0.50)))
    for hh, mm in ((10, 15), (11, 30)):
        for thr in (0.005, 0.010):
            R.append(Rule(f"rs-i-{hh}{mm:02d}-{int(round(thr * 1e4)):03d}", "F2", "rs_i",
                          dict(s=K.row(hh, mm) - 1, thr=thr)))
    for Lb in (1, 3):
        for Hd in (1, 3):
            R.append(Rule(f"rs-d-l{Lb}-h{Hd}", "F2", "rs_d", dict(s=4, L=Lb), hold=Hd))
    for th in (0.01, 0.02):
        R.append(Rule(f"gap-go-{int(th * 100)}", "F3", "gap", dict(s=14, th=th, how="go")))
        R.append(Rule(f"gap-fade-{int(th * 100)}", "F3", "gap", dict(s=14, th=th, how="fade")))
    R.append(Rule("gap-go-1-ps", "F3", "gap", dict(s=14, th=0.01, how="go", ps=0.25)))
    R.append(Rule("gap-fade-1-ps", "F3", "gap", dict(s=14, th=0.01, how="fade", ps=0.25)))
    for k in (2.5, 4.0):
        for ex, tmin in (("60", 60), ("eod", 0)):
            R.append(Rule(f"vol-k{str(k).replace('.', '').rstrip('0') or '0'}-{ex}", "F4", "vol", dict(k=k, tmin=tmin)))
    for hh, mm in ((10, 0), (10, 30), (11, 30)):
        R.append(Rule(f"vwap-{hh}{mm:02d}", "F4", "vwap", dict(s=K.row(hh, mm) - 1)))
    for Hd in (1, 3, 5):
        R.append(Rule(f"dc20-h{Hd}", "F5", "dc20", dict(s=4), hold=Hd))
    for Hd in (3, 5):
        R.append(Rule(f"mom10-h{Hd}", "F5", "mom", dict(s=4), hold=Hd))
    rt = dict(stop=0.15, tgt=0.30, tmin=30)
    R.append(Rule("dc5-rt-atm", "F6", "dc5", rt, per_side=True, max_side_day=2))
    R.append(Rule("dc5-rt-itm1", "F6", "dc5", rt, itm=1, per_side=True, max_side_day=2))
    R.append(Rule("dc5-a-atm", "F6", "dc5", dict(stop=0.25, tgt=0.50, tmin=60), per_side=True, max_side_day=2))
    R.append(Rule("h37-60-atm", "F6", "h37", dict(s=4, tmin=60)))
    R.append(Rule("h37-60-itm1", "F6", "h37", dict(s=4, tmin=60), itm=1))
    R.append(Rule("h37-30-atm", "F6", "h37", dict(s=4, tmin=30)))
    assert len(R) == 38 and len({r.id for r in R}) == 38, [r.id for r in R]
    return R


def stocks_for(rule: Rule) -> tuple:
    return tuple(s for s in K.UNIVERSE if not (rule.itm and s in K.NO_PM1))


def specs(bk: Book, rule: Rule, days: list[int]) -> list[Spec]:
    """Every candidate entry of the rule in the given sessions, in time order (before position limits)."""
    f = SIG[rule.kind]
    out = []
    for di in days:
        for x in f(bk, di, rule.p):
            out.append(Spec(sym=bk.sym, di=di, s=int(x["s"]), side=int(x["side"]), itm=rule.itm,
                            pstop=float(x.get("pstop", 0.0)), ptgt=float(x.get("ptgt", 0.0)),
                            ustop=float(x.get("ustop", np.nan)), utgt=float(x.get("utgt", np.nan)),
                            vwap=bool(x.get("vwap", False)), tmin=int(x.get("tmin", 0)), hold=rule.hold))
    return out
