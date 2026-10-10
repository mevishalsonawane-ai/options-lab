# h15 pre-registration: Liquidity 15+5 with Rs 1,00,000 of capital (written 2026-10-07, BEFORE any capital-path P&L)

Question: starting from Rs 1 lakh, which books, how many lots, which compounding rule, what is the realistic Rs/day now,
the ruin risk, and the time (if any) to a size that makes about Rs 5,000/day? Option BUYING only. Signals, strike
(1-ITM nearest expiry), books and exits are the app's Liquidity 15+5 arm unchanged (h4/h7 port, h10 trade list).
Execution: h14 E2 (limit buy at entry print +0.5%, rest 3 min, cancel; exits h10-worked market), fill model M2,
kappa 0.02 central (0.04 pessimistic, 0.01 reported); h10 market E0/M1 reported for reference.

Known before writing this: h10/h13/h14 reports (incl. their holdout results), and from h15's own table validation
(tab.py reproduces h10 4,928/4,830 and h14 5,162/5,368 Rs/day at BN 26/FIN 3/MIDCP 11): 1-lot premium medians
(BN ~Rs 19-21k, FIN ~17k, MIDCP ~23-25k) and 1-lot mean net per trade in the regime window (BN +240, FIN +209,
MIDCP -72) and, printed in the same validation line, in the holdout (BN +214, FIN -71, MIDCP +99). Disclosed; the
choice below is mechanical on pre-holdout data only.

## Tables (tab.py)
For every trade and every lot count n = 1..NMAX (BN 100 = h10's practical ceiling, FIN 3, MIDCP 11 = h10 capacity):
net, gross, premium paid, filled lots, exit-end minute, from h14's exe.simulate. Lots above NMAX are never bought.

## Capital walk (cap1l.py)
Trades in time order (day, entry minute); one position per book at a time (the arm's own rule), so <= 3 concurrent.
- E = realised capital (start Rs 1,00,000; closed trades' net added when their last exit slice is done).
- cash = E - premium paid for positions still open. A trade's lots n are fixed at its signal from the rule below,
  then cut to the largest n whose table premium (+ Rs 100 charges buffer) <= cash. If that is 0: SKIP the signal
  (capital tied up). First come, first served; no reservation between books.
- Single-lot allowance: if the rule gives n < 1, take n = 1 when 1 lot's premium <= 35% of E; else skip.
- Ruin: E < Rs 25,000 at any time (reported; trading continues while a lot is affordable).

## Variants (all counted): 3 book sets x 6 sizing rules = 18
Book sets: A = BN + FIN + MIDCP, B = BN + MIDCP, C = BN only.
Sizing (p1 = 1 lot's premium at the signal = arm's entry price x lot size):
- F1: 1 lot per book, always (no compounding).
- FL: fixed lots that compound: n = floor(E / 1,00,000) per book.
- R2 / R4: fixed-% risk: n = floor(f E / (0.15 p1)), f = 2% / 4% (risk = the 15% premium stop).
- K4 / K2: fractional Kelly: premium per trade = x f*_u E, x = 1/4 / 1/2, f*_u = argmax_f mean log(1 + f R) over the
  book's regime-window trades at 1 lot (R = net / premium, E2, kappa 0.02); n = floor(x f*_u E / p1).

## Bootstrap (pre-holdout only)
Month blocks from the REGIME window 2024-12 .. 2025-09 (10 blocks; monthly-only market = today's), resampled with
replacement into 5,000 paths of 60 months (same block sequence for every variant). Reported at 3/6/12/24 months:
capital quantiles, P(ruin), P(capital >= 2 lakh), median Rs/day in months 1/6/12. Sensitivity (info only): blocks
from 2023-06 .. 2025-09 (28 blocks, includes the weekly era with different premiums/liquidity).
Random entries: the same paths with each real trade replaced by one of its 5 random-entry alternatives (same exits,
same sizing and capital walk).

## Target size (Rs 5,000/day)
E*_v = smallest E such that, with the rule's lots at constant capital E (no cash cut), the regime-window average net
per trading day >= Rs 5,000. Reported: P(E reaches E*_v by 12/24/36/60 months) and the median time among hits.
Cross-check against h10's plan (BN 26 / FIN 3 / MIDCP 11, premium p95 ~Rs 14 L).

## Choice rule
On the regime bootstrap at kappa 0.02: eligible = P(ruin within 24 months) <= 10%. Pick the highest median capital at
12 months. If the best is within 5% of a simpler variant, take the simpler one (order F1 < FL < R2 < R4 < K4 < K2;
fewer books simpler). Report the choice at kappa 0.04 too (no re-choice).

## Holdout (2025-10-01 .. latest, ONCE)
Plain replay of the chosen variant from Rs 1 lakh at kappa 0.02 and 0.04 (E2/M2), plus E0/M1 market for reference;
gross shown beside net. Then, info only, all 18 variants. Random-entry replay (2,000 draws): p = share of random
replays ending >= the real one. Where the real holdout path falls in the bootstrap 12-month distribution.
Multiple testing: BH over the 18 variants' pre-holdout random-entry p-values; SPA over the 18 variants' pre-holdout
daily-P&L series (replay from Rs 1 lakh on the regime window).
