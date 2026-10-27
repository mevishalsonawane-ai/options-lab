# R116 pre-registration: on an expiry day, buy the NEXT expiry's option?

Written 27 Oct 2026, **before any R116 profit or loss was computed** (committed and pushed with the data map and its code, before
the study code's first run). Research only; nothing in `android/**` changes.

Boss, 27 Oct (a monthly expiry: NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY all expired, so the arms skipped them):
*"Today was expiry, so most strategies did not work. Why don't we use next expiry options?"*

Today every arm skips an index on its own expiry day (PassedArmsHost "its expiry day: no trades"; Liquidity's skip from
26 Oct), because every study they rest on skipped those days. R116 asks: on those days, keep the arm's index signal
unchanged and buy the 1-ITM option of the **next** expiry (next weekly for NIFTY / SENSEX, next monthly for BANKNIFTY /
FINNIFTY / MIDCPNIFTY), with the arm's own exits, 1 lot.

**Signal source of every rule: the index's own bars** (R72 / R51: the 5-minute index bars; R52: the 09:15-09:19 index bar
against yesterday's value area; Liquidity: its 15 / 30 / 5-minute index book bars). No rule reads an option's chart; only
the contract bought changes. Stops, locks and time exits stay on that option's premium as today.

## 1. The data map (written before any number; `results/datamap.json`, built by `r116data.py map`)

Sources searched:
- **Drive lake** `options_rolling_1m` (Dhan's rolling ATM±10, 1-minute), copied 27 Oct. WEEK holds the nearest expiry
  (on an expiry day: the contract expiring that day). MONTH holds the nearest monthly. For **BANKNIFTY, FINNIFTY and
  MIDCPNIFTY the MONTH file is byte-identical to WEEK** in 2025 and 2026 (same md5), so the lake has **no next-month
  contract** for them. For **NIFTY and SENSEX** the MONTH series is the exact next weekly on the weekly expiry day one
  week before a monthly expiry, a later monthly (13-56 days out) on the other weekly expiry days, and the expiring
  contract itself on a monthly expiry day.
- **`research-data` GitHub release** archives `nifty_prev` / `nifty_year` (1 Apr 2024 - 13 Apr 2026) and
  `banknifty_prev_year_wide` / `banknifty_year_wide` (13 Feb 2024 - 23 Feb 2026): for every session, "the nearest expiry
  AFTER the day" at 20 strikes around the 09:20 index, 1-minute OHLCV. On an expiry day that is the next expiry. The
  `expiry_days.parquet` file (EXPIRY_SAME_DAY.md) holds the same-day contracts only. FINNIFTY / SENSEX in the release are
  index minutes only. No other source holds next-expiry 1-minute prints (R104 / H11 / H13 / H19 / H22 / H31 notes agree).
- **Checks:** on ordinary days the archive's nearest contract matches the lake's WEEK contract (median day 97% of
  shared minutes within 1% for NIFTY, 94% of all shared minutes for BANKNIFTY, expiry days included; timestamps aligned); on the 17 NIFTY expiry days
  where both the lake's exact MONTH and the archive exist, they agree on 98.0-99.8% of minutes. The archive's stray opens
  / capped highs were fixed with lakeclean step 2 (NIFTY 357 + 116 of 1,094,390 rows; BANKNIFTY 5 of 207,582).
- **Rejected, never guessed:** BANKNIFTY 24 Dec 2024 (the archive labels its next contract 29 Jan 2025 while the
  exchange calendar says 30 Jan: not provably the next expiry), NIFTY 6 Oct 2026 (the calendar does not reach the next
  weekly), and days missing from the archive (NIFTY 30 Dec 2025, BANKNIFTY 30 Dec 2025 and 24 Feb 2026).

Expiry days = the days the R51 clean stores flag (the same flag the arms skip on), 20 Nov 2024 - 13 Oct 2026. "Next
prints" = the next expiry's 1-minute contracts exist that day (median 1-ITM call + put minute coverage 09:15-15:05: 1.0).

| index | expiry days design (of which walk-forward) | with next prints, design (wf) | expiry days final | with next prints, final | next expiry is | source |
|---|---|---|---|---|---|---|
| NIFTY | 72 (53) | **71 (52)** | 27 | **8** | 5-8 days out (next weekly) | archive 54 + 2, lake MONTH 17 + 6 |
| SENSEX | 70 (51) | **14 (9)** | 27 | **5** | 6-7 days out | lake MONTH only (exact weeks) |
| BANKNIFTY | 17 (12) | **13 (9)** | 6 | **0** | 27-35 days out (next monthly) | archive only, to Jan 2026 |
| FINNIFTY | 17 (12) | **0** | 6 | **0** | - | none |
| MIDCPNIFTY | 17 (12) | **0** | 6 | **0** | - | none |

Stand-in days (NIFTY / SENSEX weekly expiry days where only the lake's later monthly exists, 13-56 days out): NIFTY 38
design / 14 final, SENSEX 40 / 15. **Reported only (world M), never gated**: it is not the contract Boss asked about.

**What the map already says (before any number):** FINNIFTY and MIDCPNIFTY cannot be tested at all. BANKNIFTY can be
tested on 13 design days only, with no final read. NIFTY is the only index with a real sample, but most of its final
days (19 of 27) have no next-weekly prints. Across all indices the final holds at most 13 expiry days (NIFTY 8, SENSEX
5). Liquidity trades only BANKNIFTY / FINNIFTY / MIDCPNIFTY, so its next-expiry test has 13 design days and no final.
**A pass is therefore close to impossible on sample size alone; R116 says so plainly rather than guessing.**

## 2. Arms, engine, exits (identical to R101 / R106 / R113 / R115)

- R51's Amendment A clean stores (built 27 Oct from the lake, window 20 Nov 2024 - 13 Oct 2026), R64's engine, R17
  fills (slippage), dated Zerodha / exchange charges, 1 lot at the lot size in force (r51cfg.LOTS).
- Arms and their exits exactly as R101 / R115 build them (`r115study.units` / `walk`, today's taking rules):
  **R72** Breadth Donchian and **R51** Donchian 20 + Supertrend (exits XL: ladder + L25, 30-minute rule, 15:15), **R52**
  OOV (09:20 entry, R64 A4 + R66 L25), **Liquidity 15+5** (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5; -15% stop,
  20-minute time stop unless +5%, index stop / target / failed-break exits).
- Strike: 1-ITM from the minute before the entry, ATM from the store's rolling (spot-based) label, the **same strike in
  every world**; only the expiry differs.
- Square-off on an expiry day: 15:05 (CLAUDE.md's expiry square-off) in every world, as the engine already does.
- **Solo 2.0 is not run** (it needs the R57 model and the brain cube, not in this session; as R101 / R106).

## 3. Worlds (what an expiry day trades)

- **(a) Skip** (today's rule): Rs 0, 0 trades on every expiry day.
- **(b) S, same-day option:** the expiry-day skip lifted, the store's own expiring contracts (EXPIRY_SAME_DAY.md's idea,
  now on all five indices and four arms).
- **(c) N, next expiry:** on the days with next prints (section 1), the expiry-day skip lifted and the day's contracts
  replaced by the next expiry's. Index minutes, signals, ATM label, strike rule and exits unchanged.
- **M, stand-in** (reported only): NIFTY / SENSEX days with only a later monthly.

Every non-expiry day is untouched; parity with R115's published "today" figures (design and final) is asserted on the
non-expiry days of world S before any expiry-day number is read.

## 4. Periods

Design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (nothing is fitted, so the walk-forward
read is that out-of-sample year as in R113 / R115); final 1 Apr - 13 Oct 2026, **read once** (`r116study.py final`,
guarded by `results/FINAL_READ`). The final window has been read by many arms studies, but **never on expiry days**
(they all skipped them), except EXPIRY_SAME_DAY's BANKNIFTY same-day read (to Jan 2026, design only).

## 5. The family and the gate (never relaxed)

8 gated tests: **N72, N51, N52, NLIQ** (next expiry vs skipping) and **S72, S51, S52, SLIQ** (same-day vs skipping), each
the arm's expiry-day trades pooled over the indices that have data in that world. A test passes only if ALL hold:

1. net above Rs 0 (skipping) in design, in the walk-forward year and in the final, each period having trades;
2. at least **40 final trades** (fewer: "not enough data to decide", never a pass);
3. beats its **random-entry twin**: 2,000 twins, each trade's entry minute drawn uniformly in the arm's window (R72 /
   R51 / R52 09:20-15:10, Liquidity 09:20-14:00; same days, sides and counts; same contracts, strike rule and premium
   exits; Liquidity's twins carry no index exits, as R51's Liquidity twin), final period; p = (1 + twins >= actual) /
   2,001, the normal-tail p when no twin reaches it;
4. **BH q <= 0.10 over every test ever logged**: 9,300,047 earlier tests (every family in `research/experiments/entries/`
   to 27 Oct, each the larger of its entries and summed `variants_tried`, `r115lib.count_prior`) + 8 = 9,300,055.

Reported, never gated: per index; N against S on the same days; the 09:15-09:30 fills (R113 found the arms' money there);
world M; the premium and lot cost of each world (median entry premium x lot), and the 2% per-trade risk cap (BossRisk:
lot x entry x 15% + Rs 60 at most 2% of the account): the account size below which one lot would be skipped.

## 6. What happens with the answer

- A passer may go on paper only (CLAUDE.md: logged in the Autopilot timeline, undoable in one tap, labelled); nothing
  goes live. Every result is logged in `research/experiments/entries/r116-*.yaml`, failures included.
- If the sample is too small to decide (expected, section 1), R116 proposes a **log-only would-be record** for expiry
  days (what each arm would have bought in the next expiry, and its premium path), so evidence builds without risk.
