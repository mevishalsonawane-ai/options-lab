# What is the best option-buying solution in India? Final synthesis

Written 7 Oct 2026 (repo HEAD 1b2974d). It combines every option-buying study in this repo:
- the catalog: 55 strategies plus the SEBI evidence (`OPTION_BUYING_CATALOG.md`);
- the 26-million-rule search with a locked holdout (`OBUY_SEARCH.md`);
- the lab validation (`OBUY_VALIDATION.md`);
- the three catalog runs (`OBUY_GA.md`, `OBUY_GB.md`, `OBUY_GC.md`) and the multi-day LV-05 test (`OBUY_LV05_MULTIDAY.md`);
- the earlier swing and exit studies (`SWING_DEEP.md`, `SWING_EXITS.md`, `LIQUIDITY_EXITS_3060.md`, `JARVIS_EXITS.md`).

New computation: `research/obuy/final.py`. All figures are after the app's charges and fills, at 1 lot, on real Dhan
option minute prices, Aug 2020 to Oct 2026.

## Verdict for Boss (plain language)

**Short answer: we found no option-buying method that reliably beats costs.**

- **What does not work.** We tested 3,561 versions of 53 popular strategies, 1,224 more versions of our own ideas, and
  26 million computer-generated rules. No rule's profit stands out from luck once you count how many rules were tried.
  The computer search's best rules also failed on the last 12 months, which were locked away until the end.
- **Most strategies lose steadily.** Each year we picked the strategy version with the best record so far. On that
  basis, 49 of the 53 catalog strategies lost money over 2022-2026. Only 60 of 259 strategy-years were profitable,
  which is 23%.
- **Our blind spot is the cost.** Random entries lose about Rs 124 a trade at 1 lot. About Rs 70 of that is charges
  and the rest is spread and decay. A strategy must beat that every trade just to break even, and almost none do.
- **The famous setups are the worst.** These include ORB, the first 5-minute candle, Supertrend, EMA crosses, ADX,
  "confluence" indicators, hero-zero on expiry day and long straddles. They lose more than buying at random.
  - Hero-zero lost Rs 15 lakh at Rs 5,000 tickets.
  - The 09:20 straddle lost Rs 2.9 lakh a year.
- **Least bad: the app's own Liquidity 15+5.** It is the only strategy positive in every test year (2023-2026). It
  made about Rs 69,000 a year at 1 lot.
  - **Warning:** its exits were chosen on this same data (Feb 2024 to Feb 2026), so that number is flattered.
  - In the periods it was NOT tuned on, it made Rs 43,900 in about 3 years. That is about Rs 15,000 a year, or 3% on
    Rs 5 lakh.
  - It does not survive the correction for everything we tried (SPA p = 0.78).
  - It is the best we have, not a proven edge.
- **Patterns that were real but faded.**
  - *BTST strong close:* buy a call when the day closes strong, and sell at the next morning's open. It made
    Rs 2.56 lakh in 2020-22 and only Rs 23,000 in 2024-26. Picked from past years, it lost Rs 1.1 lakh in 2026.
  - *Camarilla R3/S3 fade:* its entries are better than random, but not better than costs. It is about zero since
    2023.
- **What to do next.**
  - Keep Liquidity 15+5 on **paper** for about 300 more trades (roughly a year), with the pass / stop rules in
    section 3.
  - Track BTST strong close and Camarilla fade on paper as **controls only**, with no money.
  - Nothing else is worth paper-trading.
- **The odds, on Rs 5 lakh at 1 lot.**
  - *A typical catalog strategy:* about a 20% chance of a profitable year. Two in three had a drop of Rs 1 lakh or
    more at some point in 2022-26.
  - *Liquidity 15+5, using only its untuned periods:* about a 65% chance of a profitable year, with a median year of
    +Rs 12,600. There is about a 7% chance of a Rs 50,000 drawdown and under 1% of a Rs 1 lakh drawdown.
  - *The worst strategies* (ORB, straddles, hero-zero) lose 30-64% of Rs 5 lakh a year.
- **This matches SEBI's data.** About 90% of individual option buyers lost money in FY26, about Rs 1.3 lakh each on
  average. Traders with 100+ active days were 42% of traders but 87% of the losses.
- **Option selling was not studied here.** It is not a recommendation. SEBI found about 44% of sellers lose too, and
  those who lose lose about Rs 51.7 lakh on average. Selling has its own tail risk.

### Practical rules the evidence supports

1. **Trade less.** Every round trip costs about Rs 70 in charges plus spread, and STT rose to 0.15% in Apr 2026.
   Blind entries lose about Rs 124 a trade. More trades means more cost, not more edge.
