# HUNT h33: unscheduled intraday news and price shocks, for option buying

Written 2026-10-08. Code: `research/hunt/h33/` (`PREREG.md`, `fetch.sh`, `parse.py`, `fetch_pages.sh`, `calib.py`,
`news.py`, `cover.py`, `feats.py`, `build.py`, `test.py`). Logs and tables: `scratchpad/hunt/h33/` (`test_pre.log`,
`holdout.log`, `cover.log`, `variants_pre.csv`, `wf.csv`, `continuation_pre.csv`, `overlap_pre.csv`, `calib.csv`,
`holdout.csv`, `build*.log`). Raw downloads (gzipped sitemaps and page publication times) are in `scratchpad/hunt/h33/raw/`.

Rules: option BUYING only. 1-ITM nearest expiry. 1 lot. Rs 1 lakh. Targets and stops are in premium points. The holdout
starts 2025-10-01; it was locked and run once.

## Verdict

**NO.** Trading intraday news, or sudden price shocks, does not beat costs. This holds whether you follow the first
move or fade it, and after 1, 3 or 5 minutes.

1. **No variant survived.** We ran 3,072 pre-registered variants.
   - Best random-time BH q = 0.09. The gate was 0.05.
   - Hansen SPA p = 1.00 and White RC p = 1.00. The best variant does not beat "do nothing".
2. **Walk-forward lost money every year.** Each year we traded the best variant from the earlier years:
   - Price-shock arm: **-Rs 60,690** (2022 to Sep 2025).
   - News arm: **-Rs 1,19,386**.
   - Both arms pooled: **-Rs 1,02,154**.
3. **The best variants made almost nothing, even before the holdout.**
   - Best price-shock variant: **+Rs 43/day net** (Rs 67/day gross) at 1 lot. Max drawdown -Rs 31.5k.
   - Best news variant: **+Rs 33/day net** (Rs 93/day gross). Max drawdown -Rs 53k.
   - The median variant lost money: -Rs 15/day (price arm) and -Rs 2/day (news arm, which trades rarely).
4. **Holdout (info only, because nothing survived):**
   - Best price variant: **-Rs 16/day net** (gross +21/day).
   - Best news variant: **+Rs 16/day net** (gross +65/day). Random-time p = 0.12, so no better than chance.
5. **Rs 5,000/day: NO.** Even the pre-holdout best (+Rs 43/day per lot) would need about **116 lots**. Rs 1 lakh pays
   for 3 to 10 lots.
6. **Why it fails:**
   - The index does not keep moving after a news headline or a shock. Over the next 15 to 30 minutes the move is within
     ±2 bp (t-stats under 2), and its sign changes between periods.
   - The headline usually arrives **after** the price has already moved. For the surprise RBI hike on 2022-05-04, NIFTY
     jumped at 14:00 and fell hard at 14:03 and 14:08. The first ET headline was published at about 14:04.
   - A 1-ITM buyer pays spread plus charges of about Rs 100 to 200 per round trip, while a 15-30 minute hold earns about
     zero.

