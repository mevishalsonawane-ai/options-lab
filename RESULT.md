# 22 Oct: every arm checked (hidden ones too), the bugs fixed, and whether any arm can make more

Boss, the short version:

- **Every arm was read line by line against its research and the four days of your phone's diagnostics (19-22 Oct).**
  27 arms and shared parts were checked. **16 real bugs were fixed**, with tests; the rest are labelling fixes, "fine as
  is", or things only you can decide (listed under "Needs you").
- **The worst bug: the NG evening breakout sold every put within one minute of buying it.** It judged a put's exit with
  the call's side, so a down-break put always looked "back at the range middle". Your 20 and 21 Oct NG trades show it
  (bought 19:54:21, sold 19:55:12; bought 20:57:12, sold 20:58:03). Fixed; the arm now holds a put as it holds a call.
- **Liquidity 15+5 (your 26 Oct live candidate) had three rule gaps against its own research**, all now closed and
  labelled: it could buy a bar up to a whole bar late (after holding, or after the app was away); it traded the index's
  own expiry day (the research skips it; 27 Oct is the monthly expiry of all three indices); and signals the day lock
  or the bot's stop suppressed left no record. BANKNIFTY's plain-rules record before 26 Oct is untouched; from now the
  skipped signals are kept as labelled would-be rows, so the plain record can still be rebuilt.
- **Hidden arms could open new trades in three ways** (before the cleanup file was read; a hidden Pine script waiting
  for its exit; a hidden shadow variant). All three doors are shut; the open positions keep their exits.
- **Can any arm make more, or win more often? No.** 20 pre-registered variants on Liquidity, R72, R51 and R52: **0 of
  20 pass.** The two findings worth knowing: the opening bar's break (bought at 09:20) is where both Donchian arms make
  most of their money, and every filter that raised the win rate made less money. Details with win rate and net side by
  side below. Nothing changed on paper from the research; nothing new is in shadow.

## The audit table

