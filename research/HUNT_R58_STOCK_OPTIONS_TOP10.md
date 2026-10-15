# R58: buying stock options on the top 10 stocks (15 Oct 2026) - 0 of 38 rules pass; the stock does no better

**Verdict.** No rule passes. 38 pre-registered rules were run in 6 families, buying the ATM call or put of a top-10
NIFTY 50 stock on a signal read from that stock. **0 of 38 pass.** Every rule lost in the final test after costs except
two, and those two had lost in design.

**Why.**
- At the option's own traded prices, before any cost, the signals are about break-even:
  - design: -Rs 15 a trade, on 52,602 trades over all 38 rules;
  - final test: -Rs 78 a trade, on 21,519 trades.
- A round trip in a stock option costs about Rs 280 a lot. That is about Rs 200 of spread and slippage plus about
  Rs 80 of charges, about 2% of the typical Rs 14,000 premium.
- So every rule loses roughly what it pays to trade.

**The stock instead of the option.**
- The same signals traded in the stock itself (the same minutes, the lot's number of shares) also lose:
  - final -Rs 1.01 crore on 21,061 trades, against -Rs 76.4 lakh for the options;
  - design -Rs 2.02 crore on 51,506 trades against -Rs 1.54 crore.
- So the problem is the signals, not the instrument.

**Capital per trade.**
- An ATM option costs about **Rs 13,000-17,000** a lot (median Rs 14,184 in the final test, 1 lot).
- The same trade in the stock needs about Rs 1.3 lakh of MIS margin intraday, or about Rs 6-7 lakh for delivery.
- The option loses about the same rupees as the stock on 1/10 of the capital. Per rupee put up, it loses about 10x
  faster.

Research only: nothing in the app changes and nothing goes on paper.

The detailed tables are in `hunt/r58/results/report.md`. Results JSON: `hunt/r58/results/design.json` and
`final.json`. Code: `hunt/r58/`. Tests: `hunt/r58/test_r58.py` (62).

## The question

Boss, 15 Oct 2026: "If we shift from index to actual funds like top50 companies ... Start a deep study on the stock
options now, start with top 10."

Our index-option studies (R47-R55) found almost nothing after costs on the Nov 2024+ market. R58 asks whether single
stocks do better. It buys the ATM option of a big stock on a signal read from that stock.

## How it was tested (pre-registered in `hunt/r58/PREREG.md`, commit ce45ea9, before any P&L)

**Data.** The Drive lake (rclone, 15 Oct 2026):
- stock 1-minute and daily bars;
- stock options (rolling ATM-5..+5 files of the nearest monthly expiry);
- NIFTY 1-minute;
- the weekly NSE F&O bhavcopies.

Only 20 Nov 2024 onwards is traded.
- **Design:** 20 Nov 2024 - 31 Mar 2026 (336 sessions).
- **Final test:** 1 Apr - 12 Oct 2026 (132 sessions), read once. No earlier study had read these stock options.

**Universe (fixed rule).**
1. Start from the 50 current NIFTY 50 names.
2. Rank them by stock-option traded value in the design-period bhavcopies.
3. Keep names whose lake holds the ATM CE and PE for >= 300 minutes on >= 95% of design sessions.
4. Take the top 10 that pass.

The result is **RELIANCE, SBIN, MARUTI, ICICIBANK, INFY, LT, KOTAKBANK, M&M, TRENT, TATASTEEL**. Eight bigger names
failed coverage, mostly because the lake's option file has a hole:

| stock | ATM coverage | gap |
|---|---|---|
| HDFCBANK | 91.4% | no ATM puts Dec 2025 - Jan 2026 |
| BSE | 62.9% | no option rows before Jun 2025 |
| BAJFINANCE | 57.6% | no option rows before Jun 2025 |
| TCS | 91.7% | no ATM calls Feb - Mar 2026 |
| BHARTIARTL | 92.0% | |
| AXISBANK | 91.4% | |
| BEL | 92.0% | |
| ADANIENT | 80.4% | |

`STOCK_OPTIONS_COVERAGE.json` shows >= 99% for BSE and BAJFINANCE. It counts fetched chunks, not rows.

**How a trade is priced.**
- **One held contract** (expiry, strike, side) from entry to exit, priced only on that contract's own minute prints.
  Never the rolling ATM series: R45 / R46 showed it makes fake profits.
- **Data hygiene:**
  - Stray rows (option `spot` more than 3% off the stock that minute) are dropped. For KOTAKBANK and TATASTEEL that
    is about 3,000 rows each.
  - One-minute bad ticks (`spikes.spike_flags`) are dropped.
  - Nothing is filled in.
- **Lot:** the contract's real lot on the trade date. It is read from its traded volumes when that matches the
  bhavcopy's `NewBrdLotQty` for that contract, else the latest bhavcopy lot. Lots moved a lot in this window:
  - RELIANCE 250 -> 500 (bonus);
  - M&M 350 -> 175 -> 200, TRENT 200 -> 100 -> 150 -> 225 and TATASTEEL 5,500 -> 2,750 (NSE revisions);
  - KOTAKBANK 400 -> 2,000 (split).

  The table is in `results/lots.json`.
- **Physical settlement:** no entries on the last 2 trading days before expiry or on expiry day, and every position
  out by 15:15 on the third trading day before expiry. No position is held across a split / bonus ex-date
  (KOTAKBANK 14 Jan 2026, TRENT 4 Jun 2026) or through LT's bad data day (9 Jul 2025).
- **Band exit (every rule):**
  - For these 10 stocks the lake has only ATM and ±1 strikes, so a held contract has prices only while the stock
    stays within about one strike of it.
  - Every rule therefore sells once the stock is 1.25 strike steps away from the held strike. That is a real,
    tradeable rule, but it caps the upside of a held option at roughly a 1-1.5% stock move.
  - Swing rules were also re-run without it, as context, with Black-Scholes prices beyond the band.
- **Costs:**
  - The repo's dated option charges (`obuy.costs`; STT 0.1% of sell premium to 31 Mar 2026, 0.15% from 1 Apr 2026;
    exchange, SEBI, stamp, GST, Rs 20 brokerage an order).
  - A spread fitted per stock on design data. It is the larger of R17's 1-4 ticks and the Roll half-spread of the
    stock's ATM options: 0.38% (RELIANCE) to 0.80% (TATASTEEL) of the premium a side when liquid, plus 5 bps (stops
    10 bps and 2x).
  - Everything is also shown at 2x.