**Fills:** Every premium target and stop was checked minute by minute against the option's own 1-minute HIGH and LOW,
not the close. When both were hit in the same minute, the stop was taken first. Same-minute ties (exit at the stop or
profit lock while that minute's HIGH also reached the target) were **2,110 out of 337,884** point-exit trades:

| exit | ties |
|---|---|
| +15 | 836 |
| +20 | 567 |
| +25 | 404 |
| +30 | 303 |

## Headline data: what we got

| source | result |
|---|---|
| Economic Times monthly news sitemaps (2020-01 to 2026-10) | **Works.** 1,058,544 article URLs with `lastmod` |
| Moneycontrol monthly post sitemaps (2020-06 to 2026-10) | **Works.** 564,297 URLs with `lastmod` |
| ET / Moneycontrol article pages | **Works.** Gives the exact publication time. Fetched 2,773 pages, 1 failure |
| GDELT 2.0 DOC API | **Blocked:** HTTP 429 on every try, even 5 s apart (shared IP). Raw GDELT GKG files were not tried (too big for the disk) |
| Google News RSS search | Works, but **dates only**: every item says 08:00 GMT. Not usable to the minute |
| Wayback CDX | Timed out |
| Reuters / Livemint / Business Standard | robots.txt read; not needed after ET + MC |

Both sitemaps were downloaded politely, 3 s apart, without cookies.

**Timing problem and fix.** `lastmod` is the time of the last edit, not of publication. We checked 118 random
market-hours headlines against their article pages:

| | ET | Moneycontrol |
|---|---|---|
| `lastmod` within 2 min of publication | 52% | 58% |
| `lastmod` more than 10 min late | 21% | 37% |
| `lastmod` early | 0% | 0% |
| id-suffix-min estimate early (look-ahead!) | 22% | 53% |

- So `lastmod` is safe (never early) but often late.
- For every event's first headline whose `lastmod` looked edited, we fetched the article page and used its printed
  publication time. That was 2,773 pages.
- In the final event set, only 58 of 4,844 events (1%) still rely on a possibly-late `lastmod`.

**Coverage.**
- Headlines: 1.62 M in total, about 36% (ET) and 40-50% (MC) of them inside market hours.
- Keyword-classified headlines: 59,189.
- Events: a class headline at 09:20-14:30 after at least 120 quiet minutes. Here is how many we got:

| class (index traded) | events 2020-2026 | burst events (>= 3 headlines in 20 min) |
|---|---|---|
| RBI (BANKNIFTY) | 351 | 1 |
| govt / SEBI / duties (NIFTY) | 1,090 | 11 |
| bank heavyweights (BANKNIFTY) | 450 | 3 |
| other heavyweights (NIFTY) | 842 | 14 |
| geopolitics / tariffs (NIFTY) | 1,170 | 42 |
| any of the above (NIFTY) | 941 | 67 |

**Honest limit: the keyword classes are noisy.** The "burst" list includes meme articles, "Q1 profit seen up X%"
previews, and world-news stories with no Indian market impact. A human or LLM tagger would do better, but it cannot
change the core finding. Even the true shocks (step 7) do not continue in a tradable way.

## Method (pre-registered in `research/hunt/h33/PREREG.md` before any P&L)

**Price-shock arm (P).**
- Normal volatility = the 1-minute volatility of the previous 5 sessions.
- Shock = the first minute 09:25-14:30 where the 1- or 3-minute index move is at least 5 or 8 times normal.
- After a shock, the next one can start 30 minutes later.
- Optional filter: ATM±2 option volume over the last 5 minutes at least 2× its usual level.
- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.

**News arm (N).**
- The class event above. The first move is measured from the minute before the headline to 1, 3 or 5 minutes after it.
- Optional confirmation: that move is at least 2 sigma.

**Trades.**
- Buy 1-ITM nearest-expiry CE or PE N = 1, 3 or 5 minutes after the event, at the next minute's open.
- Follow or fade the first move.
- Costs: app fills (±5/10 bps), app charges, and h24's real half-spread per side (NIFTY 0.16%, BANKNIFTY 0.16%,
  FINNIFTY 0.42%, MIDCP 0.21%, SENSEX 0.20%). Everything was also rerun at 1.5× the spread.
- One position at a time. Square-off at 15:10.

**8 exits, fixed in advance:**

| exit | rule |
|---|---|
| +15 / +20 / +25 / +30 | target that many premium points, stop -15 points, 30-minute hard time stop |
| LIQ | Liquidity arm: -15% stop; at 20 min, exit unless up 5% |
| LOCK | -15 point stop, profit-lock ladder on 30 points, 30-minute stop |
| T15 / T30 | out after 15 / 30 minutes |

**Variant count:**
- P: 5 indices × 2 windows × 2 z levels × 2 volume filters × 3 delays × 2 sides × 8 exits = 1,920.
- N: 6 classes × 2 intensities × 2 confirmations × 3 delays × 2 sides × 8 exits = 1,152.
- **Total: 3,072. All were counted in BH and SPA.**

**Tests:**
- Random-time baseline: same index and day, a random minute and a coin-flip side, the same exit, 2,000 draws.
- BH correction across all 3,072 variants.
- Hansen SPA and White RC.
- Anchored walk-forward by year.
- Holdout, once.

## Results before the holdout (2020-08 to 2025-09)

**Blind level.** Random-time 1-ITM buying with these exits loses Rs 72-211 per trade, depending on index and exit.

**Shock and news trades do no better.** Median net per trade by side and exit:

| arm / side | LIQ | LOCK | +15 | +20 | +25 | +30 | T15 | T30 |
|---|---|---|---|---|---|---|---|---|
| P follow | -128 | -72 | -135 | -131 | -120 | -117 | -126 | -191 |
| P fade | -230 | -129 | -146 | -142 | -132 | -116 | -117 | -137 |
| N follow | -135 | -117 | -117 | -113 | -112 | -105 | -123 | -127 |
| N fade | -45 | -98 | -118 | -115 | -130 | -113 | -74 | -80 |

