# How the Indian index market works for an option buyer (cross-checked, 25 Oct 2026)

Boss, 25 Oct 2026: *"I want you to cross-check the findings and learn how the market actually works: what all things
should be checked, what all things we should ignore, what are the best practices."*

This guide is built from every study in `FINDINGS.md` (R1 to R104), with each summary checked against its full study
by four separate reviewers on 25 Oct 2026. It is about **buying NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX
options**, with the signal read on the index (CLAUDE.md, 14 Oct). Every number has its sample size. Nothing here is a
new test: it is what ~100 studies already found, put in one place. Where the cross-check found a summary wrong or
stated too strongly, the correction is in section 5 and was added to `FINDINGS.md` as a dated note.

## 0. The short version

1. **Direction is a coin flip** on every index and every timeframe we have, from 1 minute to 1 day. Hundreds of
   thousands of tests agree (AUC 0.48-0.54). Nothing that "calls the direction" has survived unseen data.
2. **The size of the move is predictable.** How big the next 30-60 minutes will be can be forecast 15-20% better than
   the time-of-day average, out of sample. VIX, the last candle's size and the first hour tell you a lot.
3. **Options are priced for more than usually happens** (except on expiry day). An option buyer starts behind: the
   09:20 straddle is beaten by the day's move on only 7% of normal days.
4. **The day is front-loaded.** About 61-65% of the day's range is made by 10:30. Most of an intraday buyer's chance is
   in the first hour.
5. **The index's long-run drift happens overnight**, not during the day. An intraday option buyer never earns it.
6. **Costs decide almost everything.** About Rs 70-76 a round trip in charges plus Rs 30-40 of spread on one lot.
   Many "edges" are smaller than that.
7. **The arms that make money live on a few big days** and on the 09:15-09:19 opening break. Cutting winners short
   always made them worse.
8. **Most "great" backtests were broken data**: rolling-ATM strike switches, stale far-strike prints, bad rows, one
   jackpot trade, or a reused holdout. The checklist in section 2 is what separated real from fake.

## 1. How the market actually behaves

### 1.1 Direction: a coin flip everywhere

| evidence | sample | result | source |
|---|---|---|---|
| Every indicator at once, next 15/30/60 min | 469 inputs, 4 indices, 2021-26 | holdout AUC 0.516 / 0.511 / 0.518 | B2 |
| Neural networks | 36,462-41,967 forecasts, 368 sessions | direction AUC 0.510-0.517 | R41 |
| Strategy factory screen | 11,672 tests, 4 indices | direction IC 0.03-0.05 | F1 |
| What moves the next candle (22 inputs) | 98 holdout sessions, 7,350 5-min candles per index | 0 of 8 direction findings held; AUC 0.485-0.535 | R88 |
| Candle shapes | 234 cells (5m/15m), 4,049 context cells | 92% of cells 45-55% up; holdout q >= 0.98 | R87 |
| Pre-registered sign rules (calendar, gaps, momentum, participants) | 70 tests | 1 confirmed, and it is the cash index's stale prices (+0.11 bps on 853,718 pairs, cost 1.25 bps) | R102 |
| 20-year daily momentum | 5,086 days, then 65 holdout days | +5.8 / +12.4 bps a day reversed to -9.4 / -24.6 bps | R102 |
| Same-colour runs | 1-min and 5-min candles | 2.01 candles vs 2.00 for coin flips (1-min), 1.93 (5-min) | RUNS |

The one clue that pointed the same way on every index was **put skew** (when puts are dearer, the index tends to rise
over 30-60 minutes), and it shrank on the later split (+0.03..+0.13 to +0.01..+0.08, F1). It is a note, not a rule.

### 1.2 Size: predictable, and useful

- The next 30-60 minutes' range: holdout error 15-20% below the time-of-day average and 10-15% below VIX (122
  BANKNIFTY / 120 NIFTY holdout sessions, q 0.003). Forecast fifths split quiet from busy half-hours about 2x:
  BANKNIFTY 80 vs 154 points (R16).
- Neural networks forecast the next 60 minutes' size better than trees: R² 0.432 vs 0.382 (36,320 forecasts, q 0.006)
  (R41).
