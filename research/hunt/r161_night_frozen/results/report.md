# R161: does the frozen index print change Night (R3)'s 15:20 decision?

Written 11 Nov 2026, the follow-up to R160 that Boss asked for.

## Short answer for Boss

**No change needed. The frozen print hardly affects Night.**

- **What's frozen.** Since 3 Aug, NIFTY and BANKNIFTY stand still from 15:15. So Night's "15:19 close" is really the
  15:14 value.
- **How far it is from the live market.** Compared with where futures and options really traded at 15:19 (53 nights):
  - NIFTY: 4.0 bps on average, about 10 points;
  - BANKNIFTY: 4.4 bps.
- **That is no bigger than before the auction.** Our live measure itself wobbles by about 4.8 bps (392 nights when
  nothing was frozen). The market rarely moves far between 15:14 and 15:19.
- **How often Night's index condition would change** (close location 0.6 / 0.4 plus the day's direction):

  | index | nights it would change since 3 Aug | before the auction (noise) |
  |---|---|---|
  | NIFTY | 8 of 53 (15%) | 8% |
  | BANKNIFTY | 3 of 53 (6%) | 5% |

  NIFTY is a little higher. But with the frozen and live readings no further apart than before, this is not clearly
  the freeze.
- **What those changes were worth (an upper bound).** Breadth and OI are not applied here, so most of these nights
  would not have been Night trades anyway.
  - The extra trades the live reading would have added lost Rs 7,851 over 6 NIFTY nights, and Rs 114 over 3
    BANKNIFTY nights.
  - The frozen reading's own extra trade made Rs +344 on NIFTY and Rs -1,075 on BANKNIFTY.
  - So reading live would not have helped.
- **The strike** differed on about 23-25% of nights, the same as before the auction (24%). That is the same noise.
- **The pre-registered bar** was: flips at least 5 points above before the auction, AND the frozen-vs-live gap at least
  twice as big.
  - NIFTY: +6.9 points, but the gap ratio was only 0.84.
  - BANKNIFTY: +0.3 points, ratio 0.93.
  - **Not met. No shadow change recommended.**

**Caveats:**
- **Breadth is stale too.** Night's breadth reads stock prices that are also in the auction from 15:15, and the
  stocks' minute data needed to check it after 13 Oct is missing.
- **The app reads Zerodha, not Dhan.** If Zerodha's index feed shows a moving "indicative" value during the auction
  (one source says it does), the app sees something different from our data again. The phone's own recordings could
  confirm which.

## Method

- **Pre-registration:** `research/hunt/r161_night_frozen/PREREG.md`, commit `0d338db`, pushed before the run. Its
  sha256 is in `results.json`.
- **A, the app's reading:** the index's 15:19 bar close.
- **B, the live reading:** the median option-implied index over 15:17-15:19 (put-call parity over ATM±2) minus the
  day's 14:30-14:59 basis.
  - Futures give the same answer: NIFTY flip rate 17%, BANKNIFTY 9%.
  - B's day range is the high and low to 15:14, widened to include B.
- **Signal source:** the index, read through its derivatives for B. No option chart is used for direction.
- **Flips were priced on Night's contract:** the 1-ITM option, bought at the 15:20 open and sold at the 09:16 open.
  - App fills, dated charges and a 0.16% half-spread.
  - No expiry-day nights.
- **Data:** R160's lake files, ending 19 Oct 2026.
- **Code and tests:** `study.py`, with 9 tests including the look-ahead guard (nothing after 15:19 is read).

| | NIFTY POST | NIFTY PRE | BANKNIFTY POST | BANKNIFTY PRE |
|---|---|---|---|---|
| nights | 53 | 392 | 53 | 392 |
| mean \|A - B\| bps (90th pct) | 4.0 (8.0) | 4.8 (10.3) | 4.4 (7.7) | 4.8 (10.5) |
| index condition differs | 15.1% (A only 1, B only 7) | 8.2% (19 / 13) | 5.7% (1 / 2) | 5.4% (9 / 12) |
| ATM strike differs | 22.6% | 24.0% | 24.5% | 23.2% |
| nights A signals | 35 | 280 | 31 | 286 |

Per-flip detail is in `results.json` (`post_flips_priced`), and per-night flags in `nights.csv`.
