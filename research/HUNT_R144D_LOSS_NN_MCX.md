# R144d: why S5 Trail, CISD + network and the four MCX arms lose, and whether we can skip the losers

1 Nov 2026. Research only: **nothing in the app or any arm changes.** Boss: *"Find all the lost trades in indian market
from our study for current arms, and find what all makes it lose money ... find patterns for each arm, so it could
help us to ignore those trades."* One of four parallel studies (R144a-d). Pre-registration:
`research/hunt/r144d_loss_nn_mcx/PREREG.md` (pushed before any number). The filters were chosen on design only and
committed (`results/filters_frozen.json`, commit `70d80b5`) before any unseen-years or final number was read.

## The answer, first

1. **No skip filter passed. 0 of 18** (3 per arm, chosen on design, tested on the unseen years and the final months).
2. **One came close: S5 Trail, "skip the morning when ATM IV is high"** (top fifth of design, IV above 20.8).
   - It passed every lose-less gate in both test periods and beat random skipping:
     - unseen years: p 0.026;
     - final months: p 0.016.
   - It failed only the multiple-testing gate: q 0.135 against 0.10, over the 18 filters.
   - Win rate: 59% → 64% in the unseen years and 79% → 88% in the final months.
   - It is worth a **shadow log on new days**. Nothing is built.
3. **Every arm has a clear flaw**:

| arm | trades (unseen / design / final) | losers | where the lost money goes | the main flaw |
|---|---|---|---|---|
| **S5 Trail** | 430 / 110 / 33 | 41% / 43% / 21% | **96-99% is the -Rs 2,000 stop** | Its profit is a few days in 2024. Design made only Rs 6.5k, and the best 5 days were 5.9 times that. 2025 lost Rs 14.8k (38 trades). High-IV mornings and the network's most confident picks lose. |
| **CISD + network** | 349 / 64 / 35 | 32% / 27% / 29% | index stop 67% (unseen); trades held to the close without reaching the lock 31-42% | 2024 made Rs 87.8k of the unseen years' Rs 90.3k. Since 2025 the network is rarely confident, and its confidence is what made CISD work. The final months made only Rs 2.7k on 35 trades. Losses are growing (average loss Rs 1,287 → 2,075). |
| **NG evening breakout** | none / 140 / 120 | 54% / 56% | option and index stops 73-80% | It breaks even in the final months: +Rs 176 on 120 trades. Costs eat the edge, and quick exits lose. Boss already switched it off. |
| **SILVERM morning call** | none / 65 / 87 | 42% / 52% | **exits priced by a model** (no trade in the exit minute) 60-90% | **Liquidity at the exit.** 61-67% of model-priced exits lose, against 31-46% of exits with a real trade. The record itself is uncertain. Boss already switched it off. |
| **MCX trend (12-month)** | 122 / 96 / 34 | 41% / 44% / 32% | losses booked at contract rolls | **Size.** At 1 lot of each leg, the worst month is -Rs 34.8k / -41.1k / **-73.1k**, beyond Boss's -Rs 13k monthly stop in every period. The worst single trade lost Rs 53.6k. |
| **US-night silver** | none / 100 / 107 | 44% / 45% | all exits are at the close: it rides the whole adverse day | It exists only in silver's huge 2025-26 year: **the lake has no MCX option minutes before Aug 2025, so the unseen years cannot be checked.** The average loss is growing (Rs 2,154 → 2,845). On paper the trade manager's early exits hurt more often than they helped. |

4. **Boss's 65% win rate:**
   - Only CISD + network reaches it in every period (68% / 73% / 71%).
   - S5 Trail reaches it only in the final months (79% on 33 trades).
   - The four MCX arms are at 44-68%. The trend arm reaches 68% only in the final months, on 34 trades.
5. **Data limits, said plainly:**
   - **MCX intraday arms:** the lake has no MCX option minutes before 5 Aug 2025. NG evening, SILVERM morning and US-night silver therefore have **no trades in the unseen years** (Dec 2022 - Nov 2024), and their design period starts only in Aug 2025.
   - **Futures volume and OI:** the lake has MCX futures volume and OI only from 2026 (NG from 26 Aug 2026), so "futures volume vs usual" and the order-flow proxies on the future are empty for most MCX trades.
   - **Trend arm:** the lake has no LEAD, so it is rebuilt on 6 of its 7 legs.
   - **Phone diagnostics:** they hide rupees, so paper losses cannot be added up.

## How each arm was rebuilt (parity)

| arm | engine | match with the published numbers |
|---|---|---|
| S5 Trail | `r128_missing/s5_app_check.py --cal` (the app's IV + offset; R123 fold networks; R128 trail) | **exact**: 540 walk-forward trades +Rs 161, 33 holdout +Rs 509 |
| CISD + network | R133 trade list, R132 N-EV filter on R123's out-of-sample predictions | **exact**: 413 design trades +Rs 261, 69% won; 35 holdout +Rs 77, 71% won |
| NG evening | `hunt/m3` (m3lib imported) | **exact**, trade by trade: 197 design, 55 holdout |
| SILVERM morning | `hunt/m4` through R42's path patch | holdout 51 trades, +Rs 441 vs +Rs 454 published (45 identical; 3 strike ties, 3 model-priced exits differ); design 95 trades as R42 (M4's own download had 78) |
| MCX trend | `hunt/m2` prep + engine | 6 of 7 legs (no LEAD): 2013-24 CAGR 16.8% vs 16.9%; holdout 81% vs 92% CAGR. 1 lot of each leg, as the app runs it |
| US-night silver | `hunt/r2` hourly.py + cand.py, Yahoo refetched | **exact**: holdout 166 trades +Rs 1,189, 56%, drawdown Rs 29.6k |

