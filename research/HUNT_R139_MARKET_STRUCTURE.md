# R139: market structure (higher highs and lows, lower highs and lows, the structure shift). 0 of 12 pass; enhancements 0 of 19; reversal 0 of 12

31 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"Anything regarding equity shift, lower
low, lower high, something like that, and create a strategy. Search on internet regarding these topics and provide me a
good backtest result."*

- **"Equity shift"** was read as the **market structure shift** (the trend turns when price breaks the last higher low
  or lower high). ICT's **"equilibrium"** (the 50% of a swing leg: premium above, discount below) sounds similar, so it
  is tested too (S8, S9).
- **Signal source:** the index spot for every rule (15-minute wick swings; S6 uses 60-minute and 5-minute). The trade
  buys the ATM option; stops and targets are on the index.

> **Update, same day (Amendments A and B, at the end):** Boss asked to enhance S3 with liquidity, volume, order flow and
> SMC (19 versions), and to try reversal logic (12 versions). **None passes.** The design favourites lost on the
> newer months; flipping the losing rules still loses.

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


---

# Amendment A: enhancing S3 with liquidity, volume, order flow, SMC and more. 0 of 19 pass

Boss, 31 Oct: *"Enhance it, try to add multiple parameters like liquidity, volumn and order flow, smc, and all things
and find how could we enhace it"*. Pre-registration: `research/hunt/r139_market_structure/PREREG_A.md` (pushed before
any number).

**The answer: nothing makes S3 good enough. The add-ons that looked best in design fell apart on the newer months.**

- **Design:** 9 add-ons kept S3 positive and better than random entries, but **every one still broke Boss's account
  stop** (-Rs 40k drawdown, -Rs 13k month on 1 lot over the five indices):
  - displacement L2: +Rs 219 a trade on 819;
  - fair value gap L3: +Rs 164 on 697;
  - option-volume surge V1: +Rs 260 on 334;
  - OI flow OF1: +Rs 158 on 510;
  - call-vs-put volume OF2: +Rs 173 on 1,479;
  - VIX C1: +Rs 255 on 864;
  - 60-min trend C2: +Rs 108 on 1,113;
  - day stop D1: +Rs 185 on 1,662;
  - trailing exit X2: +Rs 188 on 1,667.
- **The only add-on inside the account stop** was the stop cap (D2, skip when the stop is more than 1.5 x ATR away):
  +Rs 617 a trade, but on just **117** trades (300 needed). In the holdout it had 8 trades and lost Rs 2,213 each.
- **Holdout (Apr - 19 Oct 2026, already seen for S3; read once more, 31 Oct). The design favourites lost:**
  - displacement: -Rs 184 a trade (144 trades);
  - volume surge: -Rs 298 (68);
  - OI flow: -Rs 227 (98);
  - VIX: +Rs 5 (107);
  - K3, the stack of the three best design filters (L2 + V1 + C1): -Rs 1,719 (18).
- **What did well in the holdout had failed design:**
  - the liquidity sweep (L1): +Rs 366 on 125 trades;
  - morning only (C3): +Rs 443 on 143.
  When the winners swap places between periods, the differences are noise.
