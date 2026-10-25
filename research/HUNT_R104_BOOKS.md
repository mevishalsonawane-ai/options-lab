# R104: every testable rule from the 50 books, run on our markets (25 Oct 2026)

Boss, 25 Oct: *"Now find the way to implement all the book studies ... and also find out any new strategies could be
found from the findings of the book."* Pre-registered in `research/hunt/r104_books/PREREG.md` (committed before any
result, with Amendment 0 for a data fact and Amendment 1 for the new strategies, both before the holdout). Code and
small results in `research/hunt/r104_books/`; trade lists stay outside git.

## The answer in one paragraph

**0 of 227 tests passed every gate.** 211 book rules: 166 were tested in 221 rule-by-market tests, 18 were near-duplicates
of rules we had already tested (recorded with that study's result, no new test), 26 need data this environment does not
have, 1 is a descriptive question. The six new strategies of Amendment 1 (combinations of book ideas) also failed.
The binding gates were the walk-forward folds (a rule had to make money in every fold) and Benjamini-Hochberg across all
227 tests. 45 entry tests made money in design after costs; 54 tests were positive in the holdout; only 22 were positive
in both, and none of those had a q below 0.81 except two with too few trades (13 and 4 trades). Nothing goes to paper.
The clearest LEAD is Graeme-Smith's daily Short Swing Trading rules: positive in design and holdout on the index (daily
SST: 172 trades, Rs 347,212 design; Rs 168,395 holdout on 66), on MCX futures (holdout Rs 588,190 on 175) and on BTC/ETH
(holdout Rs 32,164 on 10), but each failed a walk-forward fold or BH. It deserves its own single pre-registered test with
a fresh forward holdout (see "What next").

## What was run

- **Signal source (CLAUDE.md, 14 Oct):** every entry and exit signal is computed on the underlying (index spot, the MCX
  future, the perp, the stock). IDX tests buy the option the rule names (1-ITM unless the rule says ATM) on R51's
  held-contract engine; daily index rules trade a near-month future proxy (spot points x lot, carry); MCX and crypto
  trade the future / perp; the volatility rules trade option structures with the chain as context. No rule reads an
  option's own price chart for its signal. Crypto trades always carry a stop and a profit lock.
- **Costs:** dated charges (STT, exchange, SEBI, stamp, GST, Rs 20 an order) and liquidity slippage: R17 `liq` fills for
  options (5 bps + 1-4 ticks by the contract's 5-minute volume, stops 2x), half-spreads for futures (stops 2x), R94's MCX
  charges, Delta Exchange fees + GST for crypto; stale option legs repriced as R103 Amendment 2.
- **Periods:** IDX / VOL design Aug 2021 - Mar 2026 (4 folds), holdout 1 Apr - 19 Oct 2026; daily index design 2006 -
  2021 (4 folds), holdout 2022 - Oct 2026; MCX daily 2012 - 2021 / 2022 - Oct 2026; MCX intraday design to 31 Jul 2026,
  holdout Aug - Oct 2026 (Amendment 0: the MCX minute file starts May-Jun 2026 for most minis); crypto design Oct 2023 -
  Mar 2026; the arms (ARM / MM) use R101's split (design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 13 Oct 2026).
- **Gates:** design net > 0; > 0 in every walk-forward fold; BH q < 0.10 across all 227 tests (day-block bootstrap p);
  min trades; not carried by the 5 best trades; a random-entry twin for design passers (none reached it); the holdout
  read once, same sign. ARM tests on the difference to the arm (B0); MM on the risk-lowering bar (drawdown -20%, net
  -10% at most, worst month not worse, beats 500 random cuts of the same size).
- **Reproduction checks:** all four arms' baselines reproduced R101's published figures exactly, design and final (LIQ
  881 trades Rs 257,249 / 357 trades Rs 11,155; R72 3,071 / Rs 216,929 and 1,202 / Rs 171,366; R51 4,498 / Rs 9,998 and
  1,781 / Rs 81,845; R52 Rs 290,573 / Rs 48,612).

## By family (tests; design positive; holdout positive; both)

| family | tests | design + | holdout + | both |
|---|---|---|---|---|
| F1 volume-confirmed breakouts (MCX real volume; index daily futures volume) | 4 | 0 | 0 | 0 |
| F2 Wyckoff tape / volume entries | 2 | 0 | 0 | 0 |
| F3 price-action bar and chart patterns with the books' own stops | 82 | 12 | 18 | 4 |
| F4 Carli divergence | 5 | 0 | 1 | 0 |
| F5 swing rules on daily bars | 25 | 18 | 13 | 11 |
| F6 Porwal's Indian setups | 9 | 3 | 2 | 1 |
| F7 Aziz VWAP / ABCD / bull flag | 9 | 0 | 1 | 0 |
| F8 volatility and premium structures | 17 | 4 | 2 | 1 |
| F9 money management on the arms | 12 | 3 (bar) | 0 | 0 |
| F10 indicator and intraday trend rules | 23 | 3 | 6 | 2 |
| F11 exit overlays and filters on the arms | 20 | 6 | 8 | 1 |
| F12 pairs, mean reversion, seasonals, crypto | 13 | 4 | 3 | 2 |
| Amendment 1 new strategies | 6 | 2 | 0 | 0 |

Plain reading: **intraday bar patterns on index options lose after costs, as R71 / R87 found** (12 of 82 positive in
design, mostly tiny or rare); **daily trend / swing rules on futures make money in most periods** (18 of 25 in design,
11 of 25 in both halves) but their year-to-year swings are large, so few are positive in every fold and none clears BH
across 227 tests; **volatility selling** stays near zero after costs (R103 again), and **buying vol** loses.

## Leads (positive in design AND holdout; none passed the gates)

| rule | what | design | holdout | why it failed |
|---|---|---|---|---|
| `book-smith-01` DLY | Graeme-Smith SST on NIFTY/BANKNIFTY daily (futures proxy) | 172 trades, win 42%, Rs 347,212 | 66 trades, win 42%, Rs 168,395 | BH q 0.81 |
| `book-smith-04` MCXD | SST on MCX futures daily | 224 trades, Rs 29,933 | 175 trades, Rs 588,190 | a negative fold, q 1.00 |
| `book-smith-06` CRY | SST on BTC/ETH daily with the trail as profit lock | 53 trades, Rs 157,221 | 10 trades, Rs 32,164 | min trades, q 1.00 |
| `book-woods-16` DLY | inverse head and shoulders on daily index bars | 94 trades, win 55%, Rs 214,631 | 32 trades, Rs 139,562 | a negative fold, q 0.81 |
| `book-fmbayes-01` MCXD | cross-sectional momentum, 4 MCX futures, monthly | 111 months, Rs 1,354,524 | 58 months, Rs 363,878 | a negative fold, q 1.00 |
| `book-wyckoff-08` ARM | Wyckoff swing-low stop on the arms | delta Rs 29,056 | delta Rs 30,382 | one negative quarter, q 1.00 |

Design stars that reversed in the holdout: Larry Swing's master-plan exits on SWING_DEEP's hosts (`book-lswing-05`,
1,584 trades Rs 1,409,446 design, -Rs 330,423 holdout), Fabozzi's filter rule (`book-fmfabozzi-01`, Rs 1,317,966 design,
-Rs 505,996 holdout), Sadowski's daily RSI(2) momentum (`book-sadowski-09` DLY, Rs 1,495,059 design, -Rs 878,745 holdout).

## Money management on the arms (risk-lowering; judged on R101's B0 trade lists of all four arms, 1 lot)

B0 (four arms together): design net Rs 774,750, max drawdown -Rs 444,695; final net Rs 312,978, max drawdown
-Rs 170,942. The arms' paper trade lists live on the phone (private repo), so the backtest lists were used.

- **CUSUM pause (`book-fmaqf-04`)**, the best design result: drawdown -Rs 178,421 vs -Rs 444,695 with net Rs 872,299,
  better than all 500 random cuts (p 0.002, q 0.15 after BH: fail). In the final period it made the drawdown WORSE
  (-Rs 201,250 vs -Rs 170,942). Rejected.
