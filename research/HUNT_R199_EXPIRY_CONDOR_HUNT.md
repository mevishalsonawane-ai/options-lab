# R199: hunting a high-win-rate expiry condor that also keeps its losses small (side study, 18 Nov 2026)

Boss: "Hunt. More" (after R198). **Research only. The app stays buy-only.**

- **Pre-registration:** `research/hunt/r199_expiry_condor_hunt/PREREG.md` (12 candidates; gates including win rate >= 80%
  in design and in 2026; BH m = 4,897). Hash-stamped before any number.
- **Code:** `r199study.py` (`run_carry` walks every minute across sessions for the index and premium stops; with the
  stops off it reproduces R198's F5 exactly, 91 of 91 cycles checked); 5 tests in `test_r199.py`.
- **Tables:** `results/report.md`, `results/final.json`.

## Verdict

**None of the 12 passes. Nothing kept an 80% win rate in both periods.** There was a real trade-off: **the loss caps made
the rules make money and stay inside the account stop, but they cost win rate.**

| id | rule | design 2021-2025: weeks / win / net / max DD / worst week | avg win / avg loss / win needed | post-Nov-2024 weeks / win / net | 2026: weeks / win / net | failed gates |
|---|---|---|---|---|---|---|
| **H1-NIFTY** | eve condor 5 / 10 + index stop at the short strikes | 225 / 74.7% (68.6-79.9%) / +Rs 33,131 / Rs 14,532 / -Rs 6,156 | Rs 744 / Rs 1,612 / 68.4% | 57 / 80.7% / +Rs 15,635 | 45 / 66.7% / +Rs 17,027 | 4, 5, 6, 9 |
| H11-NIFTY | eve condor + premium stop at 1.5x credit | 225 / 72.0% / +Rs 25,463 / Rs 17,399 / -Rs 7,110 | Rs 810 / Rs 1,679 / 67.5% | 57 / 77.2% / +Rs 4,971 | 45 / 68.9% / +Rs 8,431 | 4, 5, 6, 9 |
| H9-BANKNIFTY | H1 on BANKNIFTY | 175 / 69.1% / +Rs 35,793 / Rs 13,530 / -Rs 6,521 | Rs 1,337 / Rs 2,334 / 63.6% | 11 / 72.7% / +Rs 8,887 | 10 / 70.0% / +Rs 12,224 | 3 (monthly since Nov 2024), 4, 5, 6, 9 |
| H2-NIFTY | index stop at 3 steps | 225 / 56.4% / +Rs 4,792 | | | 45 / 73.3% / +Rs 11,937 | 4, 5, 6, 8, 9 |
| H4-NIFTY | eve condor on high-VIX eves | 92 / **84.8%** / +Rs 3,840 / worst -Rs 15,681 | Rs 1,286 / Rs 6,891 / 84.3% | 19 / 78.9% / -Rs 4,073 | 33 / 69.7% / +Rs 89 | 1, 3, 4, 5, 6, 7, 9 |
| H5-NIFTY | eve condor on calm eves | 141 / 77.3% / -Rs 3,730 | | | 21 / 76.2% / +Rs 6,238 | 1, 3, 4, 5, 6, 8, 9 |
| H3-NIFTY | eve condor 7 / 10 (further out) | 225 / 51.6% / -Rs 15,585 | Rs 362 / Rs 529 | | 45 / 73.3% / +Rs 340 | 1, 4, 5, 6, 8, 9 |
| H6 / H7 / H8-NIFTY | expiry-day intraday condors (09:20; + index stop; 12:00) | -Rs 22,009 / -Rs 8,923 / -Rs 20,124 (217-222) | | | +Rs 13.8k / +Rs 7.5k / -Rs 7.6k | 1 and more |
| H10-SENSEX | H1 on SENSEX | 121 / 55.4% / +Rs 2,365 | | | 40 / 42.5% / -Rs 8,803 | 2, 4, 6, 9 |
| H12-NIFTY | put spread two days out, closed on the eve (fits Rs 56k) | 227 / 66.5% / -Rs 932 | | | 44 / 56.8% / -Rs 13,794 | 1, 2, 4, 5, 6, 8, 9 |

(1 lot at today's lot, after slippage and dated charges. Gates: 1 design and post-Nov-2024 > 0; 2 2026 > 0; 3 40+ trades;
4 > 0 without the 5 best; 5 twin p < 0.05; 6 BH q <= 0.10; 7 worst month >= -Rs 13k and drawdown <= Rs 40k; 8 > 0 at 2x
slippage; 9 win rate >= 80% in design and in 2026.)

## In plain English

- **The best new rule is H1:** sell the NIFTY condor (5 steps out, hedged 10 out) at 15:15 the day before expiry. Exit
  everything if the index closes beyond either sold strike, otherwise close at 15:05 on expiry day.
  - It made money in 2021-2025 (+Rs 33,131 over 225 weeks), after Nov 2024 (+Rs 15,635, 57 weeks) and in 2026
    (+Rs 17,027, 45 weeks).
  - Its worst week was -Rs 8k and its drawdown Rs 14.5k: the first selling rule in R195-R199 inside Boss's account stop
    over the whole span.
  - **But its win rate is 75% (2021-2025) and 67% (2026), not 80%.** The stop turns some would-be wins into small
    losses. That is the price of cutting the big losses: the average loss fell from Rs 3,023 (R198's F5) to Rs 1,612.
- **Why it still fails:**
  - Entering the same way on any other day of the week did about as well (twin p 0.24), so the expiry-eve timing is not
    shown to be special.
  - Without its 5 best weeks it loses.
  - With 4,897 ideas logged, BH needs far stronger evidence.
  - It needs about Rs 1.5 lakh of margin (expiry-day ELM), almost three times Rs 56k.
- **The rules that kept about 80% wins** (the high-VIX filter, H4: 84.8% over 92 weeks) still had average losses about
  5x their wins. They made almost nothing (+Rs 3,840) and fell to 70% in 2026.
- **Selling further out (7 steps) won less, not more:** the premium left there on the eve (about Rs 350 a lot) is
  smaller than the charges (about Rs 160), so many "wins" become small net losses.
- **The one structure Rs 56k can carry** (H12, a put spread closed before expiry day) lost money in both periods.

**After R195-R199 (126 selling rules): no rule wins 80% and makes money reliably.** The best risk-controlled rule (H1)
is honest at 67-75% wins with small, capped losses. It needs about Rs 1.5 lakh and has not passed the evidence gates.
If Boss wants it watched, the right next step is the forward window from 16 Nov 2026 (read once at 40+ weeks, about
nine months for a weekly rule), not money.

## Caveats

R198's caveats apply: strikes limited to +-10 steps, far legs marked by Black-Scholes when they leave the window, modelled
slippage, R195's margin model, today's lot, and 2026 as a second look. An index stop is checked on minute closes and
fills at the next minute's open; a gap past the strike overnight fills at the next session's first minute, as modelled.