- **The 60% premium lock (X1)** lifts the win rate to **68%** (above Boss's 65%) but **loses money**: -Rs 48 a trade
  on 1,783 in design, -Rs 39 on 313 in the holdout. It cuts the big winners that pay for S3.
- **The order-block retest entry (L5)** is worse than entering at once: -Rs 82 (622), holdout -Rs 628 (85).
- **The full SMC stack** (sweep + displacement, K1): +Rs 234 on 444 in design, not better than random (p 0.22).
  **The flow stack** (K2): -Rs 280 on 100.

Volume and order flow come from the option chain (ATM ± 5 strikes). The index has no volume, and the lake has no tick
or bid/ask history for 2021-2026. They are context filters around the index signal.

| version | what | design trades | win | avg win / loss | net per trade (2x) | total | max DD | worst month | folds + | p | q | holdout (seen): trades, net per trade (2x), p | fails |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | S3 as in R139 (the base, for reference; not a new test) | 1,663 | 43% | Rs 3,636 / -Rs 2,449 | Rs 185 (Rs 166) | Rs 307,806 | Rs -203,995 | Rs -61,070 | 4 of 4 | 0.010 | nan | 291, Rs 61 (Rs 34), 0.62 | reference |
| L1 | liquidity sweep | 812 | 42% | Rs 3,838 / -Rs 2,579 | Rs 148 (Rs 128) | Rs 120,104 | Rs -108,019 | Rs -52,054 | 2 of 4 | 0.109 | 0.172 | 125, Rs 366 (Rs 341), 0.25 | twins, folds, q, account |
| L2 | displacement | 819 | 43% | Rs 3,799 / -Rs 2,453 | Rs 219 (Rs 200) | Rs 179,238 | Rs -144,774 | Rs -43,996 | 3 of 4 | 0.010 | 0.038 | 144, Rs -184 (Rs -210), 0.73 | account, holdout |
| L3 | fair value gap left between the low and the CHoCH bar | 697 | 44% | Rs 3,365 / -Rs 2,385 | Rs 164 (Rs 145) | Rs 114,574 | Rs -107,220 | Rs -35,485 | 4 of 4 | 0.040 | 0.075 | 123, Rs 71 (Rs 47), 0.04 | account |
| L4 | discount | 512 | 45% | Rs 2,790 / -Rs 1,981 | Rs 144 (Rs 124) | Rs 73,532 | Rs -56,153 | Rs -36,715 | 4 of 4 | 0.406 | 0.454 | 93, Rs 54 (Rs 29), 0.01 | twins, q, account |
| L5 | order-block retest entry | 622 | 40% | Rs 2,336 / -Rs 1,697 | Rs -82 (Rs -103) | Rs -51,222 | Rs -108,936 | Rs -18,224 | 2 of 4 | 0.653 | 0.690 | 85, Rs -628 (Rs -657), 0.95 | net, twins, folds, q, account, holdout |
| V1 | volume surge | 334 | 44% | Rs 3,253 / -Rs 2,065 | Rs 260 (Rs 243) | Rs 86,811 | Rs -81,572 | Rs -31,537 | 3 of 4 | 0.040 | 0.075 | 68, Rs -298 (Rs -325), 0.74 | account, holdout |
| OF1 | OI order flow | 510 | 44% | Rs 2,960 / -Rs 2,020 | Rs 158 (Rs 139) | Rs 80,558 | Rs -85,423 | Rs -18,058 | 4 of 4 | 0.020 | 0.054 | 98, Rs -227 (Rs -251), 0.70 | account, holdout |
| OF2 | buying flow | 1,479 | 43% | Rs 3,689 / -Rs 2,494 | Rs 173 (Rs 154) | Rs 256,198 | Rs -180,551 | Rs -52,292 | 4 of 4 | 0.010 | 0.038 | 252, Rs 17 (Rs -8), 0.57 | account, holdout |
| C1 | India VIX above its 20-session median | 864 | 45% | Rs 3,889 / -Rs 2,693 | Rs 255 (Rs 235) | Rs 220,568 | Rs -141,703 | Rs -67,527 | 3 of 4 | 0.030 | 0.071 | 107, Rs 5 (Rs -22), 0.28 | account, holdout |
| C2 | the 60-minute trend is not against the trade | 1,113 | 43% | Rs 3,560 / -Rs 2,538 | Rs 108 (Rs 89) | Rs 120,527 | Rs -160,007 | Rs -44,350 | 3 of 4 | 0.020 | 0.054 | 200, Rs -65 (Rs -91), 0.85 | account, holdout |
| C3 | morning only | 798 | 41% | Rs 4,525 / -Rs 3,029 | Rs 86 (Rs 66) | Rs 68,291 | Rs -150,974 | Rs -41,012 | 2 of 4 | 0.149 | 0.217 | 143, Rs 443 (Rs 418), 0.70 | twins, folds, q, account |
| C4 | R123's N1 network expected value > 0 (2023 on) | 178 | 42% | Rs 3,693 / -Rs 2,093 | Rs 313 (Rs 297) | Rs 55,659 | Rs -35,228 | Rs -17,728 | 3 of 4 | 0.383 | 0.454 | 7, Rs 1,691 (Rs 1,674), 0.15 | n, twins, q, account, holdout |
| D1 | day stop | 1,662 | 43% | Rs 3,639 / -Rs 2,449 | Rs 185 (Rs 165) | Rs 306,709 | Rs -203,995 | Rs -61,070 | 4 of 4 | 0.010 | 0.038 | 291, Rs 61 (Rs 34), 0.55 | account |
| D2 | stop cap | 117 | 49% | Rs 3,057 / -Rs 1,701 | Rs 617 (Rs 596) | Rs 72,205 | Rs -18,211 | Rs -6,204 | 3 of 4 | 0.010 | 0.038 | 8, Rs -2,213 (Rs -2,254), 0.42 | n, holdout |
| X1 | exit | 1,783 | 68% | Rs 1,015 / -Rs 2,288 | Rs -48 (Rs -73) | Rs -86,402 | Rs -188,204 | Rs -49,233 | 1 of 4 | 0.396 | 0.454 | 313, Rs -39 (Rs -73), 0.34 | net, twins, folds, q, account, holdout |
| X2 | exit | 1,667 | 44% | Rs 3,523 / -Rs 2,410 | Rs 188 (Rs 168) | Rs 313,279 | Rs -189,549 | Rs -61,324 | 4 of 4 | 0.010 | 0.038 | 294, Rs 37 (Rs 10), 0.66 | account |
| K1 | SMC stack | 444 | 42% | Rs 4,133 / -Rs 2,629 | Rs 234 (Rs 214) | Rs 103,973 | Rs -86,146 | Rs -36,359 | 3 of 4 | 0.218 | 0.296 | 69, Rs 326 (Rs 301), 0.49 | twins, q, account |
| K2 | flow stack | 100 | 41% | Rs 2,058 / -Rs 1,906 | Rs -280 (Rs -297) | Rs -28,031 | Rs -46,279 | Rs -7,803 | 1 of 4 | 0.723 | 0.723 | 26, Rs -632 (Rs -652), 0.60 | n, net, twins, folds, q, account, holdout |
| K3 | every single filter that beat S3 per trade in design (>= 300 trades), together (L2 + V1 + C1) | 130 | 45% | Rs 3,738 / -Rs 2,406 | Rs 335 (Rs 317) | Rs 43,537 | Rs -34,249 | Rs -21,645 | 2 of 4 | 0.079 | 0.137 | 18, Rs -1,719 (Rs -1,746), 0.92 | n, twins, folds, q, account, holdout |

A0 is S3 itself, for reference. p for C4 is R132's random-subset test. K3's filters were chosen by the pre-registered
rule from the design results: L2 + V1 + C1.

---

# Amendment B: reversal logic. 0 of 12 pass

Boss, 31 Oct: *"Check the adding reversal logic too and check if it could help??"* Pre-registration: `PREREG_B.md`.
Two meanings were tested:
- **flip:** take each R139 rule the other way (F1-F11);
- **stop-and-reverse on S3** (SR3): when S3 is stopped, buy the opposite option at once, with the stop back at S3's
  entry.

**The answer: reversal does not help.**

- **Flipping a losing rule does not make it a winner.**
  - 9 of 11 flipped rules still lose in design. Both directions pay the option's time decay and costs.
  - Boss's "sell the lower high" flipped (F1): -Rs 125 a trade on 4,247.
  - The two small positives (F6 +Rs 58 on 1,586, F8 +Rs 12 on 2,973) break the account stop and lost in the
    holdout.
- **Stop-and-reverse slightly lowers S3:**
  - design: +Rs 174 a trade on 1,868, against S3's +Rs 185, and positive in only 2 of 4 folds;
  - the reverse legs alone: 262 trades, +Rs 93 each;
  - holdout: +Rs 75 on 325 (the reverse legs: 43 trades, +Rs 16 each), not better than random (p 0.49).

| version | what | design trades | win | avg win / loss | net per trade (2x) | total | max DD | worst month | folds + | p | q | holdout (seen): trades, net per trade (2x), p | fails |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| F1 | S1 taken the other way | 4,247 | 37% | Rs 2,558 / -Rs 1,714 | Rs -125 (Rs -145) | Rs -529,134 | Rs -567,022 | Rs -97,852 | 1 of 4 | 1.000 | 1.000 | 574, Rs 9 (Rs -20), 0.06 | net, twins, folds, q, account, holdout |
| F2 | S2 taken the other way | 1,818 | 40% | Rs 3,151 / -Rs 2,389 | Rs -171 (Rs -190) | Rs -310,446 | Rs -467,712 | Rs -81,526 | 1 of 4 | 0.970 | 1.000 | 235, Rs -129 (Rs -154), 0.20 | net, twins, folds, q, account, holdout |
| F3 | S3 taken the other way | 1,592 | 37% | Rs 3,133 / -Rs 2,408 | Rs -365 (Rs -384) | Rs -580,337 | Rs -638,869 | Rs -48,931 | 0 of 4 | 0.970 | 1.000 | 270, Rs -539 (Rs -564), 0.59 | net, twins, folds, q, account, holdout |
| F4 | S4 taken the other way | 298 | 39% | Rs 2,295 / -Rs 2,003 | Rs -315 (Rs -335) | Rs -93,983 | Rs -103,597 | Rs -17,777 | 0 of 4 | 0.733 | 1.000 | 30, Rs 444 (Rs 412), 0.41 | n, net, twins, folds, q, account, holdout |
| F5 | S5 taken the other way | 935 | 38% | Rs 2,333 / -Rs 1,682 | Rs -158 (Rs -178) | Rs -147,493 | Rs -178,826 | Rs -47,473 | 0 of 4 | 0.990 | 1.000 | 138, Rs -432 (Rs -460), 0.91 | net, twins, folds, q, account, holdout |
| F6 | S6 taken the other way | 1,586 | 41% | Rs 2,815 / -Rs 1,886 | Rs 58 (Rs 38) | Rs 92,647 | Rs -108,132 | Rs -38,177 | 3 of 4 | 0.030 | 0.119 | 224, Rs -87 (Rs -115), 0.37 | q, account, holdout |
| F7 | S7 taken the other way | 3,404 | 36% | Rs 1,936 / -Rs 1,220 | Rs -71 (Rs -93) | Rs -240,640 | Rs -283,661 | Rs -65,872 | 0 of 4 | 0.030 | 0.119 | 468, Rs -181 (Rs -211), 0.02 | net, folds, q, account, holdout |
| F8 | S8 taken the other way | 2,973 | 38% | Rs 2,979 / -Rs 1,795 | Rs 12 (Rs -9) | Rs 34,954 | Rs -226,238 | Rs -48,058 | 2 of 4 | 0.713 | 1.000 | 391, Rs -152 (Rs -182), 0.36 | net, twins, folds, q, account, holdout |
| F9 | S9 taken the other way | 3,755 | 32% | Rs 2,004 / -Rs 1,207 | Rs -172 (Rs -195) | Rs -646,439 | Rs -673,162 | Rs -69,815 | 1 of 4 | 1.000 | 1.000 | 493, Rs -170 (Rs -201), 0.59 | net, twins, folds, q, account, holdout |
| F10 | S10 taken the other way | 718 | 38% | Rs 4,110 / -Rs 2,994 | Rs -293 (Rs -313) | Rs -210,373 | Rs -300,570 | Rs -58,052 | 1 of 4 | 0.950 | 1.000 | 86, Rs -347 (Rs -370), 0.19 | net, twins, folds, q, account, holdout |
| F11 | S11 taken the other way | 2,736 | 36% | Rs 2,420 / -Rs 1,644 | Rs -162 (Rs -182) | Rs -442,113 | Rs -489,126 | Rs -90,542 | 1 of 4 | 1.000 | 1.000 | 373, Rs -81 (Rs -110), 0.25 | net, twins, folds, q, account, holdout |
| SR3 | S3 with stop-and-reverse (once per trade) | 1,868 | 43% | Rs 3,592 / -Rs 2,431 | Rs 174 (Rs 155) | Rs 324,795 | Rs -183,553 | Rs -66,994 | 2 of 4 | 0.010 | 0.119 | 325, Rs 75 (Rs 49), 0.49 | folds, q, account |

## Where R139 stands after both amendments

- **0 of 12 rules, 0 of 19 enhancements and 0 of 12 reversal versions pass.** Nothing goes on paper or live.
- **The honest best:** S3, the CHoCH reversal, with the plain 2R exit:
  - +Rs 185 a trade on 1,663 in design;
  - +Rs 61 on 291 in the holdout;
  - drawdowns far past Boss's account stop.
- **One thing worth keeping in mind:** cutting the trades with wide stops (D2) is the only change that kept S3 inside
  the account stop in design. It had far too few trades to judge, and lost in the holdout.
- **Nothing here earns a forward test.** If Boss wants one anyway, S3 with D2 is the version to log. It would be forward
  only; CLAUDE.md allows no more failed-gate exceptions.
