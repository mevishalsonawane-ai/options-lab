# Battery audit, 27 Oct: result

1. Drains found, from the 19–27 Oct diagnostics and the code: knowledge/words-only analysis running with nothing held, calls into Android every few seconds (the 25 Oct "binder traffic" kills), reconnect storms on a changing network, and a stream reconnect loop with no night exit.
2. Candle agent: with nothing held, rests at 30% battery or less, or under the saver; every 10 min with the screen off; on the MCX evening only while something is held on MCX or an arm is in its entry minutes.
3. Jarvis's words round, with nothing held: every 2 min with the screen off, every 5 min on a low battery or under the saver (it was every minute).
4. Order flow (shadow, unproven per TODO S7): follows only the charted option on a low battery or under the saver, with nothing held and no strategy in CONFIRM. The recorders narrow or let the stream go at 30% (was 20%).
5. Battery, saver, screen and temperature are now read from Android at most every 30 s. The health check alone made about 1,080 calls an hour on MCX evenings; the history keeper made two per piece at night.
6. Stream: the reconnect loop stops outside market hours. With nothing held, a flapping network backs off to 16 s with jitter; while anything is held the waits are exactly as before, and a position opening ends the wait at once.
7. With any position or order open (paper or live, NSE or MCX), nothing slows: the watch, the stop lane, the stream and start-up restore are untouched, and all analysis keeps its pace.
8. The uploaded diagnostics now carry "Battery today" (health.lines.battery). Its figures are saved, so a restart or the next morning's catch-up keeps the day's count. It holds loop names, minutes and counts only.
9. Tests: ira-core BatteryRestTest and StreamHealthTest; app Robolectric BatteryAuditTest (held at 10% with the saver on, nothing held rests, MCX evening, the 30 s cache, the diagnostics line, the save off the calling thread). StopLaneTest was adjusted for the cache.
10. The plain-English note for Boss is android/BATTERY_AUDIT_2026-10-27.md. What's New has a new entry (WhatsNewChangelogTest indices shifted). Left for later: the restart burst and the 3-second position cards.
