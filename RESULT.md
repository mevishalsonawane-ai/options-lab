# Liquidity give-back: what happened on 22 Oct, and what changes

Boss, 22 Oct: *"do something for liquidity strategy, it went up to 4.5k, and settled at -1k, this is not acceptable for me"*.

## In short

- **The trade:** Liquidity 5m MIDCPNIFTY put, 1 lot of 120, today (22 Oct).
  - Bought 09:20:02 @ 172.95.
  - It rose to about +Rs 4,500 (option near 210, +22%), then fell back.
  - Sold 09:40:02 @ 164.00 by the **20-minute time stop**: -Rs 1,074 before charges, **-Rs 1,168 after**.
- **Why nothing kept the profit:** Liquidity has **no profit lock**. Every lock tested on it so far cost more than it
  saved, so it was never switched on.
  - The time stop asks one thing at minute 20: is the trade at least +5%? It was not (164 against 181.60), so it sold.
  - None of Liquidity's exits looks at how high the trade went before.
  - Your Rs 500 trail switch was almost certainly off. The diagnostics name no trailed trade. With it on, the trade
    would have been sold near +Rs 4,000: the watch checked the stops every 15 s all morning.
  - **This was not a bug.** The rules did what they say.
- **Two bugs found and fixed** in the Liquidity exit path. Neither one touched this trade.
  1. **One index's failed candle read skipped every Liquidity position's exits on that pass.** That meant no index
     stop, no target and no time stop, for other indices too. Now a failed read affects only that index's index exits.
     Its positions keep their time stop, the -15% stop and 15:10.
  2. **After a restart or a stall longer than 5 minutes, a paper lock or trail missed the highest price reached in the
     gap.** The live stream keeps only 5 minutes of prices. Now the first check after such a gap also reads the minute
     candles once, so a lock or trail earned in the gap is set (and sells if the price already fell back to it). Paper
     positions only for now.
- **New research (R100): 7 ways to keep a big open profit were tested on 1,238 past Liquidity trades. None passed.**
  **So nothing changes on the paper or live Liquidity books.**
  - Trades like today's are rare: 9 of 881 design trades went +20% and still lost (Rs 15,030 together), and 5 of 357
    in the final period.
  - Every lock saves those trades. Every lock but one also sells winners early, and that costs much more than it saves.
- **What you can switch on knowingly** (nothing is switched on for you):
  - **Breakeven once +20%** (G1) is about free either way: +Rs 3,287 in design, -Rs 2,641 in the walk-forward year,
    +Rs 1,341 in the final period. It changes about 1 trade in 55.
    - **The effect:** a trade that reached +20% can no longer end as a loss. Today's trade would have closed at about
      Rs 0 instead of -Rs 1,168.
    - **Not proven:** random early exits did as well. Say the word and it can be built as a paper-only switch, off by
      default, labelled "did not pass research, your choice".
  - **Keep your Rs 500 trail switch off.** On the same trades it would have cost **Rs 1.79 lakh** over 17 months (881
    trades) and Rs 45,326 in the final period (357 trades).
- **BANKNIFTY's 26 Oct decision:** its plain-rules record is untouched (no rule changed, nothing new is labelled).

## The trade, minute by minute

Source: your diagnostics of 22 Oct 09:48 (pasted in the chat). The Drive lake ends 19 Oct. The phone's 22 Oct recording
and its daily diagnostics file were not read here: the diagnostics download was refused by this session's permission
check, and the recordings are sealed with your passphrase. So the exact minute and price of the peak come from your
+Rs 4,500, not from data.

| time | what happened |
|---|---|
| 09:15-09:20 | MIDCPNIFTY's 5-minute bar breaks a liquidity level downward (a pool on a swing low): a put signal on the index |
| 09:20:02 | bought 120 MIDCPNIFTY put (1 strike in the money) @ 172.95; -15% stop resting at 147.00 |
| about 09:25-09:35 | the put rises to about 210 (+22%, about +Rs 4,500 open). The market then turns up: Nifty +0.38% from 09:34 to 09:44 |
| 09:40:02 | 20 minutes after entry the option is 164.00, under +5% (181.60): **time stop**, sold @ 164.00 |
| result | -Rs 1,074 before charges, -Rs 1,168 after |

The exits in force for this book were:
- the -15% resting stop (147.00);
- the index stop, 8 points beyond the broken level;
- the 20-minute time stop (+5% or out, decided once);
- the next liquidity level (target);
- a completed bar closing back through the level (failed break);
- a new level forming;
- 15:10.

The strategy lab's lock variant was not in force (none was promoted), and your Rs 500 trail was almost certainly off.

The same trade under each tested rule, assuming the price fell smoothly from 210 (before charges):

