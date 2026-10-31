# R139: market structure (higher highs and lows, lower highs and lows, the structure shift). 0 of 12 pass

31 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"Anything regarding equity shift, lower
low, lower high, something like that, and create a strategy. Search on internet regarding these topics and provide me a
good backtest result."*

- **"Equity shift"** was read as the **market structure shift** (the trend turns when price breaks the last higher low
  or lower high). ICT's **"equilibrium"** (the 50% of a swing leg: premium above, discount below) sounds similar, so it
  is tested too (S8, S9).
- **Signal source:** the index spot for every rule (15-minute wick swings; S6 uses 60-minute and 5-minute). The trade
  buys the ATM option; stops and targets are on the index.

## The answer: no good backtest result exists for these rules. 0 of 12 pass

**Boss asked for a good result. The honest answer is that none of the 12 is good enough to trade.**

- **The best rule is S3, the CHoCH reversal.** After a down-trend (lower highs and lower lows), the first 15-minute
  close above the last lower high buys the call. The mirror buys the put.
  - **Design (Aug 2021 - Mar 2026):** +Rs 185 a trade on 1,663 trades (2x slippage: +Rs 166). It beat random entries
    (p 0.02, q 0.08) and was positive in all 4 folds.
  - **But it breaks Boss's account stop:** a -Rs 2.04 lakh drawdown and a -Rs 61,070 month on 1 lot across the five
    indices (the limits are -Rs 40,000 and -Rs 13,000).
  - **57% of its design profit came from 92 trades in Jan-Mar 2026** (+Rs 1.74 lakh of +Rs 3.08 lakh). In 2025 it made
    +Rs 12 a trade (445 trades).
  - **Holdout (Apr - 19 Oct 2026, read once):** +Rs 61 a trade on 291 trades (2x: +Rs 34). That is **no better than
    random entries** (p 0.56), with a -Rs 53,696 month.
- **Boss's literal idea** ("lower high, lower low": sell the lower high in a down-trend, buy the higher low in an
  up-trend) lost money:
  - S1, the Dow pullback: -Rs 148 a trade on 4,218 trades;
  - S11, the first lower high after a lower low: -Rs 80 on 2,829.
  - Both lost again on the holdout.
- **The internet's favourites lost too:**
  - break of structure (S2: -Rs 312 a trade);
  - swing failure (S7: -Rs 204);
  - ICT's 50% equilibrium entry (S8: -Rs 264);
  - the multi-timeframe version (S6: -Rs 301).
- **No rule comes near Boss's 65% win rate.** The range is 33% to 49% in design. The highest, S9 at 49%, loses money
  because its wins are smaller than its losses.

**Nothing goes on paper or live.** CLAUDE.md: failed ideas stay notes.

## Every rule, design (1 Aug 2021 - 15 Mar 2026; 1 lot, all five indices, after charges and slippage)

Nothing was fitted, so every fold (2023, 2024, 2025, 2026 Q1) is out of sample. p = vs 100 random-entry twins per trade
(the same day, side, stop and target, entry at a random later minute). q = Benjamini-Hochberg over the 12. S12 is
2023 on only, because the network's walk-forward views start there; its p is R132's random-subset test.

