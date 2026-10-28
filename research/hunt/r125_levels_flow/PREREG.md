# R125 pre-registration: support/resistance from wicks, liquidity, order flow and volume, all inside the network

Written 28 Oct 2026, before any R125 feature was computed or model fitted. Research only: nothing in the app changes,
nothing trades.

Boss, 28 Oct: *"Try using support and resistance from all the wicks, try using liquidity, try order flow, try using
all the parameters, in the neural network. Also add volume data too, vertical volume too."*

## 0. The question

R124 found that its network sees a 30-point NIFTY move coming (holdout AUC 0.853 vs trees 0.842 at 30 minutes), but
its direction was a coin (holdout AUC 0.51). **Does adding wick support and resistance, liquidity, order flow and
volume, including the volume profile, let the network tell which way the move goes, and does that make money on
options after costs?**

Everything that measures a move or a level uses candle **highs and lows (wicks)**, never opens or closes (R124
amendment 1). The one exception is a trade's own reference, the index at entry.

## 1. Data, and what each new input really is

| Boss's word | what exists | what R125 uses |
|---|---|---|
| support / resistance from wicks | index 1-minute highs and lows | swing highs/lows (wick pivots) from today and the last 5 sessions, clustered into levels |
| liquidity | the same wicks | "pools" of equal highs or lows (resting stops), and **sweeps**: a wick that pokes through a level and fails |
| order flow | **daily** NSE participant OI by group (FII, Pro, Client, DII; 2012-2026, from OF1), and **per-minute** option volume and OI by strike | yesterday's participant positions; the last minutes' call-vs-put volume and option OI build-up. True tick order flow (bid/ask, absorption) exists only in the phone's recent recordings, so it cannot be backtested |
| volume | index volume exists only from Jan 2026, too short to train on; option volume exists every minute since 2021 | traded volume of the ATM±3 options (calls + puts) as the market-activity measure |
| vertical volume (volume profile) | as above | each minute's option volume spread evenly over that minute's index wick range (low to high), giving a volume-at-price histogram for today and the last 5 sessions |

All inputs at decision row t use bars ≤ t only. A pivot counts only after its confirming bars have closed. The
participant file of day d is used from day d+1 (it is published after the close).

## 2. The new inputs (4 groups, each a new specialist in the network)

The move size is m: 30 points on NIFTY, scaled by price on the other indices (R124).

**SR, support and resistance from wicks (8 inputs).**
- Pivots: a bar whose high is the highest (or low the lowest) of the 5 bars on each side. It is confirmed 5 bars
  later, and only confirmed pivots count.
- Taken from today and the previous 5 sessions. Pivots within m/3 of each other form one level; its strength is the
  number of wick touches.
- The inputs:
  - distance from bar t's high to the nearest resistance above, and from bar t's low to the nearest support below
    (in m);
  - the strength of each;
  - how many levels lie within 1 m above and below;
  - distance to the 5-session high and low (in m).

**LIQ, liquidity (7 inputs).**
- Pools: two or more confirmed pivots on the same side within m/6 of each other, not yet traded through.
  - distance to the nearest pool above and below (in m).
- Sweeps: in the last 15 bars, a bar's high went above a confirmed level, pool, or yesterday's high, and within the
  next 3 bars no high reached it again (a failed poke, on wicks only). A low sweep is the mirror.
  - whether a high sweep or a low sweep happened;
  - minutes since the last sweep;
  - how far it poked (in m).
- How often today's highs and lows were taken out: the count of new session highs minus new lows in the last 30 bars.

**FLOW, order flow (8 inputs).**
- Yesterday's participant positions:
  - FII index-futures long share and its 1-day change;
  - Pro option stance (calls net minus puts net) and its 1-day change;
  - Client option stance (scaled by total index option OI).
- The last minutes' option flow:
  - log ratio of call volume to put volume (ATM±3) over 5 and 15 minutes;
  - OI build-up score over 15 minutes, ATM±3: (+1) a call strike with premium up and OI up, or a put strike with
    premium down and OI up; (-1) the mirror; averaged over strikes. This is option-chain context, as CLAUDE.md
    allows; it is never the signal by itself.

**VOL, volume and the volume profile (8 inputs).**
- Option volume (ATM±3) over the last 5, 15 and 60 minutes, each as a log ratio to the average of the previous 20
  sessions at the same minutes.
- From the profile (today so far and the last 5 sessions, 70% value area):
  - distance from bar t's wick range to the point of control (in m);
  - distance to the value-area high and to the value-area low (in m);
  - inside the value area (0/1);
  - volume at bar t's price range relative to the profile's average (a high- or low-volume node).

## 3. Periods, rows, labels, network

- **Periods:** as R123/R124. Design 2021-08-01 .. 2026-03-15, folds 2023 / 2024 / 2025 / 2026-Q1; embargo 16-31 Mar;
  holdout 2026-04-01 .. 10-19, read once.
