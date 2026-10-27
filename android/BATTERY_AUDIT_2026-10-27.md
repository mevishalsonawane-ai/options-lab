# Battery audit, 27 Oct 2026

Boss, 27 Oct: "Check the battery usage too; the battery drained a lot today."

This note is in plain English. The code changes are on branch `claude/battery-audit-1027`.

## What the phone told us

The data comes from the phone's own redacted diagnostics for 19–27 Oct. They hold no personal data.

| Day | What happened |
|---|---|
| 25 Oct (Sat) | Android killed the app several times for "excessive CPU usage: excessive binder traffic during cached". That means many calls into Android while the app sat in the background with no watch running. |
| 26 Oct (Mon) | Battery hit 15% four times (13:05, 19:43, 21:56, 22:49). "Phone is very hot" 12:33–12:59. 38 stream reconnects: 22 "no data from Zerodha", 15 "the phone's network changed", 1 "internet dropped". 52 background trims, 14 worker overruns. Battery saver was on. |
| 27 Oct (Tue) | 15% at 16:13 (back to normal at 18:24). 24 reconnects, 60 trims, saver off. Candle agent: 101 runs, slowest 5% took the full 2-minute limit. Jarvis words: 421 runs, max 2 min 48 s. |

Your in-app line on 26 Oct at 12:20 showed:

- stream on for 196 min;
- the order-flow pump on for 196 min, with 25 instruments in Zerodha's "full" mode;
- the tick recorder on for 185 min;
- Jarvis's words every round, p95 14.1 s, worst 2 min 3 s, over 194 runs;
- the candle expert p95 7.8 s.

The uploaded file did not have your "Battery today" line at all. It does now (see below).

## The top drains, with rough estimates

These are estimates from the code and the run counts above, not measured on the phone. A phone has no per-loop battery meter. The share is of the app's own drain on a normal market day with nothing held in the evening.

