# R93 pre-registration: does Breadth Donchian (or plain Donchian) work on MCX commodities? (21 Oct 2026)

Boss, 21 Oct: *"Just check if Breadth Donchian works on commodities or not."*
Research only: nothing in `android/**` or `.github/**` changes. Written and committed **before any return, P&L or
signal count was computed**. Done before this commit (data checks only, no returns): the download; the coverage of the
ten MCX option books (5 Aug 2025 - 19 Oct 2026, 309 sessions each, the near future's close on 1-minute rows); the share
of design minutes in which each book's 1-ITM call / put printed (section 2); the count of daily roll days found by the
open-interest rule per year against the known expiry calendar (section 1); a reachability check of Dukascopy.

## 0. What is already known (read, not repeated)

- **R72 (indices)**: Donchian 20 on 5-minute index bars, traded only when >= 4 of the 5 indices break the same way on
  the same bar, passed every gate (final 1,202 trades, +Rs 1,71,366, twin p 0.008). Confirmed Donchian breaks made
  +Rs 1,37,635 (752), unconfirmed lost -Rs 55,790 (1,029). R84: no per-signal position cap keeps its edge.
- **R51**: Donchian 20 (5m index, 1-ITM, stop -15%, 30 min) passed thinly. R66 added L25.
- **MCX so far**: R44 tested 5m Donchian 20 on CRUDEOIL options (Aug 2025 - Oct 2026): lost. R82 D0-D2 (daily
  Donchian 55/20 on gold / silver, with a both-metals agreement) lost in design. R83 E8 (daily Donchian 20 on crude /
  gas / copper with a cross-commodity trend filter): crude made money, the agreement filters added nothing (twin p
  0.23). R85, MB1, R53, M3, M4: costs decide on MCX; no MCX option-buying rule has passed.
- So R93's new questions are: (1) the **R72 rule itself** (same-bar breadth across a group) on MCX 5-minute bars with
  R72's exits; (2) whether **breadth sorts MCX Donchian breaks** the way it sorts index breaks; (3) a cross-market
  version; (4) the same on long daily history.

## 1. Data (never committed; the lake copy lives in $IRAALGO_LAKE, default ~/lake)

| series | what | span | used for |
|---|---|---|---|
| near future of each option book = per-minute median of `spot` in `options_rolling_1m/opt_<SYM>_MONTH_*.parquet` (R53 `Market`) | close-only minutes of the future under the nearest monthly option | 5 Aug 2025 - 19 Oct 2026 | intraday signals of GOLDM, SILVERM, CRUDEOILM, NATGASMINI, COPPER, ZINC |
| the same files, ATM-3..+3 option minutes | printed legs, R42 F1 bad-print filter (R53 `Market`) | same | option fills and exits |
| `fut_1m_mcx_continuous` (ALUMINIUM rows) + `fut_mcx_rolls` | the continuous near future, true minutes, volume-roll records | 24 Jul - 19 Oct 2026 | ALUMINIUM intraday (holdout only; it has no option book) |
| `fut_daily_continuous` (expiry_code 0) | near-month daily OHLC + OI, not back-adjusted | 2012 - 16 Oct 2026 | daily variants |
| Dukascopy free 1-minute BID candles: XAUUSD, XAGUSD, LIGHTCMDUSD (WTI), GASCMDUSD (Henry Hub), COPPERCMDUSD | point-in-time (each minute stamped at its open; known at its close) | 1 Jul 2025 - 19 Oct 2026 | variant (c) only |

- **Intraday bars**: 5-minute bars anchored at 09:00 IST from minute closes (close-only, R85 convention): open = the
  last minute close before the bar (the bar's first close on a day's first bar), high / low = max / min of that open
  and the bar's closes, close = the last minute close; a bar needs >= 3 of its 5 minutes. Session 09:00 to 23:30 (US
  summer time) / 23:55 (US winter time), `McxSession` rule. The app uses true OHLC bars; close-only highs and lows are
  a little narrower (R85: about 78% of the true 5-minute range on the minis), so breaks happen a little more often
  here than live. Stated with the results.