- Next-candle size (R88, out of sample 1 Jun - 19 Oct 2026, 98 sessions): 225 of 305 size findings held. After a big
  candle the next is about 1.5x bigger; a volatile last hour 1.4x; a big range yesterday 1.3x; high VIX 1.3x; expiry
  day 1.27x. A model with everything beats "last size + recent volatility" by only 2-7%, so the simple rule is enough.
- VIX is the range dial: median day range 0.75% (NIFTY) / 1.01% (BANKNIFTY) when VIX is 9-15, and 1.72% / 2.37% when
  VIX is above 20 (1,509 days a tercile) (R102).
- Negative dealer gamma (GEX) at 10:00 comes before a bigger rest-of-day range (BANKNIFTY 0.87% vs 0.75%, 199 / 397
  days, R28), but once VIX and the morning range are known GEX makes the forecast slightly worse (R86). Use VIX and the
  morning range, not GEX.
- Same on MCX: the next hour's size is forecastable, the direction is not (MB1).

### 1.3 The shape of the day

- **Front-loaded:** median day range 0.907% NIFTY (2,288 minute-days) / 1.131% BANKNIFTY (1,185). By 10:30, 61% / 65%
  of it is made; by 13:00, 82% / 87%. 09:15-09:30 moves 2.32 / 3.09 bp a minute against 0.29 / 0.38 at 10:30-13:00. The
  first five minutes hold the day's high or low on 41% / 46% of days. Weekday and expiry day do not change the shape
  (R102).
- **The first 15-minute candle is the biggest of the day** (NIFTY 15m: 36 bps at 09:15 vs 11-12 bps at midday, R88).
- **Gaps:** a gap of 0.5% or more on 20% of NIFTY days and 34% of BANKNIFTY days (5,086 days). A 0.5-1% gap up fills by
  the close 42% / 53% of the time (gap down 41% / 49%). A NIFTY gap of 2% or more closes on its own side 94-96% of the
  time (R102).
- **Drift is overnight:** close-to-close +0.051% a day (NIFTY) / +0.065% (BANKNIFTY), but open-to-close -0.035% /
  -0.039% (5,087 / 5,086 days, R102).
- **Volatility regimes change:** NIFTY's daily sd was 1.32% over 2006-26, 0.74% in 2025 (249 days), 1.05% in 2026 to
  July (130 days) (R102). A rule tuned in a quiet year meets a busy one.
- **Indices move together:** NIFTY's own candle explains 88-90% of SENSEX's variance, 78-79% of FINNIFTY's, 67-68% of
  BANKNIFTY's and 51-52% of MIDCPNIFTY's (R88 holdout). Five indices are not five independent bets.
- **Breaks don't follow through on average:** a 2-close break of support/resistance was further on at the close only
  51% of the time (7,548 breaks, 1,121 sessions); random levels did as well (R20c). A failed poke beyond yesterday's
  high/low does not reverse either (6 fade tests lost, R86).
- **Big selling is fast, big buying is slow:** on FII heavy-selling days NIFTY made 61% of its move by 10:15 (169 days);
  on heavy-buying days 40% (179 days) (R86b). A gap down of 0.5%+ flags a seller morning (lift 3.25 NIFTY, 132 days),
  and the 09:45 detector ranks seller days well on unseen data (AUC 0.72-0.74), but shorting it did not pay (R86c, R86d).

### 1.4 What the option chain tells you

- **Implied volatility sits above realised:** VIX over-states NIFTY's next 21 days on 81% of days (median ratio 1.28,
  4,507 days) (R102). The option buyer pays this premium. (BANKNIFTY: under-stated on 60%.)
- **The 09:20 ATM straddle is the market's forecast of the day:** a median 0.96% of spot on NIFTY (1,222 days) and 1.49%
  on BANKNIFTY (1,190); 2.78% on BANKNIFTY in 2026 since it became monthly-only. The close beats it on only 7.1% /
  6.5% of normal days, but on 40.5% / 37.8% of expiry days (R102).
