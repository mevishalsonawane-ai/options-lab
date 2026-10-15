# R48: high-win-rate NSE index OPTION BUYING (15 Oct 2026) - 0 of 10 pass; a high win rate did not mean profit

**Verdict:** none of the 10 pre-picked option-buying rules passed the locked holdout.
- 7 of 10 lost money even though they won 50-88% of their trades.
- The 3 that made money were no better than buying the same option at random times (p_twin 0.07-0.10).
- Nothing goes to paper. Plain English below; the code, tests and rules are in `research/hunt/r48/`
  (pre-registration `PREREG.md` with amendments 1-4, all committed before the holdout was read).

## What Boss asked, and how the study changed on the way

1. Brief (15 Oct): "results with a good win rate", 65%+ winners that also make money after real costs on unseen
   data, for a Rs 1 lakh account. Search space: defined-risk selling, ITM buying, and earlier high hit-rate ideas.
2. Boss, mid-study: **"backtest only from Nov 2024"**, then **"ignore selling completely ... only buying options"**.
   - Both instructions came before the holdout was read. They are amendments 3 and 4.
   - Design = entries from 1 Nov 2024 to 17 Oct 2025.
   - Locked holdout = entries from 3 Nov 2025 to 13 Oct 2026 (about 11.4 months), read once.
   - Only buying rules could be candidates.
   - The earlier full-period scan, which included selling, is reported below for reference only. Its candidates were
     never read on the holdout.

**Signal source (CLAUDE.md):**
- Every rule reads its signal from the INDEX: the gap vs the prior close, the prior close vs EMA20, the index vs its
  average since 09:15 (TWAP; the index has no volume), and the 09:15-09:29 opening range.
- It then buys the option.
- Targets and stops sit on the option premium.
- No rule reads an option's own chart.

## How every trade was priced (not relaxed)

- **One held contract per trade.** Strike plus side of the nearest expiry, rebuilt from every offset of the lake's
  rolling ATM+-10 rows. Never the rolling ATM series (R45).
- **Next-minute fills.** Decide on a minute's close, fill at the next minute's open.
- **Slippage, every order:** 5 bps plus 1 to 4 ticks, depending on how much that contract traded in the previous
  5 minutes. +1 tick when the strike is 6 or more steps from ATM; doubled on stops.
- **Charges:** the research cost model `Costs("dated")`, which charges the rates in force on each trade date: STT on
  the sold premium, exchange (BSE for SENSEX), SEBI, stamp, GST and Rs 20 brokerage.
- **Lots:** 1 lot at the lot size in force on the day.
- **Time exits:** 15:15, or 15:05 on expiry day.
- **Risk numbers:** worst day and drawdown come from the daily P&L. Every number below carries its trade count.
- **Sample size of the search:** 6,651,600 buying configs were scanned on design. 32,879 passed every design gate:
  - 43+ trades and 65%+ winners;
  - net > 0 and positive in both halves;
  - worst day > -Rs 13k and drawdown > -Rs 40k;
  - net still positive without its top 5 trades;
  - priced on 90%+ of its sessions.
- **Choosing the 10 candidates:** ranked by t-statistic, at most 2 per index, no duplicate entries.

## Results: the 10 candidates, design (Nov 2024 - Oct 2025) and holdout (Nov 2025 - Oct 2026)

Design (Rs, 1 lot):