- **Intraday contract changes**: the option books' near future switches contract the session after the option expiry
  (R53, checked there). A Donchian channel must not span two contracts: a bar's channel is valid only if its previous
  20 bars all belong to the bar's own contract segment (R53 `Market.seg`, expiry days from the straddle jump; for
  ALUMINIUM the roll days of `fut_mcx_rolls`). A segment boundary therefore blanks the first 20 bars of the new
  contract (about 100 minutes, once a month).
- **Daily rolls**: a roll row = a day whose open interest is >= 1.5x the previous row's, keeping the largest jump in
  any 15-day window. Checked before this file (no returns): it finds 6 a year on GOLD, 11-12 on GOLDM, 4-6 on SILVER,
  5 on SILVERM, 10-14 on CRUDEOIL, 9-13 on NATURALGAS, about 12 on the base metals from 2020 (fewer before, when their
  contracts were bi-monthly), matching the exchange calendar. The daily channel is computed on a back-adjusted series
  (each roll row's open minus the previous close is treated as the roll gap and subtracted from all earlier prices);
  a held daily position is closed at the close before a roll row (`roll` exit).
- Known limits, stated up front: intraday history is **14.5 months** (309 sessions; design 160, holdout about 140);
  copper and zinc option books are too thin to trade (section 2); aluminium minutes exist only from 24 Jul 2026, so in
  design the metals group has 4 members with data; GOLD and SILVER option books are thin in Jan - May 2026 (MB1).

## 2. Groups, signal series and what is traded

"Minis count with their parent only once": each **family** is one member.

| group | family (member) | signal series (the underlying) | traded (1 lot) | why |
|---|---|---|---|---|
| metals | GOLD (GOLD, GOLDM) | GOLDM near future | **GOLDM option**, 1-ITM, 10 units / point | 1-ITM call printed in 20% / put 17% of design minutes |
| metals | SILVER (SILVER, SILVERM) | SILVERM near future | **SILVERM option**, 1-ITM, 5 units / point | 24% / 18% |
| metals | COPPER | COPPER near future | **COPPER future**, 2,500 kg | options thin: 1-ITM call 12% / put 7% |
| metals | ZINC | ZINC near future | **ZINCMINI future** priced on ZINC, 1,000 kg | options thin: 1% / 0% |
| metals | ALUMINIUM | ALUMINIUM continuous future | **ALUMINI future** priced on ALUMINIUM, 1,000 kg | no options listed |
| energy | CRUDE (CRUDEOIL, CRUDEOILM) | CRUDEOILM near future | **CRUDEOILM option**, 1-ITM, 10 units / point | 88% / 81% (the mini fits the account) |
| energy | NATGAS (NATURALGAS, NATGASMINI) | NATGASMINI near future | **NATGASMINI option**, 1-ITM, 250 units / point | 81% / 76% |

Rule fixed here: a book is traded as options when both its 1-ITM call and put printed in >= 15% of design minutes;
otherwise the future is traded (CLAUDE.md: trade the future where no liquid option exists). The signal is always read on
the underlying future; nothing reads an option's own chart.

Daily variants use the parent's near-month daily series (GOLD, SILVER, CRUDEOIL, NATURALGAS, COPPER, ZINC, ALUMINIUM:
the longest history; the minis share the price quote) and trade the future at the mini lot where one exists (GOLDM 10,
SILVERM 5, CRUDEOILM 10, NATGASMINI 250, COPPER 2,500, ZINCMINI 1,000, ALUMINI 1,000): there is no option history
before Aug 2025, so the daily study is **futures only** and says so.

## 3. Variants (5, declared; nothing else is tested)

Signal on a bar of a family: **up** = the bar's close > the highest high of the previous 20 bars; **down** = the close <
the lowest low of the previous 20 bars (R72 / `DonchianArm.sideAt`), channel valid as in section 1.

Intraday (5-minute bars):
- **I-A plain Donchian 20** (baseline, R51-style): every up / down bar of a family is a call / put (or long / short)
  signal for that family.
