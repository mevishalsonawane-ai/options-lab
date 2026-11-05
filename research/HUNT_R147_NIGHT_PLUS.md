# R147: improving Night (R3): more indices, every gap studied, eight filters

Written 5 Nov 2026 for Boss:
- "Improve Night first, add more indices, check each and every gap up and gap down, with its yesterday data, and find
  out how could we improve it."
- "We have all data in google drive."
- "Check data only from nov 2024."

**Files:**
- Pre-registration: `research/hunt/r147_night_plus/PREREG.md`, commit `98f961d`, pushed before any result.
- Code and 17 tests: `research/hunt/r147_night_plus/`.
- Design results: committed in `80f198c` before the holdout.
- Holdout: read once (`results/HOLDOUT_READ.flag`).
- Boss's "since Nov 2024" view: `results/since_nov2024.json`. It came after the holdout read and is information only
  (NOTES R1).
- Per-night gap atlas: `results/atlas_<INDEX>.csv`. Index facts and Night's side only; no prices or P&L.
- 16 experiment entries: `research/experiments/entries/r147-*.yaml`.

**Research only: nothing in the app changes.**

## Short answer for Boss

**1. Add FINNIFTY to Night.** It is the one new index that passed every pre-registered test, on the design years and
on the locked holdout.

- **Design 2022-24:** 134 trades, +Rs 315 a trade, positive in all 3 years, random-side twin p 0.001.
- **Holdout Jan 2025 to Oct 2026:** 105 trades, +Rs 367 a trade (Rs 286 at 1.5x spread), twin p 0.002.
- **Caveat: it has been weak in 2026.** It lost Rs 18k Jan to Oct 2026, and Rs 17k in Sep 2026 alone.
- **Since Nov 2024:** 113 trades, +Rs 419 a trade, 12 of 21 months positive.

**2. SENSEX is the strongest of all since Nov 2024, but it needs your call on which rule.**

- **Night's exact rule on SENSEX (with the OI check):**
  - Since Nov 2024: 129 trades, +Rs 733 a trade, 20 of 24 months positive, twin p < 0.001.
  - Holdout: 120 trades, +Rs 572 a trade.
  - It failed the design test only for lack of data: SENSEX options start in May 2023, so there were only 35 design
    trades. On paper it would be **your exception** ("failed gates").
- **The same rule WITHOUT the OI check passed both tests:**
  - design 134 trades, +Rs 123;
  - holdout 147 trades, +Rs 348;
  - since Nov 2024, 160 trades, +Rs 470.
- **One bad month:** SENSEX lost Rs 37k in Apr 2026, its worst.

**3. Do NOT add MIDCPNIFTY.** It looked good in design (+Rs 330 a trade, 80 trades) but lost in the holdout: -Rs 169
a trade on 154 trades, and -Rs 22k in 2026.

**4. None of the 8 filters helps.** Most made Night worse, because they skip Night's BEST nights.

- **"Skip weekends / holidays" was the worst idea.**
  - Since Nov 2024, Night's weekend nights averaged +Rs 1,027 a trade.
  - Skipping them would have cost Rs 99,645.
  - Weekends carry the biggest gaps, and Night catches big gaps on the right side about 2-3 times in 3.
- **"Skip when India VIX >= 18"** helped in the holdout (+Rs 25.7k on 35 skipped trades, p 0.045), but it cost
  Rs 46.5k in 2022-24. It is not reliable.
- **Last hour, Hang Seng, extreme close, expensive straddle, event nights and big days** all failed in both periods.

**5. Keep the 1-ITM strike.**
- 2-ITM gave +Rs 34-46 a trade more, but with a bigger drawdown, and it missed the holdout test (p 0.075).
- ATM was worse (-Rs 36 to -40 a trade).

**6. Night on the two indices it trades today (since Nov 2024):**
- NIFTY: 149 trades, +Rs 262 a trade, worst month -Rs 19k (Aug 2025), 13 of 24 months positive.
- BANKNIFTY: 188 trades, +Rs 645 a trade, worst month -Rs 21k (Apr 2026), 16 of 24 months positive, twin p < 0.001.

**What I would change in the app (NOTES, for your decision; nothing is changed here):**
1. **Add FINNIFTY to the Night arm on paper.** It passed the research tests, so paper is allowed straight away
   (logged, one tap off). Live: shadow first, because it adds new live trades.
