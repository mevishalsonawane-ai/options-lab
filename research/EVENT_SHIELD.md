# I6 Event shield: BANKNIFTY and NIFTY on RBI, Budget, US CPI / Fed and heavyweight-results days

*13 Oct 2026. Pre-registration: `research/context/PREREG.md` (written before any test ran). Code:
`research/context/event_shield.py`; numbers: `research/context/results/event_shield.json`. Data: the `research-data`
release (BANKNIFTY 13 Feb 2024 - 23 Feb 2026, 497 sessions with a previous close; NIFTY 1 Apr 2024 - 13 Apr 2026, 498).
Re-runs on the 6-year Dhan lake with one switch (`IRAALGO_CONTEXT_SOURCE=lake`). Advisory only: nothing here trades.*

## Verdict (read this first)

- **BANKNIFTY moves more on India-policy days (RBI policy and the Budget).** The median day range was **1.44% against
  0.98% on normal days (1.48x; 15 sessions, 359 normal)**. The test passed on design (BH q = 0.08) and the locked
  holdout confirmed it (1.35x, p = 0.03, 7 sessions). The Budget alone: 2.2x (3 sessions, too few to test).
- **NIFTY on India-policy days: 1.28x, not significant** (15 sessions, design p = 0.26).
- **US CPI and Fed (FOMC) nights: bigger gaps, not bigger days.** The next Indian session opened with a median |gap| of
  0.35% against 0.23% on normal days (BANKNIFTY, 39 sessions; NIFTY 0.26% vs 0.23%, 38), but the day range was the same
  (1.01x both; BANKNIFTY p = 0.72, NIFTY p = 0.74).
- **Heavyweight results (HDFC Bank, ICICI Bank; Reliance for NIFTY): bigger gap and first hour, not a bigger day.**
  BANKNIFTY |gap| 0.39% vs 0.23%, first-hour range 1.41x, day range 1.14x (12 sessions, p = 0.22). Most of the results
  dates were entered from memory and are marked `verified=no` in `research/context/events.csv`: check them before
  relying on this row.
- **The session before an event looks normal** (BANKNIFTY day range 1.03% vs 0.98%, 65 sessions; NIFTY 0.87% vs 0.83%,
  69). A warning the day before is about planning, not about that day.
- **The arms on event days (research replay of ORB and ORB Fresh, BANKNIFTY, real costs).** ORB Fresh lost more on event
  days in design (Rs -629 a day vs Rs -74, 36 event days, BH q = 0.096) but the holdout did NOT confirm it (Rs -340 vs
  Rs -131, 30 days, p = 0.21). ORB: not significant (design p = 0.12). Pausing on event days would have cut losses in both
  periods only because both arms lose on most days (R14); it is not an edge.

**What the app does with it:** a warning the evening before and on the morning of an event day, with these numbers and
their sample sizes, and an optional switch "pause paper arms on event days" that is **OFF by default**, has a shadow
mode (logged only), touches paper arms only, never Liquidity BANKNIFTY (frozen until 26 Oct), and can only skip an entry.

## Event sessions

The first Indian session that trades after the event: RBI (10:00 IST) and the Budget (11:00) that day; US CPI (08:30 New
York) and FOMC (14:00 New York) come after the Indian close, so the next session; results announced after hours or on a
Saturday, the next session. Normal days: no event, not an expiry day, not the session before an event.

## Tables (median; ratio = event / normal)

BANKNIFTY (normal days: 359)