| rule | sells at about | P&L |
|---|---|---|
| own exits (what happened) | 164.00 | -Rs 1,074 |
| breakeven once +20% (G1) or +15% (G2), step lock (G3) | about 173.5 | about Rs 0 |
| keep half the gain once +20% (G4); keep half once +Rs 3,000 (G7) | about 191.7 | about +Rs 2,250 |
| keep a quarter of the gain in the first 30 minutes (G6) | about 182.3 | about +Rs 1,125 |
| your Rs 500 trail switch | about 206.3 | about +Rs 4,000 |

On today's trade every lock looks good. History shows the cost: the same locks sell the big winners on their first dip.

## Research R100 (pre-registered before any number; full write-up `research/HUNT_R100_LIQUIDITY_GIVEBACK.md`)

**Setup:**
- Liquidity's own trades: BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5. The signal is on the index; locks are on the
  option price.
- Real charges and slippage, 1 lot.
- Design: 20 Nov 2024 - 31 Mar 2026, 881 trades; its last year, Apr 2025 - Mar 2026, is the walk-forward check.
- Final: 1 Apr - 13 Oct 2026, 357 trades, read once. Earlier studies had already read it, so it is weak evidence.
- Each rule was compared with random early exits (500 twins), with Benjamini-Hochberg across the 7 rules.

| rule | design (881) | walk-forward (657) | final (357) | trades changed | passed? |
|---|---|---|---|---|---|
| **own exits (today)** | **Rs 2,57,249** | **Rs 2,38,064** | **Rs 11,155** | | |
| G1 breakeven once +20% | Rs 2,60,537 | Rs 2,35,423 | Rs 12,496 | 16 / 8 | no (closest; about neutral) |
| G2 breakeven once +15% | Rs 2,51,939 | Rs 2,28,153 | Rs 972 | 29 / 12 | no |
| G3 step: +15% breakeven, +30% keep 40%, +50% keep 60% | Rs 2,26,824 | Rs 1,85,182 | -Rs 18,091 | 47 / 20 | no |
| G4 keep half the gain once +20% | Rs 1,69,617 | Rs 1,39,859 | Rs 13,513 | 57 / 16 | no |
| G5 index back to the entry price once 2 index stops in profit | Rs 1,91,840 | Rs 1,60,429 | Rs 3,425 | 152 / 60 | no |
| G6 keep 25% / 50% after 30 min / 75% after 60 min, once +20% | Rs 2,28,546 | Rs 2,09,119 | Rs 4,656 | 58 / 24 | no |
| G7 keep half the profit once +Rs 3,000 a lot | Rs 1,47,120 | Rs 1,42,478 | Rs 6,717 | 88 / 21 | no |
| your Rs 500 trail (reference) | Rs 77,904 | Rs 75,171 | -Rs 34,171 | 324 / 107 | very costly |

Both sides, design:

| rule | saved (trades, Rs) | winners cut short (trades, Rs) |
|---|---|---|
| G1 | 9 trades, Rs 14,800 | 7 trades, Rs 11,512 |
| G4 | 22 trades, Rs 47,928 | 35 trades, Rs 1,35,560 |
| your Rs 500 trail | 171 trades, Rs 2,58,188 | 153 trades, Rs 4,37,533 |

## What changed in the app (this push)

- **Exit fix 1:** a failed index read no longer skips the other Liquidity positions' exits. Its own positions keep their
  time stop. The diagnostics say it once a minute.
- **Exit fix 2:** after a gap longer than the stream keeps (a restart mid-trade, a stall), the first regular check reads
  the minute candles' highs once. A lock or trail then counts the high reached in the gap.
  - It applies to every laddered or trailed paper position (ORB arms too), and only ever sells sooner.
  - It is never done on the stream's fast check, and the read never holds the check more than 3 s; a failed read is
    tried again once a minute.
  - A target is still judged as before (no late "target" sale).
  - Live positions are unchanged (TODO X7).
- **What's New:** one entry ("Liquidity exits keep working when one feed fails").
- **Unchanged:**
  - Liquidity's rules on paper and live;
  - the Rs 500 trail switch (still off);
  - every switch, PIN and safety rule;
  - BANKNIFTY's plain-rules record.
- **Found, not changed here** (reported for a later fix):
  - the Liquidity exits read the index candles over the network inside the arms' lock, so a slow feed delays the other
    checks;
  - a live position under the lab's lock variant reads no candle high;
  - the time stop can be decided on an old candle price of a thin option;
  - the diagnostics' per-bar line shows the BANKNIFTY range and bar for every Liquidity book (MIDCPNIFTY's entry today
    reads "bar 09:15 close 54704.4"); it is display only.

## Checks

- **Research:**
  - R100 tests: 51 passed (look-ahead guard and edge cases).
  - `research/brain/tests`: 183 passed.
  - Features catalogue: up to date.
  - Experiment log validator: OK (7 new entries `r100-liquidity-g1..g7`).
- **App and engine:**
  - New tests:
    - engine: the gap rule, and the time stop with no index bars;
    - app (Robolectric, market clock pinned): the time stop still sells when the index feed fails, another index's
      position still exits, and the candles' high after a gap.
  - The engine and core test runs, and CI's results, are in the session report.