Details: `results/parity_nn.json`, `results/parity_mcx.json`.

**Network arms use only out-of-sample scores.** These are R123's walk-forward fold networks (2023 - 15 Mar 2026) and its
holdout network (Apr - Oct 2026). Their "unseen years" therefore start in Jan 2023.

**Those years are not new to the arms.** R128 and R133 chose these rules on 2023 - Mar 2026. They are only new to the
filters here.

## Periods

- **Unseen:** 1 Dec 2022 - 19 Nov 2024.
- **Design:** 20 Nov 2024 - 31 Mar 2026. Filters were chosen here only.
- **Final:** 1 Apr - 19 Oct 2026.

All results are for 1 lot, after each engine's own charges and slippage. A loser is net below 0.

## S5 Trail

**What it is:** R123's network picks an ATM option (85-97% puts) at 09:20, 09:25 or 09:30 on the five indices. It
holds with a -Rs 1,000 or -Rs 2,000 stop, and a 60% lock after +Rs 1,000.

**How it loses**
- **The stop takes almost all the money.**
  - Unseen years: 158 stop-outs, -Rs 3.16 lakh of -Rs 3.29 lakh lost (96%).
  - Design: 42 stop-outs, -Rs 87.5k of -Rs 88.9k.
  - Final months: 6 stop-outs, -Rs 13.1k.
  - The trailing lock almost never loses: 5-7% of lock exits.
- **The profit is a handful of days.**
  - The best 5 days are 139% of the unseen years' net (Rs 80.5k on 430 trades).
  - In design they are 585% of the net (Rs 6.5k on 110 trades).
  - 407 of the 430 unseen-year trades are in 2024.
  - 2025 lost Rs 14.8k on 38 trades. Apr 2025 - Mar 2026 lost Rs 4.9k on 44 trades.
- **Trades still open after 1-3 hours mostly lose** in the unseen years: 58% of 52.

**Patterns at entry**
- **Steady in every period with at least 30 trades (unseen and design):**
  - **High ATM IV (top fifth):**

    | period | trades | lost | average |
    |---|---|---|---|
    | unseen | 173 | 49% | -Rs 35 |
    | design | 22 | 68% | -Rs 944 |
    | final | 7 | 57% | -Rs 768 |

    The option is dear, and the expected move is already priced in.
  - **The network's most confident picks (top fifth of P(win)) lose most:**

    | period | trades | lost | average |
    |---|---|---|---|
    | unseen | 72 | 53% | -Rs 145 |
    | design | 22 | 59% | -Rs 509 |
    | final | 7 | 43% | -Rs 198 |

    These are the same high-IV mornings: big moves are priced and the stop is hit first.
- **Leads, not steady:**
  - Order-flow proxy (labelled a proxy): when the option had more volume on down-minutes in the 15 minutes before entry,
    it lost 50% (unseen, 91 trades, -Rs 15) and 50% (design, 22, -Rs 491).
  - The 09:30 pick lost 56% in design (9 trades, -Rs 949).
- **No steady pattern** in India VIX, breadth, gap, days to expiry, option volume or spread.

**Filters (frozen on design)**

| filter | unseen: trades, win, net, losing days | final: trades, win, net, losing days | p (unseen / final / pooled) | q | result |
|---|---|---|---|---|---|
| skip ATM IV > 20.8 | 430 → 257, 59% → 64%, Rs 80.5k → 86.5k, -Rs 2.50 lakh → -1.42 lakh | 33 → 26, 79% → 88%, Rs 16.8k → 22.2k, -Rs 13.1k → -6.0k | 0.026 / 0.016 / 0.015 | 0.135 | **fails BH only** |
| skip network P(loss) > 0.342 | 430 → 406, 59% → 60%, Rs 80.5k → 92.1k, -2.50 lakh → -2.38 lakh | 33 → 25, 79% → 84%, Rs 16.8k → 16.8k | 0.43 / 0.059 / 0.21 | 0.55 | fails (twins) |
| either | 430 → 250, 59% → 64%, Rs 80.5k → 86.1k, -2.50 lakh → -1.39 lakh | 33 → 21, 79% → 90%, Rs 16.8k → 17.8k, -13.1k → -4.0k | 0.037 / 0.047 / 0.013 | 0.135 | fails BH only |