2. **Never buy hero-zero tickets.** The catalog version lost in all 96 variants and all 5 years. The app's Hero rule
   is up only because of one day (12 Sep 2024, +Rs 2 lakh), and random tickets on the same days did better.
3. **Do not buy straddles or strangles to "play volatility".** Every version lost, before events too. Options are
   priced above the moves that follow.
4. **Do not buy breakouts of the opening range.** ORB, the first 5-minute candle, Box 9 and late ORB all do worse
   than random entries with the same exits.
5. **For holds of several days, buy the monthly, not the weekly.**
   - The monthly lost less in 7 of 8 comparisons (SWING_DEEP) and in 44 of 48 pairs (LV-05).
   - Sell before expiry day.
   - Never hold an OTM weekly into expiry.
6. **Holding overnight is where index moves pay; the trading day takes it back.** Swing gains came almost all from
   the gaps between close and open (SWING_DEEP, LV-05).
7. **Exits control damage; they do not create an edge.** Stops, targets and the profit-lock ladder cut drawdowns. On
   random entries they do as well as on real ones (SWING_EXITS, JARVIS_EXITS).
8. **Position size.**
   - Use 1 lot per Rs 5 lakh at most.
   - Risk no more than 1% of capital (Rs 5,000) per trade.
   - Without a stop, the whole premium is at risk: size for that.
   - Set a loss limit for the year (say Rs 50,000, 10%) and stop when you hit it.
9. **Do not trust a backtest of the "best" rule.** In-sample "best" rules showed a 93-99% chance of a profitable year.
   On the locked holdout they lost (OBUY_SEARCH). Only walk-forward and paper results count.

## 1. One multiple-testing pass across all catalog variants

The union is GA 1,329 + GB 1,072 + GC 1,060 + LV-05 multi-day 100 = **3,561 variants**. The calendar is 1,529
trading days (3 Aug 2020 to 6 Oct 2026).

Per-variant daily P&L:
- **GA and GC:** rebuilt from every trade in `trades.csv.gz`.
- **GB:** read from the daily matrices saved in each chunk (`gb_all_c*/chunk.pkl`), loaded with an allow-list
  unpickler.
- **Checks:** the daily series reproduce every variant's net in `variants.csv` to within Rs 0.00001. The walk-forward
  years recomputed from the picks match GA's `ga_post.json` and the GC report exactly.
- **LV-05 multi-day:** no daily matrix is on disk. Its t-statistics come from the reported Sharpe
  (t = Sharpe x sqrt(1,529 / 248)), and it enters the BH / Holm passes but not the SPA.

| test (benchmark) | variants | result |
|---|---|---|
| **Hansen SPA / White RC**, stationary bootstrap (mean block 5 days, B = 1,000) of daily P&L against **not trading** | 3,461 (GA+GB+GC) | **SPA p = 0.93, RC p = 1.00.** The best studentised t is 2.38: the POS-01 BTST strong close, ATM, -30%, out at 09:30. **No variant beats not trading.** |
| same, adding the validation grids (1,224) and the 7 fixed strategies (Liquidity 15+5, Solo, Hero, ORB15, straddle, big bar, random) | 4,692 | SPA p = 0.78, RC p = 0.98. Here the best t, 2.71, is **Liquidity 15+5**. Alone it has SPA p = 0.003, but among everything tried it is not significant. |
| one-sided t-test of mean daily P&L > 0, **BH / Holm / Bonferroni** | 3,561 | 347 variants (9.7%) have a positive mean, 8 have t > 2 and none t > 3. Bonferroni at 5% needs t >= 4.20. **Smallest BH q = 1.00, Holm = 1.00: 0 survivors.** With Liquidity and the grids added (4,792): Liquidity t = 2.62, raw p = 0.004, BH q = 1.00. |
| **beats random entries with the same exits** (reported per-variant p), BH / Holm / Bonferroni | 3,561 | 185 at raw p < 0.05; **56 at BH q = 0.032 and 80 at q <= 0.10**; Holm and Bonferroni: none (smallest 1.00). |

**What the 80 BH survivors of the random-entry test mean.**

They are 35 LV-05 first-day, 17 BTST strong close, 9 Camarilla fade, 6 gap-and-go, 4 VOL-02 "faster leg", 3 max pain,
3 gap fill, and 1 each of POS-02, OI-01 and VOL-07. Read them carefully:
- Their entries pick better moments than random entries with the same exits, over the full sample, for the best of
  several variants.
- That is a narrower claim than "profitable after costs". The two tests that ask whether a variant makes money (SPA,
  and the t-test against zero) find nothing.
- Every one of these families was then traded out of sample by the walk-forward:

