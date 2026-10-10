# HUNT h14: can better EXECUTION (not better signals) rescue the Rs 5,000/day Liquidity plan?

Code: `research/hunt/h14/`
- `PREREG.md`: the policies, the fill models, the gates and the choice rule, written before any h14 P&L.
- `build_atm.py`: the ATM-strike minute paths for every trade, used by the strike split and the delta estimate.
- `exe.py`: the execution simulator. It is built on h10's `cap.py` Book, and its baseline reproduces h10 to the paisa.
- `run.py`, run in two stages:
  - `pre`: the choice, made on data before 1 Oct 2025.
  - `hold`: one holdout test.

Logs and CSVs are in `scratchpad/hunt/h14/`.

Everything here is option BUYING. The signals, the 1-ITM strike, the books, and every exit decision and its timing are
the app's Liquidity 15+5 arm, unchanged (the h4/h7 port, h10's trade list). Only the way orders are worked changes.
Size is h10's plan: flat BN 26 / FIN 3 / MIDCP 11 lots.

## Verdict (plain language)

**One small execution change helps a little: a marketable LIMIT order at +0.5% instead of a market order on entry. It
does NOT make the plan robust at κ = 0.04.**

1. **The adopted policy is E2.** Buy with a limit at the entry-bar price + 0.5%.
   - Take what is available at once up to that price.
   - Leave the rest resting for 3 minutes, then cancel. Never chase.
   - Exits are unchanged: market orders, worked as in h10.
   - This was the only policy of the 9 that passed every pre-registered gate, and only just: it added +Rs 288/day
     against a required +250.
2. **Holdout, run once, conservative model M2, h10 plan size:**

   | | E2 limit entry | market entry (h10) | change |
   |---|---|---|---|
   | κ 0.02, net/day | **Rs 5,368** | Rs 4,743 | **+625** |
   | κ 0.02, gross/day | Rs 13,170 | Rs 13,933 | |
   | κ 0.02 with h10's loss limits, net/day | **Rs 6,514** | Rs 6,014 | +500 |
   | κ 0.04, net/day | **+362** | -1,304 | +1,666 |
   | κ 0.01, net/day | 7,598 | 7,767 | about the same |

   - The gain comes from the thin books.
     - MIDCPNIFTY net rose from +1.67 L to +2.88 L.
     - FINNIFTY went from -0.55 L to -0.29 L.
     - BANKNIFTY was unchanged at about +10.7 L.
   - In effect the limit refuses fills that would cost more than about 0.5%. That cuts costs from 66% to 59% of gross.
   - It fills 90.5% of the desired lots, against 94.1% for market orders under the cap, and misses 29 trades. Those missed trades would have netted -Rs 14k in total under market
     orders, so missing them cost nothing.
3. **It is not robust at κ = 0.04.**
   - It turns -Rs 1.3k/day into +Rs 0.4k/day. That is breakeven, nowhere near Rs 5,000.
   - It hangs on one fill-model detail. In the post-hoc version that caps the MARGINAL fill price at the limit (instead
     of the average), κ = 0.04 gives -Rs 1.4k/day. At κ = 0.02 that version still gives +Rs 5.1k.
   - **The deciding unknown is still κ.** Execution alone cannot rescue κ = 0.04.
4. **Max capacity (holdout, M2, κ 0.02, FIN 3 / MIDCP 11 plus BN scanned):**

   | BANKNIFTY lots | E2 net/day | market net/day |
   |---|---|---|
   | 100 | Rs 12.4k | 11.5k |
   | 200 | 18.4k | 15.2k |
   | 287 | **23.6k** | 14.8k |

   - The limit stops the curve from bending down at large size, because it buys only the cheap liquidity.
   - At κ = 0.04 every size from 26 to 200 BN lots loses money in the holdout. The exception is 287 lots, which makes
     +Rs 2.9k/day only because the limit leaves most of the order unfilled.
   - The large sizes need Rs 1-1.6 crore of premium.
5. **Everything else made things worse, before the holdout and in it.** This answers the brief's questions directly:
   - **Passive join at the print (E1).** It fills only 65-76% of lots, and it misses exactly the winners. Before the
     holdout, the 162 missed trades averaged Rs +8,910 each under market orders, against Rs +1,942 for an average
     trade. In the holdout the missed trades averaged Rs +10,617. **Adverse selection is brutal.** Holdout result:
     -Rs 712/day.
   - **Slicing entries (TWAP-3/5, E4/E5).** Breakout entries drift away from you, so the later slices cost more than
     the impact they save. Holdout: Rs 2.5-2.6k/day.
   - **Passive or sliced exits (X1/X3) and a pre-placed target limit (X2).** Profitable exits fade while the order
     waits. Holdout: Rs 2.2-4.7k/day.
   - **Splitting 1-ITM with ATM (K1).** It was the best variant on the 10-month window before the holdout (+Rs 724/day),
     but it failed the year-by-year gate (2023 and 2024 were negative). Holdout: +Rs 693/day, information only and not
     adopted.

**Rs 5,000/day realistically: borderline YES at κ ≈ 0.02, NO at κ = 0.04.**
- With the E2 entry the holdout made about **Rs 5.4k/day net (Rs 13.2k gross)**, or Rs 6.5k with h10's loss limits.
- Capital: premium tied up p95 Rs 13.4 L, max Rs 23.8 L, plus a max drawdown of Rs 8-12 L. In total about **Rs 35-40
  lakh**. No margin is needed beyond the premium, because these are bought options.
