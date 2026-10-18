# R80: better ENTRIES for Liquidity 15+5

18 Oct 2026. Research only (no `android/**`, no `.github/**`). Pre-registered in `research/hunt/r80/PREREG.md`
(f164b8f, pushed before any number). Code and design results were committed (61932e8) before the final period was read
once. Code: `research/hunt/r80/`.

Boss, 18 Oct: *"use multiple agents/sessions to enhance the liquidity strategies ... use all the existing knowledge and,
if you need, the internet."* R80 asked one question: **can a filter on WHICH Liquidity signals to take beat the plain
rules?** Another session (R79) tests direction filters (Supertrend, a 10-candle wait, cross-index breadth); R80 does not
repeat them.

## Answer

**No. None of the 10 entry filters passed. Liquidity keeps its plain entries, on paper and live.**

- The three filters that looked best in design all lost in the final period:
  - V1, old pools only: +Rs 6,901 in design, then -Rs 12,415 in the final.
  - V9, at most 2 trades a day: +Rs 6,889, then -Rs 1,297.
  - V10, stop after the first loss: +Rs 4,825, then -Rs 24,032.
- The final period's best filter had lost badly in design. V4, clean breaks only, made +Rs 16,733 in the final (twin p
  0.03) but had lost Rs 75,028 in design. That is the same flip seen in H12 and R15.
- V8, the "displacement" break, is the only filter ahead in both the walk-forward window (+Rs 3,889) and the final
  (+Rs 3,708). But it beat random skipping only by chance (twin p 0.30, q 0.80), and it was slightly behind over the
  whole design period (-Rs 721).
- Not trading before 10:15 (V6, V7) cut most of the arm's design profit. The morning breaks carry Liquidity BANKNIFTY:
  dropping them cost Rs 89,850 to Rs 93,387 over 336 design trades.
- BH q is 0.31 or more for all ten (one family of 10, on the final random-skip twin p). No APP_SPEC is written.

The final period (1 Apr - 13 Oct 2026, 131 BANKNIFTY trades) was already read by R51, R63, R70 and R76 for this arm, so it
is weak evidence. Plain Liquidity BANKNIFTY there made +Rs 10,978, and -Rs 33,992 without its top 5 trades.

## Headline table: BANKNIFTY (1 lot, net after all charges and slippage)

| rule (signal: index; option bought 1-ITM) | design trades | design net | walk-forward vs B0 | final trades | final net | final vs B0 | twin p | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| **B0 plain rules** | 336 | Rs 1,10,094 | Rs 63,994 (242 trades) | 131 | Rs 10,978 | - | - | - | - |
| V1 PRIOR: pool formed on an earlier session | 281 | Rs 1,16,995 | +Rs 541 | 108 | -Rs 1,437 | -Rs 12,415 | 0.87 | 0.97 | fail |
| V2 STACK: >= 3 same-side zones on the level | 55 | Rs 34,620 | -Rs 41,636 | 26 | Rs 615 | -Rs 10,363 | 0.56 | 0.80 | fail |
| V3 ROUND: the break crosses a round number | 89 | Rs 66,922 | -Rs 30,597 | 39 | Rs 24,060 | +Rs 13,082 | 0.06 | 0.31 | fail |
| V4 CLEAN: the pool was not swept before the break | 172 | Rs 35,066 | -Rs 59,162 | 69 | Rs 27,711 | +Rs 16,733 | 0.03 | 0.31 | fail |
| V5 PDX: close beyond the previous day's high / low | 238 | Rs 86,594 | -Rs 17,932 | 95 | Rs 13,256 | +Rs 2,278 | 0.36 | 0.80 | fail |
| V6 T1015: no entries before 10:15 | 178 | Rs 20,243 | -Rs 54,398 | 77 | Rs 6,024 | -Rs 4,954 | 0.56 | 0.80 | fail |
| V7 FHR: from 10:15, first-hour range >= its 20-day median | 87 | Rs 16,707 | -Rs 61,498 | 30 | -Rs 15,628 | -Rs 26,606 | 0.96 | 0.97 | fail |
| V8 DISP: breaking bar's body >= 1.5x the last 20 bars' mean | 240 | Rs 1,09,373 | +Rs 3,889 | 87 | Rs 14,686 | +Rs 3,708 | 0.30 | 0.80 | fail |
| V9 CAP2: at most 2 trades a day (both books) | 301 | Rs 1,16,983 | +Rs 5,688 | 116 | Rs 9,681 | -Rs 1,297 | 0.56 | 0.80 | fail |
| V10 L1: stop for the day after the first loss | 271 | Rs 1,14,919 | +Rs 1,046 | 101 | -Rs 13,054 | -Rs 24,032 | 0.97 | 0.97 | fail |

