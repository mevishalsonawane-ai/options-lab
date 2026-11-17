# R190 pre-registration: best strategy hunt (reliability first)

Written 17 Nov 2026, BEFORE any R190 profit or loss was computed. Research only; nothing under `android/` changes.

Boss (owner): *"go through all the studies we have done till now, and all the data we have in the data lake, and find
me very good profitable strategies with good winrate... it should be great and reliable."* No rush; reliability first.

The starting point is two surveys (kept outside git): the internal one (about 4,750 logged studies) and an outside one
(papers and SEBI studies). Their main points:
- The Donchian arms (R72, R51, R52) made all their money from Nov 2024 on and lost on every older period (R143, R144a).
- Night (R3) is the one rule that was positive in two separate eras (R147: 2022-24 and 2025-26).
- Liquidity 15+5 was positive in every period tested, with a 31-39% win rate.
- Win-rate boosters (small targets, breakevens) cut profit by 25-90% (R105C, R106, R179, R186).
- Option selling failed on real prices, and Boss chose not to sell (21 Oct). Everything here BUYS options.

## 1. Data (nothing raw goes into git)

- **Drive lake** `gdrive:IraAlgo-data-lake`, copied with rclone on 17 Nov 2026 to `~/.cache/iraalgo/r190/raw`:
  - `index_1m` (NIFTY from 2017; BANKNIFTY, FINNIFTY, SENSEX, INDIAVIX from 2021; MIDCPNIFTY from 2022), plus the
    16 Nov fill of 20 Oct - 13 Nov 2026;
  - `options_rolling_1m` WEEK files of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX and the BANKNIFTY MONTH files
    (from 2 Aug 2021), plus the 20 Oct - 13 Nov 2026 gap files;
  - `equity_1m` 2019 - 13 Nov 2026 (the 59 stocks Night's breadth reads); `index_daily` (daily closes, INDIAVIX).
- **`dhan-data` branch** of options-lab (Dhan v2, fetched 6 Oct 2026), copied out of git with `git archive` to the
  scratchpad: NIFTY WEEK CALL / PUT 2020 and 2021. It is the same Dhan rolling-option endpoint as the lake (checked on
  3 Aug 2021: 7,875 of 7,875 rows identical). Only its rows before the lake's first option day (2 Aug 2021) are used.
- `r190data.py` builds one flat folder (`~/.cache/iraalgo/r190/lake`) in the lake's own file layout: year files
  linked, the 2026 files joined with their fill parts (first print of a minute kept), and the dhan-data NIFTY rows
  written as `opt_NIFTY_WEEK_2020.parquet` (3 Aug 2020 - 30 Jul 2021). Data files are read with `python3 -I`.

**What exists before Aug 2021 (checked 17 Nov):** only NIFTY options (and the NIFTY index and the 59 stocks).
BANKNIFTY / FINNIFTY options start in Aug 2021, MIDCPNIFTY in 2022, SENSEX in 2023 (dhan-data README, lake STATUS).

## 2. Eras

| era | dates | role |
|---|---|---|
| **A** | 3 Aug 2020 - 30 Jul 2021 | the oldest option data; LOCKED, read ONCE at the end (section 7) |
| (gap) | 2 Aug - 30 Nov 2021 | warm-up only (indicators, OI history); no trade is counted |
| **B** | 1 Dec 2021 - 31 Oct 2024 | first look for the new rules |
| **C** | 1 Nov 2024 - 13 Nov 2026 | the recent regime (weekly-expiry cut, bigger lots), to the latest data |

**Honest disclosure about era A (found while checking the loaders, before any R190 number):** era A is NOT unseen
data. The 8 Oct 2026 "Rs 5,000 a day" hunt (h22-h47, about 55 log entries, `research/HUNT_FINAL.md`) used Dhan option
minutes from Aug 2020 as its pre-holdout design data. In particular h31 read overnight close-following rules on NIFTY
2020-21 (its "R17 all agree" family made +43k in 2020 and +67k in 2021), and Night (R3) descends from that family. So:
- for **Night**, era A is in-sample for its ancestors: it is a consistency check, not out-of-sample proof;
- for the **new rules (3, 4, 6)**, era A is data nobody has run THESE rules on, but it is not virgin data;
- no R-numbered study (R1-R189) used option prices before 2 Aug 2021, as far as the log shows.
There is no option history older than Aug 2020 anywhere we can reach. The only truly unseen days are 20 Oct - 13 Nov
2026 (18 sessions), too few on their own; they are the tail of era C. Era A is still locked and read once here.

Era A can only test NIFTY rules. Liquidity (BANKNIFTY, FINNIFTY, MIDCPNIFTY) and R72 (needs all five indices) have no
data there, so **they cannot meet the RELIABLE gate** (section 6). That is a data limit, stated as "untestable in era A",
not as a failure of the rule.

## 3. Candidates (all fixed now; signal on the underlying, buy the option)

Signal source of every rule: the index itself (NIFTY / BANKNIFTY spot minutes). Option-chain OI is context only
(Night's OI check, as built). Option prices are only traded. No rule reads an option's own chart.

### 1. Night (R3), exactly as built
R147's builder (`research/hunt/r147_night_plus/data.py`, R27's exact rebuild of the app's `NightRules`), unchanged:
- decision at 15:19: close location >= 0.6, day up, 59-stock breadth >= 50% gives a call (mirror for a put), and the
  index's day-end OI build-up z (60 sessions, at least 20) agrees (side x z >= 0.5);
- 1-ITM of NIFTY's nearest weekly (WEEK file) and BANKNIFTY's nearest monthly (MONTH file); never entered on an expiry
  day, never held into one;
- buy at the 15:20-15:22 open, sell at the next session's 09:16 open, 1 lot.

| id | what |
|---|---|
| **N1B** | the Night book as the app runs it: NIFTY + BANKNIFTY, 1 lot each (era A: NIFTY only, BANKNIFTY has no data) |
| **N1N** | Night on NIFTY alone (the like-for-like line across all three eras) |
| **N2** | Night for a Rs 56k account: at most one position a night (NIFTY when both signal), 1 lot, only if the premium x lot fits Rs 56,000; month loss cap: once the month's closed net is <= -Rs 13,000 (the account's month stop), no new Night trade until the next month |

### 2. Liquidity 15+5, exactly as built
**L1**: R101's LIQ arm (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5, 1-ITM, its own exits) walked by R115 / R106's
engine on R51's clean stores, exactly as R143 / R144b ran it (parity with the published trades). Eras B and C;
untestable in era A.

### 3. End-of-day intraday momentum (Gao et al. 2018; Baltussen et al. 2021), NIFTY
- r1 = NIFTY's return from the previous session's last close to 09:45 (the close of the 09:44 minute);
  r2 = the return from 09:45 to 14:50 (the close of the 14:49 minute).
- **M1**: if r1 + r2 and r2 have the same sign (both non-zero) and |r1 + r2| is at or above the 60th percentile of the
  previous 60 sessions' |r1 + r2| (top 40%), buy the ATM CE (up) or ATM PE (down) of the nearest weekly at the 14:50
  minute's open; sell at the 15:20 minute's open.
- **M2** (variant): r2 alone: the sign of r2, and |r2| in the top 40% of the previous 60 sessions' |r2|.
- No trade on the weekly series' expiry day (its 15:05 square-off falls inside the 14:50-15:20 hold). No stop (30-minute
  hold). ATM = the strike at offset 0 of the rolling series at 14:50; the same strike is followed to the exit.

