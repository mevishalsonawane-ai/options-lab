# R62: the video's Volume Profile + Smart Money Concepts strategy (15 Oct 2026) - 0 of 32 pass; it does not work after costs

**Verdict.** No variant passes. Boss's video strategy was turned into fixed rules and tested on the five indices
(buying the option) and on the top 10 stocks (in cash), Nov 2024 onwards only.
- **0 of 32 primary tests pass.** Every one of the 32 lost money in the final test (1 Apr - 14 Oct 2026).
- None of the 32 passed even the design walk-forward first.
- None of the 64 secondary runs (other volume sources, swing holding, stock options) made money in the final test either.

Research only: nothing in the app changes and nothing goes on paper.

**Why it loses.**
- Before any cost, the setups are a coin flip on the underlying:
  - the average realised R multiple is between -0.04 and +0.02 for every primary variant in design;
  - the win rate is about 39-40% at a 1:2 target and 45-58% at the structural target, which is what random entries
    with the same stop and target give.
- Costs then decide the result:
  - an index option round trip costs about Rs 80 a lot in charges plus the spread;
  - 1 lot of a stock in cash (about Rs 5-7 lakh of shares) costs about Rs 290 in charges plus slippage.
- The random-entry twins confirm it. Their p values are 0.13 to 1.00: the rules did no better than random entries
  on the same days with the same stop and target.

**What the video's pieces did (design period, which is where they can be judged; final shown too).**

| piece | effect |
|---|---|
| POC retest (base) | about -0.02 R a trade on indices and stocks; loses its costs |
| + Order Block on the POC ("POC on a clean OB") | the one piece that looked useful in design, and on every volume source: losses roughly halved (index -50k vs -155k at 1:2). With time-at-price or constituent volume it even made a little before costs (+0.05 to +0.09 R). In the final test it was as bad as the base or worse (index -0.19 R). Not robust. |
| LVN / "volume vacuum" pullback | about break-even before costs at the structural target (win 54-58%); loses its costs |
| + FVG on the LVN | no real change (it overlaps an FVG most of the time: 519 of 723 NIFTY LVN zones) |
| 1-hour structure filter (BOS / ChoCH) | fewer trades, no better per trade |
| 1:2 target vs structural high / low | the structural target loses less (higher win rate, smaller give-back), but no version is positive |

**By market (final test, summed over the 16 variants; a sum, not a tradable book).**
- Indices: NIFTY -Rs 5.6 lakh (lost in 16 of 16 variants), BANKNIFTY -3.9 lakh (14), FINNIFTY -2.7 lakh (15),
  SENSEX -1.6 lakh (15), MIDCPNIFTY -1.6 lakh (lost in 7, a few trades each in the others, e.g. +8.6k on 30 trades in
  lvn-c-h-st).
- Stocks: 9 of 10 lost; RELIANCE alone was positive (+Rs 56k summed, positive in 9 of 16 variants). One stock out of
  ten, after the fact, is what chance gives; it is not a reason to trade it.

The detailed tables are in `hunt/r62/results/report.md`. Results JSON: `hunt/r62/results/design.json` and
`final.json`; stock options `opt_design.json` / `opt_final.json`. Code and tests: `hunt/r62/` (`test_r62.py`, 28 tests).

## The question

Boss, 15 Oct 2026, shared a strategy from a video:
- draw a Volume Profile (row size 50, value area 70%) from swing low to swing high in an uptrend (high to low in a
  downtrend);
- the POC (the price with the most volume): when it lines up with an Order Block, price comes back to it and reverses,
  so trade the reversal with the trend;
- low / zero-volume nodes (like Fair Value Gaps) pull price in; a pullback into them reverses;
- stop just beyond the swing low / high, target at least 1:2 or the structural high / low, risk 1-2% a trade;
- filters: POC on a clean OB, an FVG lined up with a volume vacuum, trade only with the higher-timeframe structure.

## How it was tested (pre-registered in `hunt/r62/PREREG.md`, commit 9ae93dd, before any P&L)

**Signal source: the underlying, always.**
- Indices: every swing, profile, OB, FVG, entry, stop and target is read on the index spot minutes. The trade buys
  the 1-ITM nearest-expiry CE (long) / PE (short) (the CLAUDE.md default). The option is only what is bought.
- Stocks: everything is read on the stock's minutes, and the stock is traded in cash. R58 showed stock options' costs
  kill small edges; the option version is reported as context.

