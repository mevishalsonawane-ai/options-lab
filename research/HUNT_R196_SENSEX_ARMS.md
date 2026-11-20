# R196: should SENSEX be in R72, R51 and Liquidity 15+5? (plain English, for Boss)

20 Nov 2026. Boss asked: *"Also create a new session for adding sensex index for our arms."* Full numbers:
`research/hunt/r196_sensex_arms/results/report.md`; the rules and the gate were fixed first in
`research/hunt/r196_sensex_arms/PREREG.md`. Nothing in the app has changed.

## First, what is already true

- **R72 and R51 already trade SENSEX**, on paper and (through every live gate) live. They read five indices: NIFTY,
  BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX. A SENSEX trade buys BSE's option (BFO) at Zerodha.
- **R72's "breadth" already counts SENSEX.** It is "4 of the 5 indices break together", not NIFTY's constituent stocks,
  so there is nothing to rebuild for SENSEX.
- **Liquidity 15+5 does not trade SENSEX** (only BANKNIFTY, FINNIFTY, MIDCPNIFTY). For it, SENSEX would be new. It was
  tried several times before (H4, H11, H23, H34, LIQ-11 / LIQ-12; R52 re-priced it on real options) and failed each
  time, with older exits.

So the real questions were: keep SENSEX in R72 and R51, and add it to Liquidity or not.

## How it was tested

- 1 Nov 2024 - 13 Nov 2026, 1 lot, real BSE charges (SENSEX lot 20, step 100), slippage, SENSEX's own weekly expiries
  (never traded on SENSEX's expiry day; 15:05 square-off rule as the arms have it).
- Each arm with the exits it uses today: R72 / R51 with the R151 picker and V5 (breakeven at +10%, then the +Rs 500
  step lock); Liquidity with breakeven at +10% or +Rs 1,000 and its index exits (SENSEX index stop 50 points, fixed
  in advance).
- Design period W1 (Nov 2024 - Mar 2026) judged quarter by quarter, then the holdout W2 (Apr - 13 Nov 2026) read once.
- Compared against 2,000 random-entry "twins" (same days, sides and number of trades, same exits).
- Check first: the code reproduced R193's published totals exactly (to the trade and the rupee).

## Results (SENSEX trades only, 1 lot)

| arm on SENSEX | trades | win | net | worst month | max drawdown | holdout (W2) | vs random | gates |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| R72 Breadth Donchian (already on) | 440 | 62.5% | +Rs 35,686 | -Rs 6,917 | Rs 19,985 | 145 trades, -Rs 2,833 | p 0.15 | **failed** |
| R51 Donchian 20 (already on) | 543 | 58.9% | **-Rs 36,842** | -Rs 15,030 | Rs 52,559 | 156 trades, -Rs 9,818 | p 0.89 | **failed** |
| Liquidity 15+5 (new) | 334 | 49.1% | +Rs 16,792 | -Rs 8,151 | Rs 24,748 | 92 trades, -Rs 996 | p 0.71 | **failed** |

- **R72 on SENSEX** made money in the design period (+Rs 38,518, 4 of 6 quarters up) but lost a little in the holdout
  and did not beat random entries convincingly. It is the weakest of R72's five indices (others: +Rs 54k to +Rs 1.17
  lakh) but not a loser.
- **R51 on SENSEX lost money in both periods** and in 5 of 6 design quarters, and broke your account stop (worst month
  -Rs 15,030, drawdown Rs 52,559). It is R51's only losing index: R51 made Rs 2.04 lakh with SENSEX and would have made
  Rs 2.40 lakh without it.
- **Liquidity on SENSEX** made +Rs 16,792 but almost all of it came from its 5 best trades (without them -Rs 29,789), it
  lost in the holdout, and random entries did better on average. It has now failed every time it was tried.

Caveats: no period here is truly unseen (the pooled R51 / R72 totals and R52's SENSEX Liquidity test read these dates
before); dropping SENSEX from R51 was not itself a pre-registered test, so "R51 without SENSEX" is a description, not
a passed result; the random twins have no index exits (the usual R51 convention), which may flatter them a
little in the Liquidity test.

## What you can choose

1. **R72: keep SENSEX as it is** (my suggestion), or switch R72's SENSEX off. It failed the gate, but it made money
   overall and is within your account stop.
2. **R51: switch SENSEX off** (my suggestion: it is R51's one losing index, on paper and live), or keep it. Switching it
   off only lowers risk, so it can act on paper and live through the existing gates; it would come as a labelled
   per-index switch, logged in the change timeline, one tap back on.
3. **Liquidity: do not add SENSEX** (my suggestion: it failed again). If you still want it as an exception, it would run
   on paper only first, labelled "failed gates, Boss's exception", off by default, one tap off; live only by your
   explicit choice with the PIN and every live gate.

Which would you like: R72 keep or off; R51 keep or off; Liquidity SENSEX no, paper exception, or paper and live?