- P(losing month) is about 44%, and 6-7 of 13 holdout months lost.

## The rule (a few lines)

1. Trade the app's Liquidity 15+5 unchanged, buying the 1-ITM monthly option: BN 26 / FIN 1-3 / MIDCP 4-11 lots.
2. **Entry:** a LIMIT buy at the current price + 0.5%, sized as in h10 (no more than 15% of the last 5 minutes'
   volume). Whatever has not filled after 3 minutes is cancelled. Never chase, and never use a passive limit at or
   below the last price.
3. **Exits:** unchanged market orders, worked over minutes in thin contracts (h10). Do not wait passively on exits.
4. Live, log every fill against the signal-minute print. That is how to learn κ, and κ decides whether any of this
   works.

## Holdout detail (M2, κ 0.02, plan size)

| | net/day | gross/day | worst day | worst month | max DD | losing months | Sharpe |
|---|---|---|---|---|---|---|---|
| market (h10), no limits | 4,743 | 13,933 | -2.14 L | -6.69 L | -13.3 L | 6/13 | 0.82 |
| **E2, no limits** | **5,368** | 13,170 | -2.14 L | -6.20 L | -11.7 L | 6/13 | 0.94 |
| market, with limits | 6,014 | 14,005 | -1.67 L | -2.79 L | -8.8 L | 6/13 | 1.11 |
| **E2, with limits** | **6,514** | 13,335 | -1.61 L | -2.52 L | -8.2 L | 7/13 | 1.22 |

E2 holdout by month, in Rs lakh (no limits):

| Oct | Nov | Dec | Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct (to date) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| -0.94 | -0.69 | -0.29 | +6.99 | +6.48 | +2.00 | -6.20 | -2.91 | +0.31 | +3.64 | +0.39 | +5.31 | -0.73 |

Random entries with the same exits, policy and lots: Rs +2,056/trade against a null of Rs -3,069, p = 0.0005, both
before and in the holdout. BH q = 0.0005.

## Pre-holdout choice table (Rs/day uplift against the market baseline, M2)

| policy | regime window (κ .02) | Jun 2023 to Sep 2025 | 2023 / 2024 / 2025 | κ .04 regime | passes the gates |
|---|---|---|---|---|---|
| E1 passive join | -4,497 | -4,623 | all negative | -1,792 | no |
| **E2 limit +0.5%** | **+288** | +107 | +25 / -14 / +320 | +1,314 | **yes** |
| E3 = E2 then market | -252 | -100 | negative | -387 | no |
| E4 / E5 TWAP-3 / 5 | -1,891 / -1,429 | -982 / -908 | negative | negative | no |
| X1 passive exit | -667 | -620 | negative | +189 | no |
| X2 pre-placed target | -2,064 | -1,397 | negative | -1,639 | no |
| X3 TWAP-3 exit | -172 | -146 | negative | +113 | no |
| K1 split ITM + ATM | +724 | +52 | -263 / -453 / +847 | +1,852 | no (years) |

Over the 9 uplift series, SPA p = 0.21 and White RC p = 0.65. So even E2's uplift is not statistically significant
before the holdout. It is adopted only because the pre-registered gates said so. Pre-holdout levels in the regime
window (Dec 2024 to Sep 2025):
- E2: Rs 5,162/day, against 4,875 for market orders;
- at κ .04: E2 2,163 against 849.

## Honesty notes

- **Fill models.**
  - M1 is h10's model. It reproduces h10 exactly: the per-trade difference is below 1e-9 Rs, and 1 lot with no impact
    gives BN 144,334 / FIN 91,911 / MIDCP 102,311.
  - M2 (the choice model) charges sliced orders cumulative meta-order impact, with no decay credited.
  - Passive fills need the price to trade THROUGH the limit by 1 tick. They fill at most 15% of the minute's volume
    and get no price improvement.
  - Minute OHLCV has no order book. Queue position and hidden liquidity are unknowable, so passive fills are
    approximations in both directions.
- **E2's immediate fill** is sized so that the model's AVERAGE price is at or below the limit (pre-registered). A real
  limit order is bounded by the marginal price. That version (E2m) was added after the choice, for information only:
  - κ 0.02: holdout Rs 5,128/day, against 5,368 for E2 and 4,743 for market orders;
  - κ 0.04: -Rs 1,409/day.

  So the κ = 0.04 rescue is model-dependent; the κ = 0.02 gain survives in both versions.
- **Stop triggers stay at the arm's price**, -15% of the arm's 'real' entry, even when our own fill differs.
- **Disclosure.** While validating the code, one debug printout showed whole-sample totals (all years including the
  holdout) for each policy, before `run.py pre` ran. The choice rule and the gates were already written in PREREG.md
  and were applied mechanically to pre-holdout data. No parameter was changed after that printout. The holdout stage
  ran once.
- **What was counted:** 9 single policies (a combination was not needed, since only one family adopted anything), ×
  3 κ × 2 models, plus E2m post hoc.
- **Inherited caveats:**
  - the exits were tuned through Feb 2026 (h4);
  - the regime window is only 10 months;
  - FINNIFTY monthly is too thin to matter;
  - exchange freeze limits are ignored.
