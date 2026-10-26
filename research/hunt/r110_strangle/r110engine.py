"""R110 engine (PREREG.md sections 1-2): R109's Monday strangle generalised to any legs, plus the S1 / S2 stops and the
F1 / F2 filters. With shorts at +-200, no wings and no stop, `week` reproduces R109's `strangle_week` (asserted by the
study's parity check and by tests).

Legs are (side, offset from the entry ATM in points, sign): sign -1 = sold, +1 = bought.
"""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np

R109 = Path(__file__).resolve().parents[1] / "r109_irathinker"
if str(R109) not in sys.path:
    sys.path.insert(0, str(R109))

import r109cfg as K  # noqa: E402
import r109engine as E  # noqa: E402

NAN = float("nan")
STOP_LOOK = 5               # a mark older than 5 minutes is stale: Black-Scholes at the last print's implied vol
LAST_DECISION = K.NROWS - 2  # 15:28: the last minute whose close can trigger a stop (it fills at 15:29)
EXERCISE_STT = 0.00125      # STT on a long option settled in the money (0.125% of the settlement value)


def legs_of(shorts: float = 200.0, wings: float | None = None) -> list:
    out = [("C", shorts, -1), ("P", -shorts, -1)]
    if wings is not None:
        out += [("C", wings, 1), ("P", -wings, 1)]
    return out


def margin_est(spot: float, lot_: int, shorts: float, wings: float | None) -> float:
    """PREREG 4: unhedged 13% of spot x lot; hedged = the defined maximum loss, (wing - short) x lot."""
    if wings is None:
        return K.MARGIN_FRAC * spot * lot_
    return (wings - shorts) * lot_


class Tracker:
    """The last print of one leg seen so far in the walk (day, row, price, index at that row)."""

    def __init__(self):
        self.day, self.row, self.px, self.spot = None, -1, NAN, NAN
        self._iv = {}

    def see(self, day, row, px, spot):
        self.day, self.row, self.px, self.spot = day, row, px, spot

    def iv(self, k, expiry, call):
        key = (self.day, self.row)
        if key not in self._iv:
            self._iv[key] = E.implied_vol(self.px, self.spot, k, E.years_left(self.day, expiry, self.row + 1), call)
        return self._iv[key]


def mark(tr: Tracker, day, row: int, s_now: float, k: float, expiry, call: bool) -> float:
    """A leg's mark at the close of minute `row`: its last print if within STOP_LOOK minutes that day, else BS at the
    last print's implied vol with the index now; the last print itself when no vol fits; NaN with no print yet."""
    if tr.day is None:
        return NAN
    if tr.day == day and row - tr.row <= STOP_LOOK:
        return tr.px
    v = tr.iv(k, expiry, call)
    if v is None or not np.isfinite(s_now):
        return tr.px
    return max(E.bs(s_now, k, E.years_left(day, expiry, row + 1), v, call), K.TICK)


