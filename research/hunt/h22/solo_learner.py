"""h22: Solo as it ran on 5 Oct 2026 (git 0498ec6): IraSolo.kt + ira-core Learner.kt, the online learning brain.

Rules ported (0498ec6, IraSolo.RULES / tick / manage):
  * markets NIFTY then BANKNIFTY; three views (Learner, horizons 15/30/60 min); every minute each view learns the
    answers that came due and guesses again (features from minute FIRST=30 on);
  * a view trades only when it is `ready` (>= 200 confident guesses scored, >= 55% right over the last 400) and the
    guess is confident (|p-0.5| >= 0.10) and its band of sureness is not below 55% (40+ faded guesses);
    of the views that would trade, the one with the best hit rate (Learner.pick);
  * NO SoloGate (added 6 Oct 08:07 UTC), NO cap on trades a day (maxPerDay = MAX), losses-to-stop = MAX;
    day loss limit Rs 5,000; one open Solo trade at a time; no entry from minute 315 (14:30);
  * contract: ATM (half-up) of the index at the decision, nearest expiry STRICTLY after today (Liquidity's
    OrbRules.expiryAfter, used by IraNewsTrades.contract); paper MARKET buy; resting 30% premium stop
    (Protections, SL-M); out when the view's horizon has passed (TIME) or at 15:10;
  * the app decides 2 minutes behind the newest minute (SETTLE): decision on learned minute m, entry = option open at
    m+3, horizon exit = open at entry + h + 1 (the convention of scratchpad/solo2/py/learner.py, reviewed earlier).
  * SoloCalibration's sit-outs need Solo's own closed record by condition; on 5 Oct it had none, so it takes every
    trade (not modelled; it only removes trades).
Fills/charges: app paper fills (+5 bps buy, -5 bps market sell, -10 bps stop) and SandboxCosts (obuy Costs('app')).

The phone's brain was created at the first market pass after install (5 Oct morning, the Learner shipped on Sat 3 Oct)
and first learned from the earlier days the phone held; how many is not known, so the warm-up is swept.

    flock <scratch>/obuy.lock python3 -I research/hunt/h22/solo_learner.py
"""
from __future__ import annotations

import math
import os
import pickle
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402
from obuy.data import market  # noqa: E402
import sim as H19  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h22")
COST = Costs("app")
STEP = {"NIFTY": 50, "BANKNIFTY": 100}
DIM = 12
FIRST = 30
BANDS = [0.10, 0.15, 0.20]
MIN_BAND = 40.0
FADE = 0.998
LAST_ENTRY = 315
CUT = 355
SETTLE = 2
STOP = 0.30
DAY_LOSS = 5000.0


class Learner:
    """ira-core Learner.kt (0498ec6), line by line (same as scratchpad/solo2/py/learner.py without the 6-Oct gate)."""

    def __init__(self, h, edge=0.10, min_hit=0.55, min_scored=200, window=400, rate=0.02, l2=1e-4):
        self.h = h; self.edge = edge; self.min_hit = min_hit; self.min_scored = min_scored; self.window = window
        self.rate = rate; self.l2 = l2
        self.w = np.zeros(DIM + 1); self.mean = np.zeros(DIM); self.m2 = np.zeros(DIM); self.seen = 0; self.vol = 0.0005
        self.rec = []; self.bandN = [0.0] * 3; self.bandHit = [0.0] * 3

    def band(self, p):
        d = abs(p - 0.5); b = 0
        for i, x in enumerate(BANDS):
            if d >= x:
                b = i
        return b

    def hit(self):
        return (sum(self.rec) / len(self.rec)) if self.rec else 0.0

    def ready(self):
        return len(self.rec) >= self.min_scored and self.hit() >= self.min_hit

    def z(self, x):
        if self.seen > 1:
            sd = np.sqrt(self.m2 / (self.seen - 1)); sd[sd <= 1e-9] = 1.0
        else:
            sd = np.ones(DIM)
        return np.clip((x - self.mean) / sd, -5, 5)

    def p(self, x):
        s = self.w[DIM] + float(self.w[:DIM] @ self.z(x))
        return 1 / (1 + math.exp(-max(-30, min(30, s))))

    def learn(self, x, up, guess):
        if abs(guess - 0.5) >= self.edge:
            right = (guess > 0.5) == up
            self.rec.append(right)
            if len(self.rec) > self.window:
                self.rec.pop(0)
            b = self.band(guess)
            for i in range(3):
                self.bandN[i] *= FADE; self.bandHit[i] *= FADE
            self.bandN[b] += 1.0
            if right:
                self.bandHit[b] += 1.0
        zx = self.z(x)
        err = (1.0 if up else 0.0) - self.p(x)
        lr = self.rate / math.sqrt(1.0 + self.seen / 5000.0)
        self.w[:DIM] += lr * (err * zx - self.l2 * self.w[:DIM])
        self.w[DIM] += lr * err
        self.seen += 1
        d = x - self.mean; self.mean += d / self.seen; self.m2 += d * (x - self.mean)

    def tick(self, pc, c):
        r = abs(math.log(c / pc))
        if math.isfinite(r):
            self.vol = 0.995 * self.vol + 0.005 * max(r, 1e-5)

    def decide(self, p):
        if not self.ready() or abs(p - 0.5) < self.edge:
            return None
        b = self.band(p)
        bh = None if self.bandN[b] < MIN_BAND else self.bandHit[b] / self.bandN[b]
        if (bh if bh is not None else 1.0) < self.min_hit:
            return None
        return p > 0.5


