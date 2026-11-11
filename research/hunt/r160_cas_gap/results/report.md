# R160: does the closing-auction gap get corrected the next morning?

Written 11 Nov 2026 for Boss's question: "Using [the Closing Auction Session] can we predict the next day opening?" ...
"if there is any issues, market tries to correct itself" ... "prices tries to fill the gap".

## Short answer for Boss

**1. Yes, the auction makes a gap.** Since 3 Aug 2026, the official close often lands away from where futures and
options were really trading in the last minutes.

- NIFTY: on average 12 bps away, about 30 points (53 sessions). Before the auction it was 5 bps, about 12 points (392
  sessions).
- BANKNIFTY: 17 vs 6 bps. SENSEX: 10 vs 5 bps.
- So the gap is about 2.5 times bigger than under the old 15:00-15:30 average close.

**2. Next morning, about half of it gets corrected on average. But not reliably, and it is not new.**

- NIFTY's open moved from the close toward the late level by about half the gap: slope 0.47 over 53 nights. BANKNIFTY
  0.48, SENSEX 0.59. Futures give the same answer (0.47-0.53), and so does dropping the wild first auction day.
- **It is unreliable night to night.**
  - The open was on the late level's side of the close on only 52% of NIFTY nights (46 nights with a gap of 2 bps or
    more).
  - On nights with a gap of 5 bps or more, the open got at least halfway there on 45% of them (40 nights).
  - The reason: the overnight news (an average gap of 24 bps on NIFTY) is twice as big as the auction gap.
- **It is not new.** Before the auction the open also corrected about half of the (smaller) gap between the last
  prices and the old average close: slope 0.53 on NIFTY. The difference between the two periods could be anything from
  -0.91 to +0.95.
- **It does not pass the bar.** The best p-values are 0.02-0.05. After counting the 2,154 earlier tests, as our
  studies must, nothing is close: BH q = 1.0. 53 nights is simply too few to prove an effect this size.

**3. It is not tradable for an option buyer.**

- **The correction is already in the option's price.** An option bought at 15:3x is priced off where futures and options
  trade (the late level), not off the official close. The part a buyer could earn is the move from that late level to
  the next open.
- **That move went the wrong way.** The open gave back about half of the late move (slope -0.53 on NIFTY, 53 nights).
  It was not significant (p 0.05 before counting earlier tests).
- **The trade, tested anyway (30 NIFTY trades, after real charges and slippage):**
  - The rule: buy the ATM option toward the correction at 15:28 and sell at 09:16.
  - It made Rs -32 a trade (Rs -967 in total) and won 47% of the time. Its worst night was Rs -5,068 and its worst
    drawdown Rs -24,363.
  - At 1.5 times the spread: Rs -44 a trade.
  - A random side lost more, but the gap to random is not significant (p 0.18).
  - The opposite side (fading) lost about Rs 917 a trade.
  - For comparison, R146's Night arm made Rs +480 a trade (84 NIFTY trades).

**4. Expiry days:**

- **The expiring options trade toward where the auction close will land, not toward the "fair" level.** On 15 Sep the
  expiring options sat within 2 bps of the close, while futures were 35 bps away. That fits expiry settlement being tied
  to the auction close in this period.
- **Expiry days are no better for a gap trade.** Their correction slope was 0.08 from options over 11 expiries, which is
  nothing.

**Verdict: interesting market fact, no trade.**

- The auction does leave the close off the market, and the open tends to drift halfway back. But the drift is already
  priced into options before the bell, it is weak night to night, and it existed before the auction too.
- **Nothing changes in the app.**
- **Rules change on 7 Dec 2026.** The auction moves to 15:30-15:40, and expiry settles on the 15:00-15:30 average again.
  This exact effect may then change shape.
- If Boss wants, it can be re-checked once we have 60+ nights after 7 Dec *with* the 15:30-15:39 bars. Today's lake
  drops them (see "Data").

## What was tested, and how

- **Pre-registration:** `research/hunt/r160_cas_gap/PREREG.md`, commit `3537952`, pushed before any data was processed.
  Its sha256 is in `results.json`. Deviations and post-hoc notes are in `NOTES.md` (A1-A2 came before the result; A3-A6
  are reporting only).
