# R118: every option on every expiry day: the basics, the patterns, and the maths

Written 27 Oct 2026, for Boss: *"Deep analysis and deep drive on each and every option on expiry date for all the data we
have, look into each and every single parameters, find a pattern, dont directly write strategy, but analysis all the
basic"*, and *"use all the math you can, also try quant mathematics"*.

- **Pre-registration:** `research/hunt/r118_expiry_atlas/PREREG.md`, commit `8aa2d64`, pushed before any number.
- **Code, tests and every table:** `research/hunt/r118_expiry_atlas/`.
  - `results/atlas.md`: sections A-K, the basics.
  - `results/quant.md`: sections L1-L5, the maths.
- **Experiment entry:** `research/experiments/entries/r118-expiry-atlas.yaml` (descriptive, not a test).
- **No strategy.** No entry, no exit, no P&L. Nothing in the app changes.
- **Signal source:** none. The atlas reads the index's minutes and the options' own prints.

## The data

- **911 expiry days, 2021 to 19 Oct 2026:** NIFTY 273, BANKNIFTY 195, FINNIFTY 167, MIDCPNIFTY 100, SENSEX 176. 263 of
  them are monthly expiries.
- **Every stored option:** the lake keeps ATM±10 strikes, calls and puts, about 48-54 series a day. Each has 1-minute
  open / high / low / close, volume, OI and the vendor's IV.
- **859,800 option-marks:** every option that traded in the 3 minutes before each 15-minute mark, 09:30 to 15:15.
- **Normal days for comparison:** 5,503 normal sessions of the same indices and years, from index minutes.
- **The stability rule:** each pattern was checked in 2021-23 (414 days) and 2024-26 (497 days) separately. It is
  called **stable** only if it points the same way in both.
- With this many tables, some numbers will look striking by chance. The stable ones come first below.

## The answer first: what an expiry day looks like

### Stable patterns (same direction in 2021-23 and 2024-26)

1. **The straddle is usually bigger than the move that comes.**
   - From 12:00, the index's move to the close beat the 12:00 ATM straddle on only **35% of 893 days** (2021-23 37%,
     2024-26 34%). A bell-curve move of the size the straddle prices would beat it 42.5% of the time.
   - From 09:30 it is 38%. Every start time from 09:30 to 14:30 gives 35-41%.
2. **But the rare big move is bigger than a bell curve allows (fat tails).**
   - Moves of more than 3 straddles came on **2.4-4.0% of days** in each half. A bell curve says 1.7%.
   - The close move's kurtosis is 4-13 in each half; a bell curve's is 3.
   - So the straddle is dear on the typical day and roughly fair, or cheap, on the rare big day.
   - Measured by the spread of the close move, each index's straddle prices about the right total variance (sd of the
     standardised move 0.94-1.16). The variance is just packed into a few days.
3. **Implied variance beats realised early, and loses late.**
   - From 09:30, the straddle's implied variance to the close is a median **1.20x** the variance that came (1.25 / 1.18
     in the two halves). Implied was higher on 66% of 890 days.
   - From 15:00 it flips: implied is **0.61x** realised (0.91 / 0.45). The last 30 minutes move more than the 15:00
     straddle prices.
4. **The last 30 minutes are livelier on expiry days.**
   - The index's last-30-minute move is larger on expiry days than on normal days, in both halves (+0.012 and +0.023
     points of spot).
   - On expiry days 5.4% of the day's index variance comes 15:00-15:15, against 3.6% on normal days.
   - NIFTY, BANKNIFTY and SENSEX make their day's high or low in the last hour 54-57% of the time, against 45-50% on
     normal days.
5. **On expiry days the index chops: it mean-reverts minute to minute.**
   - Variance ratio VR(30) is **0.76 on expiry days vs 0.84 on normal days** (2021-23: 0.84 vs 0.87; 2024-26: 0.69 vs
     0.81). Lag-1 autocorrelation is −0.03 vs +0.02.
   - VR below 1 means 30-minute moves are smaller than 30 one-minute moves would suggest: up-ticks get taken back.
   - The effect is strongest on SENSEX (0.65 vs 0.82) and MIDCPNIFTY (0.62 vs 0.85).