def features(ix, m, prev_close, vol, hi, lo):
    c = ix[m, 3]

    def ret(k):
        return math.log(c / ix[max(0, m - k), 3]) / (vol * math.sqrt(k))
    l15 = ix[m - 14:m + 1]
    r15 = (l15[:, 1].max() - l15[:, 2].min()) / c / (vol * 15)
    ups = (l15[:, 3] > l15[:, 0]).sum() / 15.0 - 0.5
    t = m / 375.0
    return np.array([ret(1), ret(5), ret(15), ret(30), ret(min(60, m)),
                     (c - lo) / (hi - lo) - 0.5 if hi > lo else 0.0,
                     math.log(c / ix[0, 0]) / (vol * math.sqrt(m + 1.0)),
                     math.log(c / prev_close) / (vol * math.sqrt(m + 1.0)) if prev_close else 0.0,
                     t, t * t, r15, ups])


def dense(x):
    a = np.full((375, 4), np.nan)
    j = np.asarray(x["m"]).astype(int) - C.OPEN_M
    ok = (j >= 0) & (j < 375)
    for k, key in enumerate(("o", "h", "l", "c")):
        a[j[ok], k] = np.asarray(x[key])[ok]
    good = np.isfinite(a[:, 3])
    if not good.any():
        return None, 0
    f = int(np.argmax(good))
    a[:f] = a[f, 0]
    for m in range(f + 1, 375):
        if not good[m]:
            a[m] = a[m - 1, 3]
    return a, int(good.sum())


class Brain:
    def __init__(self):
        self.minds = [Learner(h) for h in (15, 30, 60)]
        self.prev = None

    def day(self, ix, upto=374, on_minute=None):
        """Feed one session minute by minute (IraSolo.feed). on_minute(m, opts) after each learned minute m."""
        pend = {L.h: [] for L in self.minds}
        hi = ix[0, 1]; lo = ix[0, 2]
        for m in range(1, upto + 1):
            hi = max(hi, ix[m, 1]); lo = min(lo, ix[m, 2])
            last = {}
            for L in self.minds:
                L.tick(ix[m - 1, 3], ix[m, 3])
                q = pend[L.h]
                while q and q[0][0] + L.h <= m:
                    m0, x, g = q.pop(0)
                    L.learn(x, ix[m0 + L.h, 3] > ix[m0, 3], g)
                if m < FIRST:
                    continue
                x = features(ix, m, self.prev, L.vol, hi, lo)
                g = L.p(x)
                q.append((m, x, g))
                last[L.h] = g
            if on_minute is not None and m >= FIRST:
                opts = []
                for L in self.minds:
                    g = last.get(L.h)
                    d = None if g is None else L.decide(g)
                    if d is not None:
                        opts.append((L.hit(), d, L.h, g))
                on_minute(m, opts)
        self.prev = ix[upto, 3]

    def state(self):
        return {L.h: (len(L.rec), round(L.hit(), 3), L.ready()) for L in self.minds}