2. **SENSEX: your choice.**
   - (a) Add it on paper with Night's exact rule as "failed gates, Boss's exception". It has the best record since
     Nov 2024.
   - (b) Add it with the no-OI version, which passed.
3. **Do not add weekend, VIX, event or other skips** to Night.

## Every gap since Nov 2024: what the day before looked like

The gap is measured from 15:19 to the next 09:15 open. The full per-night lists are `results/atlas_<INDEX>.csv`.

**NIFTY, 483 nights:**

| gap | nights | day's move | close location | India VIX | VIX change | weekend nights | S&P 500 overnight | Night right / wrong / no trade |
|---|---|---|---|---|---|---|---|---|
| big down (<= -0.5%) | 46 | -0.22% | 0.39 | 16.1 | +0.7% | 41% | -0.70% | 11 / 6 / 29 |
| down | 103 | -0.01% | 0.45 | 13.8 | -0.7% | 17% | -0.29% | 22 / 11 / 70 |
| flat | 126 | +0.01% | 0.49 | 13.0 | -0.6% | 22% | +0.03% | 15 / 20 / 91 |
| up | 159 | +0.01% | 0.49 | 13.9 | +0.3% | 23% | +0.29% | 31 / 26 / 102 |
| big up (>= +0.5%) | 49 | +0.01% | 0.59 | 16.4 | +1.6% | 35% | +0.81% | 8 / 3 / 38 |

**BANKNIFTY, 483 nights:**

| gap | nights | close location | VIX | weekend | S&P overnight | Night right / wrong / none |
|---|---|---|---|---|---|---|
| big down | 53 | 0.40 | 16.1 | 42% | -0.70% | 21 / 8 / 24 |
| big up | 66 | 0.60 | 15.8 | 29% | +0.69% | 22 / 7 / 37 |

FINNIFTY, MIDCPNIFTY and SENSEX show the same pattern (`results/since_nov2024.json`).

**What the gaps show:**
- **Big gaps (about 1 night in 5) come with high VIX (about 16 vs 13), more often after a weekend (29-44% vs about
  20%), and with a big US move overnight (about ±0.7-0.8% on the S&P 500).**
- **The day before hints at the direction, a little.** Close location is 0.39-0.43 before big gap downs and 0.57-0.63
  before big gap ups.
- **Night is right on big gaps about 2.5-3 times as often as it is wrong:** 11/6 and 8/3 on NIFTY, 21/8 and 22/7 on
  BANKNIFTY, 14/5 and 7/2 on SENSEX. On small and flat gaps it is close to a coin flip. **Night's profit comes from
  the big gaps.**
- **Winning and losing Night nights look the same the day before.**
  - VIX: 13.6 vs 13.8 (NIFTY);
  - weekend share: 29% vs 35%;
  - close location and move: similar.
  - The difference is the overnight gap itself: +35 bps our way on wins vs -21 bps against on losses.
  - No filter at the close can separate them. This is why every filter failed, and it matches R146.
- **Before big gap UPS, India VIX had risen on the day (+0.9% to +1.8%) and the Hang Seng had often fallen.** This is
  descriptive only and was not tested; it would need its own pre-registration.

## How it was tested

- **Data:** the Drive lake (`gdrive:IraAlgo-data-lake`), Aug 2021 to 16 Oct 2026:
  - index and India VIX minutes;
  - real option minutes with OI for all 5 indices: NIFTY, BANKNIFTY, FINNIFTY (from Aug 2021), MIDCPNIFTY (from
    Feb 2022), SENSEX (from May 2023);
  - the 59 NIFTY 50 / NIFTY BANK stocks for breadth.
- **Night rebuilt exactly as the app runs it**, on every index:
  - the OI build-up matches the app's own 60-session seed to within 0.0000005 for NIFTY and BANKNIFTY;
  - 1-ITM nearest expiry (BANKNIFTY monthly), no expiry day, never held into expiry;
  - bought at the 15:20 open, sold at the 09:16 open, 1 lot at the lot in force.
- **Costs:**
  - app fills and charges (BSE for SENSEX);
  - real half-spreads on both legs: NIFTY / BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20%;
  - every result also at 1.5x the spread.
