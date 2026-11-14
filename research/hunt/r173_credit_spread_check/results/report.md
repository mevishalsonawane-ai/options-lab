# R173: are Boss's credit-spread files right? (R72 and Liquidity "LIQ-2" as short OTM credit spreads)

14 Nov 2026. Research only: nothing in the app changes. Pre-registration: `../PREREG.md` (committed before any number).

## For Boss, in short

- **The files are not right.** They never look at a real option price. Each spread's profit is made up from a formula:
  0.28 x the index move, PLUS a fixed "time decay" of 22% of the option's price per day (x 1.35 more for R72), using
  3 days to expiry for every index on every day. The wing (the protective option) is never priced. Costs count 2 orders
  instead of 4. Margin is ignored.
- **I ran their own scripts on our data** (Nov 2024 - Feb 2026, 4 indices; we have no MIDCPNIFTY): they give almost the
  same picture they show you: R72 spread **+Rs 1,73,927, 62.9% wins** (1,219 trades) vs the bought-option R72 at
  -Rs 1,65,453. But the made-up time decay alone is **+Rs 2,52,375**; without it the spread **loses Rs 79,975**. And
  **random entries** (random side, random time) under their formula made money in **200 of 200** tries (average
  +Rs 1,04,987). The profit comes from the formula, not from the trading idea. Liquidity: +Rs 43,521 with
  +Rs 89,721 of made-up decay; -Rs 46,736 without it.
- **On real 1-minute option prices** (NIFTY and BANKNIFTY, the only option prices we have here; Nov 2024 - Feb 2026),
  the same rules LOSE:
  - **R72 spread: -Rs 77,605 on 666 trades, 50.6% wins** (design -Rs 31,799 / walk-forward -Rs 27,607 / final -Rs 18,199).
    Their formula said **+Rs 89,490** on the same two indices and months (610 trades; its weekday expiry rule and
    one-minute look-ahead pick slightly different trades).
  - **Liquidity spread: -Rs 39,372 on 255 trades, 38.0% wins** (-Rs 25,249 / -Rs 8,647 / -Rs 5,476). Their formula said
    +Rs 27,690 (251 trades).
  - Why: the index direction is right about 60% of the time, but a spread only earns a fraction of the move (the wing
    gives most of it back), real time decay over a few hours is small (BANKNIFTY's options are MONTHLY now, median 14
    days to expiry), and four orders cost about **Rs 164 a trade** (Rs 1.09 lakh on R72's 666 trades, more than its
    Rs 31,482 profit before costs). A catastrophe exit loses about Rs 760 - 925, a target pays about Rs 107 - 237.