**The subjective words, made mechanical (each has a unit test on hand-made bars).**
- **Swing:** a fractal of 2 bars each side, known only 2 bars later (look-ahead guard).
- **Trend, BOS, ChoCH:**
  - a close beyond the latest unbroken swing high / low is a break;
  - it is a ChoCH if it flips the trend, else a BOS;
  - the trend is the side of the last break.
- **Timeframes:** structure on 15-minute bars, the higher timeframe on 1-hour bars, execution on 5-minute bars.
- **Anchor:** a 15-minute swing high confirmed in a bullish trend, from the latest swing low before it, with a break
  in between (mirror for bearish).
- **Profile:**
  - 50 rows over the leg; each minute's volume is spread over the rows its range touches;
  - POC = the fullest row; value area 70%.
- **Low-volume node:** inner rows (5-44) below 20% of the POC row. The zone is the first run a pullback meets.
- **FVG:** 5-minute three-candle gap.
- **Order Block:**
  - the last opposite-colour 5-minute candle before an internal 5-minute break;
  - its full range is the zone;
  - "clean" means price did not trade back into it before the leg ended.
- **Entry:**
  - a 5-minute bar touches the zone, and the next bar closes back beyond it in the trend's colour;
  - fill at the next minute;
  - signals 09:24-14:29; one trade per leg.
- **Stop:** 0.02% beyond the leg's swing low (high).
- **Target:** 2R, or the leg's swing high (low).
- **Square-off:** index options 15:15 (15:05 on expiry days); stocks 15:10.
- **Variants:** 16 = POC / LVN x confluence on / off x 1-hour filter on / off x 2R / structural target.
- **BH family:** the 16 variants on the two primary books, 32 tests.

**Measurement.**
- Index option:
  - R51's held-contract engine (one contract from entry to exit, never the rolling ATM series);
  - R47 foreign / stray-row cleaning and the `spikes.py` rule;
  - R17 spread fills;
  - dated charges (STT 0.15% from 1 Apr 2026);
  - the lot in force.
- Stock: R58's MIS / delivery charges, plus slippage of max(Rs 0.05, 1 bp) a side (x2 on stops); 1 F&O lot of
  shares.
- 2x costs are reported.
- Gates (as R47-R61):
  - final net > 0;
  - twin p < 0.05;
  - BH q <= 0.10;
  - 40+ trades;
  - top 5 < net;
  - positive in design (net > 0 and >= 3 of 5 quarters).

**Data and periods.**
- Data: the Drive lake.
- Design: 20 Nov 2024 - 31 Mar 2026.
- Final test: 1 Apr - 14 Oct 2026 (indices) / 12 Oct 2026 (stocks), read once.
- Nothing before 20 Nov 2024 was traded or reported. Earlier bars only warm up the swing state.

## The volume problem (honest limits)

An index has no traded volume. A real intraday volume profile for NIFTY or BANKNIFTY needs index futures minutes,
and the lake has those only from 29 Jul 2026. The `index_1m` volume column is empty until Dec 2025. So three
stand-ins were pre-registered, and one was chosen as primary before any result:
- **(a) primary: option-chain volume.** The summed traded volume of the index's nearest-expiry chain (ATM +-10, CE +
  PE), each minute, placed on the index bar. It is real traded volume available for all five indices over the whole
  window. But it measures option traders: it piles up at the open, on fast moves and on expiry days.
- **(b) constituents.** The traded value of the index's stocks each minute (NIFTY: its 50 current names; BANKNIFTY:
  12 banks; current lists, so survivorship applies). This is the closest to "the index's own volume". It did no
  better: 0 of 16 positive in the final test.
- **(c) TPO (time at price).** R52's H37-10 "open outside value" is a TPO value-area rule, and it is our only
  index rule that has passed on Nov 2024+ data (final: 294 trades, +Rs 48,772, p 0.001). With TPO, R62's rules looked
  best in design: POC + OB was +Rs 6.3k on 272 trades, about +0.08 R before costs. In the final test they lost
  heavily (-Rs 90k on 78 trades, -0.23 R).

So the profile's POC / LVN locations do not carry the edge the video claims, whichever volume stand-in is used. The
stocks have real volume, and the result is the same there.

MCX was not tested. Its futures minutes start 20 Oct 2025, less than half the window.

## Final test (1 Apr - 14 Oct 2026), the primary books

| book | best variant | trades | net | 2x | twin p | q |
|---|---|---|---|---|---|---|
| 5 indices, 1-ITM option (a) | lvn-c-h-st | 143 | -Rs 19,702 | -Rs 35,923 | 0.13 | 1.00 |
| 10 stocks, cash intraday | poc-c-h-2r | 128 | -Rs 72,185 | -Rs 131,466 | 0.98 | 1.00 |

