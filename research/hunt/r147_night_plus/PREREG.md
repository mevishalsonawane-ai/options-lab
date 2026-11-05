# R147 pre-registration: improve Night (R3), add indices, and study every gap with the day before it

Written 5 Nov 2026, BEFORE any R147 result was computed. The lake files were being copied when this was written; no
R147 feature, gap or P&L existed yet. Boss (5 Nov): "Improve Night first, add more indices, check each and every gap
up and gap down, with its yesterday data, and find out how could we improve it." Boss then added: "We have all data
in google drive."

Research only; nothing under `android/` changes. Starting point: R146 (`HUNT_R146_GAP_BTST.md`):
- the close predicts the gap's direction weakly and its size hardly at all;
- Night (R3) was the best overnight rule.

## 1. Data (the Drive lake; nothing raw goes into git)

`gdrive:IraAlgo-data-lake`, copied with rclone (Ubuntu package) to `~/.cache/iraalgo/lake` on 5 Nov 2026:
- `index_1m_<UND>_<Y>`: minutes for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and INDIAVIX;
- `opt_<UND>_<WEEK|MONTH>_<Y>`: rolling ATM-10..+10 option minutes with OI;
- `equity_1m`: the 59 stocks of NIFTY 50 + NIFTY BANK, for breadth.

The lake runs to mid-Oct 2026.

**Data rules** (from R27 / R47, fixed now):
- An option row whose `spot` is more than 3% from the index's own close that minute is another index's row (R47's
  defect in FINNIFTY / MIDCPNIFTY / SENSEX / BANKNIFTY MONTH). It is dropped. So is a row whose strike is more than 12
  steps from spot.
- Expiry days come from the data: R25 / R27's rule (ATM time value at 15:15 under 0.6% of spot, and the next
  session's more than 2x).
- A duplicated minute keeps its first row; nothing is filled.
- **The fill guard** is R146 NOTES A2: a print is stray only if it is more than 20% from its own close AND from the
  next bar's open.

## 2. Night (R3), exactly as the app, on every index

This is R27's builder, generalised to 5 indices:
- **Decision at 15:19:**
  - close location >= 0.6, day up, breadth >= 50% gives the call (mirror for the put);
  - breadth is the 59-stock share above the previous close at 15:19, needing at least 40 priced;
  - and the index's own day-end OI build-up z (60 sessions, at least 20) agrees: side x z >= 0.5.
- **Contract:**
  - 1-ITM of the index's nearest series (the WEEK file; BANKNIFTY the MONTH file, as the app);
  - never on an expiry day of the index, never held into expiry.
- **Trade:** buy at the 15:20-15:22 open, sell at the next session's 09:16 open, 1 lot.
- **Lots:** the dated tables (R27 for NIFTY / BANKNIFTY; `research/obuy/config.py` for FINNIFTY / MIDCPNIFTY /
  SENSEX).
- **Costs:**
  - app fills (+-5 bps);
  - app charges (BSE rates for SENSEX);
  - h28 half-spreads on both legs: NIFTY / BANKNIFTY 0.16%, FINNIFTY 0.42%, MIDCPNIFTY 0.21%, SENSEX 0.20%;
  - stress at 1.5x the spread.

## 3. Periods

| block | entry days | use |
|---|---|---|
| WARM | lake start to 31 Dec 2021 | OI z history only (no trades counted) |
| DESIGN | 2022, 2023, 2024 | each calendar year is a walk-forward fold; gates read these |
| HOLDOUT | 1 Jan 2025 to lake end | read ONCE (`results/HOLDOUT_READ.flag`) |

**Prior reads (disclosed; no block is unseen for Night itself):**
- X2, R27, R144c and R146 read Night (R3) on NIFTY and BANKNIFTY across these years.
- R144c read Night with VIX-change / trend / previous-VIX skips. Those are different from F1-F8 below.
- R47 read "overnight late-day momentum" on BANKNIFTY / FINNIFTY (holdout Nov 2025 to Oct 2026).
- Night's own rule has never been run on FINNIFTY, MIDCPNIFTY or SENSEX.

## 4. The gap atlas (descriptive; every night, every index, every period)