- **Data hygiene:** other indices' rows mixed into the files were dropped (R47's defect), and stray option prints
  were guarded (R146).
- **Periods:**
  - design 2022, 2023, 2024 (each year must mostly be positive);
  - locked holdout 1 Jan 2025 to 16 Oct 2026, read once;
  - the "since Nov 2024" view, asked for after the holdout, is information only.
- **Tests per candidate:**
  - random-side twins for the indices;
  - random-skip twins for the filters;
  - sign-flip tests for the strikes;
  - BH over the 6 index tests and over the 10 filter / strike tests.

## Results tables

**New indices** (1 lot, net after all costs, Rs a trade; twin p in brackets):

| index, rule | design 2022-24 | holdout 2025-26 | since Nov 2024 | passed both |
|---|---|---|---|---|
| FINNIFTY, Night exact | 134 trades, +315 (0.001) | 105, +367 (0.002) | 113, +419 | **yes** |
| FINNIFTY, no OI | 181, +110 (0.005) | 206, -337 (0.13) | 223, -231 | no |
| MIDCPNIFTY, Night exact | 80, +330 (0.042) | 154, -169 (0.21) | 167, -24 | no |
| MIDCPNIFTY, no OI | 122, +261 (0.029) | 243, -366 (0.42) | 261, -265 | no |
| SENSEX, Night exact | 35, +871 (too few trades) | 120, +572 (0.001) | 129, +733 | no (design sample) |
| SENSEX, no OI | 134, +123 (0.008) | 147, +348 (0.003) | 160, +470 | **yes** |
| *NIFTY, Night (as now)* | *247, +200* | *137, +202* | *149, +262* | *baseline* |
| *BANKNIFTY, Night (as now)* | *256, +296* | *173, +557* | *188, +645* | *baseline* |

**Filters on Night** (NIFTY + BANKNIFTY together; rupees gained (+) or lost (-) vs Night as it is):

| filter | design 2022-24 | holdout 2025-26 | since Nov 2024 | passed |
|---|---|---|---|---|
| F1 skip weekend / holiday nights | -116,415 (170 skipped) | -111,256 (90) | -99,645 | no |
| F2 skip nights before FOMC / CPI / RBI / Budget | -9,573 | +2,422 | -15,418 | no |
| F3 skip after a 1.5%+ day | -49,337 | +1,080 | +5,249 | no |
| F4 skip if the last hour went against | -70,691 | -36,055 | -69,398 | no |
| F5 skip if the Hang Seng disagrees | -43,194 | -39,911 | -64,922 | no |
| F6 skip if India VIX >= 18 | -46,540 | +25,748 (p 0.045) | +25,748 | no (design) |
| F7 only extreme closes (0.8 / 0.2) | -28,526 | -34,650 | -56,460 | no |
| F8 skip expensive straddles | -128,353 | -2,065 | -41,554 | no |
| K1 ATM strike | -19,894 | -11,689 | -12,125 | no |
| K2 2-ITM strike | +17,101 (p 0.008; bigger drawdown) | +10,505 (p 0.075) | +15,474 | no |

## Honesty notes

- **Prior reads.** Night itself on NIFTY and BANKNIFTY was read in these years by X2, R27, R144c and R146, so their
  "as now" rows are not new evidence. Night's rule had never been run on FINNIFTY, MIDCPNIFTY or SENSEX before.
- **The "since Nov 2024" view** overlaps the design (Nov-Dec 2024) and the already-read holdout. It shows the current
  market regime; it is not a third test.
- **SENSEX with Night's exact rule failed only on its pre-registered sample size**, not on results. Adding it is
  allowed only as Boss's exception.
- **Lots:** the lots in force on each date. FINNIFTY is 60 from Dec 2025; the MIDCPNIFTY / SENSEX tables may miss
  the Dec 2025 revision.
- **FINNIFTY's spread** (0.42%) is the widest of the five. Live fills there need the thin-option gate.
- **Multiple testing.** The overnight family now holds over 10,000 logged tries. R147's own BH (6 and 10 tests) is the
  gate used; against the whole family nothing is significant.
- **No raw data in git.** The lake stays in `~/.cache/iraalgo/lake`.