- **The expiry-day decay clock** (NIFTY, 259 expiries; BANKNIFTY the same shape): share of the 09:20 straddle left is
  84% at 10:30, 61% at 13:00, 40% at 14:30, 22% at 15:00, 13% at 15:15 (R102).
- **Cheap expiry options are a trap:** 97% of Rs 1-10 options end below entry (911 expiries); held to 15:20 a cheap
  ticket loses -Rs 3,213 of Rs 5,000 on average (R98). About 7 in 10 cheap expiry OTM options never reach 2x (R55).
  A hero needs the index to run a median 2.9 straddles one way; it ends a median 0.70 straddles away (R98).
- **Max pain, max OI, PCR are not magnets or walls:** the max-OI strike ends closer to settlement than a random strike at
  the same distance 41.8% of the time (NIFTY, 392 expiries) vs ~44% for no magnet (OF1); max pain is a worse expiry
  forecast than "no change" (R16); the expiry close lands a median 0.60% / 0.68% from max pain (R102).
- **Skew:** OTM puts dearer than OTM calls by +2.56 / +2.11 IV points on 91% / 90% of days (R102). Skew adds almost
  nothing to ATM IV for forecasting the range (R² 0.214 to 0.216, 1,285 days, R104).
- **How an option moves in a big candle:** in a 5-minute candle with a 50+ point body the BANKNIFTY ATM option moves
  about ±5% (monthly series) or ±8.5% (weekly); the straddle barely moves (BIG_BAR_OPTIONS, 1,428 / 1,923 candles).

### 1.5 Who wins

- From NSE's end-of-day participant data (2012-2026): brokers' own desks (pros) made money in 13 of 15 years, almost all
  of it from index options (about +Rs 8,900 crore), and are net option writers on 92% of days. Clients (mostly retail)
  lost about Rs 7,600-11,600 crore on index options and made money in only 7 of 15 years; most of that loss came at low
  VIX (-Rs 9,661 crore over 1,949 days). FIIs made theirs in index futures on trend days (OF1). This end-of-day view
  misses intraday losses and costs, so the real client loss is bigger.
- New longs opened on falling-premium days lost about 23% of the premium by expiry (NIFTY 1,38,164 strike-days) (OF1).
- Following FII or client positions does not work: 15 of 36 rules passed on 2012-25, 0 of 15 held on the next 12
  months, and 13 flipped sign (OF1).

### 1.6 How our money-making arms actually make money

| arm (signal on the index, buys 1-ITM) | design | final (1 Apr - 13 Oct 2026) | source |
|---|---|---|---|
| Liquidity 15+5 (BANKNIFTY) | 881 trades, +Rs 2,57,249 | 357 trades, +Rs 11,155; -Rs 51,376 without its top 5 | R101 |
| Breadth Donchian R72 (5 indices) | 3,071 trades, +Rs 2,16,929 | 1,202 trades, +Rs 1,71,366 | R101 |
| Donchian 20 R51 (5 indices) | 4,498 trades, +Rs 9,998 | 1,781 trades, +Rs 81,845 | R101 |
| R52 | +Rs 2,90,573 | +Rs 48,612 | R101 |
| all four together | +Rs 7,74,750, max drawdown -Rs 4,44,695 | +Rs 3,12,978, max drawdown -Rs 1,70,942 | R104 |

- **A few big days pay for everything:** Liquidity's 36 winners of Rs 5,000+ made Rs 3,38,862 while the other 845
  trades lost Rs 81,613 together (R100).
- **The opening break is the money:** removing the 09:15-09:19 break cuts R72 by Rs 1,49,195 in design and Rs 76,078 in
  the final (R101).
- **Breadth agreement matters:** Breadth signals with 4-5 positions made +Rs 3.45 lakh; signals with 1-3 positions lost
  Rs 1.28 lakh (R84). Breadth agreement cut losses on break trades by 62% in design and 76% in the holdout (R89B).
- **A higher win rate is not more profit:** a variant winning 48% vs 44% made -Rs 54,136 in design (R101 L2); in R48,
  rules winning 81-88% on the holdout still lost because the average loss was 7-12x the average win.