- **The stock instead of the option:** the same entry and exit minutes, lot-many shares.
  - Intraday rules as MIS (both sides).
  - Swing rules as delivery, long only, because a cash short cannot be held overnight.
  - The daily-signal rules that hold one session (`rs-d-*-h1`, `dc20-h1`) buy at 09:20 and sell at 15:15 the same day,
    so they ran as MIS on both sides. The PREREG's wording put them under delivery. Cash is a baseline and is not
    gated.
  - Real cash charges (STT 0.025% MIS / 0.1% delivery, exchange, stamp, GST, DP).
- **Statistics:** 500 random-entry twins per rule: the same stock, side and count; a random session; a random minute
  inside the rule's own window; the same exits. Benjamini-Hochberg runs over all 38 rules.
- **Gates (as R47-R55):**
  1. final net > 0;
  2. p < 0.05;
  3. q <= 0.10;
  4. 40+ trades;
  5. the top 5 trades < 100% of net;
  6. a positive design: design net > 0 and at least 3 of 5 design quarters positive.

## Not tested, and why

- **Post-results drift (family 7):**
  - There is no company-results calendar in the lake or in the repo. `research/brain/calendar_data/events.csv` has
    only budget and RBI dates.
  - Dating results from the same day's move would be look-ahead, so the family was not tested.
  - Results days were not removed from the other rules either. A results day is just another session for them.
- **ATM±2 and wider strikes, OTM / ITM ladders:**
  - The lake has no ±2..±5 rows for these 10 stocks in this window.
  - 1-ITM was tested only in the two pre-registered variants, on the 8 stocks with ±1 coverage >= 95% (not LT or
    TATASTEEL).
- **Next-month contracts:** the lake holds only the nearest monthly expiry, so nothing trades in the last 2 days before
  expiry or on expiry day.
- **HDFCBANK, BSE, BAJFINANCE, TCS, BHARTIARTL, AXISBANK, BEL, ADANIENT:** excluded by the coverage rule (the table
  above), not by results.
- **Holding a winning option beyond about 1.25 strikes:** see the band exit. The no-band context run is modelled, not
  market prices.

## The rules (signal source: the STOCK for every rule; NIFTY too for relative strength; option data never)

All 38 buy 1 lot of the nearest-monthly ATM CE (long) or PE (short) and have a stop and a time exit. Exact settings:
`hunt/r58/PREREG.md` section 5 and `hunt/r58/r58rules.py`.

