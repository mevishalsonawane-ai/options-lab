# R101 pre-registration: can the arms with a real record make MORE, or win MORE OFTEN, without making less?

Written 22 Oct 2026, **before any R101 number was computed** (committed and pushed on `claude/arms-audit` ahead of the
code's first run). Boss, 22 Oct: *"Check each and every arm ... Also find if could enhance it by increasing the profit /
winrate"*. Boss asked for the win rate AND the net profit side by side: a variant that wins more often but makes less
money is shown as exactly that and never passes.

Research only in this folder (the app changes of the same session are the audit's bug fixes, not this study's). Signal
source for every rule: the index (CLAUDE.md default); the option is only what is bought.

## 0. What is already settled (cited, not re-run)

The scout memo of this session (every number with its file) and `research/experiments/entries/` (1,629 entries):

| arm | exhausted | source |
|---|---|---|
| ORB, ORB Fresh, ORB Sweep, Range Fade | every entry hour, OR length, exit time, weekday, days to expiry, VIX / gap / width filters, Boss's consistency exits: 0 of 16 + 0 of 336 + 0 of 257 | HUNT_R3_ORB_FAMILY, HUNT_R49_ORB_RECENT, HUNT_R92_ORB_TIME |
| Hero (expiry) and the Hero Rs 1,000 ticket | 9 + 18 + 88 + 28 + entry/exit times + trail starts + 8,280-variant search (SPA p 0.96) | HUNT_R21, R25, R43, R50, R54, R55, R98 |
| FT3 near-ATM morning | a FROZEN forward test (any change starts a new test) | hunt/r98/FORWARD_TEST.md |
| Liquidity 15+5 EXITS (stops, locks, trails, time stops, partials, give-back, spreads) | 13 exit sets + 6 + 8 + 14 managers + 5 overlays + 4 trend turns + L25 + 4 index locks + 11 exits + 15 give-back rules: 0 pass | LIQUIDITY_*.md, R12, R18, R19/R19B/R19C, R63, R70, R76, R81, R100 |
| Liquidity ENTRIES already tried | before/after 11:00, VIX level and change (daily), gap, prior-day range, expiry week, room, PCR, futures OI, GEX, Supertrend, 10-candle wait, other-index 5-min trend, 10 structure/time/count filters, 14:00 cut-off | H12, R15, R28, R79, R80, ENTRY_CUTOFF |
| R51 Donchian EXITS and loss-cut rules | R64-R69, R71 XB/XL/XI, R63, R75 (0 of 40) | HUNT_R64..R71, R75 |
| R72 Breadth Donchian | re-tune, 5 of 5 (design only), Supertrend add-on, R75 rules, 8 position-count rules (R84), MCX (R93) | HUNT_R72, R73, R75, R84, R93 |
| R52 OOV EXITS | g1-g17, R64-R69, R63 | HUNT_R52, R64..R69 |
| Solo 2.0 | A1-A4, B1-B6, R60, R61, R63-R69 | HUNT_R57, R60, R61 |
| Night (R3), VIX divergence, Solo midday | holds, GEX, re-tune, managers, floors; hidden since 15 Oct | R27, R51 V1-V8, R12, R17, R19C, R74 |
| MCX: NG evening breakout, SILVERM morning call, NR7 crude, 12-month trend, US-night silver | R42, R53, R82, R83, R93, R94, R96 (46 ideas) | BRAIN_MCX, HUNT_R42..R96 |

Not run here, with the reason: Solo 2.0's second pick / threshold (needs the R57 model and the brain cube, not in this
session's lake copy; its entry filters are already model inputs); Night's event-eve skips (needs equity_1m breadth and
chain OI; the arm is hidden); US-night silver's stop (its signal needs COMEX and USD/INR data outside the lake); the
MCX 12-month trend's T4 trail (Boss hid the arm on 21 Oct).

## 1. The arms tested (B0 = the app's rule, reproduced first)

Engine: R51's Amendment A clean stores (`R51_CLEAN=1`, Nov 2024 onward), R64's held-contract simulator, R17 liquidity
fills, dated Zerodha charges, 1 lot, the arms' own exits. Each B0 must reproduce the published figure before any
variant runs (asserted in code):

| arm | B0 | must reproduce (design / final) |
|---|---|---|
| Liquidity 15+5 | BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5 pooled, `hunt/r51/fam_liq` | 881 trades Rs 2,57,249 / 357 trades Rs 11,155 (R76, R81, R100) |
| R72 Breadth Donchian | C1 4-of-5, exit XL, `hunt/r72` | 3,071 / Rs 2,16,929 ; 1,202 / Rs 1,71,366 |
| R51 Donchian 20 | exit XL (ladder + L25), `hunt/r71` | 4,498 / Rs 9,998 ; 1,781 / Rs 81,845 (R66) |
| R52 Open outside value | R64 exits with L25, `hunt/r64/r64arms.oov_trades` | Rs 2,90,573 / Rs 48,612 (R66 table) |

## 2. The variants (fixed now; each is a FILTER on B0's candidate signals, removed BEFORE the one-position walk, so a
skipped signal frees its book exactly as the app would; B5 is a sizing rule)