| family | walk-forward net |
|---|---|
| LV-05 first day | +85.8k (but -85.3k as the multi-day trade it is meant to be) |
| BTST | -42.4k |
| Camarilla fade | -16.3k |
| gap-and-go | -29.7k |
| VOL-02 | -40.2k |
| max pain | +7.0k on 27 trades |
| gap fill | +8.4k |

The LV-05 p-values also use an any-minute random baseline that flatters a fixed 09:16 entry. With a same-minute
baseline its family p is 0.017 (OBUY_GA).

**Limitations of this pass.**
- **The random-entry p-values have a floor of 1/2,001 = 0.0005.** Holm and Bonferroni therefore cannot reject anything
  among 3,561 tests (0.0005 x 3,561 = 1.78), however strong the signal. Only BH is informative there. The t-test and
  the SPA have no such floor.
- **GB used 3 random alternatives per signal; GA and GC used 10.** Variants that lost money in the full sample were not
  tested against random and carry p = 1.
- **The t-test treats days as independent.** The fat-tailed daily P&L is handled by the SPA's bootstrap instead.
- **LV-05 multi-day books each trade's P&L on its entry day** in the catalog runs. Its own run (daily mark-to-market)
  gives SPA p = 0.77.

## 2. Every strategy family's walk-forward result, ranked

**How to read the table.**
- **Walk-forward:** each test year (2022-2026; 2026 runs to 6 Oct) trades the variant with the best net on all
  earlier years. Fixed rules (marked "fixed" or "App") just run their one rule.
- **Units:** Rs at 1 lot per index traded, after charges. "% of Rs 5L / yr" puts the yearly figure against Rs 5 lakh
  of capital. Strategies trading 2-4 indices hold up to one lot of each.
