# HUNT h18: what decides whether candles go up or down, and can an option buyer exploit it?

Files:
- Web research: `research/MARKET_DRIVERS.md`, with sources.
- Code: `research/hunt/h18/`
  - `PREREG.md`: written before any outcome.
  - `build.py`: the gamma/OI panel.
  - `analyze.py`: tests T1-T4.
  - `liqfilter.py`: features as Liquidity filters.
  - `rule.py`: the option-buying rule, through the obuy Lab.
- Logs: `scratchpad/hunt/h18/*.log`.

Only option BUYING was tested. Choices were made on data before 2025-10-01. The holdout (2025-10-01 to 2026-10-05) was
run once at the end.

## Verdict (plain language)

**Nothing "decides" the next candle that a retail chart can see in advance.** Minute to minute, price goes where net
aggressive order flow pushes it (Cont-Kukanov-Stoikov). That flow comes from:
- institutions working big orders through the heavyweights;
- index arbitrage;
- option hedgers;
- stop cascades;
- news;
- occasionally manipulators.

Jane Street, for example, made the morning push with Rs 4,000+ crore of BANKNIFTY constituents on about 18 expiry days.
SEBI saw it only in entity-level data.

**Cracking this does not make 20 points "dust".** What our data shows:

1. **Dealer gamma (GEX) predicts HOW MUCH the market moves, not WHICH WAY.**
   - When more option gamma than usual sits near spot, the next 15-60 minutes are calmer. The incremental t is -10
     before the holdout, -6 in the holdout, and every index had the same sign.
   - In the most negative GEX_A quintile, the 30-minute range is about 10-30% wider: 30 vs 27 bps before the holdout,
     32 vs 24 in the holdout.
   - The sign is OPPOSITE to "option sellers' hedging makes moves bigger". In India, OI piles up where the market is
     quiet.
   - GEX gave no stable trend-vs-reversion effect. The gamma-flip distance flipped sign in the holdout.
2. **Max pain / max OI "magnet": no.** There was no drift toward the max-OI or max-pain strike in the last 2-3 hours of
   expiry. Some point estimates drift *away*, for example BANKNIFTY -10 bps, t -2.2. Strike clustering of expiry
   closes was mild before the holdout (23% vs 19%) and absent in the holdout.
3. **Jane Street-style "expiry morning push, then reversal": invisible in the aggregate data.** BANKNIFTY expiry days,
   Jan 2023 to Mar 2025:
   - the correlation between the morning and afternoon returns was 0.01;
   - after a morning move above 0.5%, the afternoon return in the same direction was -0.02 bps.
   - Nothing to fade. The BANKNIFTY weekly expiry no longer exists (since Nov 2024).
4. **"Liquidity sweep, then reversal" is folklore. Breaks continue; sweeps don't reverse.**
   - After a 0-0.10% poke beyond a rolling-60-min, prior-day or opening-range high/low that closes back inside, price
     drifts 0.5-1 bp *further* in the poke's direction. For 60-min levels, t ≈ -3 against the reversal.
   - Clean breaks continue: +2.3 bps in 30 minutes for 60-min highs (t 4.9), and +3.0 bps (t 3.2) in the holdout.
   - This supports Liquidity 15+5's design: it buys the break, not the fade.
5. **The only tradable-looking footprint (breaks continue) is too small for option buying.**
   - The simplest rule is "buy 1-ITM on a close beyond the 60-min high/low", with Liquidity's exits.
   - It beats random entries with the same exits (p < 0.001, BH q 0.002).
   - But net after costs it is about zero: +Rs 3k over 4,537 trades and 5 years. That is Rs 250/day gross and Rs 2/day
     net at 1 lot per index.
   - Walk-forward: -Rs 1.73 L. SPA p = 0.87 over the 12 variants.
   - Gamma filters made it worse. No rule went to the holdout.
6. **As a filter on Liquidity 15+5: nothing worth adopting.**
   - One of the 11 pre-registered filters technically passed my adoption rule: skip trades when the gamma level is in
     its middle tercile. It is non-monotone, has no mechanism, and its family-wise p ≈ 0.10.
   - In the holdout it was consistent but tiny: +Rs 45/day net at 1 lot per index (Rs 623 → 668). It cuts gross.
   - I do NOT recommend it.

**Rs/day at Rs 1 lakh: nothing new from h18.** The realistic figure stays h16's: Liquidity 15+5, 1-ITM, a third of
equity per trade, about **Rs 1,100/day net (Rs 2,500 gross) in year one at κ 0.02** (Rs 250 at κ 0.04), with a 60%
drawdown. **Rs 5,000/day at Rs 1 lakh: NO.**

What we can honestly use:
- (a) Keep trading the break, never the sweep or fade.
- (b) Read gamma concentration as a *volatility* gauge. A high gamma level near spot means calmer, which is bad for
  premium buyers. It did not help Liquidity's P&L, though.
- (c) Ignore max pain, max-OI support/resistance and stop-hunt reversal stories.

## Details

### T1: dealer gamma from the OI chain
- At every 5-minute point I built Black-Scholes gamma per strike:
  - OTM-side IV, with ATM IV / VIX as fallback;
  - T = time to the near expiry;
  - the ATM±10 OI.
