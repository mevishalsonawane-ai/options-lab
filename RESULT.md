# Enhancement round 1: RESULT (20 Oct 2026)

Branch `claude/enhance-r1` (based on main `f724ec5`, the deep-review fixes). Boss, 20 Oct: "...after that run a loop for
enhancement and adding new features in the application." This round took six of the deep review's ranked ideas
(2, 3, 4, 6, 7 and 9). Idea 1 (one exit coordinator) is left for a later round, as asked. Idea 5 (encrypted diagnostics)
was not done: see "Next" below.

## What was added (plain words)

1. **Overnight stops at Zerodha (idea 2).** When you protect a position you carry overnight (NRML, not intraday), its stop
   and target used to be day orders that Zerodha cancels at the close. Now, once they end, the app rests the same stop and
   target at Zerodha as one GTT, so they hold overnight and at the open even if the phone is off. At the next open the
   GTT is deleted first (and checked gone), then the day's orders go back as before. Two exits never rest together, and
   the GTT is deleted whenever the protection ends or the position is closed. The watch now stays for up to half an hour
   after the 15:40 close, looking only at stops and targets, until the GTT is placed. It only ever adds a stop.
   Jarvis's live trades use these protections, so they get it too. The Strategy Module's carried legs and the arms' live
   trades already had their own GTTs.
2. **Liquidity no-twin rule (idea 3, paper).** On 19 Oct both Liquidity BANKNIFTY books held the same put and were
   stopped together. Now, on paper, if one Liquidity book already holds an option, the other book's entry on that same
   option is not bought: it is kept as a would-be trade, counted apart, so the plain-rules record for the 26 Oct decision
   stays separable (the evening replay still replays every signal). The two books still trade side by side on different
   options. Live is never changed: a live twin is only logged. It is on for paper from this update, logged in the
   Autopilot change timeline, and one tap undoes it (or the switch on the Strategy lab card).
3. **Account stop rule over the whole account (idea 4).** Your rule (-₹40,000 from the best, or -₹13,000 in a month) now
   watches the whole Zerodha account's daily P&L (Zerodha's own day figure, before charges), not just the arms' trades.
   Once reached, it can refuse the bots' new live entries and their automatic live strategy starts. It never touches your
   own orders or any exit. It starts in **shadow**: it only logs what it would refuse until you switch it on in
   Bot settings → Account stop rule. Restarting its count after it is reached takes your PIN.
4. **Stop checks timed honestly (idea 6).** While anything is held, the 15-second stop check now runs every 15 s from
   start to start (it used to wait 15 s after each check finished, so a 3 s check made 18 s). Every gap over 16 s is
   counted and logged; one of 30 s or more is also shown in the app. The daily diagnostics say how many gaps went over.
5. **One "is anything held?" check (idea 7).** Four slightly different lists decided whether something was held (that
   is how the T3 gap happened). Now one shared check decides for the watch's pulse, the 15-second stop check, the arms'
   waits and the smart scheduler. It keeps every source each list had, so nothing is checked less often; a book not read
   yet still counts as held. The diagnostics show what counts as held.
6. **Cap "only logged" count (idea 9).** The daily diagnostics now count the entries the same-direction cap only logged
   and let through (paper entries that could go live, live entries in shadow), apart from the ones it refused, so a
   silent bypass like 20 Oct's shows at once.

One What's New entry (6 sentences) says these in plain words; the indices in WhatsNewChangelogTest and BatteryRestTest
moved by +1.

## Safety

- Nothing new skips or resizes a live trade by itself: the account stop rule and the live no-twin check are shadow only
  until Boss switches them on; the overnight GTT only adds a stop.
- The entry door reads only memory for the account stop rule (no disk, settings or network on the order path).
- Paper changes (the no-twin rule) are logged in the Autopilot timeline and undoable in one tap; paper changes never
  carry over to live.
- Open positions first: the shared held check only adds sources; the stop-check window only brings checks sooner; the
  evening look only adds a stops-and-targets look after the close.

## Tests

(filled in below after the final runs)

## Next: ranked ideas for round 2

(filled in below)
