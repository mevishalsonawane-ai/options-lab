"""R92 engine: the ORB family's app rules (R3's rebuild, R49's dated charges) sliced by TIME (PREREG.md A3 / A4).

How it works:
1. `outcome_table(s, tmult, sq)`: for one session, the result of entering 1 lot of the ATM contract on every 5-minute
   decision bar b and both sides (R3 / R49's simulate: fill at the next minute's open, -P stop, +tmult*P target, the
   ladder, square-off at `sq`). An entry is fully decided by (session, side, bar, exits), so every arm, slot and twin is
   a lookup in this table.
2. `signals(s, arm, m)`: each arm's signal on every bar, from the INDEX's 5-minute bars and an m-minute opening range.
3. `run_arm(...)`: the arm's own loop (1 entry a side a day, at most 2 a day, cooldown to the bar after the exit, a
   refused premium uses no cap), with an optional set of allowed bars (the ANY-mode time filter).

Clock: grid row t = the 1-minute bar that STARTS at 09:15 + t. 5-minute bar b covers rows 5b .. 5b+4; its decision is
known at its close, the ENTRY MINUTE 09:15 + 5b + 5, whose open fills the order. Every time in R92 is that entry minute.
Nothing here reads an index row after the decision to decide anything (test_r92.py: the look-ahead guard).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

import numpy as np

import r92cfg as K
import r3cfg as K3
import r3engine as E
import r49engine as R49
from r3data import C, H, L, O, Sess

NB = 75                              # 5-minute bars in a session
DAY0 = 9 * 60 + 15                   # 09:15 in clock minutes
SQ = {"13:30": 255, "14:30": 315, "15:10": E.SQ_ROW}
ARMS = ("orb", "orb_fresh", "orb_sweep", "range_fade")
NAME = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep", "range_fade": "Range Fade"}
TMULT = {"orb": 1.0, "orb_fresh": 1.0, "orb_sweep": 2.0, "range_fade": 1.0}
APP_LAST = {"orb": 55, "orb_fresh": 55, "orb_sweep": 62, "range_fade": 55}
EXT_LAST = 62                        # entry 14:30 (Sweep's app window end)
MAX_DAY = 2
WHY = E.WHY
FIRST_BAR, LAST_BAR = 3, 62          # every bar any cell can decide on (M = 15 starts at bar 3)


def entry_min(b: int) -> int:
    """Clock minute (since midnight) of the entry for decision bar b."""
    return DAY0 + 5 * b + 5


def hm(t: int) -> str:
    return f"{t // 60:02d}:{t % 60:02d}"


def clock(s: str) -> int:
    h, m = s.split(":")
    return int(h) * 60 + int(m)


def bars_in(lo: str, hi: str) -> list[int]:
    """Decision bars whose entry minute is in [lo, hi] (clock strings, both ends included)."""
    a, z = clock(lo), clock(hi)
    return [b for b in range(NB) if a <= entry_min(b) <= z]


def window(arm: str, m: int = 50, ext: bool = False, last_entry: str | None = None) -> tuple[int, int]:
    """First and last decision bar: R49's rule for an m-minute range (Fade 5 bars later), the app's last bar (EXT: 14:30),
    cut so the entry is at or before `last_entry`."""
    nb = m // 5
    first = nb + (5 if arm == "range_fade" else 0)
    last = EXT_LAST if ext else APP_LAST[arm]
    if last_entry is not None:
        last = min(last, max(b for b in range(NB) if entry_min(b) <= clock(last_entry)))
    return first, last


# ------------------------------------------------------------------------------------------------ one trade
def simulate(s: Sess, tmult: float, side: int, sig_row: int, P: float, sq_row: int = E.SQ_ROW) -> dict | None:
    """R49's simulate (R3's fills, exit walk and ladder; the charges of the trade date) with a chosen square-off row
    (the earlier of it and 15:05 on an expiry day) and the underlying's points. One held contract: the ATM of the 09:20
    bar, side CE (+1) / PE (-1), the nearest expiry."""
    k = s.atm
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return None
    if a.dtype != np.float64:
        a = a.astype(np.float64)
    c0, px = E.next_open(a, sig_row + 1, sig_row + 1 + E.FILL_TRIES)
    if c0 < 0 or px <= P:
        return None
    qty = s.lot
    e = E.buy_px(px, a, c0, s.lot)
    trig = E.floor_tick(e - P)
    tgt = tmult * P
    lad_t = tmult * P
    be = e + R49.be_per_unit(e, qty, s.und, s.day)
    sq = min(sq_row, E.SQ_EXPIRY) if s.expiry else sq_row
    if c0 >= sq:
        return None
    r, p, why = E._walk(a[:, O], a[:, H], a[:, L], a[:, C], c0, e, trig, tgt, lad_t, be, float(qty),
                        0.0, 0.0, 0.0, 0.0, -1, 0.0, sq)
    stale = False
    if why in (0, 1):
        x = E.sell_px(p, a, r, s.lot, stop=True)
    else:
        rr, p2 = E.next_open(a, r)
        if rr < 0:                       # the strike left the stored band: its last stored print (R3 A3)
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            p2 = float(a[rr, C])
            stale = True
        r, x = rr, E.sell_px(p2, a, rr, s.lot)
    ch = R49.charge(True, e, qty, s.und, s.day) + R49.charge(False, x, qty, s.und, s.day)
    gross = (x - e) * qty
    return dict(entry_row=c0, exit_row=int(r), entry=e, exit=x, why=5 if stale else int(why), qty=qty,
                gross=gross, charges=ch, net=gross - ch, pts=side * (ix_px(s.ix, r) - ix_px(s.ix, c0)))


def ix_px(ix: np.ndarray, r: int) -> float:
    """The index at the open of row r (the last close before it when that minute has no bar)."""
    r = min(r, len(ix) - 1)
    if np.isfinite(ix[r, 0]):
        return float(ix[r, 0])
    prev = np.where(np.isfinite(ix[:r, 3]))[0]
    return float(ix[prev[-1], 3]) if len(prev) else np.nan


@dataclass
class Table:
    """Outcomes of an entry on bar b, side j (0 = CE, 1 = PE). exit_row -1 = no trade (no print / refused premium)."""
    net: np.ndarray        # (2, NB)
    exit_row: np.ndarray   # (2, NB) int
    pts: np.ndarray        # (2, NB)
    why: np.ndarray        # (2, NB) int


def outcome_table(s: Sess, tmult: float, sq_row: int = E.SQ_ROW, bars=range(FIRST_BAR, LAST_BAR + 1)) -> Table:
    net = np.zeros((2, NB))
    xr = np.full((2, NB), -1, dtype=np.int32)
    pts = np.zeros((2, NB))
    why = np.full((2, NB), -1, dtype=np.int8)
    if np.isfinite(s.atm):
        P = K3.PTS[s.und]
        for j, side in enumerate((1, -1)):
            for b in bars:
                t = simulate(s, tmult, side, 5 * b + 4, P, sq_row)
                if t is None:
                    continue
                net[j, b], xr[j, b], pts[j, b], why[j, b] = t["net"], t["exit_row"], t["pts"], t["why"]
    return Table(net, xr, np.nan_to_num(pts), why)


# ------------------------------------------------------------------------------------------------ signals
def signals(s: Sess, arm: str, m: int = 50) -> np.ndarray:
    """The arm's signal on every decision bar (+1 CE, -1 PE, 0), from the index's 5-minute bars and an m-minute range.
    Bar b's signal reads bars <= b only. All zero when the range's last bar is missing."""
    b5 = E.bars5(s.ix)
    nb = m // 5
    out = np.zeros(NB, dtype=np.int8)
    if not np.isfinite(b5[nb - 1, 3]):
        return out
    hi, lo = float(np.nanmax(b5[:nb, 1])), float(np.nanmin(b5[:nb, 2]))
    for b in range(nb, NB):
        out[b] = E.signal(arm, b5, b, hi, lo)
    return out


def run_arm(sig: np.ndarray, tab: Table, first: int, last: int, allowed: set | None = None) -> list[tuple[int, int]]:
    """The arm's loop (R3's run_session with the 1-a-side cap for every arm and at most 2 a day). Returns (side, bar)
    of each trade. `allowed`: the ANY-mode time filter; a signal on another bar is ignored and uses no cap."""
    out, per, n, free = [], {1: 0, -1: 0}, 0, -1
    for b in range(first, last + 1):
        if b <= free:
            continue
        if n >= MAX_DAY:
            break
        sd = int(sig[b])
        if not sd or per[sd] >= 1:
            continue
        if allowed is not None and b not in allowed:
            continue
        j = 0 if sd > 0 else 1
        xr = int(tab.exit_row[j, b])
        if xr < 0:
            continue
        out.append((sd, b))
        per[sd] += 1
        n += 1
        free = xr // 5
    return out


# ------------------------------------------------------------------------------------------------ day types
def day_types(cal: list[tuple[date, bool]], last_day: date = K.LAST_DAY) -> dict:
    """Per session: weekday, trading days to the contract's expiry (0 / 1 / 2 / 3+; None when unknown at the lake's end)
    and the month position (first3 / mid / last3), on the index's own session calendar (dates only)."""
    days = [d for d, _ in cal]
    exp = [e for _, e in cal]
    out = {}
    nxt = None
    for i in range(len(days) - 1, -1, -1):
        if exp[i]:
            nxt = i
        if nxt is not None:
            dte = nxt - i
        else:
            left = len(days) - 1 - i
            dte = 3 if left >= 2 else None
        out[days[i]] = {"weekday": days[i].weekday(), "dte": None if dte is None else min(dte, 3)}
    # month position; the lake's last month is incomplete when the next business day after last_day is in the same month
    nb = last_day + timedelta(days=1)
    while nb.weekday() >= 5:
        nb += timedelta(days=1)
    open_month = (last_day.year, last_day.month) if nb.month == last_day.month else None
    by_m = {}
    for d in days:
        by_m.setdefault((d.year, d.month), []).append(d)
    for ym, ds in by_m.items():
        for i, d in enumerate(ds):
            if i < 3:
                pos = "first3"
            elif ym != open_month and i >= len(ds) - 3:
                pos = "last3"
            else:
                pos = "mid"
            out[d]["mpos"] = pos
    return out