- **Rows and labels:** R124's rows (every 5 minutes, 09:20-14:55, all five indices). The labels are R124's
  wick-to-wick "30-point move within 15 / 30 minutes: up first, down first, neither". The trades are R124's
  index-exit option trades. Both are reused unchanged.
- **The network, N3:** R124's network (R123's multi-level N2: fast and slow encoders, specialists, gated committee,
  calibration and abstention), with **four more specialists (sr, liq, flow, vol)** on the 31 new inputs. Same
  settings, seeds and folds.
- **Comparison, the point of R125:** N3 against R124's network on the same rows (retrained here with the same seeds,
  so that only the inputs differ), plus the base rate and trees on all inputs.

## 4. Tests and gates

**Model tests** (one BH family of 4 cells). The new inputs "help" a cell only if all of these hold:
- pooled walk-forward q ≤ 0.05;
- N3 better in ≥ 3 of 4 folds;
- the holdout 95% CI excludes 0 in the same direction.

| cell | measure |
|---|---|
| direction given a move, 15 min | log-loss of P(up)/(P(up)+P(down)) and AUC; N3 vs R124's network |
| direction given a move, 30 min | as above |
| move coming, 15 min | Brier score; N3 vs R124's network |
| move coming, 30 min | as above |

**What N3 uses:** permutation importance by input group, including the four new groups.

**Rules** (signal on the underlying, buy the ATM option; R124's exits on the index's wicks: ±m or the time limit;
1 lot at today's lots, dated charges, liquidity-aware slippage, at most 5 trades a day, one open per index;
thresholds from the training rows only):

| rule | enter when | buys |
|---|---|---|
| **M1** | P(move in 15 min) in the top 10%, and direction share ≥ 0.60 or ≤ 0.40 | ATM call (up) or put (down); exits ±m / 15 min |
| **M2** | as M1, 30 min | ATM call or put; exits ±m / 30 min |
| **M3** | M1 at 09:20 / 09:25 / 09:30 only | as M1 |
| **L1** | bar t's wick is within 0.25 m of a wick level (SR) or a sweep happened in the last 15 bars (LIQ), and N3's 15-minute direction share is ≥ 0.60 or ≤ 0.40 | ATM call or put; exits ±m / 15 min |

**Gates (as R123/R124):**
- Walk-forward: ≥ 300 trades; positive average net at 1x and 2x slippage; positive in 3 of 4 folds; BH q ≤ 0.10
  over the 4 rules vs 1,000 random-entry twins.
- Holdout: ≥ 100 trades; positive at 1x and 2x; q ≤ 0.10; the account stop is not breached.
- A rule that passes everything is only *proposed* for a paper test.

## 5. Honest limits, stated before any result

- **The holdout has been read twice today**, by R123 and by R124 (whose holdout read used these exact labels). We
  already know that in it, direction was a coin for R124's network. A third read is the only data there is. It is
  disclosed, and a pass here would need a fresh forward test before anything else.
- "Order flow" here is daily positions plus option volume and OI, not tick-level bid/ask flow.
- The volume profile is built from option volume on the index's price range, a proxy: there is no historical index
  or futures volume before 2026.
- Previous studies of single levels and sweeps found nothing:
  - I12: levels held no more often than random prices;
  - R91: ICT liquidity sweeps;
  - the Liquidity Swings / Pools break rules (LIQUIDITY.md);
  - OF1: following participant groups did not pay after costs.

  R125 differs because it puts all of them together into one network with the price sequence. It does not use any
  of them as a rule on its own.

## 6. Tests (before any result)

- Pivots confirm only after their right-hand bars; levels and pools use confirmed pivots only.
- A sweep is known only after its 3 failure bars.
- Participant data is lagged one session.
- The volume profile at t uses minutes ≤ t.
- Every new input is unchanged when every bar after t is changed.
- Hand-made cases: a pool, a sweep, the value area and the point of control.

## 7. Outputs

- Code and results: `research/hunt/r125_levels_flow/`.
- Big files: `gdrive:IraAlgo-data-lake/derived/r125/`.
- Write-up: `research/HUNT_R125_LEVELS_FLOW.md`.
- Experiment entries: `research/experiments/entries/r125-*.yaml`.
- RESULT.md.

## Amendment 1 (28 Oct 2026, before any outcome or model): two definition fixes found on real inputs

A first look at the new inputs alone (10 NIFTY days in June 2025; no label, trade or model was read) showed two
definitions behaving badly:

1. **Levels chained into blobs.** Joining pivots that are each within m/3 of the next merged whole price ranges into
   one "level", with up to about 66 touches. Now a level spans at most m/3 from its first pivot.
2. **Sweeps fired on 68% of rows,** because every pivot of 5 sessions counted as a level. Sweeps now count only
   against *notable* levels, as liquidity is usually described: yesterday's high/low, the previous 5 sessions'
   high/low, the untaken equal-high/low pools, and today's confirmed swings.

Nothing else changes.

## Amendment 2 (28 Oct 2026, before any outcome or model): candlestick patterns, wick to wick