| family | rules | what they do |
|---|---|---|
| F1 opening-range breakout | `orb15-*`, `orb30-*` (6) | first 5-minute close outside the 15 / 30-minute opening range (by 13:00); stop at the range's other side or -30% premium; one version with a +50% premium target; out 15:15 |
| F2 relative strength vs NIFTY | `rs-i-*` (4), `rs-d-*` (4) | intraday: at 10:15 / 11:30 the stock is up (down) 0.5% / 1% from its open while NIFTY is flat or the other way; stop back through the open or -30%. Daily: the stock beat (lagged) NIFTY by 2% over 1 day or 3% over 3 days while NIFTY was down (up); buy at 09:20 the next day, hold 1 or 3 sessions |
| F3 gap-and-go / gap-fade | `gap-*` (6) | a 1% / 2% gap; go with a first 15-minute bar in the gap's direction, fade one against it (target: the gap filled); stop beyond the first bar or -25% |
| F4 volume and VWAP | `vol-*` (4), `vwap-*` (3) | a 5-minute bar with 2.5x / 4x its usual volume that makes a new high (low) of the day; or price 0.2% above (below) a rising (falling) VWAP at 10:00 / 10:30 / 11:30, out on the first 5-minute close back across VWAP |
| F5 daily momentum / Donchian 20 | `dc20-h1/h3/h5`, `mom10-h3/h5` (5) | a close above the 20-day high (below the low), or a 10-day move of 5%+ beyond the 20-day average; buy at 09:20 next day, hold 1-5 sessions |
| F6 the closest index rules | `dc5-*` (3), `h37-*` (3) | R51's Donchian 20 on 5-minute bars (its re-tuned -15% / +30% / 30-minute exits, the original -25% / +50% / 60 minutes, and 1-ITM); R52's H37-10 "open outside yesterday's value area and go" (60 / 30 minutes, ATM and 1-ITM; a -30% stop added because this brief asks for one) |

## Results

### By family (all rules of a family together, Rs, 1x costs)

| family | design trades | design net | per trade | final trades | final net | per trade | stock in cash, final (same minutes) |
|---|---|---|---|---|---|---|---|
| F1 opening range | 14,343 | -56.0 lakh | -391 | 5,613 | -21.1 lakh | -376 | -22.8 lakh |
| F2 relative strength | 2,155 | -7.0 lakh | -325 | 915 | -3.1 lakh | -340 | -3.9 lakh (863) |
| F3 gaps | 765 | +0.15 lakh | +20 | 441 | -1.7 lakh | -386 | -2.1 lakh |
| F4 volume / VWAP | 7,239 | -17.9 lakh | -248 | 2,847 | -11.4 lakh | -400 | -14.2 lakh |
| F5 daily momentum | 2,303 | -9.2 lakh | -398 | 970 | -5.5 lakh | -572 | -12.7 lakh (564, longs only) |
| F6 index rules on stocks | 25,797 | -64.3 lakh | -249 | 10,733 | -33.6 lakh | -313 | -45.9 lakh |

### The rules that looked best

- **Four rules passed the design gate:**
  - `gap-go-1`: +Rs 15,166 on 140 trades;
  - `gap-fade-2`: +Rs 17,370 on 81;
  - `rs-d-l1-h3`: +Rs 11,518 on 100;
  - `gap-go-2`: +Rs 6,168 on 36.
- **All four lost in the final test:**
  - `gap-go-1` -Rs 46,398 (103 trades);
  - `rs-d-l1-h3` -Rs 47,257 (51);
  - `gap-fade-2` -Rs 11,482 (21, too few);
  - `gap-go-2` -Rs 3,777 (24, too few).
- **Only two rules made money in the final test**, and both had lost in design:
  - `rs-d-l3-h3` (the stock beat NIFTY by 3% over 3 days while NIFTY fell; hold 3 sessions): +Rs 9,014 on 75
    trades, twin p 0.20, design -Rs 48,635;
  - `rs-d-l3-h1`: +Rs 5,421 on 82, p 0.13, design -Rs 59,942.
  - Both are carried by KOTAKBANK (without it, -Rs 15,198 and -Rs 6,158), and both lose at 2x costs.
- **The lowest p-value** was `vol-k4-60` (0.070), with a final net of -Rs 34,980. Its random twins lost even more.
- **BH q is 1.00 for every rule.**
- Every rule's top-5 share is infinite, because no rule's final net is positive enough to need its top 5.

### Every stock loses

Final test, all 38 rules together, Rs (trades). No stock carries anything.

