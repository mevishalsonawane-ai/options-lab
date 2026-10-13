# RC2 pre-registration: buying BTC/ETH options on Delta Exchange India with a stop and a profit lock

Written 13 Oct 2026, before any profit or loss of any rule was computed. Code: this folder (`signals.py` rules,
`exits.py` stops and locks, `costs.py` fees and spreads, `sim.py`, `stats.py`, `run.py`). The rule parameters below are
the ones in the code at the commit that adds this file. Step 1 (anatomy, design data only, no P&L) may inform
amendments, and any amendment is listed at the end with its reason, before the design run.

## Question

Boss (13 Oct): "I don't want to hold crypto. Like the Indian market: take profit and sell. Is there anything like
options in crypto?" Can an option-BUYING rule on Delta India's BTC/ETH daily options, with a stop-loss AND a profit lock
on every trade (CLAUDE.md crypto rule), make money after real costs on a locked holdout?

## Data

- Delta India public API (no keys): the index (.DEXBTUSD, .DEETHUSD) in 5-minute bars from 2024-03-01, and 5-minute
  MARK candles of the daily expiries (12:00 UTC = 17:30 IST) for the 30 hours before each settlement: strikes within
  ±3.5% of the index 24 h before (Delta's own grid, 200 BTC / 20 ETH where listed), plus coarser strikes 3.5-8% out of
  the money. Every 7th day also the traded-price candles (volume). First option day found: 2 Mar 2024.
- Delta India live option chain (all strikes, bid/ask/size), polled every 10 minutes during this session: the spread
  model.
- Deribit public data: DVOL and perp funding (hourly) as signals; option trades for the cross-check and for the
  zero-to-hero anatomy on traded prices (2022 onward).
- Fills use Delta's MARK (not a traded price) plus half the modelled spread; see Costs.

## Splits

- Design: the first Delta option day (2 Mar 2024) to 12 Apr 2026, about 25 months.
- **Holdout: 13 Apr 2026 00:00 UTC to 13 Oct 2026 12:00 UTC (6 months), read once** by `run.py holdout`, which writes
  `results/HOLDOUT_READ` and refuses a second run.
- Honest label: **partly seen.** The NN_CRYPTO and STRAD_CRYPTO studies opened Apr-Oct 2026 once for other crypto ideas
  (size forecasts, straddles). Nothing in this study was chosen on it.

## Rules (option buying only; one position per rule per coin; decisions on 5-minute closes, UTC)

Decision time T = the close of a 5-minute index bar. The option is bought at its mark at T plus half the spread.
Expiry: the nearest Delta daily expiry with at least `min_left` hours left. Strike: ATM = the listed strike nearest the
index (skip if more than 1% away); the hero: the out-of-the-money option on the signal's side priced 0.02-0.10% of the
index, the dearest one in that band (the nearest to the money).

| id | entry | min_left | max hold |
|---|---|---|---|
| R1_asia_orb | range = high/low of 00:00-01:00 UTC (05:30-06:30 IST); first 5-min close outside it, 01:00-07:00 UTC; above buys a call, below a put; one a day | 3 h (today's expiry) | 11 h (forced exit 11:50 UTC) |
| R2_eu_orb | range 07:00-08:00 UTC (London open, Deribit's 08:00 settlement); first close outside, 08:00-10:30 | 1.5 h (today's) | 3 h |
| R3_us_orb | range 13:30-14:00 UTC (US cash open, 19:00 IST); first close outside, 14:00-17:00 | 6 h (tomorrow's) | 4 h |
| R4_vol_burst | 15-minute move >= 2.5x the DVOL-implied 15-minute move (DVOL/100 x sqrt(15 min / year)); with the move; bar starts 00:00-10:30 and 12:00-22:00 UTC; 4 h cooldown | 1.5 h | 2 h |
| R5_dvol_jump | on the hour: DVOL of the hour just ended >= 3% above the hour before AND the 1-h move >= 1x the implied 1-h move; with the move; 6 h cooldown | 3 h | 3 h |
| R6_hero | decisions 09:00-11:00 UTC (14:30-16:30 IST): 15-minute move >= 2x the implied 15-minute move; buy the cheap OTM option (0.02-0.10% of the index) of TODAY's expiry on that side; one a day | today's | to 11:50 UTC |
| R7_funding_rev | on the hour: Deribit funding (8 h rate) above its trailing 60-day 97.5th percentile and the last hour down: buy a put; below the 2.5th percentile and the last hour up: buy a call; 12 h cooldown | 6 h | 6 h |
| R8_pdl_break | first 5-minute close above the previous UTC day's high (call) or below its low (put), 01:00-22:00 UTC; one per side a day | 3 h | 3 h |

Hourly inputs (DVOL, funding) for the hour starting at h are used only from h + 1 h. No position in today's expiry is
held past 11:50 UTC (17:20 IST), ten minutes before settlement.

## Exits: every trade has a stop-loss and a profit lock (four variants per rule, all run, all reported)

Levels are multiples of the entry mark. Checked on 5-minute mark bars, cautiously: a bar that opens through the level
exits at its open; otherwise a low at or below the active level exits at the level; only then does the bar's high raise
the peak (a new lock applies from the next bar). Forced exit at the rule's max hold or 11:50 UTC on expiry day.

- Directional rules (R1-R5, R7, R8):
  - `s40_step`: stop -40%; step lock: peak 1.3x locks 1.0x, 1.6x locks 1.3x, 2x locks 1.6x, 3x locks 2.4x, 5x locks
    4x, 10x locks 8x.
  - `s40_trail`: stop -40%; trailing lock from a 1.3x peak: 75% of the peak, never below 1.0x.
  - `s25_step`, `s25_trail`: the same with a -25% stop.
- The hero (R6): stop -50% or -70%; step lock 2x→1.3x, 3x→2x, 5x→3.5x, 10x→7x, 20x→14x (`s50_step`, `s70_step`);
  or a trailing lock from a 2x peak at 60% of the peak, never below 1.3x (`s50_trail`, `s70_trail`).

No variant without a stop or without a lock exists in the code (`Exit` refuses to be built without both).

## Costs

- Fee per side (Delta India API, 13 Oct 2026): min(0.01% x notional, 3.5% x premium) + 18% GST. Settlement is never
  reached (exit by 11:50 UTC).
- Spread: full spread / spot = a + b x mid / spot per coin, the WIDER (coefficient by coefficient) of two live-chain
  fits: STRAD_CRYPTO's 22 snapshots of 8 Oct and this study's polls (`results/spread_fit_live.json`);
  buys pay the ask, exits get the bid less one tick and 1% (market exits).
- Stress reading (a gate): spread x 1.5 and the fee at 0.03% of notional.
- Today's spreads are applied to 2024-2026; older books were likely thinner (the stress reading covers part of that).

## Money and tax

- Accounts Rs 25,000 and Rs 1,00,000, USD/INR 96.78. Premium paid per trade (with the entry fee) at most 2% of the
  account, whole lots (BTC 0.001, ETH 0.01); skipped when one lot costs more. Daily loss cap per rule: 5% of the account
  (IST day). Fixed account size (no compounding).
- Tax, both readings, a CA must confirm: (A) VDA, 31.2% of every winning trade, no loss set-off; (B) business income,
  31.2% of each financial year's net profit, losses carried forward. TDS 1% of sale value shown separately (a prepayment
  if it applies).

## Statistics and gates

- Random twins per rule and exit: (1) same-clock: 2,500 random bars per coin inside the rule's own entry window, random
  side, same expiry/strike/exit rules; p = share of 2,000 draws (same trade count per coin) with a mean return >= the
  rule's; (2) direction: the same entry times with a coin-flip side.
- BH across all 32 variants (8 rules x 4 exits) on the same-clock p.
- Walk-forward: per rule, from the third calendar quarter of the design span, each quarter uses the exit with the best
  mean return on all earlier design trades (>= 20); also across all 32 variants.
- **A rule passes only if its picked exit (best design mean return) clears every gate:**
  - G1: design >= 60 trades, mean return per trade > 0 and rupee net > 0 (Rs 1 lakh sizing), after costs;
  - G2: BH q < 0.10 on the same-clock twin;
  - G3: the rule's walk-forward rupee net > 0;
  - G_stress: design rupee net > 0 with spread x 1.5 and fee 0.03%;
  - G4 (holdout, read once): >= 20 trades and rupee net > 0 after costs.
  - The after-tax figures (both readings) are reported, and Boss is told plainly if a pass does not survive VDA tax.
- Every number is reported with its trade count.

## Amendments

1. R5's DVOL rise 5% -> 3% (13 Oct, before any P&L). Reason: signal count only. At 5% the design span had 57 signals
   (BTC 33, ETH 24), below the 60-trade gate; at 3% it has 200 (BTC 116, ETH 84). No P&L was looked at.
2. Spread model (13 Oct, after the anatomy, before any P&L): this study's live polls (8 snapshots, 19:00-20:10 UTC) fit
   tighter spreads (BTC 0.000018 + 0.0104 x mid/spot) than STRAD_CRYPTO's 8 Oct fit (0.000098 + 0.0179). Neither saw a
   chain in its last 6 hours (both polled in the UTC evening), and older books were likely thinner, so the simulator
   uses the wider of the two, coefficient by coefficient (in effect the 8 Oct fit). The x1.5 stress applies on top.
3. Nothing else changed after the anatomy. Its findings (options dearest per hour in 03:00-12:00 UTC, cheapest in
   13:00-16:00 UTC; cheap OTM options reach 3x on about 20% of tries but are worth 0.6-0.9x on average by 11:50) are
   reported, not used to retune any rule.
4. **Data bug fix AFTER the first design run (13 Oct; the holdout still unread).** The first design run showed R5's
   whole design profit coming from one trade: a BTC 93000 put of 3 Feb 2025 bought at a mark of 25.6 that was really
   about 1,650 (+4,889%). Delta's FIRST mark candle of a newly listed strike often opens at a stale value: 4,605 of
   81,452 listings (5.7%) have a first candle whose close is more than 3x away from its open. Fix, for every rule and
   the anatomy alike: no entry on a strike in its first 15 minutes after its first candle, nor on a bar that opens more
   than 2x away from the previous close (111 such jumps in 28.3 million bars). No rule, exit, threshold or gate changed.
   The first run's design table is kept (`scratchpad` copy, summarised in the write-up) and the design was re-run once.
   The anatomy's zero-to-hero multiples now use 5-minute mark closes (0.08% of bars have a high above 2x their open and
   close).