- **Warning for the 26 Oct decision:** at 1 lot the four arms' drawdowns already exceed Boss's -Rs 40k account stop
  (Liquidity alone -Rs 61,062 in the final, R100; R72 alone -Rs 61,884, R101), and fixed-rupee risk sizing takes no
  trades at the 1-lot minimum (R104).

## 2. What to check before believing any result

Each item below decided at least one study. If a result has not passed all of them, it is not a result yet.

1. **Same contract at entry and exit.** The rolling ATM series switches strike as the index moves. In R59, 89.5% of
   trades and 100.1% of the profit came from strike switches or expiry rolls; on the contract actually held it was
   -Rs 3 a trade (6,509 trades). R45's "scalp" went from +Rs 264 to -Rs 65 a trade (2,826 trades); R46's AUC 0.78 fell
   to 0.50.
2. **Stale far-strike prices.** The lake keeps ATM ±10 strikes; a far leg the index moves away from stops printing. R103's
   six selling passes (+Rs 1.9 to 5.4 lakh) became -Rs 2.9 to 3.3 lakh once repriced. Check the share of exit legs
   without a print.
3. **Bad rows in the lake.** Foreign-underlying rows and stray prints (R47) moved R51's pass by about 16%, almost all on
   FINNIFTY. R43-R50 never applied the cleaning, so treat their FINNIFTY / MIDCPNIFTY / SENSEX positives as unproven.
4. **Real costs, dated.** About Rs 70-76 a round trip in charges today (NIFTY lot 65, BANKNIFTY 30; STT 0.15% of the
   sell premium since 1 Apr 2026), plus spread and slippage; stops slip about 2x. Many older studies used today's rates
   on history (conservative) or Rs 40 a trip (optimistic). Re-entries made about Rs 0 before charges, and charges were
   36% of winners' gross (R14).
5. **Net without the top 5 trades.** One SENSEX call on 2 Apr 2026 (26-37x) was the whole final profit of five R50
   rules. Liquidity's final is -Rs 51,376 without its top 5. Always report it.
6. **The holdout, read once, and a fresh one when it's been read.** Design stars that reversed: Larry Swing's exits
   +Rs 14.1 lakh to -Rs 3.3 lakh, Sadowski's RSI(2) +Rs 14.95 lakh to -Rs 8.79 lakh (R104); R82 U2 q 0.032 on 564
   trades then -Rs 5.84 lakh on 234; R43's Donchian +Rs 5.83 lakh then -Rs 67,696. The 1 Apr - 13 Oct 2026 window has
   been read about 20 times; only paper days from 14 Oct 2026 are truly unseen.
7. **A random-entry twin, built right.** Many rules did no better than random minutes (R21, R24, R28, R43, R91, R93). Same-
   side twins leak the day's direction to breakout rules (R52); same-day twins look very profitable on long holds
   (R89B); use twins drawn after the trigger.
8. **Many tries, one winner.** Benjamini-Hochberg across everything tried. R47 searched 387,392 variants and none
   survived; R104's 227 tests produced 0 passes even though 22 were positive in both periods.
9. **One index carrying the result.** MIDCPNIFTY carried 72-95% of the final profit of H37-10 and Solo 2.0 A2 (R52,
   R57); FINNIFTY carried R51's Donchian. Ask whether it works on more than one index.
10. **Regime breaks.** BANKNIFTY, FINNIFTY and MIDCPNIFTY have been monthly-only since Nov 2024 (premium about 455 to
    720 on BANKNIFTY, straddle 1.49% to 2.78% of spot). Lots changed (NIFTY 25 / 75 / 65); STT changed (0.0625% /
    0.1% / 0.15%). Results from before must be re-checked on the new market.
11. **Look-ahead and repainting.** R95's divergence made +Rs 12.0 lakh at the label candle and -Rs 3.4 lakh when the
    signal was actually known. Run the look-ahead guard on every rule.
12. **Bar fills and model prices.** R90 made +Rs 3.30 lakh with bar fills and no costs, and -Rs 1.19 lakh as a real 1-ITM
    option flat by 15:15. R96's NR7 went from +Rs 1.84 lakh on modelled exits to -Rs 4,067 on real prints.
