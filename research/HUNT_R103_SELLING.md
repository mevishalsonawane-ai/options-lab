# R103: defined-risk option selling on NIFTY and BANKNIFTY (22 Oct 2026)

Boss, 22 Oct: "Find strategies with best winrate and more profit", then "Can you enhance it". Research only: nothing in
`android/**` or `.github/**` changes and nothing goes to paper or live. Pre-registered in
`research/hunt/r103_sell/PREREG.md` (committed 994bdf0 before any result; amendments 1 and 2 committed f0d5a9c and the
next commit, before the corrected results and with the holdout unread). Code, tests and tables:
`research/hunt/r103_sell/` (`results/report.md`).

## The answer for Boss

**No. Selling the 09:20 straddle with wings (iron fly, wide iron fly, iron condor) and closing the same day does not
make money on NIFTY or BANKNIFTY after costs. 0 of 24 tests passed.** It does have the high win rate you asked about:
before charges about 60% of non-expiry trades end positive. But the decay earned in one day is about the same as the
slippage, so the gross result is close to zero (between -Rs 117 and +Rs 2 a trade on non-expiry days), and the charges
on eight orders (Rs 196-255 a trade) turn every non-expiry version into a loss of Rs 217-344 a trade.

**And a warning.** The first run looked like a big win: BANKNIFTY wide iron fly on non-expiry days, 70% win,
+Rs 5.4 lakh over 996 trades, every month green since Nov 2024. It was a data artefact (below). We caught it before
reading the holdout, fixed it for all 24 tests, and published both runs.

## Why we tried selling

The Donchian books (the arms with the most profit) were frozen by R75 after 40 "less loss, more win rate" levers
failed; option buying with a high win rate was searched by R48 (6.6 million setups) and lost. R102 then showed what
the option chain over-prices: the close beats the 09:20 ATM straddle on only 7.1% (NIFTY) / 6.5% (BANKNIFTY) of
non-expiry days, and India VIX over-states NIFTY's next month on 81% of days. Selling that premium with wings, so the
loss is capped, was the one untested family with a reason to win often (open question `defined-risk-selling`).

## The rules (pre-registered, no tuning)

- **When:** decide on the 09:20 minute close, fill at the 09:21 open; square off 15:10 (15:05 on expiry day).
  Expiry days (E) and other days (N) are separate tests.
