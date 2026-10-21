# R26 pre-registration: Boss's "OI wall blast" hero-zero framework, on every expiry of the Dhan lake

Written 14 Oct 2026, BEFORE any trade, trigger or outcome of R26 was computed. Only the expiry calendar (day counts) had
been read. Committed and pushed on its own. After that commit nothing here changes; a change needs a new entry and a new
pre-registration.

Boss's request (14 Oct 2026): a specific, mechanical "hero-zero" framework found online, to be tested properly. It is
different from R21 / R25 (near strikes at Rs 15-30, a call-wall break with call-OI unwinding), so it gets its own fair
test. R25 found that jackpots come with strike OI *rising*, not falling, and that 0 of 18 hero rules pass.

## Why every expiry is out-of-sample

Every parameter of the rule comes from the external text (Boss's message), not from fitting by us. Where the text is
vague, the most natural reading is fixed here, with at most one alternative reading per vague point, before any outcome
is seen. So the whole lake is an out-of-sample test of a third-party rule. R25's split is still reported separately
(R25's design block 2023 – 12 Oct 2026; R25's holdout block 2021-22, which R25 read once for its own rules and which no
R26 rule has seen), and every year on its own.

## Data (R25's loader, unchanged; `research/hunt/r25/r25data.py`)

- Drive `IraAlgo-data-lake`: `options_rolling_1m/opt_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY}_WEEK_<YEAR>` (the nearest expiry,
  ATM-10 .. ATM+10 strikes chosen at each minute; open, high, low, close, volume, OI, IV) and
  `index_1m_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY,INDIAVIX}_<YEAR>`.
- Expiry days from the data (R25's rule: ATM time value at 15:15 < 0.6% of spot and the next session's > 2× it).
  BANKNIFTY weeklies to 13 Nov 2024, FINNIFTY weeklies to 19 Nov 2024; later ones are monthlies, out of scope.
- Day counts (calendar only): NIFTY 271 (5 Aug 2021 – 6 Oct 2026), BANKNIFTY 172 (5 Aug 2021 – 13 Nov 2024), SENSEX 175
  (19 May 2023 – 8 Oct 2026), FINNIFTY 152 (26 Aug 2021 – 19 Nov 2024; the detector misses some 2021-22 FINNIFTY weeks
  where the lake is thin). **770 expiries.** R25's 2021-22 block: NIFTY 74, BANKNIFTY 74, FINNIFTY 53 (201).
- R25's data fixes apply: mislabelled rows dropped, freak prints capped (same-minute rule), targets fill only on a
  confirmed price.
- **13 Oct 2026 is not in the lake** (it ends Mon 12 Oct 2026). It is described from R25's notes only.
- **The chain is ATM±10 only.** The wall is the highest-OI strike among the stored strikes. When that strike is the
  outermost stored strike on its side, the true wall may lie outside the range; how often that happens is reported.
- **Volume:** the index files carry no traded volume and the lake's index-futures minutes cover only recent months, so
  the volume used is the option chain's: the sum of the traded volume of every stored option (both sides) per minute.

## Grid and point-in-time reads

- Row t is the 1-minute bar that starts at 09:15 + t and closes at t + 1. Times below are bar CLOSE times. A decision at
  row t reads rows ≤ t only and acts at row t + 1 (the next bar's open). The look-ahead guard (future blanked, future
  absurd) must give identical signals.
- **OI of a strike at row r:** its last OI > 0 in rows ≤ r of the same day (NaN: none yet).
- **The stored chain at row r:** strikes with a bar in rows r-4 .. r.
- **Call Wall at row r:** the stored strike with the highest call OI at r (tie: nearest spot). **Put Wall:** the same
  with put OI. Current (nearest) expiry only.

## The rule, primary reading (P)

**Window:** triggers at bar closes 13:45 .. 14:30. **One trade per index per expiry: the first minute at which every
condition (and the price filter) holds.**

**UP (short-covering blast), at row t:**
1. **Wall break held > 15 minutes.** The Call Wall W is read at the 13:00 close (anchored). Spot's 1-minute closes at
   rows t-15 .. t (16 closes) are all > W, AND spot closed ≤ W at some row from 13:00 to t-16 (the move above happened
   after the wall was read).
2. **Wall OI unwinding.** W's call OI at t ≤ (1 − θ) × its highest call OI from 12:00 to t. θ = 15% NIFTY and SENSEX
   (SENSEX is a broad index like NIFTY), 25% BANKNIFTY and FINNIFTY (the text: FINNIFTY tracks BANKNIFTY).
3. **Volume spike.** The chain's volume over the last 15 minutes (rows t-14 .. t) ≥ 2 × the session's average
   15-minute volume up to then (the chain's volume over rows 0 .. t × 15 / (t + 1)).
4. **Price rising with call change-in-OI negative.** Spot close at t > spot close at t-15, AND the summed call OI of the
   strikes stored at both t and t-15 is lower at t than at t-15.

**DOWN (mirror):** the Put Wall at 13:00; 16 closes < W after a close ≥ W since 13:00; W's put OI down ≥ θ from its
peak since 12:00; the same volume spike; spot falling and summed put OI falling over 15 minutes.

**Strike:** ATM = spot at t rounded to the strike step (NIFTY / FINNIFTY 50, BANKNIFTY / SENSEX 100). UP buys the
ATM+1 strike CE, DOWN the ATM-1 strike PE. The option must have traded in the last 5 bars; its last close is the signal
price.

**Price filter ("premium Rs 15-30 and one strike from spot"):** applied in P. The signal price must be Rs 15-30;
otherwise that minute does not qualify (the next minute in the window may).

**Size:** Rs 2,000 a trade (2% of a Rs 1,00,000 account). Lots = floor(2,000 / (fill × lot)); when one lot costs more
than Rs 2,000 the trade is 1 lot and its real risk is reported. The lot is the one in force that day.

**Exit (P): E2 — target 4× the fill, else the 15:05 square-off.** The text says "it goes to 4x or to zero" and gives no
stop; the most a ticket can lose is the premium plus charges.

**Costs and fills:** R25's, unchanged. Rs 20 an order, STT 0.15% of the sell, exchange (NSE / BSE), SEBI, stamp 0.003%
of the buy, GST 18% (`obuy.costs.Costs("app")`). Buys at the next bar's open + 5 bps + a 1 / 2 / 4-tick half-spread by
the strike's 5-minute volume; sells the mirror. The entry is a limit at 1.2 × the signal price + 0.10, worked for 3
minutes, never chased. A target fills only when the high trades a tick through it AND the bar's close or the next open
is at or above it. Worthless at the exit: no sell order. Out-of-range prices: R25's proxy, and every rule is repeated
with them at 0.

## Alternative readings (at most one per vague point; each changes ONE thing from P)

| id | vague point | alternative |
|---|---|---|
| A1 | when the wall is read | **rolling wall**: W is the wall read at row t-16, and spot's close at t-16 is ≤ W (a fresh break held 16 closes) |
| A2 | the OI reference | W's OI at the 12:00 close instead of its peak since 12:00 |
| A3 | the price filter | not applied (any ATM±1 price) |
| A4 | the exit | E1: hold to 15:05 (the app's expiry square-off) |
| A5 | the exit | E1b: hold to 15:20 |
| A6 | the exit | E3, Boss's style: sell half at 2×, then step-lock the rest (best high 2× → lock 1×, 3× → 2×, 4× → 3×, …: a minute close at or below the lock sells at the next open), else 15:05. With 1 lot the 2× sells all (whole lots only) |
| A7 | BANKNIFTY strike | ATM+2 CE / ATM-2 PE (BANKNIFTY only; compared with P's BANKNIFTY trades) |
| A8 | SENSEX trigger | the "wall" is the nearest 1,000-point level above spot at 13:00 (UP) / below (DOWN); the OI conditions read that strike (SENSEX only) |
| B1 | baseline (b) | P WITHOUT the OI conditions (2 and the OI half of 4): does the OI part add anything? |

## Controls

- **Random-time twins (every rule):** 1,000 twin sets. Each trade is replaced by an entry on the same expiry, same
  direction, at a uniformly random bar close 13:45 .. 14:30, with the rule's strike rule, price filter (up to 20 draws to
  find a minute where the option is tradable and passes the filter), size and exit. p = the share of twin sets whose net
  ≥ the rule's net.
- **Baseline (b):** B1 above, plus the trades B1 took that P did not.
- **Baseline (c):** R25's T1 and the app's Hero rule (H0), quoted from R25 on its 618 expiries (NIFTY / BANKNIFTY /
  SENSEX); not re-run.

## Reporting (every rule)

Trigger days / expiries; trades; hit rate (net > 0), share reaching ≥ 2× and ≥ 4× (confirmed high before the exit);
average win and loss; net Rs after charges and spread; per expiry; per Rs 1,000 risked; % green expiries; worst day;
max drawdown; longest losing streak; the best day's share and the net without the best day; 2× spread; out-of-range at
0; p vs random twins; BH q. Split by index, by direction, by year and by R25 block (2021-22 / 2023-26). Real risk of the
1-lot trades above Rs 2,000.

## Gates (a rule passes only if ALL hold)

1. ≥ 30 trades on all expiries.
2. Net > 0 on all expiries, after costs.
3. Net > 0 in BOTH R25 blocks (2021-22 and 2023 – Oct 2026).
4. BH q < 0.05 (random-time twin p) across **R21's 9 + R25's 9 + R26's 10 = 28** p-values.
5. Net > 0 at 2× the half-spread, and with out-of-range prices at 0.
6. Net > 0 without the single best expiry day.
7. Max drawdown smaller than Rs 40,000 (Boss's account stop).

## Descriptive check of the claim itself (all expiries; no gate)

- **Walls:** the share of expiry-sides whose 13:00 wall is the outermost stored strike (the true wall may be outside).
- **Breaks:** on how many expiries spot broke the call / put wall in 13:45-14:30; held 16 closes; with the OI fall; with
  the volume spike; the full trigger.
- **Blasts and OI:** per expiry and side, the ATM+1 CE / ATM-1 PE at the 13:45 close. A BLAST is a confirmed high before
  15:05 ≥ 4× its 13:45 close. For blasts, the wall's OI change from 13:45 to the minute the 4× was first reached
  (falling by ≥ θ, falling less, rising), and the bought strike's own OI change; the same for non-blasts from 13:45 to
  15:00. This links to R25's finding (jackpots come with OI rising).

## If a rule passes

Paper only, per CLAUDE.md: a labelled, logged, one-tap-undoable change to a paper arm, shown to Boss, and only after
Boss's go-ahead. Never live by itself. R26 changes no app code. If nothing passes: no app change; the shadow forward log
is described (not built).
