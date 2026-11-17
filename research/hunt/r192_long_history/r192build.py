"""R192 tables (PREREG.md sections 4-5): for every session of every era, each idea's side (decided only from data before
the decision minute and earlier sessions) and, for every timing, BOTH 1-ITM legs priced:
REAL on R51's NIFTY option store (eras A, B, C) and SYNTHETIC (Black-76 from the index and India VIX) wherever VIX
exists (S0, S, and the real eras for the calibration).

    python3 -I research/hunt/r192_long_history/r192build.py real   # signals + real legs -> ~/.cache/iraalgo/r192/rows_real.pkl
    python3 -I research/hunt/r192_long_history/r192build.py syn    # + synthetic legs (needs results/calibration.json)

Nothing here summarises a trade; the study does that.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r192cfg as K  # noqa: E402

os.environ["IRAALGO_LAKE"] = str(K.LAKE)
os.environ.setdefault("R147_OUT", str(K.OUT / "r147"))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import r192lib as G  # noqa: E402

ROWS_REAL = K.OUT / "rows_real.pkl"
ROWS = K.OUT / "rows.pkl"
TIMINGS_DAILY = ("ON", "CC1", "CC2", "D2")
TIMINGS_ALL = ("ON", "CC1", "CC2", "D2", "G5", "G6")


# ================================================================================================ inputs
def daily() -> tuple:
    d = pd.read_parquet(K.LAKE / "index_daily.parquet")
    d["date"] = pd.to_datetime(d.date).dt.date
    n = d[d.symbol == "NIFTY"].drop_duplicates("date").set_index("date").sort_index()
    n = n[[x.weekday() < 5 for x in n.index]]
    v = d[d.symbol == "INDIAVIX"].drop_duplicates("date").set_index("date").close.sort_index()
    v = v.where(v > 1.0)
    sessions = list(n.index)
    vix = v.reindex(sessions).to_numpy(float)
    return sessions, n.open.to_numpy(float), n.high.to_numpy(float), n.low.to_numpy(float), n.close.to_numpy(float), vix


def minutes() -> dict:
    from research.hunt.r147_night_plus import data as D7
    return {d: a for d, a in D7.index_days("NIFTY").items() if d.weekday() < 5}


def store() -> dict:
    sys.path.insert(0, str(K.HUNT / "r51"))
    with open(K.STORE, "rb") as f:
        return pickle.load(f)


# ================================================================================================ per session
class World:
    """Everything the builder reads, with the calendars."""

    def __init__(self, with_store: bool = True):
        self.sessions, self.op, self.hi, self.lo, self.cl, self.vix = daily()
        self.pos = {d: i for i, d in enumerate(self.sessions)}
        self.ix = minutes()
        self.st = store() if with_store else {}
        self.hol = G.holidays_between(self.sessions)
        self.syn_exp = G.syn_expiries(self.sessions)
        self.syn_mon = G.monthly_set(self.syn_exp)
        self.real_exp = sorted(d for d, D in self.st.items() if D.expiry)
        self.real_exp_set = set(self.real_exp)
        self.syn_exp_set = set(self.syn_exp)

    # ---------------------------------------------------------------------------------------- calendars
    def real_era(self, d) -> bool:
        return K.era_of(d) in K.REAL_ERAS

    def is_exp(self, d) -> bool:
        return d in self.real_exp_set if self.real_era(d) else d in self.syn_exp_set

    def expiry(self, d):
        if self.real_era(d):
            return G.expiry_for(d, self.real_exp)
        return G.expiry_for(d, self.syn_exp, self.syn_mon)

    def n_between(self, d, ex) -> int:
        """Sessions strictly between d and the expiry session."""
        if ex is None or ex <= d:
            return 0
        import bisect
        j = self.pos.get(ex, bisect.bisect_left(self.sessions, ex))
        return max(j - self.pos[d] - 1, 0)

    def minute_day(self, d) -> bool:
        return d >= K.MINUTE_START and d in self.ix

    # ---------------------------------------------------------------------------------------- signals
    def context(self, i: int) -> dict | None:
        """The 15:19 decision context of session i (minutes before 15:20, or the daily bar in S0)."""
        d = self.sessions[i]
        if i < 1:
            return None
        pc = self.cl[i - 1]
        if self.minute_day(d):
            a = G.blank_after(self.ix[d], K.R1520)
            px = G.last_close(a, K.R1520)
            h, lo = G.day_hilo(a, K.R1520)
            src = "minute"
        elif d < K.MINUTE_START:
            px, h, lo, src = self.cl[i], self.hi[i], self.lo[i], "daily"
        else:
            return None
        move = px / pc - 1.0 if np.isfinite(px) and np.isfinite(pc) and pc > 0 else np.nan
        cb = self.cl[:i]
        vb = self.vix[:i]
        gap_night = (i + 1 < len(self.sessions)) and (self.sessions[i + 1] - d).days > 1
        return dict(px=float(px), hi=h, lo=lo, loc=G.close_loc(px, h, lo), move=float(move), prev_close=float(pc),
                    sma200=G.sma_prev(cb), vix_high=G.vix_pct_high(vb), gap_night=bool(gap_night), src=src)

    def sides(self, i: int, ctx: dict) -> dict:
        d = self.sessions[i]
        cb, vb = self.cl[:i], self.vix[:i]
        s = dict(K1=1 if G.is_last_of_month(self.sessions, i) else 0,
                 K2=1 if G.is_pre_holiday(self.sessions, i + 1, self.hol) else 0,
                 K3=1,
                 K4=G.sig_k4_base(ctx["move"], ctx["loc"]),
                 K7=G.sig_k7(vb, cb),
                 K8=G.sig_k8(ctx["px"], cb),
                 K9=G.sig_k9(ctx["px"], cb),
                 K10=G.sig_big(ctx["move"], True),
                 K11=G.sig_big(ctx["move"], False))
        if self.minute_day(d):
            s["K5"] = G.sig_k5(self.ix[d], ctx["prev_close"])
            s["K6"] = G.sig_k6(self.ix[d], ctx["prev_close"])
        else:
            s["K5"] = s["K6"] = 0
        return s

    # ---------------------------------------------------------------------------------------- holds
    def window(self, i: int, timing: str):
        off = G.SPECS[timing][1]
        if i + off >= len(self.sessions):
            return None
        flags = [self.is_exp(self.sessions[i + k]) for k in range(off + 1)]
        w = G.hold_window(timing, flags)
        return None if w is None else (w, [self.sessions[i + k] for k in range(w[1] + 1)])

    def decision_px(self, d, er: int) -> float:
        i = self.pos[d]
        if self.minute_day(d):
            return G.last_close(self.ix[d], er)
        return self.cl[i] if er >= K.R1520 else self.op[i]

    def _ixc(self, days) -> np.ndarray:
        out = np.full(len(days) * K.N, np.nan)
        for k, d in enumerate(days):
            if d in self.ix:
                out[k * K.N:(k + 1) * K.N] = self.ix[d][:, G.C]
        return out

    def real_leg(self, i: int, timing: str, side: int):
        win = self.window(i, timing)
        d = self.sessions[i]
        if win is None or d not in self.st or not self.minute_day(d):
            return None
        (er, off, xr), days = win
        D = self.st[d]
        atm = D.atm[er - 1] if D.atm is not None and np.isfinite(D.atm[er - 1]) else np.nan
        if not np.isfinite(atm):
            px = self.decision_px(d, er)
            if not np.isfinite(px):
                return None
            atm = G.atm_of(px)
        kx = G.strike_1itm(float(atm), side)
        cp = "C" if side > 0 else "P"
        n = len(days) * K.N
        o, c, v = np.full(n, np.nan), np.full(n, np.nan), np.zeros(n)
        for k, dk in enumerate(days):
            Dk = self.st.get(dk)
            con = Dk.con.get((cp, kx)) if Dk is not None else None
            if con is not None:
                o[k * K.N:(k + 1) * K.N], c[k * K.N:(k + 1) * K.N] = con[0], con[3]
                v[k * K.N:(k + 1) * K.N] = np.nan_to_num(con[4])
        target = self.target_for(i, timing, side, er)
        r = G.walk(o, c, v, self._ixc(days), er, off * K.N + xr, G.lot(d), lambda p: days[p // K.N],
                   G.SPECS[timing][3], target, side)
        if r is None:
            return None
        # the synthetic inputs at the real entry minute (for the calibration of `a`)
        ke, re_ = r["epos"] // K.N, r["epos"] % K.N
        S = self.ix[days[ke]][re_, G.O] if days[ke] in self.ix else np.nan
        sig = self.sigma_base(days[ke], re_)
        ex = self.expiry(d)
        r["cal"] = None if not (np.isfinite(S) and np.isfinite(sig) and ex is not None) else \
            (float(S), kx, days[ke], int(re_), ex, self.n_between(days[ke], ex), float(sig), side)
        r["strike"] = kx
        return r

    def target_for(self, i: int, timing: str, side: int, er: int):
        """G5's target: the previous close, only when it lies on the side's favourable side of the decision price."""
        if not G.SPECS[timing][4]:
            return None
        pc, px = float(self.cl[i - 1]), self.decision_px(self.sessions[i], er)
        if not (np.isfinite(pc) and np.isfinite(px)):
            return None
        return pc if (side > 0 and pc > px) or (side < 0 and pc < px) else None

    def sigma_base(self, d, rowpos: int) -> float:
        """VIX / 100: the same session's close for prices at or after 15:00, else the previous session's close."""
        i = self.pos[d]
        v = self.vix[i] if rowpos >= K.R1500 else (self.vix[i - 1] if i > 0 else np.nan)
        return float(v) / 100.0 if np.isfinite(v) else np.nan

    def syn_leg(self, i: int, timing: str, side: int, a: float, w: float | None = None):
        win = self.window(i, timing)
        d = self.sessions[i]
        if win is None:
            return None
        (er, off, xr), days = win
        minute = self.minute_day(d)
        if not minute and (d >= K.MINUTE_START or timing not in TIMINGS_DAILY):
            return None
        ex = self.expiry(d)
        if ex is None:
            return None
        px = self.decision_px(d, er)
        if not np.isfinite(px):
            return None
        kx = G.strike_1itm(G.atm_of(px), side)
        qty = K.SYN_LOT if K.era_of(d) in K.SYN_ERAS else G.lot(d)
        n = len(days) * K.N
        o, c = np.full(n, np.nan), np.full(n, np.nan)
        v = np.full(n, K.SYN_VOL_LOTS * qty)
        rows = np.arange(K.N)
        for k, dk in enumerate(days):
            sl = slice(k * K.N, (k + 1) * K.N)
            clock = None if w is None else (self.n_between(dk, ex), w)
            if minute:
                if dk not in self.ix:
                    continue
                a_ = self.ix[dk]
                for part, lo_, hi_ in (("am", 0, K.R1500), ("pm", K.R1500, K.N)):
                    sg = self.sigma_base(dk, lo_)
                    if not np.isfinite(sg):
                        continue
                    rr = rows[lo_:hi_]
                    o[k * K.N + lo_:k * K.N + hi_] = G.syn_price(a_[lo_:hi_, G.O], kx, dk, rr, ex, a * sg, side, clock=clock)
                    c[k * K.N + lo_:k * K.N + hi_] = G.syn_price(a_[lo_:hi_, G.C], kx, dk, rr, ex, a * sg, side, True, clock=clock)
            else:
                j = self.pos[dk]
                pts = []
                if k == 0:
                    pts.append((er, self.cl[j] if er >= K.R1520 else self.op[j]))
                if k == off:
                    pts.append((xr, self.op[j] if xr <= K.R0930 else self.cl[j]))
                for rr, S in pts:
                    sg = self.sigma_base(dk, rr)
                    if np.isfinite(sg) and np.isfinite(S):
                        o[sl][rr] = G.syn_price([S], kx, dk, [rr], ex, a * sg, side, clock=clock)[0]
                        c[sl][rr] = o[sl][rr]
        target = self.target_for(i, timing, side, er)
        ixc = self._ixc(days) if minute else np.full(n, np.nan)
        r = G.walk(o, c, v, ixc, er, off * K.N + xr, qty, lambda p: days[p // K.N], G.SPECS[timing][3], target, side)
        if r is not None:
            r["strike"] = kx
        return r


# ================================================================================================ builds
def build_real():
    W = World()
    rows = []
    for i, d in enumerate(W.sessions):
        era = K.era_of(d)
        if era is None:
            continue
        ctx = W.context(i)
        if ctx is None:
            continue
        rec = dict(day=d, i=i, era=era, ctx=ctx, sides=W.sides(i, ctx), expiry=W.is_exp(d), pools={})
        if era in K.REAL_ERAS:
            for t in TIMINGS_ALL:
                rec["pools"][t] = {s: {"real": W.real_leg(i, t, s)} for s in (1, -1)}
        rows.append(rec)
    ROWS_REAL.parent.mkdir(parents=True, exist_ok=True)
    ROWS_REAL.write_bytes(pickle.dumps(rows))
    print("rows", len(rows), rows[0]["day"], rows[-1]["day"], flush=True)


def build_syn():
    """The amended synthetic legs ("syn": trading-time clock, results/calibration.json) and the pre-registered ones
    ("syn_cal": calendar clock, results/calibration_calendar.json)."""
    cal = json.loads((K.RESULTS / "calibration.json").read_text())
    a_cal = json.loads((K.RESULTS / "calibration_calendar.json").read_text())["a"]
    W = World(with_store=True)
    rows = pickle.loads(ROWS_REAL.read_bytes())
    for rec in rows:
        i = rec["i"]
        ts = TIMINGS_DAILY if rec["era"] == "S0" else TIMINGS_ALL
        for t in ts:
            p = rec["pools"].setdefault(t, {1: {}, -1: {}})
            for s in (1, -1):
                p[s]["syn"] = W.syn_leg(i, t, s, cal["a"], cal["w"])
                p[s]["syn_cal"] = W.syn_leg(i, t, s, a_cal, None)
    ROWS.write_bytes(pickle.dumps(rows))
    print("rows with synthetic legs", len(rows), "a", cal["a"], "w", cal["w"], "calendar a", a_cal, flush=True)


if __name__ == "__main__":
    {"real": build_real, "syn": build_syn}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