# ------------------------------------------------------------------------------------------------ the cells
@dataclass(frozen=True)
class Cell:
    id: str
    family: str                      # T | R | D | X | C | H12
    arm: str
    mode: str = "ANY"                # ANY | FIRST | APP (the app arm, all its trades) | ATTR (app trades in the slot)
    slot: tuple = ()                 # (lo, hi) entry-minute clock strings
    ext: bool = False                # the window extended to 14:30
    m: int = 50
    exit: str = "15:10"
    day: tuple = ()                  # (key, value) day-type filter, e.g. ("weekday", 0); ("expiry", True)
    unds: tuple = K.UNDS
    label: str = ""

    @property
    def tmult(self):
        return TMULT[self.arm]

    def win(self) -> tuple[int, int]:
        le = None
        if self.exit != "15:10":
            le = hm(clock(self.exit) - 15)
        return window(self.arm, self.m, self.ext, le)

    def slot_bars(self) -> set:
        return set(bars_in(*self.slot)) if self.slot else set()


HOURS = [("10:00", "10:59"), ("11:00", "11:59"), ("12:00", "12:59"), ("13:00", "13:59"), ("14:00", "14:30")]
HALF = [("10:00", "10:29"), ("10:30", "10:59"), ("11:00", "11:29"), ("11:30", "11:59"), ("12:00", "12:29"),
        ("12:30", "12:59"), ("13:00", "13:29"), ("13:30", "13:59"), ("14:00", "14:30")]
