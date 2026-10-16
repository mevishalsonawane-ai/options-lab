# R63: sell when the trend turns against the trade - every strategy (16 Oct 2026)

Boss, 16 Oct: "Can we ask Solo/agents to check the current trend, and if the trend changes, sell the orders" ... "It
should work for all the arms and orders" ... "All the strategies".

Pre-registered in `research/hunt/r63/PREREG.md` (commit c6712c4, before any result). Code and design results frozen in
536f5a4 before the one final-test read (16 Oct 2026 04:52 UTC, `results/FINAL_READ`). Code: `research/hunt/r63/`. All
tables: `results/report.md`; numbers: `results/design.json`, `results/final.json`. Data: 20 Nov 2024 onwards only.

## Verdict for Boss (read this first)

**One rule passes, on one strategy: T3 ("two 5-minute closes in a row beyond the day's VWAP") on the Hero Rs 1,000
ticket.** On every other strategy, and on all strategies pooled, selling when the trend turns does **not** make money
reliably - it saves about as much on losers as it gives up on winners, and cutting the same trades at random times does
better. So:

| rule (signal on the index) | Liquidity 15+5 | R51 Donchian 20 | R52 Open outside value | Solo (midday) | Solo 2.0 | Hero Rs 1,000 ticket | all pooled |
|---|---|---|---|---|---|---|---|
| T1 15-min close crosses EMA20 | fail (0 changed) | fail (+Rs 5,011, 76 ch.) | fail (+Rs 4,911, 13 ch.) | fail (+Rs 370, 9 ch.) | fail (+Rs 3,583, 7 ch.) | fail (+Rs 2,303, 36 ch.) | fail (+Rs 16,178, 141 ch.) |
| T2 15-min Supertrend(10,3) flips | fail (0 ch.) | fail (0 ch.) | fail (+Rs 3,316, 2 ch.) | fail (+Rs 1,011, 1 ch.) | fail (+Rs 3,316, 2 ch.) | fail (+Rs 1,152, 10 ch.) | fail (+Rs 8,797, 15 ch.) |
| T3 two 5-min closes beyond VWAP | fail (+Rs 3,039, 46 ch.) | fail (-Rs 3,028, 324 ch.) | fail (+Rs 13,056, 223 ch.) | fail (+Rs 2,329, 12 ch.) | fail (-Rs 20,716, 85 ch.) | **PASS (+Rs 14,730, 74 ch.)** | fail (+Rs 9,410, 764 ch.) |
| T4 5-min Donchian-10 opposite break | fail (-Rs 10,686, 18 ch.) | fail (+Rs 8,156, 39 ch.) | fail (+Rs 2,383, 28 ch.) | fail (+Rs 20,044, 23 ch.) | fail (+Rs 7,289, 9 ch.) | fail, q only (+Rs 13,130, 65 ch.) | fail (+Rs 40,316, 182 ch.) |