For every night d -> next session:
- **The gap:** g = ln(next 09:15 open / 15:19 close). Classes:
  - big down (<= -0.5%);
  - down (-0.5% to -0.1%);
  - flat (abs < 0.1%);
  - up (0.1% to 0.5%);
  - big up (>= 0.5%).
- **Yesterday's profile:**
  - the day's move, close location, the last 30 and 60 minutes, today's own gap, the day's range;
  - India VIX and its change;
  - breadth (59 stocks), the OI build-up z;
  - weekday and weekend, sessions to expiry;
  - next-session events (r98 calendar);
  - the Hang Seng's day;
  - and, labelled "arrives overnight", the S&P 500's US session.
- **Night's call** (call / put / none, and why) and its net if traded.

Tables per index and period:
- the profile by gap class;
- Night's catches (right side / wrong side / missed) by gap class;
- winning vs losing Night nights.

The per-night atlas (index-derived columns and Night's side; no option prices or P&L) is written to
`results/atlas_<UND>.csv`.

The atlas chooses nothing. Every candidate below is fixed here. Any NEW idea the atlas suggests goes to a later
pre-registration and a forward (shadow) test.

## 5. Candidates (fixed now; no threshold is fitted)

**More indices (Night's exact rule on its own data):**

| id | |
|---|---|
| X1 | FINNIFTY |
| X2 | MIDCPNIFTY |
| X3 | SENSEX |
| X1b / X2b / X3b | the same without the OI check (R17 strong close only), for indices whose OI history is thin |

**Filters on Night (risk-lowering skips).** Each is tested on NIFTY + BANKNIFTY pooled (the arm as it is), and also
reported on every index:

| id | skip the night when |
|---|---|
| F1 | the next session is more than 1 calendar day away (weekend / holiday: extra decay) |
| F2 | the next session is hit by FOMC / US CPI overnight, or is an RBI / Budget / election-result day |
| F3 | abs(day move) >= 1.5% |
| F4 | the last 60 minutes (14:19 -> 15:19) moved against the side |
| F5 | the Hang Seng's day disagrees with the side |
| F6 | India VIX >= 18 at 15:19 |
| F7 | the close location is not extreme (call needs >= 0.8, put <= 0.2) |
| F8 | the ATM straddle / spot at 15:19 is >= 1.2x its median of the previous 60 sessions |

**Contract (Night's nights):** K1 ATM instead of 1-ITM; K2 2-ITM.

## 6. Gates

**A new index (X1-X3b)**, on DESIGN:
- net per trade > 0, also at 1.5x spread;
- positive in at least 2 of the 3 years;
- random-side twin p <= 0.05 (2,000 twins);
- BH q <= 0.10 over the 6;
- at least 60 trades.

Then on HOLDOUT: net > 0 at 1.5x spread, twin p <= 0.05, at least 30 trades.

**A filter or contract (F1-F8, K1-K2)**, paired against Night on the same NIFTY + BANKNIFTY nights, on DESIGN:
- the total net difference > 0, and > 0 in at least 2 of the 3 years;
- for a filter, a random-skip twin p <= 0.05 (2,000 twins skipping the same number of Night's trades at random);
  for a contract, a paired sign-flip p <= 0.05;
- BH q <= 0.10 over the 10;
- max drawdown no worse than Night's.

Then on HOLDOUT: difference > 0 and p <= 0.05.

If nothing passes DESIGN, the holdout is reported as information only.

## 7. Verdict rules (CLAUDE.md)

- A filter passing both is a PAPER candidate: a risk-lowering switch, logged in Autopilot, one-tap undo. Live, it
  stays in shadow first.
- A new index passing both is a PAPER candidate for the Night arm. Live, it starts in shadow, since it adds new live
  trades.
- Everything else stays a note. Boss may still choose an exception; it is then labelled "failed gates, Boss's
  exception".
- Nothing in the app changes in R147 itself.
- **Signal source:** the index (close, breadth, VIX, Hang Seng, events) with the option chain's OI as context. The
  option is bought.

## 8. Logging and tests

- Every candidate gets an experiment entry (`r147-*.yaml`), failures included.
- Pytest tests cover:
  - the generalised builder's look-ahead (nothing after 15:19 of d is read for the decision);
  - the foreign-row filter;
  - expiry detection;
  - each filter;
  - the paired / skip twins;
  - the holdout flag.