- **I-B complex breadth (the R72 rule on MCX)**: an up (down) bar of a family counts only if, **on the same bar**
  (same date and bar start), at least **M** families of its group are up (down), the family itself included.
  Metals: **M = 3 of the 5 families**; energy: **M = 2 of 2**. A family with no bar at that time (missing data, no
  data yet: aluminium before 24 Jul 2026) counts as not breaking; M stays absolute (as R72 and the app).
- **I-C cross-market**: an up (down) bar of a family counts only if its international driver's own 5-minute bar over
  the same IST minutes also closes above (below) its own previous-20-bar channel (built the same close-only way from
  the driver's minute closes). Drivers: GOLD - XAUUSD, SILVER - XAGUSD, CRUDE - WTI, NATGAS - Henry Hub, COPPER -
  COMEX copper. ZINC and ALUMINIUM have no free point-in-time driver: they take no I-C trades. USD/INR is not used (not
  a commodity driver, and it moves too little in 5 minutes to confirm a break). A missing driver bar = not confirmed.

Daily (one bar per session, back-adjusted channel):
- **D-A plain daily Donchian 20** per family.
- **D-B daily complex breadth**: as I-B on daily bars (metals 3 of 5, energy 2 of 2, same day).
- Daily cross-market (c) is **not** tested: the international daily close (New York) comes after the MCX close, so a
  same-day check would look ahead, and a lagged check is a different rule. Stated, skipped.

## 4. Trade-taking and exits (d: R72's own settings, moved to MCX hours)

Intraday, every variant:
- Fill: the signal is known at the bar's close; options are bought at the open of the first **printed** minute of the
  1-ITM contract (strike from the near future's close of the signal bar's last minute, R53 `Market.strike`) within
  3 minutes after the bar (R72 / R51 `mayFill` 2-minute slack); no print = no trade. Futures fill at the close of the
  first minute after the bar (close-only series) plus the half-spread.
- Entry window: fills from 09:10 (so the 09:00 bar never trades, as R72's 09:15 bar) to the session close - 20 minutes
  (23:10 summer / 23:35 winter; R72: 15:10 = close - 20). Square-off: close - 15 minutes (23:15 / 23:40; R72 15:15).
- Never on the traded option's expiry day (R53 detected expiry days; R72: never on the index's own expiry day).
  Futures trades: no expiry-day rule (intraday, flat before the close).
- One open position a side per family; at most 2 entries a side a day per family (R72 / `mayTake`). 1 lot.
- Option exits = **R72's XL**, exactly: stop -15% on the premium (on the printed minute's low; a gap through fills at
  the worse open); L25 (+5% locked once the premium's high is +25%); the 30/60/100 ladder (breakeven + charges once
  +30%, +30% once +60%, +60% once +100%); no fixed target; out after 30 minutes (the close of the 30th minute from the
  fill, else R53 `price_exit`); square-off.
- Futures exits (no premium exists): R72's index stop (exit set XI): out when a minute close is back through the
  signal bar's channel mid ((high + low) / 2 of the 20-bar channel), filled at that close with stop slippage; out after
  30 minutes; square-off.

Daily: the signal at a day's close; filled at the next day's open; initial stop 2 x ATR14 below / above the entry
(ATR of the signal day, true range on roll rows = high - low), then a chandelier trailing lock at 3 x ATR14 from the
best close since entry (never loosening; it protects open profit); stops are checked on closes and filled at that
close with stop slippage; out after 10 sessions at the close; out at the close before a roll row. One position per
family, a new signal while in a position is ignored.

## 5. Costs (real charges and slippage)

- Options: Zerodha MCX option charges (R53 `mcx_opt_charges`: Rs 40 brokerage, CTT 0.05% on the sell, exchange
  0.0418%, SEBI, stamp, GST) + a half-spread on the premium at entry and at exit: GOLDM, SILVERM, CRUDEOILM 0.30% from
  17:00 IST / 0.60% before; NATGASMINI 0.45% / 0.90% (R85 / R83 values for their books).
- Futures: Zerodha MCX futures charges (R53 `mcx_fut_charges`) + a half-spread of 0.03% crude, 0.06% gas, 0.05% gold,
  silver, copper, zinc, aluminium (R83 values; gold / silver as copper) at entry and exit, + 0.02% extra on stop exits.
