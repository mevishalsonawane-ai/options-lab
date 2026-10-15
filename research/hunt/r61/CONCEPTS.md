# What Solo knows: the concepts behind option trading, and what our research found

R61 Part 1 (2026-10-15). Generated from `research/hunt/r61/r61concepts.py` (also written as `concepts.json` for the app); edit the Python, then run it. Plain English, one section per concept.

**House rule:** every entry and exit signal is read from the underlying (the index), and the option is then chosen by the strike rule. Option-chain data (OI, PCR, IV, skew, GEX, max pain) may only be context or a filter around that signal. A rule whose signal comes from the option chain is non-default, labelled, and switched on only by Boss.

**The short version of what our studies found:** across every option-chain, volatility and positioning concept below, nothing passed as a trade rule or as a filter on the arms. Volatility, IV, VIX, ATR and GEX predict how BIG the next move is, not which WAY. Event days move more. That is why Solo uses them only as context: to skip dear or risky trades, never to pick a side.

## Open interest (OI) and OI change

Also called: OI, change in OI.

**What it is.** The number of option (or futures) contracts still open at a strike: every contract has one buyer and one seller, so OI counts positions, not trades. OI rises when new positions open and falls when they close.

**How it is computed.** Read directly from the chain for each strike and side. R61: near-the-money OI = call + put OI at the 5 strikes nearest the money (log10); OI change = % change of call + put OI at the 11 nearest strikes since the day's first chain minute; 'writer tilt' = put OI % change minus call OI % change over 30 minutes.

**On the phone.** yes: ChainAnalytics.oiData / oiProfile / oiChange from the live chain (Dhan or Zerodha).

**How traders use it.** Traders read rising OI as fresh conviction and falling OI as positions being closed. Big call-OI additions are read as call writing (a ceiling), big put-OI additions as put writing (a floor).

**Pitfalls.**

- OI is published with a lag (NSE updates it every few minutes), so minute-level OI changes are stale.
- You cannot tell from OI alone whether the buyer or the seller opened the position.
- Lot-size changes and expiry roll-offs move OI without any view behind it.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| OI_FILTER (`research/OI_FILTER.md`) | BANKNIFTY, two years (Feb 2024-Feb 2026), 145 arm x check results | No OI or volume check improved the Liquidity arm in both years; the ORB 'volume surge' kept 3% of trades and never reached t = 2. | failed |
| H26 (`research/HUNT_H26.md`) | 2,268 variants, five indices | Put-minus-call OI change had a 15-minute IC of about 0.03, too small to pay costs (holdout -Rs 45 a day). The filter 'skip when the 15-minute OI build-up opposes the trade' (BU15): holdout +Rs 81 a day on 876 trades, p 0.035, pre-holdout q 0.13. | failed (BU15 near-miss, paper-log only) |
| R15 F4 (`research/HUNT_R15_DAILY_FILTERS.md`) | Liquidity arm days | Futures OI rising as a daily filter: -Rs 9.4 a day, q 0.50. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `oi_near_log`, `oi_near_chg_pct`, `writer_tilt30`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'oi level change' inputs from the all-inputs model changed walk-forward AUC by +0.0007 (design) / +0.0011 (final) and net by Rs +11,103 / Rs +1,225. Each input added alone to A2's 29 changed AUC (design / final): `oi_near_log` -0.0010 / -0.0012, `oi_near_chg_pct` +0.0005 / +0.0002, `writer_tilt30` +0.0000 / -0.0008. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## OI build-up types

Also called: long build-up, short build-up, short covering, long unwinding.

**What it is.** Price change and OI change read together: price up + OI up = long build-up (new buyers); price down + OI up = short build-up (new sellers); price up + OI down = short covering (sellers closing); price down + OI down = long unwinding (buyers closing).

**How it is computed.** R61: the index's 30-minute change and the 30-minute change of call + put OI at the 11 strikes nearest the money: +2 long build-up, +1 short covering, -1 long unwinding, -2 short build-up, 0 if either is flat. Classic build-up uses FUTURES OI; the lake has index-futures minutes only from Jul 2026, so R61 uses near-the-money option OI instead.

**On the phone.** yes (option OI version); futures build-up from the futures quote stream (recorded from Jul 2026).

**How traders use it.** A trend is trusted when it comes with build-up on its side (long build-up for up moves) and doubted when it is only covering or unwinding.

**Pitfalls.**

- It labels the past 30 minutes; it is a description, not a forecast.
- Option OI mixes calls and puts and both buyers and writers; the labels come from futures.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| IV1 O3 (`research/HUNT_IV1_OPTION_MARKET.md`) | 5 indices, 7,339 trades | A fixed 'smart money' rule (puts written, calls covered, PCR rising) lost on all 5 indices (-Rs 24 to -245 a trade). | failed |
| H38 (`research/HUNT_H38.md`) | 1,080 intraday variants (43 days) + 1,800 daily variants | Futures build-up: lowest q 0.54; nothing survives. | failed |
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen, 5 indices | Put long-unwinding share: 15-minute direction IC +0.029 ('barely'). | weak |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `buildup`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'buildup' inputs from the all-inputs model changed walk-forward AUC by -0.0009 (design) / +0.0003 (final) and net by Rs +17,993 / Rs +4,651. Each input added alone to A2's 29 changed AUC (design / final): `buildup` +0.0017 / +0.0007. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Put-call ratio (PCR), on OI and on volume

Also called: PCR, PCR OI, PCR volume.

**What it is.** Put OI divided by call OI (or put volume / call volume). Above 1: more puts than calls.

**How it is computed.** R61: put / call OI at the 11 strikes nearest the money (A2 already uses this level); its change over 30 minutes; put / call volume summed since 09:15; and the rank of today's OI ratio among the last 5 and 20 sessions at the same minute.

**On the phone.** yes: ChainAnalytics.pcr (OI and volume); the ranks need the phone's own stored history of past sessions.

**How traders use it.** Read both ways: as sentiment (many puts = fear, a contrarian buy) or as writing (many puts written = a floor, bullish). Extremes versus the index's own history matter more than the raw level.

**Pitfalls.**

- The same number is read bullish by one school and bearish by another.
- The ratio depends on which strikes are included and on the expiry; compare like with like.
- Near expiry, OI shifts fast and the ratio swings without meaning.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R47 C4 (`research/HUNT_R47_RELAXED_OPTION_BUYING.md`) | BANKNIFTY; holdout 91 trades; Nov 2024+ final 55 trades | 'PCR in its top fifth of 60 sessions at 10:15 -> buy a call' (and bottom fifth -> put): +Rs 865 a trade, p 0.0499 but q 0.50; Nov 2024+ final +Rs 1,125 a trade, q 0.76; design profit faded from +1,766 a trade (2022) to +9 (2025). | near-miss, note only |
| R15 F3 (`research/HUNT_R15_DAILY_FILTERS.md`) | Liquidity arm days | PCR extremes as a daily filter: -Rs 19.4 a day, q 0.61. | failed |
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | PCR level and change: noise for direction (15-minute IC about +0.026). | failed |
| R57 A4 (`research/HUNT_R57_SOLO_2.md`) | final 121 trades | A2-style model with C4 as an extra candidate passed (+Rs 59,706, p 0.002), but it is a non-default option-chain signal. | passed as a non-default variant only |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side. Using PCR AS THE SIGNAL (R47 C4) is non-default: an option-chain signal, Boss-only.

**Solo's inputs from it.** `pcr_oi (A2)`, `pcr_oi_chg30`, `pcr_vol`, `pcr_rank5`, `pcr_rank20`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'pcr' inputs from the all-inputs model changed walk-forward AUC by -0.0015 (design) / +0.0007 (final) and net by Rs -22,947 / Rs -59. Each input added alone to A2's 29 changed AUC (design / final): `pcr_oi_chg30` +0.0046 / -0.0007, `pcr_vol` -0.0014 / +0.0010, `pcr_rank5` -0.0019 / -0.0014, `pcr_rank20` -0.0034 / -0.0019. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Max pain

Also called: option pain, max pain strike.

**What it is.** The expiry price at which option holders, taken together, would get the least money: the strike that minimises the total payout on all open calls and puts.

**How it is computed.** For each chain strike X: sum over strikes K of call OI x max(X - K, 0) + put OI x max(K - X, 0); max pain = the X with the smallest total (ties: the lowest strike). R61: (max pain - index) / index x 100, signed by the trade's side, over the 21 strikes around the money.