QUARTER = [(hm(t), hm(t + 14)) for t in range(clock("10:00"), clock("14:00"), 15)] + [("14:00", "14:14"),
                                                                                      ("14:15", "14:30")]
ARM_ID = {"orb": "orb", "orb_fresh": "fresh", "orb_sweep": "sweep", "range_fade": "fade"}


def _slot_ok(arm: str, slot: tuple) -> tuple[bool, bool]:
    """(exists, ext) for an arm's slot: it exists when an entry minute of the slot is in the arm's window extended to
    14:30; it is EXT when no entry minute of it is in the app window."""
    f, _ = window(arm)
    bs = [b for b in bars_in(*slot) if f <= b <= EXT_LAST]
    if not bs:
        return False, False
    app = [b for b in bs if b <= APP_LAST[arm]]
    return True, not app


def _sid(lo, hi):
    return f"{lo.replace(':', '')}-{hi.replace(':', '')}"


def cells() -> list[Cell]:
    out = []
    # T: entry-time slots
    for arm in ARMS:
        for grp, slots, modes in (("h", HOURS, ("ANY", "FIRST")), ("30", HALF, ("ANY", "FIRST")), ("15", QUARTER, ("ANY",))):
            for mode in modes:
                if mode == "FIRST" and arm == "orb_fresh":
                    continue
                for sl in slots:
                    ok, ext = _slot_ok(arm, sl)
                    if not ok:
                        continue
                    out.append(Cell(f"r92-t-{ARM_ID[arm]}-{mode.lower()}-{grp}-{_sid(*sl)}", "T", arm, mode, sl,
                                    ext=ext or mode == "FIRST",
                                    label=f"{NAME[arm]} {mode} {sl[0]}-{sl[1]}" + (" EXT" if ext else "")))
    three = ("orb", "orb_sweep", "range_fade")
    # R: opening-range length
    for arm in three:
        for m in (15, 30, 50, 60, 75):
            out.append(Cell(f"r92-r-{ARM_ID[arm]}-m{m}", "R", arm, "APP", m=m, label=f"{NAME[arm]} range {m} min"))
    # D: day type
    wd = ("Mon", "Tue", "Wed", "Thu", "Fri")
    for arm in three:
        for i, w in enumerate(wd):
            out.append(Cell(f"r92-d-{ARM_ID[arm]}-{w.lower()}", "D", arm, "APP", day=("weekday", i), label=f"{NAME[arm]} {w}"))
        for dte in (0, 1, 2, 3):
            lab = "expiry day" if dte == 0 else f"{dte}{'+' if dte == 3 else ''} day{'s' if dte > 1 else ''} to expiry"
            out.append(Cell(f"r92-d-{ARM_ID[arm]}-dte{dte}", "D", arm, "APP", day=("dte", dte), label=f"{NAME[arm]} {lab}"))
        for pos in ("first3", "mid", "last3"):
            out.append(Cell(f"r92-d-{ARM_ID[arm]}-{pos}", "D", arm, "APP", day=("mpos", pos), label=f"{NAME[arm]} month {pos}"))
    # X: exit time
    for arm in three:
        for x in ("13:30", "14:30", "15:10"):
            out.append(Cell(f"r92-x-{ARM_ID[arm]}-{x.replace(':', '')}", "X", arm, "APP", exit=x,
                            label=f"{NAME[arm]} out at {x}"))
    # C: hour x expiry day / not
    for arm in three:
        for sl in HOURS:
            ok, ext = _slot_ok(arm, sl)
            for ex in (True, False):
                out.append(Cell(f"r92-c-{ARM_ID[arm]}-h{sl[0][:2]}-{'exp' if ex else 'nonexp'}", "C", arm, "ANY", sl,
                                ext=ext, day=("expiry", ex),
                                label=f"{NAME[arm]} {sl[0]}-{sl[1]}{' EXT' if ext else ''} {'expiry' if ex else 'non-expiry'}"))
    # H12: R3's lead, on the indices R3's hour row did not use
    out.append(Cell("r92-h12-orb-1200-1259", "H12", "orb", "ATTR", ("12:00", "12:59"), unds=K.FRESH_UNDS,
                    label="ORB 12:00-12:59 (post hoc from R3), FINNIFTY + MIDCPNIFTY + SENSEX"))
    return out


