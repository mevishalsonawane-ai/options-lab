# HUNT R11: move anatomy: what makes the candle move, what shows up before it, what shows only during it

Written 9 Oct 2026 by R11 for Boss. It extends `MARKET_HOW.md` (h41: who/why/when the market moves). That file is not
repeated here. Frame: option BUYING only, Rs 1 lakh, 1 lot. This is a description study: no P&L was computed.

- Code: `research/hunt/r11/`
  - `build.py`: minute panels;
  - `anatomy.py`: events, controls, tests, hit rates;
  - `report.py`: labels and the table;
  - `extras.py`: census, stories, fake breakouts;
  - `partB.py`: MCX 1-second anatomy;
  - `PREREG.md`: the rules, written before the holdout was read.
- Outputs (121 MB): `scratchpad/hunt/r11/`
  - `tests_*.csv`, `lags_*.csv`, `hits_*.csv`, `recall_*.csv`, `labels.csv`, `table.md`;
  - `events_*.parquet` (one row per event, with every feature's window means);
  - `stories.txt`, `fakes.txt`, `extras.log`;
  - `B.log`, `B_*.csv` (1-second bars, 5-level heatmap data, footprint).
- Reused code:
  - R9 `lib9.py` (volume profile, value area, HVN/LVN) and R9 `gex.parquet` (dealer gamma from the NSE bhavcopy);
  - R10 `feats.py` (VWAP bands, initial balance, opening types);
  - the R7/R8 feed cleaning rules for Part B.
- Holdout: 1 Oct 2025 to 6 Oct 2026, read once (`HOLD_READ`, 9 Oct 2026 20:47 IST). Every threshold and label came from
  the design period (Aug 2020 / Aug 2021 to Sep 2025).
- Nothing was committed. No secrets were read or printed.

## The answer in plain English

**What makes the candle move.** Aggressive orders hit one side at once, in the futures, the options and the heavyweight
stocks together. Inside a big 1-minute candle, everything lights up at the same time:
- option volume (+1.3 SD);
- heavyweight cash volume (+2.2 SD);
- futures volume (+0.8 SD);
- the "delta" (signed volume) in the move's direction (+2.5 to +4 SD);
- the book on the side in the way thins out (MCX, 1-second data);
- VIX and ATM IV jump the opposite way: down on up-moves, up on down-moves (about 2 SD);
- puts are bought harder in falls than calls in rallies.

That is the anatomy, but it is the move itself, not a warning. Only the order book and the tape can show *who*
pushed, and we have no NSE history of either. In our one MCX evening, the push and the move happened in the same
second.

**What shows up before.** Three things, and none of them tells you the direction more than a minute ahead.

1. **The market is already busy (a state, not a trigger).** In the 30 minutes before a top-1% candle:
   - realised volatility is up 0.9 SD;
   - the 30-minute range is up 1.8-2.1 SD;
   - option OI is changing more (+0.9 SD);
   - heavyweight volume is up (+0.5-0.7 SD);
   - it is usually a wide initial-balance day;
   - price has moved away from the strikes where the open interest sits.

   About half of this is the day itself: big candles come on busy days. The other half is a build-up within the day.
   All of it held in the holdout.
   - Used as an alarm, "30-minute range at 2 SD" gives a top-1% minute in the next 15 minutes **34% of the time
     instead of 10%**. That is 3.3 times more often. But it is wrong 66% of the time, and it says nothing about which
     way.
   - This is volatility clustering. MARKET_HOW already says "be in the market when it moves". This is the minute-level
     version of that.
2. **The derivatives move about one minute before the cash index.** In the minute before the big candle:
   - the option-implied forward (strike + call - put) has already moved the move's way (+0.4-0.6 SD);
   - VIX has already moved the opposite way;
   - signed option flow already leans the move's way (+0.15 SD);
   - the index has already started (+0.3-0.4 SD).

   All of this held in the holdout. But the lead is **one minute**, the effect is small, and **88% of the "forward
   jumped" alarms are false**. h29 found the same lead and could not trade it.
3. **Put writing minus call writing near the money leans the move's way for 5-10 minutes before.** It is +0.2 to
   +0.3 SD, mostly before falls. It passed the design period, held in the holdout on NIFTY, but **failed on
   BANKNIFTY**. Treat it as unproven. The OI timestamps are 3-minute snapshots, so a timing artefact is possible.

**What shows up only during.** Option volume, CE-vs-PE volume, premium moves, ATM IV, VIX, futures volume, futures delta,
heavyweight volume and the first break of the initial balance all jump in the same minute as the candle. They explain it;
they cannot be traded.

**What does not show up at all**, before or during, beyond chance:
- round numbers;
- the prior day's value area, POC, HVN and LVN (same as R9);
- the opening type (same as R10);
- the sign of dealer gamma or the distance to the zero-gamma level (same as R9);
- heavyweights moving ahead of the index print (at minute resolution).

**When.** Big candles are about 4 times more common on Budget days and somewhat more common on expiry days
(1.0-1.5×). Results days of BANKNIFTY heavyweights show no excess. RBI days show 0.75-1.6× (no consistent excess). Election-result days
show about 2× on the few days we have. Unscheduled news could not be rebuilt (h33's headline data is gone and
Moneycontrol now blocks the download). h33 already found that headlines arrive after the move.

**What happens after.** After a top-1% 1-minute candle, the next 15 minutes continue the move only **47-49%** of the
time. The average is 0 bp, a coin flip. 13-21% of 5-minute breakout candles to a new day high or low (after 10:15) are
fully given back within 15 minutes. These fakes came in *busier* markets than the breakouts that held.

**For Boss, in one line:** big candles come when the market is already moving and are announced at best one minute
early by the options themselves. What they are made of shows only while they happen. **Nothing here is a trade for an
option buyer.** The useful part is the "when". The "which way" still needs the 1-second order flow that the app
records from Monday. Part C says exactly what to save so that the same anatomy can be done on NSE with depth.

---

## 1. Boss's ten words mapped to data we actually have

| concept | what we used on NSE history (1-minute) | real or proxy | in Part B (MCX, 1-second) |
|---|---|---|---|
| Heatmap (resting orders by price over time) | nothing exists | not possible | 5-level book every second, `B_heatmap_*.csv` |
| Time & sales (trade sizes, sides) | nothing exists | not possible | prints from volume changes, tick-rule side, size |
| Gamma | dealer GEX sign and zero-gamma flip from the prior day's bhavcopy OI (R9); OI concentration ATM±2 / ATM±10 every minute | proxy (the sign of dealer gamma in India is a guess, R9/R10) | - |
| Footprint (buy vs sell volume per price) | nothing exists | not possible | per price, tick-rule buy/sell volume |
| Delta | "delta PROXY" = sign of the minute's index return × option volume; signed option flow (tick rule, ATM±5); real futures delta proxy for 43-47 days | proxy (no trade sides in history) | tick-rule delta, OFI, MOFI |
| VWAP | session VWAP weighted by option volume, ±SD bands (R10's proxy, 80-89% sign agreement with the real futures VWAP) | proxy | - |
| Order flow | option-implied forward vs index, CE vs PE volume and OI ATM±2, premium moves, VIX, heavyweight cash volume, futures volume and OI (2026-07-29 on) | partly real | OFI, pulls, depth on each side |
| Auction market theory | initial balance (IB) position, first IB break, opening type, IB width (R10) | real (prices) | - |
| Volume profile | prior day's POC, VAH/VAL, HVN/LVN from option-volume-at-price (R9's proxy) | proxy | - |
| Market profile | IB / opening type as above; value area position | real (prices) / proxy | - |

Other features: round numbers (BANKNIFTY 500s, NIFTY 100s), time of day, scheduled events (h28 calendar), expiry days.
The 30-minute range was used as a squeeze/expansion measure. The study covers BANKNIFTY (1,244 days) and NIFTY
(1,515 days).

## 2. The events

- **Normal minute.** The mean absolute 1-minute move at that time of day (±2 minutes), averaged over the previous 60
  days. So a "big" candle is big for its hour and for the current regime.
- **Big candle thresholds** (set in the design period, used unchanged in the holdout):
  - 1-minute candle, top 1%: **4.7×** a normal minute;
  - 1-minute candle, top 5%: 2.8×;
  - 5-minute candles: same multiples, on the 09:15 grid.
- **Which minutes count.** Only 09:16-15:14: the 09:15 print and the 15:15-15:27 closing-auction freeze are out.
- **Merging.** Big candles less than 10 minutes apart on one day count as one event.
- **Trend leg.** At least 0.4% in at most 30 minutes, without a 0.15% pullback (on closes). The leg starts at the run's
  lowest (or highest) close and ends at the extreme before the first 0.15% pullback.

| | BANKNIFTY design (996 days) | BANKNIFTY holdout (248) | NIFTY design (1,276) | NIFTY holdout (239) |
|---|---|---|---|---|
| 1-min top 1% | 1,996 (2.0/day), median 16 bp | 500, 12 bp | 2,589, 12 bp | 525, 10 bp |
| 1-min top 5% | 6,970, 10 bp | 1,800, 8 bp | 8,740, 8 bp | 1,707, 7 bp |
| 5-min top 1% | 606, 35 bp | 155, 28 bp | 774, 27 bp | 160, 23 bp |
| trend legs | 3,425 (3.4/day), 56 bp | 541, 55 bp | 2,594, 55 bp | 318, 54 bp |
| share down | 50-53% | 44-51% | 52-59% (NIFTY's big 5-min candles lean down) | 50-55% |

**Two kinds of "ordinary moment"** were used as controls:
- **other_day**: 5 other days of the same period, at the same minute, with no top-5% minute from 30 minutes before to
  10 minutes after.
- **same_day**: up to 3 minutes of the same day, at least 45 minutes away, under the same rule. This removes the
  day's regime.

**How the effects are measured.**
- Every minute feature is a z-score against the same minute of the previous 40 days.
- Signed features are turned so that + means "in the move's direction".
- Effect = event minus controls, in SD units.
- The t-tests are clustered by day.
- Benjamini-Hochberg (BH) correction was run over all 2,900 design tests.

With thousands of events, even tiny effects pass BH: 1,767 of 2,900 did. So a label also needs **|effect| ≥ 0.10 SD
in both indices**, and then the holdout.

## 3. Feature by feature

Columns:
- **Label**: the PREREG rule on the design period (other-day controls, 1-minute top-1% / top-5% candles, both indices).
  - **STATE**: already there 16-30 minutes before, about as strong as just before.
  - **LEADS**: rises 2-15 minutes before.
  - **1-MIN LEAD**: only the minute just before.
  - **COINCIDENT**: only during the candle.
  - **NONE**: not significant before or during.
- **Before**: the effect in the label's window, in SD, BANKNIFTY / NIFTY:
  - STATE = minutes -15..-6;
  - LEADS = -5..-2;
  - 1-MIN LEAD = -1;
  - NONE = -5..-2.
- **Same-day**: the same window against same-day controls (how much survives once the day's regime is removed).
- **During**: during the top-1% candle, design → holdout.
- **Hit 15 min**: of all the times the feature "spiked", how often a top-1% minute followed within 15 minutes.
  - A spike is z ≥ 2. For signed features it is |z| ≥ 2, and the move must then go in the predicted direction.
  - For flags, the flag being on is the spike. For round numbers it is being within 5% of the grid of a round level.
  - **Base** = the same rate at ordinary minutes of the same time of day.
  - **Lift** = hit / base.
  - **False alarm** = 1 - hit (holdout).
  - **"after quiet"** = only spikes with no top-5% minute in the previous 15 minutes, which strips out the most obvious
    clustering.

| feature | label | before BN / NF (design) | same-day BN / NF | before BN / NF (holdout) | holdout confirms | during, design → holdout (BN / NF) | hit 15 min design / holdout | base | lift design / holdout | false alarm (holdout) | lift after quiet design / holdout |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Realised volatility (\|1-min return\|) | STATE | +0.83 / +0.91 | +0.46 / +0.49 | +0.90 / +0.88 | yes | +5.7 / +5.7 → +5.6 / +5.6 | 23% / 24% | 10% | 2.4 / 2.3 | 76% | 2.0 / 2.0 |
| 30-min range | STATE | +1.78 / +1.89 | +0.95 / +1.00 | +2.11 / +1.98 | yes | +2.3 / +2.4 → +2.6 / +2.5 | **34% / 34%** | 10% | **3.5 / 3.3** | 66% | 3.1 / 3.3 |
| IB width vs 20-day average (day level) | STATE | +0.33 / +0.39 | 0 (day level) | +0.37 / +0.37 | yes | same | 33% / 26% | 11% | 3.3 / 2.5 | 74% | 3.1 / 2.6 |
| OI activity ATM±2 (any change, 5 min) | STATE | +1.00 / +0.89 | +0.74 / +0.67 | +0.85 / +0.96 | yes | +1.0 / +0.9 → +1.0 / +1.1 | 20% / 18% | 10% | 2.0 / 1.8 | 82% | 1.6 / 1.4 |
| Heavyweights' cash volume (Oct 2024 on) | STATE | +0.52 / +0.49 | +0.33 / +0.28 | +0.70 / +0.71 | yes | +2.2 / +2.2 → +1.7 / +1.7 | 21% / 19% | 10% | 2.1 / 1.8 | 81% | 1.6 / 1.6 |
| OI concentration near spot (ATM±2 / ATM±10) | STATE (low) | -1.21 / -1.04 | -0.31 / -0.15 | -0.38 / -0.88 | yes | -1.3 / -1.1 → -0.4 / -0.9 | 3% / 5% (high conc. = calm) | 11% | 0.3 / 0.5 | 95% | 0.4 / 0.7 |
| Option volume ATM±10 | STATE | +0.27 / +0.35 | +0.25 / +0.32 | +0.15 / +0.34 | **no** (BN p 0.36) | +1.3 / +1.3 → +1.2 / +1.2 | 16% / 13% | 10% | 1.6 / 1.3 | 87% | 1.3 / 1.1 |
| Distance to VWAP (absolute, SD) | STATE | +0.16 / +0.15 | +0.14 / +0.19 | +0.07 / +0.07 | no | +0.4 / +0.3 → +0.2 / +0.2 | 17% / 16% | 11% | 1.7 / 1.5 | 84% | 1.3 / 1.3 |
| Distance to zero-gamma flip (prior-day ranges) | STATE (closer) | -0.10 / -0.12 | -0.06 / -0.04 | -0.12 / -0.14 | no (BN p 0.21) | same | 12% / 14% | 10% | 1.2 / 1.3 | 86% | 1.0 / 1.2 |
| Price drift in the move's direction | 1-MIN LEAD | +0.42 / +0.37 | +0.49 / +0.46 | +0.33 / +0.25 | yes | +5.1 / +5.1 → +5.0 / +5.0 | 15% / 15% | 6% | 2.6 / 2.4 | 85% | 2.1 / 2.1 |
| Option-implied forward minus index | 1-MIN LEAD | +0.40 / +0.42 | +0.42 / +0.42 | +0.44 / +0.58 | yes | -0.3 / -0.1 → -0.7 / 0.0 (the index catches up) | 12% / 12% | 6% | 2.0 / 1.9 | 88% | 1.7 / 1.7 |
| India VIX change (against the move) | 1-MIN LEAD | -0.16 / -0.16 | -0.19 / -0.18 | -0.37 / -0.26 | yes | -1.9 / -2.3 → -2.2 / -2.5 | 11% / 10% | 6% | 1.9 / 1.6 | 90% | 1.5 / 1.3 |
| Signed option flow (tick rule) | 1-MIN LEAD | +0.19 / +0.16 | +0.21 / +0.19 | +0.12 / +0.16 | yes | +0.7 / +0.7 → +0.7 / +0.7 | 6% / 8% (rare spikes) | 6% | 1.0 / 1.2 | 92% | 0.8 / 1.6 |
| ATM IV change | 1-MIN LEAD | -0.19 / -0.20 | -0.19 / -0.27 | -0.12 / +0.13 | no | -1.7 / -1.8 → -2.3 / -1.3 | 10% / 12% | 6% | 1.7 / 1.9 | 88% | 1.4 / 1.7 |
| Delta PROXY (return sign × option volume) | 1-MIN LEAD | +0.33 / +0.33 | +0.48 / +0.43 | +0.16 / +0.31 | no (BN p 0.19) | +2.7 / +2.5 → +2.4 / +2.7 | 9% / 8% | 6% | 1.5 / 1.4 | 92% | 1.3 / 1.2 |
| Distance to VWAP (signed) | 1-MIN LEAD | +0.19 / +0.12 | +0.11 / +0.12 | -0.06 / +0.11 | no | +0.9 / +0.8 → +0.5 / +0.7 | 11% / 11% | 6% | 2.0 / 1.7 | 89% | 1.5 / 1.3 |
| OI: put writing minus call writing ATM±2 | LEADS (5-10 min) | +0.28 / +0.22 | +0.34 / +0.29 | +0.12 / +0.30 | **no** (BN p 0.21; NF p < 0.001) | +0.5 / +0.4 → +0.3 / +0.5, then +1 to +2.6 in the 4 minutes after | 11% / 10% | 6% | 1.8 / 1.7 | 90% | 1.6 / 1.4 |
| ATM CE minus PE premium move | LEADS (small) | +0.11 / +0.11 | +0.12 / +0.12 | +0.11 / +0.06 | no (NF p 0.22) | +4.3 / +4.0 → +3.8 / +4.0 | 9% / 10% | 6% | 1.6 / 1.6 | 90% | 1.3 / 1.4 |
| CE vs PE volume ATM±2 | COINCIDENT | +0.07 / +0.03 | +0.07 / +0.10 | -0.12 / +0.07 | yes (during) | +0.9 / +0.7 → +0.3 / +0.7 | 11% / 8% | 6% | 1.9 / 1.4 | 92% | 1.6 / 1.1 |
| Outside the initial balance | COINCIDENT | +0.03 / +0.01 | -0.07 / -0.03 | -0.07 / +0.01 | yes (during) | +0.22 / +0.19 → +0.11 / +0.15 | 7% / 7% | 6% | 1.2 / 1.0 | 93% | 1.1 / 1.0 |
| First IB break this minute | NONE (as a precursor) | 0 | 0 | 0 | - | +0.06 / +0.05 → +0.05 / +0.04 (share of candles that are the first IB break) | 8% / 8% | 6% | 1.4 / 1.4 | 92% | 1.0 / 0.9 |
| Heavyweights ahead of the index print | NONE (design label COINCIDENT at -0.05, not confirmed) | -0.06 / -0.05 | -0.03 / 0.00 | -0.01 / -0.05 | no | +0.3 / -0.2 → -0.3 / +0.2 | 9% / 11% | 6% | 1.6 / 1.7 | 89% | 1.4 / 1.6 |
| Above the zero-gamma flip | COINCIDENT in design, not confirmed | -0.03 / -0.01 | -0.05 / -0.03 | -0.11 / -0.09 | no | +0.14 / +0.14 → -0.02 / +0.03 | 6% / 6% | 6% | 1.0 / 1.0 | 94% | 1.0 / 1.0 |
| Dealer gamma negative (prior day, US convention) | NONE | -0.03 / +0.02 | 0 | +0.26 / -0.04 | - | same | 10% / 14% | 10% | 1.0 / 1.4 | 86% | 0.9 / 1.2 |
| Distance to a round number | NONE | 0.00 / -0.01 | -0.01 / -0.01 | +0.02 / 0.00 | - | 0.00 / -0.01 → +0.02 / -0.02 | 10% / 10% | 10% | 1.0 / 1.0 | 90% | 1.0 / 1.0 |
| Above VAH / below VAL (prior day) | NONE | -0.04 / -0.04 | -0.05 / -0.04 | -0.15 / -0.09 | - | +0.10 / +0.10 → -0.05 / 0.00 | 6% / 6% | 6% | 1.0 / 1.0 | 94% | 1.0 / 0.9 |
| Distance to prior-day POC / HVN / LVN | NONE | ±0.06 | ±0.03 | ±0.09 | - | ±0.08 | POC 16% / 22%; LVN unstable (315 spikes) | 11% | 1.6 / 2.1 (POC far = busy day) | 78% | 1.3 / 1.7 |
| Opening type direction | NONE | -0.01 / -0.01 | 0 | -0.03 / -0.04 | - | -0.01 → -0.03 | 6% / 6% | 6% | 1.0 / 1.0 | 94% | 1.0 / 0.9 |

Futures. Real 1-minute futures volume and OI exist only from 29 Jul 2026, so 43-47 sessions, all in the holdout.
There were 36-40 top-1% candles and 123-148 top-5% candles per index.

| futures feature (holdout only) | 5-2 min before, BN / NF (1-min top-5% candles) | during | hit 15 min / base (top-1%) | reading |
|---|---|---|---|---|
| futures volume | +0.11 (p 0.47) / +0.24 (p 0.05) | +0.72 / +0.88 (p < 0.001) | 12% / 10% | coincident; slightly raised before, like option volume |
| futures OI activity (any change) | +0.45 (p 0.06) / +0.42 (p 0.11) | +0.77 / +0.52 | 12% / 11% | state, like option OI activity; too few days to call |
| futures OI change (signed) | +0.07 / -0.07 | +0.49 / +0.28 (n.s.) | 7% / 6% | nothing before |
| futures delta proxy | +0.56 (p 0.06) / +0.16 | **+2.1 / +3.0** | 7% / 6% | coincident (the push itself) |
| futures minus index (basis change) | -0.03 / +0.02 | **-1.5 / -1.3** | 7% / 6% | the future moved first within the minute, so the index catches up and the basis narrows |

How the futures OI moved in the candle and the next 4 minutes (top-5% candles), against random minutes of the same
days:

| | long build-up | short build-up | short covering | long unwinding | OI flat |
|---|---|---|---|---|---|
| BANKNIFTY big candles (258) | 40% | **30%** | 4% | 3% | 24% |
| BANKNIFTY random minutes | 41% | 13% | 2% | 2% | 42% |
| NIFTY big candles (228) | 43% | **47%** | 2% | 3% | 6% |
| NIFTY random minutes | 57% | 28% | 2% | 1% | 12% |

- OI almost never falls in a big candle: covering and unwinding happen 2-4% of the time.
- Big candles bring *new* positions, and short build-up (price down, OI up) is over-represented.
- The futures OI was rising all through this window, as the October contract became the near month.
- So read this as "big moves add positions", not as a direction tell.

### 3a. Minute by minute: when each feature starts to move

Effect against other-day controls, in SD, mean of BANKNIFTY and NIFTY, 1-minute top-1% candles. Signed features are
aligned with the move. Lag 0 is the big candle itself.

| feature | -30 | -15 | -10 | -5 | -3 | -2 | -1 | **0** | +1 | +2 | +4 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| realised volatility (design) | 0.93 | 0.93 | 0.92 | 0.71 | 0.73 | 0.85 | 1.12 | **5.68** | 1.70 | 1.37 | 1.20 |
| realised volatility (holdout) | 0.94 | 0.96 | 0.93 | 0.88 | 0.91 | 0.88 | 1.15 | **5.60** | 1.51 | 1.54 | 1.16 |
| 30-min range (holdout) | 1.67 | 2.02 | 2.04 | 2.05 | 2.06 | 2.08 | 2.11 | **2.56** | 2.74 | 2.78 | 2.81 |
| option volume (holdout) | 0.23 | 0.27 | 0.22 | 0.29 | 0.30 | 0.35 | 0.41 | **1.18** | 1.03 | 0.71 | 0.52 |
| heavyweight volume (holdout) | 0.73 | 0.78 | 0.70 | 0.80 | 0.79 | 0.86 | 0.97 | **1.71** | 1.36 | 1.02 | 0.96 |
| price drift (holdout) | -0.07 | 0.09 | 0.14 | -0.06 | 0.03 | 0.07 | **0.29** | **4.97** | 0.02 | -0.05 | 0.07 |
| forward minus index (holdout) | 0.04 | 0.10 | 0.08 | 0.02 | 0.15 | 0.13 | **0.51** | -0.36 | 0.04 | 0.04 | 0.02 |
| VIX change (holdout) | 0.10 | 0.00 | -0.15 | 0.03 | -0.17 | -0.05 | **-0.31** | **-2.37** | -0.50 | -0.15 | -0.14 |
| signed option flow (holdout) | -0.06 | 0.03 | 0.04 | -0.02 | 0.02 | 0.06 | 0.14 | **0.72** | -0.05 | -0.04 | 0.03 |
| CE vs PE volume (holdout) | -0.13 | -0.08 | -0.04 | -0.02 | -0.03 | -0.02 | 0.03 | **0.47** | 0.82 | 0.59 | 0.52 |
| put minus call writing (design) | 0.09 | 0.12 | 0.17 | 0.24 | 0.27 | 0.29 | 0.33 | 0.48 | 0.91 | 1.41 | **2.34** |
| put minus call writing (holdout) | -0.06 | -0.03 | 0.17 | 0.19 | 0.22 | 0.20 | 0.25 | 0.42 | 0.72 | 1.08 | **1.71** |
| futures delta proxy (holdout, 43 days) | -0.93 | -0.13 | -0.88 | 0.73 | 0.64 | 0.73 | **2.20** | **3.96** | 0.30 | 0.65 | 0.01 |

How to read it:
- **Volatility, range and heavyweight volume are flat-high for the whole half hour.** That is the state.
- **Price, the forward, VIX and option flow step up only at -1.** That is the one-minute lead.
- **CE/PE volume and OI peak after the candle.** Traders react to the move: they buy the side that moved and write the
  other. That is why "OI build-up" looks so convincing on a chart afterwards.
- **The futures delta proxy jumps at -1.** This is the futures trading ahead of the cash index by seconds, seen in
  1-minute bars.

### 3b. Up moves and down moves are not mirror images (top-1% candles, raw z)

| | BN down | BN up | NF down | NF up |
|---|---|---|---|---|
| CE minus PE volume during | **-0.94** | +0.55 | **-0.99** | +0.34 |
| VIX change during | +1.70 | -2.27 | +2.09 | -2.62 |
| put minus call writing, 5-2 min before (raw) | **-0.39** | +0.09 | **-0.55** | -0.12 |

- Falls bring heavier put buying than rallies bring call buying.
- The only pre-move OI lean (call writing ahead of the move) is seen before *falls*.

## 4. When, and what happens next

Big moves per day on flagged days ÷ big moves per day on other days (design → holdout):

| day type | BN top-1% candles | BN trend legs | NF top-1% candles | NF trend legs |
|---|---|---|---|---|
| Budget day (5-6 days + 1) | **3.9 → 4.0** | **4.0 → 3.7** | **3.8 → 4.2** | **4.2 → 9.3** |
| election-result day (8-9 + 1) | 1.1 → 2.0 | 3.0 → 1.8 | 0.9 → 1.8 | 3.6 → 2.3 |
| RBI policy day | 1.24 → 0.99 | 1.25 → 1.63 | 1.19 → 1.14 | 1.21 → 0.75 |
| expiry day | 1.24 → 1.40 | 1.03 → 1.17 | 1.00 → 1.53 | 0.91 → 1.02 |
| day after US CPI | 0.72 → 1.62 | 0.82 → 1.58 | 0.81 → 1.00 | 0.73 → 1.29 |
| day after FOMC | 1.14 → 1.06 | 1.09 → 1.15 | 1.08 → 1.06 | 1.07 → 1.39 |
| day after a bank heavyweight's results | 0.78 → (1 day) | 0.89 | 0.70 | 0.90 |
| any scheduled event | 1.09 → 1.38 | 1.26 → 1.36 | 1.08 → 1.36 | 1.17 → 1.40 |

**Time of day.**
- Normalised big candles are spread almost evenly over the day, by design. They are 6% of events in the first half
  hour (09:16-09:44, normalised) and 7-10% in every later half hour.
- Trend legs (an absolute 0.4%) cluster at the open: 23% start before 09:45, and 8-10% in the last hour.
- This is MARKET_HOW's U-shape again.

**What happens next**, in the move's direction, from the end of the candle:

| | +15 min, mean | share continuing | +30 min, mean |
|---|---|---|---|
| 1-min top-1% (BN / NF, design) | +0.1 / -0.1 bp | 47% / 47% | -0.4 / -0.4 bp |
| 1-min top-1% (holdout) | +0.4 / +1.1 bp | 48% / 48% | +0.6 / +1.5 bp |
| 5-min top-1% (BN / NF, design) | +2.1 / +2.2 bp | 50% / 52% | +2.6 / +1.6 bp |
| 5-min top-1% (holdout) | +0.5 / +2.2 bp | 50% / 52% | -1.1 / +1.5 bp |

- A big candle tells you nothing about the next 15-30 minutes. The +2 bp after big 5-minute candles is below the
  ~2 bp cost of an option round trip (R7).
- Trend legs show -16 bp, but only because a leg *ends* at the pullback that defines it.
- **Fake breakouts.** These are 5-minute top-5% candles that make a new day high or low after 10:15 and are fully given
  back within 15 minutes:
  - BANKNIFTY: 12.8% design, 21.1% holdout;
  - NIFTY: 14.9% / 14.6%.
- **The fakes came in busier markets.** Compared with breakouts that held:
  - the 30-minute range was +0.56 SD higher (design) and +0.63 SD higher (holdout, p 0.04);
  - realised volatility was +0.24 / +0.30 SD higher.
- That fits a wild, two-way market. It is also partly mechanical: when volatility is high, giving back one candle is
  easier.

## 5. Stories: 15 real big moves

How the stories are written:
- "z" = SD units against the same minute of the previous 40 days. For drift, forward, flow and CE-PE, + means in the
  move's direction. VIX and IV are raw: + means rising.
- "Next" is measured in the move's direction.
- Full feature dumps are in `scratchpad/hunt/r11/stories.txt` and `fakes.txt`.

1. **BANKNIFTY, 6 Dec 2024, 10:22, RBI policy day: UP +68 bp in one minute (23× normal).**
   - **Before:** unusually *quiet*. The 30-minute range was z -1.4 and option volume z -1.8. Price was 1.7 SD below
     VWAP, inside the prior day's value area.
   - **The minute before:** the index started (drift z +2.5) and the CE premium led (+1.2).
   - **During:** ATM IV collapsed (z -6), VIX fell (z -2.9) and heavyweight volume exploded (z +5.1).
   - **Next:** -9 bp after 15 minutes, +12 bp after 30. This is what a scheduled announcement looks like: calm, then a
     jump at a known hour.
2. **BANKNIFTY and NIFTY, 18 Apr 2024, 13:26, NIFTY's expiry day: DOWN -48 / -56 bp (17-28×).**
   - **Before:** nothing. BANKNIFTY option volume was below normal (z -0.5 to -1.0). NIFTY's option volume was
     raised all afternoon (expiry, z +1.3-1.6), but its drift was flat.
   - **During:** IV and VIX jumped (VIX z +4.7) and CE-PE volume tilted to puts (+1.4 to +3.7).
   - **Next:** the fall *continued*, +22 to +25 bp more after 15 minutes. One of the few that ran on. No warning in
     any of our data.
3. **BANKNIFTY, 17 Jul 2026, 14:15 (holdout): UP +36 bp.**
   - **The 5-2 minutes before:** price drifting up (z +1.7) and option volume rising (z +1.2).
   - **The minute before:** the forward (z +2.9), the CE-PE premium spread (z +2.5) and option flow (z +1.7) all led.
     This is the textbook "options lead by a minute" case.
   - **Next:** +2 bp after 15 minutes, -1 after 30. Nothing followed.
4. **BANKNIFTY and NIFTY, 12 Mar 2026, 11:18, day after US CPI, VIX 21.7 (holdout): DOWN -29 / -28 bp.**
   - **Before:** a busy morning (realised volatility z +1.6-1.9, 30-minute range z +1.8-2.3), but option volume
     *below* normal. Price was below the prior day's value area, with OI concentration near spot very low (z -1.2 to
     -3.3).
   - **The minute before:** the forward led again (z +2.0 / +2.4).
   - **During:** VIX jumped (z +6).
   - **Next:** flat.
5. **NIFTY, 21 Jan 2025, 11:45: UP +41 bp (22×).**
   - **Before:** slightly busy (realised volatility z +0.7-1.7) and below VWAP.
   - **The minute before:** the move had already begun (drift z +4.7) and the CE premium led (+2.2).
   - **During:** heavyweight volume z +6, IV and VIX down hard.
   - **Next:** +36 bp after 15 minutes, then fully reversed (-28 bp at 30).
6. **NIFTY, 22 Sep 2026, 14:14, expiry day (holdout): UP +21 bp.**
   - **Before:** clearly busy. Realised volatility z +2.5 to +3.7 over the last 15 minutes, option volume z +1.5 to
     +2.1, option OI activity z +3.6 (30-16 minutes before), futures OI activity z +2.4 (5-2 minutes before).
   - **The minute before:** the forward z +6 (capped) and the CE-PE premium z +3.8.
   - **During:** futures delta proxy z +6, futures volume z +1.2.
   - **Next:** +3, then -6 bp. A busy expiry afternoon: the move was visible a minute early and went nowhere.
7. **BANKNIFTY, 6 May 2026, 13:05 (holdout): UP trend leg, +221 bp in 87 minutes.**
   - **Before:** nothing busy (realised volatility and option volume z ≈ 0). Price was stretched 2.2 SD *below* VWAP and
     VIX had just risen (z +2.2), so the leg started from a washed-out low under the initial balance.
   - **During:** it broke the IB high on the way up.
   - **Next:** +26 bp more by 30 minutes.
   - A slow leg like this is invisible to every "spike" measure. Each minute was ordinary (z +0.3); only the sum was
     big.
8. **BANKNIFTY, 1 Feb 2023, 14:35, Budget day: DOWN leg, -301 bp in 17 minutes.**
   - **Before:** extremely busy (30-minute range z +6, option OI activity z +4.5). There was heavy *call writing*
     (aligned z +5.6) and put buying (CE-PE volume aligned +2.3) 15-6 minutes before.
   - **The minute before:** a sharp bounce (drift z -5.8), the last up-tick before the slide.
   - **Next:** **fully reversed**: 224 bp back up within 15 minutes and 277 bp within 30.
   - Here the OI lean was right about the leg, and then the whole leg was given back. It is also the kind of day
     (Budget, with an ongoing stock-specific crisis) where everything is extreme.
9. **NIFTY, 1 Feb 2021, 12:46, Budget day: UP leg, +194 bp in 16 minutes.**
   - **Before:** very busy (30-minute range z +6, realised volatility z +3.3) and a small dip just before (drift z
     -3.8).
   - **During:** CE-PE volume z +2.0.
   - **Next:** -24, then +25 bp.
10. **NIFTY, 21 Dec 2020, 14:12: DOWN leg, -314 bp in 22 minutes** (the day the new COVID strain hit world markets).
    - **Before:** busy (30-minute range z +2.3). Call writing over put writing (aligned z +4.3) and put buying (CE-PE
      +2.1) 15-6 minutes before. Price was below the prior day's value area.
    - **Next:** reversed: 145 bp back within 15 minutes.
    - A second case where the OI/volume lean came first, and again the leg was largely given back. Stories 1-7 show the
      lean is not the rule.
11. **BANKNIFTY, 24 Jul 2024, 13:40, weekly expiry: UP +30 bp.**
    - **Before:** loud. Option volume z +2.2 to +3.2, option OI activity z +3.5 and put writing z +3.3 (aligned) from
      15 minutes before, and the index drifting up (z +2.4) in the 5 minutes before.
    - **During:** IV *rose* (z +6): an expiry squeeze.
    - **Next:** **-38 bp after 15 minutes and -76 after 30.** The loudest "bullish" build-up in this set was fully
      reversed.
12. **NIFTY, 1 Feb 2024, 11:16, Budget day and expiry day: DOWN -26 bp.**
    - **Before:** the speech had started. Realised volatility z +2.8 and option volume z +2.0 in the 15-6 minutes
      before.
    - **The minute before:** the forward led (z +2.0).
    - **Next:** -7, then -19 bp (reversed).
13. **NIFTY, 14 Nov 2025, 15:00, election-result day (holdout): UP +24 bp.**
    - **Before:** nothing.
    - **When:** the 15:00 minute, the start of the old closing-price window (MARKET_HOW 3e).
    - **During:** VIX z -3.6 and heavyweight volume z +2.3.
    - **Next:** +38 bp after 15 minutes. Closing-window flows, not news.
14. **NIFTY, 4 Jun 2024, election results.** The biggest day in the sample. By the section 4 rule this candle is also a fake breakout.
    - A -114 bp 5-minute candle at 12:20 followed hours of extreme volatility: 30-minute range z +6, option OI activity
      z +4.4, call writing z +4.4.
    - It was then reversed by 47 bp within 15 minutes and by 290 bp within 30.
15. **BANKNIFTY, 18 Apr 2024** (story 2) and **NIFTY, 18 Apr 2024** moved together to the minute. Most top-1% candles
    are index-wide: the same 13:26 minute is a top-1% candle in both. Heavyweights did not move first at minute
    resolution (table, "heavyweights ahead": no).

### Five fake breakouts (new day extreme after 10:15, fully given back within 15 minutes)

1. **NIFTY, 1 Feb 2026, 11:05, Budget day (Sunday session; holdout): UP 5-minute candle, +21 bp, to a new high.**
   - **Before:** quiet.
   - **The minute before:** the forward jumped (z +5.6), but VIX was also *rising* (z +2.7).
   - **Next:** -19 bp after 15 minutes, -20 after 30.
2. **BANKNIFTY, 27 Jan 2026, 11:40, monthly expiry (holdout): UP +23 bp to a new high.**
   - **Before:** everything looked bullish. Option volume z +4, option OI activity z +3.8, put writing z +3.5,
     heavyweight volume z +1.9, CE premium z +6.
   - **Next:** -29 bp after 15 minutes.
3. **BANKNIFTY, 24 Mar 2026, 12:45, VIX 25 (holdout): UP +30 bp.**
   - **Before:** price already 2.5 SD above VWAP after a 15-minute run (drift z +2.6, put writing z +3.8).
   - **Next:** -37 bp after 15 minutes (back to -5 at 30).
4. **NIFTY, 1 Feb 2022, 13:10, Budget day: DOWN -87 bp to a new low.**
   - **Before:** 2.6 SD below VWAP, VIX rising.
   - **Next:** **+95 bp the other way within 15 minutes.**
5. **BANKNIFTY, 1 Feb 2022, 13:10, the same minute: DOWN -88 bp.** Reversed +94 bp within 15 minutes.

**What the fakes share:**
- They were often already stretched far from VWAP in the breakout's direction.
- They came in busy markets.
- The "confirming" evidence (OI build-up, option volume, the forward's lead) looked just as strong as in the
  breakouts that held.

None of this separates fakes from real breakouts reliably (section 4). R10 found the same: "skip beyond ±2 SD" helped
only a losing arm.

## 6. Part B: the order-book anatomy at 1-second resolution (MCX, ONE evening)

**Sample warning.** This is R7's Dhan full-feed recording on 9 Oct 2026, 22:55-23:29 IST: 34 minutes, about 2,000
seconds per contract, 33 full minutes each. It covers CRUDEOIL and NATURALGAS October futures plus ATM±1 options. Every
number below is an anecdote. There is no NSE data, no open, and no expiry.

Cleaning, as R7/R8:
- drop exact repeats and older states re-sent;
- drop crossed books;
- sign trades with the tick rule;
- a "pull" is size that leaves a still-visible level without a trade at that price.

**1-second lead/lag over the whole evening** (correlation of the feature at second s-k with the mid change at second
s; k > 0 means the feature came first):

| feature | CRUDEOIL k=0 | k=1 | k=2 | k=5 | NATURALGAS k=0 | k=1 | k=2 | k=5 |
|---|---|---|---|---|---|---|---|---|
| MOFI (5-level order-flow imbalance) | **0.77** | 0.00 | 0.01 | -0.02 | **0.90** | -0.03 | -0.06 | 0.00 |
| OFI (best level) | 0.57 | 0.03 | 0.03 | -0.02 | 0.55 | 0.04 | 0.01 | 0.00 |
| delta (tick-rule signed volume) | 0.18 | 0.01 | 0.00 | 0.01 | 0.42 | -0.02 | 0.00 | -0.02 |
| ATM±1 option flow (calls minus puts) | 0.26 | **0.13** | 0.04 | 0.01 | 0.43 | 0.05 | 0.00 | -0.01 |
| volume × price sign | 0.28 | -0.04 | 0.00 | 0.01 | 0.67 | 0.01 | -0.03 | -0.02 |

- **The push and the price change happen in the same second.** One second later the correlation is zero.
- The only lagged value worth noting is crude's option flow: 0.13 when the options come first (k=1), but 0.32 and 0.20
  when the futures move first (k=-1, k=-2, not shown). So in crude the options mostly *follow* the futures by 1-2
  seconds.
- Summed over 10 seconds, OFI "predicts" the next 10 seconds at 0.20 (crude) and 0.06 (gas). Over the next 60 seconds
  it is ≈ 0. The same as R7.

**The 4 largest 1-minute moves per contract** (10-19 bp, against a median minute of 3-4.5 bp):

| | CRUDE 23:16 ↓ 15 bp | CRUDE 22:57 ↓ 12 | CRUDE 23:00 ↓ 11 | CRUDE 22:56 ↑ 11 | GAS 23:03 ↑ 19 | GAS 23:06 ↑ 19 | GAS 23:13 ↓ 13 | GAS 23:04 ↑ 10 |
|---|---|---|---|---|---|---|---|---|
| second when 25% of the move was done | 27 | 26 | 40 | 3 | 10 | 18 | 3 | 55 |
| second when MOFI first exceeded 2 SD (move-aligned, 10-s sum) | 28 | 27 | 37 | 7 | -31 | -59 | -46 | -50 |
| volume in the minute (lots) vs other minutes' mean | 130 vs 64 | 74 | 136 | 120 | 519 vs 238 | 690 | 272 | 364 |
| largest single print (lots) vs other minutes' mean max | 65 vs 26 | 16 | 74 | 86 | **368** vs 72 | **276** | 36 | 178 |
| aggressor share in the move's direction | 76% | 82% | 27% (!) | 92% | 93% | 88% | 62% | 91% |
| 5-level depth in the way: 60 s before → during | 64 → 65 | 59 → **46** | 56 → 56 | 59 → 55 | 368 → **306** | 305 → **248** | 280 → **201** | 306 → 313 |

What came first:
- **Crude:** the order-flow imbalance and the price moved *together*, within 1-4 seconds of each other in all 4
  minutes. Nothing led.
- **Gas:** MOFI was already above 2 SD 30-60 seconds before in all 4 big minutes. But it was also above 2 SD in the 60
  seconds before **24% of ordinary minutes**. So with 4 cases, that is not evidence of a lead.
- **Time & sales:** the big gas minutes were one large aggressive buyer.
  - 23:03: **421 of 519 lots traded in one print at 312.70**, all buyer-initiated.
  - 23:06: a 345-lot print at 313.90, then smaller buys up the ladder (footprint, `B_footprint.csv`).
  - The largest print was 2-5× the normal maximum.
- **Heatmap:** the book on the side in the way **thinned during** the move in 4 of 8 cases (asks absorbed or pulled),
  not before. Pulls on that side in the 60 seconds before were no higher than in ordinary minutes.
- **The spread did not change** (crude about 2 ticks, gas about 1 tick).
- **One exception (crude 23:00):** tick-rule buyers were 73% of the volume while the price fell 11 bp. This is the
  snapshot feed's trade-signing problem (R7) or passive buyers absorbing. Footprint labels from a retail feed can be
  wrong.

**Part B in one sentence:** in the seconds data, a big minute is one or a few aggressive sweeps that eat the visible
book on one side. They are seen in OFI/MOFI, delta, the print sizes and the thinning book in the same second as the
price moves. In this sample nothing showed up clearly before. **One evening cannot settle it. NSE has to be recorded.**

## 7. Part C: what the app must record from Monday so this can be done on NSE with depth

### 7a. What the app records today (code read on 9 Oct 2026)

| record | where | what it holds | enough for the anatomy? |
|---|---|---|---|
| Daily 1-second order-flow file | `OrderFlowLive.Recorder` → `noBackupFilesDir/orderflow/<day>.csv.gz`, 30 days kept | per second per instrument: `epoch_sec, time_ist, token, name, role, strike, mid, last, ofi, buy_vol, sell_vol, vol, depth_imb, queue_imb, buy_sell_ratio, oi` | **Partly.** Delta, OFI and volume are there. Missing: see 7b |
| Instruments in it | `OrderFlowLive.relayout` | NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY near futures; ATM±2 CE/PE of **the focus index only** (the charted one, default BANKNIFTY), recentred at most every 5 min; capped at 22 | Partly. NIFTY options are missing unless charted. The recorder **drops MCX futures** (`role != FUTURE \|\| name in INDICES`) |
| Shadow log | `FlowGate` / `FlowShadow` → `orderflow/shadow.log` | at each strategy signal: the flow read (10/60/300 s OFI, CVD, depth), the traded option's 5-level book, trap flags; `M\|` 15/30-min moves; `R\|` results | Only at signals. Not around big candles |
| Market recorder | `MarketRecord` (since 6 Oct) | per minute: index level `S`, futures `F`/`B` (last, volume, OI, basis), best bid/ask and totals `D` (ATM±2), chain `C` every 5 min, news `N`, events `E` | Minute level only. No 5-level book, no per-second data |
| In memory | `OrderFlow.Tracker` | the last 30 minutes of 1-second `Bar`s per instrument, **including** `qDelta`, `pdepth`, `pullBid`, `pullAsk`, `updates`, `stale`, `medLevel`, `ageMs` | The ring buffer the event recorder needs already exists |

Two practical gaps:
- The flow stream only runs while `KiteStream` runs. It "never keeps the stream awake by itself". A day without the
  app open has holes, and those must be marked (`G` lines).
- The 1-second file does not write the index itself. The cash index's per-second print is needed to see the
  "futures lead the index by seconds" step found in section 3a.

### 7b. Fields to add to the daily 1-second line (cheap; the values already exist in `Bar` / `Quote`)

`bid, bid_qty, ask, ask_qty` (best level), `bid5_qty, ask5_qty` (sums of 5 levels), `pull_bid, pull_ask`, `q_delta`
(quote-rule split), `prints` (packets with a volume change), `max_print` (largest volume step in the second),
`updates, stale`, `trade_age_ms`, `high, low` (of trades in the second), and an `INDEX` role row per second for NIFTY
and BANKNIFTY spot from the index tick.

This is R10's `Q` record, section 4.1.

### 7c. The event recorder ("big candle snapshot")

- **Trigger:**
  - Fires when the closing 1-minute candle of the NIFTY or BANKNIFTY near **future** has |return| ≥ **N × normal**.
  - **normal** = this study's per-minute scale: the mean |1-minute move| at that minute of day. Ship it as a
    375-value table per index, from `sigma_tod`, scaled each morning by today's VIX ÷ the table's VIX.
  - **N = 4.7** is a top-1% candle, about 2 per index per day. Use N = 2.8 (top 5%) only if storage allows.
  - Merge triggers less than 10 minutes apart.
  - **Controls:** also fire on **2 random minutes per index per day** that had no top-5% minute in the 30 minutes
    before or the 10 after. Without ordinary moments to compare against, the anatomy cannot be read.
- **What to save:** a file `orderflow/events/<day>_<index>_<hhmm>_<kind>.csv.gz`, with `kind` = big_up / big_down /
  control. It covers **5 minutes before and 5 minutes after**: 300 seconds from the ring buffer at trigger time, plus 300
  seconds written when the after-window closes.
- **Instruments:**
  - the index future;
  - the cash index;
  - ATM±2 CE/PE of **that** index (follow both indices' options while recording, or at least ATM±1);
  - the other index's future;
  - for BANKNIFTY, the top 3 bank heavyweights if the 22-instrument cap allows.

Fields per second per instrument (`S` lines):

| group | fields |
|---|---|
| keys | `event_id, epoch_ms, sec_rel` (seconds from the trigger candle's start), `token, name, role, strike, expiry` |
| price | `mid, last, high, low, bid, ask, spread` |
| trades (time & sales, delta) | `vol, buy_vol, sell_vol` (tick rule), `q_buy_vol, q_sell_vol` (quote rule), `prints, max_print`, `trade_age_ms` |
| order flow | `ofi` (best level), `mofi` (5 levels), `queue_imb, depth_imb, pdepth` (rested, walls capped), `pull_bid, pull_ask`, `tbq, tsq` (record, never trust for direction, R8) |
| book (heatmap) | 5 bid prices, 5 bid sizes, 5 bid order counts, the same for asks (last packet of the second) |
| context | `oi`, `oi_change`; for options `iv` if computed; feed health `updates, stale, gap_flag` |

Per event, once (`H` line):
- trigger time, trigger candle return and N;
- the index's VIX, VWAP and SD distance;
- IB high and low, the opening type;
- the prior day's POC/VAH/VAL (the r9/r10 rules);
- the minutes since open, the expiry flag, scheduled events today, the app's latest headline time and title (from
  `MarketRecord N`);
- the trap guard's flags.

Optional, per event (`T` lines):
- a per-packet tape for the index future: `epoch_ms, ltp, ltq, volume, side`. This is true time & sales at
  packet resolution, about 5-20 thousand rows per event.

**Size:**
- A 1-second line with the 5-level book is about 400 bytes.
- 600 seconds × about 12 instruments × 400 B = **about 2.9 MB raw, about 0.5 MB gzipped per event**.
- With about 4 big events and 4 controls per day, that is about **4 MB/day, about 120 MB for 30 days**.
- Keep the event files for 12 months (not 30 days). They are the sample.

**Rules:**
- phone time to the millisecond plus the exchange time;
- gaps marked;
- no tokens in files;
- plain market data only (no account data), so the files can be exported with the market recorder's zip;
- record from 09:00 to 15:45.

**What it allows, after 4-6 weeks (about 200 big candles and 200 controls per index):**
- the same lead/coincident table as section 3, with the order-book columns: heatmap depth in the way, pulls, walls,
  print sizes, footprint, OFI, MOFI and delta;
- at 1-second resolution, before and during, against controls;
- with a pre-registered design block and a locked later block, as here.

**Decision rule** (with R10's): the order-flow lead matters only if, before the candle, a feature beats its control
level enough to call the direction of the next 5 minutes **above 2 bp after the phone's measured lag**. On the history
and the MCX evening, nothing does.

## 8. Honest limits

- **No NSE order book, no trade sides, no tick data in history.** Delta, VWAP and volume profile on NSE history are
  proxies from option volume (R9/R10 validated them as fair for levels and poor for flow). Heatmap, time & sales and
  footprint exist only in Part B's 34 minutes.
- **The futures window is short.** Real futures volume and OI cover 43-47 sessions, all in the holdout, so futures
  rows are information, not tests.
- **The OI timing is coarse.** Option OI is a 3-minute snapshot. The one pre-move OI lean (put minus call writing)
  could partly be a timestamp effect, and it failed on BANKNIFTY in the holdout.
- **Clustering inflates every "before" number.** Big candles come in clusters, so part of "realised volatility rose
  before" is the previous big candle. The "after a quiet 15 minutes" columns remove the most obvious part. Lifts fall
  from 2.4 to 2.0 for realised volatility and stay above 3 for the 30-minute range.
- **The legs' signed "before" values are by construction.** A leg starts at the extreme, so the minutes before it moved
  the other way. Only unsigned features are read for legs.
- **News.** h33's headline timestamps were deleted from the scratchpad, and Moneycontrol now refuses the sitemap
  download (HTTP 403). The news part rests on the scheduled-event calendar (h28) and on h33's published finding.
- **Multiple testing.** 2,900 design tests. Labels need BH q ≤ 0.05, |effect| ≥ 0.10 SD in both indices, and a holdout
  check. Effects of 0.1-0.4 SD are real but small: they shift odds, they do not call moves.
- **Not a strategy.** No P&L was computed. MARKET_HOW and h29/h32/h33 already showed that the 1-minute derivative lead,
  volatility clustering and shock-following do not pay an option buyer after costs.

## 9. Reproduce

```bash
python3 -I research/hunt/r11/build.py BANKNIFTY NIFTY   # panels (about 2 min)
python3 -I research/hunt/r11/anatomy.py design          # design only
python3 -I research/hunt/r11/anatomy.py hold            # holdout, once (refuses if HOLD_READ exists)
python3 -I research/hunt/r11/report.py                  # labels.csv, table.md
python3 -I research/hunt/r11/extras.py                  # census, stories, fakes
python3 -I research/hunt/r11/partB.py                   # MCX 1-second anatomy
```
