# R82 pre-registration: enhancing the MCX precious-metal strategies (GOLDM / SILVERM)

Written 18 Oct 2026, **before any R82 result was computed**: no trade list, no net and no count of signals has been
looked at. Research only: nothing under `android/**` or `.github/**` changes. Any app change is Boss's decision.

Boss, 18 Oct: "use all the existing knowledge and, if you need, the internet" to enhance the commodity strategies.
This study covers precious metals on MCX: GOLDM, GOLD, SILVERM and SILVER futures and their options. XAUUSD and XAGUSD
are used only as signal or price proxies.

## 0. What the app runs today (read before writing this)

- **MCX arms** (`android/engine/.../mcx/`; all "Not proven — paper only", 1 lot):
  - `McxTrendRules`, "MCX 12-month trend":
    - Signal: the sign of the 252-trading-day change, read on the last trading day of the month, traded at the next
      open, long AND short.
    - Legs: CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI, GOLDPETAL.
    - Rolled before expiry. **No stop-loss.**
  - `McxUsSilverRules`, "US-night silver": the 09:05 sign of the COMEX silver + USDINR overnight move on SILVERMIC.
    R53 MA2: final 109 trades, +Rs 62,850, q 0.198, not proven.
  - `McxEveRules`: the NG evening breakout (not precious metals).
  - The SILVERM morning call: switched off on 14 Oct (R42).
- **Gold arms** (XAUUSD, `engine/gold/`): Liquidity 1h (GoldLiquidity), Trend 4h Supertrend, Dip, TAS. These are
  XAUUSD, not MCX.