- The worst index variant was lvn-x-x-2r: 222 trades, -Rs 2.28 lakh.
- Stock losses range from -Rs 72k to -Rs 2.85 lakh a variant.
- Every variant's row is in `results/report.md`.

**Risk 1-2% a trade (information).** With Rs 1 lakh and Rs 1,000 at risk, an index option trade is never allowed even
1 lot. The stop is beyond the swing, typically 0.3-0.7% of the index away, which is more than Rs 1,000 of option
risk on 1 lot. For stocks, 1% risk means about 100 shares (median risk 0.7% of price). The best stock variant then
lost Rs 22,400 over the final test.

## Secondary results (context, never gated)

- Constituent volume (NIFTY, BANKNIFTY):
  - design: 3 of the 4 POC + OB variants slightly positive (+Rs 0.2k to +6.3k on 70-98 trades);
  - final: all 16 lose (-Rs 21k to -Rs 97k).
- TPO, 5 indices: all 16 lose in the final test.
- Stocks, TPO: all 16 lose.
- Stocks, 1-5 day swing (delivery, long only): all 16 lose heavily. Delivery charges (STT 0.1% each side) are about
  Rs 1,550 a trade on Rs 6 lakh of shares.
- Stock options (R58's engine, ATM monthly): all 16 lose, design and final (final -Rs 38k to -Rs 1.5 lakh).

## What this means

- The video's examples are chosen after the fact. Mechanically, "price comes back to the POC / LVN and reverses" is
  not more likely than a random entry with the same stop and target, on indices or stocks, from Nov 2024.
- The Order-Block-on-POC filter was the only piece that helped in design (on all three index volume sources). It did
  not hold up in the final test. It is not worth a re-tune on these data: the final test has now been read, so any
  re-tune would have to be judged on new, unseen sessions (paper from today), not on these.
- Nothing goes to paper or live.

## Reverse engineering: what is the setup really trading? (probe on DESIGN sessions only, after the final read)

Boss asked (15 Oct) whether changes could make it profit, and what the strategy is really trying to achieve.
Stripped of its words, the setup is: **after a structure break, buy the first pullback into the leg that made it, and
ride it back to the high.** The volume profile only chooses WHERE in the pullback to buy.
`hunt/r62/r62probe.py` keeps everything of R62's walk:
- the legs, the activation, the touch + next-bar confirmation;
- the cancellation rules and the stop beyond the anchor.

It swaps only the level. Each level's first signal per leg is measured BEFORE costs, on the underlying
(`results/probe_design.json`). Mean R a trade, structural target (+- one standard error):

| level | indices, option volume | indices, TPO | 10 stocks |
|---|---|---|---|
| POC | +0.00 +- 0.03 (550) | +0.04 +- 0.04 (558) | -0.03 +- 0.02 (1,179) |
| LVN | -0.02 +- 0.02 (599) | -0.00 +- 0.02 (500) | -0.01 +- 0.02 (966) |
| VAH / VAL | +0.02 / -0.06 | -0.05 / -0.03 | +0.01 / -0.04 |
| plain 38.2% pullback (no volume) | +0.01 +- 0.02 (798) | same legs | -0.02 +- 0.01 (1,621) |
| plain 50% | +0.00 +- 0.02 (763) | same legs | -0.04 +- 0.02 (1,656) |
| plain 61.8% | -0.05 +- 0.03 (717) | same legs | -0.03 +- 0.02 (1,474) |
| a RANDOM level in the leg | -0.01 +- 0.03 (691) | -0.04 +- 0.03 (693) | -0.04 +- 0.02 (1,432) |

(The plain pullback rows of the TPO column are the same legs as the option-volume column, within 0.002 R.) The 2R
target gives the same picture.

What this says:
- The POC and the LVN are not special places. Buying there does the same as buying a plain 38-50% pullback, or a
  random level in the same leg. All are about 0 R before costs.
- The thing the video is really trading, "the pullback after a break continues", has no measurable edge at these
  timeframes since Nov 2024. It is about a coin flip on the underlying.
- To pay for costs, a rule needs roughly +0.1 R a trade before costs on index options (about Rs 80-160 a lot). Stocks
  in cash need much more: about Rs 290 on Rs 6 lakh of shares. Nothing here is within reach of that.
- So tweaking this setup (other rows, other confluences, other targets) is searching noise. Any "profitable"
  version found now would be fitted to these sessions; the final test has been read, so it could only be checked on
  new sessions from today.
