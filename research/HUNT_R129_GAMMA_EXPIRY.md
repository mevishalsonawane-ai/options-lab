# R129: the "Gamma-Driven Hero-to-Zero" expiry strategy, backtested as stated

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss pasted the strategy (a VIX filter, a 3x OI
imbalance wall, an index box breakout through the wall, heavyweights above VWAP, a dual Supertrend on the option's 1-minute
premium, ₹15-30 premiums, a 4x target), then said *"Okay, now test the gamma strategy"*.

## The answer: it almost never fires, and when it did, it lost

**On 917 expiry days across the five indices (Aug 2021 - Oct 2026), the stated rules lined up only 2 to 6 times, depending on the
version.**
- Every version lost money; none of the 2-6 trades ever reached 4x. Every one ended on a Supertrend flip.
- Its central claim also fails: after the index crossed a 3x OI wall, it did not keep running.

| version | trigger read on | trades | won | avg net | total (1 lot) | vs random entries |
|---|---|---|---|---|---|---|
| G1 (as written) | the option's premium chart | 2 | 1 | −₹210 | −₹421 | p 0.62 |
| G2 (as written, out by 15:05) | the option's premium chart | 2 | 1 | −₹210 | −₹421 | p 0.60 |
| G3 (index signal) | the index | 2 | 1 | −₹841 | −₹1,682 | p 0.71 |
| G4 (index signal, out by 15:05) | the index | 2 | 1 | −₹841 | −₹1,682 | p 0.80 |
| G5 (nearest OTM at ₹15-30) | the option's premium chart | 5 | 1 | −₹780 | −₹3,899 | p 0.97 |
| G6 (nearest OTM at ₹15-30) | the index | 6 | 1 | −₹900 | −₹5,402 | p 0.99 |

- The single win was the same trade in every version: BANKNIFTY, 16 Sep 2021, +₹363. The losses came from BANKNIFTY, FINNIFTY and
  SENSEX in 2024-25. NIFTY and MIDCPNIFTY never traded.
- G1, G2, G5 read their signal from the option's own price chart. That is not the default signal source (CLAUDE.md), so they are
  labelled and reported apart. G3, G4, G6 read it from the index.

**Nothing passes; there is nothing to put on paper.** With 2-6 trades in five years there is also nothing to tune: the strategy is
too rare to be a strategy.

## Why it almost never fires (each filter, on 917 expiry days)

| filter | days it stopped |
|---|---|
| no 3x call/put imbalance at the heaviest OI wall | 517 |
| India VIX 12 or under at 13:15 | 194 |
| no 5-minute close through both the box and the wall, 14:00-15:00 | 184 |
| heavyweights not beyond their VWAP | 8 |
| the ATM±1 option not priced ₹15-30 | 8 |
| reached the trigger stage | 6 |

- **The 3x wall is the main blocker.** On expiry afternoon the heaviest OI sits at the money (median 0.6 strikes from spot), where
  both calls and puts are heavy. Only 14% (NIFTY) to 25% (BANKNIFTY) of those walls had a 3x imbalance (80 recent expiries each).
  A 3x imbalance is common further out of the money, but there the strike is not the "heaviest wall".
- **By index:**

  | index | expiry days | no 3x wall | VIX ≤ 12 | no breakout | reached a trigger |
  |---|---|---|---|---|---|
  | NIFTY | 272 | 172 | 52 | 48 | 0 |
  | BANKNIFTY | 195 | 115 | 26 | 51 | 2 |
  | FINNIFTY | 173 | 80 | 31 | 53 | 1 |
  | SENSEX | 176 | 86 | 57 | 25 | 3 |
  | MIDCPNIFTY | 101 | 64 | 28 | 7 | 0 |

## The claim, checked: "a crossed 3x wall brings an explosive move"

- There were 22 days when the index broke out through a 3x wall between 14:00 and 15:00.
- From the setup bar's close to 15:20, the index then moved −6 points the wall's way on average, and went the right way on only 12
  of the 22 days (55%). There was no follow-through: the "blast" was the break itself.
- From 14:00 the same days show +134 points. That number is selection: those days were picked because the index had already
  broken out. On wall days without a breakout it was −31 points.

## How it was done

- **Pre-registration:** `research/hunt/r129_gamma_expiry/PREREG.md`, commit `39ec9da`, before any number.
- **Code and tests:** `research/hunt/r129_gamma_expiry/r129.py`, `test_r129.py` (7 tests, including a look-ahead guard on the
  breakout). Results: `results/results.json`.
- **Data:** the lake's index and VIX minutes; option minutes (cleaned; minutes with no trade never priced) with per-strike OI;
  HDFCBANK, ICICIBANK, AXISBANK and RELIANCE minutes for the VWAP check. Each index's own expiry days; after Nov 2024 these are
  monthly for BANKNIFTY, FINNIFTY and MIDCPNIFTY.
- **Rules as pre-registered:**
  - VIX above 12 at 13:15.
  - At 13:45, the strike with the most call + put OI (±10 strikes) must have a 3x call-over-put (bullish) or put-over-call
    (bearish) imbalance.
  - The 13:15-13:59 box (wicks).
  - In 14:00-15:00, the first 5-minute close beyond the box and the wall, beyond the TWAP; the index must still be on the near
    side of the wall at 13:59.
  - The three heavyweights beyond their VWAP (none stated for MIDCPNIFTY).
  - The ATM+1 CE / ATM−1 PE at ₹15-30 at the setup.
  - Trigger: both Supertrend(7,3) and (10,2) the trade's way and the option's volume over its 10-minute mean; buy at the next
    minute's open.
  - Exits: 4x target resting; out when either Supertrend flips; else at 15:25 or 15:05.
  - Supertrend is R73's (the app's).
- **Money:** 1 lot at today's lots, dated charges, liquidity-aware slippage, and 2x slippage.
- **Twins:** 200 random entries a trade (same day and contract, 14:00-15:00, the same exits). BH over the 6 versions.
- **Gates (R127's):** ≥ 300 trades, positive at 1x and 2x, 3 of 5 years positive, q ≤ 0.10, the account stop. All versions fail
  them.

## The 2% sizing rule (as a table only)

Lots = capital × 2% ÷ (premium × lot size), rounded down, with today's lots:

| capital | NIFTY (₹15 / 20 / 25 / 30) | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX |
|---|---|---|---|---|---|
| ₹5 lakh | 10 / 7 / 6 / 5 | 19 / 14 / 11 / 9 | 11 / 8 / 6 / 5 | 5 / 4 / 3 / 2 | 33 / 25 / 20 / 16 |

₹2 lakh and ₹10 lakh are in `results.json`. With a rule that loses, more lots only lose more.

## Honest notes

1. **The rule had gaps; each was fixed before any result:**
   - which strike is "the wall" (the heaviest call + put OI);
   - the box (13:15-13:59);
   - TWAP (the mean minute close);
   - the heavyweights for indices other than BANKNIFTY;
   - "crosses within the window" (still on the near side at 13:59).

   A looser reading would trade more often, but no reading can rescue the claim: after a wall break the index did not keep going.
2. **The ₹15-30 band is checked at the setup.** By the time the Supertrend trigger fired, some premiums had already climbed to
   ₹42-81. Checking the band at entry would leave even fewer trades.
3. **The sample is tiny (2-6 trades).** The numbers above say "too rare to use", not "proven to lose by ₹X". The claim check
   (22 days) is the more useful finding.
4. **The source pointed to Quantsapp and Spider Software for "verified backtests".** We have not seen those, and nothing here
   relies on them.
