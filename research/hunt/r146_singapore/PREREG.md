# R146 pre-registration: does the Singapore market tell us anything about the Indian index day?

Written 1 Nov 2026, before any R146 number. Only the timestamps and coverage of the Singapore data were looked at, to
fix the cut-off minute. Research only.

Boss, 1 Nov: *"Check what is the effect of Singapore market on indian market, as it closes before indian market
starts"*, then *"yes run R146 for Singapore market"*.

## A correction first, and what is new

- **Singapore does not close before India opens.**
  - SGX trades 09:00-17:00 Singapore time = **06:30-14:30 IST**, so it is mid-session at India's 09:15 open.
  - What is known before 09:15 IST is Singapore's **overnight gap** (yesterday's close to its 06:30 IST open) and its
    **early-morning move** (06:30 to 08:30 IST, the last full hourly bar).
  - SGX Nifty (the NIFTY futures once traded in Singapore) moved to GIFT City in July 2023 and is now GIFT Nifty. H27
    tested GIFT Nifty and other overseas cues: they set the opening gap, but none of 234 option variants passed.
- **H27 deliberately left Singapore's own index (the STI) out** ("redundant"). R146 tests it.

## Data

- **Singapore Straits Times Index (^STI)** from Yahoo's chart API (H27's cookie + crumb method), downloaded 1 Nov 2026:
  - daily bars 2 Jan 2015 - 30 Oct 2026;
  - hourly bars 6 Dec 2023 - 30 Oct 2026 (Yahoo gives about 730 sessions).
  - The raw files stay outside git; they are parsed with `python -I`.
- **US control:** S&P 500 (^GSPC) daily, the same source. Its session ends before India's day starts.
- **India:** NIFTY and BANKNIFTY minutes from the Drive lake (2021+); the daily file (2015+) for the opening gap.

## Signals (all known before 09:15 IST on India's day D)

- **G (overnight gap):** STI's day-D open / its previous close - 1 (daily).
- **MO (morning move):** the close of STI's 10:00-11:00 Singapore-time hourly bar (08:30 IST) / STI's day-D open - 1
  (hourly).
- **PRE (everything before India's open):** that 08:30 IST close / STI's previous close - 1 (hourly).
- **US (control):** the S&P 500's last session before day D (its close / the close before - 1).

Days are matched by date. A day when either market is shut is left out.

## Targets (NIFTY and BANKNIFTY)

- **T0, the opening gap:** day D's 09:15 open / the previous close - 1 (daily file).
- **T1, the first hour:** the close of the 09:20 minute to the close of the 10:15 minute (lake minutes).
- **T2, the rest of the day:** the close of the 09:20 minute to the close of the 15:10 minute.

## Tests (one BH family of 26, design period)

1. Correlation of each signal (G, MO, PRE) with each target (T0, T1, T2), for both indices: 18 tests.
2. The same for G and PRE against T1 and T2, **after removing the US move** (partial correlation): 8 tests.

- **p:** a moving-block bootstrap (block 10 days, 2,000 draws), two-sided.
- **Gate for an index-level finding:** BH q <= 0.10 in design, the same sign in both halves of design, and the same
  sign with |r| at least half its design value in the holdout (read once).

## Periods

- **Design:**
  - T0 with G: 2 Jan 2015 - 30 Sep 2025;
  - T1 / T2 with G: Aug 2021 - 30 Sep 2025 (the lake's minutes start in 2021);
  - MO / PRE: 6 Dec 2023 - 30 Sep 2025.
- **Holdout, 1 Oct 2025 - 30 Oct 2026, read once.** H27 also used 1 Oct 2025 on as its holdout for other cues, never
  for the STI.

## A trade test, only if an index-level finding passes design

- **Trigger:** a T1 or T2 test with q <= 0.10 and the same sign in both design halves.
- **The trade:** for each such test, buy the ATM option at the 09:21 open on holdout days when the signal is in the top
  or bottom 30% of its design values. Direction: the signal's sign times the sign of the correlation. Exit at 10:15
  (T1) or 15:10 (T2).
- **Pricing:** R131's option rule, dated charges and liquidity-aware fills; 1 lot.
- **Gates:**
  - average net > 0 at 1x and 2x slippage on >= 50 trades;
  - better than 200 random-side twins on the same days (p < 0.05).
- If nothing passes design, there is no trade test, and the write-up says so.

## Outputs

- `fetch.sh` (download), `r146.py` (parse with `python -I`, tests), `test_r146.py`, `results/r146.json`.
- `research/HUNT_R146_SINGAPORE.md`.
- Entries `research/experiments/entries/r146-*.yaml`.
