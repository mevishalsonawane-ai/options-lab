"""The app's lock rules in Python (android/engine/.../orb/ProfitLock.kt), for today's minute data."""
import math
from day import rt_per_unit, closes, minutes

LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]
TRAIL_BE, TRAIL_STEPS = 5.0, [(8.0, 50.0), (20.0, 65.0), (40.0, 75.0)]


def on_tick(x, tick=0.05):
    return round(math.ceil(x / tick - 1e-6) * tick, 2)


def ladder_level(entry, target, peak, cost):
    be = entry + max(cost, 0)
    lv = [max(entry + k * target, be) for up, k in LADDER if peak >= entry + up * target - 1e-9]
    lv = [x for x in lv if x < peak]
    return max(lv) if lv else None


def trail_level(entry, peak, cost):
    if peak <= entry: return None
    g = (peak - entry) / entry * 100
    lv = []
    if g >= TRAIL_BE and entry + cost < peak: lv.append(entry + cost)
    lv += [entry + k / 100 * (peak - entry) for s, k in TRAIL_STEPS if g >= s]
    return max(lv) if lv else None


def pine_level(entry, peak, cost, target=60.0):
    return max([x for x in (ladder_level(entry, target, peak, cost), trail_level(entry, peak, cost)) if x is not None], default=None)


def walk_resting(t, level_fn, base_stop, target=None, slip_bps=10.0, end="15:09", start_after_entry_minute=True):
    """A resting sell stop at max(base_stop, level_fn(peak before this minute)), the peak from the minute HIGHS (from the
    minute after the entry minute, as Paper.highSince), filled at min(trigger, open) less SL-M slippage; a +target
    sells at the target. Returns dict(exit_minute, exit, why, peak, peak_minute, stop_after_peak)."""
    c = closes(t.feed)
    peak, pk_m, stop_hist = t.p_in, None, []
    m_in = t.t_in[:5]
    for mm in minutes(m_in, end):
        _, o, h, l, cl, _ = c[mm]
        lv = level_fn(peak)
        stop = max([x for x in (base_stop, on_tick(lv) if lv else None) if x is not None], default=None)
        if mm != m_in and stop is not None and l <= stop + 1e-9:
            px = min(stop, o) * (1 - slip_bps / 1e4)
            return dict(exit_minute=mm, exit=round(px, 2), why="lock" if stop >= t.p_in else "stop", peak=peak, peak_minute=pk_m, stop=stop)
        if target is not None and h >= t.p_in + target - 1e-9 and mm != m_in:
            return dict(exit_minute=mm, exit=round(max(t.p_in + target, o if o > t.p_in + target else 0), 2), why="target", peak=max(peak, h), peak_minute=mm, stop=stop)
        if mm != m_in or not start_after_entry_minute:
            if h > peak: peak, pk_m = h, mm
    return dict(exit_minute=end, exit=c[end][4], why="time", peak=peak, peak_minute=pk_m, stop=None)