def trade(mk, und, d, call, m, h, ix, lot):
    """One Solo trade: ATM at the index close of minute m, nearest expiry strictly after d (the 'near' series when its
    expiry is after d; on an expiry day the next contract is not in the data -> None)."""
    ch = mk.options(und).chain(d, "near")
    if ch is None:
        return None
    st = STEP[und]
    k = int(math.floor(ix[m, 3] / st + 0.5) * st)
    r = "C" if call else "P"
    if k not in set(int(v) for v in ch.K):
        return None
    i = ch.kpos(k)
    o, hh, l, c = ch.o[r][i], ch.h[r][i], ch.l[r][i], ch.c[r][i]
    em = m + SETTLE + 1
    j0 = next((j for j in range(em, min(em + 4, 375)) if o[j] == o[j]), None)
    if j0 is None or j0 >= CUT:
        return None
    e = float(adverse_bps(np.array([o[j0]]), 5, True)[0])
    trig = math.floor(e * (1 - STOP) / 0.05 + 1e-9) * 0.05
    tx = min(em + h + 1, CUT)
    xm = xp = why = None
    for j in range(j0, 375):
        if j >= tx:
            jj = next((q for q in range(j, min(j + 6, 375)) if o[q] == o[q]), None)
            if jj is None:
                jj = j - 1
                while jj > j0 and c[jj] != c[jj]:
                    jj -= 1
                p = float(c[jj])
            else:
                p = float(o[jj])
            xm, xp, why = jj, float(adverse_bps(np.array([p]), 5, False)[0]), "cut" if tx == CUT and j >= CUT else "time"
            break
        if o[j] == o[j] and l[j] <= trig + 1e-9:
            xm, xp, why = j, float(adverse_bps(np.array([min(trig, float(o[j]))]), 10, False)[0]), "prem_stop"
            break
    if xm is None:
        return None
    ch_ = COST.charge_exact(True, e, lot) + COST.charge_exact(False, xp, lot)
    gross = (xp - e) * lot
    return dict(und=und, day=d, side=1 if call else -1, h=h, sig_m=m, strike=k, em=j0, xm=xm, entry=e, exit=xp,
                lot=lot, why=why, gross=round(gross, 2), charges=round(ch_, 2), net=round(gross - ch_, 2),
                series=ch.series)


def sessions(mk, und):
    ix = mk.index(und)
    out = []
    for d in ix.days:
        a, n = dense(ix.d[d])
        if a is None or n < 250:
            continue
        out.append((d, a, ix.lot(d), bool(ix.d[d].get("exp", False))))
    return out


def run_days(mk, S, trade_days, warm, cont=False):
    """S: {und: sessions}. warm: number of earlier sessions each brain learns from before the first trade day
    (None: from the first session of data). cont: one continuous brain trading every day in trade_days."""
    brains = {}
    rows = []
    first = min(trade_days)
    for u in ("NIFTY", "BANKNIFTY"):
        b = Brain()
        pre = [s for s in S[u] if s[0] < first]
        if warm is not None:
            pre = pre[-warm:] if warm > 0 else []
        for (d, a, lot, ex) in pre:
            b.day(a)
        brains[u] = b
    allp = sorted(set(d for u in S for (d, *_r) in S[u] if d >= first))
    byd = {u: {s[0]: s for s in S[u]} for u in S}
    for d in allp:
        trade_today = d in trade_days
        evs = {u: {} for u in brains}
        for u, b in brains.items():
            s = byd[u].get(d)
            if s is None:
                continue
            a = s[1]
            if trade_today:
                b.day(a, 374, lambda m, opts, u=u: evs[u].__setitem__(m, opts) if opts else None)
            else:
                b.day(a)
        if not trade_today:
            continue
        busy = -1; pnl = 0.0
        ms = sorted(set(m for u in evs for m in evs[u]))
        for m in ms:
            if m <= busy or m + 1 >= LAST_ENTRY or pnl <= -DAY_LOSS:
                continue
            for u in ("NIFTY", "BANKNIFTY"):
                opts = evs[u].get(m)
                if not opts:
                    continue
                best = max(opts, key=lambda o: o[0])
                s = byd[u][d]
                if s[3] or (u == "NIFTY" and d == date(2026, 10, 6)):
                    continue   # expiry day: the app buys the NEXT expiry, which the data does not hold
                t = trade(mk, u, d, best[1], m, best[2], s[1], s[2])
                if t is None:
                    continue
                t["p"] = round(best[3], 3); t["hit"] = round(best[0], 3)
                rows.append(t)
                pnl += t["net"]; busy = t["xm"]
                break
    return pd.DataFrame(rows), {u: b.state() for u, b in brains.items()}


def main():
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    for u in ("NIFTY", "BANKNIFTY"):
        H19.supplement(mk, u)
    S = {u: sessions(mk, u) for u in ("NIFTY", "BANKNIFTY")}
    res = {}
    targets = [date(2026, 10, 5)]
    for warm in (5, 10, 20, 40, 60, 120, 250, None):
        t, st = run_days(mk, S, set(targets), warm)
        res[warm] = (t, st)
        n = 0 if t.empty else len(t)
        print(f"warm={warm}: trades={n} net={0 if t.empty else t.net.sum():.0f} gross={0 if t.empty else t.gross.sum():.0f} state={st}",
              flush=True)
    # long run: one brain from the first session of data, trading every session from 2021-01-01
    alld = sorted(set(d for u in S for (d, *_r) in S[u] if d >= date(2021, 1, 1)))
    t, st = run_days(mk, S, set(alld), None, cont=True)
    res["long"] = (t, st)
    print("long trades", len(t), "net", t.net.sum(), flush=True)
    with open(os.path.join(OUT, "solo_learner.pkl"), "wb") as f:
        pickle.dump(res, f)


if __name__ == "__main__":
    main()
