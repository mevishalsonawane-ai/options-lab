"""Task 3 on today's minutes: what would have kept the day's gains?
 A  per-trade locks as designed (ORB family re-walked on the minute data; Pine as the app samples it = actual)
 B  faster enforcement: the Pine lock as a resting SL-M moved up on every new best (minute highs), filled at the trigger
    or the gap open (B_opt) - and B_thin: filled at the minute's low (FINNIFTY monthly options trade 0-12 lots a minute)
 C  a premium trail on Solo (ProfitLock.Trail defaults and a few others), resting
 D  an account-level day lock on the net (after-charges) minute curve, several settings
Every rupee is net of the app's charges; forced closes sell at the next minute's open less 5 bps."""
from __future__ import annotations
import copy
from day import TRADES, T, curve, closes, minutes, charge, rt_per_unit
from locks import ladder_level, pine_level, trail_level, on_tick, walk_resting

TGT = {"ORB": 40.0, "Range Fade": 40.0, "ORB Sweep": 80.0}


def with_exit(t, mm, px):
    u = copy.copy(t); u.t_out = mm + ":30"; u.p_out = round(px, 2); return u


def scen_A(trades):
    out = []
    for t in trades:
        if t.arm in TGT:
            tg = TGT[t.arm]; cost = rt_per_unit(t.p_in, t.qty)
            r = walk_resting(t, lambda pk, t=t, tg=tg, cost=cost: ladder_level(t.p_in, tg, pk, cost), round(t.p_in - 40, 2), target=tg)
            # a +target is sold by the app at the price it sees after the cross (above the target today): keep the actual
            out.append(t if r["why"] == "target" else with_exit(t, r["exit_minute"], r["exit"]))
        else:
            out.append(t)
    return out


def pine_resting(t, thin=False):
    cost = rt_per_unit(t.p_in, t.qty)
    c = closes(t.feed)
    peak = t.p_in
    for mm in minutes(t.t_in[:5], "15:14"):
        _, o, h, l, cl, _ = c[mm]
        lv = pine_level(t.p_in, peak, cost)
        stop = max(t.p_in - 30, on_tick(lv) if lv else -1)
        if mm != t.t_in[:5] and l <= stop:
            px = l if thin else min(stop, o) * 0.999
            return with_exit(t, mm, px)
        if mm != t.t_in[:5] and h >= t.p_in + 60:
            return with_exit(t, mm, t.p_in + 60)
        if mm != t.t_in[:5]: peak = max(peak, h)
        if mm >= t.t_out[:5] and mm > t.t_in[:5]:   # the script's own exit (signal change / 15:15) still applies
            return t
    return t


def scen_B(trades, thin=False):
    return [pine_resting(t, thin) if t.arm == "Pine" else t for t in scen_A(trades)]


def solo_trail(t, level_fn):
    c = closes(t.feed); peak = t.p_in
    for mm in minutes(t.t_in[:5], t.t_out[:5]):
        _, o, h, l, cl, _ = c[mm]
        lv = level_fn(peak)
        if mm != t.t_in[:5] and lv is not None and l <= lv and mm < t.t_out[:5]:
            return with_exit(t, mm, min(lv, o) * 0.999)
        if mm != t.t_in[:5]: peak = max(peak, h)
    return t


SOLO_TRAILS = {
    "Pine trail defaults (5% BE; 8%->50%, 20%->65%, 40%->75% of best gain)": lambda e, cost: (lambda pk: trail_level(e, pk, cost)),
    "keep 50% of best gain from +10%": lambda e, cost: (lambda pk: e + 0.5 * (pk - e) if pk >= e * 1.10 else None),
    "keep 75% of best gain from +20%": lambda e, cost: (lambda pk: e + 0.75 * (pk - e) if pk >= e * 1.20 else None),
    "give back at most 15% of the premium's best": lambda e, cost: (lambda pk: pk * 0.85 if pk * 0.85 > e + cost else None),
}


