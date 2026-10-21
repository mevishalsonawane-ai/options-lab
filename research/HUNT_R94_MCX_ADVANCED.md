# R94: Boss's five "advanced commodity" ideas on MCX (21 Oct 2026)

Pre-registered in `research/hunt/r94_mcx_advanced/PREREG.md` (commit 8b6b326, before any statistic). Dated notes and
amendments: `hunt/r94_mcx_advanced/NOTES.md` (N1-N3), `NOTES_idea3.md`, `NOTES_idea5.md`. Code, tests and the
look-ahead guards: `research/hunt/r94_mcx_advanced/` (77 tests). Full per-cell tables: `results/TABLES.md`; numbers:
`results/design_all.json`, `results/holdout_all.json`. Experiment entries: `research/experiments/entries/r94-*.yaml`
(13). Research only: nothing in the app changed, nothing trades.

## Verdict (read this first)

**None of the ideas works on MCX after costs. 0 of 152 pre-registered cells passed the design gates, so no APP_SPEC and
no paper arm.** The holdout was read once for every in-scope cell (64), and nothing passed there either.

| idea | can we test it? | what happened | in one line |
|---|---|---|---|
| 1. Calendar spreads | partly: only contracts live on 19 Oct, 16 pairs, 1-5 months each (May - Oct 2026) | design -Rs 5.31 lakh on 546 trades, holdout -Rs 9.55 lakh on 937; lowest q 0.15 | the spread does not mean-revert at all before costs (gross about Rs 0 a trade); 4 legs of costs then lose every pair |
| 2. Sector index vs constituents | the index: **no** (no point-in-time history; ~0 lots a day). Proxies inside each index: yes, 2012-2026 | GOLD/SILVER ratio lost in design (-Rs 6.3 lakh, W60) and holdout; base-metal pairs positive in design but not significant (q 1.00) and negative in holdout | the gold/silver ratio trends instead of reverting; base metals also fail Boss's liquidity rule |
| 3. Option selling | yes (design only) | **out of scope (Boss, 21 Oct)**: stopped at design, holdout not read; 0 of 88 cells reached p <= 0.05 | iron condors lose to costs (crude: ~Rs 1,380 a round trip vs about Rs 0 gross); naked strangles carry -Rs 1.5 lakh single-gap tails |
| 4. Bullion seasonals | yes, 2012-2026 | design: best "short gold 10 days after Diwali" +Rs 44,734 on 10 (p 0.037, q 0.70); holdout bullion seasonals all positive but random windows of the same years were too (twin p 0.09-0.31) | the 2022-26 gains are the bullion bull market, not the calendar |
| 5. US overnight -> MCX open | yes, with free timestamped Dukascopy proxies (not exchange prints) | design best: follow-US on gas +Rs 73,995 on 29 (q 0.23, 1 of 4 folds positive); holdout pooled +Rs 7,467 on 320 trades | the gap prices the night (corr 0.71-0.87 gold, silver, crude); what is left is not tradable after costs |

Boss's risk rules make it worse: at Rs 1 lakh capital, a 2% (Rs 2,000) stop risk fits a full lot only on the mini
crude and mini gas contracts. GOLDM / SILVERM / CRUDEOIL / NATURALGAS trades needed Rs 1.3-8 lakh of capital for the
median trade to stay within 2%; the pair proxies Rs 15-77 lakh.

## Method (as pre-registered)

- Real MCX costs on every leg (Zerodha charges, `McxCosts.kt`), a half-spread on every fill (far month 3x the near),
  1 lot, a hard stop on every trade, margins without any spread benefit.
- One Benjamini-Hochberg family over all 152 design cells (38 calendar, 10 pairs, 88 option, 8 seasonal, 8 US gap). The
  88 option cells stay in the family after idea 3 went out of scope (N2): removing them after seeing their p-values
  would have loosened the bar for the rest. (Without them no verdict changes: the gas cell of idea 5 would reach q 0.10
  but still fails on trades, 29 < 40, and walk-forward, 1 of 4 folds.)