- Weekly profit goal (`book-heitkoetter-14`): cut net to -Rs 279,253 in design (it stops the best weeks early). Rejected.
- Aziz's day give-back stop (`book-aziz-12`): small drawdown cut (11%), final drawdown worse. Rejected.
- Douglas's 20-trade blocks (`book-douglas-01`): design drawdown -18%, final worse. Rejected.
- Chan's half-Kelly throttle (`book-fmchan-05`) and the worst-day cap (`book-fmchan-06`): cut drawdown by cutting most of
  the trading (net -27% / -101%). Rejected.
- Douglas's fixed rupee risk (`book-douglas-02`): at Rs 2,000 or Rs 650 split over 4 arms, one lot of every arm already
  risks more than the budget, so it takes no trades at all: the account stop cannot be met by sizing at 1-lot minimum.
- Sizing rules that ADD lots (Aziz 1% / 6%, Kelly, Merton mean-variance, min-variance, cushion) are not risk-lowering:
  Kelly at a quarter turned Rs 774,750 into Rs 13,712,650 in design with a -Rs 1,050,373 drawdown; reported, not "helps".

## New strategies (Amendment 1, hypotheses written before their numbers)

| id | idea | design | final / holdout | verdict |
|---|---|---|---|---|
| N1 | option-volume surge confirms R72 / R51 Donchian breaks | delta -Rs 376,815 | delta -Rs 103,584 | fail: volume-confirmed breaks did NOT do better |
| N2 | Wyckoff effort vs result on the 09:20 bar (skip absorption-direction entries) | delta Rs 23,344 (twin p 0.035) | delta -Rs 5,481 | fail |
| N3 | skip option buying on vol-rich days | delta -Rs 317,305 | delta -Rs 3,382 | fail: rich-IV days were the arms' good days |
| N4 | month (-Rs 13k) / week (-Rs 6.5k) account pause | DD -Rs 143,770 vs -Rs 444,695, net Rs 304,788 | net -Rs 19,718 vs Rs 312,978 | fail: cuts net far more than 10% |
| N5 | daily SMA50 + DMI(8) direction filter on the arms | delta -Rs 126,918 | delta -Rs 350,032 | fail |
| N6 | hedged 5-lot short straddle only when vol is rich | 154 trades, Rs 99,770 | 18 trades, -Rs 28,200 | fail |

**No new strategy survived.** The useful negative: the books' "volume confirms the break" (N1) and "trade with the daily
trend" (N5) both made our Donchian arms worse, and skipping vol-rich days (N3) removed good trades.

## Descriptive (Bennett 10, no trade)

Does the 09:20 skew add anything to ATM IV in explaining the range? NIFTY rest-of-day range: R² 0.214 with ATM IV alone,
0.216 with skew added (1,285 days, skew t 1.86); BANKNIFTY 0.265 and 0.265 (1,244 days). Next day: no gain on either.
Bennett is right: once ATM IV is known, skew adds almost nothing.

## Disclosures

- Before F3's code was committed, `book-woods-01` IDX was run once as an engine smoke test; its code was not changed after.
- Bugs found and fixed before the holdout (each disclosed in a commit): daily stop orders were clamped to one day (two F3
  daily tests re-run); the event straddle (`book-fmwhd-02`) could be priced past its weekly expiry on the next contract
  (design +Rs 2.04 lakh on 18 trades became -Rs 7,709 on 5 after the fix); 5 of 1,150 `book-fmwhd-01` trades had no
  price and were dropped. Engine speed-ups changed no rule.
- After the holdout read, only bookkeeping changed: the entry ids of N1-N6 were lower-cased for the validator, the book
  cards became re-run safe, and a test fixture was given its expiry day (the walker's expiry guard had made it return
  nothing); tested entries lost their "proposed" titles, re-runs no longer repeat the notes, `variants_tried` counts
  every variant; the pack puts an entry's result before a long hypothesis (it was being cut off); and two ira-core
  knowledge tests that expected 100+ unrun rules now expect exactly the log's proposed entries. No rule, number or
  verdict changed. The rebuilt knowledge pack (`android/app/src/main/assets/knowledge/`)
  and that test file are the only files outside `research/`; the pack is generated; it is about 2 MB, as it already was before R104.
- The MCX intraday sample is short (Amendment 0), so most MCXI tests had 15-100 trades.
- Index volume rules (Wyckoff 01 / 03, Porwal 10 / 11) could not be tested: the index has no volume and index-futures
  minutes exist only from 29 Jul 2026 (57 sessions).

## Every test

