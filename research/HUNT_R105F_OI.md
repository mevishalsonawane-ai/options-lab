# R105F: open-interest (OI) checks on Breadth Donchian and R51

26 Oct 2026. Research only. Pre-registered in `research/hunt/r105f_oi/PREREG.md` (e5f2f36) before any number; design
frozen before the one final read. Code, 6 tests, numbers: `research/hunt/r105f_oi/`.

Boss, 26 Oct: *"What if we [use] OI data?"*

## The answer

**No. None of the 4 OI checks passed on either arm (0 of 8).** Each looked good in one period and bad in another,
which is what luck looks like. Nothing changes.

| final period 1 Apr - 13 Oct 2026, 1 lot | R72 Breadth Donchian | R51 Donchian 20 + Supertrend |
|---|---|---|
| **today (no OI check)** | 1,202 trades, 46% win, **Rs 1,71,366**, worst day -Rs 22,571, max DD Rs 61,884 | 1,670, 45%, **Rs 1,34,743**, -Rs 21,578, Rs 81,683 |
| O1 PCR (calls only if PCR >= 1.0, puts only if <= 0.8) | 628, 47%, Rs 1,49,807, -Rs 17,100, **Rs 33,421** | 1,001, 45%, Rs 1,28,272, -Rs 15,029, **Rs 38,297** |
| O2 writers back our side (OI added since 09:20) | 858, 47%, Rs 1,03,884 | 1,221, 46%, Rs 98,991 |
| O3 skip if our option's OI rose > 5% in 15 min | 1,129, 46%, **Rs 1,90,690** | 1,614, 45%, **Rs 1,58,819** |
| O4 skip if a big OI wall is within 0.3% ahead | 939, 45%, Rs 1,70,095 | 1,382, 45%, Rs 1,46,919 |

Each check fails for its own reason:
- **O2 "writers back our side"** looked best in design: R72 Rs 2,40,778 vs Rs 2,16,929 and R51 Rs 87,790 vs Rs 50,425,
  both beating random skips. Then it lost Rs 67k (R72) and Rs 36k (R51) in the final. It also lost on the 4 unseen
  days (14-19 Oct).
- **O3 "our option's OI piling"** made more in the final (+Rs 19k, +Rs 24k), but less in design and walk-forward, and
  no better than random skips.
- **O1 PCR** halved the final drawdown (R72 Rs 33k vs Rs 62k), but it trades half as often. In design it wiped out
  almost all of R72's profit (Rs 8,318 vs Rs 2,16,929), and on R51 it lost money (-Rs 77,410).
- **O4 OI wall** was about level in the final and worse in design.
- **BH q 0.81-1.0 over 281 tests. Win rates did not move** (45-47% everywhere).

**This matches the earlier work.** About 3,500 option-chain tests (PCR, OI walls, max pain, GEX) all failed for
direction. OI tells where the big positions are, not where the index goes next in the following 30 minutes. R72's
breadth check (4 of 5 indices breaking together) remains the direction filter that works.

## Honest limits

- **OI is read from the lake's rolling ATM+-10 chain,** with OI as last printed (NSE updates every few minutes). Strikes
  further out are not seen.
- **The final window has been read about 31 times** (weak evidence). The 4 unseen sessions are tiny (10-44 trades).
- **The signal stays on the index.** OI is context only, as CLAUDE.md allows.

Logged: `research/experiments/entries/r105f-*.yaml` (8, rejected).