| | RELIANCE | SBIN | MARUTI | ICICIBANK | INFY | LT | KOTAKBANK | M&M | TRENT | TATASTEEL |
|---|---|---|---|---|---|---|---|---|---|---|
| option | -2.5 lakh (2,084) | -9.4 lakh (2,226) | -9.5 lakh (2,279) | -9.6 lakh (2,145) | -7.1 lakh (2,363) | -6.9 lakh (1,785) | -6.7 lakh (2,147) | -7.7 lakh (2,305) | -6.6 lakh (2,346) | -10.5 lakh (1,839) |
| stock (cash) | -1.5 lakh | -12.1 lakh | -15.1 lakh | -16.4 lakh | -6.5 lakh | -10.4 lakh | -8.2 lakh | -12.1 lakh | -5.8 lakh | -13.3 lakh |

- RELIANCE loses least, with the tightest fitted spread (0.38% of premium).
- TATASTEEL loses most:
  - its options cost Rs 3-5, so the Rs 0.05 tick and its 0.80% fitted spread take a large bite;
  - in design it lost Rs 41 lakh on 4,646 trades.

### Costs, the band exit and expiry

- **At 2x costs** the final loss grows from -Rs 76.4 lakh to -Rs 1.36 crore. Nothing turns positive at 1x anyway.
- **The band exit** (sell once the stock is 1.25 strikes from the held strike) ended 32% of final trades. Removing it
  for the swing rules (holding on, priced by Black-Scholes beyond the band) makes them WORSE:
  - `dc20-h5` -Rs 35,036 -> -Rs 2.37 lakh;
  - `mom10-h5` -Rs 1.95 lakh -> -Rs 3.67 lakh;
  - `rs-d-l3-h3` +Rs 9,014 -> -Rs 31,476.

  So the band exit is not hiding winners. On the 1-ITM versions it ends about half the trades almost at once, because
  a 1-ITM strike is already one step from ATM (data limitation; noted).
- **Physical settlement:** trading also on the last 2 days before expiry and on expiry day (context only) loses more:
  final -Rs 84.7 lakh on 24,827 trades vs -Rs 76.4 lakh on 21,519; design -Rs 1.83 crore vs -Rs 1.54 crore.
- **Stale and model exit prices** are rare:
  - The median rule has 4% of its final exits priced from a stale print or the model.
  - The worst is `rs-d-l1-h3`: 6 stale and 4 model exits of 51 (20%).
  - Re-pricing those exits at intrinsic value changes no verdict: no rule's final net turns positive
    (`report.md`, "final intrinsic").

### Option or stock: which is the better way to trade these signals?

Neither makes money. For the same signal and the same minutes:
- **Rupees per trade:** the option usually loses fewer rupees than the stock (final: -Rs 355 vs -Rs 482 a trade). An
  ATM option moves about half as much as the stock, and its loss is capped at the premium.
- **Capital:** the option needs about Rs 14,000 a lot. The stock needs about Rs 1.3 lakh of MIS margin, or Rs 6-7 lakh
  for delivery (median, final).
- **Return on capital:** the option's -Rs 355 on Rs 14,000 is -2.5% of the capital a trade. The stock's -Rs 482 on
  Rs 1.3 lakh is -0.4%.
- **Costs:** the option pays far more relative to what it can earn. The spread is 0.4-0.8% of the premium a side,
  against about 0.01% of the price for the stock.
- In design, the gap family made money in cash (+Rs 3.7 lakh, 765 trades) but not in options. In the final test it
  lost in both.

## What this means

- Moving from index options to stock options does not rescue option buying. The top-10 stocks' monthly ATM options,
  bought on the obvious stock signals, lose about the cost of the trade, as the index studies did.
- Nothing goes to paper, and no app change follows.
- If Boss wants to continue with stocks, the data says the cheaper lever is the signal, not the instrument. It would
  need a stock signal that makes money in cash first: here none did in both periods.
- Expanding to the top 50 would need the lake's stock-option holes (HDFCBANK, TCS, BSE, BAJFINANCE, ...) filled first.

## Checks run

- `python -m pytest research/hunt/r58 -q`: 62 passed. The tests cover:
  - a look-ahead guard for each of the 38 rules;
  - held-contract pricing; the expiry cut-off; lot by date; holidays; missing minutes; no data;
  - stray rows; ex-dates and bad days; charges against `obuy.costs`; the exit walk; the final-test lock.
- `python -m pytest research/brain/tests -q`: 139 passed, with the pinned `research/brain/requirements.txt`
  (pandas 2.2.3). The machine's own pandas 3.0.5 fails 5 brain tests that R58 does not touch.
- `python -m research.brain.features catalog --check`: up to date.
- `python -m pytest research/data -q`: 133 passed.
- `python research/experiments/validate.py`: 38 R58 entries (`entries/r58-*.yaml`, family `stocks`).
- Raw lake files and caches were deleted after the run.
