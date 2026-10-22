# Arms audit (22 Oct 2026) and the R101 enhancement study

Boss asked on 22 Oct: *"Check each and every arm also hidden arms, check if there are issues or not. Also find if could
enhance it by increasing the profit / winrate."* This page answers both, plainly. Part 1 is the audit of every arm,
hidden ones included, against its research write-up and the safety rules; part 2 points to the R101 study.

**Nothing changed on paper or live from the research study (R101: 0 of 20 ideas passed).** The audit's bug fixes change
how the arms EXIT and how hidden arms are gated; no arm's entry rule, stop, target or lot changed. Every fix only lowers
risk. The plain-rules Liquidity record stays separable for the 26 Oct decision.

## Part 1: the audit table

| Arm | Verdict | In one line |
|---|---|---|
| Liquidity 15+5, BANKNIFTY 15-min and 5-min | ok, exits fixed; rule differs (expiry days), needs Boss | Rules match the research; the app also trades expiry days, which every study skipped (see "Needs Boss"). |
| Liquidity 30+5 FINNIFTY, 15+5 MIDCPNIFTY (Boss's choice, paper) | ok, exits fixed | Same as above; labelled "Boss's choice"; parked indices handled. |
| Hero ₹1,000 ticket (Boss's choice, paper) | bugs fixed; rule differs (trail start), needs Boss | Its 15:05 sale could race the expiry square-off into a paper short (fixed); its "trail after 2×" default is the cell R55 found worst. |
| FT3 near-ATM morning (Boss's choice, paper) | ok, exit hardened | Rules match the 21 Oct forward-test spec; its sale is now reduce-only. |
| R51 Donchian 20 (record-only) | bug fixed | Would-be trades were counted in its 40-trade forward test; now only real trades count. |
| R52 Open outside value | ok | Rules match; the added -30% stop is labelled. |
| Solo 2.0 | ok; rule differs (documented) | Its IV inputs are computed from option prices, not the lake's vendor IV column (said in the code). |
| R72 Breadth Donchian (primary) | ok; needs Boss (its kept place) | Rules match and the 09:20 bar is taken as research does; the "kept place" is in effect a cap of 1 for every other arm on every bar (see "Needs Boss"). |
| ORB, ORB Fresh, ORB Sweep, Range Fade (hidden) | bugs fixed | Time and Stop-for-today exits were skipped when no price could be read; the expiry square-off was booked as "closed by you"; a hidden arm could still post an approval. |
| Hero (expiry) ₹5,000 (hidden) | bug fixed | Its 15:05 sale could race the expiry square-off into a paper short; fixed at the paper book. |
| Night (R3) (hidden) | ok; rule differs (low) | Overnight hold, 09:16 sale and restart recovery are right; its OI history stops updating while hidden (judged against a stale window on a restore). |
| VIX divergence (hidden) | bugs fixed (low) | An unreadable "stop for today" sold a held position; its sale was not capped to what the book held. |
| Pine auto-trade scripts (hidden) | bug fixed | A hidden script waiting for its exit re-entered on a signal flip; now it exits and buys nothing more. |
| Pine scripts and indicators | ok | Signals on the index only (option-chart scripts are refused); stop, target and lock compulsory. |
| Jarvis's trade ideas (hidden) | fixed (safety rule) | A live path existed behind Live + AI-live + a proven record; CLAUDE.md says Jarvis's own trades stay on paper. Now paper always. |
| Telegram calls (Boss's choice, paper) | ok | Per-chat switch off by default; live only through the arms' live door with PIN. |
| Forge ideas on paper (hidden) | ok | No new ideas while hidden; exits keep running. |
| Shadows re-armed on paper (hidden) | bug fixed | Same gap as Pine: a re-armed shadow could still buy while hidden. |
| Scheduled strategies (hidden) | ok | Imported ORB baskets stay blocked (TODO A4); only START is held back while hidden. |
| Watchlist (shadow), Solo (midday) | ok | No order path / paper only; rules match. |
| NG evening breakout (MCX, paper) | ok (fixed 22 Oct earlier); needs Boss | The put exit and the unreadable-stop bugs were fixed in this branch's first commit; research R53 and R83 say switching it off lowers risk. |
| US-night silver (MCX, paper) | bugs fixed | Its short's stop never filled in the paper book; it could trade against the hidden trend arm's leg on the same contract; a failed exit left no stop. |
| SILVERM morning call, MCX 12-month trend (hidden) | ok | Exits keep running; no new legs. |
| NR7 crude (record only) | ok | No order path, ever. |
| Hidden-arms machinery | bug fixed | While its file could not be read, nothing was blocked and the file was never read again. |
| Shared machinery (positions first, 15 s stop lane, day lock, LossBreaker, kill switch, guard, expiry square-off, holidays, market hours, lot table, live mirror) | ok | NIFTY 65 and BANKNIFTY 30 are in the lot table; every lot comes from the contract list; nothing stale. |

## The bugs, one line each (what it did on the phone)

1. **NG evening breakout's put sold a minute after it was bought** (fixed earlier on this branch). The put's exit was judged as if it were a call: 20 Oct 290 PE and 21 Oct 275 PE in your diagnostics.
2. **An unreadable "stop for today" sold every held MCX position** as "operator_stop" (fixed earlier on this branch). The same mistake was also in VIX divergence and the passed arms; fixed there too.
3. **An arm's 15:05 sale could open a paper short.** The Hero tickets, FT3 and ORB read "held", the app's 15:05 expiry square-off closed the option, and the arm's sale then went short. Every arm's exit now only reduces what is held, and a close takes the stop out of the book first.
4. **ORB's Stop-for-today and 15:10 exits waited for a price.** With the feed down they held until the 15:15 backstop. They now sell at market.
5. **ORB's expiry close was booked "closed by you"**, and live raised a false "stop at Zerodha was cancelled" alert. Now it says "expiry square-off".
6. **A hidden Pine script or re-armed shadow could buy again** while it waited for its last exit. Now it only exits.
7. **While the hidden-arms list could not be read, nothing was blocked** (Jarvis's ideas and Forge ideas could enter) and the list was never read again. Now those two stay blocked, no hidden arm can be switched on, and the list is re-read every 20 seconds.
8. **A hidden ORB arm could still post an "approve BUY" notification** in the seconds after its exit. Not any more.
9. **The US-night silver short's 3% stop never filled in the paper book.** The arm's own minute check still covered it. It now rests and fills like a real stop.
10. **US-night silver could trade against the hidden trend arm's leg** on the same SILVERMIC future, closing both with wrong records. It now skips that day and says why.
11. **A failed MCX market exit left the position with no resting stop** until the next pass. The stop now goes back at once.
12. **Cancelled entry orders showed as your own.** On 22 Oct the Hero ticket's two cancelled SENSEX 73,800 CE orders, and on 21 Oct a Liquidity entry cancelled for "no price", were labelled "IraAlgo-Android". Every arm now names its entry order when it is placed.
13. **Would-be trades counted in the passed arms' 40-trade forward test**, at zero spread. Only real trades count now.
14. **R72 went last for no reason** when R51 was record-only with its Trade-live switch on. A record-only arm is never mirrored live, so R72 now decides first.
15. **Jarvis's own trade ideas had a path to Zerodha** once Live, AI trades, a proven record and your fingerprint lined up. Your rule says they stay on paper. They now always do.
16. **Liquidity's exits could wait behind repeated candle downloads** while the feed lagged. Your 21 Oct diagnostics show "stops looked at 15 s or more apart" twice. The read is now kept for 5 seconds.
17. **A Liquidity approval could buy up to an hour late**, after 14:00. It now lapses at the next bar's close, or 10 minutes at most.
18. **Smaller fixes.** The Hero ticket now keeps watching an entry order it could not cancel. Liquidity's arming words now describe the strike, the index stop and the time stop correctly. SENSEX expiry is now Thursday in the "days like today" context.

19. **The day-lock line printed the limit as if it were the day's P&L** (from a parallel session's work, merged here). It now says "reached +Rs 11,514 (limit +Rs 8,000)".
20. **The lot table knew only NIFTY and BANKNIFTY** (same parallel session). It now has FINNIFTY, MIDCPNIFTY and SENSEX rows too. Those rows are not yet checked against NSE's bhavcopy; every trade still reads its lot from the contract list first, so the table is only a fallback.

A parallel session also wrote the rule text for skipping Liquidity on an index's own expiry day and for a 2-minute fill window. It is not wired into the arm yet, so Liquidity still trades expiry days (see "Needs Boss").

## What changed on paper

Nothing from the R101 study: no arm's entry, stop, target or lot changed. The fixes above change how exits are sent, how hidden arms are gated and how records are labelled. All of them only lower risk. They are listed in What's New as "Fixes from the arms audit".

## Needs Boss

- **Liquidity trades on expiry days. Every study it rests on skipped them** (research could not price those contracts). The 26 Oct comparison with the research is therefore not like for like on expiry days. Keep trading them on paper, or skip them?
- **The Hero ₹1,000 ticket's trail starts "after 2×", marked as a placeholder.** R55 found that the worst of five; it recommends starting the trail at the fill. Until a ticket doubles there is no stop below it but the 15:05 sale.
- **R72's kept place blocks the second same-direction place on every bar while R72 is on**, even when R72 has no signal (22 Oct diagnostics: Liquidity 15m refused at 09:30). Keep it, or keep it only when R72 has a break due?
- **The no-twin rule for Liquidity is on and untested** (E1). Its would-be twin has no exit, so the 5-minute book's plain-rules record cannot be rebuilt exactly.
- **NG evening breakout: research R53 and R83 say switching it off lowers risk.** It is still on, by your choice.
- **Night (R3) stops updating its OI history while hidden.** A restore judges its first weeks against a stale window.
- **NSE's Muhurat session on Sunday 8 Nov** is not in the holiday table yet (MCX's is). No arm trades outside 09:15, so this is low impact.

## Part 2: R101, can the arms make more or win more often?

No. **0 of 20 pre-registered ideas passed** (full write-up: `research/HUNT_R101_ARMS_ENHANCE.md`; tables in `research/hunt/r101_arms_enhance/results/report.md`). Win rate and net, final period (1 Apr - 13 Oct 2026, 1 lot, after charges):

| Arm today | Final trades | Win rate | Net |
|---|---|---|---|
| Liquidity 15+5 | 357 | 37% | Rs 11,155 |
| R72 Breadth Donchian | 1,202 | 46% | Rs 1,71,366 |
| R51 Donchian 20 | 1,781 | 44% | Rs 81,845 |
| R52 Open outside value | 293 | 52% | Rs 48,612 |

Two findings worth your attention:

- **The 09:20 opening bar carries both Donchian arms.** Dropping its breaks costs R72 Rs 1,49,195 in design and Rs 76,078 in the final; R51 Rs 87,186 and Rs 97,444. Never add a "skip the first bar" rule.
- **A VIX-confirmed Liquidity entry wins more often but makes less.** Win rate 48% vs 44% in design and 40% vs 37% in the final. It made Rs 37,912 more in the final but Rs 54,136 less in design, and lost in two of four walk-forward quarters. A higher win rate, not more money.

Filters that raise the win rate on these arms lower the profit: the trades they drop are weak one by one but make money together. Sizing up on the strongest R72 breaks earned no more per extra lot than random extra lots, with twice the worst day.
