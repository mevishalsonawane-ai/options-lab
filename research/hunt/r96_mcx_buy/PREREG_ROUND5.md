# R96 round 5 pre-registration: six ideas (two new from the literature, four from round 4's list) (21 Oct 2026)

Boss, 21 Oct: "Run until we found something, commodities options buying strategy". Written and committed **before any
round-5 statistic**. Everything in PREREG_ROUND1.md s2-s5 and s7, PREREG_ROUND2.md s2-s5, PREREG_ROUND3.md s2-s4 and
PREREG_ROUND4.md s4-s5 still holds unless changed here: the same design (5 Aug 2025 - 14 Jul 2026), the same four
walk-forward folds, the **same LOCKED holdout (15 Jul - 19 Oct 2026, never read so far)**, the same costs (Zerodha MCX
option charges + 0.30% / 0.60% half-spread from / before 17:00 IST, natural gas 0.45% / 0.90%; gates also at 2x),
twins (500, seed 96, same book and calendar month), the -40% hard stop, the round-1 lock ladder and the round-2 edge
take-profit on every rule, and BH over the **cumulative ledger: rounds 1-4's 36 ideas + these 6 = 42**.

R97-R99 (gold, NIFTY) have not logged any idea on `origin/main` as of this commit, so BH counts R96's ideas only.
Crude NR7 (R3A / R4G) is **not** re-run on the holdout (Boss has not changed that rule).

Signal source of every rule: **the MCX future** (minute closes of the book's near future; daily rules: the near-month
daily continuous future, back-adjusted). R5B and R5C also read the option chain's ATM IV, as a filter only (CLAUDE.md
allows option-chain data as context). Every trade BUYS options; every trade has a hard stop (-40% of the premium for a
single leg, -30% / -25% for straddles, plus a stop on the future for single legs) and a profit lock (the ladder +30% ->
breakeven, +60% -> +30%, +100% -> +60%, then 40% behind; straddles held for hours use the short event ladder), plus the
edge take-profit where said. Exits use real prints where one exists; model-priced exits are flagged (gate 8).

## 1. Data check (21 Oct, no statistic)

- rclone was missing again; installed from downloads.rclone.org (v1.75.2) as in N6 / N8. Lake copied with plain
  `rclone copy gdrive:IraAlgo-data-lake/<folder> ~/lake/<folder> --include ...` (options_rolling_1m MONTH books of
  CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM, SILVERM, GOLD, SILVER; fut_daily_continuous; fut_1m_mcx_continuous;
  fut_1m_live_mcx; fut_mcx_contracts; fut_mcx_rolls; xauusd_1m). No credential or config was read or printed.
- **`xauusd_1m/` on Drive holds only its README and the US event list; the XAUUSD minute pack itself is not there.**
  So the MCX-vs-COMEX/spot lag idea cannot be tested this round (and the pack starts 15 Oct 2025 anyway). Not counted.
- Strikes: still ATM+/-3 on every MCX book (round-4 finding; STATUS.txt says +/-5). R5-0 (wider prints) cannot run.
- Python: the container's default is 3.13; checks run in a 3.11 venv with research/brain/requirements.txt pinned.

## 2. Ideas considered and not tested (stated before any statistic, not counted)

| idea | why not |
|---|---|
| MCX-vs-COMEX lag in Indian evening hours | no XAUUSD / COMEX minute data in the lake (s1) |
| OPEC+ pre-meeting straddle | the eight-country group met monthly on **Sundays** in the design window (~11 meetings): cannot reach 30 trades; OptionMetrics (57 meetings since 2005) finds IV *falls* slightly around meetings |
| crude / natural gas weekly-expiry behaviour | MCX lists monthly options only (the lake has MONTH books only); the weekly NYMEX contracts don't trade on MCX |
| gold overnight-up / day-down drift (Blose, Gondhalekar & Kort 2018) | a few basis points a day, far under GOLDM option spreads (~1% of premium, R97-R99) plus a day's theta; R97-R99 already found nothing on gold |
| API Tuesday -> EIA Wednesday | only anecdotes found; no consensus or API numbers in the lake |

## 3. Books

crude = CRUDEOILM (x10) + CRUDEOIL (x100); gas = NATGASMINI (x250) + NATURALGAS (x1250); metals = GOLDM, SILVERM.
Pooled rupees are dominated by the big books; median 1-lot stop risk and capital for 2% are reported per rule.

## 4. The six rules (each one BH test, pooled over its books)