- Every gate is also reported at **twice** the half-spreads (gate 7).

## 6. Split, walk-forward, locked holdout

- Intraday: **design 5 Aug 2025 - 31 Mar 2026**; walk-forward folds inside design (R85's): Nov-Dec 2025, Jan-Feb 2026,
  Mar 2026. **Locked holdout 1 Apr 2026 - 19 Oct 2026**, read once.
- Daily: **design 2012 - 2022**; walk-forward folds 2019, 2020, 2021, 2022; **locked holdout 2023-01-01 - 2026-10-16**,
  read once (in the same single read as the intraday holdout).
- No parameter is tuned: the 5 variants are fixed. Walk-forward = each fold's out-of-sample net of each variant, and a
  selection test: in each fold the variant (within its timeframe) with the best net from the design start to the day
  before the fold is traded in the fold; the selection's out-of-sample sum is reported.
- Lock: `results/HOLDOUT_READ` is written by the holdout run; a second holdout run raises. The holdout overlaps the
  final / holdout windows of R44, R53, R83, R85, MB1 (MCX) and R82 / R83 (daily). None of them ran I-B or I-C; R44
  ran a 5m Donchian on CRUDEOIL options (different exits, ATM, the large lot) over the whole intraday period, and
  R82 / R83 ran daily Donchian rules (different exits, filters) on gold, silver, crude, gas and copper. Disclosed: the
  holdout is not virgin for the plain baselines.

## 7. Twins, multiple testing and gates (as R72 / R51, never relaxed)

- **Random-entry twins**: for every trade of a variant, a twin trade on the **same day, same family, same side, same
  instrument and exits**, entering at a random 5-minute bar close inside the entry window (daily: a random session of
  the same calendar year, same side). 500 twin sets, seed 93. p = (1 + #twin totals >= the variant's net) / 501.
- **Benjamini-Hochberg** across the 5 variants on the holdout twin p.
- A variant **PASSES** only if all hold:
  1. holdout net > 0 after all costs;
  2. >= 40 holdout trades;
  3. twin p < 0.05 and BH q <= 0.10;
  4. design net > 0 and the walk-forward out-of-sample sum > 0;
  5. holdout net without its 5 best trades > 0;
  6. no single family carries it: the holdout net without its best family > 0;
  7. holdout net at twice the half-spreads > 0.
- **Does breadth help?** (Boss's question, descriptive): I-A trades split into those I-B confirms (an I-B signal on
  the same family, side and bar) and those it does not; trades, win rate, net per trade and net per side, per group,
  design and holdout. Breadth "helps" if confirmed breaks beat unconfirmed per trade in design AND holdout AND I-B
  passes the gates. Same split for I-C and for D-A / D-B.
- Reported per variant, per family and per group: trades, win rate, average win / loss, net after costs, worst day,
  worst month and the months below the account stop (-Rs 13k a month), max drawdown vs Rs 40k, a sample size with
  every number.
- If a variant passes: an APP_SPEC for a **paper-only** MCX arm (logged in the Autopilot change timeline, one-tap undo,
  behind a switch off by default for anything live; nothing goes live). If none passes: say so, and say whether the
  sample is too short to know (the minimum holdout trades and the size of effect it could detect).

## 8. Look-ahead guard and tests (before any read)

- Every signal function is pure and causal: deleting or perturbing every row after a cut leaves all signals at or
  before the cut unchanged (bars, channels, breadth counts, driver confirmations, daily back-adjustment and rolls).
  Canary: a deliberately leaky signal (next bar's close) must be caught.
- Unit tests: bar building (close-only, >= 3 minutes, 09:00 anchor, session close by US time), the channel and the
  segment rule, breadth M-of-N with missing members, minis counted once, the driver confirmation, the XL exit engine
  (stop, gap fill, L25, ladder, breakeven, time exit, square-off), the futures exits, the daily rolls and stops, the
  costs, caps, the entry window, expiry skip, twins, BH, the gates and the holdout lock.