| arm | status | what was found |
|---|---|---|
| ORB, ORB Fresh, ORB Sweep, Range Fade (hidden) | **bug fixed** | On an expiry day the 15:05 square-off was booked "closed by you" and dropped from the forward tally (research R3 counted those trades); now its own reason, counted. The resting paper stop was left in the book after the square-off (a tick through it could open a short); cancelled first now. Rules match R3 / ENTRY_CUTOFF (14:00; Sweep 14:25; Fade 13:55; -40/+40; 15:10). |
| Hero (expiry) ₹5,000 (hidden) | labelling fixed | Its live copy started ON although its signal reads the option's own price (not the default); now off until you turn it on. Stale "never reaches Zerodha" words corrected. Rules match R21 / R25. |
| Liquidity 15+5: BANKNIFTY 5m, 15m | **bug fixed; rule differed from research** | (1) A bar that closed while the book held, or while the app was away, was decided and bought up to a whole bar late; now a 3-minute window after the close, as the research (fills within 2 minutes or skips). (2) Entries on the index's own expiry day, which the research skips; now skipped, logged once a day. (3) Day-lock skips and the bot's stop-for-today left no record; now would-be rows "day lock" / "bot stopped for today", counted apart. (4) An unfilled, cancelled entry showed as nobody's order (21 Oct 09:30:03); tagged at once now. (5) A trade run under a lab exit variant is now kept out of the plain-rules record. 21-22 Oct diagnostics otherwise clean: the 22 Oct trade's 20-minute time stop did what it says. |
| Liquidity 15+5: FINNIFTY 5m, 30m; MIDCPNIFTY 5m, 15m | **bug fixed** (as above) + labelling | Same fixes. Each index's row now says why it runs for good: FINNIFTY "failed its final research read, research says parked; paper only, your choice (15 Oct)"; MIDCPNIFTY "passed H4's gates; paper only, your choice (07 Oct)". |
| Night (R3) (hidden) | **bug fixed** | Once 15:20:30 passed without the 15:19 bar it decided on an older minute; now it decides only on the 15:19 bar or skips the night, naming the last minute seen. Its rules now say "no stop overnight on paper (the live copy has a -30% safety GTT)"; its OI window is labelled "over N of the last 60 sessions" after the hiding gap. |
| VIX divergence (hidden) | ok | Matches research R1 N13 exactly; labelled "not proven". |
| Hero ₹1,000 ticket (your choice, R50) | **bug fixed** | Its exits fetched the option's candles over the network under the arm lock inside the 15-second stop lane, delaying the other arms' exits; the read now happens outside the lane. Unfilled entry attempts and exit retries were untagged (the two "IraAlgo-Android" orders at 12:30 on 22 Oct); tagged now. An unreadable stop-for-today could sell a held ticket; never again. One status line per index overwrote the call's verdict with the put's; both kept. Buying both a call and a put at 12:30 is the rule (R50 / R63), and the Trend watch selling both legs is R63 as tested. Your switch-off now writes a timeline entry. |
| FT3 near-ATM morning (your choice, R98e) | **bug fixed** (owner tags, unreadable stop, timeline entry) | Rule matches the golden file; lots are sized by the LIMIT price, not the fill (0-17% under ₹5,000), now noted. |
| R51 Donchian 20 | **bug fixed**; needs you | The 40-trade forward bar counted would-be rows (cap / twin trades never bought) and let their estimated nets decide the switch-off; now real paper trades only. When an index's earlier sessions are not loaded, its breaks before 11:00 are impossible; now said in its status. **Note: R51 is OFF on your phone, not record-only, so the R72-vs-R51 comparison has no R51 record** (see "Needs you"). |
| R52 Open outside value | ok | Rules match R52 g17 with the app's exits; the forward-bar fix applies. |
| Solo 2.0 | ok | One pick a day as R57 (a pick that cannot be bought ends the day); the forward-bar fix applies. |
| R72 Breadth Donchian | **bug fixed**; labelling | Forward bar as R51. After one Undo of the 16 Oct lock its new trades ran exit set XB (+30% target), which R72 never passed research with; the rules now name both exit sets and such trades are labelled. The docs of all four passed arms said "paper only, never live"; since 18 Oct each has a live copy behind Live + "AI trades go live" + its Trade live switch + Shadow off (PIN), your 18 Oct choice; the words now say so. |
| Solo (midday) | **bug fixed** | It opened a new paper trade while you had stopped the bots for today; refused now. Its card quoted research that is no longer in the repo; it now quotes R57's measured -₹21,589 on 36 trades, labelled "not proven, your choice". |
| Watchlist, OI wall, gamma (shadow) | ok | Place no order of any kind; every line says "information only". |
| Pine auto-trade (hidden) | **bug fixed** | A hidden script still waiting for its exit sold on the next signal change AND bought the opposite option; it now sells only. The cleanup's switch-off sold a holding the read had missed; it now winds the script down to its own exits. Pine signals read the index chart only (option charts refused). |
| Jarvis's trades (hidden) | ok | Self-taken ideas are paper-or-nothing; approved ideas go live only with Live + AI-live + a proven record; a locked phone hears nothing about the account. |
| Telegram calls (paper) | ok | Your labelled choice, off per chat by default; no broker path; 3 a day and a ₹3k cap. |
| Forge ideas (hidden) | labelling fixed | An idea approved while the forge is hidden was hosted and said "now trades on paper"; it now says it is hidden and trades nothing until restored. Failed ideas cannot trade. |
| Shadows re-armed on paper (hidden) | **bug fixed** | A re-armed variant's paper trade had no resting stop (checked only once a minute on a price up to 50 s old); it now rests a -40 SL-M in the paper book like ORB. A hidden variant could open its second trade; shut. The switch-off read a stale copy of the rows on a busy lock and could silently do nothing; it now reads the real rows. |
| Scheduled strategies (hidden) | ok | Blocked while hidden; the imported ORB baskets stay blocked (by name only; a renamed import would be armable, noted). |
| NG evening breakout (MCX, on) | **bug fixed**; needs you | The put exit bug above. Record words now carry R53's re-test (-₹3,508 on 115 final trades: fail); switching it off is your call. It takes no option trade on the NG expiry eve and day (no roll to the next month), now said in its rules. |
| SILVERM morning call, MCX 12-month trend (hidden 21 Oct) | ok | Open nothing new; held positions keep their exits. |
| US-night silver (MCX, "not proven") | ok, one note | As specified (09:05 entry, +3% resting stop, 23:20 exit). The short's BUY stop is not wick-filled between passes as a SELL stop is (paper only; the arm's own check closes it at the next pass). Not changed. |
| NR7 crude (record only) | ok | No order path at all. |
| Gold arms | ok | IraGoldAlgo only; paper and notifications; no order path. |
| Shared: restart recovery | ok | Paper, ORB, arms-live, MCX and the passed arms' books are read in the first batch; the watch starts before any analysis worker; memory trims never touch the position watch. |
| Shared: day lock / loss breaker / kill switch / guard | fixed words; needs you | The day-lock line printed the LIMIT, not the P&L; now both, signed. Exits bypass every entry gate. **Engine and app disagree on the kill switch and exits** (see "Needs you"). An unreadable stop-for-today never sells a held position (MCX, Hero ticket, FT3); entries still treat it as stopped. |
| Shared: market clock, holidays, expiry | fixed words | NSE 15:30 / 15:40 / 15:05 and the 2026 holiday lists are right; the expiry weekday was hard-coded Tuesday for every index in Jarvis's words and lessons (SENSEX is Thursday); now per index, from the instrument list when the app has it. No NSE Muhurat session is modelled (8 Nov 2026). |
| Shared: lot sizes | fixed | The engine's lot table had only NIFTY and BANKNIFTY; now every index with dates (NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20 today). Trading itself always read the instrument list, which was right. |
| Shared: diagnostics | improved | The day file now has a line each for the hidden arms, Pine, shadows, Telegram paper and Jarvis's trades (counts only). |