**On the phone.** yes: ChainAnalytics.maxPain.

**How traders use it.** Folklore says the index is 'pulled' toward max pain into expiry because option writers defend it.

**Pitfalls.**

- It changes every minute as OI changes, and depends on the strike window used.
- It assumes writers can move the index; our data says they do not.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R16 (`research/HUNT_R16_PREDICTABILITY.md`) | NIFTY and BANKNIFTY expiries | As a forecast of the expiry close, max pain is worse than 'no change': error 0.74% vs 0.43% (BANKNIFTY), 0.58% vs 0.52% (NIFTY). | failed |
| EXPIRY_PLAYBOOK (`research/EXPIRY_PLAYBOOK.md`) | 53 NIFTY, 11 BANKNIFTY expiries | The close ended 0.58% from max pain vs 0.50% from the open; within 0.25% of it on only 25% / 27% of expiries. | failed |
| H18 (`research/HUNT_H18.md`) | intraday, several indices | No drift toward max pain or max OI (BANKNIFTY -10 bps, t -2.2: if anything, away). | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `maxpain_dist_pct`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'max pain' inputs from the all-inputs model changed walk-forward AUC by +0.0006 (design) / +0.0009 (final) and net by Rs +6,347 / Rs -6,258. Each input added alone to A2's 29 changed AUC (design / final): `maxpain_dist_pct` +0.0001 / -0.0057. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## OI walls (support and resistance from OI)

Also called: call wall, put wall, max OI strike.

**What it is.** The strike with the most call OI above the index (read as resistance) and with the most put OI below it (read as support).