def tables_needed(cs: list[Cell]) -> set:
    return {(c.tmult, c.exit) for c in cs}


def cell_trades(c: Cell, s: Sess, sig: np.ndarray, tab: Table, dt: dict | None) -> list[tuple[int, int]]:
    """(side, bar) of every trade the cell takes in one session."""
    if c.day:
        key, val = c.day
        if key == "expiry":
            if bool(s.expiry) != val:
                return []
        else:
            if dt is None or dt.get(key) != val:
                return []
    first, last = c.win()
    if c.mode == "ANY":
        return run_arm(sig, tab, first, last, c.slot_bars())
    tr = run_arm(sig, tab, first, last)
    if c.mode == "APP":
        return tr
    sb = c.slot_bars()
    if c.mode == "FIRST":
        return tr[:1] if tr and tr[0][1] in sb else []
    if c.mode == "ATTR":
        return [t for t in tr if t[1] in sb]
    raise ValueError(c.mode)


def twin_pool(c: Cell) -> np.ndarray:
    """Bars a twin may draw from: the cell's window, outside its own slot (T / C / H12: 'other minutes')."""
    first, last = c.win()
    if c.mode == "ATTR":
        first, last = window(c.arm)
    bars = [b for b in range(first, last + 1)]
    if c.slot:
        sb = c.slot_bars()
        bars = [b for b in bars if b not in sb]
    return np.array(bars, dtype=np.int64)


# ------------------------------------------------------------------------------------------------ statistics
def money_p(day_nets: np.ndarray) -> float:
    """One-sided t-test p: the mean net per traded (index, session) is above 0."""
    from scipy import stats
    x = np.asarray(day_nets, dtype=float)
    if len(x) < 3 or x.std() == 0:
        return 1.0
    return float(stats.ttest_1samp(x, 0.0, alternative="greater").pvalue)


def twins(nets_tab: list[np.ndarray], real: list[tuple[int, int, int, bool]], pool: np.ndarray, n: int,
          rng: np.random.Generator) -> np.ndarray:
    """n twin totals. real: (table index, side j, own bar, own-bar-excluded) per trade; nets_tab[i] = (net (2, NB),
    ok (2, NB)) of that trade's session. Each trade is re-entered at a random pool bar (not its own bar), the first of
    up to 5 draws whose entry exists; else it adds 0."""
    tot = np.zeros(n)
    if not len(pool):
        return tot
    for ti, j, b, _ in real:
        net, ok = nets_tab[ti]
        pl = pool[pool != b]
        if not len(pl):
            continue
        cand = pl[rng.integers(len(pl), size=(n, 5))]
        v = ok[j][cand]
        has = v.any(axis=1)
        first = v.argmax(axis=1)
        pick = cand[np.arange(n), first]
        tot += np.where(has, net[j][pick], 0.0)
    return tot


def bh(ps: list[float]) -> list[float]:
    return E.bh(ps)
