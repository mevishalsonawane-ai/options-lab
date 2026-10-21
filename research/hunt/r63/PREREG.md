# R63 pre-registration: sell when the trend turns against the trade, for every strategy

Written 16 Oct 2026, BEFORE any R63 trade, exit or profit-and-loss number was computed. Only the lake's file list, the
session counts of the day stores (built by R51 / R57 / R54's own builders, no prices of any trade) and earlier reports
were read. This file is committed and pushed on its own before any result. After that commit nothing here changes; a
change needs a dated amendment below, written before the numbers it concerns.

## 0. Why

Boss, 16 Oct 2026: "Can we ask Solo/agents to check the current trend, and if the trend changes, sell the orders" ...
"It should work for all the arms and orders" ... "All the strategies".

Today no strategy closes a trade because the trend turned. Each has only its own stop, target, time stop and square-off.
ira-core `BotTrades` only flags two arms holding opposite sides. Earlier evidence is weak and mixed:
`research/CHANDELIER_EXIT.md` (exit on the opposite Chandelier signal, BANKNIFTY ATM): 5-minute lost in both years, 30- and
60-minute mixed. R19b / R19c (Liquidity give-back trails) cut big winners and lost against random early exits.

R63 asks one question per rule and strategy: **added to the strategy's own exits, does a trend-turn exit on the
underlying make more money after costs than the plain rules, and more than cutting the same trades at random times?**

## 1. Signal source (CLAUDE.md "signal on the underlying")

Every trend rule reads the **underlying only**: the index's 1-minute spot bars from the lake (`index_1m`), the same
series each strategy reads its entries from. The option is only what is held and sold. No option-chain or option-price
input enters any trend rule.

## 2. The four trend-turn rules (fixed now; code `r63rules.py`)

Grid: row r = the 1-minute bar that starts at 09:15 + r (375 rows, 09:15 .. 15:29). A k-minute bar b covers rows
kb .. kb+k-1 (09:15-anchored), is complete at the end of row kb+k-1, and a decision on it acts at the **open of row kb+k**
(the next minute). Bars are built only from the minutes that exist; a bar with no minute does not exist and the
indicators skip it (gaps, holidays and missing minutes never invent a price). Indicators run on the continuous series
of bars across sessions (warm-up from 1 Sep 2024; no trade before 20 Nov 2024).

"Against" a CALL = the underlying turned DOWN; against a PUT = turned UP. (Hero buys both a call and a put: each leg is
judged on its own side.)

- **T1, EMA20 cross (15-minute).** A 15-minute close crosses the EMA20 of 15-minute closes against the trade: down-cross
  (close < EMA and the previous bar's close >= its EMA) against a call; up-cross against a put. EMA seeded with the
  first close, alpha 2/21.
- **T2, Supertrend flip (15-minute).** Supertrend(10, 3) on 15-minute bars flips against the trade (up -> down against a
  call; down -> up against a put). ATR = Wilder's RMA of the true range over 10 bars (seeded with the mean of the first
  10 true ranges), bands from (high + low) / 2, TradingView's band-carry rule.
- **T3, two closes beyond VWAP (5-minute).** Two consecutive 5-minute closes of the same session on the far side of the
  session VWAP (below it against a call, above it against a put). **The index has no volume**, so "VWAP" here is the
  session's equal-weight mean of the 1-minute typical prices (H + L + C) / 3 from 09:15 to the bar's last minute (a
  TWAP). Said plainly in the write-up. Both bars must complete while the trade is open.
- **T4, Donchian-10 opposite break (5-minute).** A 5-minute close below the lowest low of the previous 10 completed
  5-minute bars against a call; above the highest high against a put.

**When a rule may act.** Only on a bar that completes while the trade is open AND at least 5 minutes after the fill:
the act row x must satisfy x >= fill row + 5 (T3: the pair's first bar must also complete after the fill). Completed
bars only; nothing reads a minute after the deciding bar (look-ahead guard test: every later minute changed or deleted
leaves the decision unchanged).

**The exit.** At the open of the act row, the HELD contract (the strike bought at entry, never the rolling ATM series) is
sold with the strategy's engine as a forced exit: R51's engine (`research/hunt/r51/engine.py`, `forced` exit row: a
market sell at that minute's open, R17 "liq" fill = -5 bps and the 1 / 2 / 4-tick half-spread by 5-minute volume; no
trade in that minute: the next traded minute within 3, else the last price), charges `Costs("dated")` on the trade date,
the lot in force. The trend exit is ADDED to the strategy's own exits: forced row = min(the strategy's own forced row,
the trend act row). Any stop, target, lock, time stop or square-off that comes first still wins. A trend act row at or
after the square-off changes nothing.

**Entries are held fixed.** R63 measures the exit only: every strategy's trades are exactly its plain-rules trades
(same entries, same strikes). A trade sold early by a trend rule never creates an extra entry that the plain rules
would not have taken. (In the app a strategy that is allowed to re-enter after an exit could re-enter; R63 does not
price that, and says so.)

## 3. The strategies (every running strategy whose rules can be replayed), exactly as the app runs them

From the app's code on 16 Oct 2026 (`ArmsCleanup.KEPT`; `PassedArms`, `LiquidityRules`, `HeroTicketRules`,
`SoloMidday`, `Solo2`). Each is generated by its own study's code, imported, not copied:

