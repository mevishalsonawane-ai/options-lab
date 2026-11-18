# R200: the iron fly, checked in full (side study, 18 Nov 2026)

Boss: "Check iron fly strategy". **Research only. The app stays buy-only.**

- **Pre-registration:** `research/hunt/r200_iron_fly/PREREG.md` (8 tests, BH m = 4,905; hash-stamped before any number).
- **Code:** `r200study.py` (the intraday fly with a stop inside max loss and a target; the carried weekly fly through
  R199's `run_carry`, with an `after` entry added to R199 and nothing else changed); 3 tests in `test_r200.py`.
- **Tables:** `results/report.md`, `results/final.json`.

## Verdict

**The iron fly does not work here in any version tested: 0 of 8 new tests, and 0 of the 35 iron-fly tests across R103,
R195 and R200.** Every version lost in 2021-2025 and in 2026, on NIFTY, BANKNIFTY and SENSEX.

| id | rule | 2021-2025: trades / win / net / max DD | 2026: trades / win / net | avg win / avg loss | worst month |
|---|---|---|---|---|---|
| IF1-NIFTY | non-expiry 09:20 fly, wings = the straddle, stop at 50% of max loss | 857 / 46.9% / -Rs 1,96,639 / Rs 1,96,639 | 165 / 43.6% / -Rs 68,821 | Rs 541 / Rs 910 | -Rs 19,021 |
| IF2-NIFTY | IF1 + target 25% of credit | 857 / 47.1% / -Rs 1,90,280 | 165 / 43.6% / -Rs 68,474 | Rs 546 / Rs 907 | -Rs 19,021 |
| IF3-NIFTY | expiry-day 09:20 fly, stop at 50% of max loss | 231 / 31.6% / -Rs 26,800 | 45 / 28.9% / -Rs 18,585 | Rs 2,645 / Rs 1,392 | -Rs 8,141 |
| IF4-NIFTY | IF3 + target 25% | 231 / 46.8% / -Rs 56,097 | 45 / 46.7% / -Rs 6,118 | Rs 1,017 / Rs 1,349 | -Rs 6,476 |
| IF5-BANKNIFTY | IF2 on BANKNIFTY | 836 / 46.9% / -Rs 2,61,010 | 182 / 35.7% / -Rs 95,787 | Rs 600 / Rs 1,118 | -Rs 16,131 |
| IF6-SENSEX | IF2 on SENSEX | 463 / 44.5% / -Rs 1,30,881 | 159 / 40.9% / -Rs 81,589 | Rs 626 / Rs 1,011 | -Rs 11,081 |
| IF7-NIFTY | weekly fly carried to expiry, wings 8 steps, index stop at 4 steps | 229 / 27.1% / -Rs 99,944 | 44 / 25.0% / -Rs 27,499 | Rs 4,259 / Rs 2,180 | -Rs 16,615 |
| IF8-SENSEX | IF7 on SENSEX | 121 / 35.5% / -Rs 40,909 | 44 / 18.2% / -Rs 29,022 | Rs 811 / Rs 972 | -Rs 7,808 |

(1 lot at today's lot, after slippage and dated charges. Every test fails gates 1, 2, 4, 5, 6, 7 and 8.)

## Why: the costs are the whole loss

| test | average gross per trade (before costs) | average costs per trade | mean credit | mean margin |
|---|---|---|---|---|
| IF1-NIFTY | -Rs 32 | Rs 228 | Rs 10,685 | Rs 70,986 |
| IF3-NIFTY (expiry day) | +Rs 42 | Rs 206 | Rs 5,011 | Rs 86,869 |
| IF5-BANKNIFTY | -Rs 88 | Rs 263 | Rs 16,078 | Rs 80,056 |
| IF6-SENSEX | -Rs 110 | Rs 231 | Rs 10,715 | Rs 76,784 |
| IF7-NIFTY (carried) | -Rs 239 | Rs 228 | Rs 15,062 | Rs 1,04,794 |

- **Before costs, an intraday iron fly is a coin flip:** the average is about Rs 0 a trade, from -Rs 110 to +Rs 42.
  The wings cost about what they save.
- **Eight orders a trade** (4 legs in, 4 out) cost Rs 206-263 in brokerage, STT, exchange charges and slippage, every
  day. Over about 860 NIFTY trades that is about Rs 1.9 lakh, which is the whole loss.
- **The stop inside max loss and the 25% target** barely changed anything. The stop fired on only about 7% of
  non-expiry days, and the target on about 1% (the fly rarely decays 25% within a day). On expiry day the target fired
  often (129 of 276) but cut each win to Rs 1,017.
- **Carried to expiry**, the index stop at half the wing width fired in 9 weeks out of 10 (246 of 273), so the fly was
  closed at a small loss most weeks.
- **Margin:** about Rs 71k-1.17 lakh per lot, more than Rs 56k in every version.

**Bottom line:** the iron fly fits the textbook (defined risk, collects time decay), but in Indian index options at one
lot it has no edge before costs and loses after them. Combined with R103 (24 flies at 09:21) and R195 (the hedged
straddle and the expiry-day flies), we have now checked the iron fly 35 ways, and none should go to paper.

## Caveats

R195's caveats apply: modelled slippage (no bid / ask), a margin model, today's lot, wings limited to +-10 steps and
stale wings marked by Black-Scholes, 2026 as a second look.