- **Logged failures this study does NOT re-test unchanged** (`research/experiments/entries/`):
  - GOLDM/SILVERM option intraday rules: M3 LIQ15/LIQ5 (the app's Liquidity on MCX options), USORB, INORB, EVE; R3; R6;
    M4; R53 MA1b, MA5-MA8.
  - R2's CPI/NFP "follow the first hour" (32-55% hits).
  - R2's DXY / real-yield 20-day direction as a gold or silver predictor (hits 39-54%).
  - R2's FOMC straddle.
  - Gold 15-minute Liquidity charts (GOLD_TIMEFRAMES, LIQUIDITY_GOLD).
  - R53 MA10, the 12-month trend: final -Rs 66.1k on 28 leg-months.
  - R53 MA2, US-night silver.
  - Every R82 idea that touches one of these says what is materially different (section 4).

## 1. Data (fixed now; nothing committed; downloads in /dev/shm)

The Drive data lake (`gdrive:IraAlgo-data-lake`: Dhan MCX option minutes Aug 2025 - Oct 2026, `fut_1m_live_mcx`,
`fut_daily_continuous`) **is not reachable from this sandbox**:
- there is no rclone;
- the `data-20261014` release is unpublished (catalogue `release_published: False`; API 404).

So **no real MCX price is used**. Every MCX price below is a **proxy**, labelled as such:

- **Daily book** (2003 - 16 Oct 2026):
  - Dukascopy XAUUSD and XAGUSD daily BID candles (UTC day, weekdays with volume), via `fetch_r82.py days`, using R53's
    decoder.
  - USDINR: Yahoo `INR=X` daily close (from Dec 2003).
  - DXY: Yahoo `DX-Y.NYB` daily close.
- **Intraday book** (20 Oct 2024 - 16 Oct 2026):
  - Yahoo 1-hour bars of COMEX gold `GC=F` and silver `SI=F` (front month, 23 h a day), via `fetch_r82.py hourly`.
  - USDINR: Yahoo `INR=X` 1-hour and daily.
  - Yahoo serves only the last 730 days at 1 hour, so the intraday book starts 20 Oct 2024.
- **Rupee proxy for one MCX lot.** For a USD price P an ounce and USDINR X (the latest INR=X close known before the
  bar):
  - GOLDM (100 g lot, quoted per 10 g): price = P × X × D × 10 / 31.1035, lot value = 10 × price.
  - SILVERM (5 kg lot, quoted per kg): price = P × X × D × 1000 / 31.1035, lot value = 5 × price.
  - D = the import-duty landed factor: 1.10 up to 22 Jul 2024, 1.06 from 23 Jul 2024 (Union Budget 2024 cut the
    customs duty on gold and silver to 6%).
  - Older duty changes are not modelled. D only scales the rupee size of a trade; it never changes a signal or the sign
    of a trade. This is stated in the write-up.
- **Not available, so not tested:**
  - MCX option prices (no option history here). The idea "MCX options versus futures for the same signal" is
    therefore **not tested**.
  - MCX contract-by-contract futures history (needed for roll timing). **Roll timing is not tested.**
  - US real yields (FRED `DFII10` unreachable; Yahoo has none). **The real-yield proxy is not tested.**
  - XAGUSD minute data: Dukascopy rate-limits to about 1 day file a minute (7 of about 790 days fetched in 5 minutes).
    **Silver 15-minute ideas are not tested.**

## 2. Splits (fixed now)

| book | design | walk-forward (inside design) | final (read once) |
|---|---|---|---|
| Daily | 1 Jan 2005 - 31 Dec 2022 (2004 is warm-up for the 252-day signal) | seven out-of-sample 2-year blocks 2009-2022 (2005-2008 warm-up) | **1 Jan 2023 - 16 Oct 2026** |
| Intraday | 21 Oct 2024 - 31 Mar 2026 | four out-of-sample quarters Apr 2025 - Mar 2026 (Oct 2024 - Mar 2025 warm-up) | **1 Apr - 16 Oct 2026** |

- No variant has a fitted parameter: every setting is fixed in section 4. So a variant's walk-forward net = the sum of
  its out-of-sample blocks.
- **The final is read ONCE**, all variants in one run, by a runner that writes `results/FINAL_READ` and refuses a second
  read.
- **Honesty note:**
  - The daily final (2023-2026) overlaps M2's holdout (2025 - Oct 2026) and R53's final (Apr - Oct 2026), which ran the
    plain 12-month rule.
  - The intraday final overlaps R53's and R78's. The R82 variants have never been run on these windows.

## 3. Trading, costs and money

- **Instrument:** the GOLDM and SILVERM futures, 1 lot each (the app's fixed 1-lot rule). Money is in Rs per 1 lot.
  - A "book" is gold + silver together, and the tests are on the book.
  - Per-metal lines are reported for information. GOLDPETAL / SILVERMIC (the app's trend legs) scale linearly: 1/100 and
    1/5 of these numbers.
- **Charges:** the app's `McxCosts` futures charges on each leg's turnover:
  - brokerage min(Rs 20, 0.03%);
  - CTT 0.01% on the sell;
  - MCX 0.0021%;
  - SEBI Rs 10 a crore;
  - stamp 0.002% on the buy;
  - GST 18% on brokerage + exchange + SEBI.
- **Slippage:** the app's paper fills (`PaperSpread.MCX_FUTURE`):
  - 0.03% of price against us on every fill;
  - 0.10% on stop fills (the desktop stop slippage, replacing the 0.03%).
- **Carry (daily book only):** a held MCX future costs a long, and pays a short, 6% a year of the lot value, pro rata per
  calendar day held. This is the near-month premium over spot (Indian rate carry); proxy prices are spot.
- **Fills:**
  - **Daily:** a signal at a day's close fills at the next day's open. A stop triggers when the day's low (long) / high
    (short) reaches it, and fills at the stop, or at the open if the day opened through it, with stop slippage. The
    same day touching stop and target: the stop.
  - **Intraday:** a signal at an hourly bar's close fills at the next bar's open. Stops are checked on each later bar's
    low / high, with the same fill rule.
- **MCX hours (intraday):** 09:00 - 23:30 IST while the US is on summer time, 09:00 - 23:55 in US winter time
  (`McxSession.kt`; MCX circulars).
  - Yahoo hours start at :00 UTC (= :30 IST). The MCX window in bars is therefore:
    - entries from the bar opening 04:00 UTC (09:30 IST);
    - the last entry bar opens at 16:00 UTC (summer) / 17:00 UTC (winter);
    - everything is flat at the open of the bar starting 17:00 UTC (22:30 IST, summer) / 18:00 UTC (23:30 IST, winter),
      i.e. at least 25 minutes before MCX's close.
  - No intraday trade is held overnight.
  - Days: Mon-Fri. MCX's own holidays are not modelled (a proxy trade on an MCX holiday is kept; this is stated).

## 4. The ideas (16 tests; settings fixed now)

Indicators:
- ATR(n) = Wilder ATR on that book's bars.
- Supertrend(10, 3) = ATR 10 (Wilder), multiplier 3, on the same bars (R73's definition).
- Donchian(n) = the highest high / lowest low of the n bars BEFORE the signal bar.
- **Every rule has a stop-loss.**

### Daily book (signal: the metal's own rupee proxy price, i.e. the MCX future proxy; trade the future)

**T0 (baseline, not a test): the app's rule as it is.**
- On the last trading day of each month, the sign of the 252-day change in the rupee proxy close; +1 long, -1 short.
- Enter or flip at the next open; hold for the month.
- Gold and silver legs.
- No stop (the app has none). Carry and costs as section 3. Monthly rolls are not charged separately.

**T1: T0 + a 3 × ATR(20) trailing stop.**
- Long: the stop = the highest close since entry - 3 × ATR(20), never lowered. Short: the mirror.
- After a stop, the leg is flat until a later month-end signal opens it again.
- A flip at a month-end closes the old side and opens the new one.

**T2: T1, long only.** The short signals mean flat. A short future is risk without a floor; Boss buys.

**T3: T1 + gold-silver agreement ("breadth", after R72).** A leg opens only at a month-end where the gold and silver
252-day signs are equal. An open leg exits at its stop, or at a month-end where its own sign flips.

**T4: T1 + a high-volatility skip ("sizing" at 1 lot).** A new entry is skipped when the metal's 20-day realised
volatility is above the 80th percentile of its own previous 252 days.

**D0: Donchian 55 / 20, long and short.**
- Enter long on a close above Donchian(55)'s high; short on a close below its low.
- Initial stop 2 × ATR(20) from the entry.
- Exit on a close beyond the opposite Donchian(20).

**D1: D0 + a Supertrend agreement filter (R73's T7, on daily bars).** A long only while the metal's Supertrend(10, 3)
is up; a short only while it is down.

**D2: D1 + cross-metal agreement ("breadth", R72).** The other metal's Supertrend must also point the trade's way at
the signal close.

**G1: the gold/silver ratio regime (long only).**
- At each month-end, R = gold proxy / silver proxy (both per oz, USD; the ratio is the same in rupees).
- If R's 63-day change < 0 (silver outperforming), the month's candidate is silver, else gold.
- Hold it long only if its own 252-day sign is +1; else flat.
- A 3 × ATR(20) trailing stop as T1. At most one metal held.

**U1: DXY context (long only).** T2, but a new long opens only if DXY's 63-day change is ≤ 0 (the dollar not rising).
This differs materially from R2's failure: R2 used the 20-day DXY trend to predict the next 20 days; U1 is a filter on
a 12-month trend book with a stop.

Note on USDINR: the rupee proxy price already contains USDINR, so a "USD-only" signal would differ. USDINR as a
separate context is not added as its own test, to keep the count down. **USDINR context is not tested.**

### Intraday book (signal: COMEX GC=F / SI=F hourly, proxy for the MCX GOLDM / SILVERM future; trade the future)

**L1: Liquidity 1h on MCX hours, buys only, gold and silver.**
- The GoldLiquidity rule (`indicator/liquidity.py`): swing zones with lookback 20 (full range), pools with 2 contacts,
  5 bars apart, confirmation 15.
- Levels are built on the continuous 23-hour series.
- **Entry:** a completed bar's close takes a POOL above that overlaps an active SWING zone above (the arm's
  confluence). Buy at the next bar's open, inside the MCX entry window.
- **Exits (first of):**
  - the stop: the entry - 1 × ATR(14, 1h), on bar lows;
  - the target: the second liquidity level above, else the first (touch, filled at the level);
  - a close back below the broken level (failed break);
  - a new level forming above;
  - the MCX flat time.
- One position per metal.
- Materially different from M3 LIQ15/5 (GOLDM options, 15m+5m, both sides, index exits): 1-hour pools, futures, buys
  only, the gold arm's second-level target, an ATR stop.

**L2: L1 + gold-silver agreement.** The other metal's last completed 1h close must be above its own EMA(20) on 1h.

**L3: L1 long AND short.** The mirror for a pool below taken by a close; the short's stop is the entry + 1 ATR.

**E1: L1 skipping US data days.** No entry on the IST date of a US CPI, NFP or FOMC release (the official BLS / Fed
calendars, below).

**O1: overnight COMEX move into the MCX day, gold+silver agreement, both metals.**
- At the open of the 04:00 UTC bar (09:30 IST), compute for each metal s = ln(price now / the close of the last bar
  before the previous MCX close).
  - The previous MCX close is the previous weekday's flat time: the 17:00 UTC (summer) / 18:00 UTC (winter) bar
    open.
  - Price now = the 04:00 bar's open.
- Trade both metals in the common direction only when the two signs agree and both |s| ≥ 0.20%.
- Stop 1 × ATR(14, 1h) from the entry. Exit at the flat time.
- Materially different from R53 MA2 / R2 C2 (silver only, 09:05, no stop, USDINR included): both metals, an agreement
  filter, a size threshold and a stop.

**U2: US open hour, gold+silver agreement.**
- The first US-cash hour is the bar starting 13:00 UTC (summer) / 14:00 UTC (winter), covering 09:00-10:00 ET.
- If gold and silver both close that bar beyond its open the same way (both up / both down), trade both metals that
  way at the next bar's open.
- Stop: the US-hour bar's opposite extreme. Exit at the flat time.
- Materially different from M3's USORB (a 15-minute range break on single options, no agreement).

**U3: U2 on US data days only** (CPI / NFP / FOMC, IST date).
- The FOMC statement (14:00 ET) comes after MCX's flat time, so FOMC days use the same 13:00 / 14:00 UTC bar as CPI
  and NFP days.
- Materially different from R2's CPI/NFP "first hour" (one metal, no agreement, no stop).

**US data calendar:**
- FOMC (the statement day):
  - 2024: 31 Jan, 20 Mar, 1 May, 12 Jun, 31 Jul, 18 Sep, 7 Nov, 18 Dec.
  - 2025: 29 Jan, 19 Mar, 7 May, 18 Jun, 30 Jul, 17 Sep, 29 Oct, 10 Dec.
  - 2026: 28 Jan, 18 Mar, 29 Apr, 17 Jun, 29 Jul, 16 Sep, 28 Oct.
- CPI and NFP from Jun 2024: R2's lists (`hunt/r2/events.py`, from bls.gov schedules), extended by
  `research/data/xau/us_events.csv` (bls.gov, federalreserve.gov) for 2025-26.
- Earlier dates are not needed: the intraday book starts 21 Oct 2024.
- Sources: federalreserve.gov/monetarypolicy/fomccalendars.htm, bls.gov/schedule.

### Not tested (said now)

| idea | why |
|---|---|
| MCX options versus futures for the same signal | no MCX option history reachable |
| roll timing of the trend arm | no contract-level futures history |
| US real-yield proxy | FRED unreachable |
| USDINR as separate context | kept the test count down; USDINR is inside every rupee proxy |
| silver 15m Liquidity | no XAGUSD minutes |
| gold 15m Liquidity | a logged failure (GOLD_TIMEFRAMES, M3 LIQ15) |

## 5. Random twins and multiple testing

**Daily twins:** for each real trade, a twin with:
- the same metal, the same side and the same planned holding length in trading days (capped at the period end);
- a random entry day in the same period (design or final);
- the same stop rule (3 ATR trail, or D0's 2 ATR initial stop with the same holding cap).

**Intraday twins:** for each real trade, a twin with:
- the same metal and side, the same UTC entry hour, the same stop distance as a % of price;
- a random weekday of the same period that has that bar;
- an exit at the same flat time, or after the same number of bars if the real trade ended earlier.

**Shared:**
- 500 twin books per test, fixed seed 82. p = (1 + #twin books with net ≥ the real net) / 501, one-sided.
- **Benjamini-Hochberg across all 16 tests** (T1-T4, D0-D2, G1, U1, L1-L3, E1, O1, U2, U3) on the DESIGN twin
  p-values.
- Final twin p-values are computed too.

## 6. Pass rule (per test; never relaxed)

A test passes only if ALL of these hold:

1. **Net > 0 after costs** in DESIGN, in WALK-FORWARD (the sum of out-of-sample blocks) and in FINAL.
2. **It beats its baseline in walk-forward AND final.**
   - T1-T4, D0-D2, G1, U1: the app's rule T0 on the same legs and window.
   - L2, L3, E1: their parent L1.
   - L1, O1, U2: no trade (0); they are new.
   - U3: U2.
3. **Design twin p:** BH q < 0.10 across the 16 tests, AND design twin p < 0.10.
4. **Final twin p < 0.10.**
5. **Not carried by its top 5:** the final net minus its 5 best final trades is > 0.
6. **At least 30 design trades.**

- A pass is a PAPER candidate only (CLAUDE.md: a passed idea may act on paper arms; live needs a shadow phase and Boss).
  It gets an `APP_SPEC.md` (not built).
- A fail is a note only.

## 7. Report

For each test, per window:
- trades, win rate, average win / loss, net (Rs, 1 lot each), max drawdown, worst trade;
- per metal;
- the walk-forward blocks.

Also: exit reasons; the baseline T0; example trades. Every number has its trade count.

## 8. Look-ahead guard

pytest tests (`test_r82.py`) check that:
- every signal at bar t is unchanged when every bar after t is deleted or scrambled (daily and hourly rules);
- the 252-day sign, the ATR, the Supertrend and Donchian use only bars up to t;
- liquidity breaks are acted on only after the breaking bar closes;
- the costs match `McxCosts` on a worked example.
