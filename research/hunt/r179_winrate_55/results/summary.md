# R179: a win rate above 55% with profit kept

## Update: a Rs 1,000 maximum loss instead of -15% (Amendment A, Boss 15 Nov)

You asked for a Rs 1,000 maximum loss on the picker + breakeven rule (and on Liquidity). The stop was set where a trade
nets -Rs 1,000 after charges and slippage (used wherever -15% would lose more). Same year, same everything else.

**The win rate falls back to about 41% and profit drops by half or more; it failed every gate.**

1 lot, whole year (today's rules / picker + breakeven with -15% / **picker + breakeven with Rs 1,000 max loss**):

| | win rate | net profit | average loss | biggest loss | worst month |
|---|---|---|---|---|---|
| R51 | 45.1% / 55.5% / **41.8%** | Rs 2.21 L / Rs 2.50 L / **Rs 75k** | Rs 1,259 / Rs 1,450 / **Rs 972** | Rs 8,269 / Rs 8,269 / **Rs 8,269** | -Rs 55k / -Rs 21k / **-Rs 24k** |
| R72 | 46.3% / 56.6% / **41.0%** | Rs 2.98 L / Rs 3.25 L / **Rs 1.59 L** | Rs 1,279 / Rs 1,484 / **Rs 958** | Rs 8,269 / Rs 6,315 / **Rs 3,781** | -Rs 73k / -Rs 18k / **-Rs 26k** |
| Liquidity (no picker) | 39.2% / 41.3% / **36.1%** | Rs 1.18 L / Rs 1.00 L / **Rs 78k** | Rs 968 / Rs 960 / **Rs 782** | Rs 4,757 / Rs 4,757 / **Rs 2,565** | -Rs 38k / -Rs 38k / **-Rs 21k** |

- **Why:** for most options -15% is more than Rs 1,000 a lot, so the cap is the tighter stop on about half the trades
  (R51: 688 of 1,394 trades ended on it). Many of those would have come back and won under -15%.
- **The cap is not exact:** it is a stop order, so a fast move sells below it. At 1 lot, 165 R51 trades (12%) lost more
  than Rs 1,000, mostly by a little (slippage); the worst was -Rs 8,269 when the price jumped past the stop.
- **Rs 1,000 for the whole trade at 2-3 lots is much worse:** the stop sits so close that the win rate falls to 17-27% and
  profit almost disappears (R51 at 3 lots: Rs 31k vs Rs 9.47 L today). Rs 1,000 a lot is the only workable version.
- 3 lots, Rs 1,000 a lot, whole year: R51 Rs 4.09 L (today Rs 9.47 L), R72 Rs 6.34 L (Rs 11.05 L), Liquidity Rs 2.81 L
  (Rs 4.16 L).

**Bottom line:** a tight rupee cap and a high win rate pull in opposite directions on these arms. The 55% result needs
the -15% stop (about Rs 1,450 average loss at 1 lot). If a smaller loss per trade matters most, the -15% stop with the
picker + breakeven lock already keeps the worst month to about -Rs 21k at 1 lot, smaller than with the Rs 1,000 cap.

## Earlier: R179's first test (picker + breakeven lock)

**Boss, in short (15 Nov 2026).** Yes, it can be done on **R51**, and very nearly on R72, with **today's exits + the R151
picker + a breakeven lock** (once the option is up 10%, the stop moves to the price where the trade nets Rs 0). On the
Rs 1,500 rule none of the combinations got above 50%. Last year (20 Oct 2025 - 19 Oct 2026), 1 lot, after charges and
slippage.

**The winner: V4 = today's rules + picker + breakeven lock**

| | today's rules | V4 | gates |
|---|---|---|---|
| **R51** whole year | 45.1% win, Rs 2.21 L (3,084 trades) | **55.5% win, Rs 2.50 L** (1,367 trades) | win and profit gates pass |
| **R51** final (Apr - Oct 2026, the clean test) | 44.8%, Rs 1.32 L | **55.1%, Rs 1.45 L** | pass |
| R72 whole year | 46.3%, Rs 2.98 L (2,281) | **56.6%, Rs 3.25 L** (1,069) | pass |
| R72 final | 45.8%, Rs 1.70 L | 53.5%, Rs 1.67 L | **fails** (win 53.5%, Rs 3k less) |

- R51 V4 also has far smaller swings: max drawdown Rs 36k vs Rs 94k; at 3 lots win 55.6%, Rs 8.88 L vs Rs 9.47 L
  (slightly less money at 3 lots).
- **It still fails two formal gates:** the random-skip check (p = 0.002, but with 4,038 tests counted BH q = 1.0) and the
  account stop rule (worst month -Rs 21k in the final at 1 lot; today's rules break it too, at -Rs 55k).

**Important: compare with the picker alone, which is already live in your app on R72 / R51.**

| 1 lot, whole year | picker alone (P) | picker + breakeven (V4) |
|---|---|---|
| R51 | 50.8% win, **Rs 2.93 L** | **55.5% win**, Rs 2.50 L |
| R72 | 51.9% win, **Rs 3.72 L** | **56.6% win**, Rs 3.25 L |

So the breakeven lock buys about **+5 points of win rate for about Rs 43k-47k less profit a year** compared with what the
app already does. Against today's plain rules it is better on both win rate and profit; against the live picker it is a
trade-off: higher win rate, a bit less money.

**The Rs 1,500 rule:** with the picker 43-44% win (more profit than the rule alone: R72 Rs 1.79 L -> Rs 2.43 L); with
the breakeven lock 45-46% (less profit); with both 49% (R72 Rs 2.11 L, R51 Rs 1.20 L). None reaches 55%, and all earn less
than today's rules + picker. **Liquidity** (no picker): breakeven lock 41% win, Rs 1.00 L vs today's 39%, Rs 1.18 L.

**Honest notes:** the picker was trained on trades up to Mar 2026, so the Oct 2025 - Mar 2026 part is in-sample for it;
the Apr - Oct 2026 final is the fair test, and R51 V4 passed there (55.1%, Rs 1.45 L vs Rs 1.32 L). Apr - Oct 2026 was also
read by many earlier studies, including R162's breakeven lock. Nothing in the app changes from R179; if you want it, the
safe path is a paper / shadow trial of "picker + breakeven lock" on R51 first.
