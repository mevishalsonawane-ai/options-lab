# R53 crypto book: implementation notes and deviations

Written 15 Oct 2026 while building the crypto book (PREREG.md section 4, rules CC1-CC8). Research only: nothing in the
app trades crypto. Every tested version has a stop-loss AND a profit lock; the exit objects refuse to be built without
both and `rules.exit_of` refuses anything that is not a pre-registered version (tested).

## Code

| file | what |
|---|---|
| `fetch.py` | public downloads into `$IRAALGO_CACHE/r53` (never git): Deribit DVOL, Binance 5-minute metrics (OI), Delta India index 1m, Delta India option MARK 1m per contract (lazy, cached, polite pacing, retries; JSON / zipped CSV parsed only) |
| `data.py` | loaders; USD/INR (ECB, the last fix before the trade's day) and funding look-ups |
| `features.py` | the brain features F1C's rules read, recomputed on the perp with the brain's own formulas (each cited in the docstring) |
| `opt.py` | Delta option trades: RC2 strike rules, listing/jump filter, fills and stop + lock exits, R53 cost books and sizing |
| `perps.py` | F1C perp exits (`f1c.perp.walk`), RC1 idea g (`rc1.strategies`), the step-lock RT variant, R53 perp books |
| `rules.py` | CC1-CC8, their versions, signals (the studies' own functions), selection, random-entry twins |
| `run.py` | `design`, `final` (once), `repro` |
| `baselines.py` | buy-and-hold and monthly DCA, after tax |
| `repro.py` | reproduction checks against RC2 / F1C / RC1 |

## Deviations from the original studies (all fixed before any R53 P&L unless dated otherwise)

1. **Signal source: the Binance USD-M perp** for every rule (PREREG section 4). RC2 ran its signals on Delta India's
   5-minute index; F1C's brain features used Binance spot closes. `repro.py` measures how many decisions agree.
2. **Option prices: Delta India 1-minute MARK candles**, fetched per contract over the 28 hours before each 12:00 UTC
   expiry. RC2 used 5-minute MARK candles; F1C used 1-minute traded-price candles from the options lake (not reachable
   here). Exits are walked on 1-minute marks, so a stop or lock can trigger inside what RC2 saw as one 5-minute bar.
3. **Entry one minute later than the next bar** (PREREG section 2): the option is bought at the open of the 1-minute
   candle that starts at signal close + 1 minute. RC2 bought at the mark at the signal close; F1C at the signal minute's
   close. The strike is chosen at the signal from the Delta index close and the marks known at the signal.
4. **Strike universe.** RC2 fetched a fixed strike grid per expiry; R53 tries Delta's strike spacings (BTC 200/500/1000,
   ETH 20/25/50; RC2's `fetch_delta.py`) nearest first and keeps the first listed, tradable strike within 1% (ATM), or
   walks outward up to 8% for the hero's 0.02-0.10%-of-spot band. A strike that is not listed returns no candles and is
   skipped (same result as RC2's "nearest listed").
5. **F1C's "the option traded in the last 30 minutes" check is dropped**: MARK candles carry no volume. RC2's listing
   filter (no entry in a strike's first 15 minutes) and jump filter (entry candle open within 0.5x-2x of the previous
   close) are kept for every option rule.
6. **Missing 1-minute marks** are carried flat at the last close (RC2/F1C carried the previous bar's OHLC); no backfill,
   so a contract is never bought before its first candle.
7. **Money.** Options: Rs 1 lakh account, premium plus the Delta entry fee <= 2% of it, whole lots (RC2); the lot count
   is set by the Delta book and kept the same in the CoinSwitch books so the books differ only by fees. Perps: Rs 1 lakh
   notional at 1x, whole Delta lots (PREREG; F1C sized 1% risk at the stop up to 3x, RC1 half the pot per coin). USD/INR
   is the ECB fix of the day before the trade (RC2 used a fixed 96.78, F1C 85).
8. **Each study's own selection rules are kept:** RC2 rules one position per coin and a 5%-of-account daily loss cap
   per rule (both coins, IST day); F1C rules one position per coin, 3% daily loss cap per coin, the rule's max a day.
   Twins have no selection (each random entry is its own trade), as in both studies.
9. **Tax** is R53's: `common.after_tax` on each trade's rupee net after costs (31.2% of each winner, no set-off). F1C
   taxed the gross gain (fees not deductible); RC2's VDA reading taxed the net per trade. TDS 1% of each sale is a
   cash-flow line (`common.tds_locked`): options the exit sale, perps the exit of a long / entry of a short (F1C).
10. **Funding** on perps as F1C and RC1: Binance's settled rate known at entry, charged per 00/08/16 UTC settlement held.
    The public archive ends 30 Sep 2026 and Binance's API is not reachable from this session, so the last print is
    carried forward for 1-12 Oct 2026 (final test, perps only; a few settlements).
11. **Final window ends 12 Oct 2026** (the lake's last day; PREREG names 13 Oct): 195 calendar days. Design 20 Nov 2024 -
    31 Mar 2026: 497 days. A trade belongs to the part of its entry time (UTC date).
12. **CC4 and CC5 run on BTC and ETH** only (F1C ran five coins). CC5's original is F1C's design pick `s15_step`
    (`f1c/results/design.json`); its RT is the other lock, `s15_trail`.
13. **CC8**: 4-hour bars resampled from the 1m perp (RC1 resampled 1h). Long only, as RC1. The step-lock RT uses R = the
    2-ATR stop distance and F1C's ladder (+1R locks +0.25R, +nR locks +(n-1)R), checked on 4-hour bars with RC1's
    order (level first, then the 10-bar-low exit, then the peak). A trade still open at the data end closes at the last
    close (`data_end`). Without the lock the walk is RC1's `breakout_spells` exactly (tested, and checked on the real
    bars in `repro`).
14. **Twins** (PREREG section 3): a pool of random entries per rule, coin and part (options 800 design / 400 final per
    coin; perps 4,000 / 2,000; CC8 every 4-hour bar of the part), each simulated on its own with each version's exits;
    each of the 500 twins draws, with replacement, as many entries per coin as the rule traded there, and sums their
    after-tax Delta nets; p = `common.twin_p`. Rules with the same entry window share their random entries (CC4/CC5,
    CC6/CC7: the 00:00-09:00 IST 5-minute decisions), simulated with each rule's own exits and limits.
15. **Brain quality flags** (spot bars flagged gap/suspect set to NaN) are not re-applied: the Binance perp 1m file has
    no missing minute from 1 Oct 2024 to 12 Oct 2026 (1,068,480 rows).
16. **Baselines**: Binance spot, RC1's base spot costs (0.25% fee + GST, 0.15% half spread a side), Rs 1 lakh per coin;
    DCA buys on the window's first day and the first of each later month; tax per buy lot (no set-off).
17. **TDS on perps** follows F1C (1% of each perp sale), so the perp rules show a large TDS cash line (about Rs 1,000 a
    trade at Rs 1 lakh notional). RC1 assumed no TDS on INR-settled perps; a CA must confirm which applies.

## Data fetched (15 Oct 2026, cache only)

- Deribit DVOL hourly BTC/ETH: 17,809 hours each, 1 Oct 2024 - 13 Oct 2026.
- Binance 5-minute metrics (OI) BTC/ETH: 213,691 rows each, 1 Oct 2024 - 12 Oct 2026, no missing day.
- Delta India index 1m BTC/ETH: 1,068,480 minutes each, 1 Oct 2024 - 12 Oct 2026.
- Delta India option MARK 1m: 25,639 contract files (each 28 h before its expiry), for signal trades and the pre-drawn
  twin pools only (expiries Nov 2024 - Oct 2026).

## Runs

- `run design` once (15 Oct 2026, 02:04-02:45 UTC); no version had a positive design net, so there was no design pick
  and the final test read the eight originals only.
- `run final` once (03:00 UTC); the lock is `results/final_read_crypto.json`. Contracts for the final period were
  downloaded before the lock was written (data only, no P&L).
- `run repro`: `results/crypto_repro.json`.