### 4. Noise-area VWAP breakout (Zarattini, Aziz, Barbon 2024), NIFTY
**V1**:
- Checked on the minute closes at 10:15, 10:45, ..., 15:15 (every 30 minutes from 10:15; the close of the minute
  before the mark).
- sigma(t) = the average over the previous 14 sessions of |close at t / that day's 09:15 open - 1|.
- Bands: today's 09:15 open x (1 + sigma(t)) and x (1 - sigma(t)).
- **VWAP: the index has no volume, and futures volume history is not available (the lake and dhan-data hold only the
  futures contracts live at fetch time). V1 uses the TWAP** (the running mean of the 1-minute typical price
  (H + L + C) / 3 from 09:15). Option-chain volume is not used (it is another instrument's volume).
- Entry at a mark from 10:15 to 14:45, when flat: buy the ATM CE if the close is above max(upper band, TWAP); the ATM
  PE if it is below min(lower band, TWAP). The order fills at the mark minute's open.
- Exit at a later mark: a call when the close is back below max(upper band, TWAP), a put when it is back above
  min(lower band, TWAP); sold at that mark minute's open. A position left at 15:20 is sold at the 15:20 open (15:05 on
  the weekly expiry day, the arms' expiry square-off). After an exit the rule may enter again (either side) at the same
  mark if its entry condition holds. One position at a time.

### 5. R72 Breadth Donchian, exactly as built (the regime check for the arms Boss trades live)
- **D1**: R72 with today's rules and its own exits (R101 XL: -15%, the 30-60-100 lock + L25, 30 min, 15:15), on R51's
  clean stores through R115's engine, as R143 / R151 ran it.
- **D2**: D1 + R151's R72 logistic picker, frozen (`r151_donchian_winrate60/results/r72_picker_model.json`, skip below
  0.4656), its features computed by R151's own code. If R151's feature code cannot run on these windows, D2 is
  reported as not run, with the reason.