13. **Bugs that inflate.** R104's event straddle was priced past its own expiry (+Rs 2.04 lakh became -Rs 7,709).
    A surprising profit is a bug until proven otherwise.
14. **The account stop as a hard limit.** A rule whose drawdown at 1 lot is beyond -Rs 40k cannot run as it is, whatever
    its net.

## 3. What to ignore (no edge for an index option buyer after costs)

| idea | evidence |
|---|---|
| Indicator crosses and settings (EMA, RSI, MACD, Supertrend, CCI, ribbons) | F1 0 of 20; R29 0 of 55; R30 0 of 30; R95 0 of 288 |
| Candle shapes and patterns, "reversal" candles, wick levels | R87 0 of 4,049 cells; R28b 0 of 378; books R104 0 of 82 pattern tests |
| ORB family, opening-range variants, re-entries | R3 every version loses; R49 0 of 336; R92 0 of 257 |
| Support/resistance, previous-day high/low, morning ranges | R20b/R20c/R22; R89 0 of 180; R89B 0 of 45 |
| Max pain, max OI, PCR, OI walls, GEX as a direction or filter | OF1 0 of 12; R28 0 of 10; IV1 lost on all 5 indices |
| Following FIIs, clients, big-player footprints, unusual OTM volume | OF1 0 of 15 held; R86 0 of 23; R86b 0 of 8; R86c unusual volume -Rs 1.71 lakh on 215 trades |
| Smart-money / ICT sweeps, BOS structure | R91 0 of 63; R90 0 of 99 |
| Zero-to-hero / cheap expiry tickets | 0 of 198 hero rules (R21-R50); R54 0 of 24; R55 0 of 30; R98 every exit loses |
| Direction models (ML, neural nets) | AUC 0.47-0.54 everywhere (B2, R41, R57, R88) |
| Long straddles on a predicted big move | R89 0 of 18 (2,214 out-of-sample trades, -Rs 4.4 lakh); LONG_VOL 0 of 66 |
| Profit locks, give-back trails, early exits on the arms | R18 0 of 60; R19 0 of 12; R19b; R100 0 of 15; R81 0 of 66 |
| Entry filters and daily filters on the arms | R15 0 of 5; R80 0 of 10; R79 0 of 6; R101 0 of 20; R104 0 of 20 |
| High-win-rate fits | R48 0 of 10 (100% design winners lost on the holdout) |
| Premium-chart signals (a rule read from the option's own chart) | R45 -Rs 170 a trade on 1,250 fixed-contract trades |
| Book rules as written | R104 0 of 227 |
| Hedged option selling (out of scope for buying, for completeness) | R103 0 of 24 |

## 4. Best practices for buying index options

1. **Signal on the index, buy the option** (CLAUDE.md). Every working arm does this; every premium-chart signal failed.
2. **Buy 1-3 strikes in the money, not far OTM.** 1-3 ITM matched or beat ATM, 5 ITM was worse (ADVANCED: Rs 12.4k vs
   Rs 11.0k on 292 trades; 2 ITM +Rs 29.1k vs ATM +Rs 20.6k in the out-of-sample year). Far OTM on expiry day loses
   half or more 90-95% of the time (R24).
3. **Trade the morning and the big days.** The opening break (09:15-09:19) is the arms' money (R101); entries after
   11:05 rarely run (16%, R23); trade when the expected range is big (VIX, the first hour) and stand aside when it is
   not.
4. **Size stops and targets from the expected range,** not fixed points: VIX, the last candle's size and the morning
   range forecast the next hour's size (R16, R88). A 15% premium stop held up; 10% failed a year; 3% sits inside normal
   noise (LIQUIDITY_STOP, BOTH_SIDES).
5. **Let winners run; cut nothing early.** Every earlier exit, lock, trail or give-back cut the few big days that pay for
   the arm (R18, R19, R19b, R81, R100). The only neutral change found: breakeven once the trade is +20% (+Rs 3.3k on 16
   changed trades, R100).
6. **Cap re-entries.** One re-entry per side a day turned the ORB holdout from -Rs 1.89 lakh to -Rs 0.18 lakh (p < 0.001,
   R14). It limits damage; it is not an edge.
7. **Want agreement across indices.** A break that most indices confirm loses far less (R84, R89B).
8. **Respect the clock.** Expiry square-off 15:05 (later exits added nothing reliable, R54); the night arm sells at 09:16
   (holding past it is a coin flip, R27); keep each arm's own tested square-off (15:10 or 15:15).
9. **Keep the paper record clean and labelled.** Only paper days from 14 Oct 2026 are unseen data; they are the only fair
   test left for the arms.
10. **Size so the account stop can hold.** At 1 lot the arms' drawdowns are already larger than -Rs 40k; the account stop
    needs fewer arms at once or fewer indices, not bigger size.

## 5. What the cross-check corrected (all added to FINDINGS.md as dated notes)

- Big-candle levels (HOLD_LEVELS): the matched baselines are 51% and 47%, not 47% and 38%; the edge is smaller.
- The sideways filter did not help "in every pairing" (one pairing worse, TREND_FILTER).
- LIQUIDITY_WAIT5: 0 of 84 versions, not 0 of 88.
- "Defined-risk selling not yet tested": it has been, 0 of 24 (R103).
- R23's "verdict waits": settled by R49 (finalists lost).
- R47: C4 was not the only candidate positive in both periods (C6 and C7 too; none passes).
- R51: the FINNIFTY +Rs 49,040 / -Rs 351 split is not in the study's files; the whole-design net was only +Rs 8,281
  cleaned and negative without its top 5.
- R52 H37-10: its final drawdown (-Rs 46,363) breaks the account stop.
- R86c: BANKNIFTY "keeps falling" after an unrecovered gap down was design-only and did not hold in 2025-26 (R86d).
- R87: "stop runs above the day's high are followed by more upside" did not hold out of sample (+0.129 to -0.001 ATR).
- MB1: size held on 4 of the 5 design passes, not 5 of 6.
- R98: selling was later tested for ATM defined risk (R103); cheap-ticket selling is still untested.
- R102: "the open" is the first five minutes; the gap-fill numbers are up-gaps; the client-futures rule reversed rather
  than going to zero.
- R104: the SST and inverse head-and-shoulders "leads" are daily overnight futures results; as intraday option buys they
  lost. For option buying there is no R104 lead.

## 6. Still open (not fixed here)

- `research/obuy/config.py` LOT_SCHEDULE (a fallback lot table) is stale: BANKNIFTY ends at 35 and has no return to 30
  from Jan 2026; MIDCPNIFTY lacks its 140 row; SENSEX's switch date differs from R49. Studies that fell back to it
  (r21, r25, r29, of1, r47) may be off in rupees; verdicts unlikely to flip. Fixing it changes old reproductions, so it
  needs its own change.
- Market hours: R102 records F&O hours of 09:15-15:45 from 7 Dec 2026, with the expiring contract stopping at 15:30 on
  its expiry day. This was not verified online here; CLAUDE.md's 15:40 / 15:05 times must be reviewed before 7 Dec.
- Which indices the paper and live accounts can actually trade (SENSEX is dropped in R57 but traded by R52 / R84).
- R43-R50's FINNIFTY / MIDCPNIFTY / SENSEX positives were never re-run with the lake cleaning.
- FT3 (R98's replayed cheap-ticket rule, +Rs 1,805 a ticket on 478) was never checked for stale exit prints and comes
  from an 8,280-variant search (SPA p 0.96).
- R92's "index on the right side 64-72%" is measured over each trade's own hold with ladder exits; it is shaped by the
  exit rule and is not a direction edge.
- Two reported figures could not be traced: R51's FINNIFTY split and R18 / R80 having identical trade counts in
  different windows. `ml_long.py` has no write-up to check its numbers against.
- No study yet treats the -Rs 40k account stop as a hard limit on the arms together.

Sources: `research/FINDINGS.md` and the study files named in each line. Cross-check method: four reviewers each took a
quarter of FINDINGS.md, opened every cited study and compared the numbers; their mismatches were re-checked before
any correction was written.
