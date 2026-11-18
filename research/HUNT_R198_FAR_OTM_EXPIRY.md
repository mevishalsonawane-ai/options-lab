# R198: far-OTM hedged strangle held to expiry, for an 80% win rate (side study, 18 Nov 2026)

Boss, after R197: "try far OTM strangle hold to expiry with hedge for 80%". **Research only. The app stays buy-only.**

- **Pre-registration:** `research/hunt/r198_far_otm_expiry/PREREG.md` (8 candidates, gates including win rate >= 80%
  in design and in 2026, BH m = 4,885). Hash-stamped before any number.
- **Code:** `r198study.py` (expiry cycles, carried iron condors, the 15:05 exit or settlement with exercise STT,
  twins, gates, report, log entries); 4 tests in `test_r198.py`.
- **Tables:** `results/report.md`, `results/final.json`.
- **The data's limit:** the lake keeps strikes within +-10 steps of the ATM, so the hedges sit at 10 steps (NIFTY 500
  points, about 2%) and the shorts at 5 or 7 steps (about 1.0-1.4% out).

## Verdict

**One rule reached 80% wins in 2021-2025, but it did not hold in 2026, it barely makes money, and it fails the safety
gates. None of the 8 passes.**

| id | rule | design 2021-2025: cycles / win (95% range) / net | avg win / avg loss / win needed to break even | 2026: cycles / win / net | worst single trade | margin, 1 lot | failed gates |
|---|---|---|---|---|---|---|---|
| **F5-NIFTY** | condor 5 / 10 steps, entered 15:15 the day before expiry, out 15:05 on expiry day | 225 / **81.8%** (76.2-86.3%) / +Rs 24,004 | Rs 804 / Rs 3,023 / 79.0% | 45 / 73.3% / +Rs 2,030 | -Rs 15,681 (11-12 Sep 2024) | about Rs 1.5 lakh | 4, 5, 6, 7, 9 |
| F4-NIFTY | condor 7 / 10 steps, entered after the previous expiry, held to settlement | 210 / 77.1% / +Rs 53,982 | Rs 1,915 / Rs 5,338 / 73.6% | 41 / 61.0% / -Rs 10,831 | -Rs 9,100 | Rs 1.44 lakh | 2, 4, 5, 6, 9 |
| F3-NIFTY | condor 5 / 10, to settlement | 210 / 68.6% / +Rs 91,247 | Rs 4,063 / Rs 7,482 / 64.8% | 41 / 58.5% / -Rs 8,274 | -Rs 14,374 | Rs 1.5 lakh | 2, 4, 5, 6, 7, 9 |
| F2-NIFTY | condor 7 / 10, out 15:05 on expiry day | 210 / 75.7% / +Rs 11,431 | | 41 / 61.0% / -Rs 14,716 | | | 2, 4, 5, 6, 7, 9 |
| F1-NIFTY | condor 5 / 10, out 15:05 on expiry day | 210 / 68.6% / +Rs 12,787 | | 41 / 61.0% / -Rs 6,405 | | | 2, 4, 5, 6, 7, 9 |
| F8-NIFTY | put spread only, 5 / 10, out 15:10 the day before expiry (fits Rs 56k: Rs 49k) | 215 / 74.9% / +Rs 45,916 | Rs 1,485 / Rs 3,577 / 70.7% | 44 / 59.1% / **-Rs 32,514** | -Rs 12,496 | **Rs 49,400** | 2, 4, 5, 6, 7, 9 |
| F6-SENSEX | condor 5 / 10, out 15:05 on expiry day | 98 / 45.9% / -Rs 53,072 | | 34 / 52.9% / +Rs 19,544 | | | 1, 3, 4, 5, 6, 7, 8, 9 |
| F7-SENSEX | condor 7 / 10 | 98 / 49.0% / -Rs 36,484 | | 34 / 55.9% / +Rs 7,735 | | | 1, 3, 4, 5, 6, 7, 8, 9 |

(1 lot at today's lot, after slippage, dated charges and, for settlement, STT on exercised legs. Gates: 1 design and
post-Nov-2024 > 0; 2 2026 > 0; 3 40+ trades; 4 > 0 without the 5 best; 5 twin p < 0.05; 6 BH q <= 0.10; 7 worst month
>= -Rs 13k and drawdown <= Rs 40k; 8 > 0 at 2x slippage; 9 win rate >= 80% in design and in 2026.)

## In plain English

- **Yes, a far-OTM hedged strangle can win about 80% of the time.** The expiry-eve condor (F5) won 81.8% of 225 weeks
  over 2021-2025, and 89.5% after Nov 2024.
- **But it earns very little for the risk.** An average win was Rs 804 and an average loss Rs 3,023, so it needs 79%
  wins just to break even. It made +Rs 24,004 over 225 weeks, about Rs 107 a week. One bad week (11-12 Sep 2024,
  -Rs 15,681) wiped out about 20 good weeks, and that one week was bigger than your -Rs 13k monthly stop.
- **In 2026 the win rate dropped to 73.3%** (45 weeks) and the profit to +Rs 2,030. That is what "80%" looks like when
  it meets new data. The 95% range on 225 weeks was already 76-86%.
- **Entering on any other day of the same week did about as well** (twin p 0.14). The expiry-eve timing is not
  shown to be special.
- **It needs about Rs 1.5 lakh of margin** (two short legs on their expiry day carry the +2% ELM), almost three times
  the Rs 56k account.
- **The one version Rs 56k can carry (F8, the put side only, out the day before expiry) lost -Rs 32,514 in 2026** at a
  59% win rate.
- **Holding to settlement** (F3, F4) made more in 2021-2025 than closing at 15:05, but lost in 2026 too.

**The answer to "80%" is the same as R197's math:** a seller can buy a high win rate by selling far out, but each loss
is several times each win, so the money left over is small and fragile. With Boss's -Rs 13k monthly stop, one bad
expiry week stops the account. R195-R198 have now tested 114 option-selling rules; none is both reliable and affordable
at Rs 56k.

## Caveats

- Strikes are limited by the lake's +-10-step window: no hedge further than 10 steps could be priced. Far legs that
  left the window were marked by Black-Scholes at their last print's implied vol.
- 2026 is a second look (R195 read it). The forward window from 16 Nov 2026 is unread.
- SENSEX has only 98 design cycles (since May 2023, after entry-print skips).
- R195's caveats apply: modelled slippage, a margin model, today's lot every year.