**More on the IV skip:**
- Unseen years: drawdown Rs 71.7k → 31.1k; worst month -Rs 21.4k → -12.3k (now inside Boss's -Rs 13k).
- Final months: worst month -Rs 4.3k → -Rs 314.

**Paper:**
- S5 Trail was switched on on 29 Oct. The diagnostics show one paper trade, on 30 Oct: a NIFTY put bought at 130.65 and sold at 149.6, a win.
- **It was bought at 09:40.** The research decides at 09:20-09:30, so this entry was at least 10 minutes late. The app's timing is worth checking.

**Flaws, plainly:** a 2024 strategy whose money is a few days; the stop is where every rupee is lost; it loses on dear
(high-IV) mornings.

## CISD + network (R133 S3-V2-E3)

**What it is:**
- CISD on 5-minute index bars, taken only when R123's network expects that side to pay.
- Exits: R131's index stop, plus a 60% lock after +Rs 1,000; else out at 15:20.

**How it loses**
- **Index stop:**
  - unseen years: 54 trades, -Rs 97.0k (67% of the money lost);
  - design: 9 trades, -Rs 23.3k;
  - final months: 4 trades, -Rs 11.9k.
- **Trades that never reach the lock and are held to the close:**
  - unseen years: 55 losers, -Rs 45.3k;
  - final months: 6 of 6 lost, -Rs 8.8k (42% of the final months' lost money).
- **The lock never loses** (0-2% of lock exits).
- **Time held tells the story.** In the unseen years:
  - trades closed within 15 minutes lost 11% (119 trades);
  - trades held 1-3 hours lost 51% (83);
  - trades held over 3 hours lost 70% (20, average -Rs 1,226).
- **Losses are getting bigger:** the average loss went -Rs 1,287 (unseen) → -1,691 (design) → -2,075 (final).
- **The profit is 2024.**
  - Unseen years: 2023 made Rs 2.6k on 170 trades; 2024 made Rs 87.8k on 179.
  - Jan - Mar 2026: -Rs 4.0k on 13 trades.
  - Final months: Rs 2.7k on 35 trades, and the best 5 days were 4.5 times that.

**Patterns**
- **The network's confidence is what makes CISD work.** In the unseen years, by fifth of the network's P(win):

  | fifth | trades | lost |
  |---|---|---|
  | highest | 24 | 8% |
  | 2nd highest | 48 | 12% |
  | lowest | 207 | 40% |

  - Design, highest fifth: 0 of 13 lost.
  - **But in the final months only 1 trade was in the highest fifth.** Since 2025 the network is rarely sure, and the
    arm's edge has thinned with it.
  - This is not a filter result (design's lowest fifth was not a loser), but it explains the decline.
- **No steady pattern** at entry in price action, volume, spread, OI or VIX.
- Order-flow proxy (labelled a proxy): options whose OI fell while the price rose ("short covering") won more in the
  unseen years (18% lost, 98 trades). The final months did not repeat it (42% lost, 12 trades).

**Filters:**
- **Breadth against the trade:**
  - unseen years: net Rs 90.3k → 78.8k, drawdown worse;
  - p 0.43 / 0.62.
- **ATM OI falling on the bought side:**
  - p 0.91 / 0.58;
  - the final months turn to a loss.
- **Either:** fails.

All three helped in design only: **pattern-fitting**.

**Paper:** switched on on 30 Oct. No paper trade yet.

**Flaws, plainly:** the edge is 2024 and the network's confident days, which have become rare; losers are trades left
open to the index stop or the close.

## NG evening breakout (NATURALGAS, M3 EVE)

**Coverage:**
- Design: 140 trades (6 Aug 2025 - Mar 2026).
- Final months: 120.
- **No unseen-years trades:** there are no MCX option minutes before Aug 2025.

**How it loses**
- **Losers:** 54% in design, 56% in the final months.
- **Final net: +Rs 176 on 120 trades**, which is break-even.
- **The stops take the money:**
  - option stop: 17 trades, -Rs 54.8k in design; 19 trades, -Rs 37.6k in the final months;
  - index stop: 33 trades, -Rs 80.0k in design; 19 trades, -Rs 36.7k in the final months;
  - together 80% (design) and 73% (final) of the money lost.
- **Quick exits lose:**
  - trades out within 15 minutes lost 73% (design);
  - 15-60 minutes lost 65% (design) and 66% (final).
- **The longest losing streak in the final months was 12 trades.**
- **Costs:** M3's own re-cost with the measured 0.43% evening spread cut the holdout from +Rs 93 to +Rs 70 a trade.

**Patterns in both periods**
- These are leads only. 19 buckets "held" in both periods, out of about 100 checked, with 9-52 trades each, so several
  are chance:
  - a wide evening box (4th fifth): 57% / 67% lost;
  - a weak push into the break (lowest fifth): 61% / 72%;
  - late signals (latest fifth): 61% / 71%;
  - the cheapest premiums (lowest fifth): 64% / 58%;
  - 4th fifth of days to expiry: 64% / 63%;
  - the US NG overnight move most with the trade (top fifth): 64% / 86% (7 trades).

**Filters:** "skip when the price is above yesterday's high", "skip far above yesterday's low", and either of the two.
All fail in the final months: the worst month gets worse, and the arm stops making money (p 0.18-0.42).

**Paper:**
- The diagnostics show 8 closed trades (rupees hidden), two exits at the range-mid stop, and an expiry-rule refusal.
- **Boss switched it off around 25 Oct** ("it loses after costs", R53 / R83). It runs in shadow now.

**Flaw, plainly:** the edge is smaller than the costs and the stops; the final months are break-even.

## SILVERM morning call (M4 Q4)

**Coverage:**
- Design: 65 trades (28 Aug 2025 - Mar 2026).
- Final months: 87.
- No unseen-years trades.

**How it loses**
- **Losers:** 42% (design), 52% (final). Average loss about -Rs 2,300.
- **The main flaw is liquidity at the exit.**
  - The exit is 4 hours after entry. When the option had no trade in that minute, the engine priced the exit from
    implied volatility (a model).
  - **Model-priced exits:**
    - design: 23 trades, 61% lost;
    - final months: 24 trades, 67% lost, average -Rs 1,250.
  - **Exits with a real trade:**
    - design: 42 trades, 31% lost;
    - final months: 63 trades, 46% lost, average +Rs 1,319.
  - **Model-priced exits carry most of the lost money:** 90% in design (-Rs 54.2k of -Rs 60.2k), 60% in the final
    months (-Rs 64.0k of -Rs 106.9k).
  - Every one of the 6 worst trades was model-priced, the worst -Rs 12.3k (8 Jan 2026).
- **Losses bunch:** 44% of the final months' losers fall on days where every trade lost.

**Patterns:**
- Leads, two periods only, small buckets:
  - a wide morning so far (4th fifth): 46% / 69% lost;
  - busy option volume (4th fifth): 58% / 75% lost.
- In the final months, the widest option spreads (top fifth) lost 77% (13 trades).

**Filters:**
- **The premium cut skipped no trade in the final months:** premiums there stayed below the design cut.
- **Premium as a % of silver:** helped design, p 0.11 in the final months.
- All fail.

**Paper:** 3 closed trades. **Boss switched it off and hid it on 21-22 Oct** ("no proven edge on cleaned prices",
R42).

**Flaw, plainly:** the option is too thin at the exit time for the backtest to be trusted. Losers cluster where there
was no real price.

## MCX trend (12-month, M2 TSM252)

**What it is:**
- Six mini futures, 1 lot each (no LEAD in the lake).
- A trade here is one contract held from a roll or trend flip to the next.
- Unseen years: 122 trades. Design: 96. Final months: 34.

**How it loses**
- **The losses are large:**

  | period | average loss | worst month |
  |---|---|---|
  | unseen | -Rs 7.6k | -Rs 34.8k |
  | design | -Rs 9.0k | -Rs 41.1k |
  | final | -Rs 14.6k | -Rs 73.1k |

  **Every period breaks Boss's -Rs 13k monthly stop** at 1 lot of each leg.
- **Worst trades:** -Rs 53.6k (1 Jun 2026), -Rs 43.2k (1 Apr 2024), -Rs 28.3k (Feb 2023), all booked at a roll.
- **Trend flips lose:** 64% (unseen) and 72% (design) of flip exits lost.
- **The money comes from a few legs and months:**
  - the best 5 days are 120-172% of the net;
  - the natural-gas leg lost 65% of its design trades (average -Rs 7.0k);
  - the silver-micro leg lost all 3 final-month trades (average -Rs 15.4k).

**Patterns:**
- **One pattern held in every period:** after a wide previous day (4th fifth of the previous day's range / its 20-day
  average), it lost 59% (unseen, 27 trades), 68% (design, 19) and 38% (final, 8).
- High 60-day volatility lost in design (74%) but won in the unseen years: it flips.

**Filters:** "skip high 60-day volatility", "skip high carry", and either. All fail in the unseen years: the drawdown
and the worst month get worse (p 0.29-0.59).

**Flaw, plainly:** the size. Six futures at once swing far more than Boss's monthly limit. The losses are real trend
reversals at rolls, not something a skip filter catches.

## US-night silver (R2 C2)

**Coverage:**
- Design: 100 trades. Final months: 107.
- Both are inside silver's huge 2025-26 rise.
- **The unseen years cannot be rebuilt:** the engine reads the future from the option files, which start in Aug 2025.
  R2's own older check lost.

**How it loses**
- **Losers:** 44% (design), 45% (final).
- **The average loss is growing:** -Rs 2,154 → -Rs 2,845.
- **Every exit is 10 minutes before the close.** A wrong-side day is held all day:
  - worst trades -Rs 18.1k (6 Feb 2026), -Rs 10.3k (14 Oct 2026), -Rs 8.9k (18 May 2026);
  - no losers fell on "all lost" days, because it trades once a day.
- **The trade manager on paper:**
  - 8 trades, 7 early exits: 3 helped, 4 hurt (diagnostics, 30 Oct);
  - Boss's note: the early exits cost Rs 4,987, and the paper record is 2 of 5.

**Patterns: almost all flip between design and final.**

| pattern | design | final |
|---|---|---|
| Fridays | 61% lost | 39% lost |
| the strongest US signal (top fifth) | won (+Rs 1,564) | lost 55% (-Rs 440) |
| the biggest overnight COMEX move with the trade | won | lost 59% (-Rs 418) |
| US data days | 55% lost (11 trades) | 62% lost (13 trades) |

- The data-day row is small but the same way twice.
- **Steady:** only a weak push before 09:05 (2nd fifth): 60% (20 trades) and 53% (32).

**Filters:** "skip Fridays", "skip US data days", and either. All fail in the final months: the drawdown is worse (p
0.42-0.91).

**Flaw, plainly:** it is one regime (a tripling silver market). Its losses are whole adverse days, and its record
cannot be checked on older years with our data.

## Before → after, every filter on every arm

1 lot, after charges. "Before" is the arm as it runs today. "Losing days" is the sum of all losing days. Win = share of trades that made money (Boss's rule: 65%).

- The filters were chosen on **design**. Only **unseen** and **final** are tests.
- MCX intraday arms have no unseen-years trades: the lake has no MCX option minutes before Aug 2025.
- p: share of 2,000 random skips of the same number of trades that cut losing days as much (pooled = unseen and final together). q: after BH over all 18 filters.

### S5 Trail

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip ATM IV > 20.8** | unseen (Dec 2022 - Nov 2024) | 430 → 257 | 59% → 64% | Rs 80,508 → Rs 86,513 | -Rs 2,50,449 → -Rs 1,42,219 | Rs 71,650 → Rs 31,113 | -Rs 10,016 → -Rs 10,016 | -Rs 21,394 → -Rs 12,337 |
|  | design (Nov 2024 - Mar 2026) | 110 → 88 | 57% → 64% | Rs 6,507 → Rs 27,265 | -Rs 73,482 → -Rs 46,624 | Rs 22,043 → Rs 14,003 | -Rs 6,518 → -Rs 4,053 | -Rs 6,813 → -Rs 6,224 |
|  | final (Apr - Oct 2026) | 33 → 26 | 79% → 88% | Rs 16,789 → Rs 22,165 | -Rs 13,105 → -Rs 5,995 | Rs 7,111 → Rs 3,997 | -Rs 5,078 → -Rs 2,000 | -Rs 4,327 → -Rs 314 |
| **skip the network's P(loss) > 0.342** | unseen (Dec 2022 - Nov 2024) | 430 → 406 | 59% → 60% | Rs 80,508 → Rs 92,067 | -Rs 2,50,449 → -Rs 2,38,116 | Rs 71,650 → Rs 60,542 | -Rs 10,016 → -Rs 10,016 | -Rs 21,394 → -Rs 19,394 |
|  | design (Nov 2024 - Mar 2026) | 110 → 88 | 57% → 61% | Rs 6,507 → Rs 23,534 | -Rs 73,482 → -Rs 47,113 | Rs 22,043 → Rs 13,825 | -Rs 6,518 → -Rs 5,856 | -Rs 6,813 → -Rs 6,222 |
|  | final (Apr - Oct 2026) | 33 → 25 | 79% → 84% | Rs 16,789 → Rs 16,777 | -Rs 13,105 → -Rs 6,030 | Rs 7,111 → Rs 2,032 | -Rs 5,078 → -Rs 2,032 | -Rs 4,327 → Rs 352 |
| **either of the two above** | unseen (Dec 2022 - Nov 2024) | 430 → 250 | 59% → 64% | Rs 80,508 → Rs 86,109 | -Rs 2,50,449 → -Rs 1,38,938 | Rs 71,650 → Rs 26,908 | -Rs 10,016 → -Rs 10,016 | -Rs 21,394 → -Rs 10,338 |
|  | design (Nov 2024 - Mar 2026) | 110 → 75 | 57% → 65% | Rs 6,507 → Rs 30,280 | -Rs 73,482 → -Rs 35,707 | Rs 22,043 → Rs 15,626 | -Rs 6,518 → -Rs 4,000 | -Rs 6,813 → -Rs 6,222 |
|  | final (Apr - Oct 2026) | 33 → 21 | 79% → 90% | Rs 16,789 → Rs 17,825 | -Rs 13,105 → -Rs 3,998 | Rs 7,111 → Rs 2,000 | -Rs 5,078 → -Rs 2,000 | -Rs 4,327 → -Rs 314 |

- skip ATM IV > 20.8: p unseen 0.026, final 0.016, pooled 0.015, q 0.135. **Fails**: q 0.135 > 0.10.
- skip the network's P(loss) > 0.342: p unseen 0.435, final 0.059, pooled 0.214, q 0.551. **Fails**: unseen: no better than random skipping; final: no better than random skipping; q 0.551 > 0.10.
- either of the two above: p unseen 0.037, final 0.047, pooled 0.013, q 0.135. **Fails**: q 0.135 > 0.10.

### CISD + network

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip breadth with the trade ≤ 0.339** | unseen (Dec 2022 - Nov 2024) | 349 → 301 | 68% → 67% | Rs 90,341 → Rs 78,824 | -Rs 1,16,918 → -Rs 1,02,606 | Rs 15,095 → Rs 15,402 | -Rs 5,409 → -Rs 5,409 | -Rs 7,474 → -Rs 5,521 |
|  | design (Nov 2024 - Mar 2026) | 64 → 50 | 73% → 80% | Rs 17,356 → Rs 29,069 | -Rs 24,575 → -Rs 9,305 | Rs 7,005 → Rs 5,272 | -Rs 3,937 → -Rs 3,040 | -Rs 3,222 → -Rs 3,040 |
|  | final (Apr - Oct 2026) | 35 → 28 | 71% → 71% | Rs 2,681 → Rs 734 | -Rs 20,146 → -Rs 17,319 | Rs 10,761 → Rs 10,761 | -Rs 5,497 → -Rs 5,497 | -Rs 4,433 → -Rs 4,433 |
| **skip ATM OI change on the bought side (%) ≤ -3.32** | unseen (Dec 2022 - Nov 2024) | 349 → 327 | 68% → 67% | Rs 90,341 → Rs 79,433 | -Rs 1,16,918 → -Rs 1,15,450 | Rs 15,095 → Rs 16,362 | -Rs 5,409 → -Rs 5,409 | -Rs 7,474 → -Rs 8,741 |
|  | design (Nov 2024 - Mar 2026) | 64 → 51 | 73% → 80% | Rs 17,356 → Rs 24,810 | -Rs 24,575 → -Rs 13,045 | Rs 7,005 → Rs 4,616 | -Rs 3,937 → -Rs 3,040 | -Rs 3,222 → -Rs 3,040 |
|  | final (Apr - Oct 2026) | 35 → 28 | 71% → 71% | Rs 2,681 → -Rs 1,048 | -Rs 20,146 → -Rs 17,239 | Rs 10,761 → Rs 9,241 | -Rs 5,497 → -Rs 4,447 | -Rs 4,433 → -Rs 4,541 |
| **either of the two above** | unseen (Dec 2022 - Nov 2024) | 349 → 281 | 68% → 66% | Rs 90,341 → Rs 66,976 | -Rs 1,16,918 → -Rs 1,01,249 | Rs 15,095 → Rs 16,669 | -Rs 5,409 → -Rs 5,409 | -Rs 7,474 → -Rs 6,789 |
|  | design (Nov 2024 - Mar 2026) | 64 → 40 | 73% → 85% | Rs 17,356 → Rs 26,947 | -Rs 24,575 → -Rs 7,350 | Rs 7,005 → Rs 3,995 | -Rs 3,937 → -Rs 3,040 | -Rs 3,222 → -Rs 3,040 |
|  | final (Apr - Oct 2026) | 35 → 22 | 71% → 73% | Rs 2,681 → -Rs 1,925 | -Rs 20,146 → -Rs 14,413 | Rs 10,761 → Rs 9,241 | -Rs 5,497 → -Rs 4,447 | -Rs 4,433 → -Rs 5,584 |

- skip breadth with the trade ≤ 0.339: p unseen 0.426, final 0.617, pooled 0.491, q 0.736. **Fails**: unseen: drawdown worse; unseen: no better than random skipping; final: no better than random skipping; q 0.736 > 0.10.
- skip ATM OI change on the bought side (%) ≤ -3.32: p unseen 0.907, final 0.583, pooled 0.900, q 0.959. **Fails**: unseen: drawdown worse; unseen: worst month worse; unseen: no better than random skipping; final: worst month worse; final: no longer makes money; final: no better than random skipping; q 0.959 > 0.10.
- either of the two above: p unseen 0.741, final 0.665, pooled 0.769, q 0.923. **Fails**: unseen: drawdown worse; unseen: no better than random skipping; final: worst month worse; final: no longer makes money; final: no better than random skipping; q 0.923 > 0.10.

### NG evening breakout

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip distance above yesterday's high (ATR) > 0.163** | design (Nov 2024 - Mar 2026) | 140 → 112 | 46% → 49% | Rs 24,755 → Rs 42,436 | -Rs 1,68,022 → -Rs 1,15,947 | Rs 24,763 → Rs 14,864 | -Rs 6,460 → -Rs 5,644 | -Rs 10,237 → -Rs 5,308 |
|  | final (Apr - Oct 2026) | 120 → 97 | 44% → 45% | Rs 176 → -Rs 193 | -Rs 1,01,557 → -Rs 78,323 | Rs 19,622 → Rs 20,338 | -Rs 3,268 → -Rs 3,138 | -Rs 8,222 → -Rs 13,056 |
| **skip distance above yesterday's low (ATR) > 1.03** | design (Nov 2024 - Mar 2026) | 140 → 112 | 46% → 47% | Rs 24,755 → Rs 32,697 | -Rs 1,68,022 → -Rs 1,16,862 | Rs 24,763 → Rs 23,530 | -Rs 6,460 → -Rs 5,644 | -Rs 10,237 → -Rs 9,376 |
|  | final (Apr - Oct 2026) | 120 → 92 | 44% → 45% | Rs 176 → -Rs 6,258 | -Rs 1,01,557 → -Rs 77,090 | Rs 19,622 → Rs 19,821 | -Rs 3,268 → -Rs 3,138 | -Rs 8,222 → -Rs 11,401 |
| **either of the two above** | design (Nov 2024 - Mar 2026) | 140 → 106 | 46% → 50% | Rs 24,755 → Rs 45,216 | -Rs 1,68,022 → -Rs 1,04,343 | Rs 24,763 → Rs 18,930 | -Rs 6,460 → -Rs 5,644 | -Rs 10,237 → -Rs 6,254 |
|  | final (Apr - Oct 2026) | 120 → 87 | 44% → 47% | Rs 176 → Rs 1,031 | -Rs 1,01,557 → -Rs 69,802 | Rs 19,622 → Rs 17,664 | -Rs 3,268 → -Rs 3,138 | -Rs 8,222 → -Rs 10,381 |

- skip distance above yesterday's high (ATR) > 0.163: p final 0.178, pooled 0.194, q 0.551. **Fails**: unseen: no data (0 trades); final: drawdown worse; final: worst month worse; final: no longer makes money; final: no better than random skipping; q 0.551 > 0.10.
- skip distance above yesterday's low (ATR) > 1.03: p final 0.416, pooled 0.439, q 0.718. **Fails**: unseen: no data (0 trades); final: drawdown worse; final: worst month worse; final: no longer makes money; final: no better than random skipping; q 0.718 > 0.10.
- either of the two above: p final 0.209, pooled 0.205, q 0.551. **Fails**: unseen: no data (0 trades); final: worst month worse; final: no better than random skipping; q 0.551 > 0.10.

### SILVERM morning call

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip premium > Rs 13,370** | design (Nov 2024 - Mar 2026) | 65 → 52 | 58% → 65% | Rs 43,059 → Rs 45,352 | -Rs 49,333 → -Rs 18,950 | Rs 20,834 → Rs 7,475 | -Rs 12,273 → -Rs 5,993 | -Rs 4,371 → -Rs 3,086 |
|  | final (Apr - Oct 2026) | 87 → 87 | 48% → 48% | Rs 53,127 → Rs 53,127 | -Rs 75,735 → -Rs 75,735 | Rs 18,755 → Rs 18,755 | -Rs 9,987 → -Rs 9,987 | -Rs 11,376 → -Rs 11,376 |
| **skip premium > 5.4% of silver** | design (Nov 2024 - Mar 2026) | 65 → 52 | 58% → 65% | Rs 43,059 → Rs 45,352 | -Rs 49,333 → -Rs 18,950 | Rs 20,834 → Rs 7,475 | -Rs 12,273 → -Rs 5,993 | -Rs 4,371 → -Rs 3,086 |
|  | final (Apr - Oct 2026) | 87 → 85 | 48% → 48% | Rs 53,127 → Rs 54,517 | -Rs 75,735 → -Rs 71,332 | Rs 18,755 → Rs 18,755 | -Rs 9,987 → -Rs 9,987 | -Rs 11,376 → -Rs 11,376 |
| **either of the two above** | design (Nov 2024 - Mar 2026) | 65 → 52 | 58% → 65% | Rs 43,059 → Rs 45,352 | -Rs 49,333 → -Rs 18,950 | Rs 20,834 → Rs 7,475 | -Rs 12,273 → -Rs 5,993 | -Rs 4,371 → -Rs 3,086 |
|  | final (Apr - Oct 2026) | 87 → 85 | 48% → 48% | Rs 53,127 → Rs 54,517 | -Rs 75,735 → -Rs 71,332 | Rs 18,755 → Rs 18,755 | -Rs 9,987 → -Rs 9,987 | -Rs 11,376 → -Rs 11,376 |

- skip premium > Rs 13,370: p final 1.000, pooled 1.000, q 1.000. **Fails**: unseen: no data (0 trades); final: losing days not smaller; final: no better than random skipping; q 1.000 > 0.10.
- skip premium > 5.4% of silver: p final 0.114, pooled 0.129, q 0.551. **Fails**: unseen: no data (0 trades); final: no better than random skipping; q 0.551 > 0.10.
- either of the two above: p final 0.126, pooled 0.124, q 0.551. **Fails**: unseen: no data (0 trades); final: no better than random skipping; q 0.551 > 0.10.

### MCX trend (12-month)

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip 60-day volatility > 0.531** | unseen (Dec 2022 - Nov 2024) | 122 → 108 | 59% → 57% | Rs 1,41,216 → Rs 77,695 | -Rs 2,87,931 → -Rs 2,55,174 | Rs 63,585 → Rs 72,517 | -Rs 45,280 → -Rs 45,280 | -Rs 34,822 → -Rs 48,602 |
|  | design (Nov 2024 - Mar 2026) | 96 → 77 | 56% → 64% | Rs 1,54,907 → Rs 3,52,248 | -Rs 3,42,359 → -Rs 1,15,668 | Rs 77,764 → Rs 33,369 | -Rs 66,317 → -Rs 28,382 | -Rs 41,141 → -Rs 20,307 |
|  | final (Apr - Oct 2026) | 34 → 26 | 68% → 65% | Rs 1,29,921 → Rs 1,22,463 | -Rs 1,51,155 → -Rs 1,07,346 | Rs 94,842 → Rs 65,474 | -Rs 50,812 → -Rs 50,812 | -Rs 73,129 → -Rs 44,892 |
| **skip carry > 0.216** | unseen (Dec 2022 - Nov 2024) | 122 → 99 | 59% → 56% | Rs 1,41,216 → Rs 53,351 | -Rs 2,87,931 → -Rs 2,39,227 | Rs 63,585 → Rs 66,990 | -Rs 45,280 → -Rs 45,280 | -Rs 34,822 → -Rs 34,822 |
|  | design (Nov 2024 - Mar 2026) | 96 → 77 | 56% → 61% | Rs 1,54,907 → Rs 2,83,989 | -Rs 3,42,359 → -Rs 1,67,556 | Rs 77,764 → Rs 40,454 | -Rs 66,317 → -Rs 28,130 | -Rs 41,141 → -Rs 28,130 |
|  | final (Apr - Oct 2026) | 34 → 28 | 68% → 64% | Rs 1,29,921 → Rs 1,21,673 | -Rs 1,51,155 → -Rs 1,42,933 | Rs 94,842 → Rs 94,620 | -Rs 50,812 → -Rs 50,812 | -Rs 73,129 → -Rs 78,740 |
| **either of the two above** | unseen (Dec 2022 - Nov 2024) | 122 → 94 | 59% → 55% | Rs 1,41,216 → Rs 18,613 | -Rs 2,87,931 → -Rs 2,23,861 | Rs 63,585 → Rs 73,971 | -Rs 45,280 → -Rs 45,280 | -Rs 34,822 → -Rs 48,602 |
|  | design (Nov 2024 - Mar 2026) | 96 → 67 | 56% → 66% | Rs 1,54,907 → Rs 3,37,744 | -Rs 3,42,359 → -Rs 95,320 | Rs 77,764 → Rs 19,718 | -Rs 66,317 → -Rs 11,646 | -Rs 41,141 → -Rs 11,418 |
|  | final (Apr - Oct 2026) | 34 → 22 | 68% → 64% | Rs 1,29,921 → Rs 1,20,883 | -Rs 1,51,155 → -Rs 99,124 | Rs 94,842 → Rs 50,812 | -Rs 50,812 → -Rs 50,812 | -Rs 73,129 → -Rs 50,503 |

- skip 60-day volatility > 0.531: p unseen 0.331, final 0.361, pooled 0.285, q 0.641. **Fails**: unseen: drawdown worse; unseen: worst month worse; unseen: no better than random skipping; final: no better than random skipping; q 0.641 > 0.10.
- skip carry > 0.216: p unseen 0.370, final 0.722, pooled 0.587, q 0.813. **Fails**: unseen: drawdown worse; unseen: no better than random skipping; final: worst month worse; final: no better than random skipping; q 0.813 > 0.10.
- either of the two above: p unseen 0.286, final 0.475, pooled 0.364, q 0.718. **Fails**: unseen: drawdown worse; unseen: worst month worse; unseen: no better than random skipping; final: no better than random skipping; q 0.718 > 0.10.

### US-night silver

| filter | period | trades | win | net | losing days | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|---|
| **skip Fridays** | design (Nov 2024 - Mar 2026) | 100 → 82 | 56% → 60% | Rs 1,13,838 → Rs 1,18,452 | -Rs 94,785 → -Rs 58,690 | Rs 29,646 → Rs 8,974 | -Rs 18,095 → -Rs 8,298 | -Rs 8,596 → -Rs 8,596 |
|  | final (Apr - Oct 2026) | 107 → 84 | 55% → 54% | Rs 69,043 → Rs 35,964 | -Rs 1,36,583 → -Rs 1,19,092 | Rs 19,294 → Rs 21,826 | -Rs 10,317 → -Rs 10,317 | -Rs 9,756 → -Rs 10,577 |
| **skip US data days** | design (Nov 2024 - Mar 2026) | 100 → 89 | 56% → 57% | Rs 1,13,838 → Rs 1,15,248 | -Rs 94,785 → -Rs 80,424 | Rs 29,646 → Rs 29,646 | -Rs 18,095 → -Rs 18,095 | -Rs 8,596 → -Rs 8,596 |
|  | final (Apr - Oct 2026) | 107 → 94 | 55% → 57% | Rs 69,043 → Rs 62,942 | -Rs 1,36,583 → -Rs 1,19,067 | Rs 19,294 → Rs 20,967 | -Rs 10,317 → -Rs 8,853 | -Rs 9,756 → -Rs 3,158 |
| **either of the two above** | design (Nov 2024 - Mar 2026) | 100 → 74 | 56% → 61% | Rs 1,13,838 → Rs 1,26,138 | -Rs 94,785 → -Rs 44,950 | Rs 29,646 → Rs 8,974 | -Rs 18,095 → -Rs 8,298 | -Rs 8,596 → -Rs 8,596 |
|  | final (Apr - Oct 2026) | 107 → 77 | 55% → 56% | Rs 69,043 → Rs 42,889 | -Rs 1,36,583 → -Rs 1,06,242 | Rs 19,294 → Rs 21,550 | -Rs 10,317 → -Rs 8,853 | -Rs 9,756 → -Rs 5,689 |

- skip Fridays: p final 0.891, pooled 0.906, q 0.959. **Fails**: unseen: no data (0 trades); final: drawdown worse; final: worst month worse; final: no better than random skipping; q 0.959 > 0.10.
- skip US data days: p final 0.427, pooled 0.423, q 0.718. **Fails**: unseen: no data (0 trades); final: drawdown worse; final: no better than random skipping; q 0.718 > 0.10.
- either of the two above: p final 0.774, pooled 0.764, q 0.923. **Fails**: unseen: no data (0 trades); final: drawdown worse; final: no better than random skipping; q 0.923 > 0.10.

## What is proposed (nothing is built; each needs Boss's go-ahead)

1. **S5-IVSKIP, a shadow log only.**
   - It would log beside S5 Trail what "skip when ATM IV > 20.8" would have done on new days: no orders, no change to the arm.
   - It passed every lose-less gate on the unseen years and the final months and beat random skipping. It missed BH (q 0.135).
   - A forward log of about 100 trades would settle it.
   - Live: it only skips trades (it lowers risk), so it would start in shadow behind a switch that is off by default.
2. **No switch for the other 17 filters.** They failed.
3. **Things worth fixing that are not filters:**
   - **MCX trend:** its size breaks the monthly stop at 1 lot of every leg. Run fewer legs at once, or cap the open legs. That is Boss's sizing choice.
   - **SILVERM morning:** its record depends on model-priced exits. If it is ever revived, it needs an exit time when the option trades.
   - **S5 Trail:** the paper entry at 09:40 against research's 09:20-09:30 should be checked in the app.
   - **US-night silver:** the trade manager's early exits hurt more than they help on paper (4 of 7).
   - **Data:**
     - MCX option minutes before Aug 2025 and futures volume / OI history would let the MCX arms be judged on the unseen years;
     - LEAD daily data for the trend arm.

## Method

- **Look-ahead guard.** Every feature is computed by a function that gets only bars before the entry. The tests
  overwrite everything at or after the entry with garbage and require identical values:
  - `test_r144d.py` for the network arms;
  - `test_mcx_arms.py` for the MCX arms.
- **R123's own inputs.** VIX, PCR, IV, skew and index breadth come from R123's rows at the decision minute, before the entry.
- **Features used:**
  - push, range, trend, gap, distance to yesterday's high and low, time, days to expiry or roll;
  - option volume vs its usual level, premium, a spread proxy (median minute high-low), share of minutes with no trade, OI and its change;
  - PCR, IV and its change, skew, India VIX, breadth of the 59 stocks and of the 5 indices;
  - the network's EV and P(win);
  - order-flow **proxies**: tick-direction volume delta and the OI-price quadrant;
  - MCX: the COMEX / NYMEX overnight move, USD/INR, US data days, DST.
- **Quintiles and steady patterns.** Quintile cuts are fixed on design. "Steady" means losing more than the arm, with a
  negative average, in every period with at least 30 trades, and at least two such periods.
- **Filters.**
  - Chosen on design only: skip one extreme fifth or one level, skipping at least 10 trades and at most 40% of them;
    losing on average in design and in each half.
  - Ranked by losing days cut per trade skipped. The best 2, plus "either".
  - Tested with R142's lose-less gates in each test period that has at least 30 trades.
  - 2,000 random skips of the same count. The pooled p of the unseen years and the final months goes to BH over all
    18 filters.
- **Files:** `research/hunt/r144d_loss_nn_mcx/`:
  - `nn_arms.py`, `mcx_arms.py`, `r144d.py`, and the tests;
  - `results/`: `r144d.json` (every table: exit types, hold times, years, worst 10 trades with features, every
    feature's fifths per period), `pvalues.json`, `filters_frozen.json`, `parity_*.json`.
- **Entries:** `research/experiments/entries/r144d-*.yaml`: 6 rejected and 1 descriptive.
- **Signal source:**
  - S5 and CISD: the index spot (the network reads option-chain context);
  - NG evening and SILVERM morning: the MCX future;
  - trend: each future's 12-month return;
  - US-night silver: COMEX silver and USD/INR before the open.

  No rule reads an option's own chart for its signal.