1. **The screen, the radio and heat (not the app's code): about 35–45%.**
   - Reconnects on a changing network: each one opens a new secure connection and subscribes all 39 instruments again.
   - Mobile data while the phone is hot.
   - On 26 Oct, 15 of the 38 drops were the phone itself switching networks, mostly in bursts (12:55–12:57, 21:00–21:01, 22:34–22:50).
2. **Analysis that only produces words or knowledge: about 20–30%.**
   - The Candle agent ran a batch every 5–10 minutes from 09:00 to 23:30, also on MCX evenings with nothing held. Its slowest 5% hit the full 2-minute limit (101 runs on 27 Oct).
   - Jarvis's words round ran every minute in the session (421 runs on 27 Oct, worst 2 min 48 s).
   - The order flow followed 25 instruments in full mode. Every price packet carries the 5-level order book, which is parsed four times a second. Its reads are SHADOW only: TODO.md S7 says order flow, absorption and the trap guard are still not proven (77 tested order-flow rules did not pay).
3. **Calls into Android ("binder traffic"): about 10–15%, and the cause of the 25 Oct kills.**
   - The health check asked Android for the battery %, the charger and the temperature every 10 s. That is about 1,080 calls an hour on an MCX evening.
   - The history keeper asked for the battery and the saver twice for each of its ~62 pieces, at night.
   - The live position cards asked for the battery every 15 s window. The recorders and the stream asked for the saver.
   - Every restart by an hourly night job reads ~18 books and arms the alarms again, about 150–300 calls each time.
4. **The stream's reconnect loop at night: varies.** It had no market-hours exit of its own. If the watch ended by being cancelled or destroyed rather than by its normal end, the loop kept reconnecting while the app was cached, until the 06:00 token expiry.
5. **Recording (tick recorder, big-move recorder): about 10%.** This is research data you asked for. It now narrows at 30% instead of 20%.
6. **The watch itself: about 10%.** This is the price of protecting open positions. It cannot be cut (see the last section).

## What changed (27 Oct)

All of these apply only when **nothing is held**. With any position or order open, paper or live, NSE or MCX, every pace is exactly as before.

| Change | When it applies | Saving (estimate) |
|---|---|---|
| Candle agent rests | Battery 30% or less (not charging), or Android's saver on | its whole batch, up to 2 min of CPU each |
| Candle agent reads every 10 min instead of 5 | Screen off | half its batches |
| Candle agent rests on the MCX evening | Nothing held on MCX and no MCX arm in its entry minutes | ~80 batches a week of evenings |
| Jarvis's words round every 5 min | Low battery or saver (every 2 min with the screen off; it was every 1 min) | 50–80% of the rounds |
| Order flow follows only the charted option | Low battery or saver | the 25-instrument full-mode feed and the 4×/s parse |
| Big-move recorder and tick recorder | Narrow or let the stream go at 30% (was 20%) | recording at low battery |
| Reconnect waits on a flapping network | Grow to 16 s with a small random spread (was 4 s) | fewer reconnect storms; a position opening ends the wait at once |

These changes apply always:

- **Battery, saver, screen and temperature are asked of Android at most once every 30 s** (they were asked every 3–10 s in several loops). This is the main fix for the binder-traffic kills. A 15% or "very hot" warning is still raised within half a minute.
- **The stream's reconnect loop now stops outside market hours.** That means NSE's day to 15:45, and MCX's hours only while something is held or working there. A cancelled watch no longer leaves it reconnecting all night.
- **"Battery today" is in the uploaded diagnostics**, under health → lines → battery. It lists:
  - the loops' minutes;
  - the MCX passes;
  - the reconnects;
  - the Candle agent's batches and Jarvis's words rounds;
  - the order flow's resting minutes;
  - the minutes at 30% or less.

  The figures are saved on the phone, so a restart or the next morning's catch-up upload keeps the day's count. They hold loop names, minutes and counts only, no personal data.

## What you can do

1. **Charge during market hours**, or keep the phone on the charger from 09:00 to 15:40. On the charger nothing rests, and the drain does not matter.
2. **Stay on one network.** Turn off "switch to mobile data automatically" (Wi-Fi assistant / adaptive Wi-Fi) while trading. 15 of 26 Oct's 38 drops were the phone switching networks.
3. **Avoid heat.** Keep the phone out of the sun, take the case off while charging, and don't charge and run the chart for hours together. Heat makes the battery drain faster and makes Android slow the app.
4. **Optional features you can switch off, and what they cost:**
   - Order flow and its big-move recorder (Home → Automation card → Order flow → Big moves): the biggest of the optional drains, about 10–15% of the app's use on an NSE day. It is shadow information only, not proven.
   - Candle agent: about 5–10%, knowledge only.
   - "Listen for Jarvis" set to "Only when unlocked or charging" (Jarvis's page): saves the microphone's keep-alive when the phone is locked on battery.
   - Tick recorder ("Record live data" on the Recordings card): about 5%, research data. Every day not recorded is lost, so keep it on if the research needs that day.
   - Jarvis's market alerts and news: small, words only.

## What cannot be slowed (by rule)

With any position or order open, paper or live, NSE or MCX, these always run at full speed whatever the battery, the saver, the screen or the heat:

- its prices on the live stream;
- the watch's passes (15 s on MCX), the 15-second stop lane, and the stops, targets, trails, locks, the square-off and the guard checks;
- after a restart, restoring the open positions and their stops first;
- the order flow, the Candle agent and Jarvis's words, which keep their normal pace while anything is held. For example, a paper US-night silver short held 09:05–23:20 keeps the MCX watch at 15 s all evening.

Live stops and targets also rest at the broker where possible (SL/GTT), so a dead phone cannot miss them.

## Not changed this round (next steps)

- **The restart burst.** Each restart by an hourly night job re-reads ~18 books (each a Keystore call). Gating these behind the screen's first start, as was done for the alarms on 22 Oct, would cut most of what is left of the binder traffic. It touches the start-up restore of positions, so it needs its own careful change and tests.
- **The live position cards.** They redraw the notification every 3 s while a position is held. That is display only, but it is part of a held position's view, so it is left alone here.
- **The widget.** It asks Android for its ids on every update. This is small.

## Tests

- **ira-core** `BatteryRestTest`:
  - a held position stays at FULL pace with the saver on at 10%;
  - with nothing held, the analysis rests or slows;
  - the Candle agent on MCX evenings;
  - the recorder narrows at 30%;
  - the 30-second cache;
  - the meter survives a restart and midnight.
- **ira-core** `StreamHealthTest`: reconnects while held are exactly as before; with nothing held, network flaps back off to 16 s with jitter.
- **app (Robolectric)** `BatteryAuditTest`:
  - with an MCX position held, the saver on and the battery at 10%: the 15 s MCX pass, the stream needed, and the words and Candle agent at their normal pace;
  - with nothing held at 25%: the Candle agent rests, words every 5 minutes, the recorder lets the stream go;
  - on the MCX evening the Candle agent rests until a position opens;
  - 500 rounds of battery reads make at most 3 calls into Android;
  - the uploaded diagnostics carry the battery line, saved and read back;
  - a memory trim saves off the calling thread.
- `StopLaneTest` (the saver never stretches the stop look) and `OrderPathRound2Test` still pass unchanged.