**How it is computed.** R61: % distance from the index to the biggest call-OI strike at or above it and to the biggest put-OI strike at or below it (ties: the nearer one), within the 21 strikes around the money; expressed as 'wall ahead' (on the trade's way) and 'wall behind'.

**On the phone.** yes: ChainAnalytics.oiProfile (max call / put OI strikes).

**How traders use it.** Option buyers avoid buying calls just under a big call wall; breakouts 'through a wall' are chased.

**Pitfalls.**

- Walls move as OI shifts; a wall is only as strong as the writers' willingness to hold.
- Far walls just mean the market expects a wide range.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| OF1 (`research/HUNT_OF1_ORDERFLOW_INDIA.md`) | 873 expiries | Max-OI strikes were neither walls nor magnets: 0 of 12 tests; the max-OI strike was closer to settlement than a random same-distance strike on 42-43% of expiries (no-magnet level about 44%). | failed |
| R20b (`research/HUNT_R20B_SR_RANGE.md`) | 98 design + 57 holdout trades | OI walls as the breakout range: +1,745 design, -3,972 holdout; random levels did better in 6 of 7 variants. | failed |
| R26 (`research/HUNT_R26_OI_WALL_BLAST.md`) | 770 expiries, 15 trades | OI-wall blast: -Rs 3,851; best p 0.73, every q 1.00. 'The OI part adds nothing.' | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `wall_ahead_pct`, `wall_behind_pct`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'oi walls' inputs from the all-inputs model changed walk-forward AUC by +0.0000 (design) / +0.0014 (final) and net by Rs +5,745 / Rs -15,469. Each input added alone to A2's 29 changed AUC (design / final): `wall_ahead_pct` -0.0003 / +0.0003, `wall_behind_pct` +0.0000 / -0.0007. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Implied volatility (IV), ATM IV

Also called: IV, ATM IV.

**What it is.** The volatility that makes the Black-Scholes price equal the option's market price: how much movement the market is paying for. Higher IV = dearer options.

**How it is computed.** From the chain's IV column (the lake and the broker both give it per strike); ATM IV = mean of the at-the-money call and put IV. R61 adds its change since the day's first chain minute.

**On the phone.** yes: ChainAnalytics.ivSmile (ATM IV), broker IV per strike.

**How traders use it.** Option buyers prefer cheap IV (less to lose if IV falls) and fear 'IV crush' after events; sellers like high IV.

**Pitfalls.**

- A buyer can be right on direction and still lose when IV falls.
- IV is quoted per expiry; weekly IV near expiry is noisy.
- Bad ticks in thin strikes give absurd IVs (R61 removes them before use).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| VOLATILITY (`research/VOLATILITY.md`) | BANKNIFTY, 249 days | Day range rose with opening IV: 464 / 540 / 631 points by IV third (83 days each). Descriptive. | size only |
| H29 (`research/HUNT_H29.md`) | 16 variants + 12 filters | Buying when IV is cheap versus a realised-vol forecast: best -Rs 30 a day net, q 0.80; 0 of 12 filters adopted. | failed |
| R57 A2 (`research/HUNT_R57_SOLO_2.md`) | 128 final trades | In Solo 2.0, ATM IV is the largest negative weight (-0.21): dear options are skipped. | used as context in A2 |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `iv_atm (A2)`, `iv_chg_day`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'iv level rank' inputs from the all-inputs model changed walk-forward AUC by +0.0040 (design) / +0.0024 (final) and net by Rs +2,878 / Rs -5,810. Each input added alone to A2's 29 changed AUC (design / final): `iv_chg_day` -0.0023 / -0.0002. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## IV rank and IV percentile

Also called: IVR, IV percentile.

**What it is.** Where today's IV sits in its own past: IV rank = (IV - lowest) / (highest - lowest) over a look-back; IV percentile = share of past days with lower IV.

**How it is computed.** R61: ATM IV rank (min-max) among the previous 60 sessions at the same minute (needs 40 sessions; Boss's Nov 2024 data rule makes 252-day ranks too short to train on).

**On the phone.** partly: needs 60 sessions of the phone's own stored ATM IV; missing until then.

**How traders use it.** Buy options when IV rank is low, sell when it is high.

**Pitfalls.**

- One spike in the window squashes every later rank (min-max).
- A short look-back mixes event weeks with quiet ones.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 S18 (`research/HUNT_F1_FACTORY.md`) | 158 holdout trades | BANKNIFTY calls when IV percentile is high: +Rs 12,319, p 0.02-0.03 but q 0.28, and it lost in design. | failed |
| OBUY_GC (`research/OBUY_GC.md`) | straddles | Buying straddles at low IV percentile loses. | failed |
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | IV percentile / rank are good SIZE predictors (IC about +0.23-0.24), not direction. | size only |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `iv_rank60`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'iv level rank' inputs from the all-inputs model changed walk-forward AUC by +0.0040 (design) / +0.0024 (final) and net by Rs +2,878 / Rs -5,810. Each input added alone to A2's 29 changed AUC (design / final): `iv_rank60` -0.0070 / +0.0000. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## IV skew and smile

Also called: skew, smile, 25-delta skew, risk reversal.

**What it is.** How IV differs across strikes. Skew: out-of-the-money puts usually carry higher IV than calls (crash insurance). Smile: both wings dearer than the middle.

**How it is computed.** A2: put IV 3 strikes below minus call IV 3 strikes above (skew). R61 adds smile curvature = (put IV -3 + call IV +3) / 2 - ATM IV. 25-delta skew is NOT used: deltas of far strikes on a 21-strike chain are noisy; the +-3-strike version is the stated substitute.

**On the phone.** yes: ChainAnalytics.ivSmile.

**How traders use it.** Steep put skew = demand for protection (fear); flattening skew = calm. Some read rising call skew as upside speculation.

**Pitfalls.**

- Skew in points depends on the strike distance chosen and on IV level.
- Thin far strikes give unreliable IVs.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen, 5 indices | The one direction clue that pointed the same way everywhere: when puts are dearer, the index tends to rise over 30-60 minutes (IC +0.03 to +0.13 fit, +0.01 to +0.08 selection). Too small to pay costs. | weak lead |
| H29 (`research/HUNT_H29.md`) | 24 variants | Skew / synthetic-forward direction rules all lose (best -Rs 76 a day net). | failed |
| R30 M4 (`research/HUNT_R30_ALL_PARAMETERS.md`) | NIFTY, 77 trades | A rule leaning on skew: +Rs 277 a trade, q 0.109; near-miss, in a forward test. | near-miss |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `iv_skew (A2)`, `smile_curv`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'skew smile term' inputs from the all-inputs model changed walk-forward AUC by +0.0027 (design) / +0.0017 (final) and net by Rs -7,434 / Rs -5,429. Each input added alone to A2's 29 changed AUC (design / final): `smile_curv` +0.0002 / -0.0043. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## IV term structure

Also called: term structure, calendar IV.

**What it is.** IV of the next expiry versus a later one. Normally later expiries carry slightly higher IV (contango); near-term IV above far-term (backwardation) signals near-term stress or an event.

**How it is computed.** R61: NIFTY only, monthly-file ATM IV minus weekly-file ATM IV (0 when both are the same contract). For BANKNIFTY, FINNIFTY and MIDCPNIFTY there are only monthly expiries since 20 Nov 2024, so there is no second expiry in the lake to compare (missing).

**On the phone.** yes: ChainAnalytics.volSurface across expiries.

**How traders use it.** Backwardation warns of an event or panic; option buyers prefer the expiry whose IV is relatively cheap.

**Pitfalls.**

- Only exists while two expiries trade; the week of the monthly expiry collapses it.
- Weekly IV jumps around expiry days by construction.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| H29 (`research/HUNT_H29.md`) | NIFTY, weeklies vs monthlies | Monthly minus weekly ATM IV 'did not sort the realised move at all'. | failed |
| MCX_OPTIONS (`research/MCX_OPTIONS.md`) | crude oil | Crude term structure predicts nothing. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `term_slope`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'skew smile term' inputs from the all-inputs model changed walk-forward AUC by +0.0027 (design) / +0.0017 (final) and net by Rs -7,434 / Rs -5,429. Each input added alone to A2's 29 changed AUC (design / final): `term_slope` -0.0001 / +0.0015. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## India VIX

Also called: VIX, fear index.

**What it is.** NSE's index of NIFTY's expected volatility over the next 30 days, computed from NIFTY option prices (annualised %).

**How it is computed.** Streamed as an index (INDIAVIX). A2 uses the level and the change from the previous close; R61 adds its rank among the previous 60 sessions at the same minute.

**On the phone.** yes: VIX stream / 1-minute bars (MarketRecorder).

**How traders use it.** High VIX = bigger expected moves and dearer options; spikes mark fear, falling VIX calm.

**Pitfalls.**

- VIX is about NIFTY; other indices have their own IV.
- It says how big, not which way.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R15 F2 (`research/HUNT_R15_DAILY_FILTERS.md`) | 228 trades | Skipping low-VIX days cost -Rs 73.4 a day (q 0.77): low-VIX days were good for the Liquidity arm. | failed |
| SIDEWAYS_GUARD (`research/SIDEWAYS_GUARD.md`) | 2 x 249 days | VIX pointed the wrong way for ORB in one year (-8.9k) and the right way in the other. | failed |
| R16 / F1 (`research/HUNT_R16_PREDICTABILITY.md`) | range forecasts | VIX alone matches a model for the next day's and rest-of-day range (size IC +0.22). | size only |
| R57 A2 (`research/HUNT_R57_SOLO_2.md`) | 128 final trades | VIX is A2's largest positive weight (+0.22). | used as context in A2 |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `vix (A2)`, `vix_chg_pct (A2)`, `vix_rank60`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'vix rank' inputs from the all-inputs model changed walk-forward AUC by +0.0029 (design) / +0.0009 (final) and net by Rs -8,460 / Rs -8,525. Each input added alone to A2's 29 changed AUC (design / final): `vix_rank60` -0.0010 / +0.0003. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Delta

Also called: option delta, hedge ratio.

**What it is.** How much the option's price moves for a 1-point move in the index (0 to 1 for calls, 0 to -1 for puts); also roughly the chance of expiring in the money.

**How it is computed.** Black-Scholes: call N(d1), put N(d1) - 1, d1 = (ln(S/K) + (r + s^2/2) T) / (s sqrt T), from the contract's own IV; R61 uses r = 6.5% and T = (sessions to expiry + rest of today / 375) / 252.

**On the phone.** yes: ChainAnalytics / OptionMath Greeks.

**How traders use it.** Pick strikes (ITM = higher delta, behaves like the index), size positions, convert an index view into option points.

**Pitfalls.**

- Delta changes with the index (gamma), time and IV.
- Model delta with a wrong IV or T misleads near expiry.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | Delta as a direction feature: IC about +0.012 (noise). | context only; never tested as a rule |

**Role in Solo.** context: describes the contract Solo would buy (the strike rule is fixed by the setup).

**Solo's inputs from it.** `delta_abs`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'greeks' inputs from the all-inputs model changed walk-forward AUC by -0.0026 (design) / +0.0009 (final) and net by Rs +7,025 / Rs +18,533. Each input added alone to A2's 29 changed AUC (design / final): `delta_abs` +0.0016 / +0.0002. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Gamma

Also called: option gamma.

**What it is.** How fast delta changes as the index moves. High gamma (at the money, near expiry) = the option's exposure grows quickly in a move, both ways.

**How it is computed.** Black-Scholes gamma = pdf(d1) / (S s sqrt T); R61 reports it as the change in delta for a 1% index move (gamma x S x 0.01).

**On the phone.** yes: OptionMath Greeks.

**How traders use it.** Buyers like gamma for fast moves (expiry-day 'hero' trades); sellers fear it.

**Pitfalls.**

- Gamma explodes in the last hours of expiry; small index moves swing the premium wildly.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | Gamma near the money predicts move SIZE (IC about +0.15), not direction. | size only |
| R21-R37 hero family (`research/HUNT_R37_GAMMA_FLIP.md`) | 52 expiry-day hero rules (one BH family) | 0 of 52 pass. | failed |

**Role in Solo.** context: describes the contract Solo would buy.

**Solo's inputs from it.** `gamma_1pct`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'greeks' inputs from the all-inputs model changed walk-forward AUC by -0.0026 (design) / +0.0009 (final) and net by Rs +7,025 / Rs +18,533. Each input added alone to A2's 29 changed AUC (design / final): `gamma_1pct` +0.0038 / +0.0053. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Theta (time decay)

Also called: time decay.

**What it is.** How much value the option loses per day if nothing else changes. Always a cost to the buyer; biggest at the money and near expiry.

**How it is computed.** Black-Scholes theta per session (1/252 year); R61 reports it as % of the premium.

**On the phone.** yes: OptionMath Greeks.

**How traders use it.** Buyers want the move to come before decay eats the premium; time stops exist because of theta.

**Pitfalls.**

- Theta is not linear: the last day's decay is far bigger than the average day's.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| EXPIRY_PLAYBOOK (`research/EXPIRY_PLAYBOOK.md`) | 53 NIFTY, 11 BANKNIFTY expiries | On expiry day the ATM option keeps a median 11% (NIFTY) / 19% (BANKNIFTY) of its 09:20 time value at 14:00, and 2% / 4% at 15:00. Descriptive. | context |
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | Theta: size IC about +0.10. | size only |

**Role in Solo.** context: describes the contract Solo would buy.

**Solo's inputs from it.** `theta_pct_day`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'greeks' inputs from the all-inputs model changed walk-forward AUC by -0.0026 (design) / +0.0009 (final) and net by Rs +7,025 / Rs +18,533. Each input added alone to A2's 29 changed AUC (design / final): `theta_pct_day` -0.0012 / -0.0015. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Vega

Also called: IV sensitivity.

**What it is.** How much the option's price moves for a 1-point change in IV.

**How it is computed.** Black-Scholes vega = S pdf(d1) sqrt T / 100 per IV point; R61 reports it as % of the premium.

**On the phone.** yes: OptionMath Greeks.

**How traders use it.** Buyers of long-dated or ATM options carry more vega: an IV drop (after an event) hurts them.

**Pitfalls.**

- Weekly options have little vega; IV crush matters more for monthlies.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | Vega: direction IC about +0.01. | context only |

**Role in Solo.** context: describes the contract Solo would buy.

**Solo's inputs from it.** `vega_pct`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'greeks' inputs from the all-inputs model changed walk-forward AUC by -0.0026 (design) / +0.0009 (final) and net by Rs +7,025 / Rs +18,533. Each input added alone to A2's 29 changed AUC (design / final): `vega_pct` -0.0004 / -0.0005. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Gamma exposure (GEX) and the gamma-flip level

Also called: GEX, gamma flip, zero gamma.

**What it is.** An estimate of how much option dealers must buy or sell the index to stay hedged as it moves. If dealers are long gamma (positive GEX) they sell rallies and buy dips (calmer market); short gamma (negative GEX) they chase moves (wilder market). The flip is the index level where net GEX changes sign.

**How it is computed.** Net GEX = sum over strikes of (call gamma x call OI - put gamma x put OI) x S^2 x 0.01, assuming dealers are long calls and short puts (the convention R28 used). R61: net / gross (between -1 and +1), its sign, and the % distance to the level (within +-3%) where net GEX, re-priced, changes sign.

**On the phone.** yes: ChainAnalytics.gex / gammaDensity (note: the phone prices from the forward; a port must match R61's definition exactly).

**How traders use it.** Positive GEX: fade moves, expect ranges. Negative GEX / below the flip: expect trends and bigger moves.

**Pitfalls.**

- In India we do not know who holds which side; the sign convention is an assumption (OF1: clients and Pros are net option writers).
- GEX is strongly tied to IV (R36: correlation -0.69), so it mostly repeats what IV says.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R28 (`research/HUNT_R28_GEX.md`) | lake Aug 2021-Oct 2026, 5 indices | Negative GEX at 10:00 -> somewhat bigger rest-of-day range (BANKNIFTY 0.87% vs 0.75%); 0 of 10 arm filters passed; on the Night arm it cut the holdout from +249 to -18 a day. | size only; failed as a filter |
| R36 (`research/HUNT_R36_GAMMA_EXPOSURE.md`) | 341 expiries; 264 traded | Correlation 0.33 with the afternoon move, but it is an IV effect; trades 0 of 4 pass, -Rs 1,03,781. | failed |
| R37 (`research/HUNT_R37_GAMMA_FLIP.md`) | 446 expiries, 42 trades | Trading the flip: -Rs 40,003, worse than 99.4% of random entries; 0 of 4 pass. | failed |
| R9 (`research/HUNT_R9_VOLUME_GAMMA.md`) | 28 filters | GEX sign flipped between periods; 0 of 28 pass. | failed |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `gex_norm`, `gex_sign`, `flip_dist_pct`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'gex flip' inputs from the all-inputs model changed walk-forward AUC by +0.0021 (design) / +0.0008 (final) and net by Rs -13,036 / Rs -808. Each input added alone to A2's 29 changed AUC (design / final): `gex_norm` -0.0016 / -0.0035, `gex_sign` +0.0000 / -0.0030, `flip_dist_pct` -0.0004 / -0.0007. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Dealer positioning

Also called: who is short gamma, writer positioning.

**What it is.** Which group holds which side of the options book; GEX depends on it.

**How it is computed.** Not observable directly. NSE's participant-wise OI (client, DII, FII, Pro; daily, after 18:30) is the closest public proxy.

**On the phone.** partly: MarketRecorder records the participant file daily; intraday positioning is not available.

**How traders use it.** Traders assume writers (dealers) hedge and so pin or push the index.

**Pitfalls.**

- Indian clients and Pros, not 'dealers', are the net writers (OF1: 97% / 92% of days).
- Daily file only, a day late for intraday decisions.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| OF1 (`research/HUNT_OF1_ORDERFLOW_INDIA.md`) | 3,632 days (2012-2026) | Follow-the-group signals: 15 of 36 passed on 2012-2025, then 0 of 15 held in the last 12 months (13 flipped sign). | failed |
| MARKET_DRIVERS (`research/MARKET_DRIVERS.md`) | review | 'GEX sign / flip decides trend': inconsistent in our data. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**R61 result.** No R61 input: positioning is not observable intraday; participant OI (its proxy) is measured under 'FII / DII flows and participant-wise OI'.

## VWAP

Also called: volume-weighted average price.

**What it is.** The average price of the day weighted by volume: where most of the day's trading happened.

**How it is computed.** sum(price x volume) / sum(volume) from the open. The index itself has no volume, so A2 uses the typical-price average of the session ('dist_tpavg_pct') as the index proxy; true VWAP needs futures volume (futures minutes from Jul 2026 in the lake).

**On the phone.** partly: futures VWAP from the futures stream; the index has no volume.

**How traders use it.** Above VWAP = buyers in control; pullbacks to VWAP are bought in up-trends.

**Pitfalls.**

- On an index without volume, 'VWAP' is a different number in every app.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| TREND_FILTER (`research/TREND_FILTER.md`) | BANKNIFTY 249 days | 9 EMA + VWAP + HH/HL: every version loses or is flat. | failed |
| H46 (`research/HUNT_H46.md`) | 2,400 variants | 'VWAP reject': best q 0.12; primary -Rs 1,505 a day in the holdout. | failed |
| F1 C1/C2 (`research/HUNT_F1_FACTORY.md`) | 3,891 + 2,256 trades | EMA + Supertrend + VWAP lost Rs 2.5-3.3 lakh in design and holdout. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `dist_tpavg_pct (A2)`.

**R61 result.** Not a new R61 input (A2 already has the typical-price proxy `dist_tpavg_pct`); R61 did not test it again.

## ATR (average true range)

Also called: ATR14.

**What it is.** The average daily (or bar) range including gaps: the index's normal move size.

**How it is computed.** True range = max(high - low, |high - previous close|, |low - previous close|); ATR14 = mean of the last 14 completed days. A2 uses daily ATR14 as % of price; SoloMidday's stops are in ATR.

**On the phone.** yes: from index candles.

**How traders use it.** Scale stops and targets to the market's current movement.

**Pitfalls.**

- Lags: after a quiet spell a sudden move is far bigger than ATR.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| F1 (`research/HUNT_F1_FACTORY.md`) | feature screen | ATR is the strongest SIZE predictor (IC +0.335); direction is noise. | size only |
| R20 / R17 (`research/HUNT_R20_RANGE.md`) | ORB and stop studies | ATR-adaptive ranges and range-scaled stops were no better. | failed |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `atr14d_pct (A2)`.

**R61 result.** Not a new R61 input (A2 already has `atr14d_pct`); R61 did not test it again.

## Opening range

Also called: ORB, opening range breakout.

**What it is.** The high and low of the first minutes of the session (15 or 30); a break above or below is read as the day's direction.

**How it is computed.** High / low of 09:15-09:29 (R61's S4) or 09:15-09:44 (A2's 'or30_state').

**On the phone.** yes: from index minutes.

**How traders use it.** Buy the breakout side, stop at the other side.

**Pitfalls.**

- Most breaks fail or reverse in a range day; costs eat the small wins.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R3 (`research/HUNT_R3_ORB_FAMILY.md`) | 2,489 trades, Aug 2021-Oct 2026 | ORB: -Rs 2,23,040; 0 of 6 years profitable; 0 of 16 versions pass. | failed |
| R49 (`research/HUNT_R49_ORB_RECENT.md`) | Nov 2024+ | Still loses. | failed |
| R57 (`research/HUNT_R57_SOLO_2.md`) | final 459 trades | S4 (15-min ORB) unfiltered: -Rs 1,14,070. | failed |

**Role in Solo.** a candidate setup (S4, signal on the index) and a context feature.

**Solo's inputs from it.** `or30_state (A2)`.

**R61 result.** Not a new R61 input (A2 has `or30_state`; S4 is a candidate setup). In R61's final test the 15-minute ORB setup was almost never taken (0-2 trades per variant).

## Value area / market profile

Also called: TPO, POC, VAH, VAL, volume profile.

**What it is.** Market profile counts how long price spent at each level (time-price opportunities). POC = the most traded level; the value area holds 70% of the day's activity (VAH top, VAL bottom).

**How it is computed.** Previous day's 30-minute TPO profile, 70% value area (h37 sigs.profile_levels). R61: (index - previous POC) / (VAH - VAL), signed by the trade's side.

**On the phone.** yes: from index minutes.

**How traders use it.** Opening outside yesterday's value and staying there = trend day ('open outside value and go'); inside value = range.

**Pitfalls.**

- Many ways to build a profile (bin size, TPO period); results depend on them.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R52 H37-10 / R57 S3 (`research/HUNT_R57_SOLO_2.md`) | final 232 trades | 'Open outside value and go' (S3) made +Rs 49,360 unfiltered in the final window; A2 took 92 of its 128 trades from it. R52 chose its exit on this same window, so it is not fresh. | candidate setup (not fresh) |
| R9 (`research/HUNT_R9_VOLUME_GAMMA.md`) | 56 + 32 tests | HVNs and the POC turn price no more often than placebo levels; auction rules 0 of 56. | failed |
| H37 (`research/HUNT_H37.md`) | 18,170 variants | TPO-based rules among them: SPA p 1.00. | failed |

**Role in Solo.** a candidate setup (S3, signal on the index) and a context feature.

**Solo's inputs from it.** `va_pos`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'value area' inputs from the all-inputs model changed walk-forward AUC by +0.0007 (design) / -0.0002 (final) and net by Rs -1,001 / Rs -3,439. Each input added alone to A2's 29 changed AUC (design / final): `va_pos` -0.0019 / -0.0005. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Trend vs range regimes

Also called: efficiency ratio, ADX, choppiness.

**What it is.** Whether the market is trending (moves far for its distance travelled) or ranging (back and forth).

**How it is computed.** Efficiency ratio = |net move| / sum of |bar moves| (A2's 'er_day'); ADX14; choppiness index.

**On the phone.** yes: from index candles.

**How traders use it.** Use trend rules in trends and fade rules in ranges.

**Pitfalls.**

- You know the regime well only after it is over.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| SIDEWAYS_GUARD (`research/SIDEWAYS_GUARD.md`) | 7 detectors x 5 arms = 35 tests | None beat random skipping in both years; the ADX pause cut Liquidity from +1,03,019 to +52,096. | failed |
| R16 (`research/HUNT_R16_PREDICTABILITY.md`) | first 30/45 minutes | Trend vs range day not predictable (AUC 0.46-0.52). | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `er_day (A2)`, `rv_30m (A2)`, `day_range_vs_adr10 (A2)`.

**R61 result.** Not a new R61 input (A2 already has `er_day`, `rv_30m`, `day_range_vs_adr10`); not tested again.

## FII / DII flows and participant-wise OI

Also called: FII OI, client OI, Pro OI.

**What it is.** NSE publishes each evening how many index futures and options each group (client, DII, FII, Pro) holds long and short; FII/DII cash flows are published by exchanges/NSDL.

**How it is computed.** R61: FII index-futures long share = long / (long + short) from the PREVIOUS session's participant file (published after 18:30), and its change from the session before.

**On the phone.** yes: MarketRecorder records the participant file daily (after 18:30); DII cash flows not reachable.

**How traders use it.** Follow FII futures positioning as 'smart money'; fade crowded client positioning.

**Pitfalls.**

- A day late; FII futures positions are often hedges for cash holdings.
- Signals that worked for years flipped in the last 12 months (OF1).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| OF1 (`research/HUNT_OF1_ORDERFLOW_INDIA.md`) | 3,632 days | Follow signals pass in design and fail in the holdout; option-buying rules R0-R3 all lose in the holdout. | failed |
| H40 (`research/HUNT_H40.md`) | 9,600 variants, 2020-2026 | 0 pass (SPA 0.96); FII flows carry a small real signal that is smaller than option costs; 0 of 160 Liquidity filters. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `fii_fut_long_share`, `fii_fut_long_chg`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'participants' inputs from the all-inputs model changed walk-forward AUC by +0.0018 (design) / -0.0017 (final) and net by Rs +10,142 / Rs +6,388. Each input added alone to A2's 29 changed AUC (design / final): `fii_fut_long_share` -0.0010 / +0.0014, `fii_fut_long_chg` -0.0028 / +0.0003. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Futures basis / premium

Also called: basis, futures premium, cost of carry.

**What it is.** Futures price minus the index: normally positive (interest minus dividends to expiry). An unusual premium or discount is read as positioning.

**How it is computed.** R61: synthetic forward from put-call parity at the ATM strike: (K + call - put - index) / index x 10,000 bps. Real futures minutes are in the lake only from Jul 2026.

**On the phone.** yes: ChainAnalytics.syntheticFuture; real basis from the futures quote.

**How traders use it.** Rising premium = long positioning; discount = hedging or short positioning.

**Pitfalls.**

- Mostly interest and dividends; the 'signal' part is tiny.
- Synthetic forward uses last-traded prices that can be minutes apart.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| H38 (`research/HUNT_H38.md`) | daily + intraday + 72 real-basis trades | Basis change, synthetic premium and real basis all fail; a Liquidity skip filter passed design (q 0.008) and went -Rs 35 a day in the holdout. | failed |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `synth_basis_bps`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'basis' inputs from the all-inputs model changed walk-forward AUC by +0.0010 (design) / -0.0025 (final) and net by Rs -10,054 / Rs +13,198. Each input added alone to A2's 29 changed AUC (design / final): `synth_basis_bps` -0.0003 / +0.0005. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## Rollover

Also called: rollover %, roll cost.

**What it is.** Near expiry, the share of futures positions moved to the next month, and the price paid to roll.

**How it is computed.** Next-month OI / (near + next OI) in the last sessions before expiry. Not built in the brain.

**On the phone.** not available on the phone (needs both futures months' OI history); context only.

**How traders use it.** High rollover with a rising premium = longs carried forward (bullish).

**Pitfalls.**

- Monthly only; a handful of observations a year.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| H40 S10 (`research/HUNT_H40.md`) | last 5 sessions of each series | Rollover % and roll cost carry no information. | failed / barely tested |

**Role in Solo.** not a model input (not on the phone).

**R61 result.** No R61 input: not available on the phone.

## Event days (RBI, Budget, CPI, Fed, expiry)

Also called: event risk, macro calendar.

**What it is.** Scheduled news that moves the market: RBI policy (10:00 IST), the Union Budget (11:00), US FOMC decision and US CPI (after the Indian close, so they hit the next session), India CPI (16:00), and expiry days.

**How it is computed.** R61: event_rbi_budget = 1 on an RBI policy or Budget day; post_us_event = 1 on the first NSE session after an FOMC decision or US CPI; other_expiry_today = 1 when another of the four indices expires today (Solo never trades an index on its own expiry day). Calendars pre-listed in r61events.py.

**On the phone.** partly: EventCalendar.kt has RBI and Budget; a static FOMC / US CPI list would need bundling; expiry calendar yes.

**How traders use it.** Avoid or size down before events (IV crush, whipsaw); some trade the post-event trend.

**Pitfalls.**

- Dates shift (the US 2025 shutdown moved CPI); the app's RBI Aug 2026 date (6 Aug) differs from the research list (7 Aug).
- Few event days: every result rests on a handful of sessions.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| EVENT_SHIELD (`research/EVENT_SHIELD.md`) | BANKNIFTY 497, NIFTY 498 sessions | BANKNIFTY moves more on RBI + Budget days (range 1.44% vs 0.98%, 15 vs 359 sessions; q 0.08, holdout confirmed p 0.03 on 7 sessions); US CPI / FOMC: bigger gap, same day range. | passed as a size warning |
| H28 (`research/HUNT_H28.md`) | about 10,300 tests | 0 survivors as trade rules; Budget days 2.1-2.4x range; the session after FOMC gaps more. | size only |
| EXPIRY_PLAYBOOK (`research/EXPIRY_PLAYBOOK.md`) | 53 vs 446 NIFTY sessions | The last hour of expiry moves more (0.15% vs 0.10%, p 0.004); expiry rules R21-R37 0 of 52 pass. | size only |

**Role in Solo.** size/context only: our research says it predicts how much the index may move, not which way.

**Solo's inputs from it.** `event_rbi_budget`, `post_us_event`, `other_expiry_today`, `dte (A2)`.

**R61 result.** R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026, not fresh): removing the 'events' inputs from the all-inputs model changed walk-forward AUC by -0.0016 (design) / +0.0003 (final) and net by Rs -6,252 / Rs -4; removing the 'other expiry' inputs from the all-inputs model changed walk-forward AUC by -0.0015 (design) / -0.0011 (final) and net by Rs +2,406 / Rs -2,221. Each input added alone to A2's 29 changed AUC (design / final): `event_rbi_budget` +0.0007 / -0.0030, `post_us_event` +0.0007 / +0.0016, `other_expiry_today` +0.0046 / -0.0014. Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both ways and are within what one or two trades swing).

## News tone (positive / negative headlines)

Also called: news sentiment, headline tone.

**What it is.** Whether the latest business headlines read good or bad for the market: 'Sensex surges', 'Nifty falls as war fears grow'. Scheduled events (RBI, Budget, Fed) are a separate concept.

**How it is computed.** The phone's own word-list reader (News.tone): good words (rally, surge, gains, rate cut...) and bad words (crash, falls, war, tariffs...) with weights, phrases first, a 'not' one or two words back flips a word; score / 2 clipped to -1..+1. News.tag decides which index a headline concerns. R61-N: mean tone of the headlines out between the previous close and the signal minute, signed by the trade's side, and their count.

**On the phone.** yes: Jarvis reads the news feeds every 5 minutes (News, NewsAnalyst) and MarketRecorder logs them.

**How traders use it.** Traders avoid buying calls into a flood of bad news and chase good news; desks read overnight news to set the day's bias.

**Pitfalls.**

- Headlines usually come AFTER the price has moved (h33: 1-4 minutes late on intraday shocks).
- A word list misreads mixed headlines ('bounce back from morning losses... gains' scores 0).
- Good overnight news is usually already in the opening gap.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| h33 (`research/HUNT_H33.md`) | 3,072 variants, 59,189 timed headlines 2020-2026 | Trading intraday headlines or price shocks loses (walk-forward -Rs 1.02 lakh); the price moves before the headline. | failed |
| R61-N (`research/HUNT_R61_SOLO_3.md`) | Moneycontrol business headlines Oct 2024 - Oct 2026 (116,570; 13,782 market-wide); 5,780 design candidate rows, 127 final trades | Adding pre-signal news tone to Solo 2.0 passed the gates but did not beat it (final +Rs 85,483 vs +Rs 99,172; AUC 0.544 vs 0.538; paired p 0.86). Descriptive: pre-open tone vs the day's open-to-close move, correlation -0.16 on 337 design days per index (good news tends to fade after the open). | failed as a Solo input; fade effect descriptive only |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `news_tone (R61-N)`, `news_count (R61-N)`.

**R61 result.** R61-N (pre-registered in NEWS_PREREG.md): see the R61-N row under 'What our research found'. Verdict: no help beyond Solo 2.0 out of sample.

## Fake (failed) breakout

Also called: failed breakout, false break, breakout failure.

**What it is.** Price closes beyond a level everyone watches (the opening range, yesterday's high or low, a swing high) and then comes back inside it within minutes, leaving the breakout buyers trapped.

**How it is computed.** R61b (signal time only): a break = a 1-minute index close beyond the level after a close that was not; it failed if a close at one of the next 15 minutes (and not after the signal) is back inside. Flags for the opening range (09:15-09:29) and the previous day's high / low, on the trade's side and the other side. The break quality of the signal bar: rejection wick share and close location of the 5 minutes before entry, and near-ATM option volume in those 5 minutes vs the same window of the last 20 non-expiry sessions (the index has no volume; futures minutes exist only from Jul 2026).

**On the phone.** yes: index minutes and the live chain's volume (ChainAnalytics); no futures history needed.

**How traders use it.** Breakout traders wait for a close outside, a retest, or volume before trusting a break; fade traders sell a break that slips back inside.

**Pitfalls.**

- Whether a break 'failed' is known only after it fails; a rule must not use the future.
- A failed break on a 1-minute chart is often a normal pullback on a 5-minute chart.
- Most breaks look fine at the moment they happen; the bad ones are clear only in hindsight.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R11 (`research/HUNT_R11_MOVE_ANATOMY.md`) | 5-min top-5% candles making a new day high / low after 10:15, 2020-2026 | Fakes (fully given back within 15 minutes): BANKNIFTY 12.8% design / 21.1% holdout, NIFTY 14.9% / 14.6%; they come in busier markets, but nothing separates fakes from real breaks reliably. | descriptive; no rule |
| H18 (`research/HUNT_H18.md`) | 4,537 trades, 5 years | Breaks continue and sweeps do not reverse (30-min sweep fades -0.42 / -0.62 bp, t -2.6 / -3.3); buying the break is gross +Rs 3.25 lakh but net +Rs 3k, walk-forward -Rs 1.73 lakh, SPA p 0.87. | failed (no edge after costs) |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates, 20 Nov 2024 - 14 Oct 2026 | Skip filters and meta-labeller inputs for failed breaks, wicks and break volume; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `trap_same_or`, `trap_opp_or`, `trap_same_pd`, `trap_opp_pd`, `brk_wick`, `brk_clv`, `vol_brk_rel`, `oi_chg15_pct`, `bu15`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'traps' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0024 (design) / +0.0003 (final) and net by Rs -13,755 / Rs -2,327; removing the 'break bar' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by -0.0055 (design) / -0.0078 (final) and net by Rs -14,313 / Rs -10,808; removing the 'break volume' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0006 (design) / -0.0011 (final) and net by Rs +9,120 / Rs -7,000; removing the 'oi at break' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0000 (design) / +0.0001 (final) and net by Rs +6,333 / Rs -6,578. Each input added alone to A2's 29 changed AUC (design / final): `trap_same_or` +0.0001 / +0.0004, `trap_same_pd` -0.0022 / -0.0003, `brk_wick` +0.0027 / +0.0001, `brk_clv` -0.0003 / +0.0000, `vol_brk_rel` -0.0006 / -0.0008, `oi_chg15_pct` -0.0002 / -0.0016, `bu15` -0.0027 / +0.0001. Skip filters on Solo 2.0: F1 (skip after a failed same-side break): design 158 trades Rs +57,050 vs V1 Rs +55,910; final 126 trades Rs +90,775 vs V1 Rs +99,172, gates PASS; F2 (skip a signal bar with a rejection wick of half its range or more): design 166 trades Rs +55,698 vs V1 Rs +55,910; final 127 trades Rs +86,888 vs V1 Rs +99,172, gates PASS; F3 (skip a break on below-normal option volume): design 95 trades Rs +46,298 vs V1 Rs +55,910; final 84 trades Rs +28,954 vs V1 Rs +99,172, gates FAIL. Verdict: no measurable help out of sample (every AUC change under 0.01; no skip filter made more than Solo 2.0 on the final days).

## Bull trap and bear trap

Also called: bull trap, bear trap, turtle soup.

**What it is.** A bull trap is an upside break that fails, catching buyers who then sell; a bear trap is the downside mirror. 'Turtle soup' trades the reversal after a false break of a 20-day or previous-day extreme.

**How it is computed.** R61b: the failed-break flags above, read for the trade's own side (a trap against Solo's direction) and the other side (a trap that supports it), for the opening range and the previous day's high / low.

**On the phone.** yes: index minutes only.

**How traders use it.** Traders avoid a direction right after it trapped people, or trade the other way once the trap is clear.

**Pitfalls.**

- The same pattern is called a 'shakeout' when the move continues; the label comes after the fact.
- Our tests of trading the reversal all lost (below).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R1 N7 (`research/HUNT_R1_INTERNET.md`) | NIFTY 777, BANKNIFTY 657 design trades | Turtle soup (false break of the previous day's high / low, then reclaim): design -Rs 25 / -77 a day; BANKNIFTY's +Rs 247 a day in the locked year was judged noise. | failed |
| R52 r1-7 (`research/HUNT_R52_OLD_CATALOGUE_RECENT.md`) | 166 final trades (Nov 2024 onwards) | Turtle soup re-tested on recent data: final -Rs 17,852. | failed |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates | Trap flags as a skip filter (F1, F4) and as inputs; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `trap_same_or`, `trap_opp_or`, `trap_same_pd`, `trap_opp_pd`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'traps' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0024 (design) / +0.0003 (final) and net by Rs -13,755 / Rs -2,327. Each input added alone to A2's 29 changed AUC (design / final): `trap_same_or` +0.0001 / +0.0004, `trap_opp_or` -0.0008 / -0.0001, `trap_same_pd` -0.0022 / -0.0003, `trap_opp_pd` -0.0014 / +0.0003. Skip filters on Solo 2.0: F1 (skip after a failed same-side break): design 158 trades Rs +57,050 vs V1 Rs +55,910; final 126 trades Rs +90,775 vs V1 Rs +99,172, gates PASS. Verdict: no measurable help out of sample (every AUC change under 0.01; no skip filter made more than Solo 2.0 on the final days).

## Stop-hunts and liquidity sweeps

Also called: stop run, liquidity grab, sweep, ORB sweep.

**What it is.** Price pokes just past an obvious high or low where stop orders sit, fills them, and snaps back: the 'liquidity' (resting stops) was taken, and the story is that the real move then goes the other way.

**How it is computed.** R61b: in the last 30 one-minute bars (from 09:30), a bar that makes a new high (or low) of the day but closes back below (above) the old extreme; flagged on the trade's side (a warning) and the other side (support).

**On the phone.** yes: index minutes only.

**How traders use it.** SMC / ICT traders wait for a sweep of the highs or lows and enter the other way; others put stops past the obvious level to avoid being swept.

**Pitfalls.**

- On the index a 'sweep' is usually just a wick; there is no real stop book to see in minute data.
- Our data says breaks continue more often than sweeps reverse (H18).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| H18 (`research/HUNT_H18.md`) | 5 years, NIFTY and BANKNIFTY | 'Liquidity sweep, then reversal' is folklore: 30-minute sweep fades -0.42 / -0.62 bp (t -2.6 / -3.3); breaks +2.34 bp (t 4.9), holdout +2.95 (t 3.2). | failed (the fade) |
| R3 (`research/HUNT_R3_ORB_FAMILY.md`) | BANKNIFTY + NIFTY 5 years 3,156 trades; unseen indices 3,303 | The app's ORB Sweep (poke beyond the 09:15-10:00 range, close back inside, buy the other way): -Rs 5,05,954 in design and -Rs 5,85,798 on unseen indices, 0 of 6 years in profit; all 16 enhanced versions failed. | failed |
| R49 (`research/HUNT_R49_ORB_RECENT.md`) | 20 Nov 2024 onwards: 2,093 design / 870 final trades | ORB Sweep -Rs 4,12,532 design, -Rs 92,211 final; Range Fade -Rs 4,06,570 / -Rs 1,14,187; 0 of 336 tests pass. | failed |
| R8 (`research/HUNT_R8_ORDER_FLOW_TRAPS.md`) | one MCX evening, 11 stop-hunt events (tick data) | Crude was back inside 33% / 50% / 83% of the time by 30 / 60 / 120 seconds; proposed a 60-second confirm and a 120-second failed-break label (not tested on P&L). | not-a-test (too small) |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates | Sweep flags in the F4 skip filter and as inputs; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `sweep_same30`, `sweep_opp30`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'sweeps' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0015 (design) / +0.0002 (final) and net by Rs +8,281 / Rs +1,329. Each input added alone to A2's 29 changed AUC (design / final): `sweep_same30` -0.0010 / +0.0015, `sweep_opp30` -0.0005 / -0.0004. Skip filters on Solo 2.0: F4 (skip on any price-only trap sign (F1, F2 or a same-side sweep)): design 144 trades Rs +65,534 vs V1 Rs +55,910; final 117 trades Rs +75,675 vs V1 Rs +99,172, gates PASS. Verdict: no measurable help out of sample (every AUC change under 0.01; no skip filter made more than Solo 2.0 on the final days).

## Absorption

Also called: passive absorption, iceberg buying / selling.

**What it is.** Heavy aggressive selling (or buying) that does not move the price, because a big passive buyer (seller) soaks it up at one level. Read on a footprint or tape as lots of volume at one price with little range.

**How it is computed.** True absorption needs trades and the order book at each price. R61b proxy: log(15-minute near-ATM option volume / its 20-session same-window mean) minus log(15-minute index range / its 20-session same-window mean): high volume with a small range. The phone's TickZoneRecorder (from 12 Oct 2026) flags real absorption per minute (60%+ of volume at one price, 2x the usual minute, narrow range).

**On the phone.** proxy yes (index minutes + chain volume); real absorption only from TickZoneRecorder's recordings (NIFTY / BANKNIFTY futures and ATM +-3 options, since 12 Oct 2026).

**How traders use it.** Traders read absorption at a level as 'this level will hold' and trade the bounce.

**Pitfalls.**

- On real MCX tick data flagged levels broke MORE often than others (R8).
- Index minutes have no volume; option volume is a noisy stand-in.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R8 (`research/HUNT_R8_ORDER_FLOW_TRAPS.md`) | MCX tick sample: 368 option + 47 futures episodes | Absorption did not mean 'this level will hold': flagged levels broke 58% (options) and 68% (futures) of the time vs 55% for unflagged levels. | failed (one evening) |
| R9 (`research/HUNT_R9_VOLUME_GAMMA.md`) | NIFTY / BANKNIFTY minutes, 52 events | Heavy-volume narrow-range 5-minute bars: NIFTY design +2-6 bp vs control, q 0.29; holdout +2 bp then negative; below the cost of trading. | failed (proxy) |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates | `absorb15` as a meta-labeller input; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `absorb15`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'absorption' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0015 (design) / -0.0007 (final) and net by Rs +7,247 / Rs -2,398. Each input added alone to A2's 29 changed AUC (design / final): `absorb15` -0.0013 / -0.0011. Verdict: no measurable help out of sample (every AUC change under 0.01).

## Order flow: delta and cumulative delta (CVD)

Also called: volume delta, CVD, buy / sell volume, footprint.

**What it is.** Delta = volume traded at the ask (aggressive buyers) minus volume at the bid (aggressive sellers) in a bar; cumulative delta adds it up through the day. Divergence (price up, CVD down) is read as weak buying.

**How it is computed.** Needs every trade with its side (tick rule or bid / ask). The lake has only 1-minute bars, so R61b uses a proxy: each index minute's close location ((C - L) - (H - C)) / (H - L) weighted by that minute's near-ATM option volume, over 30 minutes (`delta30`) and from 09:15 (`cdelta_day`), signed by the trade's side. TickZoneRecorder computes the real tick-rule delta and CVD per minute from 12 Oct 2026.

**On the phone.** proxy yes; real delta / CVD in TickZoneRecorder's orderflow_1m (NIFTY / BANKNIFTY futures, ATM +-3 options) and OrderFlowLive's 1-second file.

**How traders use it.** Confirm a breakout with positive delta; fade a move whose delta diverges.

**Pitfalls.**

- A close-location proxy is not delta: it only says where the bar closed.
- Same-time delta always 'explains' the move; the question is whether it LEADS it (R7: it did not).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R7 (`research/HUNT_R7_ORDER_FLOW.md`) | 34-minute Dhan full-feed sample, 14 MCX contracts, 217,517 packets; own NIFTY / BANKNIFTY option-flow feature | Same-10-second correlation with the move +0.71 to +0.90, but no lead across 66 cells; the app's option-flow signal leads (IC +0.109 at 1 minute) but its edge is 0.42x the cost; best use a filter, not a signal. | not-a-test (needs 8-12 weeks of recording) |
| R9 (`research/HUNT_R9_VOLUME_GAMMA.md`) | NIFTY / BANKNIFTY minutes | Delta divergence (proxy): reversal -2.6 to +0.6 bp. | failed (proxy) |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates | `delta30`, `cdelta_day` as inputs; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `delta30`, `cdelta_day`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'delta proxies' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by -0.0024 (design) / -0.0027 (final) and net by Rs +13,733 / Rs +1,730. Each input added alone to A2's 29 changed AUC (design / final): `delta30` +0.0036 / +0.0015, `cdelta_day` +0.0004 / -0.0012. Verdict: no measurable help out of sample (every AUC change under 0.01).

## Order-flow imbalance (OFI)

Also called: OFI, MOFI, multi-level OFI.

**What it is.** The net change in the best bid and ask queues between quotes: bids growing or asks being taken pushes OFI up. Academic work finds it explains same-time price changes very well.

**How it is computed.** Needs every best-bid / best-ask update (Cont, Kukanov and Stoikov 2014). Not computable from minute bars. R61b's nearest proxy: the near-ATM call minus put volume share over 15 minutes (`optvol_imb15`), signed by side; it is an activity tilt, not OFI.

**On the phone.** real OFI only from OrderFlowLive (1-second, index futures + ATM +-2) and TickZoneRecorder (from 12 Oct 2026); proxy yes.

**How traders use it.** HFT and scalpers lean on short-horizon OFI; discretionary traders watch the bid / ask stack.

**Pitfalls.**

- Strongly same-time, weakly predictive; any lead lasts seconds.
- Call volume is not 'buying': writers sell calls too.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R7 (`research/HUNT_R7_ORDER_FLOW.md`) | MCX full-feed sample | OFI +0.71 / +0.73 and MOFI +0.81 / +0.90 same-10-second correlation (CRUDEOIL / NATURALGAS), no lead; cost hurdle about 2 bp of the index for BANKNIFTY. | not-a-test |
| R10 (`research/HUNT_R10_ORDERFLOW_TOOLS.md`) | 75 design tests on the arms | VWAP / profile tools: one Range Fade filter passed (q 0.075) but Range Fade still loses Rs 309 a day; proposes recording 1-second quotes and footprints and testing after 20 sessions. | failed / record first |
| R61b (`research/HUNT_R61B_TRAPS_ORDERFLOW.md`) | S1-S4 candidates | `optvol_imb15` as an input; see R61b result. | see R61b result |

**Role in Solo.** context or filter only: may make Solo skip or prefer a candidate, never create a trade or pick its side.

**Solo's inputs from it.** `optvol_imb15`.

**R61 result.** R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on 5,780 candidate rows, final 1 Apr - 14 Oct 2026 on 2,248, not fresh; signals from the index, these inputs are minute-bar PROXIES, not tick order flow): removing the 'optvol imbalance' inputs from M1 (A2 + all 15 R61b inputs) changed walk-forward AUC by +0.0013 (design) / +0.0014 (final) and net by Rs +16,257 / Rs -1,394. Each input added alone to A2's 29 changed AUC (design / final): `optvol_imb15` -0.0005 / -0.0007. Verdict: no measurable help out of sample (every AUC change under 0.01).

## Depth (order-book) imbalance

Also called: book imbalance, bid/ask imbalance, walls in the book.

**What it is.** Total bid quantity vs total ask quantity in the visible book (5 levels on NSE): (bids - asks) / (bids + asks). Big resting orders are read as 'walls'.

**How it is computed.** Needs the order book; the lake has none (Dhan does not provide historical depth). TickZoneRecorder stores 5 bid and 5 ask levels each second from 12 Oct 2026. Not an R61b input.

**On the phone.** live only: TickZoneRecorder / OrderFlowLive book imbalance; no history before 12 Oct 2026.

**How traders use it.** Lean with the heavier side of the book; watch walls being pulled.

**Pitfalls.**

- Visible depth is mostly cancelled within seconds (R8: about half of added size within 3-5 s; walls live a median 1.2 s and 50% are pulled, 5% traded).
- Spoofing: size shown to be pulled.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R8 (`research/HUNT_R8_ORDER_FLOW_TRAPS.md`) | one MCX evening, 11,700 walls | Walls (2x or more) had a median life of 1.2 seconds; half were pulled and 5% traded; no extra pulling at candle closes (hazard 1.03x, p 0.53). | not-a-test |
| OF1 (`research/HUNT_OF1_ORDERFLOW_INDIA.md`) | the app's recordings as of 13-14 Oct 2026 | 0 uploaded releases and an empty catalogue: no recorded day analysed yet; footprints.py is ready for large prints, absorption, sweeps and refills. | waiting for data |

**Role in Solo.** not a model input yet (no history): needs the phone's recordings.

**R61 result.** Not measurable in R61b: the lake has no order book. The phone's TickZoneRecorder stores 5-level depth each second only from 12 Oct 2026 (a few sessions, none uploaded or analysed yet).

## Volume profile: POC, value area, high / low-volume nodes

Also called: VPOC, VAH / VAL, HVN, LVN.

**What it is.** Volume traded at each price over a session: the point of control (POC) is the busiest price, the value area the band holding 70% of the volume, high-volume nodes act as magnets and low-volume nodes are 'thin' prices that get crossed quickly. The time-based version (TPO / market profile) is the 'Value area / market profile' concept above.

**How it is computed.** Needs volume by price; the index has no volume, so our studies use a TPO profile (30-minute letters) or option / futures volume as a stand-in. R62 (branch claude/r62-volume-profile-smc) tests Boss's 15 Oct volume-profile video strategy separately; R61b does not duplicate it.

**On the phone.** TPO version yes (index minutes); volume version only with futures volume (recorded from Jul 2026).

**How traders use it.** Trade back to the POC, fade at value-area edges, expect fast moves through low-volume nodes.

**Pitfalls.**

- Levels picked by eye look magical; against randomly shifted placebo levels they do no better (R9, LEVEL_MAP).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R9 (`research/HUNT_R9_VOLUME_GAMMA.md`) | 224 design tests, holdout 1 Oct 2025 - 6 Oct 2026 | POC / value area / HVN / LVN vs placebo levels: 0 of 32 design tests pass BH (best q 0.26); real futures volume (48 days) gives scattered results. | failed |
| LEVEL_MAP (`research/LEVEL_MAP.md`) | 24 tests (12 level types x 2 indices) | No level type beats placebo levels shifted 0.3-0.7% (lowest q 0.76). | failed |
| R52 H37-10 (`research/HUNT_R52_OLD_CATALOGUE_RECENT.md`) | 294 final trades | 'Open outside value and go' (TPO value area): final +Rs 48,772, p 0.001, q 0.03, PASS but MIDCPNIFTY carries +Rs 46,329 and the exit was re-tuned; it is Solo's S3. | paper-only candidate |

**Role in Solo.** R62's subject; in Solo only through S3 and A2's inputs.

**R61 result.** Not an R61b input: R62 (branch claude/r62-volume-profile-smc) tests the volume-profile strategy separately. In Solo it appears only through S3 (open outside the previous day's TPO value area).

## Order blocks

Also called: OB, institutional candle, supply / demand zone.

**What it is.** SMC idea: the last opposite-colour candle before a strong move marks where 'institutions' placed orders; price is expected to come back to that zone and react.

**How it is computed.** Typical: the last down candle before an up-impulse that breaks structure (bullish OB), its high-low as a zone. Not an R61b input. R62 tests the SMC strategy separately.

**On the phone.** computable from index candles; not built.

**How traders use it.** Enter on a return to the order block in the direction of the impulse.

**Pitfalls.**

- Defined after the impulse, so zones are easy to draw in hindsight and hard to test honestly.
- No order data is behind it: it is a candle pattern.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| search (`research/FINDINGS.md`) | the whole research folder | No study of order blocks exists in our research yet; R62 is the first. | untested (R62) |

**Role in Solo.** R62's subject; not an R61b input.

**R61 result.** Not an R61b input: R62 tests it separately.

## Fair value gaps (FVG)

Also called: FVG, imbalance (ICT), liquidity void.

**What it is.** A three-candle pattern where the first candle's high and the third candle's low do not overlap (bullish), leaving a 'gap' the middle candle moved through; price is expected to return to fill it.

**How it is computed.** Bullish FVG: low[t] > high[t-2]; bearish: high[t] < low[t-2]; usually a minimum gap size. Not an R61b input; R62 tests the strategy separately.

**On the phone.** computable from index candles; not built.

**How traders use it.** ICT traders enter when price returns into the gap after a break of structure.

**Pitfalls.**

- Gaps are everywhere on 1-5 minute charts; filters picked after the fact make backtests look good.
- Our design favourite flipped to a loss on unseen data.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R1 N6 (`research/HUNT_R1_INTERNET.md`) | BANKNIFTY 814 / NIFTY 982 design trades | ICT first FVG: design +Rs 142 / +125 a day, then the locked year -Rs 196 / -334 a day; q 1.00. | failed (flipped) |
| R52 (`research/HUNT_R52_OLD_CATALOGUE_RECENT.md`) | BANKNIFTY 110, NIFTY 97 final trades | FVG re-tested on Nov 2024+ data: -Rs 15,329 and -Rs 18,658. | failed |
| ADVANCED (`research/ADVANCED.md`) | 209 trades | Entering in the FVG removed the pullback edge (-Rs 35,814). | failed |

**Role in Solo.** R62's subject; not an R61b input.

**R61 result.** Not an R61b input: R62 tests it separately.

## Break of structure (BOS) and change of character (ChoCH)

Also called: BOS, CHoCH, MSS, market structure.

**What it is.** SMC terms: a BOS is a break of the last swing high in an uptrend (continuation); a ChoCH is the first break against the trend (the last higher low broken), read as a possible reversal.

**How it is computed.** Swing points from N-bar pivots; BOS = close beyond the last swing in the trend's direction, ChoCH = close beyond the last opposite swing. Not an R61b input; R62 tests the strategy separately.

**On the phone.** computable from index candles (Matrix Pine script had BOS / ChoCH entries).

**How traders use it.** Trade continuation after a BOS and the new direction after a ChoCH, often with an order block or FVG.

**Pitfalls.**

- Swing definitions change the answer; many parameters to over-fit.
- A BOS is just a breakout with a new name; breaks barely pay after costs (H18).

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| MATRIX_V22 (`research/MATRIX_V22.md`) | 38 in-spec versions, two years | BOS / ChoCH entries lose in both years in every version (5-minute example 608 trades, -Rs 2,30,966 on 2 lots). | failed |
| ADVANCED (`research/ADVANCED.md`) | 167 trades | 'Base + break of structure': -Rs 54,886. | failed |

**Role in Solo.** R62's subject; not an R61b input.

**R61 result.** Not an R61b input: R62 tests it separately.
