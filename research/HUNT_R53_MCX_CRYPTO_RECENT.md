# R53: rejected MCX, gold and crypto rules, re-tested on the recent market only (15 Oct 2026)

Boss, 15 Oct: "any more strategies we rejected, try that too" and "add the Nov 2024 onwards view. Don't test any data
before that". R53 took every rejected MCX, gold and crypto rule, de-duplicated them and re-tested the ones that were in
the app, proposed by Boss, or called "close".

## Short answer

- **Nothing passes.** 31 rules were re-tested as 44 final-test versions: 18 MCX, 18 gold (XAUUSD) and 8 crypto.
  - **0 pass every gate.**
  - **10 have too few final-test trades to judge** (fewer than 40).
  - **Nothing goes to paper because of R53, and nothing in the app changes.**
- **The closest is the app's US-night silver arm (MA2).** On the final test (1 Apr - 12 Oct 2026) it made **+Rs 62,850
  on 109 trades** (57% won, +Rs 459 a day, max drawdown Rs 18.5k). Its design walk-forward was also positive
  (+Rs 1.42 lakh).
  - Twin p is 0.036, but the BH q-value across all 44 final tests is **0.198**, so it fails the q <= 0.10 gate.
  - Almost all of the money came from shorts (+Rs 56.5k; longs +Rs 6.4k).
  - It stays a "not proven" paper arm, as it is now.
- **Two other app MCX arms lost on the final test.**
  - NG evening breakout (MA1): -Rs 3,508 on 115 trades. It was +Rs 19k in design.
  - 12-month trend (MA10): -Rs 66,099 on 28 leg-months, a loss on every leg except crude.
  - Switching them off would lower risk, and R53 leaves that to Boss (they are paper only).
- **R2's "fade the gap residual" (MA4) made money in the final test in all three books, but lost in design.**
  - GOLDTEN future: +Rs 17.0k, p 0.004, q 0.059; fails the walk-forward gate (-Rs 9.1k).
  - NG option: +Rs 20.2k, p 0.008, q 0.088; fails the walk-forward gate (-Rs 20.0k).
  - CRUDEOILM future: +Rs 22.6k, p 0.032, q 0.198. Its walk-forward is +Rs 2.6k, but its design net is -Rs 0.4k, its
    top 5 trades are the whole final net, and q is above 0.10.
- **Gold:** none of the 13 XAUUSD rules passes.
  - Most did well in design (Nov 2024 - Mar 2026: GB4, GB5-4h, GB8, GB9, GB12 and GB13 made +$80k to +$245k per
    standard lot). Then they lost or shrank in the final test, which was gold's choppier 2026.
  - GB9 (Liquidity 1h, confirmation 20) made +$25.5k on only 23 trades: too few to judge.
- **Crypto:** all 8 rules lose after tax, both in design and in the final test. Every version had a stop and a profit
  lock.
  - The nearest was RC2 R8 (previous-day high/low break on Delta daily options): +Rs 16,902 before tax, -Rs 28,866 after
    tax, on 313 trades.
  - Plain buy-and-hold made +Rs 15.5k (BTC) and +Rs 12.9k (ETH) after tax per Rs 1 lakh over the same final window.
- **The old "close" MCX GOLDM results were data holes.** The lake fills holes in M3's old downloads, and on it:
  - GOLDM evening break (MA1b) goes from +Rs 313 a trade to -Rs 386;
  - GOLDM morning range break (MA8) goes from +Rs 110 to -Rs 315.

## What was tested and what was left

The full list is in `hunt/r53/CATALOGUE.md`: every rejected rule, its source, its old verdict and sample, its signal
source, and why it was or was not re-tested.

- **Re-tested: 31 rules.**
  - 10 MCX rule families, run as 16 books. Three of the four app arms are included: NG evening (MA1), US-night silver
    (MA2) and 12-month trend (MA10). The fourth, the M4 SILVERM morning call, is R51's and is skipped here.
  - 13 gold rules (15 books).
  - 8 crypto rules.
