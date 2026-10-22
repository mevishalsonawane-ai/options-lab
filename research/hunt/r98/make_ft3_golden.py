"""The FT3 golden chains for the app's engine test (android/engine/src/test/resources/ft3/golden.txt): five real expiry days
from the lake (one per index), every mark from 11:00 to the day's pick, with the chain the app reads at that mark and the
pick the app-faithful replay (ft3_app.pick_at) makes. The Kotlin test (Ft3RulesTest) must pick the same.

    python3 -I research/hunt/r98/make_ft3_golden.py
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
import ft3_app as F  # noqa: E402

OUT = HERE.parents[2] / "android/engine/src/test/resources/ft3/golden.txt"
CASES = [("SENSEX", date(2023, 7, 21)), ("FINNIFTY", date(2023, 10, 23)), ("NIFTY", date(2024, 4, 10)),
         ("MIDCPNIFTY", date(2024, 5, 17)), ("BANKNIFTY", date(2023, 9, 6))]


def hm(t: int) -> str:
    return f"{(555 + t) // 60:02d}:{(555 + t) % 60:02d}"


def main():
    out = ["# FT3 golden chains (research/hunt/r98/make_ft3_golden.py, from the lake; one line per mark):",
           "# und|day|mark|spot|atmCall|atmPut|strike:side:close:traded;...  (close '-' = no bar at the mark)",
           "# then 'pick|und|day|mark|strike|side|price|distEm|ivRatio' = the app-faithful replay's pick (ft3_app.pick_at)"]
    for und, d in CASES:
        day = next(x for x in R.load(und) if x.day == d)
        sp = L.spot(day)
        em, katm = L.straddle(day)
        pick = None
        for t in F.MARKS:
            s, ka = sp[t], katm[t]
            cc = L.ffill(day.opts[(ka, "C")][:, R.C], 3)[t] if (ka, "C") in day.opts else np.nan
            pp = L.ffill(day.opts[(ka, "P")][:, R.C], 3)[t] if (ka, "P") in day.opts else np.nan
            legs = []
            for (k, side), a in sorted(day.opts.items()):
                if not L.otm(k, side, s) or not abs(k - s) <= 2.0 * em[t]:
                    continue
                c = a[t, R.C]
                legs.append(f"{k:g}:{side}:{'-' if np.isnan(c) else repr(round(float(c), 2))}:{int(L.traded(a, t))}")
            out.append(f"{und}|{d}|{hm(t)}|{float(s)!r}|{'-' if np.isnan(cc) else repr(float(cc))}|"
                       f"{'-' if np.isnan(pp) else repr(float(pp))}|{';'.join(legs)}")
            pick = F.pick_at(day, t, sp)
            if pick is not None:
                k, side, px, dist, r = pick
                out.append(f"pick|{und}|{d}|{hm(t)}|{k:g}|{side}|{px!r}|{dist:.6f}|{r:.6f}")
                break
        assert pick is not None, (und, d)
    OUT.write_text("\n".join(out) + "\n")
    print(OUT, len(out))


if __name__ == "__main__":
    main()