- Random-entry twins (calendar, pairs: random time and side, holding times drawn from the rule's own trades, N1);
  random windows inside the same year (seasonals); random side on the same days (US gap); block bootstrap against zero
  (option selling).
- Gates: >= 40 trades (10 for seasonals and option cycles), net > 0, p <= 0.05, q <= 0.10, walk-forward folds 2/3
  positive, net without the top 5 > 0, worst month >= -Rs 13k; then the holdout read once (net > 0, p <= 0.10, worst
  month >= -Rs 13k) and the 2% check.
- **Signal source of every rule:** idea 1 the two futures legs (the spread); idea 2 the two roll-adjusted futures
  (their ratio); idea 3 none (non-directional; ATM IV only as a context filter); idea 4 the calendar; idea 5 the US
  benchmark CFD and the MCX future. No rule reads an option's own chart.
- **Holdout incident (N3).** The first holdout call aborted before saving or printing anything (two pieces of code
  both took the read lock). The fix was made and the read ran once; the aborted lock file is kept in `results/`.

## Idea 1: calendar spreads

**Data.** Dhan serves only live contracts, so the lake holds per-minute prices only for contracts live on 19 Oct.
Pairs (consecutive months, >= 4,000 minutes with both legs printed): GOLDM Nov/Dec, Dec/Jan, Jan/Feb; SILVERM Nov/Feb,
Feb/Apr; SILVER Dec/Mar; CRUDEOILM Oct/Nov, Nov/Dec, Dec/Jan; CRUDEOIL Oct/Nov, Nov/Dec; NATGASMINI Oct/Nov, Nov/Dec,
Dec/Jan; NATURALGAS Oct/Nov, Nov/Dec. Common prints start between 29 Apr (SILVERM Nov/Feb) and 17 Aug 2026. Design: first
data - 31 Aug 2026 (observations per pair 28-2,978 on a 15-minute grid); holdout 1 Sep - 19 Oct 2026.

**Rules.** Z1 (z of ln(far/near) over 100 observations, |z| >= 2, exit at z = 0 / 2 sessions / a 2-sd stop), Z2 (400
observations, 5 sessions), C1 for bullion (implied carry vs 6.5% a year, +/-3 points in, 1 point out, 3 more points stop).

| | design | holdout (read once) |
|---|---|---|
| all 38 cells pooled | 546 trades, -Rs 5,31,171 | 937 trades, -Rs 9,55,191 |
| best design cell by q | Z1 GOLDM Jan/Feb: 18 trades, -Rs 2,453 (p 0.001 vs worse twins; q 0.15) | 51 trades, -Rs 70,645 |
| positive design cells (>= 4 trades) | C1 SILVER Dec/Mar 4 trades +Rs 30,951 (worst month -Rs 45,225); C1 SILVERM Feb/Apr 8 trades +Rs 13,078 (worst month -Rs 16,535) | -Rs 366 on 3; -Rs 1,590 on 3 |
| largest losses | Z1 GOLDM Nov/Dec 74 trades -Rs 1,02,906 (14% win, worst month -Rs 54,609, max DD -Rs 1,02,906) | Z1 SILVER Dec/Mar 40 trades -Rs 1,56,300 (worst month -Rs 84,822) |

**Why it fails.** Over design and holdout trades together, the spread earned almost nothing even before costs:

| contract | trades | gross a trade | costs a trade (4 orders, 4 half-spreads) | trades positive before costs |
|---|---|---|---|---|
| GOLDM | 505 | +Rs 20 | Rs 1,242 | 54% |
| SILVERM | 322 | -Rs 73 | Rs 994 | 48% |
| CRUDEOILM | 179 | -Rs 20 | Rs 203 | 54% |
| NATGASMINI | 204 | +Rs 18 | Rs 224 | 50% |
| NATURALGAS | 120 | +Rs 9 | Rs 738 | 49% |
| CRUDEOIL | 78 | -Rs 832 | Rs 1,188 | 49% |

Short-term wiggles in the near/far spread are noise, not something that pays back. Even with the far leg as cheap as
the near (1x half-spread) every large cell stays negative (e.g. Z1 GOLDM Nov/Dec -Rs 80,197). The carry rule (C1) trades
rarely (3-21 times a cell) and is negative on 4 of 6 bullion pairs. In the holdout the worst month breaches -Rs 13k on
every Z1 cell of GOLDM, SILVERM, SILVER, CRUDEOIL and NATURALGAS (-Rs 21k to -Rs 85k); the mini crude / gas cells
lose small amounts (worst months -Rs 4.3k to -6.5k).
2% check: only CRUDEOILM and NATGASMINI pairs fit Rs 2,000 (median capital needed Rs 22-62k); GOLDM pairs need about
Rs 1.3-2.7 lakh, SILVER Rs 14-30 lakh. The MCX calendar-spread margin benefit would cut margin, not these losses.

**To test it properly** we would need years of next-month prices (Dhan's continuous `expiryCode 1` daily since 2012, or
expired contracts' minutes), which needs a live Dhan token, and real bid/ask for the far months.

## Idea 2: sector index pairing (labelled proxies)

**The index itself cannot be tested.** MCXBULLDEX and MCXMETLDEX futures are listed (Dhan scrip master, Oct-Jan
expiries; no ENRGDEX future), but the lake holds no history, the Dhan token had expired (HTTP 401), mcxindia.com refuses
scripted downloads (HTTP 403), and MCX_GUIDE (8 Oct) measured about 0 lots a day in both, which already fails Boss's
"liquid contracts only" rule. Boss's own example (long METLDEX, short lead or zinc) uses contracts that fail it too.

**Proxies** (roll-adjusted daily continuous, z of the log ratio over 60 / 120 days, |z| >= 2, exit at z = 0 / 20 days /
|z| >= 3.5; Rs 10 lakh a leg). Design 2012-2021, holdout 2022 - 16 Oct 2026.

| pair | label | design W60 / W120 (trades, net) | q | holdout W60 / W120 |
|---|---|---|---|---|
| GOLD / SILVER | BULLDEX proxy, liquid | 68, -Rs 6,26,347 / 50, -Rs 3,54,044 | 1.00 | 33, -Rs 1,68,790 / 21, -Rs 7,40,116 |
| COPPER / ZINC | METLDEX proxy, fails liquidity | 71, +Rs 1,24,960 / 51, +Rs 1,93,152 | 1.00 | 32, -Rs 2,17,922 / 23, -Rs 1,26,762 |
| COPPER / ALUMINIUM | METLDEX proxy, fails liquidity | 67, +Rs 1,01,307 / 48, +Rs 2,97,202 | 1.00 | 37, -Rs 63,949 / 24, +Rs 1,04,126 |
| ZINC / ALUMINIUM | METLDEX proxy, fails liquidity | 70, +Rs 2,54,190 / 49, +Rs 1,44,900 | 1.00 | 36, -Rs 9,170 / 27, -Rs 23,193 |
| CRUDEOIL / NATURALGAS | ENRGDEX proxy, not a real pair | 68, -Rs 2,29,137 / 44, -Rs 7,05,128 | 1.00 | 28, +Rs 12,07,330 / 18, -Rs 47,754 |

Design twin p 0.12-0.96. Worst months: -Rs 1.0 to -5.5 lakh (design), and -Rs 3.9 lakh in the holdout for gold/silver
and crude/gas; max drawdowns -Rs 2.2 to -18.7 lakh. The crude/gas W60 holdout gain is one regime (twin p 0.10, worst
month -Rs 3.9 lakh) and the same pair lost -Rs 7.1 lakh in design. 0% of trades fit 2% of Rs 1 lakh (median capital
needed Rs 15-77 lakh). The gold/silver ratio kept running instead of reverting: of 68 design W60 trades 14 hit the
|z| >= 3.5 stop and 40 ran out of time; only 14 reverted to z = 0.

## Idea 3: option selling (out of scope; design only)

Boss, 21 Oct: "Option selling is out of scope." The design run had finished, so it is recorded (N2; `NOTES_idea3.md`)
and its holdout was **not** read. Iron condor (short +/-1, long +/-3), iron fly, a naked strangle (labelled comparison)
and a covered call, on CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM and SILVERM, overnight (D1) and a 10-session
cycle (CY), all days or high-IV days only, each with a 1x-credit hard stop, a 50 / 25% profit lock and an 80% take
profit. Design 5 Aug 2025 - 31 Mar 2026:
- 0 of 88 cells reached p <= 0.05 (best p 0.36: covered call CRUDEOIL D1 high-IV, 67 trades, +Rs 14,734).
- Iron condor CRUDEOIL D1: 149 trades, -Rs 2,42,943, worst month -Rs 1,01,031; about Rs 1,063 of half-spreads + Rs 316
  of charges per round trip (8 fills) against a mean gross of -Rs 251 on a Rs 7,443 credit. Win rate 0-5% on D1 IC / IF.
- GOLDM / SILVERM condors almost never trade: the +/-3 wings rarely print (GOLDM IC D1: 3 trades, 146 days skipped).
- Naked strangle (research only): wins 46-61% of the time but CRUDEOIL D1 lost -Rs 5,32,912 on 149, worst trade
  -Rs 1,49,006 (a 12.8% weekend gap into 9 Mar 2026), worst month -Rs 5,05,246.
- Covered call SILVERM D1: 108 trades, -Rs 6,51,564, worst trade -Rs 1,90,692 (a 9% one-minute fall went through the 1%
  stop).
- Caveat: the lake has only ATM-3..+3, so wings leaving the window were valued by bounds; the valuation rule was amended
  twice AFTER design numbers were seen (A3.1, A3.2), both times only to remove impossible values. These numbers are
  evidence of "costs and data limits", not a clean pre-registered test. Selling options needs the full chain (ATM+/-10)
  and real bid/ask to be judged.

## Idea 4: bullion seasonals (daily continuous, 2012 - 2026)

Long 10 sessions before Diwali, short 10 after, long 10 before Akshaya Tritiya, long in the wedding months (Nov-Feb,
Apr-May); 1 GOLDM / SILVERM lot on the GOLD / SILVER price, 2 x ATR14 resting stop, rolls not earned.

| cell | design 2012-21 trades, net (p, q) | holdout 2022-26 trades, net (twin p; twin mean) | holdout worst trade |
|---|---|---|---|
| Gold pre-Diwali long | 10, -Rs 10,780 (0.71, 1.00) | 4, +Rs 89,205 (0.072; +Rs 26,733) | -Rs 12,604 |
| Silver pre-Diwali long | 10, +Rs 12,147 (0.29, 1.00) | 4, +Rs 38,549 (0.36; +Rs 30,982) | -Rs 18,062 |
| Gold post-Diwali short | 10, +Rs 44,734 (0.037, 0.70) | 4, +Rs 77,248 (0.006; -Rs 26,069) | -Rs 10,161 |
| Silver post-Diwali short | 10, +Rs 57,271 (0.062, 1.00) | 4, +Rs 16,401 (0.16; -Rs 19,200) | -Rs 15,291 |
| Gold pre-Akshaya long | 10, +Rs 40,607 (0.092, 1.00) | 5, +Rs 64,625 (0.31; +Rs 29,523) | -Rs 11,449 |
| Silver pre-Akshaya long | 10, -Rs 16,473 (0.62, 1.00) | 5, +Rs 1,40,549 (0.15; +Rs 28,707) | -Rs 12,381 |
| Gold wedding months long | 59, +Rs 89,602 (0.24, 1.00) | 28, +Rs 5,73,106 (0.094; +Rs 3,52,238) | -Rs 20,043 |
| Silver wedding months long | 59, -Rs 27,684 (0.47, 1.00) | 28, +Rs 5,03,942 (0.17; +Rs 2,10,470) | -Rs 2,94,772 (Feb 2026) |

- Design: no claim beats random windows of the same year after BH (lowest q 0.70); "gold falls after Diwali" is the
  nearest (p 0.037 on 10 events, folds +10k / +3k / +32k), but one of 152 tests at p 0.037 is what chance gives.
- Holdout: every long made money because gold and silver rose strongly in 2024-26 (the wedding months' gains are mostly
  Nov 2025 - Feb 2026). Random windows of the same length in the same years made most of it too. Post-Diwali gold short
  made +Rs 77,248 on 4 events (p 0.006): 2024 and 2025 +Rs 45k each, 2022 and 2023 negative; 4 events cannot carry a
  claim. Diwali 2026 (8 Nov) falls after the data.
- Single wedding-month losses reach -Rs 2.95 lakh (silver, Feb 2026): 0% of trades fit 2% of Rs 1 lakh (a 2 x ATR stop
  on one GOLDM lot needs about Rs 2.9-3.5 lakh of capital; SILVERM Rs 3.7-4.7 lakh).
- Like most seasonal claims, these vanish against same-year random windows. NCDEX agri seasonals: no data, not tested.

## Idea 5: US overnight move -> MCX open (re-test of R2)

US proxy: Dukascopy 1-minute BID CFDs with UTC timestamps (XAUUSD, XAGUSD, WTI, Henry Hub; points / 1,000, gas
/ 10,000), not COMEX / NYMEX exchange prints. MCX: the option books' near-future minute. G1 trades the part of the US
move MCX has not priced at 09:05, G2 follows the US move; 1 mini lot, 09:06 entry, 14:00 exit, 0.5 x ATR14 stop. Not
independent of R2, whose holdout covered this whole window.

| how much of the night the 09:00 gap prices (design) | corr(US move, MCX gap) | corr(leftover at 09:05, 09:05 -> 14:00) |
|---|---|---|
| GOLDM <- XAUUSD | 0.71 (139 days) | -0.02 (148) |
| SILVERM <- XAGUSD | 0.87 (119) | 0.09 (124) |
| CRUDEOIL <- WTI | 0.83 (153) | 0.57 (152; partly shared 09:05 noise) |
| NATURALGAS <- Henry Hub CFD | 0.19 (151; a weak proxy) | 0.01 (150) |

| cell | design trades, win, net (p, q) | holdout trades, net (p) | worst month design / holdout | fits 2% @ Rs 1L (design / holdout) |
|---|---|---|---|---|
| G1 GOLDM | 23, 43%, -Rs 41,877 (0.53, 1.00) | 23, +Rs 17,037 (0.20) | -Rs 38,121 / -Rs 12,920 | 0% / 0% |
| G2 GOLDM | 67, 60%, +Rs 1,13,304 (0.037, 0.70) | 64, -Rs 21,031 (0.38) | -Rs 1,287 / -Rs 40,910 | 0%/ 0% |
| G1 SILVERM | 25, 64%, +Rs 83,403 (0.20, 1.00) | 21, -Rs 9,006 (0.49) | -Rs 48,318 / -Rs 30,835 | 0%/ 0% |
| G2 SILVERM | 56, 57%, +Rs 65,688 (0.25, 1.00) | 62, +Rs 4,467 (0.35) | -Rs 38,733 / -Rs 29,818 | 0%/ 0% |
| G1 CRUDEOILM | 20, 50%, +Rs 5,002 (0.37, 1.00) | 13, +Rs 8,220 (0.34) | -Rs 1,143 / -Rs 2,624 | 100% / 46% |
| G2 CRUDEOILM | 58, 45%, +Rs 8,827 (0.084, 1.00) | 57, +Rs 6,707 (0.26) | -Rs 1,364 / -Rs 7,805 | 86% / 40% |
| G1 NATGASMINI | 9, 44%, +Rs 8,164 (0.27, 1.00) | 12, -Rs 2,176 (0.71) | -Rs 2,577 / -Rs 1,165 | 22% / 100% |
| G2 NATGASMINI | 29, 69%, +Rs 73,995 (0.003, 0.23) | 68, +Rs 3,249 (0.013) | -Rs 218 / -Rs 2,963 | 31% / 100% |

- Design: G2 GOLDM fails without its top 5 trades (-Rs 63,933); G2 NATGASMINI has 29 trades and most of its profit is
  the late-January 2026 gas spike (two trades +Rs 28.6k and +Rs 19.4k; 1 of 4 folds positive).
- Holdout: pooled +Rs 7,467 on 320 trades, about Rs 23 a trade. G2 NATGASMINI kept the right sign (p 0.013) but made
  +Rs 3,249 on 68 trades: not worth a lot of risk. Mini crude is the only book that fits 2% and earns +Rs 6-8k a
  half-year, not significant.
- R2 said it: the opening gap prices the night; from a tradable 09:05-09:06 entry little is left.
- Data that would sharpen it: exchange COMEX / NYMEX minute prints (CME DataMine, paid) and the onshore USD/INR open.

## What would need new data (plainly)

| idea | missing | where |
|---|---|---|
| 1 | years of next / far-month futures | Dhan `expiryCode 1/2` continuous daily since 2012, or expired-contract minutes (live token); far-month bid/ask |
| 2 | point-in-time MCX iCOMDEX values and index-future prints | MCX historical data (manual download; scripts refused), Dhan with a live token; but the futures barely trade |
| 3 (out of scope) | the full option chain (ATM+/-10) with bid/ask | Dhan rolling options at wider offsets, or a quote recorder |
| 4 | NCDEX agri history | NCDEX bhavcopies (not in the lake) |
| 5 | exchange-timestamped COMEX / NYMEX prints, onshore USD/INR at 09:00 | CME DataMine (paid); NSE currency futures minutes |

Nothing passes, so there is no APP_SPEC. The arms stay as they are; nothing goes live; nothing sells options.