## What changed on paper (each logged, labelled, undoable where it is a switch)

- **Liquidity 15+5, all books:** no entry on the index's own expiry day (the research rule; 27 Oct is the monthly expiry);
  no entry on a bar seen more than 3 minutes after its close; both are refusals only, recorded as labelled would-be rows.
  Day-lock skips and the bot's stop-for-today are recorded the same way. Live copies refuse the same way (lower risk only).
- **NG evening breakout:** holds a put as it holds a call (the exit rule was wrong, the rule itself is unchanged).
- **R51, R52, Solo 2.0, R72:** the forward bar counts real paper trades only.
- **Hero (expiry) ₹5,000:** its live copy's switch now starts off (paper unchanged).
- **Night, Solo (midday), Pine, shadows, forge:** the fixes above; none adds a trade.

## What is in shadow

Nothing new. No change here touches a live order: every live-path change is a refusal (Liquidity's expiry day and fill
window) or words.

## Needs you

1. **The paper-only period is not set.** Your diagnostics show `paper_only_period: False` on all four days although
   13-26 Oct is paper-only. Settings → Zerodha → paper-only period until 26 Oct (one tap, no PIN: it only lowers risk).
2. **Kill switch and exits.** The engine's account guard refuses EXITS while the kill switch is on; the app lets every
   exit through regardless. MAINTENANCE.md's golden rule says "exits always go". Say which you want; I did not change
   either side.
3. **Live stops at Zerodha.** A live ORB/Liquidity position whose exchange stop is gone (refused, cancelled at Kite web,
   lost reply) is re-rested only when "exchange stops" is on (off by default); the GTT backup was dropped on 18 Oct. Turning
   "exchange stops" on is a live change, so it is yours.
4. **R51 is off, not record-only.** If you want the R72-vs-R51 comparison, switch R51 on from its row (paper).
5. **The passed arms' live copies start ON** (behind Live + AI-live + Shadow off, your 18 Oct choice). On 19 Oct you
   flipped R72's live switch off and on. Nothing reached Zerodha (Live was off all four days). Keep or change: your call.
6. **Hero ₹1,000 ticket's trail start.** R55 found "from the fill" best on its own; the trend exit (R63) was tested only
   with "after 2×", the setting in force. Both facts are now on the setting; the choice is yours.
