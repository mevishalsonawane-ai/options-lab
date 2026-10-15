"""R59 step 3: the honest tests on HELD contracts (PREREG.md).

  skeleton()   for one (session, bar, side): the ATM contract at the bar's close, its entry row (first row in the 5
               minutes after the bar, stray-guarded open), and its exit row (first row at/after entry + 60 or 15:25,
               within 5 minutes) or the fallback (last close before the exit minute, thin-market fill)
  price()      fills (5 bps + the larger of R17's 1/2/4 ticks and the stock's Roll half-spread, x k), the S25 stop on
               1-minute rows (gap fills at the open), 1 lot in force, Costs('dated') charges x k
  signals_A()  their drop measured on the held contract (option-chart signal: NON-DEFAULT)
  signals_B()  the stock's own 15-minute move against the option (default-compatible)
  run_cell()   no overlap per stock and side; trades and 500 random-entry twin sets
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np

import r59cfg as K
from obuy.costs import Costs

N = K.N
COSTS = Costs("dated")
EPOCH = date(1970, 1, 1).toordinal()


def _round2(x):
    return np.floor(float(x) * 100.0 + 0.5) / 100.0


# ------------------------------------------------------------------------------------------------ row helpers
def row_range(bk, cid, g0, g1):
    """Row indices [a, b) of contract cid with g0 <= g <= g1."""
    s, e = bk.cstart[cid], bk.cend[cid]
    a = s + int(np.searchsorted(bk.g[s:e], g0, side="left"))
    b = s + int(np.searchsorted(bk.g[s:e], g1, side="right"))
    return a, b


def bar_last_row(bk, cid, di, b):
    """Last row of cid in bar b of session di, or -1."""
    g0 = di * N + b * K.BAR
    a, z = row_range(bk, cid, g0, g0 + K.BAR - 1)
    return z - 1 if z > a else -1


def atm_at_bar(bk, di, b, sd, fresh=K.FRESH_OPT):
    """The contract on offset 0 at the latest minute of bar b that has one; -1 if none in the bar's last `fresh`
    minutes."""
    for m in range(b * K.BAR + K.BAR - 1, b * K.BAR + K.BAR - 1 - fresh, -1):
        c = bk.atm[di, m, sd]
        if c >= 0:
            return int(c)
    return -1


def ref_close(c, s, e, r):
    a = c[r - 1] if r - 1 >= s else c[r]
    b = c[r]
    d = c[r + 1] if r + 1 < e else c[r]
    return max(min(a, b), min(max(a, b), d))


def _stray(p, ref):
    return abs(p - ref) > 2 * K.TICK + 1e-9 and (ref <= 0 or abs(p / ref - 1.0) > K.BAD_PRINT)


def open_for_buy(bk, cid, r):
    s, e = bk.cstart[cid], bk.cend[cid]
    ref = ref_close(bk.c, s, e, r)
    return max(bk.o[r], ref) if _stray(bk.o[r], ref) else bk.o[r]


def open_for_sell(bk, cid, r):
    s, e = bk.cstart[cid], bk.cend[cid]
    ref = ref_close(bk.c, s, e, r)
    return min(bk.o[r], ref) if _stray(bk.o[r], ref) else bk.o[r]


def lots5_before(bk, cid, r, lot):
    s = bk.cstart[cid]
    g0 = bk.g[r] - 5
    q, tot = r - 1, 0.0
    while q >= s and bk.g[q] >= g0:
        tot += bk.v[q]
        q -= 1
    return tot / lot if lot else 0.0


# ------------------------------------------------------------------------------------------------ fills
def ticks(lots5):
    return 1.0 if lots5 >= 20.0 else (2.0 if lots5 > 0.0 else 4.0)


def half_spread(p, lots5, roll_h, k=1.0):
    return k * max(ticks(lots5) * K.TICK, roll_h * p)


def buy_fill(p, lots5, roll_h, k=1.0):
    return _round2(p * (1 + K.BPS) + half_spread(p, lots5, roll_h, k))


def sell_fill(p, lots5, roll_h, k=1.0, stop=False):
    if stop:
        x = p * (1 - K.BPS_STOP) - 2 * half_spread(p, lots5, roll_h, k)
    else:
        x = p * (1 - K.BPS) - half_spread(p, lots5, roll_h, k)
    return max(_round2(x), 0.0)


def charges(buy, price, qty, d_ord, k=1.0):
    return float(COSTS.charge(buy, np.array([price]), np.array([qty]), np.array([d_ord - EPOCH]))[0]) * k


# ------------------------------------------------------------------------------------------------ one trade
@dataclass
class Skel:
    di: int
    b: int
    sd: int
    cid: int
    r0: int                 # entry row
    eopen: float            # stray-guarded open of the entry row
    lot: int
    l5_in: float
    X: int                  # exit minute (in the session)
    rx: int                 # exit row, or -1 (fallback)
    xpx: float              # raw exit price (guarded open, or the fallback close)
    l5_out: float


def skeleton(bk, di, b, sd, cid=None):
    """Entry and exit rows of the ATM contract of side sd at bar b of session di (None if no fill)."""
    if cid is None:
        cid = atm_at_bar(bk, di, b, sd)
    if cid < 0:
        return None
    G0 = di * N
    m_in = b * K.BAR + K.BAR
    if m_in > N - 1:
        return None
    a, z = row_range(bk, cid, G0 + m_in, G0 + min(m_in + K.WAIT - 1, N - 1))
    if z <= a:
        return None
    r0 = a
    lot = bk.lots.lot(int(bk.cexp[cid]), int(bk.days[di]))
    if lot <= 0:
        return None
    m0 = int(bk.g[r0] - G0)
    X = min(m0 + K.HOLD, K.EXIT_CAP)
    if X <= m0:
        return None
    a2, z2 = row_range(bk, cid, G0 + X, G0 + min(X + K.WAIT - 1, N - 1))
    if z2 > a2:
        rx, xpx, l5o = a2, open_for_sell(bk, cid, a2), lots5_before(bk, cid, a2, lot)
    else:
        a3, z3 = row_range(bk, cid, bk.g[r0], G0 + X - 1)
        rx, xpx, l5o = -1, bk.c[z3 - 1], 0.0
    return Skel(di, b, sd, cid, r0, float(open_for_buy(bk, cid, r0)), lot, lots5_before(bk, cid, r0, lot), X, rx,
                float(xpx), l5o)


def price(bk, s: Skel, variant: str, roll_h: float, k: float = 1.0) -> dict:
    """Net Rs of one lot, entry and exit fills, exit reason."""
    fill = buy_fill(s.eopen, s.l5_in, roll_h, k)
    G0 = s.di * N
    reason, exit_g = "time", G0 + s.X
    if s.rx < 0:
        reason = "fallback"
        xfill = sell_fill(s.xpx, 0.0, roll_h, k)
    else:
        xfill = sell_fill(s.xpx, s.l5_out, roll_h, k)
    if variant == "S25":
        L = (1 - K.STOP) * fill
        a, z = row_range(bk, s.cid, bk.g[s.r0], G0 + s.X - 1)
        for r in range(a, z):
            if r > s.r0:
                op = open_for_sell(bk, s.cid, r)
                if op <= L:
                    xfill = sell_fill(op, lots5_before(bk, s.cid, r, s.lot), roll_h, k, stop=True)
                    reason, exit_g = "stop_gap", int(bk.g[r])
                    break
            if bk.l[r] <= L:
                xfill = sell_fill(L, lots5_before(bk, s.cid, r, s.lot), roll_h, k, stop=True)
                reason, exit_g = "stop", int(bk.g[r])
                break
    d = int(bk.days[s.di])
    qty = s.lot
    ch = charges(True, fill, qty, d, k) + charges(False, xfill, qty, d, k)
    gross = (xfill - fill) * qty
    return dict(fill=fill, xfill=xfill, gross=gross, charges=ch, net=gross - ch, reason=reason, exit_g=exit_g,
                entry_g=int(bk.g[s.r0]), capital=fill * qty, lot=qty)


# ------------------------------------------------------------------------------------------------ signals
def signals_A(bk, sd, days_ok=None):
    """(di, b, cid) where the ATM contract at bar b fell 20%+ on ITS OWN prices from bar b-1 (same session) and closes
    >= 5. Its last trade in bar b must be in the bar's last 5 minutes."""
    out = []
    for di in range(len(bk.days)):
        if bk.skip[di] or (days_ok is not None and not days_ok[di]):
            continue
        for b in range(1, K.NBARS - 1):
            cid = atm_at_bar(bk, di, b, sd)
            if cid < 0:
                continue
            rt = bar_last_row(bk, cid, di, b)
            rp = bar_last_row(bk, cid, di, b - 1)
            if rt < 0 or rp < 0:
                continue
            if bk.g[rt] < di * N + b * K.BAR + K.BAR - K.FRESH_OPT:
                continue
            ct, cp = bk.c[rt], bk.c[rp]
            if ct >= K.MIN_PREM and cp > 0 and (ct - cp) / cp * 100 <= K.DROP:
                out.append((di, b, cid))
    return out