(Final test 1 Apr - 14 Oct 2026, read once; "+Rs x, n ch." = the net change after costs on the n trades the rule changed.
Every cell's design, walk-forward, twins, drawdown and worst day are in the tables below and in `results/report.md`.)

**What the app does with it (section "In the app" below):** a Trend watch row that watches every open position and
pending order of every source. It only writes notes and (if Boss wants) quiet alerts, except on the **Hero ticket's paper
legs**, where T3 now sells the leg on paper (logged in the Autopilot change log, one tap undoes it). Live never acts.

## The question and the rules

Each strategy's trades were replayed exactly as the app runs them (same entries, same strikes, same stops, targets, time
stops and square-offs), and a trend-turn exit was added on top: whichever comes first wins. Every rule reads the
**underlying index only** (the same 1-minute spot series the strategy reads its entry from); the option is only what is
held and sold. A rule acts only on a completed bar, at least 5 minutes after the fill, and sells the held contract at the
next minute's open with the R51 engine's real fills and dated charges (Hero: R54's own fill and sell model).

| rule | signal source | what "turned against" means |
|---|---|---|
| T1 | index, 15-minute bars | close crosses EMA20 of the 15-minute closes (down for a call, up for a put) |
| T2 | index, 15-minute bars | Supertrend(10, 3) flips |
| T3 | index, 5-minute bars + the day's average price | two 5-minute closes in a row on the far side of the session VWAP. **The index has no volume**, so this "VWAP" is the equal-weight mean of the day's 1-minute typical prices (a TWAP) |
| T4 | index, 5-minute bars | a close beyond the lowest low / highest high of the previous 10 bars |

The control: for every trade a rule changed, 500 twin sets cut the same trade at a random minute between 5 minutes after
the fill and its plain exit. This is a hard control: random early cuts of trades that later turned against the position
save money too (they are the trades that went wrong), so a rule has to time the exit better than chance, not just exit.

## Strategies replayed (6), and every other source

| strategy (app) | replayed with | final-test trades (sessions) | design trades |
|---|---|---|---|
| Liquidity 15+5 (BANKNIFTY 15m+5m, FINNIFTY 30m+5m, MIDCPNIFTY 15m+5m) | R51 `fam_liq`, R51 clean store | 357 | 881 |
| R51 Donchian 20 (5 indices) | R57 S2, R57 store | 1,807 | 4,551 |
| R52 Open outside value (5 indices) | R57 S3, R57 store | 292 | 712 |
| Solo (midday) | R57 `plain_solo` | 36 | 117 |
| Solo 2.0 | the app's own A2.json over R57's S1-S4 candidates | 128 | 310 |
| Hero Rs 1,000 ticket (NIFTY, SENSEX expiry days, 12:30, trail after 2x, 15:05) | R55 cell 12:30/15:05/T2, R54 store | 107 tickets (55 expiry days) | 248 (134) |

Sanity checks: re-pricing every plain trade with the trend exit switched off reproduces it to the paisa (checked in the
code on every trade); Solo 2.0's final 128 trades match R57's 128; open outside value's 292 final trades are close to R52's 294 (R52
priced it with its own engine and store; the app runs it as R57's S3, with the Solo layer as its stop).

Every source in the app, and how it is covered:

| source (app) | paper / live | replayed? | Trend watch in the app |
|---|---|---|---|
| Hero Rs 1,000 ticket | paper only | yes - **T3 PASSED** | **acts on paper**: sells a leg on T3 (its own switch, on; change log; one-tap undo) |
| Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY) | paper; BANKNIFTY live with PIN | yes - all fail | shadow note + optional alert (BANKNIFTY labelled: its plain-rules record stays separable for 26 Oct) |
| R51 Donchian 20, R52 Open outside value, Solo 2.0 | paper only | yes - all fail | shadow note + optional alert |
| Solo (midday) | paper (Jarvis's) | yes - all fail | shadow note + optional alert |
| MCX paper arms (NG evening, SILVERM morning (parked), MCX 12-month trend, US-night silver) | paper only | no (MCX minutes start Aug 2025, closes only; US-silver needs a Yahoo cache; the 12-month trend holds for weeks) | shadow note on the MCX future's own bars |
| Hidden arms (ORB, ORB Fresh, ORB Sweep, Range Fade, old Hero, Night, VIX divergence, forge ideas, Pine auto-trade, shadow arms, scheduled baskets, Jarvis's news trades) | hidden since 15 Oct | no (switched off; ORB family failed R49; Night sells at 09:16) | if restored, their paper positions are watched like any other (shadow) |
| Boss's manual orders (paper and live, NSE / BSE / MCX), stock-option tools | both | no fixed rules | shadow note; live only with the live switch on (off by default) |
| Jarvis's orders on request, the Telegram Signal-tester paper arm | paper (Jarvis's live orders go through order review) | no fixed rules | shadow note; acting would need the POOLED rule to pass - it did not, so their switches stay locked off |
| Pending entry orders (any source) | both | - | shadow note "would cancel" only; never cancels |
| Protections, expiry square-off, missed-lock sweep | both | they are exits | not watched (they are already closing) |
| Watchlist | shadow, no orders | - | nothing to watch |
| Gold flavour (XAUUSD paper books) | paper, Gold build only | no | not watched by this change: those books keep their own stop and profit lock; the Gold build has no index feed for these rules |
| Crypto | nothing in the app trades crypto | - | - |

## The pass: T3 on the Hero ticket

| | design (134 expiry days, 248 tickets) | final test (55 expiry days, 107 tickets) |
|---|---|---|
| tickets the rule sold early | 178 | 74 |
| Rs saved on losers | +Rs 58,315 | +Rs 20,659 |
| Rs given up on winners | Rs 16,488 | Rs 5,929 |
| **net change after costs** | **+Rs 41,827** | **+Rs 14,730** |
| net change without its 5 best | +Rs 36,928 | +Rs 11,295 |
| plain net -> with T3 | -Rs 59,286 -> -Rs 17,459 | -Rs 9,680 -> +Rs 5,050 |
| max drawdown plain -> with T3 | Rs 59,853 -> Rs 18,659 | Rs 13,429 -> Rs 8,162 |
| worst day plain -> with T3 | -Rs 1,999 -> -Rs 1,235 | -Rs 1,752 -> -Rs 939 |
| twin p (500 random cuts) | 0.002 | 0.002 (twin mean +Rs 8,187) |
| BH q (28 cells) | - | 0.056 |
| walk-forward quarters | +8,458 / +6,297 / +8,352 / +6,325 | every final month positive (+Rs 535 to +Rs 3,347) |
| per index (final) | - | NIFTY 38 changed, +Rs 8,377; SENSEX 36 changed, +Rs 6,354 |

Why it works here and nowhere else: the Hero ticket buys a cheap call AND a cheap put at 12:30 and (in the app's
setting) has no stop until a leg doubles. When the index moves one way, the losing leg used to be held to 15:05 and decay
to almost nothing. T3 sells that leg once the index has closed twice on the far side of the day's average price, while it
still has some premium left. It does not make the ticket a winner on its own (design still -Rs 17,459) - it makes it lose
much less. The other strategies already have stops, targets and short time stops, so a trend exit mostly duplicates them
or cuts winners.

Caveats, plainly:

- **Thin:** +Rs 14,730 on 74 changed tickets is about Rs 200 a ticket. q is 0.056 (passes 0.10, not by a lot).
- **Not pristine:** R50, R54 and R55 read these expiry days; the Hero ticket exists because of them.
- **Only for the setting tested:** trail "after 2x", "trail only", 12:30, 15:05. With another trail start or a 2x / 3x
  target, the app only writes a shadow note for that ticket.
- **T4 is close behind** (+Rs 13,130, p 0.034) but fails BH (q 0.47). It is not used.

## Why the rest fail (final test)

- **Liquidity 15+5:** its 20-minute time stop closes most trades before a 15-minute rule can act (T1 / T2: 0 trades
  changed). T3 changed 46 trades: +Rs 3,039 net, but Rs 17,427 saved and Rs 14,388 given up; lost Rs 31,008 in design.
  BANKNIFTY alone: T3 18 changed, +Rs 2,678. Its plain-rules record is untouched.
- **R51 Donchian 20:** T3 changed 324 trades and lost Rs 3,028 (saved Rs 1,04,189, gave up Rs 1,07,217). T1 and T4 were
  positive in the final test but negative in design and walk-forward, and random cuts did better (p 1.0).
- **R52 Open outside value:** T3 cut 223 of 292 trades: +Rs 13,056 in the final test but -Rs 1,59,911 in design. It
  throws away the 60-minute hold the rule's edge comes from.
- **Solo (midday)** and **Solo 2.0:** T3 on Solo 2.0 lost Rs 20,716 (saved Rs 51,538, gave up Rs 72,254). Solo (midday)'s
  T4 made +Rs 20,044 on 23 changed trades in the final test after -Rs 46,400 in design: noise.
- **All strategies pooled:** no rule passes. The best pooled final number, T4 +Rs 40,316, follows -Rs 47,980 in design;
  T3 pooled is +Rs 9,410 final after -Rs 2,33,670 in design.

The earlier evidence agrees: `research/CHANDELIER_EXIT.md` (exit on the opposite signal lost on 5-minute bars in both
years) and R19b / R19c (Liquidity give-back trails cut big winners and lost to random early exits).

## Honest notes

- Entries are held fixed: a trade sold early never creates an extra entry. In the app a strategy that may re-enter
  could do so; that is not priced (it matters only for the Hero ticket, which takes one ticket a side a day and does not
  re-enter).
- Solo 2.0's entries come from the app's model file, fitted on every row to 14 Oct 2026, so its picks are in-sample;
  that can only flatter the plain exits.
- The Hero ticket is priced with R54's engine (limit buy, late-minute spread, stray-print guard), not R51's, because that
  is how the ticket is bought; the trend exit uses R54's market sell at the act minute's open.
- In the app the Hero exit fires from the Trend watch's worker round after the 5-minute bar closes, so the sale can come
  up to a minute or so after the research's next-minute open.
- Sample sizes: every number above is on the trades and days listed with it; Liquidity's T1 / T2 have 0 changed trades.

## In the app (what changed)

- **Trend watch** (Home → Strategies, one row: name, one status line, its switch; tap the name for the details and the
  per-source switches, then OK). It runs in Jarvis's worker round, about once a minute, never on the order or tick path.
- **Paper:** the Hero ticket's legs are sold on T3 (its own switch, on; each sale is in the Autopilot change log with one
  tap to undo - the undo switches the Hero trend exit off; a sold leg is not bought back). Everything else: a note "trend
  turned against <source> <symbol> at hh:mm: would sell at <premium>", and a quiet alert only if Boss switches alerts on.
  Non-replayed sources (manual, Jarvis, Telegram, MCX, restored arms) cannot act: the pooled rule did not pass.
- **Live:** never acts. Notes and alerts only, behind "Live positions too", off by default.
- **Pending entry orders:** a note "would cancel" only.
- The watch uses the pooled design-best rule for its notes (T2, the Supertrend flip, decided on design only) and T3 for
  the Hero ticket.
- Jarvis answers "has the trend turned on my trades?"; nothing about the account is said on a locked phone.