| id | app strategy | rules (as the app runs them) | indices | generator |
|---|---|---|---|---|
| LIQ | Liquidity 15+5 (paper; BANKNIFTY live with PIN) | R51 `fam_liq`: swing / pool level breaks on the index bars, 1-ITM, -15% premium stop, 20-minute time stop unless +5%, 15:10, index stop (BANKNIFTY 30, FINNIFTY 15, MIDCPNIFTY 8 points) and next-level target as the forced exit, one position per book | BANKNIFTY 15m + 5m, FINNIFTY 30m + 5m, MIDCPNIFTY 15m + 5m | `r51/fam_liq.make` (MIDCPNIFTY added with its 8-point index stop, the app's) |
| DON | R51 Donchian 20 (paper) | R57 S2 = `DonchianArm`: 5-minute Donchian-20 close break, 1-ITM, -15% / +30%, 30 minutes, 15:15, one a side, at most 2 a side a day, never on own expiry day | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX | `r57setups.s2_candidates` |
| OOV | R52 Open outside value (paper) | R57 S3 = `OovArm`: open and first 5-minute close outside the previous day's value area at 09:20, 1-ITM, -30% stop, +10% locked once +40%, 60 minutes, 15:10 | the same five | `r57setups.s3_candidates` |
| SOLO | Solo (midday) (Jarvis's paper) | R57 `plain_solo`: S1 at 12:00, 4-ITM, index stop 0.3 ATR14, breakeven once 0.45 ATR, 14:30; strongest of NIFTY / BANKNIFTY / FINNIFTY, 1 a day | NIFTY, BANKNIFTY, FINNIFTY | `r57study.plain_solo` |
| SOLO2 | Solo 2.0 (Jarvis's paper) | A2 logistic model over S1-S4 candidates; the first candidate of the day scoring >= 0.4871 is bought; each setup's own exits | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY | `r57setups.candidates` + the app's own model file `r57/results/models/A2.json` scored with `r57eval` |
| HERO | Hero Rs 1,000 ticket (paper) | R55 cell 12:30 / 15:05 / T2 (the app's trail "after 2x for now", mode "trail only"): 1 lot of the cheap CE and PE at 12:30 on own expiry day, 25% trail on minute closes armed once a close >= 2x the fill, out 15:05 | NIFTY, SENSEX expiry days | `r55lib` + R54's picker, fill and sell |

Honest notes, fixed now:

- **SOLO2 entries are in-sample.** The app's model file was fitted on every labelled row to 14 Oct 2026, so its picks in
  design and final are in-sample selections. R63 holds those entries fixed and asks only about the exit; the
  selection may favour trades that did well under the plain exits, which can only make a trend exit look worse. Said
  in the write-up.
- **HERO is priced with R54's engine, not R51's.** The ticket is a limit buy with R54's late-minute spread multipliers
  and stray-print guard, and its trail reads the option's own minute closes (a premium exit, allowed by CLAUDE.md). A
  trend exit sells at the act row's open with R54's sell (`r54lib.sell_at`: R17 liq ticks x the late multiplier), the
  same family of fills. The trend signal is still the index.
- **The R51 store** (R51 Amendment A, cleaned: foreign rows and stray prints removed) prices LIQ; R57's store (cleaned
  the same way plus the spike rule) prices DON, OOV, SOLO and SOLO2; R54's store prices HERO. Each strategy is priced
  on the store its own study used.

**Not replayed, and why (each is still covered by the app's Trend watch, section 8):**

- MCX paper arms (NG evening breakout, SILVERM morning call (parked), MCX 12-month trend, US-night silver): MCX minutes
  in the lake start 5 Aug 2025 (spot column of the option files only, closes only), the US-silver rule needs a Yahoo
  cache that is not in the lake, R53's simulator fills on minute closes rather than R51's engine, and the 12-month
  trend holds for weeks (an intraday turn is not its question).
- Hidden since 15 Oct (`ArmsCleanup.HIDDEN`): ORB, ORB Fresh, ORB Sweep, Range Fade (R49: 0 of 336 pass), the old Hero,
  Night (R3; held overnight and sold at 09:16, before any completed bar of the new session can turn), VIX divergence,
  Strategy Lab forge ideas, Pine auto-trade, shadow arms on paper, scheduled strategies / baskets, Jarvis's news trades.
  None can trade unless Boss restores it.
- No fixed rules to replay: Boss's manual orders (paper and live, NSE / BSE / MCX), Jarvis's orders on request, the
  Telegram Signal-tester paper arm (a channel's calls), stock-option tools (Boss's own orders), protections and
  square-offs (they are exits themselves), the Gold flavour's paper books (XAUUSD; not in the main app), Watchlist
  (shadow only, places nothing). Crypto: nothing in the app trades crypto.

## 4. Window, split, walk-forward

- Trades only from **20 Nov 2024** (NSE's weekly-expiry change; first session 21 Nov 2024) to the lake's end
  (**13 Oct 2026**; NIFTY 14 Oct in R57's store). Bars from 1 Sep 2024 only warm up indicators.
- **DESIGN:** 20 Nov 2024 - 31 Mar 2026. **Walk-forward:** nothing is fitted (every rule's parameters are fixed above),
  so the walk-forward is the design's out-of-sample quarters Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026,
  each reported; "positive in walk-forward" = their sum > 0.
- **FINAL TEST:** 1 Apr 2026 - the lake's end, read **once** by `r63study.py final`, which writes
  `results/FINAL_READ` and refuses a second run. **It is not pristine:** R51, R52, R54, R55, R57, R61, R61b and R62
  read these sessions, and every strategy here was chosen BECAUSE of how it did on them (R51 Donchian, R52 OOV and
  Solo 2.0 passed on exactly this window). Only paper trading from 16 Oct 2026 on is truly unseen.

## 5. The control: random early-exit twins

For each (rule, strategy) cell and period: the trades the rule **changed** (its exit row differs from the plain exit)
are the sample. A twin set cuts the same changed trades at random instead: for each, an exit row drawn uniformly from
the rows the rule itself could have used (from fill row + 5 to the plain exit row - 1), sold the same way (forced exit
at that row's open, own exits still first). Twin improvement = sum over those trades of (twin net - plain net).
**500 twin sets** per cell (seed 63). One-sided p = (1 + twins with improvement >= the rule's) / 501. Pooled cells sum
each twin draw across the strategies (draw i of every strategy is twin set i).

## 6. What is reported per cell (rule x strategy, plus "all strategies" pooled), per period

- trades (all), trades changed, sessions;
- **Rs saved on losers** (changed trades whose plain net < 0: sum of new - plain), **Rs given up on winners** (changed
  trades whose plain net > 0: sum of plain - new), **net improvement** (all changed trades: new - plain);
- plain net and net with the rule; max drawdown and worst day (daily P&L) with and without the rule;
- improvement without its 5 best changed trades; the twin mean and p; BH q (final);
- per index (and BANKNIFTY apart, for the 26 Oct separation) and per month in the final test;
- the walk-forward quarters.

## 7. Gates (per cell; never relaxed)

A cell PASSES only if all hold:

1. final-test net improvement > 0 after costs;
2. twin p < 0.05 (final);
3. BH q <= 0.10 over **every final-test cell tried** (4 rules x 7 = 28 cells: 6 strategies + pooled);
4. >= 40 changed trades in the final test;
5. not from the top 5 alone: final improvement minus its 5 largest per-trade improvements > 0;
6. design net improvement > 0 AND walk-forward (sum of the four quarters) > 0.

Every cell is logged in `research/experiments/entries/` (28 entries, failures included).

## 8. What the app does with the result (fixed now)

- **Live: never acts.** Shadow and alerts only, behind a switch that is OFF by default, whatever R63 finds (CLAUDE.md:
  anything that could change live trades starts in shadow).
- **Paper acts only where a rule PASSED:**
  - a replayed strategy acts with a rule that passed **for that strategy**; if more than one passed, the one with the
    highest DESIGN improvement (decided before the final read: the order is written into `results/design.json`);
  - non-replayed sources (manual, Jarvis's orders, Telegram tester, MCX arms, restored hidden arms, stock-option
    tools) act only if the rule passed **pooled**, and each source has its own switch, OFF by default;
  - every paper exit goes into the Autopilot change timeline, one-tap undo, labelled (BANKNIFTY labelled for the
    26 Oct separation).
- **Failed rules: shadow notes and alerts only.** The rule the shadow uses is the pooled cell with the highest DESIGN
  improvement (decided on design only, before the final read).
- The trend watch never runs on the order or tick path, never slows or pauses any stop, target, trail, lock,
  square-off or guard, and is restored after the positions on a restart.

## 9. Tests (before the final read)

`test_r63.py`: bars from gapped minutes, EMA / Supertrend / TWAP / Donchian against hand values, the look-ahead guard
(later minutes changed or deleted), the 5-minute rule, the T3 both-open rule, act rows, a forced exit that comes after
the strategy's own exit changes nothing, a session with no bars, an expiry day (15:05 square-off), the twins' window,
the gates and BH. Golden vectors from `r63rules.py` are exported for the app's Kotlin port (`results/golden.json`).