- **Left, each with its reason:**
  - rules that were far from passing (the ORB, scalp and colour-run grids, M3's liquidity / US-ORB / H60, F1C's other 12
    perps, RC2 R1/R2/R3/R5/R7);
  - model rules (NN_CRUDE, NN_CRYPTO, the C1/C2 logistic models, STRAD_CRUDE's HAR/MLP);
  - event rules with too few trades by construction (STRAD_CRYPTO's CPI/FOMC);
  - RC1's slow regime rules (not Boss's "enter, take profit or stop, exit");
  - F1C P10 (needs the crypto brain's ribbon features past March 2026);
  - R2 C5 (no XAG/crude/gas minute source reachable).

## Signal source

Every tested rule reads its signal from the underlying:

| book | signal source |
|---|---|
| MCX | the near future (the lake's per-minute option `spot`), or for MA2/MA4 the US future plus USD/INR |
| gold | XAUUSD itself |
| crypto | the BTC/ETH perpetual future, with DVOL or open interest as context for CC2, CC3, CC5 and CC6 |

No rule reads an option's own price chart. Option rules buy the contract chosen by the strike rule, and use the
premium only for the stop, lock and P&L.

## Method (pre-registered in `hunt/r53/PREREG.md`, commit 3d233ea4, before any P&L)

### Data
- Only data from 20 Nov 2024 onwards is traded. Earlier bars are used only to warm up indicators.
- MCX minutes exist from 5 Aug 2025 only (the lake's MCX option files), so MCX intraday design is
  **5 Aug 2025 - 31 Mar 2026**.
- Every other design period is **20 Nov 2024 - 31 Mar 2026**, with an anchored walk-forward (quarterly; MCX two-monthly).

### Final test
- **1 Apr - 12 Oct 2026** (the lake ends 12 Oct). It was read once per rule. Lock files are in `hunt/r53/results/`.
- It is not unseen data. M3/M4, R2/R6, RC1/RC2, NN, STRAD and the gold studies all looked at parts of it. It is a fresh,
  single, pre-registered measurement, nothing more.

### How each trade is measured
- One held contract per trade:
  - The MCX option strike is chosen at the signal from the near-future price, then that one contract is priced.
  - R42's F1 bad-print filter is applied.
  - Only printed minutes are used.
- Fills: next bar. Gap fills are at the worse price. Delta options fill one minute later still.
- Costs:
  - MCX: Zerodha MCX charges plus half the spread per side (doubled before 17:00 for options).
  - Gold: the studies' own costs (bid + 0.30 ask, $7 a lot, swap where charged).
  - Crypto: Delta India fees + 18% GST and RC2's spread model, reported again in the CoinSwitch PRO book with and without
    GST.
- Crypto tax: 31.2% of each winning trade, no set-off. 1% TDS is shown as cash locked for a year.
- Every crypto trade has a stop-loss and a profit lock.

### Statistics and gates
- 500 random-entry twins per tested version. They use the rule's own exits where its code allows; otherwise they are
  hold-matched (listed in each book's NOTES.md).
- One Benjamini-Hochberg step over all 44 final-test p-values.
- Gates (all needed): final-test net > 0 (crypto: after tax); p < 0.05; q <= 0.10; 40+ trades; top 5 trades < 100% of
  the net; design walk-forward net > 0.
- Each rule ran its original settings first, then a small pre-registered re-tune on design. The final test read only the
  original and the design pick.

### Re-using each study's code
- **MCX:** `hunt/r53/mcx/`, 35 tests. It reproduces the old studies on their own windows:
  - M3 EVE NG: 197 trades, the same trades;
  - R2 C1 NG future: 232 trades, +Rs 355 vs +Rs 346;
  - R3: 250 trades, -Rs 272 vs -Rs 274;
  - R6 Gann swing: exact.
- **Gold:** `hunt/r53/gold/`, 75 tests. Every adapter gives the study's own trades, trade for trade.
  - The release file had 86 missing weekdays. Those were re-fetched as real Dukascopy bars; nothing was forward-filled
    (gold/NOTES.md).
- **Crypto:** `hunt/r53/crypto/`, 33 tests.
  - Signals agree with RC2 / F1C 80-99% (perp vs index/spot).
  - RC1 g's trades match exactly.
- **Global BH and gates:** `hunt/r53/aggregate.py`.

### Deviations (each in its book's NOTES.md)
- **MCX:**
  - Futures fill at minute closes, because the lake's near-future series has closes only.
  - MA2's 3% stop is checked on closes.
  - An MCX option target that gaps over its level fills at the open (the better price), not the level. Only MA6 has an
    option target, and none of its target exits gapped, so no number changes.
  - **MA5 ran by mistake with one signal a day.** It is kept and labelled. The pre-registered uncapped rule (MA5u) was
    read after the lock, as a labelled correction.
  - SILVERMIC, GOLDTEN, NATGASMINI and CRUDEOILM are priced from their big brothers' near future, scaled.
  - MA10's minis come from the four continuous daily series in the lake, so ZINC, LEAD and ALU are not included.
- **Gold:**
  - GB2 used M5, the old study's only positive buys-only cell.
  - Two studies filled stops at the level on a gap; R53 fills them at the open.
  - Overnight swap ($40 a lot a night) was charged only where the old study charged it (GB13, the trend rule). For the
    other rules that hold overnight it is not in the net. On the final test that flatters them by $40 to $3,560 a lot
    (GB9 $280, GB5-1h $1,880, GB3 $2,440). No verdict changes; each rule's would-be swap is in `gold_final.json`.
  - One GB9 final entry sat in the daily break at a stale close. At the next real price the final net is +$25,490, not
    +$26,813.
- **Crypto:**
  - Delta 1-minute MARK candles are used (RC2 used 5-minute).
  - F1C's "traded in the last 30 minutes" check is dropped, because marks have no volume.
  - Binance funding for 1-12 Oct carries the last print forward.

## Results

Money: MCX in Rs per lot. Gold in USD per standard XAUUSD lot (100 oz; 1 GOLDM lot is about 0.032 of it). Crypto in Rs
(options: Rs 1 lakh account, premium <= 2%; perps: Rs 1 lakh notional).

Table columns:
- "design" is the Nov 2024+ design (MCX intraday: Aug 2025+).
- "WF net" is the design walk-forward: the original's out-of-sample folds, or the re-tune's.
- Net per day is per trading day of the final period: 137 MCX (138 for MA10's daily series), 138 gold, 195 crypto
  calendar days.
- Win %, averages, worst day and drawdown are on the final test (crypto: after tax, Delta).

### OLD verdict vs NOW

| id | rule | OLD verdict (sample) | NOW: design | FINAL TEST | verdict |
|---|---|---|---|---|---|
| MA1 | app NG evening breakout | design +Rs 47/tr (197), holdout +Rs 93 (55), q 0.53-0.63; paper arm | 140 tr +Rs 19.4k, p 0.014 | 115 tr -Rs 3.5k | fail |
| MA1b | EVE on GOLDM future | design +Rs 313/tr (192), q 0.45 | 159 tr -Rs 38.0k | 116 tr -Rs 42.9k | fail |
| MA1c | EVE on crude option | design -Rs 633 (203), holdout +Rs 303 (60) | 145 tr -Rs 33.0k | 124 tr -Rs 92.2k | fail |
| MA2 | app US-night silver | holdout 166 tr +Rs 1,189 (q 0.019); paper arm | 122 tr +Rs 142.9k, p 0.048 | **109 tr +Rs 62.9k, p 0.036, q 0.198** | fail (q) |
| MA3 | R2 C1 NG gap follow (fut / opt) | fut q 0.019 (Jan-driven); opt q 0.13 "watch" | fut 147 tr +Rs 82.7k; opt 139 tr +Rs 48.8k | fut -Rs 4.0k; opt +Rs 2.5k (top 5 = 9.3x net) | fail |
| MA4 | R2 C3 gap-residual fade (GOLDTEN / NG option / CRUDEOILM) | GOLDTEN q 0.049; NG opt q 0.13; crude q 0.17 | all three lost | +Rs 17.0k / +20.2k / +22.6k, p 0.004 / 0.008 / 0.032 | fail (WF; crude: q, top 5) |
| MA5 / MA5u | R2 C4 XAU lead, GOLDM option | holdout 128 tr +Rs 228 (q 0.095) | capped 55 tr -Rs 28.9k; uncapped 237 tr -Rs 122.2k | capped -Rs 5.5k; uncapped 153 tr +Rs 16.5k (top 5 = 2.2x net, WF -1.2 lakh; read after the lock) | fail |
| MA6 | R3 GOLDM EMA8/21 + ADX15 morning (outside report) | 222 tr -Rs 274/tr | 88 tr -Rs 17.4k | 175 tr -Rs 49.6k | fail |
| MA7 | R6 Gann swing 15m SILVERMIC | holdout +Rs 439/day, design -Rs 94/day | 106 tr +Rs 33.3k, p 0.078 | 82 tr -Rs 14.2k | fail |
| MA8 | M3 India-open break, GOLDM option | design +Rs 110 (68), holdout -Rs 67 | 45 tr -Rs 23.6k | 115 tr -Rs 13.9k | fail |
| MA9 | M3 crude EIA break | design -Rs 361 (41) | 29 tr -Rs 7.3k | 27 tr -Rs 0.6k | too few trades |
| MA10 | app 12-month trend | Sharpe 0.54, RC p 0.13, SPA p 0.79; paper arm | 64 leg-months +Rs 191.4k | 28 leg-months -Rs 66.1k | too few trades (and a loss) |
| GB1 | Boss Option 1 (EMA9/21 + RSI) | +$15.9k, 265 tr, t 0.52 | 140 tr +$24.6k | 34 tr -$18.3k | too few trades |
| GB2 | Boss Option 2 (Asian sweep) | lost, win 9-17% | 117 tr +$12.6k | 61 tr -$6.2k | fail |
| GB3 | TAS 15m (Boss's setting) | +$72.6k, 947 tr, t 0.92 | 471 tr +$59.3k (no targets +$129.8k) | 181 tr -$30.1k (no targets -$34.7k) | fail |
| GB4 | TAS 4h / 30m | 4h t 2.22; 30m t 2.03 | 4h 28 tr +$157.4k; 30m 216 tr +$171.7k | 4h 12 tr -$36.9k; 30m 86 tr -$31.8k | fail / too few |
| GB5 | AI's Opinion V5 Robust 1h / 4h (Boss) | 1h t 1.55; 4h t 2.25 | 1h +$49.8k; 4h +$170.0k, p 0.008 | 1h 75 tr +$22.3k, p 0.078, top 5 = 1.37x net; 4h 23 tr -$36.3k | fail / too few |
| GB6 | reopen drift | +$62.7k, 716 tr, t 1.83 | 348 tr +$39.7k (23:00 fixed +$64.9k, p 0.004) | 137 tr +$9.2k, p 0.18 (fixed +$9.0k) | fail |
| GB7 | Liquidity India 1h+15m | +$40.7k, 133 tr, t 2.83 | 63 tr +$36.3k, p 0.024 | 23 tr +$6.5k | too few trades |
| GB8 | Liquidity 2h | +$101.2k, 81 tr, t 2.11 | 40 tr +$82.0k, p 0.008 | 14 tr +$2.4k | too few trades |
| GB9 | Liquidity 1h conf 20 | +$121.6k, t 3.21 | 69 tr +$87.5k, p 0.038 | 23 tr +$25.5k, p 0.020 | too few trades |
| GB10 | Liquidity India 15+5 | +$33.3k, 488 tr, t 1.71 | 251 tr +$25.5k | 87 tr +$11.6k, p 0.082, top 5 = 2.0x net | fail |
| GB11 | 1h hammer 2R | +$37.7k, 424 tr, t 0.84 | 197 tr +$48.2k | 69 tr -$13.5k | fail |
| GB12 | Dip keep-half 2 ATR | +$154k, t 2.40 | 464 tr +$112.9k, p 0.018 | 160 tr +$6.1k, top 5 = 5.6x net | fail |
| GB13 | Supertrend 4h, 3-ATR giveback | +$207.6k, t 3.52 | 26 tr +$144.1k (5 ATR +$245.3k) | 9 tr -$0.9k (5 ATR -$1.8k) | too few trades |
| CC1 | RC2 R8 day-high/low break, Delta options | holdout 290 tr +Rs 8.9k pre-tax, -Rs 34.2k after | 790 tr -Rs 54.0k / -138.6k after tax | 313 tr +Rs 16.9k / **-Rs 28.9k after tax** | fail |
| CC2 | RC2 R4 vol burst | holdout 512 tr -Rs 54.7k | 1,578 tr -Rs 229.1k after tax | 557 tr -Rs 135.2k after tax | fail |
| CC3 | RC2 R6 hero | holdout 56 tr -Rs 20.9k | 199 tr -Rs 78.1k after tax | 70 tr -Rs 53.2k after tax | fail |
| CC4 | F1C P07 on perps | p 0.058, holdout -Rs 1.50L | 471 tr -Rs 144.5k after tax | 172 tr -Rs 38.6k after tax | fail |
| CC5 | F1C P13 OI fade | p 0.007, q 0.053, design -Rs 4.26L | 593 tr -Rs 149.8k after tax | 173 tr -Rs 31.4k after tax | fail |
| CC6 | F1C O19 vol burst, options | pre-tax +Rs 1.42L, after tax -Rs 16.8k | 841 tr -Rs 123.0k after tax | 290 tr -Rs 56.6k after tax | fail |
| CC7 | F1C O18 break, options | pre-tax +Rs 42.5k, after tax -Rs 40.9k | 470 tr -Rs 101.6k after tax | 172 tr -Rs 32.8k after tax | fail |
| CC8 | RC1 g 4h breakout, perps | after tax -12.2%/yr, p 0.089 | 98 tr -Rs 98.1k after tax | 43 tr -Rs 29.1k after tax | fail |

### Full final-test tables (every version read; from `hunt/r53/results/r53_tables.md`)

#### MCX (Rs per lot; final test 1 Apr - 12 Oct 2026, 137 trading days)

| rule:version | design tr / net / p | WF net | final tr | win % | net | net/day | avg win / loss | worst day | max DD | top-5 share | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| MA1:orig | 140 / 19,353 / 0.014 | 9,162 | 115 | 43 | -3,508 | -26 | 1,882 / -1,501 | -3,319 | 20,728 | – | 0.164 | 0.450 | fail |
| MA1:stop25 | 140 / 23,048 / 0.012 | 10,299 | 115 | 43 | -5,785 | -42 | 1,882 / -1,536 | -3,653 | 24,308 | – | 0.250 | 0.465 | fail |
| MA1b:orig | 159 / -37,966 / 0.407 | -74,651 | 116 | 39 | -42,878 | -313 | 4,749 / -3,614 | -11,968 | 92,332 | – | 0.437 | 0.687 | fail |
| MA1c:orig | 145 / -33,044 / 0.297 | -15,970 | 124 | 39 | -92,211 | -673 | 4,243 / -3,893 | -13,357 | 119,512 | – | 0.691 | 0.810 | fail |
| MA2:orig | 122 / 142,880 / 0.048 | 141,636 | 109 | 57 | 62,850 | 459 | 3,283 / -2,994 | -9,237 | 18,507 | 0.78 | 0.036 | 0.198 | fail |
| MA3_fut:orig | 147 / 82,666 / 0.012 | 84,472 | 125 | 43 | -4,020 | -29 | 445 / -395 | -1,635 | 7,435 | – | 0.154 | 0.450 | fail |
| MA3_fut:xeod | 147 / 86,344 / 0.026 | 82,798 | 125 | 45 | -20,266 | -148 | 1,150 / -1,227 | -5,185 | 22,497 | – | 0.745 | 0.810 | fail |
| MA3_opt:orig | 139 / 48,784 / 0.012 | 47,064 | 119 | 45 | 2,515 | 18 | 1,399 / -1,123 | -4,334 | 19,304 | 9.30 | 0.092 | 0.337 | fail |
| MA4_crudeoilm:orig | 84 / -433 / 0.230 | 2,640 | 77 | 57 | 22,611 | 165 | 1,213 / -932 | -3,062 | 5,202 | 1.00 | 0.032 | 0.198 | fail |
| MA4_goldten:orig | 88 / -9,416 / 0.605 | -9,070 | 76 | 63 | 17,029 | 124 | 630 / -472 | -2,056 | 3,343 | 0.61 | 0.004 | 0.059 | fail |
| MA4_ng_opt:orig | 83 / -13,435 / 0.403 | -20,009 | 59 | 63 | 20,155 | 147 | 989 / -746 | -1,639 | 4,087 | 0.53 | 0.008 | 0.088 | fail |
| MA5:orig (capped) | 55 / -28,931 / 0.798 | -29,345 | 48 | 31 | -5,493 | -40 | 2,349 / -1,234 | -6,921 | 16,267 | – | 0.114 | 0.385 | fail |
| MA5u:orig (after the lock) | 237 / -122,190 / 0.890 | -119,760 | 153 | 41 | 16,500 | 120 | 1,811 / -1,085 | -10,206 | 21,997 | 2.16 | 0.002 | 0.044 | fail |
| MA6:orig | 88 / -17,432 / 0.172 | -12,750 | 175 | 38 | -49,611 | -362 | 1,302 / -1,267 | -6,151 | 62,948 | – | 0.623 | 0.783 | fail |
| MA7:orig | 106 / 33,291 / 0.078 | 18,935 | 82 | 39 | -14,234 | -104 | 2,164 / -1,669 | -6,304 | 30,093 | – | 0.555 | 0.759 | fail |
| MA8:orig | 45 / -23,591 / 0.768 | -23,024 | 115 | 39 | -13,883 | -101 | 1,393 / -1,094 | -2,917 | 26,542 | – | 0.056 | 0.273 | fail |
| MA9:orig | 29 / -7,285 / 0.649 | -6,972 | 27 | 56 | -567 | -4 | 1,446 / -1,854 | -3,292 | 12,740 | – | 0.253 | 0.465 | too few trades |
| MA10:orig (leg-months) | 64 / 191,444 / 0.080 | 156,510 | 28 | 43 | -66,099 | -479 | 6,406 / -8,935 | -60,267 | 84,319 | – | 0.816 | 0.855 | too few trades |

Notes on the MCX table:
- MA2: shorts +Rs 56.5k, longs +Rs 6.4k on the final test.
- MA10 by leg: CRUDEOILM +Rs 4.1k, GOLDPETAL -Rs 1.2k, NATGASMINI -Rs 27.5k, SILVERMIC -Rs 41.5k.
- GOLDM option prints roughly quadruple from April 2026, so MA6 and MA8 have more final trades than design trades.

#### Gold (USD per standard XAUUSD lot; 138 trading days)

| rule:version | design tr / net / p | WF net | final tr | win % | net | net/day | avg win / loss | worst day | max DD | top-5 share | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| GB1:orig | 140 / 24,573 / 0.080 | 24,898 | 34 | 38 | -18,275 | -132 | 1,709 / -1,928 | -6,808 | 19,968 | – | 0.850 | 0.870 | too few trades |
| GB2:orig | 117 / 12,632 / 0.076 | 16,221 | 61 | 10 | -6,165 | -45 | 4,124 / -562 | -2,218 | 12,385 | – | 0.577 | 0.759 | fail |
| GB3:orig | 471 / 59,297 / 0.535 | 54,754 | 181 | 34 | -30,078 | -218 | 3,351 / -1,954 | -7,832 | 48,570 | – | 0.471 | 0.691 | fail |
| GB3:rt_notargets | 471 / 129,771 / 0.275 | 101,808 | 181 | 32 | -34,721 | -252 | 3,540 / -1,951 | -7,832 | 50,557 | – | 0.471 | 0.691 | fail |
| GB4-4h:orig | 28 / 157,420 / 0.140 | 134,443 | 12 | 17 | -36,940 | -268 | 14,094 / -6,513 | -12,198 | 43,009 | – | 0.675 | 0.810 | too few trades |
| GB4-30m:orig | 216 / 171,712 / 0.170 | 137,930 | 86 | 34 | -31,845 | -231 | 4,952 / -3,078 | -11,954 | 59,510 | – | 0.493 | 0.700 | fail |
| GB5-1h:orig | 229 / 49,805 / 0.393 | 47,234 | 75 | 37 | 22,274 | 161 | 4,258 / -2,063 | -4,693 | 24,373 | 1.37 | 0.078 | 0.327 | fail |
| GB5-4h:orig | 86 / 170,046 / 0.008 | 155,097 | 23 | 22 | -36,308 | -263 | 7,471 / -4,092 | -7,210 | 55,014 | – | 0.754 | 0.810 | too few trades |
| GB6:orig | 348 / 39,656 / 0.124 | 40,437 | 137 | 44 | 9,204 | 67 | 1,176 / -797 | -3,292 | 16,232 | 2.30 | 0.176 | 0.455 | fail |
| GB6:rt_fixed2300 | 337 / 64,855 / 0.004 | 57,251 | 137 | 56 | 9,017 | 65 | 663 / -700 | -2,816 | 9,079 | 1.20 | 0.134 | 0.420 | fail |
| GB7:orig | 63 / 36,307 / 0.024 | 30,145 | 23 | 57 | 6,458 | 47 | 1,095 / -778 | -2,147 | 3,365 | 1.94 | 0.238 | 0.465 | too few trades |
| GB8:orig | 40 / 81,986 / 0.008 | 80,268 | 14 | 50 | 2,377 | 17 | 4,349 / -4,009 | -14,436 | 20,425 | 11.68 | 0.385 | 0.628 | too few trades |
| GB9:orig | 69 / 87,501 / 0.038 | 87,170 | 23 | 48 | 26,813 (25,490 corrected) | 194 | 4,109 / -1,532 | -2,689 | 6,948 | 1.22 | 0.020 | 0.146 | too few trades |
| GB10:orig | 251 / 25,538 / 0.074 | 22,063 | 87 | 47 | 11,599 | 84 | 893 / -544 | -3,063 | 7,634 | 2.01 | 0.082 | 0.327 | fail |
| GB11:orig | 197 / 48,230 / 0.062 | 52,236 | 69 | 29 | -13,503 | -98 | 2,545 / -1,314 | -3,904 | 24,901 | – | 0.701 | 0.810 | fail |
| GB12:orig | 464 / 112,866 / 0.018 | 111,569 | 160 | 51 | 6,065 | 44 | 2,065 / -2,041 | -12,625 | 37,299 | 5.63 | 0.240 | 0.465 | fail |
| GB13:orig | 26 / 144,135 / 0.074 | 139,999 | 9 | 33 | -876 | -6 | 8,450 / -4,371 | -13,020 | 19,343 | – | 0.325 | 0.551 | too few trades |
| GB13:rt_giveback5 | 26 / 245,310 / 0.018 | 224,053 | 9 | 22 | -1,782 | -13 | 21,461 / -6,386 | -13,020 | 22,906 | – | 0.297 | 0.523 | too few trades |

#### Crypto (Rs; final test 195 calendar days; every trade with a stop and a profit lock)

| rule:version | design tr / net before / after tax / p | WF net (after tax) | final tr | win % | Delta before / after tax | CoinSwitch GST before / after | CoinSwitch no GST before / after | after-tax net/day | avg win / loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CC1:s40_step | 790 / -54,024 / -138,576 / 0.006 | -84,797 | 313 | 34 | 16,902 / -28,866 | -4,431 / -48,313 | 3,174 / -41,468 | -148 | 961 / -624 | -2,810 | 47,421 | 0.002 | 0.044 | fail |
| CC2:s40_trail | 1,578 / -60,980 / -229,130 / 0.002 | -194,083 | 557 | 35 | -89,923 / -135,228 | -118,744 / -161,508 | -107,780 / -151,602 | -693 | 512 / -650 | -5,413 | 139,435 | 0.014 | 0.123 | fail |
| CC3:s70_step | 199 / -26,718 / -78,109 / 0.002 | -63,397 | 70 | 36 | -46,984 / -53,204 | -119,407 / -123,080 | -106,910 / -111,015 | -273 | 549 / -1,487 | -3,895 | 55,817 | 0.238 | 0.465 | fail |
| CC4:s15_trail | 471 / -102,668 / -144,509 / 0.994 | -97,503 | 172 | 35 | -26,290 / -38,618 | -26,290 / -38,618 | -23,260 / -35,920 | -198 | 453 / -588 | -3,476 | 43,801 | 0.890 | 0.890 | fail |
| CC5:s15_step | 593 / -89,581 / -149,841 / 0.916 | -100,613 | 173 | 45 | -18,964 / -31,375 | -18,964 / -31,375 | -15,907 / -28,758 | -161 | 355 / -612 | -4,954 | 32,612 | 0.587 | 0.759 | fail |
| CC6:s25_step | 841 / -48,649 / -123,042 / 0.002 | -97,176 | 290 | 28 | -34,252 / -56,608 | -47,062 / -68,390 | -41,608 / -63,395 | -290 | 616 / -504 | -2,905 | 71,331 | 0.218 | 0.465 | fail |
| CC7:s40_trail | 470 / -52,755 / -101,574 / 0.018 | -77,661 | 172 | 34 | -12,883 / -32,849 | -23,259 / -42,489 | -19,327 / -38,891 | -168 | 759 / -674 | -2,888 | 46,005 | 0.244 | 0.465 | fail |
| CC8:orig | 98 / -47,255 / -98,102 / 0.687 | -36,099 | 43 | 23 | -10,247 / -29,082 | -10,247 / -29,082 | -9,490 / -28,382 | -149 | 4,153 / -2,140 | -6,402 | 45,386 | 0.750 | 0.810 | fail |

Notes on the crypto table:
- No crypto version made money after tax in design, so there were no design picks. The final test read the originals
  only.
- The very low twin p of the option rules (CC1 0.002) means random option entries with the same stop and lock lose even
  more. That is the cost of the option spread, not an edge: the rule still loses after tax.
- TDS locked in the final test: about Rs 6.5k for CC1, and about Rs 1.7 lakh for CC4/CC5 (1% of every perp sale, as F1C
  assumed; RC1 assumed none on INR perps; a CA should confirm).

Baselines, after tax, per Rs 1 lakh (not tested, not gated):

| window | BTC buy-and-hold | BTC monthly DCA | ETH buy-and-hold | ETH monthly DCA |
|---|---|---|---|---|
| design | -Rs 17.7k | -Rs 22.1k | -Rs 24.7k | -Rs 19.6k |
| final | +Rs 15.5k | +Rs 11.5k | +Rs 12.9k | +Rs 12.6k |

## What could come back on PAPER, and what stays off

**Nothing comes back on paper because of R53.** No rule passes every gate. Under CLAUDE.md, ideas that fail the gates
stay notes only.

The app's MCX paper arms are not changed by R53. Boss decides.

| arm | R53 result | what it means |
|---|---|---|
| US-night silver (MA2) | the closest of all 44: +Rs 62.9k on 109 final trades, design walk-forward +Rs 1.42 lakh, p 0.036 | fails only the BH q gate (0.198). It may stay as the "not proven" paper arm it already is. Exact settings as the app has them: SILVERMIC near future, 09:05 entry on the sign of the SI=F + INR=X move since the last MCX close, 3% disaster stop, out 10 min before the close. |
| NG evening breakout (MA1) | lost on the final test (-Rs 3.5k, 115 trades) | turning it off would be a risk-lowering paper change |
| 12-month trend (MA10) | lost on the final test (-Rs 66.1k), and too few leg-months to judge | turning it off would be a risk-lowering paper change |
| SILVERM morning call (M4) | R51's | already off since 14 Oct |

Watch-list only. These are not paper candidates, because each failed at least one gate:
- **R2's gap-residual fade (MA4).** It is positive with low p in all three books on the final test, but it lost in
  design (and CRUDEOILM's net is all in its top 5 trades).
- **GB9 Liquidity 1h confirmation 20.** Good in design and in the final test, but only 23 final trades.

Both need another out-of-sample period before anyone could judge them.

**Crypto:** nothing. Every rule loses after tax under both Delta and CoinSwitch costs. Nothing in the app trades crypto.

## Files

- `hunt/r53/CATALOGUE.md`: the list of rules, tested and left.
- `hunt/r53/PREREG.md`: the pre-registration.
- `hunt/r53/common.py`: the harness.
- `hunt/r53/aggregate.py`: global BH and gates.
- `hunt/r53/entries.py`: the experiment entries.
- `hunt/r53/{mcx,gold,crypto}/`: code, tests and NOTES.md for each book.
- `hunt/r53/results/`: design and final JSON, lock files, small per-trade CSVs, `r53_gates.json` and `r53_tables.md`.
- Raw data stays outside git: the Drive lake, Binance archive, Delta India API, Deribit, Yahoo and Dukascopy (fetchers in
  `hunt/r53/fetch_*.py` and in each book).
- Experiment entries: `experiments/entries/r53-*.yaml` (39 files, family `mcx-crypto`).