def eq_bar_close(bk, di, b, fresh=None):
    m1 = b * K.BAR + K.BAR - 1
    lo = b * K.BAR if fresh is None else m1 - fresh + 1
    row = bk.eqc[di, lo:m1 + 1]
    ok = np.flatnonzero(np.isfinite(row))
    return float(row[ok[-1]]) if len(ok) else np.nan


def signals_B(bk, sd, days_ok=None):
    """(di, b, cid): the stock moved 1.0%+ against the option in bar b (CE: fell, PE: rose), same session; the ATM
    contract of that side at bar b."""
    out = []
    for di in range(len(bk.days)):
        if bk.skip[di] or (days_ok is not None and not days_ok[di]):
            continue
        for b in range(1, K.NBARS - 1):
            ct = eq_bar_close(bk, di, b, K.FRESH_EQ)
            cp = eq_bar_close(bk, di, b - 1)
            if not (np.isfinite(ct) and np.isfinite(cp) and cp > 0):
                continue
            r = ct / cp - 1
            if (sd == 0 and r <= -K.STOCK_MOVE) or (sd == 1 and r >= K.STOCK_MOVE):
                cid = atm_at_bar(bk, di, b, sd)
                if cid >= 0:
                    out.append((di, b, cid))
    return out


# ------------------------------------------------------------------------------------------------ cells
def no_overlap(skels, priced):
    """Keep a trade only if it enters after the previous kept trade's exit (one position per stock and side)."""
    keep, last_exit = [], -1
    for s, p in zip(skels, priced):
        if p["entry_g"] > last_exit:
            keep.append(True)
            last_exit = p["exit_g"]
        else:
            keep.append(False)
    return keep