| rule | trades | win (vs 65%) | avg win / avg loss | net per trade (2x slip) | total, 1 lot | max drawdown | worst month | folds + | p | q | fails |
|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 Dow pullback (higher low / lower high) | 4,218 | 38% | Rs 2,553 / -Rs 1,792 | Rs -148 (Rs -169) | Rs -624,097 | Rs -659,295 | Rs -97,749 | 0 of 4 | 0.050 | 0.149 | net, folds, q, account |
| S2 BOS continuation | 1,805 | 40% | Rs 3,166 / -Rs 2,614 | Rs -312 (Rs -331) | Rs -563,097 | Rs -709,290 | Rs -104,588 | 1 of 4 | 0.960 | 1.000 | net, twins, folds, q, account |
| **S3 CHoCH reversal** | 1,663 | 43% | Rs 3,636 / -Rs 2,449 | Rs 185 (Rs 166) | Rs 307,806 | Rs -203,995 | Rs -61,070 | 4 of 4 | 0.020 | 0.079 | account |
| S4 1-2-3 reversal | 303 | 41% | Rs 3,026 / -Rs 1,943 | Rs 91 (Rs 71) | Rs 27,529 | Rs -50,190 | Rs -22,510 | 4 of 4 | 0.168 | 0.289 | twins, q, account |
| S5 MSS, then first higher low | 957 | 38% | Rs 2,522 / -Rs 1,763 | Rs -142 (Rs -163) | Rs -135,993 | Rs -187,615 | Rs -38,100 | 2 of 4 | 0.168 | 0.289 | net, twins, folds, q, account |
| S6 60-min trend + 5-min CHoCH | 1,625 | 35% | Rs 2,827 / -Rs 1,982 | Rs -301 (Rs -322) | Rs -489,459 | Rs -512,947 | Rs -79,861 | 1 of 4 | 0.950 | 1.000 | net, twins, folds, q, account |
| S7 swing failure (SFP) | 3,409 | 33% | Rs 1,800 / -Rs 1,182 | Rs -204 (Rs -227) | Rs -695,859 | Rs -696,160 | Rs -63,838 | 0 of 4 | 1.000 | 1.000 | net, twins, folds, q, account |
| S8 ICT equilibrium 50% | 3,103 | 45% | Rs 1,776 / -Rs 1,911 | Rs -264 (Rs -285) | Rs -819,547 | Rs -843,173 | Rs -67,872 | 0 of 4 | 0.822 | 1.000 | net, twins, folds, q, account |
| S9 shallow 25% pullback | 3,878 | 49% | Rs 1,195 / -Rs 1,253 | Rs -66 (Rs -87) | Rs -254,992 | Rs -296,651 | Rs -44,666 | 0 of 4 | 0.010 | 0.059 | net, folds, account |
| S10 BOS, first hour | 725 | 38% | Rs 4,283 / -Rs 3,135 | Rs -321 (Rs -341) | Rs -232,815 | Rs -328,477 | Rs -45,447 | 1 of 4 | 0.842 | 1.000 | net, twins, folds, q, account |
| S11 first lower high after a lower low | 2,829 | 38% | Rs 2,548 / -Rs 1,705 | Rs -80 (Rs -101) | Rs -226,503 | Rs -390,752 | Rs -63,193 | 2 of 4 | 0.010 | 0.059 | net, folds, account |
| S12 S1 + R123 network | 338 | 40% | Rs 2,479 / -Rs 1,651 | Rs 11 (Rs -6) | Rs 3,712 | Rs -47,751 | Rs -15,357 | 2 of 4 | 0.158 | 0.289 | net, twins, folds, q, account |

How to read p for S1, S9 and S11: p is low because random entries with the same stops lose **even more**. The rule is
better than chance but still loses money after costs.

## Holdout (1 Apr - 19 Oct 2026, read once at 18:44-18:45 UTC, 31 Oct, after the design results were pushed)

