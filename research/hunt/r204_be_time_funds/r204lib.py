"""R204 / R205 pure parts (PREREG.md): the early time stop's row, the breakeven level merged into the lock levels, the book
filter and the funded replay. No data and no engine here (test_r204.py). Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

import r204cfg as K

NROWS = K.K2.NROWS


def no_go_row(high: np.ndarray, ef: int, minutes: int, need_px: float) -> int:
    """ET: the sale minute (fill + minutes) when the option's highs of minutes fill .. fill + minutes - 1 never reach
    `need_px`; -1 when one did (the trade got going), when there is no fill, or when the session has no such minute.
    A missing high counts as not reached. Reads minutes before the sale minute only."""
    ef, minutes = int(ef), int(minutes)
    if ef < 0 or not (need_px == need_px):
        return -1
    r = ef + minutes
    if r >= NROWS or r >= len(high):
        return -1
    seg = np.asarray(high[ef:r], float)
    seg = seg[np.isfinite(seg)]
    if len(seg) and float(seg.max()) >= need_px - 1e-9:
        return -1
    return r


def add_level(trig: np.ndarray, lock: np.ndarray, new_trig: float, new_lock: float) -> tuple:
    """BE500: the lock levels with one more (trigger premium, stop premium), in trigger order (the simulator walks them
    upward and keeps the highest stop reached, so an added level can never lower a lock). A level with no stop
    (new_lock <= 0: the whole premium is worth less than the charges) is not added."""
    t = np.asarray(trig, float)
    l = np.asarray(lock, float)
    if not (new_trig == new_trig) or not (new_lock > 0):
        return t, l
    i = int(np.searchsorted(t, new_trig, side="left"))
    return np.insert(t, i, float(new_trig)), np.insert(l, i, float(new_lock))


def keep_book(t: dict, book: tuple = K.NOM5) -> bool:
    """NOM5: False for a trade of the removed book (index, bar minutes)."""
    return not (t["und"] == book[0] and t.get("book") == book[1])


def funded_replay(by_arm: dict, account, order: tuple = K.ARM_ORDER) -> tuple:
    """R205: (trades taken, trades the money could not pay for, the most money in use on any minute). by_arm: {arm:
    trades}; account None = no limit. Each day starts with free cash = account. Exits are booked before entries of the
    same minute; entries of a minute go in `order`, then index, then side. A taken trade uses rE x qty until its exit
    minute, when that money plus its net comes back. A trade whose exit minute is not after its fill minute is still
    paid for first (it frees the money on the next step)."""
    rank = {a: i for i, a in enumerate(order)}
    days = defaultdict(list)
    for a in order:
        for t in by_arm.get(a, []):
            days[t["day"]].append((int(t["er"]), rank[a], t["und"], t["side"], float(t["strike"]), a, t))
    taken, refused, peak = [], [], 0.0
    for d in sorted(days):
        free = float("inf") if account is None else float(account)
        used = 0.0
        open_ = []                                             # (exit minute, cost, net)
        for er, _, _, _, _, a, t in sorted(days[d], key=lambda x: x[:5]):
            still = []
            for xr, cost, net in open_:                        # sales up to this minute come back first
                if xr <= er:
                    free += cost + net
                    used -= cost
                else:
                    still.append((xr, cost, net))
            open_ = still
            cost = float(t["rE"]) * float(t["qty"])
            if cost > free + 1e-9:
                refused.append(dict(t, arm=a))
                continue
            free -= cost
            used += cost
            peak = max(peak, used)
            open_.append((max(int(t["xr"]), er + 1), cost, float(t["net"])))
            taken.append(dict(t, arm=a))
    return taken, refused, peak