- **D3**: D1 + FB-R72-A10 (skip an entry when yesterday's range / the 20-session average daily range >= 1.3206).
- The R176 Rs 500 step trail (Boss's 15 Nov exception) is not added: R176 already measured it (-Rs 3.23 lakh on the
  year), and this study judges the arms' own rules.
- Eras B and C; untestable in era A.

### 6. Trend / VIX overlay (outside survey item 5), on 3 and 4 only
**O1 / O2 / O3** = M1 / M2 / V1 with both:
- a call only if the decision price is above the 50-day simple average of NIFTY's daily closes up to the previous
  session; a put only if it is below;
- no entry when India VIX's previous close is above the 90th percentile of its previous 252 daily closes.

**No parameter is fitted for 3, 4 or 6.** Every number above is the paper's or Boss's brief. Era B is the first look;
whatever it shows, the rules go to era C and then era A unchanged (no re-tuning, no dropping of a losing variant).

## 4. Measurement

- **Fills (real prices):** intraday rules (M, V, O) use R51's engine conventions: entry at the contract's open at the
  order minute, or the first traded minute up to 2 minutes later, else no trade; R17's "liq" fills (buys +5 bps plus
  1 / 2 / 4 ticks by the contract's volume in the 5 minutes before; market sells -5 bps minus the same; Rs 0.05 tick).
  A contract with no traded minute at the exit row is sold at the next traded minute (up to 10 minutes later), else at
  its last traded price before the exit (reported as such). Night uses R147's fill model (app +-5 bps and h24's real
  half-spread, NIFTY / BANKNIFTY 0.16% of premium, on both legs). Liquidity and R72 use their own engines (R17 fills).
- **Charges:** dated Zerodha charges (`research/obuy/costs.py` Costs("dated"): STT on option sells 0.05% to Mar 2023,
  0.0625% to Sep 2024, 0.1% to Mar 2026, 0.15% from 1 Apr 2026; dated exchange fees; stamp, SEBI, GST, Rs 20 a
  side) for every candidate. Lots: the dated tables (R51 / R147).
- **Stress:** every candidate is also reported with twice the slippage.
- **Lots:** 1 lot (all gates), and "max fit": the largest whole number of lots whose premium fits Rs 56,000 at that
  trade (charges re-computed; a trade that cannot fit 1 lot is skipped in this column). Option buying needs no margin
  beyond the premium.
- **Per candidate and era:** trades, win %, average win and average loss, net, net without the top 5 trades, worst day,
  worst month, max drawdown (on daily net, 1 lot), and the same at max-fit lots.

## 5. Random-entry twins and multiple testing

- 2,000 twins per candidate and era, seed 190:
  - M1 / M2 / O1 / O2: the same number of trades on days drawn at random (without replacement) from the era's eligible
    days (non-expiry days with both ATM legs priced at 14:50 and 15:20), each with a random side, same contract and
    exit;
  - V1 / O3: the same number of entries, each a random (eligible day, entry mark 10:15-14:45, side), exited by V1's own
    rule;
  - N1B / N1N / N2: per index, the same number of nights drawn at random from the era's tradable nights (both 1-ITM legs
    priced), each with a random side.
  - p = (1 + twins with net >= the rule's) / 2,001 (one-sided); the normal-tail p is also kept.
- **Benjamini-Hochberg** across all earlier log entries plus R190's 13 gated candidates: m = 4,749 + 13 = 4,762 (the
  entry files at the branch base, origin/main 30c4b3c62); earlier entries count as p = 1. R190's q uses each
  candidate's era-A normal-tail twin p (untestable in era A = p 1).

## 6. The RELIABLE gate (all must hold; nothing is relaxed)

1. net > 0 in ALL THREE eras, each with 40+ trades;
2. net without the top 5 trades > 0 in each era;
3. twin p < 0.05 in era A;
4. BH q <= 0.10;
5. reported against the account stop (-Rs 40k drawdown, -Rs 13k month), at 1 lot: max drawdown and worst month in each
   era (a breach is written next to the verdict).

A candidate that meets 1-4 is "reliable"; otherwise "not reliable", with every failed item listed. Win % is reported
prominently with the average win and loss.

## 7. Order of work and the era-A lock

1. This file is committed before any R190 profit or loss exists.
2. `r190study.py bc` computes eras B and C only (era A trades are never summarised: the cut is in `select_era`).
   Results are committed.
3. `results/ERA_A_READ.flag` is committed. `r190study.py a` refuses to run unless that flag is tracked in git and
   `results/era_a.json` does not exist yet. It reads era A once and writes `results/era_a.json`.
4. `r190study.py report` writes `results/summary.md`, `results/report.md` and `decision.json`.

## 8. Tests

`test_r190.py` (pytest): the look-ahead guard (each decision is unchanged when every minute after it is replaced by
noise), the signal rules on hand-made days, the band / TWAP maths, the sizing, the month cap, stats, drawdown, BH, the
twin p, the gate, and the era-A lock.

## 9. Outputs

`results/summary.md` (for Boss: outcome first, one table per candidate x era, a reliable / not reliable line),
`results/report.md`, `decision.json`, `results/*.json`, entries `research/experiments/entries/r190-*.yaml`.
