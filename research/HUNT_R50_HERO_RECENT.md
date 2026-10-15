# R50: every cancelled hero / zero-to-hero rule, on the recent market only (20 Nov 2024 - 13 Oct 2026)

Written 15 Oct 2026, for Boss: "add the Nov 2024 onwards view. Don't test any data before that, and now find a
hero-zero, ORB, and all the strategies which we have cancelled". This is the hero part: every expiry-day hero rule that
R21-R43 cancelled (0 of 170 passed), re-tested on the market since NSE's weekly-expiry change only.

- **Pre-registration:** `research/hunt/r50/PREREG.md`, commit `97d5c922`, pushed before any P&L. Amendments 1-2 (R35's
  heavyweight check kept; the expiry test skips sessions with no 15:15 price) were written before any P&L and pushed
  with the design results (`98f6b301`), before the final test was read.
- **Code, tests and tables:** `research/hunt/r50/` (`results/report.md` has every table, `design.json`, `final.json`,
  `expiry_days.json`). Trade lists and day files stay on the cache, not in git.
- **Experiment entries:** `research/experiments/entries/r50-*.yaml` (8 families, 20 originals + 34 re-tune cells, all
  rejected).
- **Research only:** nothing in the app changes.

## Verdict for Boss

**No hero rule comes back. 0 of 28 pass** (20 original rules + 8 re-tunes, one BH family; smallest q 0.056).

- **Most rules still lose on the recent market.** In design (20 Nov 2024 - 31 Mar 2026, 193 index-expiry-days) 17 of
  the 20 originals lost money; together they lost Rs 8.85 lakh on 2,209 trades. The 3 that made money are the OI-wall
  rules, with 4 trades each (+Rs 577 to +Rs 4,924). The closest of the rest was T1 (-Rs 227 on 125 trades).
