"""The ORB arms' trades today: peak open profit, the rung reached (ProfitLock.kt as fixed in 38400e4), the stop that should
have rested after it, the exit the rules give on the minute data, and the actual exit."""
from day import TRADES, rt_per_unit, closes, minutes
from locks import ladder_level, on_tick, walk_resting

TGT = {"ORB": 40.0, "Range Fade": 40.0, "ORB Sweep": 80.0}

def rung_name(entry, target, peak):
    up = peak - entry
    names = [(0.75, "+%g -> lock +%g" % (0.75 * target, 0.5 * target)), (0.5, "+%g -> lock +%g" % (0.5 * target, 0.25 * target)),
             (0.25, "+%g -> lock breakeven" % (0.25 * target))]
    for k, n in names:
        if up >= k * target - 1e-9: return n
    return "none (first rung +%g)" % (0.25 * target)

def main():
    rows = []
    for t in TRADES:
        if t.arm not in TGT: continue
        tg = TGT[t.arm]; cost = rt_per_unit(t.p_in, t.qty)
        r = walk_resting(t, lambda pk: ladder_level(t.p_in, tg, pk, cost), base_stop=round(t.p_in - 40, 2), target=tg)
        c = closes(t.feed)
        held = [mm for mm in minutes(t.t_in[:5], t.t_out[:5])]
        pk_m = max(held, key=lambda mm: c[mm][2]); pk = c[pk_m][2]
        lv = ladder_level(t.p_in, tg, pk, cost)
        should_stop = max(round(t.p_in - 40, 2), on_tick(lv)) if lv else round(t.p_in - 40, 2)
        rows.append((t, pk, pk_m, rung_name(t.p_in, tg, pk), should_stop, r))
        print(f"#{t.n:2d} {t.arm:10s} {t.t_in[:5]} in {t.p_in:7.2f} | peak {pk:7.2f} @{pk_m} = {pk - t.p_in:+6.1f} pt (Rs {(pk - t.p_in) * t.qty:+5.0f}) "
              f"| rung {rung_name(t.p_in, tg, pk):28s} | stop that should rest {should_stop:7.2f} | rules on minutes: {r['why']:6s} {r['exit']:7.2f} @{r['exit_minute']} "
              f"| actual {t.p_out:7.2f} @{t.t_out[:5]} ({(t.p_out - t.p_in):+6.1f} pt, net Rs {t.net:+5.0f}) {t.note}")
    orb = [t for t in TRADES if t.arm == "ORB"]
    print(f"ORB entries {len(orb)} on {set(t.feed for t in orb)}: net Rs {sum(t.net for t in orb):+.0f}, charges Rs {sum(t.chg for t in orb):.0f}")
    fam = [t for t in TRADES if t.arm in TGT]
    print(f"ORB family {len(fam)} trades: net Rs {sum(t.net for t in fam):+.0f}; gross {sum(t.gross for t in fam):+.0f}")
    for a in TGT: print("  ", a, round(sum(t.net for t in fam if t.arm == a)))
    # running ORB-family P&L (realised + open, net of charges)
    from day import curve
    cv = curve([t for t in TRADES if t.arm in TGT])
    pts = [(mm, round(v[2])) for mm, v in cv.items() if "11:30" <= mm <= "14:05"]
    sign = None
    for mm, v in pts:
        s = v > 0
        if s != sign: print("   ORB family running P&L turns", "green" if s else "red", "at", mm, v); sign = s

if __name__ == "__main__":
    main()