6. **The straddle melts close to the square root of time until about 14:30, then faster.**
   - Theory: an ATM straddle ≈ 0.8 × spot × volatility × √(time left).
   - Median straddle left, as % of its 09:30 value, against √(time left): 13:30 **57% vs 58%**; 14:30 **39% vs 41%**;
     15:00 **23% vs 29%**; 15:15 **12% vs 20%**.
   - After 15:00 it falls faster than √time in both halves. Before that it tracks √time in 2021-23 and runs 4-7
     points faster in 2024-26.
   - In % of spot (all 911 days): 0.53% at 09:30, 0.34% at 12:30, 0.21% at 14:30, 0.12% at 15:00.
7. **The market prices a far higher chance of a cheap option ending in the money than actually happens.**
   - Black-76 on each option's own price gives the market's (risk-neutral) chance it ends ITM. Against what happened:

     | market's chance | priced (mean) | ended ITM | paid back per Rs 1 at the end |
     |---|---|---|---|
     | 1-2% | 1.5% | 0.13% | Rs 0.10 |
     | 2-5% | 3.2% | 1.1% | Rs 0.20 |
     | 5-10% | 7.2% | 4.1% | Rs 0.45 |
     | 10-20% | 14.4% | 10.6% | Rs 0.70 |
     | 20-30% | 24.7% | 21.0% | Rs 0.80 |
     | 40-50% | 45.0% | 44.1% | Rs 0.90 |

   - Same in both halves, at every time of day, for calls and puts.
   - The cheaper the option, the more it is overpriced against its outcome: the "longshot" premium.
   - These are averages per option held to the end, before charges. They are not a trade.
8. **Puts are dearer than calls at the same distance (skew).**
   - At 11:00-13:00, an OTM put's IV is 3-7% above the call's at the same distance in straddles (1.06 vs 1.02 at
     0.5-1 straddles out; 1.31 vs 1.24 at 2-3).
   - Despite that, calls 1-3 straddles out reached 10x more often than puts from 12:00 (by 2.4 and 1.0 points in the
     two halves). Rallies on expiry run further, measured against what was priced.
9. **Where the OI sits.**
   - At 09:30, the put wall (the stored strike with the most put OI) is a median 0.96 straddles below spot. The call
     wall is 1.30 above. Puts are written closer, in both halves.
   - The put/call OI ratio is below 1 (median 0.84 at 09:30).
   - Near-money OTM OI grows to about 3-4 times its 09:30 level by 15:00, which suggests heavy writing through the day.
     Far-OTM OI barely changes, and ITM OI falls about 11% as positions close.
10. **Where and when the volume is.**
    - By the clock: volume is heaviest late. 14:00-15:15 carries 14-19 points more of the day's volume than
      09:15-10:30. The busiest 15 minutes is 15:00-15:15 (9.3% of the day).
    - The most-traded strike sits about 0.4-0.6 straddles OTM all day; its price falls from about Rs 40 in the morning
      to Rs 6 after 14:45.
    - In units the volume spreads out: 46% at 0.5-3 straddles OTM (both sides), 23% ITM and 17% beyond 3 straddles.
      In rupees, ITM options carry 61% of the turnover.
11. **Options that doubled usually did it late.**
    - Of the OTM options (Rs 1+) that later doubled, 26-31% made their best price after 15:00 (31% / 26% in the two
      halves).
    - Only 6% made it before 11:00.

### What is NOT there (checked, and absent in both halves)

- **No pinning to strikes.**
  - The index's close sits a mean 0.244 strike steps from the nearest strike. 0.25 is what pure chance gives.
  - The 12:00 and 13:00 prices, used as a placebo, sit at the same distance (0.244-0.246).
- **Max pain and the OI walls do not pull the index.**
  - From 09:30 to the close, the index moved toward max pain on 49% of 845 days. The mean pull is +0.016 straddles,
    inside the range a random target gets (−0.083 to +0.096 from 500 shuffles).
  - It flips sign between the halves. The call and put walls are the same: nothing.