| event | sessions (design / holdout) | day range % | abs gap % | abs open-close % | first-hour range % | design p | holdout p |
|---|---|---|---|---|---|---|---|
| RBI | 12 (6 / 6) | 1.35 vs 0.98 (1.38x) | 0.13 vs 0.23 | 0.48 vs 0.44 | 0.71 vs 0.63 | 0.07 | 0.07 |
| Budget | 3 (2 / 1) | 2.19 vs 0.98 (2.24x) | 0.35 vs 0.23 | 0.62 vs 0.44 | 0.87 vs 0.63 | not tested | |
| India policy (RBI + Budget) | 15 (8 / 7) | 1.44 vs 0.98 (1.48x) | 0.15 vs 0.23 | 0.56 vs 0.44 | 0.71 vs 0.63 | 0.008 (q 0.08) | 0.03 confirmed |
| US macro (CPI + FOMC) | 39 (20 / 19) | 0.99 vs 0.98 (1.01x) | 0.35 vs 0.23 | 0.40 vs 0.44 | 0.56 vs 0.63 | 0.72 | 0.50 |
| FOMC | 16 | 0.87 vs 0.98 | 0.37 vs 0.23 | 0.38 vs 0.44 | 0.54 vs 0.63 | described | |
| US CPI | 24 | 1.10 vs 0.98 | 0.35 vs 0.23 | 0.41 vs 0.44 | 0.57 vs 0.63 | described | |
| Results (HDFC Bank, ICICI Bank) | 12 (8 / 4) | 1.11 vs 0.98 (1.14x) | 0.39 vs 0.23 | 0.40 vs 0.44 | 0.88 vs 0.63 | 0.22 | 0.23 |

NIFTY (normal days: 321)

| event | sessions (design / holdout) | day range % | abs gap % | abs open-close % | first-hour range % | design p | holdout p |
|---|---|---|---|---|---|---|---|
| RBI | 12 (7 / 5) | 0.95 vs 0.83 (1.14x) | 0.11 vs 0.23 | 0.33 vs 0.42 | 0.50 vs 0.50 | 0.58 | 0.26 |
| Budget | 3 (2 / 1) | 1.69 vs 0.83 (2.04x) | 0.22 vs 0.23 | 0.38 vs 0.42 | 0.56 vs 0.50 | not tested | |
| India policy | 15 (9 / 6) | 1.06 vs 0.83 (1.28x) | 0.11 vs 0.23 | 0.37 vs 0.42 | 0.50 vs 0.50 | 0.26 | 0.11 |
| US macro | 38 (20 / 18) | 0.84 vs 0.83 (1.01x) | 0.26 vs 0.23 | 0.35 vs 0.42 | 0.50 vs 0.50 | 0.74 | 0.42 |
| Results (HDFC Bank, ICICI Bank, Reliance) | 16 (11 / 5) | 0.91 vs 0.83 (1.10x) | 0.33 vs 0.23 | 0.40 vs 0.42 | 0.62 vs 0.50 | 0.51 | 0.35 |

Tests: one-sided Mann-Whitney U on the day range (event > normal), design first; BH over the 8 index tests and the 2
arm tests (10) at q < 0.10; a design pass is confirmed on the holdout (last 12 months, read once) at p < 0.10.

## The arms (BANKNIFTY ORB and ORB Fresh, research replay, after charges and slippage)

| arm | period | event days | net on event days | mean / event day | mean / normal day | all days net | without event days | p |
|---|---|---|---|---|---|---|---|---|
| ORB | design | 36 | Rs -46,812 | Rs -1,300 | Rs -761 | Rs -2,25,362 | Rs -1,78,550 | 0.12 |
| ORB | holdout | 30 | Rs -14,712 | Rs -490 | Rs -882 | Rs -2,14,075 | Rs -1,99,362 | 0.75 |
| ORB Fresh | design | 36 | Rs -22,657 | Rs -629 | Rs -74 | Rs -49,149 | Rs -26,492 | 0.02 (q 0.096) |
| ORB Fresh | holdout | 30 | Rs -10,203 | Rs -340 | Rs -131 | Rs -36,039 | Rs -25,835 | 0.21, not confirmed |

The replay: the app's ORB rules as R14's baseline (09:15-10:00 range, entries on 5-minute closes 10:05-13:55, the 09:20
ATM option, premium at least Rs 40, -40 stop with the lock ladder, +40 target, 15:10 square-off, re-entry after the exit's
bar), minute closes (no ticks), the app's paper fills plus a modelled half-spread, and SandboxCosts charges, 1 lot.

## Limits

- Two years of data: 15 India-policy sessions per index. Re-run on the lake (2020 onward) before trusting the NIFTY rows.
- Results dates: 25 of the 27 results rows are unverified (entered from memory, marked in `events.csv`). The CPI date of
  24 Oct 2025 (delayed by the US shutdown) is also marked unverified.
- Liquidity BANKNIFTY is not replayed here and the switch never touches it (frozen until 26 Oct).