def week(days: dict, monday: date, expiry: date | None, lot_: int, shorts: float = 200.0, wings: float | None = None,
         stop: str | None = None, stop_mult: float = 1.0, slip_mult: float = 1.0):
    """One Monday week. stop: None, 'S1' (premium: loss >= stop_mult x credit) or 'S2' (index beyond a breakeven).
    Returns None when the week cannot be entered exactly (not a Monday, holiday, missing 09:30 bar or any leg without a
    print in the 3 minutes from 09:31)."""
    if monday.weekday() != 0 or monday not in days or expiry is None:
        return None
    D = days[monday]
    e = K.STR_DECIDE + 1
    spot = D.idx[3, e - 1]
    if not np.isfinite(spot) or spot <= 0:
        return None
    atm = math.floor(spot / K.STR_STRIKE_STEP + 0.5) * K.STR_STRIKE_STEP
    thu = monday + timedelta(days=3)
    exp_week = expiry <= thu
    target = expiry if exp_week else thu
    sess = sorted(x for x in days if monday <= x <= target)
    exit_day = sess[-1]
    legs = []
    for side, off, sg in legs_of(shorts, wings):
        k = atm + off
        con = D.con.get((side, float(k)))
        if con is None:
            return None
        p, f = E.first_open(con, e, K.FILL_LOOK_ENTRY)
        if f < 0:
            return None
        real = E.slip(p, sg > 0, E.vol5_lots(con, f, lot_), mult=slip_mult)
        legs.append(dict(side=side, k=k, sg=sg, raw=p, f=f, real=real, call=side == "C"))
    ch = sum(E.charges(L["real"], lot_, L["sg"] > 0, monday, False) for L in legs)
    credit = sum(-L["sg"] * L["raw"] for L in legs)             # points received (net of wings bought)
    shorts_ = [L for L in legs if L["sg"] < 0]
    up_be = shorts_[0]["k"] + credit
    dn_be = shorts_[1]["k"] - credit

    # ---------------------------------------------------------------- the stop walk (S1 / S2)
    hit = None                                                  # (day, decision row)
    if stop is not None:
        trs = [Tracker() for _ in legs]
        for dd in sess:
            X = days[dd]
            cons = [X.con.get((L["side"], float(L["k"]))) for L in legs]
            r0 = e if dd == monday else 0
            for r in range(r0, LAST_DECISION + 1):
                s = X.idx[3, r]
                for L, con, tr in zip(legs, cons, trs):
                    if con is not None:
                        c = con[3, r]
                        if np.isfinite(c) and c > 0:
                            tr.see(dd, r, float(c), float(s) if np.isfinite(s) else E.idx_close_at(X.idx, r))
                if not np.isfinite(s):
                    continue
                if stop == "S2":
                    if s >= up_be or s <= dn_be:
                        hit = (dd, r)
                elif stop == "S1":
                    m = [mark(tr, dd, r, float(s), L["k"], expiry, L["call"]) for L, tr in zip(legs, trs)]
                    if all(np.isfinite(m)):
                        cost = sum(-L["sg"] * x for L, x in zip(legs, m))
                        if cost - credit >= stop_mult * credit:
                            hit = (dd, r)
                else:
                    raise ValueError(stop)
                if hit:
                    break
            if hit:
                break

    # ---------------------------------------------------------------- exits
    stale = 0
    stt = 0.0
    settle = False
    if hit:
        xd, r = hit
        X = days[xd]
        x = r + 1
        s_now = E.idx_close_at(X.idx, x)
        for L, tr in zip(legs, trs):
            con = X.con.get((L["side"], float(L["k"])))
            px, xr = (E.first_open(con, x, K.FILL_LOOK_EXIT) if con is not None else (NAN, -1))
            if xr >= 0:
                L["xraw"] = px
                L["xreal"] = E.slip(px, L["sg"] < 0, E.vol5_lots(con, xr, lot_), stop=True, mult=slip_mult)
            else:
                stale += 1
                px = NAN
                if tr.day is not None:
                    v = tr.iv(L["k"], expiry, L["call"])
                    if v is not None and np.isfinite(s_now):
                        px = max(E.bs(s_now, L["k"], E.years_left(xd, expiry, x), v, L["call"]), K.TICK)
                    else:
                        px = tr.px
                if not np.isfinite(px):
                    px = max(s_now - L["k"], 0.0) if L["call"] else max(L["k"] - s_now, 0.0)
                L["xraw"] = px
                L["xreal"] = E.slip(px, L["sg"] < 0, 0.0, stop=True, mult=slip_mult)
            ch += E.charges(L["xreal"], lot_, L["sg"] < 0, xd, False)
        exit_day_used = xd
        s_exit = s_now
    else:
        settle = exp_week and exit_day == expiry
        X = days[exit_day]
        s_exit = E.idx_close_at(X.idx, K.NROWS - 1)
        for L in legs:
            call = L["call"]
            if settle:
                L["xraw"] = max(s_exit - L["k"], 0.0) if call else max(L["k"] - s_exit, 0.0)
                L["xreal"] = L["xraw"]
                if L["sg"] > 0 and L["xraw"] > 0:
                    stt += EXERCISE_STT * L["xraw"] * lot_
                continue
            con = X.con.get((L["side"], float(L["k"])))
            px, xr = (NAN, -1)
            if con is not None:
                o = con[0, K.STR_EXIT_ROW]
                if np.isfinite(o) and o > 0:
                    px, xr = float(o), K.STR_EXIT_ROW
                else:
                    lc = E.last_close_row(con, K.NROWS - 1, K.FILL_LOOK_EXIT)
                    if lc >= 0:
                        px, xr = float(con[3, lc]), lc
            if xr >= 0:
                L["xraw"] = px
                L["xreal"] = E.slip(px, L["sg"] < 0, E.vol5_lots(con, xr, lot_), mult=slip_mult)
            else:
                stale += 1
                L["xraw"] = E._stale_leg(days, sess, L, s_exit, exit_day, expiry, call)
                L["xreal"] = E.slip(L["xraw"], L["sg"] < 0, 0.0, mult=slip_mult)
            ch += E.charges(L["xreal"], lot_, L["sg"] < 0, exit_day, False)
        exit_day_used = exit_day
    ch += stt
    pnl = sum(-L["sg"] * (L["real"] - L["xreal"]) for L in legs) * lot_
    net = pnl - ch
    cost_raw = sum(-L["sg"] * L["xraw"] for L in legs)
    return dict(day=monday, exit_day=exit_day, exit_used=exit_day_used, exp_week=bool(exp_week), settle=bool(settle),
                spot=float(spot), atm=atm, credit=credit, cost=cost_raw, net=float(net), charges=ch, stale=stale,
                lot=lot_, stopped=hit is not None, stop_at=(str(hit[0]), int(hit[1])) if hit else None,
                gross_pct=(credit - cost_raw) / credit * 100 if credit > 0 else NAN,
                net_pct=net / (credit * lot_) * 100 if credit > 0 else NAN,
                margin=margin_est(spot, lot_, shorts, wings), expiry=expiry, s_exit=s_exit)


