"""Boss's paper fills on Thu 08 Oct 2026 (from his diagnostics paste), the app's charges, and the minute-by-minute day P&L.

Charges: SandboxCosts.charge (android/engine/.../sandbox/SandboxCosts.kt): Rs 20/leg, STT 0.15% on sells, exchange 0.03553%,
SEBI Rs 10/crore, stamp 0.003% on buys, GST 18% on brokerage + exchange + SEBI.
Two ORB exits are not in Boss's log (12:51 and 13:15 entries): both reached the +40 target on the minute highs within
minutes (12:56 and 13:21) and a later ORB entry followed, so they are booked at the +40 target level (estimate, marked est).
"""
from __future__ import annotations
from dataclasses import dataclass
import bars as B

LOT = {"BANKNIFTY": 30, "FINNIFTY": 60, "NIFTY": 65, "MIDCPNIFTY": 120}


def charge(side: str, price: float, qty: int) -> float:
    v = price * qty
    if v <= 0: return 0.0
    buy, sell = (v, 0.0) if side == "BUY" else (0.0, v)
    txn = (buy + sell) * 0.0003553
    sebi = (buy + sell) * 10 / 1e7
    return round(20 + sell * 0.0015 + txn + sebi + buy * 0.00003 + (20 + txn + sebi) * 0.18, 2)


def rt_per_unit(entry: float, qty: int) -> float:
    """ProfitLock.roundTripPerUnit: the breakeven-after-charges add-on per unit."""
    b = charge("BUY", entry, qty)
    first = (b + charge("SELL", entry, qty)) / qty
    return (b + charge("SELL", entry + first, qty)) / qty


@dataclass
class T:
    n: int
    arm: str
    feed: str          # bars.py name
    und: str
    t_in: str          # HH:MM:SS
    p_in: float
    t_out: str
    p_out: float
    note: str = ""
    @property
    def qty(self): return LOT[self.und]
    @property
    def gross(self): return (self.p_out - self.p_in) * self.qty
    @property
    def chg(self): return charge("BUY", self.p_in, self.qty) + charge("SELL", self.p_out, self.qty)
    @property
    def net(self): return self.gross - self.chg


TRADES = [
    T(1, "Liquidity", "BANKNIFTY_54900PE", "BANKNIFTY", "09:22:03", 694.20, "09:23:54", 668.10),
    T(2, "Liquidity", "MIDCP_13675PE", "MIDCPNIFTY", "09:22:13", 219.20, "09:25:36", 238.75, "contract matched from minute prices"),
    T(3, "Pine", "FINNIFTY_24800PE", "FINNIFTY", "09:30:10", 337.22, "10:12:13", 327.20),
    T(4, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "11:31:05", 759.18, "11:36:50", 714.19),
    T(5, "Liquidity", "BANKNIFTY_54800PE", "BANKNIFTY", "11:31:19", 705.00, "11:33:08", 699.70),
    T(6, "Liquidity", "FINNIFTY_24750PE", "FINNIFTY", "11:31:30", 357.80, "11:48:46", 336.95),
    T(7, "ORB Sweep", "BANKNIFTY_54900CE", "BANKNIFTY", "11:41:26", 825.15, "11:58:28", 842.85),
    T(8, "Solo", "NIFTY_13OCT_22550PE", "NIFTY", "12:00:53", 261.33, "14:31:09", 327.40),
    T(9, "Range Fade", "BANKNIFTY_54900CE", "BANKNIFTY", "12:05:53", 835.95, "12:15:37", 838.66),
    T(10, "ORB Sweep", "BANKNIFTY_54900CE", "BANKNIFTY", "12:21:00", 833.00, "12:31:48", 792.21),
    T(11, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "12:38:08", 773.70, "12:40:27", 779.87),
    T(12, "Liquidity", "BANKNIFTY_54800PE", "BANKNIFTY", "12:38:20", 731.60, "12:45:11", 735.55),
    T(13, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "12:51:37", 771.90, "12:56:30", 811.90, "exit est: +40 target (12:56 high 813.60)"),
    T(14, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "13:05:25", 767.00, "13:08:22", 774.62),
    T(15, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "13:15:33", 795.85, "13:21:15", 835.85, "exit est: +40 target (13:21 open 839.05)"),
    T(16, "Liquidity", "BANKNIFTY_54700PE", "BANKNIFTY", "13:20:55", 714.50, "13:40:44", 699.40),
    T(17, "Pine", "FINNIFTY_24650PE", "FINNIFTY", "13:31:23", 368.90, "13:39:06", 332.40),
    T(18, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "13:40:53", 792.40, "13:45:52", 853.90),
    T(19, "ORB", "BANKNIFTY_54900PE", "BANKNIFTY", "13:55:52", 835.35, "13:59:24", 883.85),
    T(20, "Pine", "FINNIFTY_24600PE", "FINNIFTY", "14:02:11", 351.80, "14:16:50", 357.25),
]


def minutes(a="09:15", b="15:29"):
    out, h, m = [], int(a[:2]), int(a[3:])
    while f"{h:02d}:{m:02d}" <= b:
        out.append(f"{h:02d}:{m:02d}"); m += 1
        if m == 60: h, m = h + 1, 0
    return out


_cache = {}
def closes(feed):
    if feed not in _cache:
        rows = B.load(feed)
        d = {r[0]: r for r in rows}
        full, last = {}, None
        for mm in minutes():
            if mm in d: last = d[mm]
            if last: full[mm] = last if mm in d else (mm, last[4], last[4], last[4], last[4], 0)
        _cache[feed] = full
    return _cache[feed]


def curve(trades=TRADES, exits=None):
    """Minute -> (realised net, open MTM net of buy charges, total). [exits] overrides (t_out, p_out) per trade n."""
    out = {}
    for mm in minutes():
        end = mm + ":59"
        real = opn = 0.0
        for t in trades:
            t_out, p_out = (exits or {}).get(t.n, (t.t_out, t.p_out))
            if t.t_in > end: continue
            if t_out <= end:
                real += (p_out - t.p_in) * t.qty - charge("BUY", t.p_in, t.qty) - charge("SELL", p_out, t.qty)
            else:
                c = closes(t.feed)[mm][4]
                opn += (c - t.p_in) * t.qty - charge("BUY", t.p_in, t.qty)
        out[mm] = (real, opn, real + opn)
    return out


def curve_gross(trades=TRADES, field=4):
    """Before charges (what the app's screens show), open positions marked at the minute's [field] (4 close, 2 high, 3 low)."""
    out = {}
    for mm in minutes():
        end = mm + ":59"
        real = opn = 0.0
        for t in trades:
            if t.t_in > end: continue
            if t.t_out <= end: real += (t.p_out - t.p_in) * t.qty
            else: opn += (closes(t.feed)[mm][field] - t.p_in) * t.qty
        out[mm] = (real, opn, real + opn)
    return out