- **What** (centred on the index's ATM strike at 09:20; S = the 09:20 ATM straddle in points):
  - **IF** iron fly: sell the ATM call and put, buy wings 1.0 x S away;
  - **IFW** wide iron fly: wings 1.5 x S away;
  - **IC** iron condor: sell 0.5 x S away, buy wings 1.0 x S beyond.
  Every distance is rounded to whole strikes, between 1 and 10.
- **Exits:** X1 = stop at a loss of 50% of the credit; X2 = the same stop plus a profit lock (armed at +40% of the
  credit, out at +20%) and a target at +60%.
- **Costs:** 1 lot (65 / 30 today; dated lots before), dated charges on every one of the 8 orders, 'liq' slippage
  (5 bps plus 1-4 ticks by the contract's recent volume) on every fill.
- **Data:** Aug 2021 - 14 Jul 2026 design (NIFTY 959 non-expiry and 249 expiry days; BANKNIFTY 998 and 175).
  Held contracts per strike from Dhan's rolling ATM +-10 book, cleaned (R51 Amendment A). Expiry calendar from the
  F&O bhavcopy.

## The results (design, after the fix; 1 lot, net after all costs)

| test | trades | win | net | per trade | worst month | bhavcopy-close check |
|---|---|---|---|---|---|---|
| NIFTY expiry, iron fly, stop | 249 | 37% | -Rs 59,661 | -Rs 240 | -Rs 9,230 | -Rs 50,351 (40%) |
| NIFTY expiry, wide iron fly, stop | 249 | 41% | -Rs 36,209 | -Rs 145 | -Rs 11,069 | -Rs 29,652 (42%) |
| NIFTY expiry, iron condor, stop | 249 | 43% | **Rs 28,163** | Rs 113 | -Rs 5,284 | Rs 32,942 (43%) |
| NIFTY expiry, iron condor, lock | 249 | 52% | -Rs 7,252 | -Rs 29 | -Rs 5,314 | -Rs 5,347 (52%) |
| NIFTY other days, iron fly | 959 | 43% | -Rs 2,10,128 | -Rs 219 | -Rs 16,540 | -Rs 1,87,950 (42%) |
| NIFTY other days, wide iron fly | 958 | 52% | -Rs 2,12,040 | -Rs 221 | -Rs 21,244 | -Rs 1,73,411 (52%) |
| NIFTY other days, iron condor, stop | 958 | 49% | -Rs 2,11,290 | -Rs 221 | -Rs 15,504 | -Rs 1,99,851 (49%) |
| BANKNIFTY expiry, iron fly, stop | 175 | 40% | -Rs 1,189 | -Rs 7 | -Rs 10,568 | -Rs 17,311 (39%) |
| BANKNIFTY expiry, wide iron fly, stop | 175 | 46% | Rs 13,840 | Rs 79 | -Rs 13,782 | -Rs 1,157 (46%) |
| BANKNIFTY expiry, iron condor, stop | 175 | 41% | -Rs 7,417 | -Rs 42 | -Rs 11,270 | -Rs 10,157 (39%) |
| BANKNIFTY other days, iron fly, stop | 998 | 42% | -Rs 3,14,609 | -Rs 315 | -Rs 15,874 | -Rs 2,06,396 (46%) |
| BANKNIFTY other days, wide iron fly, stop | 996 | 47% | -Rs 3,17,010 | -Rs 318 | -Rs 17,553 | -Rs 1,97,275 (52%) |
| BANKNIFTY other days, iron condor, stop | 942 | 44% | -Rs 3,23,612 | -Rs 344 | -Rs 21,087 | -Rs 2,68,157 (45%) |

All 24 rows, with average win and loss, profit factor, year by year, after-Nov-2024, without the best 5 days, 2x
slippage, the bootstrap p and BH q, are in `research/hunt/r103_sell/results/report.md`. The two NIFTY / BANKNIFTY
expiry cells that are positive (iron condor with the stop, +Rs 28,163 on 249 NIFTY expiries; wide iron fly with the stop,
+Rs 13,840 on 175 BANKNIFTY expiries) fail the bootstrap (p 0.19 and 0.38), BH (q 1.00) and the year-by-year gate; the
BANKNIFTY one is -Rs 1,157 at bhavcopy closes and breached the -Rs 13,000 monthly stop.

**Where the money goes** (mean per trade, design):

| | credit received | gross before charges | charges (8 orders) | net |
|---|---|---|---|---|
| NIFTY other days, iron fly | Rs 8,978 | +Rs 2 | Rs 222 | -Rs 219 |
| NIFTY expiry, iron condor, stop | Rs 2,434 | +Rs 309 | Rs 196 | +Rs 113 |
| BANKNIFTY other days, wide iron fly | Rs 15,048 | -Rs 65 | Rs 253 | -Rs 318 |
| BANKNIFTY expiry, wide iron fly, stop | Rs 5,492 | +Rs 281 | Rs 202 | +Rs 79 |

The gross is after slippage. On a normal day the intraday decay of a fixed-strike structure is used up by the moves of
the index and the slippage on four legs in and four out. On expiry day the decay is larger (R102: 60% of the 09:20
straddle is gone by 14:30) and the gross is mostly positive (between -Rs 37 and +Rs 309 a trade), but not by enough to carry the charges with any certainty.

## The artefact we caught (read this before trusting any wing price from the rolling book)

- Dhan's rolling option book carries only the strikes within 10 steps of the ATM. A wing bought 10 steps out stops
  printing as soon as the index moves away from it: in the BANKNIFTY monthly era 39% of the long wings had no print at
  the exit minute and 31% had a last print more than an hour old.
- The first run sold those wings back at their stale last price. Checked against the F&O bhavcopy close of the same
  contract, legs that were still printing matched within a median 0.4-1.0 point, but stale wings were a median 41
  points too high. That alone added Rs 5.66 lakh to the BANKNIFTY wide iron fly in the monthly era, more than its whole
  "profit" (first run: +Rs 5,42,746, 70% win, 996 trades; kept in `results/design_run1.json`).
- Fix (amendment 1, all 24 tests alike): a leg with no print in the last 5 minutes is re-priced with Black-Scholes at
  the minute's index level, with the implied volatility of its own last print. The bhavcopy-close check is reported
  next to every test: the two pricings bracket the truth (the bhavcopy close of a far wing can itself be an old trade)
  and agree on the sign of every non-expiry test.
- **Lesson for every study that uses the rolling book for anything but near-ATM strikes:** check how old each leg's
  last print is at the exit. R45 and R59 found the related "the strike changed between bars" problem; this is the same
  family: the rolling series is not one instrument, and its edges go stale.

## Gates, and what was not done

- Gates (all on design, PREREG.md): bootstrap p < 0.05, BH q <= 0.10 over 24, net > 0 in 3 of 4 years 2022-2025 and
  after 20 Nov 2024, >= 40 trades, > 0 without the best 5 days, worst month >= -Rs 13,000, > 0 at 2x slippage. 0 of 24
  passed (the closest, NIFTY expiry iron condor with the stop: bootstrap p 0.19, q 1.00, fails four gates).
- **The holdout (15 Jul - 19 Oct 2026) was not read**: no test passed design, as pre-registered. It stays unread for
  a future selling study.
- Not tested: holding overnight or for several days (NONDIRECTIONAL.md found the naked overnight straddle positive in
  both of its years); entering later in the day; selling only on high-VIX days. Each would be a new pre-registered study,
  and each adds the overnight gap risk the intraday version avoids.
- Signal source: none directional. The structure is centred on the index's ATM strike (the underlying); stops, locks and
  targets are on the position's premium.

## What it means for our trading

- The high win rate Boss asked for is real for option sellers (about 60% of days end positive before charges), and it
  is exactly the trap R48 warned about: a high win rate with an average loss bigger than the average win. Here the
  average non-expiry win was Rs 453-781 and the average loss Rs 758-1,293.
- Charges matter more than anything for a four-leg trade: Rs 196-255 a trade at 1 lot is 2-8% of the credit. A strategy
  that trades four legs every day needs an edge of that size before it starts.
- Nothing changes on paper or live. The Donchian books stay frozen (R75); Liquidity's plain rules are untouched.
