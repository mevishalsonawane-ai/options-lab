"""Task 1: today's day P&L path, its peaks, and who gave it back. Writes data/curve.csv."""
import csv
from day import TRADES, curve, closes, minutes, charge, rt_per_unit

def main():
    print("TRADES (net after app charges)")
    tot = 0
    for t in TRADES:
        c = closes(t.feed)
        m_in, m_out = t.t_in[:5], t.t_out[:5]
        held = [mm for mm in minutes() if m_in <= mm <= m_out]
        hi = max(held, key=lambda mm: c[mm][2]); lo = min(held, key=lambda mm: c[mm][3])
        tot += t.net
        print(f"{t.n:2d} {t.arm:10s} {t.feed:20s} {t.t_in}->{t.t_out} {t.p_in:7.2f}->{t.p_out:7.2f} qty {t.qty:3d} "
              f"net {t.net:+8.0f} | best high {c[hi][2]:7.2f} @{hi} ({(c[hi][2]-t.p_in):+6.1f} pt, Rs {(c[hi][2]-t.p_in)*t.qty:+6.0f}) "
              f"worst low {c[lo][3]:7.2f} @{lo} {t.note}")
    print(f"day net (realised, after charges) Rs {tot:+.0f}")
    cv = curve()
    with open("data/curve.csv", "w", newline="") as f:
        w = csv.writer(f); w.writerow(["minute", "realised", "open_mtm", "total"])
        for mm, (r, o, x) in cv.items(): w.writerow([mm, round(r), round(o), round(x)])
    s = [(mm, v[2]) for mm, v in cv.items()]
    pk = max(s, key=lambda x: x[1]); print("PEAK", pk)
    # local peaks >= 6000 and the troughs after them
    print("minutes with total >= 7000:", [(mm, round(v)) for mm, v in s if v >= 7000])
    print("every 5 min:")
    for mm, v in s:
        if mm.endswith(("0", "5")) and "09:15" <= mm <= "15:15":
            r, o, x = cv[mm]
            opn = [t.n for t in TRADES if t.t_in[:5] <= mm < t.t_out[:5]]
            print(f"  {mm} total {x:+7.0f} realised {r:+7.0f} open {o:+7.0f} open trades {opn}")

if __name__ == "__main__":
    main()


def contrib(t, mm):
    """Trade t's net contribution to the day at minute mm's close (0 before entry)."""
    end = mm + ":59"
    if t.t_in > end: return 0.0
    if t.t_out <= end: return t.net
    return (closes(t.feed)[mm][4] - t.p_in) * t.qty - charge("BUY", t.p_in, t.qty)


def attribution(windows=(("13:20", "13:24"), ("13:30", "13:39"), ("14:11", "14:15"), ("14:21", "14:31"), ("14:11", "15:29"))):
    cv = curve()
    for a, b in windows:
        print(f"\n{a} -> {b}: day {cv[a][2]:+.0f} -> {cv[b][2]:+.0f} ({cv[b][2] - cv[a][2]:+.0f})")
        for t in TRADES:
            d = contrib(t, b) - contrib(t, a)
            if abs(d) >= 50: print(f"   #{t.n:2d} {t.arm:10s} {t.feed:20s} {d:+6.0f}")


if __name__ == "__main__":
    attribution()