How to read the columns:
- Design is 20 Nov 2024 - 31 Mar 2026. The walk-forward window is Apr 2025 - Mar 2026. No parameter was fitted, so the
  walk-forward column is simply that part of design compared with B0.
- Twin p: the filter's final net against 500 random-skip twins, each dropping as many signals at random (V9 / V10: as
  many trades). The p value is the share of twins that did at least as well.
- Final worst day / max drawdown (B0 -Rs 6,910 / Rs 24,873): V4 -Rs 6,910 / Rs 16,726; V3 -Rs 6,910 / Rs 10,057; V8
  -Rs 6,910 / Rs 19,781. The full statistics are in `hunt/r80/results/*.json`.

## Verdict per idea

| idea | verdict | why |
|---|---|---|
| V1 older pools (formed on an earlier session) | fail | +Rs 6.9k design, -Rs 12.4k final; still behind B0 after removing each side's top 5 trades |
| V2 stacked levels (>= 3 zones) | fail | far fewer trades (55 / 26) and much less money in both periods |
| V3 round-number crossing (Osler's stop cascades) | fail | -Rs 30.6k in the walk-forward window; final +Rs 13.1k on only 39 trades (q 0.31) |
| V4 clean break (no earlier sweep) | fail | lost Rs 59k in the walk-forward window, then won in the final; a flip, q 0.31 |
| V5 beyond the previous day's high / low | fail | -Rs 17.9k walk-forward; +Rs 2.3k final is noise (twin p 0.36) |
| V6 no entries before 10:15 | fail | -Rs 54k walk-forward: the morning breaks make the money |
| V7 active days only (first-hour range) | fail | the worst filter in both periods |
| V8 displacement break | fail | ahead in walk-forward and final but no better than random skipping (twin p 0.30) |
| V9 max 2 trades a day | fail | ahead in design (the 3rd+ trades lost Rs 6.9k on 35), behind in the final |
| V10 stop after the first loss | fail | the later trades of a losing day were winners in the final (-Rs 24k) |

## Did any filter hold on NIFTY and SENSEX? (information only: no BANKNIFTY pass, so nothing to confirm)

The same rules were run on NIFTY (index stop 15) and SENSEX (index stop 50), 15+5 books. Each cell is the change
against that index's plain rules, design / final:

| | V1 | V2 | V3 | V4 | V5 | V6 | V7 | V8 | V9 | V10 |
|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY (plain: design -Rs 9,522 on 235 trades, final -Rs 23,134 on 94) | +14.4k / +8.1k | +16.2k / +29.0k | +6.8k / +16.1k | +0.6k / +7.3k | +27.5k / +11.1k | -20.6k / +12.9k | +0.2k / +18.1k | -4.5k / +17.4k | +7.4k / -2.0k | +13.1k / +0.4k |
| SENSEX (plain: design +Rs 5,877 on 229, final +Rs 5,263 on 72) | +7.9k / +3.8k | +21.1k / -10.5k | +11.9k / +1.7k | -19.7k / -10.5k | +2.9k / +1.1k | -22.3k / +2.6k | -17.0k / -5.9k | +2.3k / +5.6k | +14.5k / +0.6k | +19.2k / +1.2k |

On NIFTY, plain Liquidity loses in both periods, so almost any filter that trades less "helps". That is why random-skip
twins are the test: NIFTY's best final twin p is 0.05 (V2, 13 trades) and 0.07 (V8). V1 (older pools) helped NIFTY and
SENSEX in both periods but hurt BANKNIFTY in the final. V5 helped NIFTY in both (+Rs 27.5k / +11.1k, twin p 0.04 /
0.17) but not BANKNIFTY. None of these is a test: they are notes for any future NIFTY / SENSEX Liquidity study, which
would need its own pre-registration.

## What the trades say (descriptive, BANKNIFTY B0)

- Puts carry the arm again: design puts +Rs 72,752 (174 trades), calls +Rs 37,342 (162); final puts +Rs 25,753 (65),
  calls -Rs 14,776 (66). This matches H12, R15 and the A2 "calls need OI" shadow rule already pre-registered
  (`PREREG_LIQUIDITY_ADDONS.md`); R80 did not re-test it.
- The 5-minute book made most of the money: design +Rs 66,262 (257 trades) against +Rs 43,832 (79) for the 15-minute
  book; final +Rs 11,935 (99) against -Rs 958 (32).
- Breaks of a pool that had been swept first: design +Rs 75,028 (164 trades), final -Rs 16,733 (62). The sign flipped
  between periods, so a sweep before the break predicts nothing here.

## Ideas from the brief not tested, and why

| idea | status |
|---|---|
| volume or tick-speed surge on the event candle | **not tested**: the lake's index-futures minutes start 29 Jul 2026, so there is no design data. R77 showed the option-volume proxy tracks futures volume poorly (rank correlation 0.18-0.38). Test it on the phone's tick recordings once 20+ sessions exist. |
| India VIX level / change, gap size, prior-day range | not repeated: H12 (F07-F10, F16-F19) and R15 (F1-F5) are logged failures |
| expiry week / expiry day | not repeated: H12 F14/F15; expiry days are already skipped |
| OI walls, PCR | not repeated: R15 F3, R28 G7; the A1 / A2 OI rules are already in shadow |
| strike choice (ATM / 1-ITM / 1-OTM) | not repeated: H13, H16 (keep 1-ITM) |
| time of day before / after 11:00 | not repeated: H12 F11/F12 flipped; R80 tried the new 10:15 cut (V6) |
| the swing's time frame (a higher-time-frame swing) | covered only by V5 (the previous day's high / low); a 60-minute swing-zone confluence was not tested |