def scen_C(trades, name):
    out = []
    for t in trades:
        if t.arm == "Solo":
            cost = rt_per_unit(t.p_in, t.qty)
            out.append(solo_trail(t, SOLO_TRAILS[name](t.p_in, cost)))
        else: out.append(t)
    return out


def net(trades): return sum(t.net for t in trades)


def day_lock(trades, rule):
    """rule = (kind, X, y): 'entries' stop new entries once net >= X; 'trail' once the peak >= X, close all + stop when net
    <= peak x (1 - y); 'floor' once the peak >= X close all + stop when net <= y (rupees); 'both' = entries at X and trail."""
    kind, X, y = rule
    cv = curve(trades)
    peak, T_stop, T_flat = -1e18, None, None
    for mm, (_, _, v) in cv.items():
        peak = max(peak, v)
        if kind in ("entries", "both") and T_stop is None and v >= X: T_stop = mm
        if kind in ("trail", "both") and peak >= X and v <= peak * (1 - y): T_flat = mm; break
        if kind == "floor" and peak >= X and v <= y: T_flat = mm; break
    T = min([x for x in (T_stop, T_flat) if x], default=None)
    if T is None: return trades, None
    out = []
    for t in trades:
        if t.t_in[:5] > T: continue                      # blocked entry
        if T_flat and t.t_in[:5] <= T_flat < t.t_out[:5]:
            nxt = minutes(T_flat, "15:29")[1]
            out.append(with_exit(t, nxt, closes(t.feed)[nxt][1] * 0.9995))
        else: out.append(t)
    return out, (T_stop, T_flat)


RULES = [("entries", X, 0) for X in (3000, 4000, 5000, 6000)] + \
        [("trail", X, y) for X in (3000, 4000, 5000) for y in (0.3, 0.5)] + \
        [("floor", X, X / 2) for X in (3000, 4000, 5000)] + [("both", 4000, 0.3), ("both", 5000, 0.3)]


def main():
    base = TRADES
    print(f"ACTUAL (reconstructed) net Rs {net(base):+.0f}; peak net {max(v[2] for v in curve(base).values()):+.0f}")
    A = scen_A(base); print(f"A per-trade locks as designed (ORB family walked on minutes): {net(A):+.0f}")
    for t0, t1 in zip(base, A):
        if abs(t0.net - t1.net) > 1: print(f"    #{t0.n} {t0.arm}: actual {t0.p_out} @{t0.t_out[:5]} -> rules {t1.p_out} @{t1.t_out[:5]} ({t1.net - t0.net:+.0f})")
    for thin in (False, True):
        Bt = scen_B(base, thin)
        print(f"B Pine lock as a resting stop on the highs ({'filled at the minute low (thin book)' if thin else 'filled at trigger/gap open'}): {net(Bt):+.0f}")
        for t0, t1 in zip(A, Bt):
            if t0.arm == "Pine": print(f"    #{t0.n} Pine: {t0.p_out} @{t0.t_out[:5]} -> {t1.p_out} @{t1.t_out[:5]} ({t1.net - t0.net:+.0f})")
    for name in SOLO_TRAILS:
        C = scen_C(base, name); s = [t for t in C if t.arm == "Solo"][0]
        print(f"C Solo trail '{name}': Solo exit {s.p_out} @{s.t_out[:5]} net {s.net:+.0f} (actual +{[t for t in base if t.arm=='Solo'][0].net:.0f}); day {net(C):+.0f}")
    print("D account-level day lock on the ACTUAL trades (net curve):")
    for r in RULES:
        tr, T = day_lock(base, r)
        print(f"    {r[0]:8s} X={r[1]:5.0f} y={r[2]:<6} fired {T}: day {net(tr):+.0f} ({net(tr) - net(base):+.0f}), trades {len(tr)}")
    print("D on top of A (per-trade locks as designed):")
    for r in RULES:
        tr, T = day_lock(A, r)
        print(f"    {r[0]:8s} X={r[1]:5.0f} y={r[2]:<6} fired {T}: day {net(tr):+.0f} ({net(tr) - net(A):+.0f})")

if __name__ == "__main__":
    main()