Features (all point-in-time; look-ahead tests for each):
- **VIX intraday**: India VIX 1-minute closes from the lake (`index_1m_INDIAVIX`); the VIX change since the day's 09:15
  open read at the minute BEFORE the entry row (the last minute inside the signal bar). Unknown VIX = the rule cannot
  confirm = the signal is dropped.
- **VIX regime**: the previous session's India VIX close against the median of the 60 sessions before it
  (`index_daily`). Unknown = dropped.
- **Realised vol regime**: the index's 20-session realised volatility (std of daily log returns of its close,
  `index_daily`) ranked among the previous 250 sessions' values; "high" = at or above the median rank.
- **Gap**: the index's day open against its previous session's close (`index_daily`).
- **Bar breadth**: for the 5 indices (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), the colour of the 5-minute bar that
  ends at the signal bar's last minute (close vs open, from the stores' bars5). A missing bar does not agree.
- **Weekday**: the session's weekday.

| id | arm | keep a signal only if ... | idea |
|---|---|---|---|
| **L1 BREADTH3** | Liquidity | at least 3 of the 5 indices' 5-minute bars ending at the signal bar's last minute closed the signal's way (close > open for a call, < for a put) | the whole market joins the break (R72's lesson applied to Liquidity; R79 used trend agreement, not the same bar) |
| **L2 VIXDIR** | Liquidity | a put: VIX up >= 1% since the open; a call: VIX down >= 1% since the open (read one minute before the entry row) | VIX confirms the direction intraday (H12 F09 used the daily change; R15 pre-open only) |
| **L3 RVHI** / **L3b RVLO** | Liquidity | the index's realised-vol rank is high / low | breaks run further in active regimes; options cost more |
| **L4 DOW** | Liquidity | the session's weekday is not the one with the LOWEST net among the design sessions BEFORE the walk-forward quarter (walk-forward pick; for the final, the pick on all design days) | a weekday effect, chosen out of sample |
| **B1 VIXLO** / **B1b VIXHI** | R72, R51 (C1 / C1b), R52 (D1 / D1b) | previous-session VIX close <= / > the median of the 60 sessions before | regime split |
| **B2 GAP** | R72, R51 (C2) | the index's own gap today is within +-0.5% | a quiet open |
| **B3 NO0920** | R72, R51 (C3) | the entry row is not the 09:20 row (the opening 09:15-09:19 bar's break) | the opening bar's break is noise |
| **B4 AM** | R72, R51 (C4) | the entry row is at or before 12:00 | R92's lesson that afternoons lose, on these arms |
| **B5 SIZE55** | R72 | 2 lots when all 5 indices break, 1 lot when 4 do (reported per lot and at B0's total lots) | size by signal strength |
| **D2 FAR** | R52 | the open is beyond the value edge by at least 25% of the value-area width | a decisive open |
| **D3 OPEN3** | R52 | at least 3 of the 5 indices open outside value on the same side today | the whole market gaps the same way |

That is 20 gated tests (L1, L2, L3, L3b, L4, B1, B1b, B2, B3, B4, B5, C1, C1b, C2, C3, C4, D1, D1b, D2, D3). No
threshold is chosen from data except L4's weekday, which is picked walk-forward.

## 3. Periods, measurement, gates (as R80 / R51; never relaxed)

- Design 20 Nov 2024 - 31 Mar 2026; walk-forward window Apr 2025 - Mar 2026 in four quarters (no parameter is fitted
  except L4's weekday, picked on the design days before each quarter). Final 1 Apr - 13 Oct 2026 (the stores' end),
  read ONCE (`results/FINAL_READ`). The final window has been read by about twenty studies since R51, so it is weak
  evidence; the paper days from 14 Oct are the only truly unseen test.
- For every variant and B0, side by side: trades, **win rate**, **net**, per trade, worst day, max drawdown, net without
  the 5 best trades, the walk-forward quarters, and the difference to B0.
- Twins: random-SKIP twins, 500 per test (seed 101): as many candidate signals dropped at random as the filter dropped,
  the books walked again; twin p = share of twins at or above the variant's net. B5: 500 random assignments of the extra
  lot to as many trades, per-lot net.
- A variant PASSES only if ALL hold: (1) net above B0 in the design walk-forward window; (2) net above B0 in the final;
  (3) twin p < 0.05; (4) Benjamini-Hochberg q <= 0.10 across all 20 R101 tests (also reported per arm, as Boss asked);
  (5) at least 40 final trades; (6) net without its 5 best trades above B0's without its 5 best; (7) its win rate AND
  its net are both reported, and a lower final net than B0 fails whatever the win rate.
- Costs: dated charges and R17 fills as every R51-family study; the passing variants are also shown at twice the
  slippage.

## 4. What a pass means (CLAUDE.md)

A passing filter may act on PAPER arms straight away: as a labelled switch on by default for that arm, logged in the
Autopilot change timeline, undoable in one tap, shown to Boss. The skipped signals are kept as labelled would-be rows,
so the plain-rules record (taken + would-be) stays rebuildable; for Liquidity BANKNIFTY's 26 Oct decision the plain
record is therefore untouched. Nothing goes live from this study. A failed idea stays a note here and in the
experiment log (`research/experiments/entries/r101-*.yaml`).