Only 402 of 3,072 variants made money at the real spread, which is about what chance gives. Best ones:

| variant | trades | Rs/day net (gross) | max DD | rand p | BH q | per year 2021 / 22 / 23 / 24 / 25 |
|---|---|---|---|---|---|---|
| FINNIFTY 1-min shock z≥5 + volume, follow after 3 min, T30 | 193 | +43 (67) | -31.5k | 0.023 | 0.90 | +0.5k / +23k / -21k / +7k / +34k |
| geo news, follow after 5 min, LIQ | 935 | +33 (93) | -53k | 0.023 | 0.90 | +75k / -12k / -14k / -18k / +4k |
| NIFTY 3-min shock z≥5 + volume, follow after 1 min, +30 | 226 | +26 (41) | -9k | 0.000 | 0.09 | +14k / +4.5k / +3k / -2k / +2k |
| any news + 2σ confirm, follow after 1 min, +20 | 53 | +8 (11) | -4k | 0.000 | 0.09 | small |
| RBI news, follow after 1 min, +20 | 235 | -29 (-6) | -30k | 1 | 1 | lost every year |
| RBI news, fade after 1 min, +20 | 235 | -29 (-7) | -30k | 1 | 1 | lost every year |

**Walk-forward picks (each lost in its test year):**

| test year | P-arm pick | net | N-arm pick | net |
|---|---|---|---|---|
| 2022 | NIFTY 3-min z5 follow T15 | -10.8k | geo follow N1 LIQ | -11.5k |
| 2023 | BANKNIFTY 3-min z5 +vol follow LIQ | -14.8k | same | -27.0k |
| 2024 | MIDCP 3-min z5 +vol follow LIQ | -11.4k | same | -39.9k |
| 2025 (to Sep) | MIDCP 3-min z5 +vol follow N5 LIQ | -23.7k | govt fade N1 LIQ | -41.0k |

## Index behaviour after events (no options; follow-sign move in bp)

| event | n | +15 min (t) | +30 min (t) |
|---|---|---|---|
| 1-min shock z≥5, from +1 min | 1,812 | -6.0 (-1.5) | -8.8 (-1.8) |
| 1-min shock z≥5, from +5 min | 1,812 | -1.2 (-1.8) | -3.8 (-1.3) |
| 1-min shock z≥8, from +3 min | 309 | -11.5 (-0.7) | -29.8 (-1.3) |
| any-news, from +1 min | 786 | +0.0 (0.1) | +0.9 (1.5) |
| RBI news, from +5 min | 235 | -2.2 (-1.9) | -2.1 (-1.4) |
| burst news, from +3 min | 112 | -2.1 (-1.8) | -1.5 (-0.8) |

- Before the holdout, shocks tended to reverse slightly. In the holdout they tended to continue (+2 to +11 bp,
  t ≈ 1.2-2.2). The sign flips, so there is nothing stable to trade.
- None of these moves pays a 1-ITM round trip of about 0.3-0.5% of premium plus Rs 50-60 in charges.

## News shocks vs price-only shocks

| check | hit rate |
|---|---|
| News event has a z≥5 price shock within -5..+15 min | 2-6% (burst geo: 6%) |
| Price shock has any classified headline within -15..+10 min | 42-50% |
| Random minute has any classified headline within -15..+10 min | 42% |

- Price shocks are **not** explained by the headlines we can see: their match rate is the same as a random minute's.
- Most classified "news" moves nothing.
- When news really moves the market (RBI 2022-05-04 14:00, the windfall tax 2022-07-01 about 10:10), the price moves
  first and the headline comes 1-4 minutes later. By then the move is mostly done.

## Size (for completeness)

- Pre-holdout best: +Rs 43/day per lot. Rs 5,000/day would need about 116 FINNIFTY lots, roughly Rs 8 lakh of premium
  at about Rs 7.2k per lot. That is far beyond Rs 1 lakh, and far beyond FINNIFTY's depth.
- Its pre-holdout drawdown was already -Rs 31.5k per lot.
- In the holdout, the same variant lost -Rs 16/day per lot.

## Honest count

- 3,072 option variants.
- 1 timing calibration (118 pages).
- 2 rounds of page timing (2,655 pages).
- Descriptive index tables: 25 continuation rows and 12 overlap rows (not used for choosing anything).
- The keyword lists and the z grid were fixed before any P&L. The z grid (5, 8) was chosen only from how often each
  level occurs.
