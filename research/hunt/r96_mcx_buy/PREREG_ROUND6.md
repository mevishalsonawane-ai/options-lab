# R96 round 6 pre-registration: four ideas on the base-metal books R96 never touched (21 Oct 2026)

Boss, 21 Oct: "Run until we found something, commodities options buying strategy". Round 6 is the last round before
the coordinator asks Boss whether to continue. Written and committed **before any round-6 statistic**. Everything in
PREREG_ROUND1.md s2-s5 and s7, PREREG_ROUND2.md s2-s5, PREREG_ROUND3.md s2-s4, PREREG_ROUND4.md s4-s5 and
PREREG_ROUND5.md s5 and s7 still holds unless changed here:
- the same design (5 Aug 2025 - 14 Jul 2026) and the same four walk-forward folds;
- the **same LOCKED holdout (15 Jul - 19 Oct 2026, never read so far)**;
- the same twins (500, seed 96, same book and calendar month), the -40% hard stop, the round-1 lock ladder and the
  round-2 edge take-profit on every rule;
- BH over the **cumulative R96 ledger: rounds 1-5's 42 ideas + these 4 = 46**, plus gate 10 (s5).

Crude NR7 (R3A / R4G) is **not** re-run, on the design window or the holdout; its forward read-out (PREREG_ROUND5 s7)
and the app's "NR7 crude (record only)" log are unchanged.

Signal source of every rule: **the MCX future** (the COPPER / ZINC book's near-future minute closes; daily rules: the
near-month daily continuous future, back-adjusted). R6B also reads the option chain's ATM IV, as a filter only
(CLAUDE.md allows option-chain data as context). Every trade BUYS one option (1-ITM). Every trade has:
- a hard stop: -40% of the premium, plus a stop on the future;
- a profit lock: the ladder +30% -> breakeven, +60% -> +30%, +100% -> +60%, then 40% behind;
- the edge take-profit.

Exits use real prints where one exists; model-priced exits are flagged (gate 8).

## 1. Data check (21 Oct, no return statistic)

- rclone was missing again; installed from downloads.rclone.org (v1.75.2) as in N6 / N8 / N10. Lake copied with plain
  `rclone copy gdrive:IraAlgo-data-lake/<folder> ~/lake/<folder> --include ...`: options_rolling_1m MONTH books of
  CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM, SILVERM, COPPER, ZINC, plus fut_daily_continuous,
  fut_mcx_contracts, fut_mcx_rolls and xauusd_1m. No credential or config was read or printed.