- **The final test (1 Apr - 13 Oct 2026, 73 index-expiry-days, read once) looks better, but it is one trade.**
  - 9 of the 20 originals made money. Four of them made only +Rs 28 to +Rs 387 (the OI-wall rules on 1 trade, K1).
  - The five big ones all hold the same lottery ticket: the **SENSEX 72,500 / 72,400 call on 2 Apr 2026**, bought
  at about Rs 22-25 around 12:30 and sold at 26-37x (SENSEX rallied about 1,450 points into the close).
  - EMA9 x 21 + ADX 20 (R43): +Rs 1,39,236 on 26 trades. The 2 Apr trade alone was +Rs 1,40,013 (101% of the net).
    Without it: -Rs 777; without the top 5: -Rs 51,273.
  - EMA8 x 21 + ADX 15: +Rs 1,21,605 (36 trades); -Rs 73,087 without the top 5.
  - EMA9 x 26 + ADX 20: +Rs 1,24,611 (23 trades); -Rs 45,957 without the top 5.
  - RSI momentum 55/45: +Rs 95,390 (64 trades); -Rs 89,287 without the top 5.
  - The no-signal 12:30 basket (CE and PE, Rs 5-30, R43's baseline): +Rs 1,26,738 (146 trades, p 0.004, q 0.056); the
    2 Apr call was +Rs 1,75,419. Without the top 5: -Rs 1,58,833. It lost Rs 1,17,686 in design.
  - None passes.
    - The EMA rules have too few final trades (23-36; the gate is 40).
    - The EMA and RSI rules do not beat random timing after BH (p 0.05-0.07, q 0.25).
    - The 12:30 basket does beat random afternoon minutes (q 0.056). It still fails, because it loses without its
      top 5 trades and it lost in design.
    - All five are negative without their top 5 trades, and all five lost in design.
- **Same story as R43.** R43's holdout (2025-26) found this very trade (+Rs 1,40,013). A rule whose whole profit is one
  jackpot cannot be told apart from luck, and the next jackpot may not come in the window it is watching.
- **The lottery baskets (Rs 1-5 tickets) still lose about half of every rupee.** At 13:30, -Rs 1,03,789 in design (283
  tickets) and -Rs 28,693 in the final test (121 tickets): -Rs 525 per Rs 1,000 staked. The best re-tune (12:30 with
  Boss's 25% trail) cut the loss to -Rs 21,197 in design and made +Rs 4,072 in the final test (115 tickets). It beats
  random times (p 0.002, q 0.056), but that profit is 398% its top 5 trades and it lost in every design block but one.
- **The app's Hero arm (HeroRules.kt)** fired 12 times in design (-Rs 26,417) and twice in the final test (-Rs 3,776). It
  did not fire on 13 Oct 2026 (as R21 found). Too few trades to judge, and nothing says it is worth more trades.
- **13 Oct 2026 (Boss's day)** is in the final test. The 13:30 Rs 1-5 basket caught the 22,700 CE at Rs 1.80 (it peaked
  27x; held to 15:05 it banked 11.9x, +Rs 5,022). RSI momentum bought the 22,600 CE at Rs 14.51 (+Rs 25,937). The same
  rules lost on most other days.
- **Nothing goes to paper.** Every hero rule stays off, as before (exact settings in "What stays off" below).

## What was tested

- **Window:** expiry days from 20 Nov 2024 to 13 Oct 2026 only. Nothing earlier is traded or reported. 2024 files were
  read only to warm up the GEX scale and the 5-minute index indicators.
- **Split (fixed in PREREG):**
  - DESIGN = expiry days 20 Nov 2024 - 31 Mar 2026, with walk-forward blocks B0 (to Mar 2025) and B1-B4 (each quarter).
    A re-tune picks its cell on the earlier blocks and trades the next one.
  - FINAL TEST = 1 Apr - 13 Oct 2026, read once.
  - **Honest note:** R43 (holdout 2025-26), R44 and R45 already looked at parts of 2025-26, so this final test is not
    pristine. Only live paper from 15 Oct 2026 is truly unseen.
- **Expiry days used** (from the data, R25's rule: ATM time value at 15:15 < 0.6% of spot and the next session's more
  than twice it; NIFTY's 13 Oct 2026 is the lake's last session and counts by its own time value; 266 index-days):

| index | expiry type | design | final test |
|---|---|---|---|
| NIFTY | weekly (Thursday to Aug 2025, Tuesday from Sep 2025; holiday shifts) | 72 | 28 |
| SENSEX | weekly (as BSE moved it) | 70 | 27 |
| BANKNIFTY | monthly only | 17 | 6 |
| FINNIFTY | monthly only | 17 | 6 |
| MIDCPNIFTY | monthly only | 17 | 6 |

- **NIFTY** (100): 24-11-21, 24-11-28, 24-12-05, 24-12-12, 24-12-19, 24-12-26, 25-01-02, 25-01-09, 25-01-16, 25-01-23, 25-01-30, 25-02-06, 25-02-13, 25-02-20, 25-02-27, 25-03-06, 25-03-13, 25-03-20, 25-03-27, 25-04-03, 25-04-09, 25-04-17, 25-04-24, 25-04-30, 25-05-08, 25-05-15, 25-05-22, 25-05-29, 25-06-05, 25-06-12, 25-06-19, 25-06-26, 25-07-03, 25-07-10, 25-07-17, 25-07-24, 25-07-31, 25-08-07, 25-08-14, 25-08-21, 25-08-28, 25-09-02, 25-09-09, 25-09-16, 25-09-23, 25-09-30, 25-10-07, 25-10-14, 25-10-20, 25-10-28, 25-11-04, 25-11-11, 25-11-18, 25-11-25, 25-12-02, 25-12-09, 25-12-16, 25-12-23, 25-12-30, 26-01-06, 26-01-13, 26-01-20, 26-01-27, 26-02-03, 26-02-10, 26-02-17, 26-02-24, 26-03-02, 26-03-10, 26-03-17, 26-03-24, 26-03-30, 26-04-07, 26-04-13, 26-04-21, 26-04-28, 26-05-05, 26-05-12, 26-05-19, 26-05-26, 26-06-02, 26-06-09, 26-06-16, 26-06-23, 26-06-30, 26-07-07, 26-07-14, 26-07-21, 26-07-28, 26-08-04, 26-08-11, 26-08-18, 26-08-25, 26-09-01, 26-09-08, 26-09-15, 26-09-22, 26-09-29, 26-10-06, 26-10-13
- **BANKNIFTY** (23): 24-11-27, 24-12-24, 25-01-30, 25-02-27, 25-03-27, 25-04-24, 25-05-29, 25-06-26, 25-07-31, 25-08-28, 25-09-30, 25-10-28, 25-11-25, 25-12-30, 26-01-27, 26-02-24, 26-03-30, 26-04-28, 26-05-26, 26-06-30, 26-07-28, 26-08-25, 26-09-29
- **FINNIFTY** (23): 24-11-26, 24-12-31, 25-01-30, 25-02-27, 25-03-27, 25-04-24, 25-05-29, 25-06-26, 25-07-31, 25-08-28, 25-09-30, 25-10-28, 25-11-25, 25-12-30, 26-01-27, 26-02-24, 26-03-30, 26-04-28, 26-05-26, 26-06-30, 26-07-28, 26-08-25, 26-09-29
- **MIDCPNIFTY** (23): 24-11-25, 24-12-30, 25-01-30, 25-02-27, 25-03-27, 25-04-24, 25-05-29, 25-06-26, 25-07-31, 25-08-28, 25-09-30, 25-10-28, 25-11-25, 25-12-30, 26-01-27, 26-02-24, 26-03-30, 26-04-28, 26-05-26, 26-06-30, 26-07-28, 26-08-25, 26-09-29
- **SENSEX** (97): 24-11-22, 24-11-29, 24-12-06, 24-12-13, 24-12-20, 24-12-27, 25-01-03, 25-01-07, 25-01-14, 25-01-21, 25-01-28, 25-02-04, 25-02-11, 25-02-18, 25-02-25, 25-03-04, 25-03-11, 25-03-18, 25-03-25, 25-04-01, 25-04-08, 25-04-15, 25-04-22, 25-04-29, 25-05-06, 25-05-13, 25-05-20, 25-05-27, 25-06-03, 25-06-10, 25-06-17, 25-06-24, 25-07-01, 25-07-08, 25-07-15, 25-07-22, 25-07-29, 25-08-05, 25-08-12, 25-08-19, 25-08-26, 25-09-04, 25-09-11, 25-09-18, 25-10-01, 25-10-09, 25-10-16, 25-10-23, 25-10-30, 25-11-06, 25-11-13, 25-11-20, 25-11-27, 25-12-04, 25-12-11, 25-12-18, 25-12-24, 26-01-01, 26-01-08, 26-01-14, 26-01-22, 26-01-29, 26-02-05, 26-02-12, 26-02-19, 26-02-26, 26-03-05, 26-03-12, 26-03-19, 26-03-25, 26-04-02, 26-04-09, 26-04-16, 26-04-23, 26-04-30, 26-05-07, 26-05-14, 26-05-21, 26-05-27, 26-06-04, 26-06-11, 26-06-18, 26-06-25, 26-07-02, 26-07-09, 26-07-16, 26-07-23, 26-07-30, 26-08-13, 26-08-20, 26-08-27, 26-09-03, 26-09-10, 26-09-17, 26-09-24, 26-10-01, 26-10-08

- **Measurement (never relaxed):**
  - Every trade is priced on ONE held contract: the strike bought, rebuilt from every offset of the lake rows, never the
    rolling ATM series (R45).
  - Fills: the 3 minutes after the signal minute (next-minute fills), capped at ref x 1.2 + 0.10, never chasing.
  - Spread and slippage: 5 bps plus 1 / 2 / 4 ticks a side by the contract's 5-minute volume. That is wide for cheap
    expiry options: one tick is 1-5% of a Rs 1-5 option. A 2x-spread stress is reported for every rule.
  - Exits triggered by a minute close sell at the next minute's open, so a gap fills at the worse price. Targets need a
    confirmed price.
  - Charges in force on each trade date (`obuy Costs("dated")`: STT 0.1% to 31 Mar 2026, 0.15% after; BSE rates for
    SENSEX). A worthless option pays no sell charges.
  - Lots in force on each date. The repo's lot table lacked the Jun 2025 and Dec 2025 revisions (BANKNIFTY 35 then 30,
    MIDCPNIFTY 140 then 120); they are added in `r50cfg.LOTS`, matching the app's current lots.
  - For BANKNIFTY / FINNIFTY / MIDCPNIFTY only the WEEK file is read (it is the same contracts as MONTH in this window).
- **Statistics:** 500 random-entry twins per rule (same day, side, strike rule, budget and exit, at a random minute of
  the rule's window) give p. BH over all 28 final tests gives q.
- **Gates (all needed):**
  - final-test net > 0;
  - p < 0.05 and q <= 0.10;
  - 40+ final-test trades (else "too few trades to judge");
  - positive without the top 5 trades;
  - design walk-forward net > 0.

## Results by family (OLD = the old study's verdict on its full data; NOW = Nov 2024+ design; then the final test)

Signal source: "index" is the default (signal on the index, buy the option). Rules marked OPTION'S OWN CHART are not
the default kind and could only ever be switched on by Boss. "top-5 share" is the top 5 trades' share of the net (only
when the net is positive). "Max DD" is on the daily P&L.

### app Hero arm

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-H0 (R21 V0 / R25 H0 = HeroRules.kt (NIFTY only)) | index momentum + ATM straddle premium (chain context) | R25: design 33 tr +76,379; holdout 15 tr -24,624 (p 0.99); R21 holdout 2 tr -6,259. Rejected | 12 / -26,417 / -17,095 | 2 | 0% | 0 / 0 | -3,776 | -135 | -3,069 | -3,776 | - | 0.623 | 1.00 | fail (too few trades to judge) |
| O-V4 (R21 V4 / R25 V4) | OPTION'S OWN CHART (non-default) + index momentum | R25: design 153 tr -34,403; holdout 86 tr -30,897 (p 0.89). Rejected | 60 / -54,402 / -20,001 | 22 | 18% | 1 / 0 | -45,066 | -617 | -11,904 | -55,628 | - | 0.994 | 1.00 | fail (too few trades to judge) |
| T-HERO (5-30-app) (R21 V0 / R25 H0 = HeroRules.kt (NIFTY only)) | index momentum + ATM straddle premium (chain context) | new (R50 re-tune) | 32 / +5,300 / +777 | 5 | 20% | 0 / 0 | -11,416 | -156 | -3,305 | -11,416 | - | 0.878 | 1.00 | fail (too few trades to judge) |

### cheap OTM lottery

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-L1 (R25 L1) | none (time only, both sides) | R25: design 724 tr -1,77,392; holdout 253 tr -90,750. Rejected | 283 / -1,03,789 / -75,692 | 121 | 8% | 6 / 2 | -28,693 | -393 | -3,670 | -34,346 | - | 0.106 | 0.33 | fail |
| O-L3 (R25 L3) | none (time only, both sides) | R25: design 732 tr -2,12,662; holdout 257 tr -74,852. Rejected | 281 / -78,129 / -68,247 | 124 | 13% | 9 / 2 | -26,486 | -363 | -3,460 | -27,184 | - | 0.034 | 0.25 | fail |
| O-BASE (R43 base cheap hero hold (12:30)) | none (time only, both sides) | R43: holdout 467 tr +33,777 (p 0.012, q 0.176). Rejected (q, top-5) | 356 / -1,17,686 / -43,531 | 146 | 16% | 12 / 3 | +1,26,738 | +1,736 | -18,877 | -78,215 | 225% | 0.004 | 0.06 | fail |
| T-LOT (1230-tr25) (R25 L1) | none (time only, both sides) | new (R50 re-tune) | 277 / -21,197 / -22,307 | 115 | 13% | 5 / 2 | +4,072 | +56 | -966 | -5,425 | 398% | 0.002 | 0.06 | fail |

### OI-wall short covering

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-P (R26 P) | index break of the OI wall; OI as filter | R26: 15 tr -3,851 (p 1.00). Rejected | 4 / +577 / +576 | 1 | 100% | 0 / 0 | +28 | 0 | +28 | 0 | 100% | 1.000 | 1.00 | fail (too few trades to judge) |
| O-A6 (R26 A6) | index break of the OI wall; OI as filter | R26: 15 tr +10,383 (p 0.80). Rejected | 4 / +4,924 / +4,924 | 1 | 100% | 0 / 0 | +28 | 0 | +28 | 0 | 100% | 1.000 | 1.00 | fail (too few trades to judge) |
| T-OI (E3) (R26 P) | index break of the OI wall; OI as filter | new (R50 re-tune) | 4 / +4,924 / +444 | 1 | 100% | 0 / 0 | +28 | 0 | +28 | 0 | 100% | 1.000 | 1.00 | fail (too few trades to judge) |

### Boss's 1:45-2:30 window

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-B1 (R26 B1) | index | R26: 18 tr -7,572 (p 1.00). Rejected | 4 / +577 / +576 | 1 | 100% | 0 / 0 | +28 | 0 | +28 | 0 | 100% | 1.000 | 1.00 | fail (too few trades to judge) |
| T-BOSS (atm1-E2) (R26 B1) | index | new (R50 re-tune) | 4 / +577 / -3,558 | 1 | 100% | 0 / 0 | +28 | 0 | +28 | 0 | 100% | 1.000 | 1.00 | fail (too few trades to judge) |

### gamma / GEX

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-X1 (R36 X1) | chain (GEX) day filter, no direction | R36: 66 days / 86 legs -32,226 (p 0.53). Rejected | 31 / -13,564 / -10,664 | 30 | 33% | 0 / 0 | -1,838 | -25 | -1,631 | -7,219 | - | 0.246 | 0.69 | fail (too few trades to judge) |
| O-X2 (R36 X2) | chain (GEX) day filter, no direction | R36: 98 days / 153 legs -42,909 (p 0.62). Rejected | 105 / -14,264 / -9,073 | 53 | 21% | 0 / 0 | -20,802 | -285 | -6,121 | -20,802 | - | 0.465 | 1.00 | fail |
| T-GEX (tr25) (R36 X1) | chain (GEX) day filter, no direction | new (R50 re-tune) | 31 / -5,273 / -3,758 | 30 | 13% | 0 / 0 | -5,471 | -75 | -639 | -5,471 | - | 0.980 | 1.00 | fail (too few trades to judge) |

### sweep signals

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-E821 (R43 ema821_adx15 cheap hero hold) | index | R43: design 324 tr +1,23,022 (p 0.17); holdout 119 tr +87,534 (p 0.34, q 0.78); -1,94,680 without top 5. Rejected | 89 / -45,243 / -58,752 | 36 | 14% | 5 / 1 | +1,21,605 | +1,666 | -8,106 | -57,266 | 160% | 0.072 | 0.25 | fail (too few trades to judge) |
| O-E921 (R43 ema921_adx20 cheap hero hold) | index | R43: design 178 tr -29,677; holdout 75 tr +1,37,909 (q 0.71). Rejected | 53 / -11,822 / +18,237 | 26 | 19% | 5 / 1 | +1,39,236 | +1,907 | -5,818 | -37,860 | 137% | 0.050 | 0.25 | fail (too few trades to judge) |
| O-E926 (R43 ema926_adx20 cheap hero hold) | index | R43: design 153 tr -68,554; holdout 62 tr +81,354; -1,25,605 without top 5. Rejected | 40 / -45,566 / -27,303 | 23 | 17% | 5 / 1 | +1,24,611 | +1,707 | -5,944 | -20,806 | 137% | 0.058 | 0.25 | fail (too few trades to judge) |
| O-RSIM (R43 rsi_momo cheap hero hold) | index | R43: design 586 tr -3,97,010; holdout 218 tr +4,546; -2,90,993 without top 5. Rejected | 171 / -86,309 / +18,876 | 64 | 20% | 5 / 1 | +95,390 | +1,307 | -10,624 | -67,055 | 194% | 0.070 | 0.25 | fail |
| O-RSIF (R43 rsi_fade cheap all hold) | index | R43: design 509 tr +52,688; holdout 186 tr -2,15,365. Rejected | 137 / -66,700 / -75,650 | 61 | 7% | 4 / 0 | -1,13,654 | -1,557 | -8,883 | -1,17,554 | - | 0.844 | 1.00 | fail |
| O-DON (R43 donchian20 otm2 all hold) | index | R43: design 662 tr +5,83,105 (p 0.034); holdout 245 tr -67,696 (q 0.81). Rejected | 193 / -18,875 / -21,963 | 73 | 18% | 5 / 0 | -8,163 | -112 | -9,523 | -62,353 | - | 0.539 | 1.00 | fail |
| T-SWEEP (ema921_adx20-s50) (R43 ema821_adx15 cheap hero hold) | index | new (R50 re-tune) | 53 / -11,822 / -82,863 | 26 | 19% | 5 / 1 | +1,39,236 | +1,907 | -5,818 | -37,860 | 137% | 0.050 | 0.25 | fail (too few trades to judge) |

### volume / supertrend

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-S3 (R35 S3) | OPTION'S OWN CHART (non-default) | R35: 163 tr -1,16,661 (p 1.0). Rejected | 79 / -76,698 / -58,008 | 26 | 35% | 0 / 0 | -5,904 | -81 | -2,749 | -8,417 | - | 0.998 | 1.00 | fail (too few trades to judge) |
| O-S5 (R35 S5) | index (+ option volume, heavyweight stocks) | R35: 70 tr -56,251 (p 1.0). Rejected | 26 / -31,898 / -25,957 | 14 | 36% | 0 / 0 | -4,264 | -58 | -1,946 | -7,225 | - | 0.996 | 1.00 | fail (too few trades to judge) |
| T-ST (tr25) (R35 S5) | index (+ option volume, heavyweight stocks) | new (R50 re-tune) | 26 / -15,195 / -12,870 | 14 | 0% | 0 / 0 | -7,340 | -101 | -884 | -7,340 | - | 1.000 | 1.00 | fail (too few trades to judge) |

### afternoon window

| rule (old name) | signal source | OLD verdict | NOW design: trades / net / walk-forward | FINAL trades | win | peaks 5x / 10x | net | per expiry-day | worst day | max DD | top-5 share | p | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| O-K1 (R34 K1e) | index | R34: K1e 194 tr -91,160 (p 0.95). Rejected | 156 / -99,713 / -70,721 | 49 | 31% | 3 / 0 | +387 | +5 | -5,228 | -16,512 | 9153% | 0.994 | 1.00 | fail |
| O-T1 (R25 T1) | index | R25: design 315 tr -8,721; holdout 106 tr +6,530 (p 0.69). Rejected | 125 / -227 / -7,934 | 56 | 11% | 5 / 1 | -3,174 | -43 | -2,227 | -17,688 | - | 0.685 | 1.00 | fail |
| T-AFT (cheap-tr25) (R34 K1e) | index | new (R50 re-tune) | 104 / -23,069 / -19,509 | 46 | 20% | 0 / 0 | -8,542 | -117 | -2,288 | -9,762 | - | 0.756 | 1.00 | fail |


## The lottery view (top 1 / 3 / 5 trades)

Full table: `hunt/r50/results/report.md`.

- In the final test, every rule that made money depended on its top trades.
  - The EMA and RSI sweep rules: the top trade is 96-125% of the net and the top 5 are 137-194%.
  - The 12:30 basket: 138% / 190% / 225% (top 1 / 3 / 5).
  - The best lottery re-tune: 199% / 349% / 398%.
  - K1: +Rs 387 net, but -Rs 35,049 without its top 5.
- In design no rule with more than 4 trades made money. Removing the top trades makes every loss deeper: the 12:30
  basket goes from -Rs 1,17,686 to -Rs 3,84,713 without its top 5.

## The re-tunes (small, pre-registered, chosen on design)

The cell chosen on all of design and the walk-forward net (each quarter traded by the cell that was best before it):

| re-tune | grid | chosen | walk-forward net | final |
|---|---|---|---|---|
| T-HERO | band Rs 1-5 / 5-30 x exit app / 25% trail | 5-30, app exits | +Rs 777 | 5 trades, -Rs 11,416 |
| T-LOT | 12:30 / 13:30 / 14:30 x hold / 25% trail | 12:30, trail | -Rs 22,307 | 115, +Rs 4,072 (top-5 398%) |
| T-OI | exits E2 / E3 / trail / hold | E3 | +Rs 444 (4 trades) | 1 trade, +Rs 28 |
| T-BOSS | ATM+-1 Rs 15-30 / nearest Rs 5-30 x E2 / trail | ATM+-1, E2 | -Rs 3,558 | 1 trade, +Rs 28 |
| T-GEX | E3 / trail / hold | trail | -Rs 3,758 | 30, -Rs 5,471 |
| T-SWEEP | EMA8x21 / EMA9x21 / Donchian x -50% stop / trail | EMA9x21, -50% stop (= O-E921) | -Rs 82,863 | 26, +Rs 1,39,236 (one trade) |
| T-ST | E3 / trail / hold | trail | -Rs 12,870 | 14, -Rs 7,340 |
| T-AFT | ATM+-1 / nearest Rs 5-30 x hold / trail | Rs 5-30, trail | -Rs 19,509 | 46, -Rs 8,542 |

- **Boss's 25% trail (R38) cut the losses in most families**: lottery, GEX, Supertrend and the late-range rules.
  Losing less is still losing.
- The OI-wall rules (R26) almost never fire now: 4 trades in design and 1 in the final test (+Rs 28). The trigger needs
  16 minutes beyond the 13:00 wall plus an OI drop, a volume spike and a Rs 15-30 option, all in 13:45-14:30. Too few
  trades to judge.

## What would come back on PAPER, and what stays off

**Comes back on paper: none.** No rule passed the gates, so nothing changes in the app, on paper or live.

**Stays off (exact settings tested):**

| rule | exact settings | why it stays off |
|---|---|---|
| App Hero arm (HeroRules.kt) | NIFTY; straddle >= 15% over its low since 12:00 and 15-min index move >= 0.25%; 13:30-14:45; nearest OTM Rs 1-5; Rs 5,000; half at 5x, rest 20x, -60% stop, 15:05 | 12 design trades -Rs 26,417; 2 final -Rs 3,776 (the arm stays on paper as it is: R50 changes nothing in the app) |
| Hero re-tune | the same trigger on all 5 indices, Rs 5-30, app exits | 5 final trades -Rs 11,416 |
| One-sided hero (V4) | option 2.5x its low since 12:00 + momentum, 13:30-15:00, Rs 1-15 | design -Rs 54,402, final -Rs 45,066 |
| Lottery baskets | CE + PE nearest Rs 3 (Rs 1-5) at 12:30 / 13:30 / 14:30, Rs 500 a side, hold or 25% trail | lose Rs 21k-1.06 lakh in design; best final +Rs 4,072 is 398% its top 5 |
| 12:30 cheap basket (R43 base) | CE + PE nearest Rs 5-30 at 12:30, Rs 5,000 each, -50% stop | design -Rs 1,17,686; final +Rs 1,26,738 is one SENSEX call |
| OI-wall short covering (R26 P / A6) and Boss's window (B1) | 13:45-14:30, 16 closes beyond the 13:00 OI wall (+ OI down 15/25%, chain volume 2x, 15-min move), ATM+-1 Rs 15-30, Rs 2,000, 4x target or step lock | 4 design trades, 1 final trade: too few to judge |
| GEX (R36 X1 / X2) | 14:00 CE + PE nearest Rs 22.5 on top-third GEX_short / bottom-third GEX_net days, Rs 1,000 a leg, step lock | lose in design and final |
| Sweep signals (R43) | 5-min index EMA8x21 ADX>15, EMA9x21 ADX>20, EMA9x26 ADX>20, RSI 55/45 momentum (12:30-14:45); RSI 30/70 fade, Donchian 20 (all day); Rs 5-30 or OTM2; Rs 5,000; -50% stop | design losses; final profits are one trade (2 Apr SENSEX call), too few trades or q 0.25 |
| Supertrend (R35 S3 option chart, S5 index) | 14:00-14:45, ST(7,3) + ST(10,2) flip, option volume 2x EMA10, TWAP side (S5: + 3 heavyweights), ATM+-1, Rs 2,000 | lose in design and final |
| Afternoon window (R34 K1, R25 T1) | K1: 14:30-14:45 index range break by 15:00, ATM+-1, Rs 2,000, hold to 15:05. T1: fresh 15-min day high / low break 11:00-14:30, strike within 1.25 expected moves, Rs 1-10, Rs 1,000, app exits | K1 design -Rs 99,713, final +Rs 387 (top-5 driven); T1 design -Rs 227, final -Rs 3,174 |

If Boss still wants to play heroes for fun, the practical points stay those of R25 / R38 / R43. A hero rule is a lottery
ticket. Size it as money you expect to lose. Use a hard trail (here it cut the loss by half or more). Never hold a cheap option to
15:05 hoping.

## Honest notes

- **Not re-run:** R37 (gamma flip: needs a spot-grid GEX solve) and R39 / R40 (patterns and ML scores mined year by
  year, so there is no fixed rule to carry over). They stay off.
- **Old rules on new indices.** The originals run on all 5 indices (the old studies used 1-4 of them), except the Hero
  arm (NIFTY only, as the app). MIDCPNIFTY's OI-wall threshold was set to 25% (as BANKNIFTY / FINNIFTY), in the PREREG.
- **Duplicate tests in the BH family.** The sweep re-tune chose EMA9x21 with the -50% stop, which is O-E921 itself, and
  O-P / O-B1 / T-OI / T-BOSS share their single final trade. BH counts each once per test (28), which is slightly
  conservative. No rule is near the gates either way.
- **Twins for the fixed-time rules** (the baskets and GEX) draw from 12:00-14:45. Their p asks "is this time better than
  a random afternoon minute", not "is there an edge".
- **Data.** The lake stores ATM+-10 strikes. A held strike that leaves that range is priced at the edge strike (further
  OTM) or at its intrinsic value (deep ITM), as in R25.
  - SENSEX is the index most affected. The 2 Apr 2026 SENSEX call was checked by hand: it tracks the index (worth Rs 712
    at 15:05 with intrinsic value Rs 750).
  - FINNIFTY's Mar and Aug 2026 monthly expiries were found only after Amendment 2.
- **Sample sizes are small.** The final test has 73 index-expiry-days: 28 NIFTY, 27 SENSEX, 6 each for the monthlies.
  Rules that fire once a day or less cannot reach 40 trades in it. That is why most of them read "too few trades to
  judge".

## Checks run

- `python -m pytest research/hunt/r50/test_r50.py -q`: 30 tests. They cover:
  - dates, window and split;
  - lots in force;
  - sizing modes;
  - the expiry rule (missing 15:15 rows, the last session);
  - next-minute fills, no chasing;
  - the -50% stop at the next open (gap at the worse price);
  - hold, E2 target confirmation, E3 half + lock, the 25% trail and the app exits;
  - worthless exits with no sell charges, dated STT and BSE charges, the 2x spread;
  - metrics and the lottery view, max drawdown, p-values, the walk-forward reading earlier blocks only, the gates;
  - the look-ahead guard on real days for 10 rule kinds (masked and poisoned futures give the same signal).
- Brain tests and the catalog check, R25 / R26 / R43 tests, `python research/experiments/validate.py`, and the knowledge
  pack rebuild with `--check`.