- **Convention A** (US "street": dealers long calls, short puts): GEX_A = Σγ·OI_call − Σγ·OI_put.
- **Convention B** (the brief's: dealers short whatever the public buys): GEX_B = −Σγ·OI, which is always ≤ 0. Its
  feature is the log of its size relative to the same time of day over the prior 20 days ("B_lvl").
- Each regression: log(next-h range) on the feature, controlling for the past 30-min range, VIX, days to expiry, hour
  and index. Errors are day-clustered.

| t-stat, h = 30 min | ALL | BN | FIN | MIDCP | NIFTY | SENSEX |
|---|---|---|---|---|---|---|
| B_lvl, before the holdout | -10.1 | -10.8 | -5.8 | -2.6 | -12.6 | -1.8 |
| B_lvl, holdout | -6.0 | -3.5 | -1.9 | -2.6 | -7.0 | -6.1 |
| A_share, before the holdout | -6.7 | -1.6 | -4.0 | -6.7 | -1.5 | +0.2 |
| A_share, holdout | -6.6 | -4.4 | -3.3 | -8.6 | +0.6 | +0.7 |

- Convention B predicted a *positive* B_lvl coefficient. The negative result rejects the idea that "sellers are short
  gamma and hedge, so moves get bigger".
- Convention A ("positive dealer gamma means calm") is supported for BN/FIN/MIDCP, not for NIFTY/SENSEX.
- Effect size: 4-14% of the range per standard deviation.
- **Trend vs reversion.** Next-30/60-min returns load slightly on the past 30 minutes (slope +0.02-0.04, t ≈ 4 pooled):
  intraday momentum.
  - Before the holdout, the GEX interaction had the WRONG sign for theory: more momentum when GEX_A > 0.
  - Nothing was significant in the holdout.

### T2: pinning on expiry
- No index showed a consistent drift toward max-OI, max-pain, max-call-OI or max-put-OI from 12:30 or 13:30 to the
  close.
- Significant estimates point *away* from the strike: BN max-OI at 13:30 -10.3 bps (t -2.2); NIFTY max-pain -5.8 bps
  (t -2.0).
- The holdout showed nothing either. See `pre_t12.log` and `hold_index.log`.

### T3: expiry-morning push then reversal
- On expiry days the morning/afternoon correlation was between -0.06 and +0.17, with no consistent sign. Before 2023
  and in Apr-Jul 2025, non-expiry days showed mild *continuation*: after a big morning the afternoon went the same way,
  +14 to +20 bps, t ≈ 2.2.
- Holdout: nothing (|t| < 1.5).

### T4: sweeps vs breaks

Bps of index in the trade direction:

| | 30 min, before the holdout | t | 30 min, holdout | t |
|---|---|---|---|---|
| break R60H | +2.34 | 4.9 | +2.95 | 3.2 |
| break R60L | +1.04 | 2.1 | -0.53 | -0.6 |
| sweep R60H (fade) | -0.42 | -2.6 | -0.39 | -1.3 |
| sweep R60L (fade) | -0.62 | -3.3 | -0.51 | -1.6 |
| sweep PDH/PDL (fade) | +0.81 / -0.25 | 1.5 / -0.4 | -0.75 / -0.83 | ~-0.8 |

A 1-ITM option needs roughly 4-8 bps of index in about 30 minutes to cover its spread, charges and theta, so these
effects are too small.

### Option-buying rule (`rule.py`, obuy Lab, 1-ITM nearest expiry, app fills and dated costs, expiry days skipped)
- The grid had 12 variants: levels {R60, ALL} × gamma filter {none, GEX_A<0, B_lvl<0} × exits {the Liquidity arm's,
  the arm's + 30% target}.
- Data: before the holdout, 1,278 days.

| best variant | trades | gross | net | p random | WF net | SPA p |
|---|---|---|---|---|---|---|
| R60, no filter, arm exits + 30% target | 4,537 | +Rs 3.25 L | +Rs 3k | <0.001 | -Rs 1.73 L (family) | 0.87 |

- Charges took Rs 3.2 L of the Rs 3.25 L gross.
- By year (net): 2020 +25k, 2021 +40k, 2022 +24k, 2023 -12k, 2024 +73k, 2025 (Jan-Sep) -147k.
- Gamma-filtered versions lost more: GEX_A<0 -70k, B_lvl<0 -89k.
- **No variant passed the gates, so nothing was taken to the holdout.** At the h10 impact model's κ 0.01-0.04 it only
  gets worse, and a near-zero edge cannot add to Liquidity 15+5.

### Liquidity 15+5 filters (`liqfilter.py`)
- Data: h4's 1-lot trades for BN, FIN, NIFTY, SENSEX and MIDCP; 2,647 trades before the holdout and 905 in the
  holdout.
- Features are read at the last 5-minute point before the signal. Terciles are set on data before the holdout.
- Adoption rule: the filter must raise net/day in both 2021-23 and 2024-25.09, and the dropped trades must be negative
  in both.
- Of the 11 candidates, only "drop the middle B_lvl tercile" passed:

  | period | dropped trades, net/trade | all trades, net/day | filtered, net/day |
  |---|---|---|---|
  | 2021-23 | -40 | -101 | -70 |
  | 2024-25.09 | -58 | +658 | +737 |
  | holdout | -33 | +623 | +668 |

  - The dropped trades' gross also falls (1,009 → 923 in the holdout), so the gain is cost savings.
  - Permutation p for one filter is 0.0096; family-wise over 11 filters it is ≈ 0.10.
  - It is non-monotone, since both the low and high terciles are fine. Treat it as noise.
- The theory-driven filters failed:
  - "Avoid high gamma, because it is calm": the top B_lvl tercile was actually the *best* in 2024-25 and in the
    holdout.
  - Negative GEX_A, the gamma-flip distance and "trade toward max OI" all flipped sign between the halves.

### Honesty notes
- The gamma assumptions are mine; nobody publishes NSE dealer positions. Both sign conventions were tested.
- OI covers only ATM±10 of the nearest expiry. Next-week and far contracts are missing.
- The rule test reuses obuy's validated engine and costs. Impact (h10 κ) would only reduce the near-zero net.
- Holdout use: one run of `analyze.py hold` and `liqfilter.py hold`. Nothing was re-chosen afterwards.