| # | rule | design: trades | win | net Rs | avg win / avg loss | worst trade | worst day | max DD |
|---|---|---|---|---|---|---|---|---|
| 1 | SENSEX 2-ITM, ema (call if up, put if down), DTE 1, 09:20, +5% / no stop / eod | 49 | 100.0% | +30,171 | 616 / 0 | 196 | 196 | 0 |
| 2 | SENSEX 0-ITM, ema (call if up, put if down), DTE 1, 09:20, +3% / no stop / eod | 49 | 100.0% | +18,767 | 383 / 0 | 9 | 9 | 0 |
| 3 | NIFTY 2-ITM, ema (put if up, call if down), DTE 0, 10:30, +3% / no stop / 60 min, VIX low | 43 | 100.0% | +18,482 | 430 / 0 | 28 | 28 | 0 |
| 4 | NIFTY 6-ITM, ema (put if up, call if down), DTE 0, 10:00, +5% / no stop / eod | 50 | 92.0% | +62,000 | 1,522 / -2,004 | -4,154 | -4,154 | -4,154 |
| 5 | MIDCPNIFTY 4-ITM, orb (call if up, put if down), DTE any, 09:30, +5% / no stop / eod, VIX low | 66 | 86.4% | +78,595 | 1,933 / -3,507 | -6,646 | -6,646 | -10,327 |
| 6 | MIDCPNIFTY 2-ITM, orb (call if up, put if down), DTE any, 14:00, +5% / -20% / 60 min | 186 | 72.6% | +79,806 | 1,328 / -1,952 | -6,024 | -6,024 | -11,022 |
| 7 | BANKNIFTY 2-ITM, orb (put if up, call if down), DTE any, 09:30, +3% / no stop / 60 min | 77 | 83.1% | +31,018 | 911 / -2,100 | -4,576 | -4,576 | -7,023 |
| 8 | BANKNIFTY 0-ITM, orb (put if up, call if down), DTE any, 09:30, +3% / -10% / 30 min | 77 | 76.6% | +22,446 | 768 / -1,271 | -3,323 | -3,323 | -6,257 |
| 9 | FINNIFTY 0-ITM, ema (call if up, put if down), DTE any, 12:00, +3% / -50% / eod, skip event days | 230 | 81.3% | +39,604 | 599 / -1,685 | -4,527 | -4,527 | -11,146 |
| 10 | FINNIFTY 4-ITM, ema (call if up, put if down), DTE any, 13:00, +5% / no stop / 120 min, VIX low, skip event days | 194 | 67.0% | +53,548 | 1,484 / -2,177 | -9,544 | -9,544 | -19,292 |

Holdout, read once (Rs, 1 lot). Gates:
- win rate >= 60%, net > 0, 40+ trades;
- worst day >= -Rs 13k, max DD >= -Rs 40k;
- net still positive without its top 5 trades;
- p_twin < 0.05 and BH q <= 0.10.

The twins are 500 random-entry copies per candidate: same DTE, random minute between 09:20 and 14:30, coin-flip
side.

| # | holdout: trades | win | net Rs | avg win / avg loss | worst trade | worst day | max DD | p_twin | q | doubled ticks net | gates failed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 49 | 83.7% | -15,562 | 578 / -4,907 | -7,707 | -7,707 | -21,723 | 0.784 | 0.872 | -15,610 | net, top5, p |
| 2 | 49 | 87.8% | -9,426 | 344 / -4,036 | -6,224 | -6,224 | -14,849 | 0.713 | 0.872 | -9,475 | net, top5, p |
| 3 | 37 | 81.1% | -6,333 | 385 / -2,556 | -4,434 | -4,434 | -8,123 | 0.655 | 0.872 | -6,458 | net, n, top5, p |
| 4 | 49 | 85.7% | +20,915 | 1,311 / -4,879 | -11,628 | -11,628 | -18,620 | 0.102 | 0.339 | +20,609 | p |
| 5 | 53 | 79.2% | -7,266 | 1,581 / -6,697 | -16,820 | -16,820 | -24,241 | 0.244 | 0.487 | -7,781 | net, worst_day, top5, p |
| 6 | 175 | 60.6% | -21,978 | 1,327 / -2,356 | -10,132 | -10,132 | -48,109 | 0.226 | 0.487 | -23,570 | net, max_dd, top5, p |
| 7 | 70 | 71.4% | +10,679 | 966 / -1,880 | -4,373 | -4,373 | -13,825 | 0.072 | 0.339 | +10,557 | p |
| 8 | 70 | 62.9% | +3,771 | 853 / -1,298 | -3,922 | -3,922 | -11,160 | 0.096 | 0.339 | +3,629 | top5, p |
| 9 | 218 | 70.6% | -105,369 | 699 / -3,328 | -18,151 | -18,151 | -106,774 | 0.894 | 0.894 | -106,474 | net, worst_day, max_dd, top5, p |
| 10 | 139 | 50.4% | -65,106 | 1,295 / -2,257 | -10,722 | -10,722 | -72,889 | 0.669 | 0.872 | -66,185 | win, net, max_dd, top5, p |

