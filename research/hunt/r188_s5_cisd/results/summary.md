# R188: can S5 Trail and CISD make more money and win more often?

**Boss, in short (17 Nov 2026).** Not with any of the 23 changes tried. None beat today's rules on money, win rate AND
risk in both the design years and the final months. **Keep both arms as they are.** The closest one, skipping CISD on
the index's expiry day, made a little more and won a little more in both periods, but its worst month and drawdown in
the final months were exactly the same as today's, and it is no better than random skips by the strict test.

You asked: *"Cam we make s5,csid more profitable and more winrate"*. All figures are at 1 lot, after Zerodha's dated
charges and slippage. "Design" = Jan 2023 - 15 Mar 2026; "final" = 1 Apr - 19 Oct 2026 (the arms' own results were
already known on this period, the changes were not); "new month" = 21 Oct - 13 Nov 2026 (too few trades to judge).

## First: the two arms rebuilt exactly

R187 could not test these arms because R123's network forecasts were not on this machine. They were on Drive, so R188
used R123's own saved forecasts (nothing re-trained) and rebuilt both arms:
- **S5 Trail**: 555 design trades, 58.7% won, +Rs 240 a trade; 41 final trades, 82.9% won, +Rs 561 - **identical to
  R128, trade by trade** (all 596 to the paisa). Its worst month, -Rs 18,596, is Sep 2024 (design), not the final period.
- **CISD + network**: 413 design trades of 3,291 setups, 68.5% won, +Rs 261 a trade; 35 final trades, +Rs 77 -
  **identical to R133, trade by trade** (all 3,871 setups to the paisa).
- The network rebuilt for new days reproduced R123's saved forecasts for 1 - 19 Oct exactly, so the new month uses the
  very same network.

"Today's rules" below = these arms as the app runs them: out by 15:15 (the research held to 15:20).

## S5 Trail (today: design 562 trades, 58.5% won, Rs 1.29 lakh; final 40 trades, 85.0% won, Rs 24,988)

| change | design: trades, win, net | final: trades, win, net | why it fails |
|---|---|---|---|
| breakeven at +10% | 600, 66.3%, Rs 99,014 | 41, 85.4%, Rs 16,319 | more wins, less money |
| step lock (+500 at +1,000 ...) | 584, 66.4%, Rs 42,251 | 41, 82.9%, Rs 32,646 | design money collapses |
| breakeven at +Rs 1,000 | 573, 64.7%, Rs 1.33 lakh | 40, 85.0%, Rs 22,656 | passed design; final less money, same win rate |
| index lock | 570, 56.0%, Rs 1.25 lakh | 40, 85.0%, Rs 23,554 | worse everywhere |
| 60% lock from +Rs 500 | 591, 63.8%, Rs 1.03 lakh | 40, 87.5%, Rs 19,281 | more wins, less money |
| stop -Rs 1,500 | 579, 52.8%, Rs 1.34 lakh | 40, 80.0%, Rs 22,461 | fewer wins |
| skip when price and OI fell | 560, 58.8%, Rs 1.33 lakh | 40, 85.0%, Rs 24,988 | almost never fires |
| skip when VIX is down > 3.13% | 511, 59.1%, Rs 1.17 lakh | 31, 90.3%, Rs 25,497 | design money lower |
| skip expiry days | 389, 62.0%, Rs 1.19 lakh | 28, 85.7%, Rs 22,112 | less money; under 30 final trades |
| skip sideways (ADX < 20) | 370, 54.1%, -Rs 7,740 | 25, 88.0%, Rs 18,847 | loses money in design |

- Only "breakeven at +Rs 1,000" passed the design checks, so no combination was formed.
- S5's design drawdown (-Rs 49,469) and worst month (-Rs 19,306, Apr 2024) break your -Rs 40k / -Rs 13k account stop
  with today's rules. Breakeven at +10% and skipping expiry days would have kept it inside the rule in design (worst
  month -Rs 12,556 / -Rs 11,956), but both made less money there and in the final months.

## CISD + network (today: design 413 trades, 67.6% won, Rs 1.05 lakh; final 35 trades, 71.4% won, Rs 3,150)

| change | design: trades, win, net | final: trades, win, net | why it fails |
|---|---|---|---|
| breakeven at +10% | 427, 73.8%, Rs 1.11 lakh | 35, 68.6%, Rs 2,072 | passed design; final worse |
| step lock | 413, 67.6%, Rs 1.21 lakh | 35, 71.4%, Rs 6,156 | more money, same win rate |
| breakeven at +Rs 1,000 / index lock | same as today | same as today | never acts: the 60% lock always comes first |
| 60% lock from +Rs 500 | 437, 76.0%, Rs 83,178 | 35, 71.4%, -Rs 4,936 | more wins, less money |
| premium stop -Rs 1,500 | 431, 59.9%, Rs 66,568 | 36, 58.3%, -Rs 2,738 | worse |
| skip when price and OI fell | 405, 67.4%, Rs 1.00 lakh | 35, 71.4%, Rs 3,150 | worse in design |
| skip when VIX is down > 3.13% | 350, 68.3%, Rs 1.03 lakh | 31, 74.2%, Rs 5,168 | design money lower |
| **skip expiry days** | **366, 69.1%, Rs 1.14 lakh** | **34, 73.5%, Rs 3,420** | **nearest miss: final worst month / drawdown only equal** |
| skip sideways (ADX < 20) | 290, 71.4%, Rs 98,614 | 26, 69.2%, Rs 143 | worse; under 30 final trades |
| stricter network (EV > +50) | 158, 77.2%, Rs 69,711 | 10, 90.0%, Rs 7,757 | far fewer trades and less design money |
| breakeven at +10% + skip expiry days | 379, 76.0%, Rs 1.16 lakh | 34, 70.6%, Rs 2,341 | final worse |

## The new month (21 Oct - 13 Nov 2026, too small to judge)

- S5 Trail: 3 trades, 2 won, +Rs 3,849 with today's rules; most exit changes turned it negative (its one big winner
  was cut short).
- CISD: 8 trades, 7 won, +Rs 6,585 with today's rules. Setups on weekly expiry days could not be priced (the new data
  has no monthly contracts).

## How honest is this?

- 23 changes, each fixed in writing before any number (PREREG.md); the combination and the network threshold were
  chosen on the design years only.
- The strict random test cannot be passed with 500 random twins among 4,925 ideas tried so far (said beforehand), so
  the most any change could show was a lead. None did.
- The final period is small (S5 40 trades, CISD 35) and both arms' own results on it were known before. Most of S5's
  design trades are in 2024.
- Nothing in the app changes.

Full tables (average win / loss, worst month, drawdown, 2x slippage, account stop rule, exit reasons, every period):
`results/report.md`.