| rule | trades | win | avg win / avg loss | net per trade (2x slip) | total, 1 lot | max drawdown | worst month | vs random (p) |
|---|---|---|---|---|---|---|---|---|
| S1 Dow pullback (higher low / lower high) | 573 | 37% | Rs 2,773 / -Rs 2,134 | Rs -310 (Rs -338) | Rs -177,385 | Rs -240,416 | Rs -70,992 | 0.83 |
| S2 BOS continuation | 244 | 39% | Rs 2,830 / -Rs 2,815 | Rs -617 (Rs -643) | Rs -150,628 | Rs -167,352 | Rs -41,551 | 0.98 |
| **S3 CHoCH reversal** | 291 | 44% | Rs 3,616 / -Rs 2,771 | Rs 61 (Rs 34) | Rs 17,698 | Rs -119,783 | Rs -53,696 | 0.56 |
| S4 1-2-3 reversal | 30 | 33% | Rs 4,034 / -Rs 2,118 | Rs -68 (Rs -97) | Rs -2,031 | Rs -22,020 | Rs -7,686 | 0.15 |
| S5 MSS, then first higher low | 144 | 41% | Rs 3,275 / -Rs 2,103 | Rs 101 (Rs 72) | Rs 14,481 | Rs -56,318 | Rs -27,860 | 0.09 |
| S6 60-min trend + 5-min CHoCH | 240 | 35% | Rs 3,780 / -Rs 2,242 | Rs -109 (Rs -141) | Rs -26,270 | Rs -107,157 | Rs -42,534 | 0.39 |
| S7 swing failure (SFP) | 480 | 33% | Rs 1,943 / -Rs 1,276 | Rs -209 (Rs -240) | Rs -100,543 | Rs -111,731 | Rs -24,160 | 0.98 |
| S8 ICT equilibrium 50% | 389 | 40% | Rs 2,102 / -Rs 2,032 | Rs -364 (Rs -393) | Rs -141,513 | Rs -168,844 | Rs -42,066 | 0.77 |
| S9 shallow 25% pullback | 501 | 47% | Rs 1,211 / -Rs 1,519 | Rs -249 (Rs -278) | Rs -124,725 | Rs -128,854 | Rs -40,830 | 0.56 |
| S10 BOS, first hour | 91 | 35% | Rs 3,146 / -Rs 3,441 | Rs -1,125 (Rs -1,149) | Rs -102,336 | Rs -112,463 | Rs -33,110 | 1.00 |
| S11 first lower high after a lower low | 392 | 38% | Rs 2,724 / -Rs 2,035 | Rs -214 (Rs -243) | Rs -83,917 | Rs -133,331 | Rs -38,588 | 0.63 |
| S12 S1 + R123 network | 20 | 60% | Rs 2,348 / -Rs 2,121 | Rs 561 (Rs 542) | Rs 11,213 | Rs -5,879 | Rs -5,434 | 0.11 |

The holdout gate (average net > 0 at 1x and 2x slippage on 50 or more trades) was met by S3 and S5 only. Both had
already failed design (S3 on the account stop, S5 on money and folds), and neither beat random entries in the holdout.
S12 had 20 trades (60% won, +Rs 561 each): too few to judge.

## The best rules, per index and per year (design unless marked)

**S3 CHoCH reversal:**
- **By index (net per trade):**
  - design: NIFTY +Rs 76 (460 trades), BANKNIFTY +Rs 141 (443), FINNIFTY +Rs 183 (291), MIDCPNIFTY +Rs 640 (231),
    SENSEX +Rs 39 (238);
  - holdout: NIFTY +Rs 75 (69), BANKNIFTY +Rs 194 (64), FINNIFTY -Rs 221 (42), MIDCPNIFTY +Rs 309 (54), SENSEX
    -Rs 117 (62).
- **By year:** 2021 -Rs 346 (76 trades), 2022 +Rs 125 (244), 2023 +Rs 71 (361), 2024 +Rs 221 (445), 2025 +Rs 12 (445),
  2026 Q1 +Rs 1,896 (92). Holdout 2026 Apr-Oct: +Rs 61 (291).
- **No single index fits the account stop either:**
  - NIFTY alone had a -Rs 72,249 drawdown;
  - even MIDCPNIFTY, the best, had -Rs 33,676 and a -Rs 14,547 month;
  - picking an index after seeing this would be fitting anyway.
- **Exits:** 75% of trades ran to 15:20, 18% hit the stop, 6% reached 2R.

**S4 1-2-3 reversal:**
- design +Rs 91 a trade on 303 trades, positive in 4 of 4 folds, but not better than random (p 0.17);
- holdout 30 trades, -Rs 68 each.

**S5 MSS then the first higher low:**
- design -Rs 142 a trade on 957 trades;
- holdout +Rs 101 on 144: BANKNIFTY and NIFTY up, the other three down.

## What the internet claimed, and what Indian index data shows

Full sources and exact rules: `research/hunt/r139_market_structure/SOURCES.md`.