# ------------------------------------------------------------------------------------------------ filters
def entry_iv(days: dict, monday: date, expiry: date) -> float:
    """F1: implied vol (%) of the 09:30 close of the ATM CE (offset 0), our Black-Scholes, time from 09:31 to 15:30 of
    the expiry day. NaN when it cannot be read."""
    D = days.get(monday)
    if D is None or D.atm is None or expiry is None:
        return NAN
    r = K.STR_DECIDE
    k = D.atm[r]
    s = D.idx[3, r]
    if not (np.isfinite(k) and np.isfinite(s)):
        return NAN
    con = D.con.get(("C", float(k)))
    if con is None or not np.isfinite(con[3, r]):
        return NAN
    v = E.implied_vol(float(con[3, r]), float(s), float(k), E.years_left(monday, expiry, r + 1), True)
    return NAN if v is None else v * 100


def f1_keep(ratios: list, i: int, window: int = 52, need: int = 26) -> bool:
    """Keep week i when its IV/RV ratio >= the median of the previous `window` finite ratios (at least `need`)."""
    x = ratios[i]
    prev = [r for r in ratios[max(0, i - window): i] if np.isfinite(r)]
    if not np.isfinite(x) or len(prev) < need:
        return False
    return bool(x >= np.median(prev))


def aftershock(closes: np.ndarray) -> int:
    """IraThinker's Strangle.aftershockFlag on the daily closes BEFORE Monday: 1 when either of the last two log returns
    is >= 2 sigma (population stdev of the last 20 log returns), 0 when not, -1 with fewer than 21 closes."""
    c = np.asarray([x for x in closes if np.isfinite(x) and x > 0], float)
    if len(c) < 21:
        return -1
    r = np.diff(np.log(c[-21:]))
    sig = float(np.sqrt(((r - r.mean()) ** 2).mean()))
    if not sig > 0:
        return -1
    r1, r2 = math.log(c[-1] / c[-2]), math.log(c[-2] / c[-3])
    return int(abs(r1) >= 2 * sig or abs(r2) >= 2 * sig)


# ------------------------------------------------------------------------------------------------ statistics
def boot_p(nets, rng, resamples: int = 1_000_000) -> float:
    """(1 + #{bootstrap means <= 0}) / (resamples + 1)."""
    nets = np.asarray(nets, float)
    n = len(nets)
    if n < 2:
        return 1.0
    hits, done = 0, 0
    chunk = max(1, 5_000_000 // n)
    while done < resamples:
        m = min(chunk, resamples - done)
        hits += int((nets[rng.integers(0, n, size=(m, n))].mean(axis=1) <= 0).sum())
        done += m
    return (1 + hits) / (resamples + 1)


def skip_twin_p(base_nets, n_skip: int, filt_net: float, rng, draws: int = 200_000) -> float:
    """Random-skip twin: drop n_skip of the base weeks at random; p = (1 + #{twin net >= the filter's}) / (draws + 1)."""
    b = np.asarray(base_nets, float)
    n = len(b)
    if n_skip <= 0 or n_skip >= n:
        return 1.0
    hits, done = 0, 0
    chunk = max(1, 4_000_000 // n)
    while done < draws:
        m = min(chunk, draws - done)
        keys = rng.random((m, n))
        drop = np.argpartition(keys, n_skip - 1, axis=1)[:, :n_skip]
        tw = b.sum() - b[drop].sum(axis=1)
        hits += int((tw >= filt_net - 1e-9).sum())
        done += m
    return (1 + hits) / (draws + 1)