- **Beats random p:** the walk-forward trades against random entries with the same exits. BH and Holm are across all
  65 rows (LV-05's first-day row uses the time-matched p = 0.017).
- **max DD:** the walk-forward's worst peak-to-trough loss over the whole 2022-26 period.
- **P(profit yr) and P(DD >= 20%):** from each run's Monte Carlo of its walk-forward trades over 1-year paths on
  Rs 5 lakh at 1 lot. The yearly MC drawdown is naturally smaller than the 5-year max DD.
- **Flags on the app rows:**
  - Liquidity 15+5's exits were chosen on this data. Its untuned periods made about Rs 15k a year (section 3).
  - Solo's C0 exit was picked after the fact as the best of 12.
  - The Hero rule's profit is one day.
  - The MC for the Hero rows is re-sized to 1 lot, so their drawdown column understates the risk.
- **Liquidity 15+5 is the only row whose random-entry p survives the family-level correction** (Holm 0.03 across 65
  rows). Three things qualify that:
  - The test asks whether it beats random entries, not whether it beats not trading. Against not trading, among
    everything tried, it fails (SPA p = 0.78, section 1).
  - Its exits were tuned on this data.
  - Its row is one fixed rule, not a walk-forward choice among variants.

| # | strategy family | variants | WF Rs total | Rs / year | % of Rs 5L / yr | years + | WF by year (2022 .. 2026) | beats random p raw / BH / Holm | max DD | P(profit yr) | P(DD >= 20%) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | App: Liquidity 15+5 (exits tuned on this data) | 1 | +257,203 | +68,661 | +13.7% | 4/4 | - / +19k / +44k / +106k / +88k | 0.000 / 0.03 / 0.03 | -29,408 | 98% | 0% |
| 2 | App: Hero rule, NIFTY expiry (fixed rule) | 1 | +207,224 | +44,000 | +8.8% | 1/5 | -46k / -28k / +329k / -39k / -9k | 0.485 / 1.00 / 1.00 | -83,510 | 39% | 0% |
| 3 | LV-05 Donchian (1st day only) | 96 | +85,754 | +18,084 | +3.6% | 3/5 | +96k / +34k / -14k / +20k / -49k | 0.017 / 0.16 / 1.00 | -93,271 | 72% | 0% |
| 4 | App: Solo midday (C0 exit picked after the fact) | 1 | +21,825 | +4,606 | +0.9% | 3/5 | +30k / -7k / -4k / +2k / +1k | 0.007 / 0.15 / 0.44 | -43,618 | 61% | 0% |
| 5 | VOL-04 VIX + ORB | 48 | +6,795 | +1,814 | +0.4% | 3/4 | - / +3k / +8k / -13k / +9k | 0.687 / 1.00 / 1.00 | -40,189 | 53% | 0% |
| 6 | OR-08 gap fill | 72 | +8,426 | +1,777 | +0.4% | 3/5 | +10k / -9k / +7k / -22k / +22k | 0.251 / 0.82 / 1.00 | -58,203 | 53% | 0% |
| 7 | EXP-04 max pain | 72 | +6,969 | +1,473 | +0.3% | 3/5 | +3k / +2k / +4k / -0k / -3k | 0.163 / 0.66 / 1.00 | -4,593 | 67% | 0% |
| 8 | POS-02 Supertrend positional | 48 | -404 | -149 | -0.0% | 2/3 | - / - / -8k / +3k / +4k | 0.436 / 1.00 / 1.00 | -18,269 | 35% | 0% |
| 9 | MR-04 Camarilla R3/S3 fade | 72 | -16,289 | -3,438 | -0.7% | 2/5 | +36k / -15k / -15k / +14k / -37k | 0.013 / 0.15 / 0.82 | -79,472 | 46% | 0% |
| 10 | EXP-05 +10% scalp, 1/day | 12 | -23,365 | -4,940 | -1.0% | 0/5 | -3k / -3k / -6k / -3k / -8k | 0.236 / 0.81 / 1.00 | -29,195 | 10% | 0% |
| 11 | EXP-02 gamma blast | 96 | -25,544 | -5,391 | -1.1% | 1/5 | +8k / -12k / -7k / -10k / -3k | 0.699 / 1.00 / 1.00 | -38,644 | 20% | 0% |
| 12 | LV-04 NR7/inside day (1st day) | 72 | -28,141 | -5,955 | -1.2% | 1/5 | -14k / +15k / -23k / -4k / -2k | 0.586 / 1.00 / 1.00 | -58,677 | 38% | 0% |
| 13 | OR-07 gap and go | 96 | -29,686 | -6,260 | -1.3% | 2/5 | -18k / +12k / -36k / +42k / -29k | 0.012 / 0.15 / 0.77 | -96,585 | 43% | 2% |
| 14 | VOL-02 straddle-premium breakout | 96 | -40,242 | -8,486 | -1.7% | 2/5 | +24k / -1k / -14k / -105k / +56k | 0.928 / 1.00 / 1.00 | -162,757 | 41% | 2% |
| 15 | POS-01 BTST (strong close) | 48 | -42,394 | -8,971 | -1.8% | 3/5 | +47k / +32k / -11k / +1k / -112k | 0.066 / 0.36 / 1.00 | -134,496 | 41% | 4% |
| 16 | TI-06 RSI 50 | 90 | -45,088 | -9,508 | -1.9% | 2/5 | -20k / +6k / -31k / -11k / +11k | 0.410 / 1.00 / 1.00 | -67,617 | 33% | 0% |
| 17 | OI-01 PCR contrarian | 54 | -38,821 | -10,465 | -2.1% | 1/4 | - / +79k / -20k / -7k / -91k | 0.081 / 0.38 / 1.00 | -129,788 | 39% | 2% |
| 18 | MR-01 5-EMA alert candle | 72 | -53,650 | -11,314 | -2.3% | 2/5 | +6k / -51k / +28k / -27k / -9k | 0.005 / 0.15 / 0.35 | -155,531 | 43% | 24% |
| 19 | LV-02 narrow CPR | 72 | -62,250 | -13,127 | -2.6% | 1/5 | -42k / -9k / -12k / -32k / +32k | 0.177 / 0.68 / 1.00 | -97,107 | 33% | 0% |
| 20 | VOL-06 event-day straddle | 36 | -57,396 | -15,339 | -3.1% | 0/4 | - / -10k / -5k / -28k / -14k | 0.986 / 1.00 / 1.00 | -57,396 | 6% | 0% |
| 21 | TI-04 EMA9 x VWAP | 36 | -73,557 | -15,512 | -3.1% | 1/5 | -28k / -17k / +6k / -9k / -26k | 0.058 / 0.34 / 1.00 | -85,696 | 26% | 0% |
| 22 | Hero grid (54 variants) | 54 | -78,668 | -16,703 | -3.3% | 1/5 | -41k / -128k / +151k / -51k / -10k | 0.767 / 1.00 / 1.00 | -253,714 | 43% | 0% |
| 23 | LV-06 OI wall break | 96 | -79,775 | -16,823 | -3.4% | 1/5 | -11k / -21k / +6k / -39k / -15k | 0.931 / 1.00 / 1.00 | -96,797 | 21% | 0% |
| 24 | MR-03 RSI 30/70 reversal | 48 | -82,004 | -17,293 | -3.5% | 2/5 | -26k / -24k / +47k / -112k / +33k | 0.373 / 1.00 / 1.00 | -153,827 | 37% | 15% |
| 25 | TD-01 2:50 pm candle | 60 | -82,108 | -17,315 | -3.5% | 0/5 | -9k / -14k / -30k / -7k / -22k | 0.997 / 1.00 / 1.00 | -84,288 | 6% | 0% |
| 26 | OR-05 open = low/high | 54 | -83,396 | -17,602 | -3.5% | 2/5 | -19k / -74k / +8k / +17k / -16k | 0.583 / 1.00 / 1.00 | -113,204 | 21% | 0% |
| 27 | LV-05 Donchian, multi-day hold | 100 | -85,323 | -17,993 | -3.6% | 2/5 | +5k / +31k / -35k / -33k / -53k | 0.683 / 1.00 / 1.00 | -185,636 | 34% | 8% |
| 28 | VOL-07 post-event breakout | 72 | -88,461 | -18,671 | -3.7% | 0/5 | -10k / -11k / -8k / -29k / -31k | 0.713 / 1.00 / 1.00 | -99,775 | 22% | 0% |
| 29 | TI-02 Supertrend + EMA | 64 | -89,468 | -18,867 | -3.8% | 0/5 | -18k / -13k / -9k / -26k / -23k | 0.039 / 0.28 / 1.00 | -123,960 | 29% | 2% |
| 30 | MR-02 Bollinger alert | 96 | -90,790 | -19,146 | -3.8% | 3/5 | +63k / +9k / -21k / -209k / +68k | 0.154 / 0.66 / 1.00 | -293,226 | 36% | 16% |
| 31 | MR-05 VWAP stretch fade | 96 | -91,856 | -19,371 | -3.9% | 1/5 | +0k / -4k / -21k / -54k / -13k | 0.984 / 1.00 / 1.00 | -99,127 | 5% | 0% |
| 32 | VOL-05 pre-event run-up | 36 | -102,552 | -21,887 | -4.4% | 0/5 | -13k / -28k / -30k / -9k / -23k | 0.055 / 0.34 / 1.00 | -104,464 | 14% | 0% |
| 33 | TD-02 first half-hour -> last | 96 | -113,150 | -23,862 | -4.8% | 1/5 | -68k / -11k / -39k / -3k / +8k | 0.579 / 1.00 / 1.00 | -141,203 | 12% | 0% |
| 34 | EXP-05 +10% scalp, 5/day | 12 | -114,993 | -24,312 | -4.9% | 0/5 | -28k / -25k / -35k / -7k / -20k | 0.400 / 1.00 / 1.00 | -116,760 | 0% | 0% |
| 35 | MR-06 opening-range fade | 48 | -96,578 | -25,782 | -5.2% | 1/4 | - / -15k / -52k / +4k / -34k | 0.071 / 0.36 / 1.00 | -141,950 | 30% | 16% |
| 36 | TD-03 last-30-min OTM jackpot | 36 | -124,865 | -26,332 | -5.3% | 1/5 | -53k / -31k / -62k / -4k / +24k | 0.033 / 0.27 / 1.00 | -173,406 | 12% | 0% |
| 37 | LV-01 PDH/PDL break-retest | 64 | -129,689 | -27,349 | -5.5% | 1/5 | -62k / -1k / +10k / -19k / -57k | 0.492 / 1.00 / 1.00 | -142,470 | 15% | 1% |
| 38 | POS-04 new weekly after expiry | 48 | -136,843 | -28,957 | -5.8% | 1/5 | -62k / +45k / -23k / -74k / -23k | 0.802 / 1.00 / 1.00 | -177,208 | 24% | 6% |
| 39 | EXP-03 expiry-day ORB | 18 | -141,218 | -29,806 | -6.0% | 1/5 | +9k / -29k / -20k / -42k / -59k | 0.633 / 1.00 / 1.00 | -165,467 | 17% | 1% |
| 40 | LV-03 Camarilla H4/L4 breakout | 72 | -144,763 | -30,528 | -6.1% | 1/5 | -9k / +46k / -53k / -102k / -27k | 0.384 / 1.00 / 1.00 | -262,827 | 34% | 45% |
| 41 | TI-07 RSI 60/40 | 64 | -154,916 | -32,669 | -6.5% | 1/5 | -59k / -27k / -30k / -52k / +13k | 0.852 / 1.00 / 1.00 | -208,314 | 14% | 2% |
| 42 | Solo grid (414) | 414 | -158,096 | -33,340 | -6.7% | 0/5 | -54k / -34k / -2k / -52k / -15k | 0.974 / 1.00 / 1.00 | -215,270 | 14% | 3% |
| 43 | VOL-01 long straddle | 64 | -164,167 | -34,620 | -6.9% | 0/5 | -14k / -23k / -14k / -88k / -25k | 0.515 / 1.00 / 1.00 | -188,076 | 9% | 0% |
| 44 | TI-08 Heikin-Ashi | 48 | -170,762 | -36,011 | -7.2% | 2/5 | -15k / +39k / +20k / -124k / -92k | 0.994 / 1.00 / 1.00 | -238,329 | 17% | 8% |
| 45 | VOL-03 low-IVP straddle | 96 | -204,087 | -43,670 | -8.7% | 0/5 | -29k / -23k / -3k / -137k / -12k | 0.995 / 1.00 / 1.00 | -205,183 | 3% | 1% |
| 46 | TI-01 Supertrend flip | 90 | -208,881 | -44,050 | -8.8% | 0/5 | -13k / -43k / -113k / -9k / -31k | 0.224 / 0.81 / 1.00 | -306,620 | 20% | 26% |
| 47 | TI-05 VWAP pullback | 48 | -209,044 | -44,084 | -8.8% | 0/5 | -58k / -53k / -17k / -39k / -42k | 0.500 / 1.00 / 1.00 | -211,190 | 3% | 1% |
| 48 | Straddle-time grid (180) | 180 | -217,129 | -45,750 | -9.2% | 0/5 | -1k / -81k / -28k / -18k / -90k | 0.013 / 0.15 / 0.82 | -228,675 | 3% | 3% |
| 49 | OI-02 intraday OI change | 72 | -249,478 | -52,611 | -10.5% | 0/5 | -48k / -41k / -11k / -122k / -27k | 0.914 / 1.00 / 1.00 | -281,997 | 13% | 29% |
| 50 | OR-03 late/wide ORB | 80 | -260,661 | -54,969 | -11.0% | 1/5 | -29k / +8k / -47k / -123k / -70k | 0.931 / 1.00 / 1.00 | -325,797 | 14% | 31% |
| 51 | OR-06 09:20 premium momentum | 72 | -282,312 | -59,485 | -11.9% | 1/5 | +0k / -119k / -3k / -57k / -103k | 0.883 / 1.00 / 1.00 | -323,318 | 9% | 30% |
| 52 | TI-10 ST+EMA+VWAP+RSI confluence | 56 | -290,461 | -61,254 | -12.3% | 1/5 | -126k / -1k / +8k / -167k / -4k | 0.833 / 1.00 / 1.00 | -322,346 | 18% | 51% |
| 53 | OR-02 first 5-min candle | 96 | -325,718 | -68,689 | -13.7% | 0/5 | -15k / -40k / -95k / -72k / -104k | 0.635 / 1.00 / 1.00 | -392,485 | 15% | 53% |
| 54 | Control: random entries | 1 | -329,019 | -69,385 | -13.9% | 0/5 | -53k / -71k / -64k / -112k / -29k | 0.767 / 1.00 / 1.00 | -343,947 | 1% | 20% |
| 55 | OI-04 max-OI magnet | 36 | -338,575 | -71,644 | -14.3% | 1/5 | -85k / -29k / +1k / -89k / -136k | 0.940 / 1.00 / 1.00 | -343,038 | 9% | 44% |
| 56 | TI-09 ADX/DMI | 48 | -345,311 | -72,821 | -14.6% | 0/5 | -156k / -29k / -27k / -30k / -104k | 0.990 / 1.00 / 1.00 | -372,895 | 5% | 40% |
| 57 | Big-bar pullback (fixed) | 1 | -364,003 | -76,762 | -15.4% | 1/5 | -115k / -118k / +3k / -122k / -11k | 0.447 / 1.00 / 1.00 | -369,510 | 3% | 40% |
| 58 | OR-04 Bank Nifty Box 9 | 96 | -366,230 | -77,232 | -15.4% | 1/5 | -90k / -60k / +49k / -246k / -19k | 0.983 / 1.00 / 1.00 | -383,189 | 10% | 56% |
| 59 | TI-03 EMA cross | 96 | -369,856 | -77,997 | -15.6% | 1/5 | -227k / -52k / -74k / +17k / -34k | 0.458 / 1.00 / 1.00 | -429,309 | 14% | 64% |
| 60 | Big-bar grid (192) | 192 | -434,256 | -91,578 | -18.3% | 0/5 | -142k / -78k / -84k / -125k / -5k | 0.983 / 1.00 / 1.00 | -446,521 | 1% | 54% |
| 61 | ORB grid (384) | 384 | -487,638 | -102,835 | -20.6% | 0/5 | -67k / -153k / -81k / -96k / -90k | 0.996 / 1.00 / 1.00 | -508,022 | 2% | 69% |
| 62 | OR-01 15-min ORB | 99 | -551,113 | -116,221 | -23.2% | 1/5 | -18k / -197k / -13k / -329k / +6k | 0.610 / 1.00 / 1.00 | -625,814 | 6% | 79% |
| 63 | ORB 15 (fixed) | 1 | -717,286 | -151,264 | -30.3% | 0/5 | -70k / -122k / -56k / -292k / -177k | 0.997 / 1.00 / 1.00 | -754,666 | 1% | 90% |
| 64 | 09:20 straddle (fixed) | 1 | -1,359,030 | -286,355 | -57.3% | 0/5 | -156k / -147k / -165k / -655k / -236k | 1.000 / 1.00 / 1.00 | -1,382,240 | 0% | 100% |
| 65 | EXP-01 hero-zero (Rs 5k tickets) | 96 | -1,509,700 | -318,643 | -63.7% | 0/5 | -315k / -367k / -226k / -517k / -85k | 0.383 / 1.00 / 1.00 | -1,531,050 | 3% | 0% |

**Top 5 by walk-forward Rs a year, and what each really is:**

| # | family | Rs / year | verdict |
|---|---|---|---|
| 1 | Liquidity 15+5 (app) | +68.7k | Best we have. Its exits were tuned on this data; untuned periods made about +15k a year. Paper only. |
| 2 | Hero rule (app) | +44.0k | One jackpot day (12 Sep 2024). 1 of 5 years positive. Random tickets do as well (p = 0.49). Not an edge. |
| 3 | LV-05 Donchian, first day | +18.1k | Fails a fair baseline (Holm about 1). Profit was 2020-23 bull-market longs. The multi-day version lost -18.0k a year. Closed. |
| 4 | Solo midday (app) | +4.6k | Exit picked after the fact. Slightly negative since 2023 (-8k in total). Not significant after correction (Holm 0.44). |
| 5 | VOL-04 VIX + ORB | +1.8k | No better than random (p = 0.69). Noise. |

Among the 53 catalog families alone:
- **Positive walk-forward:** 4 of 53 (LV-05 first day, gap fill, max pain, VOL-04).
- **Totals:** together the 53 lost **Rs 83 lakh** over 2022-26 at 1 lot each. The median family lost Rs 19,400 a year.
- **Full sample:** only 9.7% of the 3,561 variants made money even before any walk-forward.

### Reference results from the other studies (different engines, not in the table's corrections)

| study | what was tested | out-of-sample result |
|---|---|---|
| OBUY_SEARCH | 25.9 M intraday rules (1-3 conditions x windows x strikes x 52 exits) | 0 survive BH / SPA / DSR. Top-20 walk-forward lost in 3 of 4 years (2022 -85k, 2023 +3k, 2024 -8k, 2025 -132k). On the locked 12-month holdout the 10 best in-sample rules went 1/10 positive, -Rs 106k combined. |
| OBUY_VALIDATION grids | Hero, Solo, ORB, big bar, straddle grids (1,224) | all walk-forwards negative (rows 22, 42, 48, 60, 61 above) |
| SWING_DEEP | daily signals into bought monthly ATM options, 2-20 day holds | walk-forward NIFTY +Rs 21k over 6 years (3/6 years), BANKNIFTY -Rs 1.49 lakh (1/4). Debit spreads lose a small steady amount. |
| SWING_EXITS | the app's stop / target / ladder on those swing trades | -Rs 0.2k to -33k a year; coin-flip entries with the same exits do as well. Best weekly row: +Rs 2k a year (0.4%). |
| JARVIS_EXITS | 12 exit rules on Jarvis's entries | Choosing the exit by walk-forward lost -Rs 46k. Random entries lose about the charges with any exit. |
| LIQUIDITY_EXITS_3060 | 12 alternative exits on Liquidity 15+5 | all worse than the current exits; picking by walk-forward lost to keeping them |

## 3. The paper-trade candidates in detail

Each daily P&L series is resampled into 1-year paths (stationary bootstrap, mean block 5 days, 10,000 paths) on
Rs 5 lakh at 1 lot. The "median yr" is the median 1-year P&L.

| candidate (variant) | period resampled | P(profit yr) | median yr | 5th / 95th pct | P(DD >= Rs 50k) | P(DD >= Rs 1 lakh) |
|---|---|---|---|---|---|---|
| **Liquidity 15+5** (app, BANKNIFTY + FINNIFTY) | all, Aug 2021 - Oct 2026 (tuned period included) | 90% | +46,673 | -12,331 / +119,014 | 2% | 0% |
| | **untuned periods only** (before 13 Feb 2024, after 23 Feb 2026; 733 days) | **65%** | **+12,560** | -38,794 / +73,886 | **7%** | **0%** |
| **BTST strong close** (POS-01, ITM1, -30% stop, out 09:30; best full-sample variant) | all 2020-26 | 81% | +50,115 | -42,895 / +144,753 | 39% | 3% |
| | 2023 onwards | 61% | +15,498 | -80,131 / +113,519 | 59% | 10% |
| | **2024 onwards** | **55%** | **+8,288** | -101,063 / +115,529 | **72%** | **18%** |
| **Camarilla R3/S3 fade** (MR-04, 5-min, target pivot, ATM; best full-sample variant) | all 2020-26 | 61% | +9,045 | -45,059 / +70,190 | 15% | 0% |
| | **2023 onwards** | **48%** | **-2,199** | -64,468 / +70,629 | **33%** | **1%** |
| Solo midday (app, reference) | all | 64% | +5,160 | -18,071 / +30,717 | 0% | 0% |

**Liquidity 15+5: the forward record.**
- **Before the tuning window** (Aug 2021 - Feb 2024): +Rs 3,156 on 609 trades.
- **After it** (24 Feb - 6 Oct 2026): +Rs 40,706 on 238 trades, about +Rs 171 a trade.
  - By month: late Feb +1.0k, Mar +10.6k, Apr -10.0k, May -7.1k, Jun +2.2k, Jul +23.7k, Aug +2.5k, Sep +21.4k, Oct to date -3.6k.
  - These 7 months are the closest thing to out of sample. They are encouraging but short.
- **One trade is very noisy:** about Rs 1,900 standard deviation at 1 lot, about 330 trades a year. After 300 paper
  trades the average still has an uncertainty of about ±Rs 110 (1 standard error). A year of paper trading can
  catch a clear failure but cannot prove a small edge.
- **Rules fixed now, before any more data comes in:**
  - **Stop paper-trading it** if, after 300 trades, the average is -Rs 75 a trade or worse. That is most of the way
    to the random-entry loss of -Rs 124.
  - **Consider 1 live lot only if all of these hold:** the average is at least +Rs 100 a trade after real charges;
    paper fills are within Rs 20 a trade of the model; and the drawdown stays under Rs 50,000.
  - **Change nothing during the test.** No exit or filter tweaks.

**BTST strong close.**
- **By year** (the best variant): 2020 +66k, 2021 +106k, 2022 +84k, 2023 +39k, 2024 +4k, 2025 +1k, 2026 +18k.
- **Picked by walk-forward:** -42k, including -112k in 2026.
- **Union tests:** it is the top variant in the union SPA (t = 2.38) but nowhere near significant (p = 0.93).
- **Verdict:** the overnight drift it rode in 2020-22 has gone. Since 2024 it is roughly a coin flip with a 72% chance
  of a Rs 50k drawdown. Track it as a control only.

**Camarilla R3/S3 fade.**
- **Entries:** they beat random entries with the same exits (raw p 0.0005 in sample, BH q 0.032 across the union;
  walk-forward p 0.013, Holm 0.82).
- **Money:** since 2023 the best variant made -10.7k, -0.3k, +20.7k and -8.5k by year. That is about zero after costs.
- **Verdict:** it is a real but small timing signal that the cost of a bought option eats. Track it as a control
  only.

## 4. Scope and limits

- **Option buying only.**
  - Option selling and spreads that are net short premium were not tested.
  - SEBI's FY26 study: about 44% of mainly-sellers lost, with an average loss of Rs 51.7 lakh when they did.
  - Selling has margin, gap and tail risk, and none of this is a recommendation to sell.
  - Debit spreads (SWING_DEEP) lost a small, steady amount.
- **Index options only.** NIFTY, BANKNIFTY, FINNIFTY and SENSEX. No stock options and no MIDCPNIFTY.
- **Data limits.**
  - Dhan's rolling data holds ATM±10 strikes and only the nearest weekly.
  - There is no bid/ask, so fills are modelled. Real fills on cheap options are worse, which only strengthens the
    negative verdicts.
  - Costs are the app's at today's rates (STT 0.15%), which is conservative for 2020-24.
- **Lot sizes** are those in force on each date. That is why rupee totals of different years are not on the same
  footing.
- **Liquidity 15+5 and Solo were developed on this data**, so their in-sample and walk-forward numbers are biased
  upward. Only the untuned windows and the paper record can judge them.

## 5. Reproduce

```
python3 -I research/obuy/final.py    # ~4 min; reads <OBUY_CACHE>/runs/{ga_all,gb_all(+_c*),gc_all,lv05_multiday,singles,grids,ga_lv05_check}
                                     # writes <OBUY_CACHE>/runs/final/{final.json,family_table.md}
```