## Signal source and method

- **Signal: the index**, the BANKNIFTY (and NIFTY / SENSEX) Liquidity level breaks on 15- and 5-minute bars, unchanged.
  Every filter also reads only index bars (pool age, stacked zones, round numbers, sweeps, the previous day's high and
  low, the first-hour range, the breaking bar's body) or the arm's own closed trades (V9, V10). No option-chain context
  is used, and no rule reads an option's price chart. The 1-ITM option is bought; its exits are the arm's own.
- Trades: `hunt/r51/fam_liq.py` on R51's Amendment A clean stores, rebuilt from the Drive lake for this run. Every
  candidate signal is simulated once on the R51 engine. B0 and each filter then walk the books again, so a removed signal
  frees its book for a later one, exactly as the app would. B0 equals `fam_liq.make(...).run` trade by trade (the study
  asserts it), and its final period equals R51's and R70's (131 trades, +Rs 10,978).
  - This run's design B0 is +Rs 1,10,094 against R51's published +Rs 1,18,828. The trades are the same (336), and so are
    the worst day, the max drawdown and the net without the top 5. The gap is in Jan-Mar 2026 among the top trades. It was
    not traced; it is probably the lake files refreshed on 15 Oct.
- Costs: dated charges, R17 liquidity fills, next-minute fills, stops on the minute low with gap fills at the worse
  open, one held contract, 1 lot.
- No look-ahead: every feature is known at the end of the breaking bar. Tests in `test_r80.py` replace every later bar
  with wild prices and check the features do not change. They also check:
  - the first-hour gate reads only rows 0-59 and the 20 sessions before;
  - L1 reads only trades already closed;
  - `day_signals` returns fam_liq's rows unchanged;
  - the final can be read only once.
- Stats: random-skip twins (500, seed 80); BH across the 10 filters; a top-5 check; a paired day bootstrap (2,000,
  information only).
- Web sources for the ideas (a web claim is never evidence of an edge):
  - C. Osler, "Currency orders and exchange-rate dynamics: an explanation for the predictive success of technical
    analysis", Journal of Finance 58(5), 2003: stop orders cluster just beyond round numbers.
  - C. Osler, "Stop-loss orders and price cascades in currency markets", Journal of International Money and Finance
    24, 2005.
  - Secondary summaries: [ATAS, the magic of round numbers](https://atas.net/market-theory/the-magic-of-round-numbers/),
    [LuxAlgo, stop placement vs liquidity pools](https://www.luxalgo.com/library/concept/stop-placement-vs-liquidity-pools.md).
  - Sweep and pool ideas: [LuxAlgo, liquidity sweep](https://www.luxalgo.com/library/concept/liquidity-sweep/),
    [CrossTrade, liquidity sweeps](https://crosstrade.io/learn/price-action/liquidity-sweeps),
    [NexusFi, sweeps and stop hunts](https://nexusfi.com/a/market-structure/liquidity-sweeps-stop-hunts).
  - None of these pages gives a tested result; they are the sources of V2-V4 and V8.

## For Jarvis

R80 (18 Oct) tested 10 ways to choose which Liquidity signals to take: older pools, stacked levels, round numbers,
clean breaks, beyond yesterday's high or low, no trades before 10:15, active days only, strong breaking candles, at most
2 trades a day, and stopping after the first loss. None passed. The ones that looked good in 2025 lost in Apr-Oct 2026,
and the best Apr-Oct filter had lost in 2025. Liquidity keeps its plain entries. Its morning breaks and its puts carry it.

Files: `research/hunt/r80/` (PREREG.md, r80rules.py, r80study.py, r80entries.py, test_r80.py, results/),
`research/experiments/entries/r80-liq-entry-v*.yaml` (10 entries).