- **Margin reality.** One spread blocks about **Rs 35,000 - 47,000** (its maximum loss plus 2% of the index value; the
  broker's number will differ a little). With your ~Rs 56k only ONE spread can be open at a time. R72 often fires on
  NIFTY and BANKNIFTY together: **271 of 666 R72 trades (41%) could not have been taken** for margin; Liquidity 34 of 255.
  The trades that fit lost too: R72 **-Rs 50,919** (395 trades, max drawdown **-Rs 51,374**, beyond the -Rs 40k account
  stop); Liquidity **-Rs 34,137** (221 trades).
- **Against random entries of the same spread** (final period, 2,000 random sets): R72's real result (-Rs 11,679) is no
  better than random timing (average -Rs 12,574; p 0.41); Liquidity's is worse (p 0.81).
- **The arms as they are (bought options), same data and costs:** R72 1-ITM: design -Rs 22,099 / walk-forward +Rs 29,879 /
  final +Rs 58,408 (1,143 trades). Liquidity 15+5 ATM: -Rs 73,032 / +Rs 53,547 / -Rs 10,204 (1,365 trades). R72's
  bought option beat its spread version in walk-forward and final; in design both lost.
- **Verdict: both fail** (5 of 6 gates for R72, 4 of 6 for Liquidity). Keep the arms as they are. Do not switch to
  selling. (Even a pass would have needed your own explicit choice, a margin check, broker-resting stops on the short
  leg and leg ordering the app does not have.)

---

## 1. The audit (every assumption and error found by reading the files)

Read in full before anything ran (`inputs/`, untouched). Neither script touches the network or writes files.

| # | item | what the files do | what is real | effect |
|---|---|---|---|---|
| A1 | option prices | none read: `option_path_1m` / `option_params` build a premium from India VIX at the day's open, ATM = 0.3989 x S x sigma x sqrt(3/365) (floored at Rs 20) | real 1-minute prices exist (the release, the lake) | every rupee number is a model output |
| A2 | days to expiry | fixed 3 days, every index, every day | since 20 Nov 2024 BANKNIFTY / FINNIFTY / MIDCPNIFTY are MONTHLY only; real BANKNIFTY spreads here: median 14 days (10%-90%: 2-27); NIFTY: median 3-5 | theta and gamma wrong for most trades |
| A3 | Greeks | constant: short leg delta 0.28, gamma 0.0002 (ITM: 0.62 / 0.00035-0.0007), no vega | the rule's strike (ATM -/+ 2 steps) has a real implied delta of about 0.35-0.39 (NIFTY) and 0.44-0.46 (BANKNIFTY, monthly); delta and vega move with the index and IV | P&L = 0.28 x index points is not a spread's P&L |
| A4 | theta | 22% of the short premium per 375-minute session, linear, x 1.35 extra in R72; earned on EVERY trade whatever happens | real decay over a few intraday hours is small and is netted by the long wing; IV often rises on the losing side | **the whole profit**: +Rs 2,52,375 of +Rs 1,73,927 (R72), +Rs 89,721 of +Rs 43,521 (LIQ) on our data |
| A5 | the wing | never priced; the spread's delta / theta are the short leg's alone | the wing is 3 steps further out and gives back part of both | overstates gains and losses alike, but theta is the one that is always positive |
| A6 | spread payoff | linear in index points, no cap | a vertical spread is capped at width - credit | catastrophe losses mis-sized |
| A7 | costs | `compute_net_cost` of ONE leg: Rs 40 brokerage (2 orders), STT 0.125% on the exit price, the options_lab "quoted x 3" spread law on one leg | FOUR orders (sell + buy at entry, buy + sell at exit): Rs 80 brokerage, STT 0.1% (to 31 Mar 2026; 0.15% after) on the two SELL orders, slippage on four legs | real costs here: about Rs 164 a trade (median Rs 152) |
| A8 | margin | ignored | Rs 35-47k a spread (section 4); Rs 56k account fits one | 41% of R72's trades cannot be taken |
| A9 | expiry days | weekday rule (NIFTY Thursday, BANKNIFTY Wednesday, FINNIFTY Tuesday, MIDCPNIFTY Monday, SENSEX Friday) + a `fut_expiries.json` not supplied | NIFTY weekly moved to Tuesday (Sep 2025); BANKNIFTY / FINNIFTY / MIDCPNIFTY weeklies ended Nov 2024; SENSEX moved to Thursday | wrong days skipped |
| A10 | selection | the HTML shows 5 target / stop presets (2.2x - 3.2x target, 4.5x / 4.8x stop); the recommended 3.2x is the BEST and the EDGE of the sweep; R72 also compares 3 variants, LIQ 3; all chosen on the whole window incl. the "holdout" | pre-registration, walk-forward, Benjamini-Hochberg over every test, random controls (CLAUDE.md) | none applied; the "holdout" was used to pick |
| A11 | random control | none | random entries under their model make money 200/200 times (R72) | the model, not the signal, makes the profit |
| A12 | look-ahead (small) | R72's session TWAP includes the CLOSE of the entry minute (fill at its open); LIQ exits on a "new level" at the FIRST minute of the bar that confirms it (known at its close) | decision data must end before the fill | removed in the real-price test |
| A13 | win rate | "Net-Rs win rate" above the index win rate, because the theta term is added to every trade (32 R72 and 14 LIQ trades "win" in rupees with the index against them, on our data) | | the 60%+ headline is partly the model |
| A14 | data paths / links | `--data-dir` default `/working_dir/c_7de8a1dfc94a9450/downloads` (another tool's sandbox); Google-Drive links to `IraAlgo-data-lake`, `fut_expiries.json`, `STATUS.txt`, `CATALOG.json`; the costs link is a google.com search URL | the lake is real but on Drive, not reachable from this session; the repo paths they cite (`r72rules.py`, `h4/comps.py`, `liquidity_break.py`, `indicator/liquidity.py`, `options_lab/costs.py`, `APP_SPEC.md`) exist | ran on the public release instead |
| A15 | small code points | R72 caps: the original variant allows 2 entries a side (the arm's rule); the spread 1. LIQ's script never skips expiry days. `max_m = 355` and the TWAP use filled minutes. The LIQ zone code is a faithful port of `indicator/liquidity.py` (test_r173 checks they give identical zones) | | not errors that change the verdict |

## 2. Parity: their scripts, unchanged, on our data

Window 1 Nov 2024 - 23 Feb 2026 (all our data allows; the 2026-02-24 .. 2026-09-05 hole is never spanned), index files
rebuilt in their format from the release (`r173parity.py build`). Forced changes: R72 without MIDCPNIFTY (4 of the 4
must break); no `fut_expiries.json`. Their claim is on 1 Nov 2024 - 19 Oct 2026 with 5 indices, so totals differ;
per-trade numbers and the shape match.

| R72, their model | trades | index win | Rs win | net | their claim (5 idx, to Oct 2026) |
|---|---|---|---|---|---|
| C1_Orig_XL (bought 1-ITM) | 2,067 | 48.2% | 42.9% | -Rs 1,65,453 | 3,921 / 44.1% / -Rs 1,12,428 |
| C1_Enh_ITM1_FastLock | 1,110 | 57.3% | 60.1% | -Rs 59,166 | 2,072 / 61.7% / -Rs 1,92,057 |
| **FreshBreadth credit spread** | **1,219** | **60.3%** | **62.9%** | **+Rs 1,73,927** | 2,233 / 62.9% / +Rs 2,36,036 |
| ... NIFTY | 304 | 59.2% | 62.5% | +Rs 52,987 (Rs 174 a trade) | 464 / +Rs 82,971 (Rs 179 a trade) |
| ... BANKNIFTY | 306 | 62.4% | 63.4% | +Rs 36,503 | 442 / +Rs 25,963 |
| ... FINNIFTY / SENSEX | 311 / 298 | | | +Rs 34,749 / +Rs 49,688 | |
| ... the same with theta = 0 | 1,219 | | 58.8% | **-Rs 79,975** | |
| ... theta's share | | | | **+Rs 2,52,375** | |
| ... 200 random-entry sets (random side and minute, every tradable day) | ~1,280 each | | | mean **+Rs 1,04,987** (sd 27,651), **100% positive** | |

| LIQ, their model (4 indices) | trades | index win | Rs win | net | their claim (5 idx) |
|---|---|---|---|---|---|
| Baseline LIQ-2 long ATM | 495 | 73.7% | 52.3% | -Rs 1,53,197 | |
| LIQ-2 + bar-close stop | 506 | 60.1% | 43.5% | -Rs 88,106 | |
| **Enhanced credit spread** | **473** | **61.9%** | **64.7%** | **+Rs 43,521** | 832 / 64.8% / +Rs 1,11,157 |
| ... with theta = 0 | 473 | | 60.3% | **-Rs 46,736** | |
| ... theta's share | | | | **+Rs 89,721** | |
| ... 200 random sets (random side and minute on about as many days, 2x IDX_STOP_EXT target, -4.5x stop from the entry, 15:10) | | | | mean -Rs 3,491 (sd 17,799), 44.5% positive | |

So: the files' numbers are reproducible, and they come from the model's free theta.

## 3. Real option prices (the test)

Rules exactly as their specs (section 3 of PREREG.md), look-aheads removed; R72's breadth on the 4 indices with data;
traded on NIFTY (nearest weekly) and BANKNIFTY (nearest monthly) with the real 1-minute prices of BOTH legs; both legs
filled at the entry minute's open; exits on the index (R72: +3.2x / -4.5x IDX_STOP_EXT; LIQ: zone target, -4.5x past the
broken level, a new level confirmed, 15:10), both legs closed at the next minute's open. Costs: R17 fills on each of
the four orders + dated Zerodha charges. 1 lot at the dated lot. Only 2 entries (of 1,523 that passed the rules' filters) had no strike in the data, no
trade lacked a fill, no exit used a stale price.

### All trades (as if margin were unlimited)

| rule | period | trades | win | net | a trade | worst day | max DD | quoted-law net |
|---|---|---|---|---|---|---|---|---|
| R72-CS | design (Nov 2024 - Mar 2025) | 223 | 50.7% | -Rs 31,799 | -Rs 143 | -Rs 3,657 | -Rs 32,603 | -Rs 72,576 |
| R72-CS | walk-forward (Apr - Sep 2025) | 244 | 47.1% | -Rs 27,607 | -Rs 113 | -Rs 3,745 | -Rs 27,855 | -Rs 74,479 |
| R72-CS | final (Oct 2025 - 23 Feb 2026) | 199 | 54.8% | -Rs 18,199 | -Rs 91 | -Rs 3,430 | -Rs 20,653 | -Rs 50,682 |
| LIQ2-CS | design | 81 | 30.9% | -Rs 25,249 | -Rs 312 | -Rs 2,227 | -Rs 25,598 | -Rs 42,046 |
| LIQ2-CS | walk-forward | 93 | 43.0% | -Rs 8,647 | -Rs 93 | -Rs 2,362 | -Rs 12,223 | -Rs 27,768 |
| LIQ2-CS | final | 81 | 39.5% | -Rs 5,476 | -Rs 68 | -Rs 2,476 | -Rs 7,447 | -Rs 20,098 |

Walk-forward by quarter (all trades): R72 Q2 2025 -Rs 18,089 (120), Q3 -Rs 9,518 (124); LIQ Q2 +Rs 1,306 (42), Q3
-Rs 9,953 (51). Per index over the three periods: R72 NIFTY -Rs 28,521 (301), BANKNIFTY -Rs 49,084 (365); LIQ NIFTY
-Rs 17,537 (128), BANKNIFTY -Rs 21,835 (127). The one positive cell: LIQ NIFTY final +Rs 2,228 (38 trades).

Where the money goes (all 666 R72 trades): before costs +Rs 31,482 (the index went the right way 59.9% of the time);
costs Rs 1,09,087 (Rs 164 a trade). By exit: target 380 trades, Rs +237 each; catastrophe 198, -Rs 759 each; 15:10
88, -Rs 200 each. LIQ (255): before costs +Rs 3,708; target 122 x +Rs 107; catastrophe 54 x -Rs 925; 15:10 68 x +Rs 16;
new level 11 x -Rs 325.

What the rule's strikes really are (all periods): NIFTY short leg median Rs 65-105 at a real implied delta of
0.35-0.39, 3-5 days to expiry, credit about Rs 34-43 (Rs 2,500-3,150 a lot); BANKNIFTY short leg median Rs 400-645 at
delta 0.44-0.46, 14-21 days, credit about Rs 98-133 (Rs 3,250-3,850). Not the files' "0.28 delta, 3 days".

### Margin and the Rs 56k account

Margin per spread (stated approximation: max loss (width - credit) x qty, which the wing caps, plus 2% exposure margin
on the short leg's notional; no trade holds an expiring contract, so no expiry-day ELM): median Rs 35,131 - 46,824
(R103's 2.5%-of-notional rule: Rs 37,076 - 50,559; both rules exclude the same trades). **One spread fits in
Rs 56,000.** Up to 3 R72 spreads were open at once.

| rule | period | signals -> trades | not taken for margin | taken | win | net | max DD | worst month |
|---|---|---|---|---|---|---|---|---|
| R72-CS | design | 223 | 79 | 144 | 49.3% | -Rs 16,858 | -Rs 17,286 | -Rs 4,388 |
| R72-CS | walk-forward | 244 | 103 | 141 | 39.7% | -Rs 22,382 | -Rs 21,842 | -Rs 5,939 |
| R72-CS | final | 199 | 89 | 110 | 50.0% | -Rs 11,679 | -Rs 11,699 | -Rs 6,259 |
| R72-CS | **all three** | 666 | **271 (41%)** | 395 | 46.1% | **-Rs 50,919** | **-Rs 51,374** | -Rs 6,259 (Dec 2025) |
| LIQ2-CS | design | 81 | 7 | 74 | 33.8% | -Rs 22,341 | -Rs 22,691 | -Rs 7,218 |
| LIQ2-CS | walk-forward | 93 | 11 | 82 | 41.5% | -Rs 8,711 | -Rs 10,827 | -Rs 3,739 |
| LIQ2-CS | final | 81 | 16 | 65 | 36.9% | -Rs 3,084 | -Rs 5,011 | -Rs 1,328 |
| LIQ2-CS | **all three** | 255 | **34** | 221 | 37.6% | **-Rs 34,137** | -Rs 35,745 | -Rs 7,218 (Mar 2025) |

Note: when both indices fire in the same minute the account takes BANKNIFTY first (alphabetical tie-break, fixed
before running); that is why R72's account trades are mostly BANKNIFTY (355 of 395). NIFTY-first would change which
trades are taken, not the conclusion: every index and period above is negative except LIQ NIFTY's final.

### Random entries of the same structure (final period, Rs 56k-account trades)

Each trade replaced by a twin on the same day, index and side at a random 5-minute entry (09:20 - 14:30), same exits
(LIQ: the trade's target / stop distances and the day's new-level bars), real prices, same costs; 2,000 sets.

| rule | trades | actual | random mean (sd) | p (normal) | p (Monte-Carlo) | random sets > 0 |
|---|---|---|---|---|---|---|
| R72-CS | 110 | -Rs 11,679 | -Rs 12,574 (3,874) | 0.409 | 0.391 | 0.1% |
| LIQ2-CS | 65 | -Rs 3,084 | -Rs 385 (3,084) | 0.809 | 0.837 | 45.6% |

On real prices the same spread at a random time loses about as much as R72's chosen time: the signal adds nothing
the costs do not take back.

### The arms as they are (bought options), same data, R17 fills + dated charges

R171's rebuild of the arms on this release (R72: 1-ITM, XL exits, 2 entries a side; Liquidity 15+5: ATM).

| arm | design | walk-forward | final |
|---|---|---|---|
| R72 1-ITM XL | 365 trades, 42.7%, -Rs 22,099 | 430, 47.2%, +Rs 29,879 | 348, 47.7%, +Rs 58,408 |
| Liquidity 15+5 ATM | 405, 41.5%, -Rs 73,032 | 519, 47.8%, +Rs 53,547 | 441, 47.4%, -Rs 10,204 |

(Under the quoted spread law both arms are negative in every period, as R171 found. On the lake's real prices, which
include FINNIFTY / MIDCPNIFTY / SENSEX and Apr - Oct 2026, R72 made +Rs 2,16,929 design / +Rs 1,71,366 final at 1 lot:
R152.) The files' "R72 baseline -Rs 1,12,428" is their MODEL of the bought option, not R72's real record.

## 4. Gates (PREREG section 5) and multiple testing

m = 3,913 earlier tests (R171's count) + 2 = 3,915; earlier tests at p = 1.

| gate | R72-CS | LIQ2-CS |
|---|---|---|
| 1 net > 0 in design, walk-forward and final (Rs 56k account) | FAIL (-16,858 / -22,382 / -11,679) | FAIL (-22,341 / -8,711 / -3,084) |
| 2 beats random entries (Monte-Carlo p <= 0.01) | FAIL (0.391) | FAIL (0.837) |
| 3 BH q <= 0.10 | FAIL (q 1) | FAIL (q 1) |
| 4 >= 40 final trades | pass (110) | pass (65) |
| 5 max DD > -Rs 40k and no month < -Rs 13k | FAIL (DD -Rs 51,374) | pass (DD -Rs 35,745, worst month -Rs 7,218) |
| 6 sign holds under the quoted spread law | FAIL (negative everywhere) | FAIL |
| **verdict** | **rejected** | **rejected** |

## 5. Honesty notes

- **Data limits.** Only NIFTY and BANKNIFTY have real option prices here (the public release); FINNIFTY, MIDCPNIFTY and
  SENSEX options, and all of Apr - Oct 2026, are in the Drive lake, which this session cannot reach. The real-price test
  is therefore about the two largest of their five books, on Nov 2024 - 23 Feb 2026. Their claim for these two:
  R72 +Rs 1,08,934, LIQ +Rs 51,228 (over a longer window).
- **Not a clean holdout.** Their rules were chosen on Nov 2024 - Oct 2026, which contains every month here; R171 and
  others have read Apr 2025 - Feb 2026. So this is a re-pricing test on in-sample days: the model's profit does not
  survive real prices even where the rules were tuned. The final block was read once (`FINAL_READ`).
- The release keeps one expiry a day (the nearest; on an expiry day the next one), 20 strikes; R72 skips each index's
  own expiry day (from the data); LIQ, as their script, does not, but then trades the next expiry.
- R72 runs 4 of 4 breadth (MIDCPNIFTY has no data), as R171 did; this changes which bars fire, the same way for the
  model and the real test.
- Margin is a stated approximation (SPAN ~ capped max loss, exposure 2% of notional); Zerodha's calculator decides on
  the day. Doubling or halving it does not change that the trades lose.
- Signal source: the index for every rule (CLAUDE.md default); the options are only traded.

## 6. Files

`../PREREG.md`; `../r173parity.py` (their scripts unchanged + diagnostics -> `parity.json`); `../r173real.py` (rules,
real-price spread, costs, margin, account); `../r173study.py` (`design` -> `design.json`, `final` -> `final.json`);
`../test_r173.py` (engine, costs, margin walk, ports vs their code and `indicator/liquidity.py`, look-ahead guard);
`trades/` (per-trade results, derived); `../inputs/` (Boss's files, unchanged). Data: the `research-data` release
(R171's store, `R171_DATA`), nothing on or after 2026-02-24.

---

## Amendment B (14 Nov 2026): can anything from the files be made to work? (pre-registered in PREREG.md before any number)

Boss: *"On the basis of the file, could we make some changes and enhance it"*; all three options chosen. 10 new tests;
BH m = 3,925. The final block is a second look (read by R173 and R171). `r173b.py` -> `amendment_b.json`.

**In short: no. All 4 filters and all 6 spread variants fail.** The honest explorer page is `results/explorer.html`
(every number generated from the JSON by `r173page.py`).

### B1. The files' entry filters, skip-only, on the arms' current bought options (R17 fills + dated charges)

| filter | final: kept / arm trades | arm final | filtered final | gain a trade design / WF / final | random-skip p (MC) | verdict |
|---|---|---|---|---|---|---|
| R72 + fresh | 344 / 348 | +Rs 58,408 | +Rs 56,196 | 0.0 / +7.9 / -4.5 | 0.70 | fail |
| R72 + TWAP side | 315 / 348 | +Rs 58,408 | +Rs 42,996 | -6.5 / +7.8 / -31.3 | 0.84 | fail |
| R72 + fresh + TWAP | 311 / 348 | +Rs 58,408 | +Rs 40,783 | -6.5 / +16.7 / -36.7 | 0.87 | fail |
| Liquidity 15+5 + TWAP side | 409 / 441 | -Rs 10,204 | -Rs 11,937 | +23.6 / -23.7 / -6.0 | 0.66 | fail |

"Fresh" changes almost nothing on the arm (design: 365 of 365 kept): the arm's own one-position-a-side cap and 30-minute
hold already stop it re-entering on the next bar. The TWAP filter removes winners as often as losers.

### B2. Spread variants on real prices (R173's entries, Rs 56k account)

| variant | design | walk-forward | final | all three: trades / win / net / max DD | random p (MC) | verdict |
|---|---|---|---|---|---|---|
| R72, sell the ~0.28-delta strike | -Rs 14,928 | -Rs 14,829 | -Rs 9,270 | 357 / 52.9% / -Rs 39,028 / -Rs 39,365 | 0.49 | fail |
| LIQ, sell the ~0.28-delta strike | -Rs 12,912 | -Rs 3,957 | +Rs 40 | 166 / 48.8% / -Rs 16,830 / -Rs 17,638 | 0.52 | fail |
| R72, wing 6 steps | -Rs 9,948 | -Rs 9,981 | -Rs 3,279 | 339 / 59.9% / -Rs 23,208 / -Rs 26,912 | 0.016 | fail |
| LIQ, wing 6 steps | -Rs 27,433 | +Rs 4,356 | -Rs 832 | 179 / 47.5% / -Rs 23,909 / -Rs 29,168 | 0.80 | fail |
| R72, debit spread (buy ATM, sell 3 steps out) | -Rs 19,720 | -Rs 29,843 | -Rs 15,419 | 394 / 41.9% / -Rs 64,983 / -Rs 66,056 | 0.47 | fail |
| LIQ, debit spread | -Rs 25,833 | -Rs 9,803 | -Rs 2,514 | 215 / 35.3% / -Rs 38,150 / -Rs 40,766 | 0.85 | fail |

- The 0.28-delta short needs a strike the 20-strike release often lacks at the right distance (BANKNIFTY's monthly
  0.28-delta strike is far out): 130 / 141 / 68 R72 entries (design / WF / final) had no such strike and were not traded.
- The wide wing (6 steps) is the least bad and beats random timing at p 0.016, but still loses in every period and is
  nowhere near the BH bar (q = 1; it would need p of about 0.000025).
- Every variant is negative under the quoted spread law in every period. Margin per spread: Rs 35-55k (the debit
  spread's is overstated by the 2% exposure rule, but it loses money before any margin question).

### What this leaves

Nothing from the files improves the arms on real prices. The arms' bought-option rules stay as they are. Logged as
`r173b-*` entries (10, all rejected).