def trades_for(bk, sigs, variant, roll_h):
    """Priced, non-overlapping trades of a signal list: list of dicts (1x and 2x costs)."""
    sk, pr = [], []
    for di, b, cid in sigs:
        s = skeleton(bk, di, b, int(bk.cside[cid]), cid)
        if s is None:
            continue
        sk.append(s)
        pr.append(price(bk, s, variant, roll_h, 1.0))
    keep = no_overlap(sk, pr)
    out = []
    for s, p, kp in zip(sk, pr, keep):
        if not kp:
            continue
        p2 = price(bk, s, variant, roll_h, 2.0)
        out.append(dict(sym=bk.sym, side="CE" if s.sd == 0 else "PE", date=date.fromordinal(int(bk.days[s.di])).isoformat(),
                        di=s.di, bar=s.b, strike=float(bk.cstrike[s.cid]),
                        expiry=date.fromordinal(int(bk.cexp[s.cid])).isoformat(), lot=p["lot"], fill=p["fill"],
                        xfill=p["xfill"], net=round(p["net"], 2), net2=round(p2["net"], 2), charges=round(p["charges"], 2),
                        capital=round(p["capital"], 0), reason=p["reason"]))
    return out


def twin_pool(bk, sd, variant, roll_h, days_ok=None):
    """Per session: net (1x) of every eligible bar's ATM trade (bars 2-24 whose entry fills), for random twins."""
    pool = {}
    for di in range(len(bk.days)):
        if bk.skip[di] or (days_ok is not None and not days_ok[di]):
            continue
        v = []
        for b in range(1, K.NBARS - 1):
            s = skeleton(bk, di, b, sd)
            if s is not None:
                v.append(price(bk, s, variant, roll_h, 1.0)["net"])
        if v:
            pool[di] = np.array(v)
    return pool