Holdout halves (before / after the Data Brain's 27 Apr 2026 holdout start), tails and the twins:

| # | 3 Nov 2025 - 26 Apr 2026 | 27 Apr 2026 - | worst week | worst month | twin mean net | bootstrap p |
|---|---|---|---|---|---|---|
| 1 | 25 tr, -4,099, win 84% | 24 tr, -11,463, win 83% | -7,707 | -10,933 | -7,069 | 0.844 |
| 2 | 25 tr, +1,213, win 92% | 24 tr, -10,639, win 83% | -6,224 | -8,404 | -5,329 | 0.803 |
| 3 | 18 tr, -2,091, win 83% | 19 tr, -4,242, win 79% | -4,434 | -5,114 | -3,443 | 0.780 |
| 4 | 25 tr, +11,688, win 84% | 24 tr, +9,228, win 88% | -11,628 | -13,547 | -9,404 | 0.128 |
| 5 | 26 tr, -7,053, win 81% | 27 tr, -213, win 78% | -11,720 | -8,940 | -33,084 | 0.578 |
| 6 | 94 tr, +718, win 66% | 81 tr, -22,695, win 54% | -18,896 | -24,552 | -46,141 | 0.766 |
| 7 | 33 tr, +9,762, win 82% | 37 tr, +918, win 62% | -4,150 | -7,276 | -12,701 | 0.190 |
| 8 | 33 tr, +8,554, win 76% | 37 tr, -4,783, win 51% | -3,306 | -4,324 | -10,724 | 0.358 |
| 9 | 112 tr, -69,795, win 69% | 106 tr, -35,575, win 73% | -22,278 | -22,376 | -58,585 | 1.000 |
| 10 | 71 tr, -38,118, win 44% | 68 tr, -26,989, win 57% | -11,377 | -19,630 | -54,692 | 0.991 |

## Why it failed: the high-win-rate trap, measured

- **Design looked perfect.** The top 3 rules won 49/49, 49/49 and 43/43 trades. Each is "buy, take a +3% or +5%
  premium profit, no stop, hold to the end".
  - An option nearly always touches +3-5% at some minute of the day. So the rule "wins" almost every day.
  - When it doesn't, the loss runs to 15:15 with no stop.
  - In one design year that loss simply never came.
  - Among 6.65 million configs, some show a perfect year by luck alone.
- **On the holdout the same rules still won 81-88% of trades, but lost money.**
  - The average loss (-Rs 2,556 to -Rs 4,907) was 7-12 times the average win (Rs 344 to Rs 578).
  - Break-even needs about 90% winners.
- **Higher-target rules did no better.**
  - FINNIFTY ATM, +3% / -50% (#9): 70.6% winners over 218 trades, net -Rs 1,05,369.
  - Its worst day was -Rs 18,151 (6 Apr 2026). Its drawdown of -Rs 1,06,774 is more than the whole account.
- **The three profitable rules have no proven edge.**
  - NIFTY 6-ITM expiry day (#4): +Rs 20,915, 85.7% winners over 49 trades.
  - BANKNIFTY 2-ITM (#7): +Rs 10,679 over 70 trades.
  - BANKNIFTY ATM (#8): +Rs 3,771 over 70 trades.
  - None beat its random-time twins: p_twin 0.102, 0.072 and 0.096, BH q 0.339. #8 also depends on its top 5 trades.
  - #4's worst day is -Rs 11,628 (15 Sep 2026), close to the -Rs 13k monthly account stop.
- **Walk-forward of the selection itself.**
  - The same picking rule on Nov 2024 - Apr 2025 found 74,608 eligible rules. Its top 10 then made -Rs 30,179 on
    May - Oct 2025: 344 trades, 70.1% winners.
  - The full-period scan below gives the same answer for 2023, 2024 and 2025.
  - Picking the best-looking high-win-rate rule from the past does not carry forward.
- **Costs are not the reason.** Doubling every slippage tick moved the holdout nets by at most about Rs 1,600.
  The losses are in the trades themselves.

## For reference: the earlier full-period scan with selling (Aug 2021 - Oct 2025 design; never read on the holdout)

- **What was scanned:** 2,149,992 configs. That is 30 hedged structures, 5 DTEs, 4 entry times and 48 exit sets,
  with direction, VIX and event filters, plus a narrower buying grid.
  - The selling structures: bull put and bear call spreads, iron condors and iron flies, with the hedge always bought.
  - 1,166 configs passed the design gates (100+ trades) and 8 candidates were picked.
- **Best selling candidates on design** (Aug 2021 - Oct 2025):
  - SENSEX expiry-day bull put spread, 13:00, +30% target: 125 trades, 72.0% winners, +Rs 7,917, worst day
    -Rs 986.
  - NIFTY BPS/BCS by TWAP, DTE 3, 13:00, 1x-credit stop, low VIX, events skipped: 152 trades, 67.8%, +Rs 91,920,
    worst day -Rs 4,844, max DD -Rs 17,092.
  - NIFTY BPS/BCS by EMA, expiry day, 1.5x-straddle strikes: 152 trades, 79.6%, +Rs 9,288.
  - SENSEX BPS/BCS by TWAP, expiry day: 121 trades, 87.6%, +Rs 12,617.
- **These were not tested on unseen data** (Boss dropped selling first).
- **Walk-forward of that scan's picking rule:** top 10 by end-2022 lost -Rs 60,363 in 2023 (510 trades, 78.8%
  winners); by end-2023, -Rs 49,840 in 2024 (444, 75.2%); by end-2024, -Rs 46,556 in 2025 (242, 66.5%). Selling
  would very likely have failed the same way.
- **Data limit:** many far-OTM spreads could not be priced on high-volatility days. The lake has strikes only within
  ATM+-10, so the hedge leg falls outside it. Amendment 2 excluded configs priced on under 90% of their days, because
  keeping only the priceable (calm) days flatters selling.

## Tail risk

- **Every candidate is a bought option,** so one trade can lose at most its premium (up to Rs 15k-52k a lot for these rules).
- **Worst holdout days:** -Rs 18,151 (#9), -Rs 16,820 (#5), -Rs 11,628 (#4).
- **#9's drawdown alone would have hit both account stops:** the -Rs 13k month and the -Rs 40k drawdown.
- **Gap days not covered:** the design (Nov 2024 on) contains no 4 Jun 2024- or Mar 2020-style day, and those dates
  were outside Boss's window.

## What this means for Boss

- **No rule passed, so there is no paper proposal.**
  - The nearest misses are #4 (NIFTY 6-ITM on expiry day, fading the EMA20 trend, +5% target, no stop) and #7
    (BANKNIFTY 2-ITM fading the opening range at 09:30).
  - Both made money on the holdout but did not beat random entry times, so they need no app change.
  - Running them on paper would test luck, not an edge.
- **A 65%+ win rate is easy to get and does not by itself make money.**
  - Small target, no or wide stop: this study's best design rules won 100% and then lost on unseen data.
  - Judge any rule by net, average win vs average loss, worst day and drawdown together.
- **Selling options is a new kind of trade for the app.** If Boss reopens it, a paper arm would need:
  - Boss's yes;
  - app work: multi-leg orders and margin checks;
  - for live, Boss's PIN and "AI trades go live".

## Files

- `research/hunt/r48/`:
  - `PREREG.md` (with amendments 1-4);
  - `r48cfg.py`, `r48data.py`, `r48cost.py`, `r48sim.py`, `r48study.py`, `report.py`, `entries.py`;
  - `test_r48.py` (30 tests);
  - `results/design.json`, `results/holdout.json`, `results/design_fullperiod.json`.
- Experiment log: `research/experiments/entries/r48-*.yaml`.
- Data: the Drive lake (not in git).