- **Code:** `cfg.py`, `measure.py`, `stats.py`, `trade.py`, `study.py` and `make_entries.py`. There are 27 tests,
  including the look-ahead guard (`tests/test_r160.py`).
- **Results:**
  - `results.json`: every number;
  - `nights.csv`: per-night measures in bps, no prices;
  - `posthoc.json`: sensitivity checks, information only;
  - `POST_READ.flag`: the post-CAS period was read once.
- **Experiment entries:** `research/experiments/entries/r160-*.yaml`, 11 of them:
  - 10 tests, all rejected;
  - 1 descriptive entry.
- **Signal source:** the index, read through its derivatives.
  - The option-implied index comes from put-call parity: strike + call - put, the median over ATM-2..+2.
  - Index futures are the check.
  - Option prices are used only as a measure of the index (context, as CLAUDE.md allows). No rule reads an option's own
    chart for direction.
- **Numbering:** R155-R159 are taken on other branches, so this is R160 on `claude/r160-cas-gap`, not R156.

### Measures (bps)

- **Late level L:** the median option-implied index over 15:20-15:27, minus that day's 14:30-14:59 basis (implied minus
  index, while the index still prints).
  - Post-CAS, 15:20-15:27 is inside the frozen print and before any closing print.
- **C:** the official close. **O:** the next official open.
- **D = ln(L/C):** the auction-made gap. **G = ln(O/C):** the overnight gap.
- **R = ln(O/L) = G - D:** what a buyer at the late level gets, before option costs.
- **M = ln(L / index at 15:14):** the late move during the freeze.

### Periods

| | entry days | nights (NIFTY / BANKNIFTY / SENSEX) |
|---|---|---|
| POST (closing auction) | 3 Aug - 16 Oct 2026 | 53 / 53 / 53 |
| PRE (old 15:00-15:30 VWAP close, control) | 1 Jan 2025 - 31 Jul 2026 | 392 / 392 / 389 |

SENSEX lost 3 PRE days to short option windows. No POST day was dropped.

## Results

### How big is the auction-made gap? (|D|, bps)

| index | POST mean | POST median | POST 90th pct | PRE mean | PRE median | mean overnight gap POST / PRE |
|---|---|---|---|---|---|---|
| NIFTY | 12.4 | 9.5 | 25.3 | 4.8 | 2.8 | 24.1 / 37.8 |
| BANKNIFTY | 16.9 | 11.9 | 25.4 | 6.1 | 4.5 | 27.4 / 41.2 |
| SENSEX | 10.4 | 8.7 | 21.1 | 4.6 | 3.0 | 27.6 / 38.3 |

- Futures give the same size on POST: 14.4 / 16.6 / 14.6 bps.
- Futures and options agree closely on non-expiry days (correlation of D 0.99 NIFTY, 1.00 BANKNIFTY, 0.87 SENSEX).
- The largest NIFTY gap was on the auction's first day, 3 Aug 2026: -77 bps.

### H1 (primary): does the next open move back toward the late level? (slope of G on D)

| index | period | nights | slope | r | Spearman | perm p (one-sided) | hit (sign G = sign D) | BH q R160 / all (m = 2,164) |
|---|---|---|---|---|---|---|---|---|
| **NIFTY** | **POST** | 53 | **0.47** | 0.24 | 0.21 | 0.045 | 52% of 46 | 0.09 / **1.0** |
| NIFTY | PRE | 392 | 0.53 | 0.06 | 0.05 | 0.11 | 53% of 237 | |
| BANKNIFTY | POST | 53 | 0.48 | 0.32 | 0.31 | 0.019 | 59% of 51 | 0.07 / 1.0 |
| BANKNIFTY | PRE | 392 | 0.60 | 0.07 | 0.09 | 0.079 | 53% of 289 | |
| SENSEX | POST | 53 | 0.59 | 0.22 | 0.21 | 0.054 | 51% of 45 | 0.09 / 1.0 |
| SENSEX | PRE | 389 | 0.80 | 0.08 | 0.11 | 0.052 | 58% of 249 | |