def twin_totals(trades, pools, rng, n=K.TWINS):
    """Totals of n twin sets: every trade re-entered at a random eligible bar of its session (same stock and side)."""
    tot = np.zeros(n)
    for t in trades:
        p = pools.get((t["sym"], t["side"]), {}).get(t["di"])
        if p is None or not len(p):
            continue
        tot += p[rng.integers(0, len(p), n)]
    return tot


def pvalue(net, twins):
    return (1 + int(np.sum(twins >= net))) / (1 + len(twins))


def metrics(tr, twins=None):
    if not tr:
        return dict(trades=0, net=0.0, net2=0.0, p=None)
    n = np.array([t["net"] for t in tr])
    n2 = np.array([t["net2"] for t in tr])
    cap = np.array([t["capital"] for t in tr])
    top5 = np.sort(n)[::-1][:5].sum()
    out = dict(trades=int(len(n)), win=round(float((n > 0).mean()) * 100, 1), per_trade=round(float(n.mean()), 0),
               median=round(float(np.median(n)), 0), net=round(float(n.sum()), 0), net2=round(float(n2.sum()), 0),
               worst=round(float(n.min()), 0), net_wo_top5=round(float(n.sum() - top5), 0),
               cap_median=round(float(np.median(cap)), 0), cap_max=round(float(cap.max()), 0),
               fallback_exits=int(sum(t["reason"] == "fallback" for t in tr)),
               stops=int(sum(t["reason"].startswith("stop") for t in tr)))
    if twins is not None:
        out["p"] = round(pvalue(float(n.sum()), twins), 4)
        out["twin_mean_total"] = round(float(np.mean(twins)), 0)
    return out


def bh(pvals):
    """Benjamini-Hochberg q-values (same order as pvals; None stays None)."""
    idx = [i for i, p in enumerate(pvals) if p is not None]
    q = [None] * len(pvals)
    if not idx:
        return q
    ps = np.array([pvals[i] for i in idx], dtype=float)
    m = len(ps)
    order = np.argsort(ps)
    adj = ps[order] * m / (np.arange(m) + 1)
    adj = np.minimum.accumulate(adj[::-1])[::-1]
    adj = np.minimum(adj, 1.0)
    for rank, j in enumerate(order):
        q[idx[j]] = round(float(adj[rank]), 4)
    return q


# ------------------------------------------------------------------------------------------------ Roll spread
def roll_spread(bk, sd, days_ok=None, min_pairs=K.ROLL_MIN_PAIRS):
    """Median over ATM contract-days of the Roll relative spread 2*sqrt(-cov(dlog c_t, dlog c_{t-1})) (0 if cov >= 0),
    from consecutive-minute closes. Returns (median spread, contract-days used)."""
    vals = []
    for di in range(len(bk.days)):
        if days_ok is not None and not days_ok[di]:
            continue
        cids = np.unique(bk.atm[di, :, sd])
        for cid in cids[cids >= 0]:
            a, z = row_range(bk, int(cid), di * N, di * N + N - 1)
            if z - a < min_pairs + 2:
                continue
            g, c = bk.g[a:z], bk.c[a:z]
            ok = c > 0
            g, c = g[ok], c[ok]
            dl = np.diff(np.log(c))
            one = np.diff(g) == 1
            pair = one[1:] & one[:-1]
            if pair.sum() < min_pairs:
                continue
            x, y = dl[1:][pair], dl[:-1][pair]
            cov = float(np.mean(x * y) - np.mean(x) * np.mean(y))
            vals.append(2 * np.sqrt(-cov) if cov < 0 else 0.0)
    return (float(np.median(vals)) if vals else 0.0), len(vals)