| idea | what the sources claim | evidence behind the claim | R139 on Indian index options |
|---|---|---|---|
| Dow theory: trade with HH+HL / LH+LL (S1) | the trend persists; buy the higher low | Brown, Goetzmann & Kumar 1998: Hamilton's own 1902-29 calls added value; no test of a mechanical intraday pivot rule | **loses**: -Rs 148 (4,218), holdout -Rs 310 (573) |
| Break of structure (S2, S10) | continuation after a close beyond the last swing | SMC teachers (LuxAlgo, TheRightTrader) say themselves it is untested; academic range-break rules (Brock et al. 1992) faded out of sample and after costs (Sullivan, Timmermann & White 1999; Bajgrowicz & Scaillet 2012) | **loses**: -Rs 312 (1,805); first hour -Rs 321 (725) |
| CHoCH / MSS reversal (S3) | the first break against the trend marks the turn | no published sample-sized test found | **best of the 12** in design (+Rs 185, 1,663), but past the account stop, and the holdout is no better than random |
| 1-2-3 reversal (S4) | Sperandeo's trend-change rule; Bulkowski: 73% of bullish daily 1-2-3s on stocks reached a 20% move (no entries, stops or costs) | a pattern count, not a trading test | +Rs 91 on 303, not better than random; holdout -Rs 68 (30) |
| Swing failure / Turtle Soup (S7) | fade the false break | Oxford Capital's futures test graded Turtle Soup poorly | **loses**: -Rs 204 (3,409). R135 already found that pokes on Indian indices continue more than they reverse |
| ICT equilibrium 50%, discount entry (S8) | buy only below 50% of the leg | no test found | **loses**: -Rs 264 (3,103) |
| Shallow pullback (S9; R135's finding) | shallow pullbacks hold more | R135 (descriptive, no costs) | holds more often (49% won, the most of the 12), but the 1:1 reward does not cover costs: -Rs 66 (3,878) |
| Multi-timeframe (S6) | trade the small-chart shift in the big chart's direction | no test found | **loses**: -Rs 301 (1,625), like R131's 60-minute filter |
| Any filter (S12, R123 network) | a filter lifts a weak rule | R132-R137: the only filter that held up | +Rs 11 on 338 (design), 20 holdout trades: too few |

**The pattern from R130-R139 holds:** structure rules from the books and videos either lose after option costs, or make
a small design profit that does not survive new months. Academic work on simple technical rules says the same (Park &
Irwin 2007; Sullivan, Timmermann & White 1999). No peer-reviewed test of swing-structure rules on NIFTY or BANKNIFTY was
found.

## What is new compared with what we knew

1. **Reversal after a trend (CHoCH) did better than continuation (BOS)** here. That is the opposite of R135's "breaks
   continue". But R135 measured breaks of single swings and levels, while S3 needs a full lower-high, lower-low trend
   before the break. The edge was small and unstable (2025 about zero), so this is a note, not a finding.
2. **Requiring a Dow trend does not rescue BOS.** S2 lost more than R137's strong-swing break (+Rs 156 in design).
3. **ICT's premium/discount entry loses after costs.** Shallow pullbacks do hold more often (R135), but not enough to
   pay.
4. **The 60-minute trend filter hurt again** (S6), as in R131 T2.

## What is proposed

- **Nothing goes on paper or live.** All 12 are rejected.
- One note for Boss, not a proposal: if Boss wants to watch S3 (CHoCH reversal) anyway, it would have to be a forward
  log only. It failed the account-stop gate, and CLAUDE.md allows no exceptions beyond S5 Trail and S3-V2-E3.

## Method and honesty notes

- Pre-registered before any number: `research/hunt/r139_market_structure/PREREG.md` (commit 9be3fc2). The design results
  were pushed before the one holdout read (lock file in the cache, `HOLDOUT_READ.json`, 18:44:40-18:45:46 UTC).
- Swings: R131's wick pivots (3 bars each side), known only 3 bars later; the look-ahead guard test re-finds every
  sampled entry after deleting all later minutes.
- Options: the ATM option of the nearest expiry that does not expire that day (R131-R138). So exits are always 15:20,
  and days when both the weekly and monthly expire are left out.
- Costs: dated charges and liquidity-aware slippage (`Costs('dated')`, `Fills('liq')`), also at 2x slippage; 1 lot at
  today's lot sizes.
- Data: the Drive lake (`gdrive:IraAlgo-data-lake`, index and option minutes), copied 31 Oct 2026; R123's N1
  predictions. No raw data in git.
- Code and tests: `r139.py`, `test_r139.py` (27 tests). Results: `results/design.json`, `results/holdout.json`.
  Experiment entries: `research/experiments/entries/r139-s1.yaml` to `r139-s12.yaml`.