Boss, 28 Oct: *"Also add candlestick patterns too ... and wick to wick."* The run was stopped before any input file
was written. A fifth new specialist, **candle**, reads 24 wick-only pattern inputs. They use each candle's high and
low only, never its open or close. They are computed for the last *completed* 1-, 5- and 15-minute candles (aligned to
09:15), 8 each:

| input | meaning |
|---|---|
| inside | an inside bar (lower high, higher low) |
| outside | an outside bar (higher high, lower low) |
| trend | +1 for a higher high and higher low; -1 for a lower high and lower low |
| run | how many such candles in a row, capped at ±3 (the wick form of three soldiers / crows) |
| rev2 | the two-candle wick reversal: a new low, then the next candle takes out the previous high (+1); mirrored (-1) |
| swing3 | the three-candle swing: the middle candle's low is the lowest (+1), or its high the highest (-1) |
| nr7 | the narrowest range of the last 7 candles |
| wide | range divided by the average of the previous 10 |

Body patterns (hammer, doji, engulfing by body) need opens and closes, so they are left out, as Boss asked. N3 now
has five new specialists (sr, liq, flow, vol, candle) and 55 new inputs. Nothing else changes.

## Amendment 3 (28 Oct 2026, before any comparison, rule or gate was computed): VWAP and moving averages; two years

Boss, 28 Oct: *"Also add VWAP and moving averages, and test on only 1 yr of data"*, then *"Okay keep it 2 yrs."*

**What had run before this:** the full-history walk-forward was stopped part-way.
- R124's network retrained (the "base" variant) had finished its 4 folds.
- N3 had finished 2.
- No comparison, model test, rule, twin or gate was computed, and no prediction was looked at.
- Those files are set aside unread (scratch, not used, not on Drive), and everything below starts again.

**1. A sixth new specialist, vwap_ma (10 inputs).** Prices are each candle's wick midpoint, (high + low) / 2. No close
is used.

| input | meaning |
|---|---|
| vw_dist | bar t's wick span against the session VWAP, signed, in m (+ wholly above, - wholly below, 0 touching). VWAP weights are each minute's ATM±3 option volume (the index has no volume before 2026) |
| vw_slope | the VWAP's change over 15 minutes, in m |
| vw_band | (midpoint - VWAP) / the volume-weighted SD |
| ma9_dist, ma20_dist, ma50_dist | bar t's wick span against the 1-minute EMA 9 / 20 / 50 of midpoints, signed, in m |
| ma_align | +1 when EMA9 > EMA20 > EMA50, -1 when reversed, 0 otherwise |
| ma20_slope | the EMA20's change over 15 minutes, in m |
| ma_cross | signed minutes since the last EMA9/EMA20 cross (+ after an up cross; capped at 120) |
| ma5m20_dist | bar t's wick span against the EMA20 of 5-minute candle midpoints (last completed candle) |

N3 now has six new specialists and 65 new inputs.

**2. Two years of data, replacing section 3's design period.**
- The window is **20 Oct 2024 - 19 Oct 2026**: 2 years, all after NSE's Nov 2024 F&O changes (fewer weekly expiries,
  new lot sizes), so the whole period trades under today's rules.
- Design: 20 Oct 2024 - 15 Mar 2026.
- Walk-forward folds (each trains on every design row before it, with a 10-day embargo):

  | fold | test span |
  |---|---|
  | F1 | Apr - Jun 2025 |
  | F2 | Jul - Sep 2025 |
  | F3 | Oct - Dec 2025 |
  | F4 | 1 Jan - 15 Mar 2026 |

- Holdout unchanged: 1 Apr - 19 Oct 2026, read once.
- Gates unchanged: ≥ 300 walk-forward trades, 3 of 4 folds, q ≤ 0.10, ≥ 100 holdout trades, 1x and 2x slippage, the
  account stop.
- Less data means less power. A real but small edge is easier to miss, and the trade-count gates are harder to reach.
  That is accepted, as Boss's choice.

## Amendment 4 (28 Oct 2026, before any comparison, rule or gate): RSI and Supertrend

Boss, 28 Oct: *"Also add RSI and Supertrend."* The two-year run was stopped while the base network trained (3 folds'
weights saved, no prediction written or read); those files are set aside unused, and the run starts again.

A seventh new specialist, **osc** (9 inputs), on wicks:

| input | meaning |
|---|---|
| rsi1, rsi5 | Wilder RSI(14) of the wick midpoints, (high + low) / 2, on 1-minute and 5-minute candles (5-minute: the last completed candle) |
| rsi1_chg5 | the 1-minute RSI's change over 5 minutes |
| st1_dir, st5_dir | Supertrend(10, 3) direction (+1 / -1) on 1-minute and 5-minute candles |
| st1_dist, st5_dist | bar t's wick span against the Supertrend line, signed, in m |
| st1_age, st5_age | signed bars since the last flip (capped at 120) |

The Supertrend uses the true range of the highs and lows (with the previous candle's midpoint standing in for the
close), bands around the midpoint, and a flip when the midpoint crosses the active band. No close or open is used.

N3 now has seven new specialists and 74 new inputs. Nothing else changes.
