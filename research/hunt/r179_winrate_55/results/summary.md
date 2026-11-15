# R179: a win rate above 55% with profit kept

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