**POST minus PRE slope, 95% bootstrap interval:**
- NIFTY [-0.91, +0.95];
- BANKNIFTY [-1.03, +0.86];
- SENSEX [-1.48, +1.19].

The auction did not change the share corrected. It made the gap bigger, so the same half-correction became easier to
see (r 0.24-0.32 vs 0.06-0.08).

**Robustness:**

| check | NIFTY | BANKNIFTY | SENSEX |
|---|---|---|---|
| futures-implied L (POST) | 0.50 (p 0.021) | 0.47 (p 0.022) | 0.53 (p 0.019) |
| index at 09:20 instead of the open (POST) | 0.75 (p 0.010) | 0.76 (p 0.005) | 0.98 (p 0.009) |
| index at 09:20 (PRE) | 0.93 (p 0.016) | 0.97 (p 0.013) | 1.06 (p 0.015) |
| without 3 Aug (post-hoc, options / futures) | 0.53 / 0.56 | 0.55 / 0.53 | 0.56 / 0.54 |

- By 09:20 most of the late level is reached, in both periods. This is the normal "the newest price is the better
  price" effect.
- **Plain shares, NIFTY nights with |D| of 5 bps or more:**

  | | POST (40 nights) | PRE (132 nights) |
  |---|---|---|
  | open at least halfway to L | 45% | 49% |
  | open beyond L | 35% | 44% |
  | median open / gap ratio | 0.27 | 0.38 |

**H1 verdict (PREREG 5):** not supported.

| condition | needed | NIFTY |
|---|---|---|
| slope | >= 0.25 | 0.47, passes |
| hit-rate | >= 55% | 52%, fails |
| BH q, earlier tests counted | <= 0.10 | 1.0, fails |

### H2: is the move from the late level to the open predictable? (slope of R on D)

This is H1's slope minus 1 (NOTES A3): it asks whether the open lands on the late level.

| index | POST slope | r | perm p (two-sided) | PRE slope | futures POST |
|---|---|---|---|---|---|
| NIFTY | -0.53 | -0.27 | 0.054 | -0.47 | -0.50 (p 0.033) |
| BANKNIFTY | -0.52 | -0.34 | 0.021 | -0.40 | -0.53 (p 0.018) |
| SENSEX | -0.41 | -0.16 | 0.26 | -0.20 | -0.47 (p 0.064) |

- The open falls back toward the official close by about half the gap, measured from the late price.
- A buyer at 15:3x pays the late price, so buying "toward the correction" is buying the half that has already happened.
- BH q with m = 2,164 is 1.0 for all three.

### Secondary (a): the late move during the frozen print (M)

| index | R on M, POST slope (p) | G on M, POST slope (p) | R on M, PRE slope (p) |
|---|---|---|---|
| NIFTY | -0.59 (0.21) | -0.62 (0.19) | -0.16 (0.65) |
| BANKNIFTY | -0.89 (0.26) | -0.89 (0.26) | -0.23 (0.50) |
| SENSEX | -1.26 (**0.004**) | -1.33 (0.003) | +0.20 (0.58) |

- SENSEX's late move tended to reverse by the open (BH q 0.04 within R160). With earlier tests counted, q is 1.0.
- NIFTY and BANKNIFTY have the same sign but are weak. Note only.

### Secondary (b): expiry days (descriptive)

- **NIFTY, 11 weekly expiries:**
  - Mean |D| was 13.6 bps from the expiring options and 21.0 from futures. Other days: 12.1 and 12.7.
  - On expiry days the two measures correlate only 0.59, against 0.99 on other days. On 15 Sep D was +1.6 bps from
    options and +34.7 from futures.
  - So the expiring options sat near where the auction close would land.
  - H1 slope on expiry days: 0.08 from options. From futures it was 0.54 (11 nights, r 0.41). Too few nights to say more.
- **SENSEX, 11 weekly expiries:** mean |D| 15.7 bps (options) / 23.5 (futures), against 9.1 / 12.3 on other days.
- **BANKNIFTY:** only 2 monthly expiries.