- **`xauusd_1m/` still holds only README.md and us_events.csv.** The MCX-vs-COMEX gold lag (R6-2 of round 5's list)
  cannot be tested. Not counted.
- **Strikes are still ATM+/-3 on every book, and the lake has no bid / ask anywhere** (CATALOG.json has no bid / ask
  field). So R6-3 (wider prints) and R6-4 (real spreads) cannot run. Not counted.
- **COPPER and ZINC option books** (5 Aug 2025 - 19 Oct 2026, ATM+/-3, 309 / 301 sessions):

  | book | printed near-ATM (+/-1) minutes a session | median premium |
  |---|---|---|
  | COPPER | ~980 | Rs 19 / kg |
  | ZINC | ~42 (very thin; most entries may find no print within 15 minutes and be skipped) | Rs 4 / kg |

  For comparison, CRUDEOILM prints ~4,700 near-ATM minutes a session.
- Daily futures: 2012 - Oct 2026 for both (3,651 rows each).
- **Lots** (Zerodha's commodity options list): COPPER 2,500 kg and ZINC 5 MT (5,000 kg), both quoted in Rs / kg.
  So 1 lot = 2,500 / 5,000 units per point.
- **Expiry days** (the R53 straddle-jump detector):
  - COPPER: 14 monthly expiries, one in every month from Aug 2025 to Sep 2026.
  - ZINC: 13 (missing 23 Apr 2026: its book is too thin).
  - COPPER and ZINC expire on the same day in every other month, so **ZINC uses the union of both sets** (N11,
    `data.share_expiries`). Without it, a ZINC trade could be held across the April expiry into the next contract.
- **Spreads (an assumption, as every R96 cost):** the median one-minute range / close on printed near-ATM option
  minutes in design was:

  | book | from 17:00 | before 17:00 |
  |---|---|---|
  | CRUDEOILM | 1.29% | 0.91% |
  | GOLDM | 1.27% | 1.01% |
  | COPPER | 1.84% | 1.65% |
  | ZINC | 4.98% | 2.70% |

  Half-spreads per fill, from / before 17:00 IST:
  - **COPPER 0.50% / 1.00%** (vs crude 0.30% / 0.60%);
  - **ZINC 1.25% / 2.50%** (one Rs 0.05 tick on a Rs 4 premium is 1.25%).

  Charges: Zerodha MCX option charges (unchanged). Gates are also run at 2x spreads, as every round.
- Python: checks run in a 3.11 venv with research/brain/requirements.txt (scikit-learn 1.5.2), as in round 5.

## 2. Ideas considered and not tested (stated before any statistic, not counted)

| idea | why not |
|---|---|
| MCX-vs-COMEX gold lag (R6-2) | no XAUUSD minute pack on Drive (s1); R97E / R98A-B already test XAUUSD leads in the R97-R98 hunts |
| R3A / R4G on wider prints (R6-3) | the lake is still ATM+/-3 |
| re-cost R3A / R4G with recorded bid / ask (R6-4) | no bid / ask in the lake |
| the forward read-out (R6-5) | pre-registered for >= 15 crude NR7 signal days from 20 Oct 2026 (or 30 Jun 2027); 1 day has passed |
| NR7 on the big GOLD / SILVER books | same underlyings as GOLDM / SILVERM, where NR7 already lost (R2A, R5B): not an independent test |
| ALUMINIUM / LEAD options | no option books in the lake (daily futures only for ALUMINIUM) |
| more crude NR7 variants on the design window | round 5's rule: they can only lower the family's q |

## 3. Books

`K.BASE = (COPPER, ZINC)`; one position per book at a time; pooled rupees are dominated by ZINC's larger lot only when
it trades. Every rule reports by book, median 1-lot stop risk and capital for 2%.

## 4. The four rules (each one BH test, pooled over COPPER + ZINC)

| id | idea (source) | rule (signal on the future) | twin | history gate |
|---|---|---|---|---|
| **R6A** | **NR7 on books R96 never touched** (round 5's R6-1; a fair out-of-sample test of the NR7 idea, since crude was picked after seeing rounds 1-2) | R3A exactly: NR7 day -> the first future minute close beyond its high / low next session (09:15-22:00) -> 1-ITM that way; stop -40% / the NR7 opposite extreme, ladder, edge, 3 sessions | T-tod (same clock, same stop distance in ATR, random session of the month) | 2012-25 daily COPPER / ZINC futures, R3A's daily NR7 trade, 0.10% cost: pooled mean > 0 |
| **R6B** | R6A on cheap-IV days (R4G's filter, separate test as round 5 said) | R6A only when the NR7 day's median ATM IV is below the median of the 20 option sessions before it (>= 10 known) | T-tod, no filter | none (no IV history), as R4G |
| **R6C** | **First-half-hour momentum in copper** (Jin, Kearney, Li & Yang, J. Futures Markets 40(4) 2020: in Chinese copper and three other commodity futures the first half-hour's return predicts the last half-hour's, more so on high-volume / high-volatility days; abstract only) | the future's move from the previous session's last minute to 09:29 IST (within 5 minutes of it); if \|move\| >= the 67th percentile of the last 60 sessions' moves (>= 20 known) -> 1-ITM that way at the session close - 45 min; stop -40% / 0.25 x ATR14 behind; edge; out at the square-off (close - 15). The previous session must be in the same contract segment | a random session of the month, its own sign, no size filter (R3I's twin) | none (intraday) |
| **R6D** | **20-day channel breakout** (Szakmary, Shen & Sharma, J. Banking & Finance 34(2) 2010: channel and moving-average rules positive net of costs in >= 22 of 28 commodity futures over 48 years of monthly data; abstract only; the 20-day daily channel is our adaptation) | the FIRST back-adjusted daily close above the max (below the min) of the previous 20 closes -> 1-ITM call (put) next session 09:15; stop -40% / 1.5 x ATR14 on the future, ladder, edge, 5 sessions | T-dir (same side, random session of the month) | 2012-25 daily futures, same signal and stop, 0.10% cost: pooled mean > 0 |

Implementation (frozen with this file):
- `cfg.py`: the round 6 block (books, lots, spreads, clocks, `OTHER_MCX_P`).
- `rules.py`: the round 6 block (`r6_daily` dc_side, `OpenHalfHour`, `RULES` R6A-R6D, `ORDER6`, `NR7_BASE_FAMILY`).
- `data.py`: `share_expiries`, `EXPIRY_FROM`.
- `study.py`: round 6 order, base-metal books, gate 10 `q_with_other`.
- `report.py` / `entries.py`: names, books and costs text.
- Tests: 10 new (70 -> 80, incl. look-ahead guards for dc_side and R6C's size filter).

Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design 6`, then `... .report 6`,
`... .entries 6`.

## 5. Gates

The gates are unchanged: round 1's gates 1-7, round 2's gate 8, round 4/5's gate 9. One new family and one new gate
are added:

- **Gate 9, new family: NR7-base**, R6A and R6B (R6B's trades are a subset of R6A's). It is collapsed like the
  NR7-crude and EIA-fade families (its smallest p x 2), and BH runs over the collapsed list. A member must pass that
  q <= 0.10 too.
- **Gate 10 (new; it only makes passing harder):** the rule's BH q must also be <= 0.10 when the 13 MCX ideas of the
  R97 / R98 gold hunts join the list. Their design twin p, from `research/experiments/entries/r97-a..g` and
  `r98-a..f` on main, are listed in `cfg.OTHER_MCX_P`. R99 (NIFTY) is not a commodity and is not counted.

Only a rule passing every gate is read on the locked holdout, once (`study holdout <rule> 6`).

## 6. Known in advance (stated so it can't be explained away later)

- **R6A:** about one NR7 day in 7, so ~30 COPPER signals in design. ZINC may lose most of its entries to "no print
  within 15 minutes", so the pooled count may be near or under 30. R2A (all six original books) lost -Rs 4,067 on 65;
  R3A won on crude only after crude was picked.
- **R6B:** roughly half of R6A's days; likely < 30 trades (fails gate 1 by design, as R4G did).
- **R6C:** ~75 COPPER candidates (a third of ~230 sessions). Its 30-minute hold (entry and exit both after 17:00) must
  beat 0.50% of half-spread each way on COPPER (1.25% on ZINC) plus charges. R3I (the last-half-hour
  version on the four minis) lost -Rs 79,100 on 223. Expected to die unless copper differs.
- **R6D:** ~10-20 signals per book. Round 1's directional daily rules (W1, P1) lost on the four minis, and R2H (quiet
  base + 20-day breakout) lost -Rs 1.08 lakh on 56. A daily breakout with a 5-session option hold pays theta.
- This is the sixth round; with 46 (59 with gate 10) ideas, a new idea needs a twin p of about 0.002 or lower to
  reach q <= 0.10. A pass is unlikely; a clean "nothing" on fresh books is still informative.