| id | idea (source) | rule (signal on the future) | books | twin | history gate |
|---|---|---|---|---|---|
| **R5A** | **Natural-gas storage-day drift** (Prokopczuk, Wese Simen & Wichmann, Energy Journal 42(2) 2021: >50% of the annual NG futures return is earned on EIA storage days, half before the 10:30 ET release; "a simple strategy earns after costs"; full text not read) | on EIA storage days (R1 calendar): **buy the 1-ITM call** at the first minute at or after **17:00 IST** (cheaper spreads, before the US open and the release); stop -40% premium / the future 1.0 x ATR14 below the signal minute's close; ladder, edge; out at the session square-off | gas | same clock and side on a non-storage session of the month | 2012-25 daily NATURALGAS futures: long open -> close on Thursdays (storage proxy), 0.10% cost, mean > 0 (twins: non-Thursdays of the same year) |
| **R5B** | Cheap-IV NR7 on the other books (round-4 list R5-2: tests R4G's filter where it was not chosen) | R2A exactly (NR7 break -> 1-ITM, -40% / opposite extreme, ladder, edge, 3 sessions), only when R4G's filter holds (the NR7 day's ATM IV below the median of the 20 option sessions before it, >= 10 known) | NATGASMINI, NATURALGAS, GOLDM, SILVERM | T-tod (no filter) | none (no IV history), as R4G |
| **R5C** | Cheap-IV NR7 straddle on crude (round-4 list R5-3: R2G lost on expensive premiums) | NR7 day -> ATM straddle at the next session's 09:15, stop -30%, ladder, edge, 3 sessions (R2G exactly), only on R4G's cheap-IV days | crude | same, random session of the month, no filter | none (no IV history) |
| **R5D** | EIA spike fade at 0.10 ATR (round-4 list R5-4; **a re-try after seeing R4C's trade count (2), not the sign of its result**) | R4C exactly with \|move\| >= **0.10** x ATR14 | crude | as R4C | none (intraday) |
| **R5E** | **Pre-event straddle with an exit before the release** (Gao, Xing & Zhang, JFQA 2018: equity straddles bought days before earnings and sold before them earn; Horan, Peterson & Mahar 2004: crude IV drifts up into OPEC meetings) | on EIA crude days (crude books) and EIA storage days (gas books): buy the ATM straddle at the first prints at or after **17:00 IST** (both legs within 15 min); **out at the release - 5 minutes** (no event exposure); stop -25%, short event ladder | crude, gas | the same clock (17:00 -> that release minute - 5) on a non-event session of the month | none (intraday) |
| **R5F** | **Gold / silver ratio breakout -> silver** (R94 found the ratio trends rather than reverts; that finding came from a different study's data, so this direction is not literature-blind) | ratio index = running sum of (gold daily return - silver daily return) from the back-adjusted GOLDM and SILVERM daily futures on common dates; index above the max of its previous 20 values (gold leading) -> **1-ITM SILVERM put** next session 09:15; below the min of its previous 20 -> **1-ITM SILVERM call**; stop -40% / 1.5 x ATR14, ladder, edge, 5 sessions | SILVERM | T-dir (same side, random session of the month) | 2012-25 daily futures, same signal and stop, mean > 0 |

Implementation (frozen with this file): `rules.py` (round 5 block: SqueezeStraddle `iv_cheap`, `PreEventStraddle`,
`StorageDayCall`, `gs_ratio_sides`, `GSRatioDir`, `FAMILIES`), `cfg.py` (round 5 constants), `study.py` (multi-family
gate 9, round 5 order), `history.py` (`storage_long`, `gsr`), `report.py` / `entries.py` names. Commands:
`IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 5`, then `... .report 5`, `... .entries 5`.

## 5. Gates (unchanged) and idea families (gate 9, extended)

Round 1 gates 1-7, round 2 gate 8, round 4 gate 9. Only a rule passing every gate is read on the locked holdout, once.
Gate 9 now collapses **every** family of near-copies into one test (its smallest p x its size, Bonferroni) and runs BH
over the collapsed list; a family member must pass that q <= 0.10 too:

- **NR7-crude family:** R3A, R4A, R4B, R4E, R4F, R4G and **R5C** (same crude NR7 days).
- **EIA-fade family:** R4C and **R5D** (R5D is R4C with a looser threshold).

R5B (other books) and R2A etc. stay separate tests as in round 4. This only makes passing harder.

## 6. Known in advance (stated so it can't be explained away later)

- R5A: ~45 storage days per gas book in design (~90 trades). Entry at 17:00 IST is 07:30 / 08:30 ET; the paper's
  pre-release half is mostly in US hours. The paper's sample ends 2018; NG options' 0.45% half-spread and a day's
  theta on a monthly option must be paid by a one-day drift. The history gate uses Thursday open -> close (the MCX day
  includes Asian hours, not only the US session).
- R5B: R2A lost on these books (-Rs 4,067 on 65, all six books). The filter keeps about half the days -> maybe < 30.
- R5C: about 10-15 trades per crude book: may fail 30 trades; straddles pay two spreads.
- R5D: R4C had 2 trades at 0.20 ATR; at 0.10 maybe 10-20 (likely fails 30 trades). It is in the EIA-fade family.
- R5E: E1 (straddle across the release) lost -Rs 61,416 on 103; this exit avoids the post-release IV crush but pays
  3-4 hours of theta on a monthly straddle. Expected to die unless IV rises into the release.
- R5F: silver is gold's high-beta cousin, so the ratio index is largely silver's own momentum; round 1's directional
  daily rules on SILVERM lost. Expected 15-25 trades.

## 7. The forward read-out of the crude NR7 record (pre-registered now; R5-1 of round 4's list)

The app's **"NR7 crude (record only)"** (android `McxNr7Record`, rules = R3A via the engine's `McxNr7Rules`, OFF by
default) is the forward log; no second log is built. It only logs while Boss's switch is ON. The read-out, done ONCE:

- **When:** the first day on which the lake (Dhan minute data from **20 Oct 2026** on, never seen by any R96 rule) holds
  **>= 15 crude NR7 signal days with a taken trade** under R3A, or on **30 Jun 2027**, whichever comes first.
- **What:** this repository's code at this commit's R3A and R4G (`study.py` with the period 20 Oct 2026 .. read date),
  real prints, the same costs, twins (500, seed 96) and the 2x-spread check. The app's record is a cross-check of the
  fills (per-trade difference reported), not the score.
- **Pass (each rule separately, no other variant read):** net > 0 at 1x and 2x spreads, twin p <= 0.10, no month below
  -Rs 13,000, >= 15 signal days. A pass only allows a PAPER arm to be proposed to Boss (CLAUDE.md PAPER rule); live
  needs Boss's own decision (and the LIVE rules: shadow first, PIN, "AI trades go live").
- If fewer than 15 signal days exist on 30 Jun 2027, it is reported as "not enough data" and read again only after a
  new pre-registration.