### Trade T1 (PREREG 6): NIFTY, buy toward the correction

| trades | calls / puts | Rs a trade | win | total | worst night | max drawdown | Rs a trade at 1.5x spread | random-side twin p |
|---|---|---|---|---|---|---|---|---|
| 30 | 13 / 17 | **-32** | 47% | -967 | -5,068 | -24,363 | -44 | 0.18 |

- **The rule:**
  - nights with |D| of 5 bps or more, not an expiry day;
  - the weekly ATM option, 1 lot (65), bought at the 15:28 open and sold at the 09:16 open;
  - app fills (+-5 bps), dated charges (Rs 20 an order, STT 0.15%, exchange, SEBI, stamp, GST) and a 0.16% half-spread on
    each leg.
- **Dropped:** 2 nights, by the stray-print guard.
- **Holding a call and a put together lost Rs 949 a night.** That is the overnight carry: time decay plus spread and
  costs.
  - T1 lost less than a random side (the twins' mean total was Rs -15,002).
  - The opposite side (fading) lost about Rs 917 a trade.
- **The entry is a proxy.**
  - Before 15:30 the official close is not known for sure: the auction shows an indicative price, and the final close
    comes after 15:30.
  - A real trade would enter at 15:30-15:39, and those bars are not in our data.
- **Gate:**

  | condition | result |
  |---|---|
  | H1 supported | no |
  | H2 > 0 | no |
  | net > 0 at 1.5x spread | no |
  | twin p <= 0.05 | no |
  | at least 20 trades | yes |

  **Failed.**
- **Against R146's BTST results:** Night (R3), NIFTY holdout, made Rs +480 a trade over 84 trades. R146's "always buy
  the call" made Rs -405, and R160's T1 made Rs -32. Nothing here beats the Night arm.

### Multiple testing

- R160's family has 10 tests: H1, H2 and (a) x 3 indices, plus the T1 twin.
- **Within R160:** the lowest q is 0.04 (SENSEX (a)); H1 has 0.07-0.09.
- **Counting the 2,154 earlier tests at p = 1, as R154 did** (m = 2,164): every q is 1.0.
- With 53 nights, clearing that bar needs a correlation of about 0.5. The observed values are 0.2-0.3.

## Rules in force (checked 11 Nov 2026)

**From 3 Aug 2026 (the period tested):**

- **Continuous trading.** Stocks with derivatives trade continuously to 15:15. Then a closing auction runs about
  15:15-15:35:
  - 15:15-15:20: transition and reference price (the 15:00-15:15 VWAP);
  - about 15:20-15:30: order entry;
  - then matching.
- **The cash close.** The auction's equilibrium price sets the close of those stocks
  ([NSE circular 38/2026, 18 Mar 2026](https://nsearchives.nseindia.com/content/circulars/CMTR73362.pdf), read in
  full).
  - The index close is built from those stock closes. That is why our index print stands still from 15:15 to 15:28 and
    jumps at 15:29.
- **Derivatives keep their own closing method** (same NSE circular, 5.2.1).
  - F&O trades to 15:40 ([Groww](https://groww.in/blog/nse-extends-f-and-o-trading-hours-by-10-minutes-new-timings-effective-from-august-3-2026),
    [HDFC Sky](https://hdfcsky.com/news/nse-to-extend-equity-derivatives-trading-by-10-minutes-from-august-3-with-launch-of-closing-auction-session)).
  - The F&O daily-close VWAP window moved to 15:10-15:40, as secondary sources report
    ([Outlook Money](https://www.outlookmoney.com/invest/nse-extended-fo-timings-and-new-closing-auction-session-rules-explained)).
- **Expiry settlement.** Secondary summaries say SEBI's 16 Jan 2026 circular tied it to the CAS closing price
  ([CorpLaw Updates](https://www.corplawupdates.in/updates/sebi-closing-auction-session-final-framework-cas-october-2026-2)).
  - Our data fits this: on expiry days the expiring options tracked the coming close, not the futures (section (b)).
  - SEBI consulted on 12 Sep 2026, after a jolt in the auction on 3 Sep (R8's notes).

**From 7 Dec 2026** ([SEBI circular HO/47/11/11(3)2025-MRD-POD2/I/23843/2026, 15 Oct 2026](https://www.sebi.gov.in/legal/circulars/oct-2026/review-of-certain-aspects-of-the-closing-auction-session-market-timings-and-settlement-methodology-for-derivatives-contracts_105293.html);
NSE circulars of 6 Nov 2026 as summarised by [Kotak Neo](https://www.kotakneo.com/news/regulations/nse-new-closing-auction-and-f-and-o-trading-rules-from-7-december/);
[TaxGuru](https://taxguru.in/sebi/sebi-revises-closing-auction-timings-derivatives-settlement-rules-december-2026.html),
[ThePrint](https://theprint.in/economy/sebi-revises-closing-auction-session-framework-changes-derivatives-settlement-norms/3073213/)):

- **The auction:** a 10-minute closing auction, 15:30-15:40, with a random close in the last two minutes. Continuous cash
  trading runs to 15:30.
- **Expiry settlement:** expiring index derivatives settle on the index computed from the constituents' 15:00-15:30
  VWAP. That is no longer the auction close.
- **Trading hours:**
  - Expiring index contracts stop at 15:30.
  - Other index and stock derivatives trade to 15:45.
  - The F&O daily-close VWAP window becomes 15:15-15:45.
- **The 7 Dec details come from summaries.** The SEBI page lists the circular, but its text could not be read from this
  session.

**So after 7 Dec:**
- The index close will come from an auction that ends at 15:40, while index futures and options trade on to 15:45.
- The expiry-day link between settlement and the auction (section (b)) goes away.
- Any forward log must restart its count on 7 Dec.

## Data: what we could and could not get

- **Used:** the Drive data lake (`gdrive:IraAlgo-data-lake`), built 13-20 Oct 2026, ending **19 Oct 2026**:
  - 1-minute index bars, daily OHLC, index futures, and nearest-expiry option chains (ATM±10) for NIFTY, BANKNIFTY and
    SENSEX;
  - sha256 in PREREG section 2.
  - rclone was installed in the session's scratchpad to read it.
- **Missing: anything after 19 Oct 2026.** Dhan's access token is expired (HTTP 401, DH-901, tried 11 Nov 2026), so no
  top-up was possible. About 16 more sessions (20 Oct - 10 Nov) exist in the market but not in our lake.
- **Missing: the 15:30-15:39 bars.** The lake build keeps only 09:15-15:29, so every option and futures file stops at the
  15:29 bar, although F&O trades to 15:40.
  - The study therefore measures the late level at 15:20-15:27 (inside the frozen print) and prices the trade at 15:28.
  - Fix for the future: keep bars to 15:40 in `lake_build.py` (to 15:45 after 7 Dec) and refetch with a fresh Dhan
    token.
- **Missing: futures before late July 2026.** The lake holds index futures only for contracts listed on 19 Oct (Oct,
  Nov and Dec 2026 expiries). Expired contracts are not served. Futures therefore check POST only, and PRE uses
  options alone.
- **Nothing was invented.** Days with too few option prices are dropped and counted: 3 SENSEX PRE days, none in POST.

## Small sample, honestly

- **The size of the test.** 53 nights and 11 weekly expiries.
- **What it can detect.** At that size, only a correlation of about 0.23 reaches p 0.05 on its own, and about 0.5
  survives the earlier-test count.
- **What a miss means.** Whatever exists here is "not shown", not "proved absent".
- **The trade has no room to work.** The half-correction is real in the averages, but an option buyer would capture it
  only by being early, and our measure says the price already carries it.

## What next (PREREG 9)

H1 was not supported, so no forward log starts. One re-check is proposed instead:

1. **Fix the lake** to keep the 15:30-15:45 bars, and refetch with a fresh Dhan token.
2. **After 60 or more sessions from 7 Dec 2026** (the new rules), re-run `study.py` unchanged with new POST dates.
   - Use the 15:30-15:39 window for L if the bars exist (a new pre-registration amendment).
   - Keep the same bar.
3. **Until then, nothing changes:** no app change, no paper or live use.
