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

## Amendment 1: a looser version (Boss: "Try a looser version of the gamma strategy")

Pre-registered before any number (`PREREG_LOOSE.md`, commit `61360fb`). Each R129 filter that blocked the most days was loosened:
- the wall is the nearest strike each way (within 5 strikes) with a 2x imbalance, not the heaviest with 3x;
- no VIX floor;
- no box: a 5-minute close beyond the wall and the TWAP, from 13:50 to 15:00;
- 2 of 3 heavyweights beyond VWAP;
- the nearest out-of-the-money option at ₹10-40.

The trigger, exits, costs, twins and gates are unchanged.

**It trades about 40 times a year now, and it loses in every version and every year.**

| version | trigger read on | trades | won | avg win / avg loss | avg net | 4x reached | total (1 lot) | vs random entries |
|---|---|---|---|---|---|---|---|---|
| L1 | option premium chart (NOT the default) | 204 | 18% | ₹1,059 / −₹447 | **−₹174** | 3.4% | −₹35,484 | p 1.00 |
| L2 | index (default) | 221 | 17% | ₹1,537 / −₹519 | **−₹175** | 7.2% | −₹38,682 | p 0.99 |
| L3 | index (default) | 221 | 17% | ₹1,282 / −₹503 | **−₹204** | 4.5% | −₹45,144 | p 1.00 |
| L4 | index breakout only (default) | 228 | 17% | ₹1,554 / −₹510 | **−₹166** | 7.5% | −₹37,891 | p 1.00 |

L3 exits at 15:05; the others at 15:25.

- **Every year negative in every version** (2021-2026), on every index.
- **Random entries on the same days and options did better** (twin p 0.99-1.00): the rule's own timing hurt.
- **About one trade in six wins.** The wins (₹1,000-1,500) are about 2-3 times the losses (₹450-520), not the 4x the source promises.
  4x was reached on only 3-7.5% of trades.
- **Filters do not rescue it.**
  - VIX above 12: −₹169 to −₹203 a trade.
  - VIX at or below 12: −₹104 to −₹213 a trade.
  - Walls within 1 strike of spot: −₹107 to −₹178.
  - Walls 1-3 strikes away: −₹211 to −₹243.
- **The claim again:**
  - After a nearest-2x-wall break (420 setups), the index moved +13 points its way to 15:20 on average.
  - It went the right way on 54% of days.
  - That is a little follow-through, far too small for a cheap option to pay 4x, and less than the option's decay and spread take.
- **Nothing passes.** q is 1.00 within these 4 versions and over all 10 R129 versions.

## Amendment 2: can a filter keep only the winners? (Boss: "Can we do something where only that 18% won trades can be bought")

Pre-registered before any number (`PREREG_META.md`, commit `f75d18d`).
- **The model:** a logistic model on 10 facts known before each entry: the entry minute, the premium, VIX, the index's move since
  the open, the day's range, the wall's distance and OI ratio, how far out of the money, the direction, and monthly expiry.
- **The keep rule:** keep the top 30% of scores (the threshold is set on the training years).
- **The test:** trained on earlier years and tested on each later year (2023, 2024, 2025, early 2026). The latest half-year was
  read once, after that was committed.
- **The trades:** the loose rule's index-signal versions, L2 (221 trades) and L4 (228).

**It cannot tell the winners apart.**

| filter on | trades tested | kept | won (kept vs all) | avg net (kept vs all) | AUC | winners kept / losers kept | vs random keeps |
|---|---|---|---|---|---|---|---|
| L2 | 143 | 39 | 13% vs 17% | −₹110 vs −₹203 | 0.47 | 21% / 29% | p 0.26 |
| L4 | 148 | 41 | 12% vs 17% | −₹197 vs −₹193 | 0.51 | 20% / 29% | p 0.49 |

The kept rows are the walk-forward 2023 - Mar 2026.

- **Scoring was no better than a coin.** AUC was 0.47 and 0.51 (0.5 is a coin). The filter kept a SMALLER share of the winners
  than of the losers.
- **The kept trades lost less, but only because they were cheaper losers.** The kept trades' average loss was about ₹320-350, not
  ₹540. Their win rate fell to 12-13%, and they made money in only 1 of 4 folds (L2) and 0 of 4 (L4).
- **Apr - Oct 2026 (read once):**
  - L2 kept 5 trades, 40% won, +₹329 a trade.
  - L4 kept 8 trades, 25% won, +₹120 a trade.
  - That rests on the same two winning trades in both (₹1,227 on average), and the filter had already failed the walk-forward,
    so it means nothing.
- **Nothing passes.** The 100-trade floor could not be met anyway (about 45 kept trades in all).
- **Why, plainly:**
  - The winners are the few afternoons when the index happened to keep running after the break.
  - None of the facts known at the entry say which afternoons those will be. R123-R126 found the same for direction over the next
    hour (AUC about 0.51).
  - A filter can only drop trades. With no signal to drop by, it drops winners and losers alike.

## Conclusion

Strict, the rule almost never fires; loose, it fires about 40 times a year and loses about ₹170-200 a trade. The weak point is the
idea itself: on expiry afternoon a broken OI wall does not bring the short-covering blast the strategy depends on, so a cheap OTM
option mostly decays. A filter on facts known at entry cannot pick the rare winners (amendment 2: AUC 0.47-0.51).
Nothing goes on paper.

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