- **Nothing at the open changes the day.** The previous-close VIX, the opening gap, the weekday and monthly vs weekly
  change neither:
  - the share of days that beat the straddle (about 35%);
  - nor the share with a 10x option (about 49%, by this atlas's definition below).
  - The weekday differences are mostly which index expires that day. Any cell that looks different is under 30 days in a
    half, or flips between the halves.
- **Expiry days are not wilder all day.** The full-day range is about the same as on normal days (0.95-1.04% median
  in each half). The difference is in the last 30 minutes, and in more chop.

### Seen in one half only (not a pattern yet)

- The square-root-of-time decay before 14:30 holds in 2021-23. In 2024-26 the straddle melts faster.
- Monthly expiries had 10x options more often in 2024-26 (63% vs 49%), but not in 2021-23 (45% vs 44%).
- MIDCPNIFTY's 10x-day rate: 20% of 30 days in 2021-23, 59% of 70 in 2024-26 (a small, early sample).

## The basics, section by section

### A. Data quality (`atlas.md` A)

- **Bad prints:** R25's same-minute clean changed 974 NIFTY bars out of 273 days; SENSEX and FINNIFTY needed the most
  (5,051 and 4,447).
- **The vendor's IV column is unreliable at the edges.** 13-20% of option-marks have an IV of 0 or above 300%. R118 uses
  medians, and computes its own Black-76 IV for the maths section.
- **Deep-ITM prints are often stale:** 13-16% of marks print more than Rs 0.50 below intrinsic value.
- **Gaps in trading:** near-money options trade almost every minute (under 1% of minutes without a trade). Beyond 3
  straddles, 7-15% of minutes have no trade.
- **Put-call parity holds** at the ATM strike to within about 1 basis point of spot, until the last 5 minutes (−1 to −3
  bps). The data is consistent.

### B. Decay (`atlas.md` B)

| time | 10:30 | 11:30 | 12:30 | 13:30 | 14:00 | 14:30 | 15:00 | 15:15 |
|---|---|---|---|---|---|---|---|---|
| straddle left, % of 09:30 (911 days) | 86 | 77 | 68 | 57 | 49 | 39 | 23 | 12 |
| √(time left), % | 91 | 82 | 71 | 58 | 50 | 41 | 29 | 20 |

- MIDCPNIFTY and SENSEX melt fastest (19-22% left at 15:00); FINNIFTY slowest (25%).
- **ITM options** keep about 8% of their price as time value all day (Rs 21 in the morning, Rs 6 after 14:45), within 3
  straddles of the money.

### C / L1 / L2. The move priced against the move that came

| from | days | index beat the straddle by the close | beat 2 straddles | best one-way move > 2 straddles | implied / realised variance (median) |
|---|---|---|---|---|---|
| 09:30 | 890 | 38% | 11% | 19% | 1.20 |
| 12:00 | 893 | 35% | 11% | 18% | 1.10 |
| 14:00 | 891 | 37% | 12% | 20% | 0.96 |
| 14:30 | 894 | 41% | 14% | 22% | 0.91 |
| 15:00 | 888 | 52% | 22% | 36% | 0.61 |

- The day's direction is a coin toss. From 12:00 the index ended higher on 49% of 893 days, with a mean signed move of
  +0.008 straddles.
- **Caution on the variance ratio by index.** 1-minute realised variance overstates the variance that matters for a
  move to the close when the index chops (section 5 above). So the by-index premium is read from the close move
  instead. On that measure all five indices are close to fair in total variance (sd 0.94-1.16).

### D. Volatility (`atlas.md` D)

- **ATM IV through the day** (vendor, median): it dips mid-morning, holds roughly flat to 14:30, then collapses after
  15:00. NIFTY: 21.8% at 09:30, 19.8% at 11:30, 21.0% at 14:30, 15.1% at 15:00, 7.9% at 15:15.
  - The collapse is the same thing as the faster-than-√time decay after 15:00.
- **The smile:** IV rises with distance on both sides, puts more than calls (table in pattern 8). Beyond 3 straddles the
  vendor's IV ratio is about 1.9 for both sides, but that zone is mostly junk prints.

### E. Volume and OI (`atlas.md` E): see patterns 9 and 10.

### F. Pinning (`atlas.md` F): see "What is NOT there".

- The close ended between the 09:30 put and call walls on 61% of 861 days. The 09:30 index was between them on 89%.
  So walls are crossed often.

### G / L3. The index on expiry days vs normal days

| | expiry (907 days) | normal (5,503) |
|---|---|---|
| daily vol (√ of 1-minute realised variance) | 0.64% | 0.63% |
| kurtosis of 1-minute returns | 5.5 | 5.3 |
| jump share of the day's variance (bipower) | 4.3% | 3.8% |
| lag-1 autocorrelation | −0.03 | +0.02 |
| variance ratio VR(15) / VR(30) | 0.86 / 0.76 | 0.93 / 0.84 |
| share of the day's variance 15:00-15:15 | 5.4% | 3.6% |
| share of the day's variance 09:15-09:30 | 9.2% | 12.0% |

- The jump share is higher on expiry days only in 2024-26 (5.2% vs 4.0%); in 2021-23 it is the same (3.3% vs 3.4%).
- 1-minute returns are fat-tailed every day, expiry or not: kurtosis about 5.3-5.5, against 3 for a bell curve.

### H. What happens to an option after a mark (`atlas.md` H)

From the 11:00-12:45 marks (OTM options; outcomes after the mark; "dies" = ends under Rs 0.10):

| premium | marks | reach 2x | reach 5x | reach 10x | dies |
|---|---|---|---|---|---|
| Rs 1-3 | 31,099 | 12% | 4.2% | 2.3% | 88% |
| Rs 3-5 | 16,287 | 16% | 6.1% | 3.1% | 83% |
| Rs 5-10 | 21,081 | 21% | 8.3% | 4.0% | 78% |
| Rs 10-20 | 18,714 | 27% | 9.9% | 3.8% | 72% |
| Rs 20-50 | 19,573 | 33% | 11% | 3.4% | 63% |

- **By distance**, the 10x rate peaks 1-2 straddles out (5.5-6.4%). Nearer options double more often but rarely 10x;
  further ones rarely move at all.
- **From 14:45-15:15** the 10x rate rises to 10-12% for options 0.5-2 straddles out. This is the late-move effect of
  patterns 3, 4 and 11, but these are small rupee prices.
- **Under Rs 1,** a single Rs 0.05 tick is a 5-100% move, so those doubling rates are mostly tick noise.
- **Leverage:**
  - An OTM option's % move per 1% index move ("leverage") is about 170-230 in the morning, 330-380 at 14:00-14:30 and
    470-550 after 14:45, peaking 1-1.5 straddles out.
  - **Delta, theory against measured (L5):** over 5 minutes, near-money options move 83-93% of their Black-76 delta
    before 14:30 (67-76% after 14:45).
    Options 2-3 straddles out move only 43-58%, and beyond 3 straddles 22-33%.
  - Theory overstates how much far-OTM options react. Time decay inside those 5 minutes, the bid-ask bounce, and IV
    falling as the index moves toward the strike all eat into it.

### I. Calls vs puts: see patterns 7 and 8.

- Put premiums are 1-19% higher than calls at the same distance (0.139 vs 0.126 straddles at 1-1.5 out).

### J. Conditions at the open: see "What is NOT there".

- **Definition used here:** a "10x day" means some OTM option priced Rs 1-10 at a mark from 11:00 later reached 10x
  (close or intrinsic value, to 15:29). By that definition it happened on 49% of days.
- R98's narrower definition gave 38% (entries 10:00-15:00, out by 15:20, closes only). The difference is the last 10
  minutes and intrinsic value.

## Limits

- **The stored chain is only ATM±10 strikes.**
  - Max pain and the walls use that chain only, not the full market.
  - An option that drifts out of the band is valued at intrinsic from then on (about 0 if far OTM).
- **"Ended ITM" uses the index's last minute,** not the exchange's settlement price (a weighted last-30-minute average).
  Options within a few points of the strike can differ.
- **Normal days include other indices' expiry days** (for example a BANKNIFTY expiry is a "normal" NIFTY day), so the
  expiry-vs-normal differences are, if anything, understated.
- **Marks are not independent:** options on the same day move together. Where it matters, ranges come from resampling
  whole days.
- **This is description, not a tested edge.** Before any pattern above becomes a rule, it needs:
  - its own pre-registration;
  - a walk-forward test with real charges;
  - random twins;
  - and Benjamini-Hochberg that counts these R118 tables as looks already taken.

## Reproduce

```bash
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m ~/.cache/iraalgo/lake --include "opt_*_WEEK_*.parquet"
rclone copy gdrive:IraAlgo-data-lake/index_1m ~/.cache/iraalgo/lake --include "index_1m_*_202[1-6].parquet"
R98_LAKE=~/.cache/iraalgo/lake R98_OUT=~/.cache/iraalgo/r98 python3 -I research/hunt/r98/r98data.py build
python3 research/hunt/r118_expiry_atlas/atlas.py build
python3 research/hunt/r118_expiry_atlas/report.py && python3 research/hunt/r118_expiry_atlas/quant.py
python3 -m pytest research/hunt/r118_expiry_atlas -q
```
