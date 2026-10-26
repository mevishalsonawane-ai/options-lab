# R105E: buy only a liquid option (an option-liquidity check on Breadth Donchian and R51)

26 Oct 2026. Research only. Pre-registered in `research/hunt/r105e_optliq/PREREG.md` (721fa29) before any number;
design frozen before the one final read. Code, 4 tests, numbers: `research/hunt/r105e_optliq/`.

Boss, 26 Oct: *"If we add liquidity check in this?"* -> option liquidity.

## The answer

**No. Skipping the breakouts whose option was thin made less money and did not raise the win rate.** None of the
6 tests passed. Nothing changes.

| final period 1 Apr - 13 Oct 2026, 1 lot | R72 Breadth Donchian | R51 Donchian 20 + Supertrend |
|---|---|---|
| **today (no check)** | 1,202 trades, 46% win, **Rs 1,71,366**, max DD Rs 61,884 | 1,670, 45%, **Rs 1,34,743**, Rs 81,683 |
| Q1: the option traded >= 20 lots in the 5 minutes before entry | 1,070, 45%, Rs 1,03,956, Rs 87,979 | 1,493, 44%, Rs 1,01,059, Rs 88,262 |
| Q2: >= 100 lots | 914, 46%, Rs 1,42,320, **Rs 42,949** | 1,283, 44%, Rs 84,518, Rs 61,977 |
| Q3: it traded in every one of those 5 minutes | 974, 44%, Rs 1,13,825, Rs 79,727 | 1,413, 44%, Rs 74,756, Rs 77,295 |

- **Design** (20 Nov 2024 - 31 Mar 2026): about the same as today. R72 Rs 2.09-2.19 lakh vs Rs 2.17 lakh. R51 Q1
  Rs 60k vs Rs 50k, but Q2 / Q3 were worse.
- **Walk-forward:** worse in 5 of 6.
- **Random skips:** none beat them (best p 0.14).
- **BH:** q 1.0 over 273 tests.
- **The 4 unseen sessions (14-19 Oct)** were mixed and tiny (18-45 trades).
- **The average loss barely moves** (R72 -Rs 1,174 to -Rs 1,234 vs -Rs 1,217 today).
- **The one plus:** R72 with >= 100 lots had a smaller final drawdown (Rs 42,949 vs Rs 61,884), at Rs 29k less
  profit. It was not better in design or walk-forward, so it is not a rule.

**Why:** many of the arms' best breaks are fast moves where the 1-ITM option has barely traded in the minutes before
(for example, the opening bar and sudden breaks). Skipping them throws away good trades. The backtest already charges
those buys 2-4 ticks of extra slippage, and they still pay.

## The one real caution

The lake has no historical bid / ask, so the true spread on a thin option is unknown. A real spread can be wider than
the backtest's 2-4 ticks. The app's own spread check (half-spread over 0.30%, now in shadow, logged only) measures it
live. If its log shows the paper fills on thin options costing much more than the backtest assumes, that is the
evidence to switch it on. Today's log already shows two FINNIFTY fills with half-spreads of 0.54% and 0.75%. Keep it
logging.

Logged: `research/experiments/entries/r105e-*.yaml` (6, rejected). Signal on the index; the check reads only the
bought option's own volume before entry.