7. **NG evening breakout:** R53 re-ran it on the fuller lake and it lost (-₹3,508 on 115 final trades). Switching it off
   is a risk-lowering paper change you can make from its row.
8. **Muhurat (Sun 8 Nov 2026):** NSE's evening session is not modelled; an arm armed that evening would treat it as a
   normal day. Keep the arms off that evening, or ask me to add the window (MCX's is already in).
9. **Hero ₹1,000 ticket after a restart:** its trail reads the minute bars the arms pass last fetched; for the first minute
   after a restart (until that pass runs) only the stream price, the resting target and 15:05 act. Say if you want a read at
   start-up too.
10. **Liquidity's paper exit** cancels the resting -15% stop without checking whether the stop was touched in the gap
   (19 Oct FINNIFTY put booked an index stop at -16.2% against a stop at -15%). It needs that contract's ticks to confirm;
   the shared paper engine is unchanged.

## Can any arm make more, or win more often? (R101, pre-registered, 0 of 20 pass)

Full write-up: `research/HUNT_R101_ARMS_ENHANCE.md`; tables: `research/hunt/r101_arms_enhance/results/report.md`. Every B0
was reproduced to the rupee before a variant ran. Design 20 Nov 2024 - 31 Mar 2026 (walk-forward Apr 2025 - Mar 2026),
final 1 Apr - 13 Oct 2026 read once. Gates: above B0 in the walk-forward AND the final, random-skip twins p < 0.05,
BH q <= 0.10 across all 20 (and per arm), >= 40 final trades, above B0 without the 5 best trades. Win rate and net side
by side; a higher win rate with a lower net fails.

**Liquidity 15+5 (B0: design 881 trades, 44% win, +₹2,57,249; final 357, 37%, +₹11,155)**

| variant | design: trades / win / net (vs B0) | final: trades / win / net (vs B0) | twin p | q | verdict |
|---|---|---|---|---|---|
| L1 3 of 5 indices' bars agree | 733 / 44% / 1,97,895 (-59,354) | 303 / 37% / 115 (-11,040) | 0.74 | 1.0 | fail |
| L2 VIX confirms the direction | 411 / 48% / 2,03,113 (-54,136) | 244 / 40% / 49,067 (+37,912) | 0.02 | 0.40 | fail: wins more often, made ₹54k less in design and lost in 2 of 4 quarters |
| L3 high realised vol | 380 / 43% / 1,46,868 (-1,10,382) | 243 / 35% / -21,162 (-32,317) | 0.92 | 1.0 | fail |
| L3b low realised vol | 501 / 44% / 1,10,382 (-1,46,868) | 114 / 41% / 32,317 (+21,162) | 0.07 | 0.74 | fail |
| L4 drop the worst weekday | 815 / 43% / 2,47,812 (-9,438) | 288 / 40% / 18,997 (+7,842) | 0.28 | 1.0 | fail |

**R72 Breadth Donchian (B0: design 3,071, 47%, +₹2,16,929; final 1,202, 46%, +₹1,71,366)**

| variant | design | final | twin p | q | verdict |
|---|---|---|---|---|---|
| B1 VIX low regime | 1,535 / 46% / 1,19,155 (-97,774) | 797 / 45% / 1,11,187 (-60,179) | 0.72 | 1.0 | fail |
| B1b VIX high regime | 1,536 / 47% / 97,774 (-1,19,155) | 405 / 48% / 60,179 (-1,11,187) | 0.84 | 1.0 | fail: wins more often, makes far less |
| B2 gap within 0.5% | 2,434 / 46% / 1,56,609 (-60,321) | 750 / 44% / 1,02,363 (-69,003) | 0.81 | 1.0 | fail |
| B3 skip the 09:20 break | 2,770 / 45% / 67,734 (-1,49,195) | 1,016 / 43% / 95,288 (-76,078) | 1.0 | 1.0 | fail: the opening bar's break is the arm's best part |
| B4 mornings only | 1,614 / 48% / 2,05,942 (-10,987) | 599 / 47% / 89,799 (-81,567) | 0.55 | 1.0 | fail: wins more often, makes less |
| B5 2 lots on 5-of-5 | ₹107 a lot vs 71 (+1,10,442 at B0's lots) | ₹161 a lot vs 143 (+22,199 at B0's lots); worst day -44,435, drawdown ₹1.12 lakh | 0.47 | 1.0 | fail: the extra lot earned what any extra lot earns |

**R51 Donchian 20 (B0: design 4,498, 45%, +₹9,998; final 1,781, 44%, +₹81,845)**

| variant | design | final | twin p | q | verdict |
|---|---|---|---|---|---|
| C1 VIX low | 2,311 / 45% / -4,386 (-14,384) | 1,265 / 44% / 28,538 (-53,307) | 0.88 | 1.0 | fail |
| C1b VIX high | 2,187 / 45% / 14,384 (+4,386; walk-forward -50,266) | 516 / 45% / 53,307 (-28,538) | 0.39 | 1.0 | fail |
| C2 gap within 0.5% | 3,602 / 45% / 43,012 (+33,014; walk-forward -46,480) | 1,199 / 43% / 48,703 (-33,142) | 0.69 | 1.0 | fail |
| C3 skip the 09:20 break | 4,291 / 44% / -77,188 (-87,186) | 1,672 / 43% / -15,599 (-97,444) | 1.0 | 1.0 | fail |
| C4 mornings only | 2,769 / 46% / 56,693 (+46,694; walk-forward -34,837) | 1,061 / 45% / 17,487 (-64,358) | 0.73 | 1.0 | fail |

**R52 Open outside value (B0: design 706, 56%, +₹2,90,573; final 293, 52%, +₹48,612)**

| variant | design | final | twin p | q | verdict |
|---|---|---|---|---|---|
| D1 VIX low | 358 / 57% / 1,04,797 (-1,85,777) | 217 / 52% / 29,554 (-19,058) | 0.62 | 1.0 | fail |
| D1b VIX high | 348 / 54% / 1,85,777 (-1,04,797) | 76 / 51% / 19,058 (-29,554) | 0.35 | 1.0 | fail |
| D2 open far outside value | 589 / 56% / 2,91,751 (+1,178; walk-forward +8,753) | 249 / 53% / 40,315 (-8,297) | 0.53 | 1.0 | fail |
| D3 3 of 5 indices open outside | 521 / 57% / 2,74,819 (-15,754) | 239 / 49% / 12,049 (-36,563) | 0.95 | 1.0 | fail |

Already exhausted and not re-run (cited in the pre-registration): ORB family (R3, R49, R92), Hero and the Hero ticket
(R21-R55, R98), FT3 (a frozen forward test), Liquidity exits and locks (R100 and before), Donchian exits (R64-R71), the
MCX arms (R42-R96). Not run, with the reason: Solo 2.0's second pick (needs the R57 model and the brain cube), Night's
event-eve skips (hidden; needs stock breadth and chain OI), US-night silver's stop (COMEX data outside the lake), the
12-month trend's trail (you hid the arm on 21 Oct).

## Tests and checks

- Engine, ira-core and jacoco: green on the first pass of the fixes; the research checks: `pytest research/brain/tests`
  183 passed, `features catalog --check` up to date, `experiments/validate.py` clean with the 20 new entries,
  `test_r101.py` 20 passed; the secret grep prints only CLAUDE.md's own line.
- App tests: the 31 changed classes (535 tests) were compiled and run locally with the Android SDK on the first pass of
  the fixes (green after four test adjustments); the fixes were then rebuilt after a container loss and verified again by
  CI on this branch (see the commit log and CI).

## Not part of this branch

The parallel branch `claude/diag-1022` (R72 decides first, a reserved cap place, the 15-second stop lane, Liquidity and
ORB entry pricing at the signal, lighter night start-ups) had not merged to main while this was written; nothing here
duplicates it. One Liquidity edit (the owner tag right after the paper order is placed) touches the same lines as its
entry-price retry and needs a two-line merge when it lands (the tag belongs inside its `buyPaperEntry`).
