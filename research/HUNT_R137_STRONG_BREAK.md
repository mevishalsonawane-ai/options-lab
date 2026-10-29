# R137: continuation after a strong-swing break. It worked in design, then lost on new data. Nothing passes

29 Oct 2026. Research only. Step 3 of `research/ICT_BOOKS_STUDY.md`: the strategy built from what R135 found on Indian
indices (continuation, not the books' reversal). The network filter is R123's original N1, because R136 showed the
books' concepts make it worse.

## The answer: 0 of 4 pass

The rule:
- **Signal:** on the index, the first 15-minute close beyond a **strong** swing. A strong swing is one that swept an
  earlier swing and broke structure.
- **Trade:** buy the ATM option at the next minute.
- **Stop:** just beyond the breakout bar.
- **Exit:** a 2R target (B1), or breakeven at 1R and then a 1R trail (B2).
- **Filtered versions (B1N, B2N):** the same, taking only the trades R123's network expects to pay.

| variant | design trades | win | net / trade (2x slip) | total, 1 lot | drawdown / worst month | folds + | p | q | holdout (Apr-Oct 2026, read once) |
|---|---|---|---|---|---|---|---|---|---|
| **B1** strong break, 2R | 797 | 41% | **Rs 156** (Rs 134) | Rs 1,24,444 | -Rs 1,10,366 / -Rs 41,010 | 4 of 4 | 0.01 | 0.01 | **130 trades, -Rs 373 each, -Rs 48,426** |
| **B2** strong break, trail | 792 | 43% | **Rs 176** (Rs 151) | Rs 1,39,456 | -Rs 82,056 / -Rs 29,531 | 4 of 4 | 0.01 | 0.01 | **130 trades, -Rs 461 each, -Rs 59,894** |
| **B1N** + R123 network | 83 | 54% | **Rs 1,130** (Rs 1,112) | Rs 93,818 | -Rs 22,971 / -Rs 12,471 | 3 of 4 | 0.003 | 0.01 | 9 trades, +Rs 1,160 each |
| **B2N** + R123 network | 83 | 54% | **Rs 1,052** (Rs 1,031) | Rs 87,349 | -Rs 16,408 / -Rs 7,771 | 3 of 4 | 0.01 | 0.01 | 9 trades, +Rs 1,196 each |
| control: **weak**-swing break, 2R | 6,365 | | -Rs 37 | | | | 0.03 | | 870 trades, -Rs 296 each |

**Why each fails:**
- **B1 and B2:**
  - **design:** profitable and better than random entries (p 0.01), positive in every fold. But they break Boss's
    account limit (drawdown past -Rs 40k, a month past -Rs 13k on 1 lot across the five indices).
  - **holdout:** they lost. The losses were mostly MIDCPNIFTY (27 trades, -Rs 1,349 each) and BANKNIFTY (24 trades,
    -Rs 730 each); NIFTY, FINNIFTY and SENSEX were near flat.
- **B1N and B2N:**
  - **design:** the best per trade of anything in R130-R137, and inside the account limits. But they had only 83
    design trades (300 needed).
  - **holdout:** 9 trades (50 needed). Those 9 made money (+Rs 10.4k), but 9 is not evidence.

## What R137 adds to what we know

1. **Strong vs weak is real, but not enough.** The same break rule lost on weak swings (6,365 trades, -Rs 37 each) and
   made money on strong ones in design. So the books' strong/weak idea carries information here, as R135 found. But
   it did not survive the holdout once costs were paid.
2. **R123's network filter is again the only thing that holds up on every slice.** That was true here (design 3 of 4
   folds, holdout 9 of 9 net positive in total) and in R132-R134. It is also too selective to judge on the data we
   have. This is exactly what the R134 forward test is for.
3. **The repeated pattern is "design positive, holdout negative".** It held for R131's CISD, R133's combinations and
   now R137. The edges we find are smaller than the noise between periods.

## What is proposed

- **Nothing goes on paper or live.** CLAUDE.md: failed ideas stay notes.
- **One option for Boss, not done:** add **B1N (strong break + R123's network filter)** to the R134 forward log as a
  second, separate rule.
  - It would get its own frozen rules and its own 50-trade decision.
  - It would not change R134's frozen primary rule.
  - At its rate (83 trades in 3.2 years) it would need about 2 years of forward data, so it is only worth adding if
    Boss wants the long watch.

## How it was done

- **Pre-registration:** `research/hunt/r137_strong_break/PREREG.md`, committed before any trade. The design results
  were committed before the one holdout read (lock file).
- **Signal source:** the index spot. The trade buys the ATM option of the nearest non-expiry-day expiry, with R131's
  costs (dated charges, liquidity-aware slippage).
- **Checks:**
  - random entries after the signal with the same exits (100 per trade);
  - for the filtered versions, random subsets of the same trades;
  - BH over 4.
- **Code and tests:** `research/hunt/r137_strong_break/` (`r137.py`, `test_r137.py`: the strong/weak entry on
  hand-built bars and the look-ahead guard).
- **Results:** `results/design.json`, `results/holdout.json`. Trades: `$R137_OUT`.