| rule | book | market (signal source) | variant | design trades | win | design net | holdout net (trades) | q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| `book-atb-01` | The Advanced Trading Book | IDX (index spot, bought option) | 5m | 122 | 37% | -Rs 35,394 | Rs 10,386 (97) | 1.00 | fail |
| `book-atb-01` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 25 | 28% | Rs 6,907 | -Rs 5,786 (30) | 1.00 | fail |
| `book-atb-02` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 1650 | 30% | -Rs 182,714 | Rs 3,315 (188) | 1.00 | fail |
| `book-atb-02` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 78 | 23% | -Rs 117,381 | -Rs 27,510 (123) | 1.00 | fail |
| `book-atb-03` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 123 | 33% | -Rs 57,418 | -Rs 4,727 (12) | 1.00 | fail |
| `book-atb-04` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 31 | 39% | -Rs 29,470 | -Rs 8,905 (4) | 1.00 | fail |
| `book-atb-04` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 4 | 25% | -Rs 19,650 | Rs 0 (0) | 1.00 | fail |
| `book-atb-05` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 762 | 38% | -Rs 4,232 | -Rs 31,840 (59) | 1.00 | fail |
| `book-atb-06` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 69 | 28% | Rs 9,746 | -Rs 2,835 (4) | 1.00 | fail |
| `book-atb-06` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 1 | 0% | -Rs 380 | -Rs 818 (5) | 1.00 | fail |
| `book-atb-07` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 106 | 36% | -Rs 35,473 | -Rs 13,798 (8) | 1.00 | fail |
| `book-atb-07` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 9 | 44% | Rs 25 | -Rs 5,294 (17) | 1.00 | fail |
| `book-atb-08` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 105 | 44% | -Rs 24,083 | -Rs 7,966 (15) | 1.00 | fail |
| `book-atb-08` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 4 | 50% | -Rs 37 | Rs 3 (13) | 1.00 | fail |
| `book-atb-09` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 254 | 42% | -Rs 70,878 | -Rs 17,450 (29) | 1.00 | fail |
| `book-atb-09` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 5 | 60% | -Rs 1,060 | -Rs 3,873 (15) | 1.00 | fail |
| `book-atb-10` | The Advanced Trading Book | CRY (BTC/ETH perp) | 15m | 285 | 48% | -Rs 16,726 | -Rs 6,912 (59) | 1.00 | fail |
| `book-atb-10` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 60 | 32% | -Rs 6,790 | -Rs 10,658 (8) | 1.00 | fail |
| `book-atb-10` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 5m | 32 | 31% | -Rs 2,108 | -Rs 1,034 (18) | 1.00 | fail |
| `book-atb-11` | The Advanced Trading Book | IDX (index spot, bought option) | retest | 52 | 25% | -Rs 14,409 | -Rs 5,433 (7) | 1.00 | fail |
| `book-atb-11` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | retest | 9 | 33% | -Rs 943 | -Rs 7,385 (6) | 1.00 | fail |
| `book-atb-12` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 20 | 10% | -Rs 12,862 | Rs 1,017 (4) | 1.00 | fail |
| `book-atb-12` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 5m | 7 | 43% | -Rs 99 | -Rs 2,017 (33) | 1.00 | fail |
| `book-atb-13` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 498 | 42% | -Rs 113,257 | -Rs 7,864 (66) | 1.00 | fail |
| `book-atb-13` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 39 | 41% | -Rs 31,359 | -Rs 6,932 (82) | 1.00 | fail |
| `book-atb-14` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 163 | 29% | -Rs 17,408 | Rs 7,532 (6) | 1.00 | fail |
| `book-atb-14` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 18 | 56% | Rs 22,921 | -Rs 5,975 (31) | 0.82 | fail |
| `book-atb-15` | The Advanced Trading Book | CRY (BTC/ETH perp) | rev | 1416 | 47% | -Rs 204,380 | -Rs 76,464 (407) | 1.00 | fail |
| `book-atb-15` | The Advanced Trading Book | IDX (index spot, bought option) | rev | 477 | 39% | -Rs 95,017 | -Rs 37,705 (74) | 1.00 | fail |
| `book-atb-15` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | rev | 24 | 25% | -Rs 6,262 | Rs 4,950 (30) | 1.00 | fail |
| `book-atb-16` | The Advanced Trading Book | IDX (index spot, bought option) | 5m | 53 | 23% | -Rs 25,455 | Rs 10,793 (38) | 1.00 | fail |
| `book-atb-16` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 17 | 12% | -Rs 4,163 | Rs 153 (3) | 1.00 | fail |
| `book-atb-17` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 59 | 39% | -Rs 9,359 | -Rs 4,980 (7) | 1.00 | fail |
| `book-atb-17` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 36 | 3% | -Rs 10,578 | -Rs 6,945 (5) | 1.00 | fail |
| `book-atb-18` | The Advanced Trading Book | IDX (index spot, bought option) | 15m | 82 | 39% | -Rs 36,895 | Rs 577 (13) | 1.00 | fail |
| `book-atb-18` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 15m | 5 | 40% | -Rs 2,931 | -Rs 2,598 (10) | 1.00 | fail |
| `book-atb-19` | The Advanced Trading Book | CRY (BTC/ETH perp) | 60m | 250 | 50% | -Rs 26,734 | -Rs 9,105 (86) | 1.00 | fail |
| `book-atb-19` | The Advanced Trading Book | IDX (index spot, bought option) | 60m | 74 | 35% | -Rs 12,598 | -Rs 12,197 (17) | 1.00 | fail |
| `book-atb-19` | The Advanced Trading Book | MCXI (MCX mini future (own bars)) | 60m | 5 | 40% | -Rs 606 | Rs 11,058 (6) | 1.00 | fail |
| `book-aziz-02` | Andrew Aziz | IDX (index spot, bought option) | 5m | 1444 | 29% | -Rs 104,778 | -Rs 12,222 (183) | 1.00 | fail |
| `book-aziz-02` | Andrew Aziz | MCXI (MCX mini future (own bars)) | 5m | 118 | 36% | -Rs 34,625 | -Rs 40,153 (125) | 1.00 | fail |
| `book-aziz-03` | Andrew Aziz | IDX (index spot, bought option) | 5m | 779 | 30% | -Rs 89,454 | -Rs 730 (84) | 1.00 | fail |
| `book-aziz-04` | Andrew Aziz | IDX (index spot, bought option) | 5m | 2677 | 25% | -Rs 327,740 | -Rs 48,469 (325) | 1.00 | fail |
| `book-aziz-04` | Andrew Aziz | MCXI (MCX mini future (own bars)) | 5m | 61 | 21% | -Rs 39,218 | Rs 405 (126) | 1.00 | fail |
| `book-aziz-05` | Andrew Aziz | IDX (index spot, bought option) | 1m | 820 | 31% | -Rs 47,515 | -Rs 4,205 (70) | 1.00 | fail |
| `book-aziz-06` | Andrew Aziz | IDX (index spot, bought option) | 1m | 977 | 31% | -Rs 85,721 | -Rs 1,874 (89) | 1.00 | fail |
| `book-aziz-06` | Andrew Aziz | MCXI (MCX mini future (own bars)) | 1m | 5 | 0% | -Rs 994 | -Rs 1,063 (11) | 1.00 | fail |
| `book-aziz-07` | Andrew Aziz | IDX (index spot, bought option) | 1m | 260 | 32% | -Rs 27,826 | -Rs 12,409 (27) | 1.00 | fail |
| `book-aziz-08` | Andrew Aziz | ARM (the arm's index signal (overlay / filter)) | base | 9161 | 44% (arm 46%) | Rs 737,006 (arm Rs 774,750, delta -Rs 37,744) | delta Rs 54,648 | 1.00 | fail |
| `book-aziz-10` | Andrew Aziz | ARM (the arm's index signal (overlay / filter)) | base | 9211 | 47% (arm 46%) | Rs 715,553 (arm Rs 774,750, delta -Rs 59,197) | delta -Rs 67,881 | 1.00 | fail |
| `book-aziz-11` | Andrew Aziz | ARM (the arm's index signal (overlay / filter)) | base | 297 | 39% (arm 44%) | Rs 45,103 (arm Rs 257,249, delta -Rs 212,146) | delta -Rs 7,078 | 1.00 | fail |
| `book-aziz-12` | Andrew Aziz | MM (the arms' trades (sizing)) | base | - | - | Rs 881,695 vs Rs 774,750; DD -Rs 397,148 vs -Rs 444,695 | DD cut -10%, net cut -23% | 1.00 | fail |
| `book-aziz-13` | Andrew Aziz | MM (the arms' trades (sizing)) | base | - | - | Rs 2,303,897 vs Rs 774,750; DD -Rs 1,420,772 vs -Rs 444,695 | DD cut -16%, net cut -14% | 1.00 | fail |
| `book-bennett-01` | Colin Bennett | VOL (option structure (chain as context)) | A | 537 | 42% | -Rs 144,620 | -Rs 38,545 (81) | 1.00 | fail |
| `book-bennett-02` | Colin Bennett | VOL (option structure (chain as context)) | nostop | 168 | 55% | -Rs 160,226 | -Rs 63,992 (28) | 1.00 | fail |
| `book-bennett-03` | Colin Bennett | VOL (option structure (chain as context)) | plain | 222 | 55% | -Rs 28,552 | -Rs 6,907 (23) | 1.00 | fail |
| `book-bennett-04` | Colin Bennett | VOL (option structure (chain as context)) | B | 557 | 27% | -Rs 279,847 | -Rs 35,441 (59) | 1.00 | fail |
| `book-bennett-05` | Colin Bennett | VOL (option structure (chain as context)) | base | 511 | 49% | -Rs 180,635 | -Rs 15,112 (59) | 1.00 | fail |
| `book-bennett-09` | Colin Bennett | VOL (option structure (chain as context)) | base | 23 | 43% | -Rs 29,762 | Rs 18,668 (2) | 1.00 | fail |
| `book-carli-01` | David Carli | DLY (index daily, futures proxy) | base | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-carli-01` | David Carli | MCXD (MCX future daily) | base | 0 | - | Rs 0 | -Rs 284 (1) | 1.00 | fail |
| `book-carli-02` | David Carli | IDXF (index spot, futures proxy) | half | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-carli-02` | David Carli | MCXI (MCX mini future (own bars)) | 4h | 0 | - | Rs 0 | Rs 2,646 (1) | 1.00 | fail |
| `book-carli-03` | David Carli | IDX (index spot, bought option) | 60A | 11 | 36% | -Rs 11,019 | Rs 0 (0) | 1.00 | fail |
| `book-carli-04` | David Carli | ARM (the arm's index signal (overlay / filter)) | window | 7928 | 45% (arm 46%) | Rs 256,280 (arm Rs 774,750, delta -Rs 518,470) | delta -Rs 16,780 | 1.00 | fail |
| `book-douglas-01` | Mark Douglas | MM (the arms' trades (sizing)) | base | - | - | Rs 807,484 vs Rs 774,750; DD -Rs 364,101 vs -Rs 444,695 | DD cut -8%, net cut 10% | 1.00 | fail |
| `book-douglas-02` | Mark Douglas | MM (the arms' trades (sizing)) | R650 | - | - | Rs 0 vs Rs 774,750; DD Rs 0 vs -Rs 444,695 | DD cut 100%, net cut 100% | 1.00 | fail |
| `book-douglas-04` | Mark Douglas | IDX (index spot, bought option) | 30m | 250 | 25% | -Rs 39,437 | Rs 807 (27) | 1.00 | fail |
| `book-douglas-04` | Mark Douglas | MCXI (MCX mini future (own bars)) | 30m | 14 | 29% | -Rs 8,502 | Rs 15,563 (27) | 1.00 | fail |
| `book-douglas-05` | Mark Douglas | ARM (the arm's index signal (overlay / filter)) | base | 171 | 37% (arm 44%) | -Rs 12,408 (arm Rs 257,249, delta -Rs 269,657) | delta -Rs 8,616 | 1.00 | fail |
| `book-fmaqf-03` | Härdle, Kleinow and Stahl | DLY (index daily, futures proxy) | M50 | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-fmaqf-03` | Härdle, Kleinow and Stahl | MCXD (MCX future daily) | M50 | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-fmaqf-04` | Härdle, Kleinow and Stahl | MM (the arms' trades (sizing)) | base | - | - | Rs 872,299 vs Rs 774,750; DD -Rs 178,421 vs -Rs 444,695 | DD cut -18%, net cut 1% | 0.15 | fail |
| `book-fmbayes-01` | Rachev, Hsu, Bagasheva and Fabozzi | MCXD (MCX future daily) | base | 111 | 57% | Rs 1,354,524 | Rs 363,878 (58) | 1.00 | fail |
| `book-fmbinomial-03` | van der Hoek and Elliott | VOL (option structure (chain as context)) | base | 424 | 58% | Rs 372,595 | -Rs 28,900 (31) | 1.00 | fail |
| `book-fmbjork-01` | Tomas Björk | VOL (option structure (chain as context)) | base | 895 | 59% | Rs 147,050 | -Rs 97,497 (105) | 1.00 | fail |
| `book-fmbjork-02` | Tomas Björk | VOL (option structure (chain as context)) | base | 130 | 27% | -Rs 288,787 | -Rs 43,215 (19) | 1.00 | fail |
| `book-fmbjork-04` | Tomas Björk | MM (the arms' trades (sizing)) | c25 | - | - | Rs 13,712,650 vs Rs 774,750; DD -Rs 1,050,373 vs -Rs 444,695 | DD cut -95%, net cut -320% | 1.00 | fail |
| `book-fmbjork-05` | Tomas Björk | MM (the arms' trades (sizing)) | base | - | - | Rs 940,118 vs Rs 774,750; DD -Rs 294,852 vs -Rs 444,695 | DD cut -4%, net cut 11% | 1.00 | fail |
| `book-fmchan-01` | Ernest Chan | IDXF (index spot, futures proxy) | stop1sd | 6113 | 52% | -Rs 2,649,139 | -Rs 1,004,805 (735) | 1.00 | fail |
| `book-fmchan-02` | Ernest Chan | DLY (index daily, futures proxy) | base | 4 | 50% | Rs 53,482 | Rs 0 (0) | 0.01 | fail |
| `book-fmchan-03` | Ernest Chan | MCXD (MCX future daily) | base | 2 | 0% | -Rs 16,836 | Rs 0 (0) | 1.00 | fail |
| `book-fmchan-04` | Ernest Chan | MCXD (MCX future daily) | base | 10 | 40% | -Rs 4,026 | Rs 25,181 (5) | 1.00 | fail |
| `book-fmchan-05` | Ernest Chan | MM (the arms' trades (sizing)) | base | - | - | Rs 564,802 vs Rs 774,750; DD -Rs 180,429 vs -Rs 444,695 | DD cut 19%, net cut 64% | 1.00 | fail |
| `book-fmchan-06` | Ernest Chan | MM (the arms' trades (sizing)) | base | - | - | -Rs 8,671 vs Rs 774,750; DD -Rs 14,130 vs -Rs 444,695 | DD cut 70%, net cut 116% | 1.00 | fail |
| `book-fmchan-07` | Ernest Chan | DLY (index daily, futures proxy) | h10_stop | 349 | 53% | -Rs 426,187 | Rs 219,315 (92) | 1.00 | fail |
| `book-fmchan-08` | Ernest Chan | CRY (BTC/ETH perp) | base | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-fmchan-09` | Ernest Chan | CRY (BTC/ETH perp) | base | 6346 | 41% | -Rs 834,590 | -Rs 171,924 (1384) | 1.00 | fail |
| `book-fmfabozzi-01` | Focardi and Fabozzi | DLY (index daily, futures proxy) | x5 | 850 | 39% | Rs 1,317,966 | -Rs 505,997 (56) | 0.90 | fail |
| `book-fmfabozzi-02` | Focardi and Fabozzi | DLY (index daily, futures proxy) | b1 | 119 | 15% | Rs 2,592 | -Rs 535,781 (31) | 1.00 | fail |
| `book-fmfabozzi-03` | Focardi and Fabozzi | IDX (index spot, bought option) | base | 939 | 30% | -Rs 231,181 | -Rs 37,943 (49) | 1.00 | fail |
| `book-fmjavaheri-03` | Alireza Javaheri | VOL (option structure (chain as context)) | nostop | 358 | 51% | -Rs 29,272 | -Rs 21,176 (47) | 1.00 | fail |
| `book-fmkaratzas-01` | Karatzas and Shreve | MM (the arms' trades (sizing)) | k10 | - | - | Rs 716,019 vs Rs 774,750; DD -Rs 459,933 vs -Rs 444,695 | DD cut -2%, net cut -29% | 1.00 | fail |
| `book-fmmusiela-02` | Musiela and Rutkowski | VOL (option structure (chain as context)) | B | 879 | 59% | Rs 116,775 (host Rs 147,050) | -Rs 79,347 (105) | 1.00 | fail |
| `book-fmmusiela-03` | Musiela and Rutkowski | VOL (option structure (chain as context)) | base | 307 | 66% | Rs 110,474 | Rs 4,088 (35) | 1.00 | fail |
| `book-fmoptim-01` | Bartholomew-Biggs | DLY (index daily, futures proxy) | plain | 996 | 39% | Rs 9,827 | -Rs 214,333 (404) | 1.00 | fail |
| `book-fmoptim-01` | Bartholomew-Biggs | MCXD (MCX future daily) | deep | 409 | 40% | -Rs 101,013 | -Rs 249,461 (305) | 1.00 | fail |
| `book-fmoptim-02` | Bartholomew-Biggs | MM (the arms' trades (sizing)) | base | - | - | Rs 1,046,849 vs Rs 774,750; DD -Rs 183,381 vs -Rs 444,695 | DD cut 5%, net cut 49% | 1.00 | fail |
| `book-fmross-01` | Sheldon Ross | MCXD (MCX future daily) | base | 1166 | 44% | -Rs 161,038 | -Rs 317,905 (1129) | 1.00 | fail |
| `book-fmross-02` | Sheldon Ross | VOL (option structure (chain as context)) | base | 498 | 33% | -Rs 110,983 | -Rs 46,248 (57) | 1.00 | fail |
| `book-fmross-03` | Sheldon Ross | DLY (index daily, futures proxy) | base | 0 | - | Rs 0 | Rs 0 (0) | 1.00 | fail |
| `book-fmross-03` | Sheldon Ross | MCXD (MCX future daily) | base | 1 | 100% | Rs 4,554 | Rs 0 (0) | 1.00 | fail |
| `book-fmross-04` | Sheldon Ross | VOL (option structure (chain as context)) | base | 3 | 0% | -Rs 1,789 | Rs 0 (0) | 1.00 | fail |
| `book-fmschmidt-01` | Anatoly Schmidt | IDX (index spot, bought option) | 5m | 9207 | 30% | -Rs 543,959 | -Rs 141,565 (1096) | 1.00 | fail |
| `book-fmwhd-01` | Wilmott, Howison and Dewynne | VOL (option structure (chain as context)) | base | 1145 | 28% | -Rs 1,542,428 | -Rs 391,601 (126) | 1.00 | fail |
| `book-fmwhd-02` | Wilmott, Howison and Dewynne | VOL (option structure (chain as context)) | base | 5 | 40% | -Rs 7,709 | Rs 0 (0) | 1.00 | fail |
| `book-fmwilmott-01` | Paul Wilmott | VOL (option structure (chain as context)) | C | 14 | 57% | -Rs 47,767 | Rs 0 (0) | 1.00 | fail |
| `book-heitkoetter-01` | Markus Heitkoetter | IDX (index spot, bought option) | 15m | 955 | 29% | -Rs 209,106 | Rs 7,491 (162) | 1.00 | fail |
| `book-heitkoetter-04` | Markus Heitkoetter | IDX (index spot, bought option) | base | 3935 | 42% | -Rs 354,686 | -Rs 102,477 (453) | 1.00 | fail |
| `book-heitkoetter-04` | Markus Heitkoetter | MCXI (MCX mini future (own bars)) | base | 959 | 34% | -Rs 104,204 | -Rs 155,264 (540) | 1.00 | fail |
| `book-heitkoetter-09` | Markus Heitkoetter | IDX (index spot, bought option) | 5m | 1259 | 33% | -Rs 4,494 | -Rs 25,975 (142) | 1.00 | fail |
| `book-heitkoetter-11` | Markus Heitkoetter | ARM (the arm's index signal (overlay / filter)) | base | 7712 | 45% (arm 46%) | Rs 233,080 (arm Rs 226,928, delta Rs 6,153) | delta -Rs 340,126 | 1.00 | fail |
| `book-heitkoetter-12` | Markus Heitkoetter | ARM (the arm's index signal (overlay / filter)) | base | 7574 | 44% (arm 46%) | Rs 269,744 (arm Rs 226,928, delta Rs 42,817) | delta -Rs 95,358 | 1.00 | fail |
| `book-heitkoetter-14` | Markus Heitkoetter | MM (the arms' trades (sizing)) | m1.0 | - | - | -Rs 279,253 vs Rs 774,750; DD -Rs 418,046 vs -Rs 444,695 | DD cut 16%, net cut 120% | 1.00 | fail |
| `book-heitkoetter-15` | Markus Heitkoetter | ARM (the arm's index signal (overlay / filter)) | base | 6616 | 47% (arm 46%) | Rs 698,314 (arm Rs 774,750, delta -Rs 76,436) | delta Rs 81,794 | 1.00 | fail |
| `book-heitkoetter-16` | Markus Heitkoetter | ARM (the arm's index signal (overlay / filter)) | base | 7691 | 46% (arm 46%) | Rs 608,413 (arm Rs 774,750, delta -Rs 166,338) | delta Rs 2,017 | 1.00 | fail |
| `book-lswing-01` | Larry Swing | DLY (index daily, futures proxy) | base | 30 | 47% | Rs 8,091 | Rs 431 (2) | 1.00 | fail |
| `book-lswing-02` | Larry Swing | MCXD (MCX future daily) | base | 58 | 40% | Rs 80,627 | Rs 105,286 (23) | 1.00 | fail |
| `book-lswing-03` | Larry Swing | STK (stock daily, futures proxy) | base | 279 | 35% | -Rs 88,313 | -Rs 60,082 (55) | 1.00 | fail |
| `book-lswing-04` | Larry Swing | DLY (index daily, futures proxy) | base | 358 | 42% | Rs 235,028 | -Rs 253,352 (120) | 1.00 | fail |
| `book-lswing-05` | Larry Swing | DLY (index daily, futures proxy) | mp | 1584 | 35% | Rs 1,409,446 (host Rs 511,931) | -Rs 330,423 (515) | 1.00 | fail |
| `book-lswing-06` | Larry Swing | IDXF (index spot, futures proxy) | B | 122 | 29% | -Rs 180,131 | -Rs 19,537 (12) | 1.00 | fail |
| `book-ozenbas-01` | Ozenbas, Pagano, Schwartz and Weber | DLY (index daily, futures proxy) | ls | 259 | 25% | Rs 750,943 | -Rs 239,991 (146) | 1.00 | fail |
| `book-ozenbas-01` | Ozenbas, Pagano, Schwartz and Weber | MCXD (MCX future daily) | long | 346 | 22% | -Rs 258,066 | Rs 1,100,931 (114) | 1.00 | fail |
| `book-ozenbas-04` | Ozenbas, Pagano, Schwartz and Weber | IDX (index spot, bought option) | 1m | 6395 | 41% | -Rs 575,827 | -Rs 10,056 (745) | 1.00 | fail |
| `book-ozenbas-05` | Ozenbas, Pagano, Schwartz and Weber | IDX (index spot, bought option) | 1m | 9206 | 30% | -Rs 1,017,788 | -Rs 176,349 (1096) | 1.00 | fail |
| `book-porwal-01` | Nikhil Porwal | IDX (index spot, bought option) | 15m_trend | 671 | 42% | -Rs 164,067 | -Rs 71,837 (163) | 1.00 | fail |
| `book-porwal-01` | Nikhil Porwal | MCXI (MCX mini future (own bars)) | 15m_trend | 326 | 49% | -Rs 235,896 | -Rs 40,867 (271) | 1.00 | fail |
| `book-porwal-02` | Nikhil Porwal | DLY (index daily, futures proxy) | confirm | 80 | 28% | -Rs 136,157 | -Rs 56,444 (24) | 1.00 | fail |
| `book-porwal-03` | Nikhil Porwal | DLY (index daily, futures proxy) | base | 124 | 44% | Rs 88,560 | -Rs 259,614 (50) | 1.00 | fail |
| `book-porwal-03` | Nikhil Porwal | MCXD (MCX future daily) | base | 136 | 33% | Rs 3,047 | Rs 15,512 (76) | 1.00 | fail |
| `book-porwal-04` | Nikhil Porwal | DLY (index daily, futures proxy) | base | 1 | 0% | -Rs 839 | Rs 0 (0) | 1.00 | fail |
| `book-porwal-05` | Nikhil Porwal | MCXI (MCX mini future (own bars)) | N20 | 3 | 33% | -Rs 6 | -Rs 233 (1) | 1.00 | fail |
| `book-porwal-07` | Nikhil Porwal | ARM (the arm's index signal (overlay / filter)) | neutral | 6725 | 46% (arm 46%) | Rs 185,982 (arm Rs 226,928, delta -Rs 40,946) | delta -Rs 57,458 | 1.00 | fail |
| `book-porwal-08` | Nikhil Porwal | IDX (index spot, bought option) | adx30 | 1115 | 25% | -Rs 123,184 | -Rs 72,134 (200) | 1.00 | fail |
| `book-porwal-09` | Nikhil Porwal | IDX (index spot, bought option) | 15m_HL | 756 | 46% | -Rs 34,592 | Rs 7,493 (161) | 1.00 | fail |
| `book-porwal-09` | Nikhil Porwal | MCXI (MCX mini future (own bars)) | 30m_HL | 124 | 57% | Rs 117,287 | -Rs 90,018 (102) | 1.00 | fail |
| `book-porwal-12` | Nikhil Porwal | ARM (the arm's index signal (overlay / filter)) | losses | 5151 | 47% (arm 46%) | Rs 738,821 (arm Rs 774,750, delta -Rs 35,929) | delta -Rs 27,996 | 1.00 | fail |
| `book-sadowski-01` | Roman Sadowski | DLY (index daily, futures proxy) | plain | 65 | 42% | Rs 416,259 | -Rs 483,356 (44) | 1.00 | fail |
| `book-sadowski-01` | Roman Sadowski | MCXD (MCX future daily) | plain | 49 | 35% | Rs 95,346 | Rs 184,782 (35) | 1.00 | fail |
| `book-sadowski-02` | Roman Sadowski | DLY (index daily, futures proxy) | base | 324 | 36% | Rs 458,687 | Rs 49,026 (90) | 1.00 | fail |
| `book-sadowski-02` | Roman Sadowski | MCXD (MCX future daily) | base | 241 | 22% | Rs 49,315 | Rs 68,655 (135) | 1.00 | fail |
| `book-sadowski-03` | Roman Sadowski | IDX (index spot, bought option) | 15m | 13 | 54% | Rs 11,240 | Rs 5,700 (4) | 0.01 | fail |
| `book-sadowski-04` | Roman Sadowski | IDX (index spot, bought option) | N3 | 603 | 26% | -Rs 85,097 | -Rs 30,566 (103) | 1.00 | fail |
| `book-sadowski-05` | Roman Sadowski | IDX (index spot, bought option) | 15m | 4038 | 26% | -Rs 498,476 | -Rs 112,977 (499) | 1.00 | fail |
| `book-sadowski-06` | Roman Sadowski | IDX (index spot, bought option) | 15m | 795 | 42% | Rs 92,562 | -Rs 14,601 (82) | 1.00 | fail |
| `book-sadowski-07` | Roman Sadowski | IDX (index spot, bought option) | 60m | 189 | 49% | -Rs 30,443 | -Rs 24,943 (39) | 1.00 | fail |
| `book-sadowski-07` | Roman Sadowski | MCXI (MCX mini future (own bars)) | 60m | 109 | 35% | -Rs 91,330 | Rs 1,487 (43) | 1.00 | fail |
| `book-sadowski-08` | Roman Sadowski | IDX (index spot, bought option) | NR4 | 223 | 37% | -Rs 26,984 | -Rs 27,149 (73) | 1.00 | fail |
| `book-sadowski-08` | Roman Sadowski | MCXI (MCX mini future (own bars)) | NR4 | 49 | 55% | Rs 78,075 | Rs 62,337 (54) | 1.00 | fail |
| `book-sadowski-09` | Roman Sadowski | DLY (index daily, futures proxy) | daily | 949 | 33% | Rs 1,495,059 | -Rs 878,745 (262) | 1.00 | fail |
| `book-sadowski-09` | Roman Sadowski | IDX (index spot, bought option) | 15m | 5498 | 35% | -Rs 710,888 | -Rs 150,781 (689) | 1.00 | fail |
| `book-sadowski-10` | Roman Sadowski | IDXF (index spot, futures proxy) | base | 114 | 33% | Rs 43,629 | Rs 112,628 (7) | 1.00 | fail |
| `book-smith-01` | David Graeme Smith | DLY (index daily, futures proxy) | pct | 172 | 42% | Rs 347,212 | Rs 168,395 (66) | 0.81 | fail |
| `book-smith-02` | David Graeme Smith | IDX (index spot, bought option) | 60m | 383 | 43% | -Rs 6,265 | -Rs 38,057 (48) | 1.00 | fail |
| `book-smith-03` | David Graeme Smith | DLY (index daily, futures proxy) | pct | 895 | 39% | Rs 737,471 (host Rs 243,767) | Rs 62,799 (319) | 1.00 | fail |
| `book-smith-04` | David Graeme Smith | MCXD (MCX future daily) | pct | 224 | 35% | Rs 29,933 | Rs 588,190 (175) | 1.00 | fail |
| `book-smith-06` | David Graeme Smith | CRY (BTC/ETH perp) | pct | 53 | 36% | Rs 157,221 | Rs 32,164 (10) | 1.00 | fail |
| `book-smith-07` | David Graeme Smith | DLY (index daily, futures proxy) | skip25 | 400 | 41% | Rs 588,417 (host Rs 301,312) | Rs 329,391 (147) | 1.00 | fail |
| `book-tom-01` | Trader Tom | IDX (index spot, bought option) | 15m_bias | 2075 | 35% | -Rs 421,025 | -Rs 187,870 (401) | 1.00 | fail |
| `book-tom-01` | Trader Tom | MCXI (MCX mini future (own bars)) | 15m_bias | 373 | 33% | -Rs 270,906 | -Rs 142,668 (340) | 1.00 | fail |
| `book-tom-02` | Trader Tom | IDX (index spot, bought option) | 60m | 203 | 36% | -Rs 75,101 | -Rs 21,616 (40) | 1.00 | fail |
| `book-tom-02` | Trader Tom | MCXI (MCX mini future (own bars)) | 60m | 33 | 33% | -Rs 3,457 | -Rs 20,261 (32) | 1.00 | fail |
| `book-tom-03` | Trader Tom | ARM (the arm's index signal (overlay / filter)) | base | 4763 | 47% (arm 45%) | Rs 578,879 (arm Rs 484,177, delta Rs 94,702) | delta -Rs 290,139 | 1.00 | fail |
| `book-tom-04` | Trader Tom | IDX (index spot, bought option) | base | 1352 | 43% | -Rs 197,505 | -Rs 104,765 (148) | 1.00 | fail |
| `book-tom-04` | Trader Tom | MCXI (MCX mini future (own bars)) | base | 44 | 48% | Rs 38,438 | Rs 77,450 (66) | 1.00 | fail |
| `book-tom-05` | Trader Tom | IDX (index spot, bought option) | 10m | 1809 | 39% | -Rs 209,879 | -Rs 61,435 (300) | 1.00 | fail |
| `book-tom-06` | Trader Tom | IDX (index spot, bought option) | 5m | 1834 | 38% | -Rs 289,727 | -Rs 51,225 (222) | 1.00 | fail |
| `book-tom-07` | Trader Tom | IDX (index spot, bought option) | 15m | 5087 | 33% | -Rs 333,577 | -Rs 4,813 (369) | 1.00 | fail |
| `book-tom-07` | Trader Tom | MCXI (MCX mini future (own bars)) | 5m | 188 | 32% | -Rs 146,517 | -Rs 149,041 (431) | 1.00 | fail |
| `book-tom-08` | Trader Tom | IDX (index spot, bought option) | 1m | 3709 | 35% | -Rs 427,331 | -Rs 66,001 (420) | 1.00 | fail |
| `book-tom-09` | Trader Tom | IDX (index spot, bought option) | 15m | 377 | 37% | -Rs 135,513 | -Rs 1,895 (54) | 1.00 | fail |
| `book-tom-10` | Trader Tom | IDX (index spot, bought option) | 15m | 340 | 43% | -Rs 92,085 | -Rs 15,950 (42) | 1.00 | fail |
| `book-tom-10` | Trader Tom | MCXI (MCX mini future (own bars)) | 15m | 55 | 40% | -Rs 42,176 | -Rs 37,497 (49) | 1.00 | fail |
| `book-tom-11` | Trader Tom | IDX (index spot, bought option) | 15m | 41 | 37% | -Rs 3,762 | -Rs 993 (5) | 1.00 | fail |
| `book-tom-12` | Trader Tom | IDX (index spot, bought option) | 15m | 29 | 45% | -Rs 4,794 | -Rs 1,233 (3) | 1.00 | fail |
| `book-tom-13` | Trader Tom | IDX (index spot, bought option) | 15m | 857 | 43% | -Rs 152,612 | -Rs 44,250 (103) | 1.00 | fail |
| `book-tom-14` | Trader Tom | DLY (index daily, futures proxy) | base | 12 | 17% | -Rs 58,814 | Rs 22,388 (2) | 1.00 | fail |
| `book-tom-14` | Trader Tom | IDX (index spot, bought option) | 15m | 24 | 25% | -Rs 13,829 | -Rs 1,967 (4) | 1.00 | fail |
| `book-tom-15` | Trader Tom | IDX (index spot, bought option) | 15m | 34 | 32% | Rs 3,688 | -Rs 11,218 (6) | 1.00 | fail |
| `book-tom-16` | Trader Tom | IDX (index spot, bought option) | 15m | 937 | 36% | -Rs 248,832 | -Rs 84,724 (128) | 1.00 | fail |
| `book-tom-16` | Trader Tom | MCXI (MCX mini future (own bars)) | 15m | 98 | 36% | Rs 44,175 | -Rs 16,889 (128) | 0.81 | fail |
| `book-tom-18` | Trader Tom | ARM (the arm's index signal (overlay / filter)) | base | 9132 | 46% (arm 46%) | Rs 829,822 (arm Rs 774,750, delta Rs 55,072) | delta Rs 0 | 1.00 | fail |
| `book-woods-01` | Galen Woods | IDX (index spot, bought option) | 15m | 476 | 47% | -Rs 47,085 | -Rs 32,754 (91) | 1.00 | fail |
| `book-woods-01` | Galen Woods | MCXI (MCX mini future (own bars)) | 15m | 28 | 29% | -Rs 25,227 | -Rs 50,868 (84) | 1.00 | fail |
| `book-woods-02` | Galen Woods | IDX (index spot, bought option) | 15m | 1240 | 46% | -Rs 95,643 | -Rs 17,566 (221) | 1.00 | fail |
| `book-woods-02` | Galen Woods | MCXI (MCX mini future (own bars)) | 15m | 34 | 21% | -Rs 6,455 | -Rs 6,681 (105) | 1.00 | fail |
| `book-woods-03` | Galen Woods | IDX (index spot, bought option) | 15m | 2099 | 44% | -Rs 249,800 | -Rs 146,463 (369) | 1.00 | fail |
| `book-woods-04` | Galen Woods | DLY (index daily, futures proxy) | h3 | 115 | 43% | -Rs 116,147 | -Rs 273,480 (41) | 1.00 | fail |
| `book-woods-04` | Galen Woods | MCXD (MCX future daily) | h1 | 23 | 43% | Rs 3,801 | Rs 24,281 (16) | 1.00 | fail |
| `book-woods-05` | Galen Woods | DLY (index daily, futures proxy) | h1 | 36 | 36% | -Rs 121,797 | -Rs 155,711 (18) | 1.00 | fail |
| `book-woods-05` | Galen Woods | MCXD (MCX future daily) | h1 | 12 | 33% | -Rs 14,684 | -Rs 43,872 (8) | 1.00 | fail |
| `book-woods-06` | Galen Woods | IDX (index spot, bought option) | 15m | 283 | 37% | -Rs 57,502 | Rs 26,154 (43) | 1.00 | fail |
| `book-woods-07` | Galen Woods | IDX (index spot, bought option) | 15m_bias | 469 | 50% | -Rs 61,716 | Rs 1,169 (74) | 1.00 | fail |
| `book-woods-09` | Galen Woods | IDX (index spot, bought option) | 15m | 2992 | 40% | -Rs 259,116 | -Rs 143,840 (351) | 1.00 | fail |
| `book-woods-10` | Galen Woods | IDX (index spot, bought option) | 15m | 1063 | 42% | -Rs 121,020 | -Rs 40,726 (162) | 1.00 | fail |
| `book-woods-11` | Galen Woods | IDX (index spot, bought option) | 15m | 1221 | 45% | -Rs 53,374 | Rs 4,876 (185) | 1.00 | fail |
| `book-woods-12` | Galen Woods | DLY (index daily, futures proxy) | base | 579 | 32% | Rs 124,016 | -Rs 310,727 (182) | 1.00 | fail |
| `book-woods-12` | Galen Woods | IDX (index spot, bought option) | 15m | 3454 | 37% | -Rs 445,187 | -Rs 150,047 (397) | 1.00 | fail |
| `book-woods-12` | Galen Woods | MCXI (MCX mini future (own bars)) | 15m | 98 | 24% | -Rs 26,072 | -Rs 16,801 (165) | 1.00 | fail |
| `book-woods-13` | Galen Woods | DLY (index daily, futures proxy) | base | 404 | 41% | Rs 486,756 | Rs 391 (114) | 0.90 | fail |
| `book-woods-13` | Galen Woods | IDX (index spot, bought option) | 15m | 2790 | 22% | -Rs 378,844 | -Rs 98,293 (347) | 1.00 | fail |
| `book-woods-14` | Galen Woods | IDX (index spot, bought option) | 15m | 1830 | 50% | -Rs 116,652 | -Rs 76,472 (199) | 1.00 | fail |
| `book-woods-15` | Galen Woods | IDX (index spot, bought option) | 15m | 55 | 45% | Rs 340 | Rs 15,605 (12) | 1.00 | fail |
| `book-woods-16` | Galen Woods | DLY (index daily, futures proxy) | base | 94 | 55% | Rs 214,631 | Rs 139,562 (32) | 0.81 | fail |
| `book-woods-16` | Galen Woods | IDX (index spot, bought option) | 15m | 459 | 33% | -Rs 46,244 | -Rs 75,273 (57) | 1.00 | fail |
| `book-woods-16` | Galen Woods | MCXI (MCX mini future (own bars)) | 15m | 26 | 38% | -Rs 1,899 | -Rs 11,073 (34) | 1.00 | fail |
| `book-woods-17` | Galen Woods | IDX (index spot, bought option) | 15m_mid | 51 | 29% | -Rs 24,834 | -Rs 945 (7) | 1.00 | fail |
| `book-woods-18` | Galen Woods | IDX (index spot, bought option) | 15m | 85 | 27% | -Rs 41,080 | -Rs 1,848 (7) | 1.00 | fail |
| `book-woods-19` | Galen Woods | IDX (index spot, bought option) | 5m | 3804 | 29% | -Rs 727,169 | -Rs 90,506 (371) | 1.00 | fail |
| `book-woods-20` | Galen Woods | IDX (index spot, bought option) | 15m | 1221 | 33% | -Rs 76,440 | -Rs 59,374 (138) | 1.00 | fail |
| `book-wyckoff-02` | Wyckoff | MCXI (MCX mini future (own bars)) | 5m | 26 | 35% | -Rs 36,075 | -Rs 180 (1) | 1.00 | fail |
| `book-wyckoff-04` | Wyckoff | MCXI (MCX mini future (own bars)) | 5m | 90 | 14% | -Rs 7,055 | -Rs 1,128 (11) | 1.00 | fail |
| `book-wyckoff-06` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 3952 | 46% (arm 46%) | Rs 459,201 (arm Rs 474,179, delta -Rs 14,978) | delta -Rs 730 | 1.00 | fail |
| `book-wyckoff-07` | Wyckoff | IDX (index spot, bought option) | 5m | 2057 | 48% | -Rs 300,751 | -Rs 57,198 (240) | 1.00 | fail |
| `book-wyckoff-08` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 8453 | 45% (arm 45%) | Rs 513,233 (arm Rs 484,177, delta Rs 29,056) | delta Rs 30,382 | 1.00 | fail |
| `book-wyckoff-09` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 9160 | 45% (arm 46%) | Rs 505,248 (arm Rs 774,750, delta -Rs 269,502) | delta Rs 26,699 | 1.00 | fail |
| `book-wyckoff-10` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 9159 | 43% (arm 46%) | Rs 611,583 (arm Rs 774,750, delta -Rs 163,167) | delta Rs 55,171 | 1.00 | fail |
| `book-wyckoff-11` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | 3d | 7830 | 46% (arm 46%) | Rs 644,734 (arm Rs 774,750, delta -Rs 130,016) | delta Rs 70,169 | 1.00 | fail |
| `book-wyckoff-12` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 9156 | 46% (arm 46%) | Rs 774,311 (arm Rs 774,750, delta -Rs 439) | delta Rs 736 | 1.00 | fail |
| `book-wyckoff-13` | Wyckoff | ARM (the arm's index signal (overlay / filter)) | base | 9151 | 46% (arm 46%) | Rs 778,831 (arm Rs 774,750, delta Rs 4,081) | delta Rs 0 | 1.00 | fail |
| `new-N1` | Amendment 1 | ARM (the arm's index signal (overlay / filter)) | base | 2681 | 45% (arm 46%) | -Rs 149,887 (arm Rs 226,928, delta -Rs 376,815) | delta -Rs 103,584 | 1.00 | fail |
| `new-N2` | Amendment 1 | ARM (the arm's index signal (overlay / filter)) | base | 8987 | 46% (arm 46%) | Rs 798,094 (arm Rs 774,750, delta Rs 23,344) | delta -Rs 5,481 | 1.00 | fail |
| `new-N3` | Amendment 1 | ARM (the arm's index signal (overlay / filter)) | base | 7632 | 46% (arm 46%) | Rs 457,446 (arm Rs 774,750, delta -Rs 317,305) | delta -Rs 3,382 | 1.00 | fail |
| `new-N4` | Amendment 1 | MM (the arms' trades (sizing)) | base | - | - | Rs 304,788 vs Rs 774,750; DD -Rs 143,770 vs -Rs 444,695 | DD cut 36%, net cut 106% | 1.00 | fail |
| `new-N5` | Amendment 1 | ARM (the arm's index signal (overlay / filter)) | base | 3639 | 48% (arm 46%) | Rs 647,832 (arm Rs 774,750, delta -Rs 126,918) | delta -Rs 350,032 | 1.00 | fail |
| `new-N6` | Amendment 1 | VOL (option structure (chain as context)) | base | 154 | 60% | Rs 99,770 | -Rs 28,201 (18) | 1.00 | fail |

## Covered by an earlier study (no new test; no BH budget)

| rule | book | covered by (earlier result) |
|---|---|---|
| `book-wyckoff-05` | Wyckoff | is the R72 Breadth Donchian arm: PASS (final +Rs 1,71,366 on 1,202 trades), on paper |
| `book-woods-08` | Galen Woods | R71 P7 three-bar reversal (final -Rs 1,67,689 on 855, twin p 0.002: timing real, below option cost); the book's stop/exit are R71's |
| `book-tom-17` | Trader Tom | R101 B4/C4 'entries by 12:00' on R72/R51 (raised win rate, cut final net -Rs 81,567) and R92 entry-hour study; the book's 12:15 differs by 15 minutes |
| `book-tom-19` | Trader Tom | R81 X7 pyramid on Liquidity (+Rs 58k design, -Rs 20k final) and H5: the same add-at-+1R mechanism |
| `book-tom-20` | Trader Tom | R81 X5 scale-out (half at 1R, -Rs 79k design / -Rs 35k final) and R100: the same partial-profit mechanism |
| `book-ozenbas-02` | Ozenbas, Pagano, Schwartz and Weber | h14 capped limit +0.5%: the only execution policy to pass (+Rs 288/day), already the Liquidity arm's entry |
| `book-heitkoetter-02` | Markus Heitkoetter | H25 price x SMA crosses (15,120 variants, Rs -145 a trade, 1 of 28 walk-forward families positive), exits of this kind included |
| `book-heitkoetter-03` | Markus Heitkoetter | OBUY_GB TI-03 MA crosses (-Rs 3,69,856 walk-forward on 4,526) and R29 |
| `book-heitkoetter-05` | Markus Heitkoetter | H25 MACD signal / zero cross: 27,000 variants, 0 of 50 families positive walk-forward |
| `book-heitkoetter-06` | Markus Heitkoetter | H25 Williams %R as given: no better than random |
| `book-heitkoetter-07` | Markus Heitkoetter | OBUY_GB MR-03 RSI 30/70 reversal: -Rs 82,004 walk-forward on 1,272 |
| `book-heitkoetter-08` | Markus Heitkoetter | OBUY_GB MR-02 Bollinger fade: -Rs 90,790 walk-forward on 2,136 |
| `book-heitkoetter-10` | Markus Heitkoetter | R1 N11/N12 first-candle rules and the ORB family (R3, R92 0 of 257), PDH/PDL R89 0 of 180 |
| `book-heitkoetter-13` | Markus Heitkoetter | R81 X5 scale-out failed; thirds are the same partial-profit mechanism |
| `book-porwal-06` | Nikhil Porwal | R23 first-15-minute break (-Rs 71,624 on 1,171) and R92 by day type (0 of 257); the prior-day trend and EMA bias are day-type filters R92 covered |
| `book-aziz-01` | Andrew Aziz | ORB on VWAP's side with a narrow range: R10 V1 / T6 and R3 / R49 / R92 (0 of 16, 0 of 336, 0 of 257) |
| `book-aziz-09` | Andrew Aziz | R81 X5 half at 1R + break-even (-Rs 79k design, -Rs 35k final), R100 G13 |
| `book-douglas-03` | Mark Douglas | R81 X5 / R100 partial exits failed; the first third here is taken even earlier |

## Not testable here (data we lack)

| rule | book | what is missing |
|---|---|---|
| `book-wyckoff-01` | Wyckoff | index-futures tick prints exist only in the phone's recorder since 12 Oct 2026 (private repo); ~3 months needed |
| `book-wyckoff-03` | Wyckoff | index only; needs index-futures minute volume, which exists only from 29 Jul 2026 (57 sessions, too few for design + holdout) |
| `book-ozenbas-03` | Ozenbas, Pagano, Schwartz and Weber | no historical bid/ask for index options (one snapshot, 6 Oct 2026) |
| `book-ozenbas-06` | Ozenbas, Pagano, Schwartz and Weber | no option book-depth history |
| `book-ozenbas-07` | Ozenbas, Pagano, Schwartz and Weber | needs tick-by-tick book and queue position |
| `book-bennett-06` | Colin Bennett | next-expiry ATM chain is mostly missing from the rolling lake |
| `book-bennett-07` | Colin Bennett | needs two expiries' chains on event days |
| `book-bennett-08` | Colin Bennett | matched-maturity IVs need a second expiry; with the nearest only, a 10-session vol position has to roll weekly, which the book's trade is not |
| `book-smith-05` | David Graeme Smith | no results / AGM calendar for F&O stocks |
| `book-atm-01` | The Advanced Trading Methods book | multi-lot exit fills need book depth |
| `book-atm-02` | The Advanced Trading Methods book | index-futures minutes only from 29 Jul 2026 (57 sessions) |
| `book-porwal-10` | Nikhil Porwal | index rule needs futures minute volume (57 sessions only); the price part (fade the day-high break) is covered by R86 (6 of 6 lost) and R91 |
| `book-porwal-11` | Nikhil Porwal | index rule needs futures minute volume (57 sessions only); the price part (day-low reclaim) is R87's held fact, below option cost |
| `book-fmjavaheri-01` | Alireza Javaheri | 10% OTM monthly strikes are outside the lake's ATM +-10; bhavcopy in the lake is a weekly sample, closes only |
| `book-fmjavaheri-02` | Alireza Javaheri | needs the next-monthly chain and far legs (see javaheri-01) |
| `book-fmaqf-01` | Härdle, Kleinow and Stahl | implied density needs every strike on the entry day; the lake has ATM +-10 intraday and a weekly bhavcopy sample |
| `book-fmaqf-02` | Härdle, Kleinow and Stahl | as fmaqf-01 |
| `book-fmlyuu-01` | Yuh-Dauh Lyuu | no minute ETF/basket prices, dividend schedule or futures quotes |
| `book-fmbjork-03` | Tomas Björk | needs synchronous bid/ask on three legs |
| `book-fmcapinski-01` | Capinski and Zastawniak | needs executable bid/ask; last prints in the lake are often stale (R103) |
| `book-fmcapinski-02` | Capinski and Zastawniak | needs executable bid/ask |
| `book-fmbinomial-01` | van der Hoek and Elliott | needs simultaneous bid/ask |
| `book-fmbinomial-02` | van der Hoek and Elliott | needs simultaneous bid/ask |
| `book-fmhughston-01` | Hughston and Hunter | needs executable bid/ask |
| `book-fmmusiela-01` | Musiela and Rutkowski | needs synchronous bid/ask for all legs |
| `book-fmmusiela-04` | Musiela and Rutkowski | no intraday USDINR or dated import-duty history |

## What next

1. **One pre-registered R105 test of Graeme-Smith's SST** on daily bars (index futures proxy and MCX futures, fixed
   book settings, no variants), judged on a FRESH forward period from 26 Oct 2026, as a shadow / paper note only. It was
   the one family positive in design and holdout on three markets; in R104 it lost to the size of the test family.
2. Do not add bar patterns, Aziz setups, Carli divergence or indicator crosses to any arm: tested as the books state
   them, they lose after option costs.
3. Keep the arms' exits and sizing as they are: none of the 20 overlays / filters or 12 sizing rules passed.
4. Index volume rules stay open until the phone's futures-tick recorder has about 3 months of data.

Files: `research/hunt/r104_books/results/final.json` (every test), `gates_design.md`, `holdout_entries.json`,
`holdout_arms.json`; RESULT for Boss: `research/hunt/r104_books/RESULT.md`.
