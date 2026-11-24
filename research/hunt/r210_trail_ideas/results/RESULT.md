# R210 result for Boss: hybrid trail, Chandelier exit and higher-timeframe filter on R72, R51 and Liquidity 15+5

**Answer: none of the three ideas passed. Nothing changes in the app.** Tables with every number: `report.md`.

Data: Boss's Drive lake (read 24 Nov through the folder link, checked against the lake's manifest), **only Nov 2024 on**.
Design years 1 Nov 2024 - 31 Mar 2026 (4 walk-forward folds), 1 lot, real Zerodha charges and slippage. Every idea is
compared with each arm's **current exits (V5)**. The baselines reproduce R193 to the rupee (R72 1,595 trades / Rs 2,37,089;
R51 2,240 / Rs 1,24,786; Liquidity 908 / Rs 2,18,210). Because nothing passed the design gate, the Apr - Nov 2026 holdout
was **not read** (it stays unseen for these ideas). BH over the 4,826 ideas ever logged: every q is 1.0.

## What each idea did (design years, net Rs, 1 lot)

| arm | V5 today | Hybrid (index) | Hybrid, no time stop | Hybrid (premium chart) | + Chandelier | 15-min filter | 60-min filter |
|---|---:|---:|---:|---:|---:|---:|---:|
| R72 | 2,37,089 | 4,85,494 | 3,13,106 | 4,88,552 | 2,33,919 | 1,62,555 | 1,55,008 |
| R51 | 1,24,786 | 3,16,058 | 2,09,859 | 3,18,754 | 1,12,509 | 62,376 | 42,747 |
| Liquidity | 2,18,210 | 2,42,146 | 2,96,887 | 2,27,589 | 2,17,058 | 1,68,000 | 1,78,787 |

Trades: R72 V5 1,595 (hybrid 1,529; no-time-stop 1,344; filters 1,151 / 953); R51 V5 2,240 (hybrid 2,103; no-time-stop
1,839; filters 1,604 / 1,262); Liquidity 908 everywhere except the filters (833 / 657). Worst months and drawdowns: `report.md`.

## In plain English

1. **The hybrid trail's money is not from the trail.** On R72 the trail itself sold just 7 of 1,529 trades, on R51 9 of
   2,103: inside 30 minutes a +30% / +45% stage and a 5-minute ATR trail almost never act (as written down before the
   run). The extra money comes from what the hybrid **removes**: V5's Rs 500 step lock (and breakeven at +15% instead of
   +10%). That is R193's finding again (V4 without the step lock made far more than V5). On R72 it beat V5 in all 4 folds
   with a better worst month (-Rs 16,512 vs -Rs 26,420) and sign-flip p 0.004, but it still fails the program-wide BH
   (q 1.0; inside this study alone q 0.045). On R51 its worst month was slightly worse (-Rs 38,007 vs -Rs 36,762). The
   premium-chart version gave the same numbers (an option-chart rule, labelled; it reads the option's own chart).
2. **Letting trades run (no time stop) hurt R72 and R51**: less money than keeping the time stop, and worse worst months
   (R72 -Rs 78,935, drawdown Rs 1,89,728). On Liquidity it made more (Rs 2,96,887) but its worst month doubled
   (-Rs 22,187 vs -Rs 10,129). Fails.
3. **Chandelier exit**: about the same as today or a little worse on all three arms (it sold 23-49 trades). Fails.
4. **Higher-timeframe filter (15 / 60-minute trend)**: cut profit hard on every arm (R51 from Rs 1.25 lakh to Rs 43-62k).
   The arms' breaks against the bigger trend were profitable on average; skipping them loses money. Fails.

## What I'd recommend

- Don't add any of these three ideas. The skill pack's rules of thumb (built for multi-day equity swings) do not fit
  30-minute option trades.
- The one real signal here is old news: **the Rs 500 step lock (V5) costs R72 and R51 money** compared with the plain
  +10% breakeven (V4). R193 showed it on the same months and this study shows it again from another angle. V5 is your
  own choice (16 Nov), and it raises the win rate (R72: V5 65% vs 56% here) - that is the trade-off. If you want, a focused,
  pre-registered "V4 vs V5 on fresh data" check would be the honest next step; nothing changes unless you choose it.

18 ideas logged in `research/experiments/entries/r210-*.yaml` (all rejected, holdout not read). Research only.
