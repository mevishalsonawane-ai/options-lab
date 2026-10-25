# ntfy alerts: review fixes (25 Oct 2026)

1. A message sent before 09:15 is no longer bought at the open: it shows "not traded: sent before the open", and one that gets too old (5 minutes) while waiting is "stale".
2. A FINNIFTY or MIDCPNIFTY alert (`NIFTY_FIN_SERVICE.NS`, `NIFTY_MID_SELECT.NS`) now buys that index, never NIFTY; any other NIFTY index (IT, PHARMA, NEXT 50, MIDCAP 100 ...) is refused as "unknown index".
3. An exit message now also cancels a buy that is still waiting on that index ("cancelled by a later exit"), so it can never fill after the exit.
4. The reader stops itself after the F&O close (15:40) instead of staying connected all night; messages outside 09:00-15:40 on trading days are recorded "outside market hours, not traded".
5. A new prefix works at once, without waiting for a reconnect.
6. The 15-second stop check never waits behind a slow price download: a waiting buy's prices are fetched before the arm's lock.
7. Old Telegram login keys (if a phone still has them) can never go into a backup.
8. Paper only, as before; every fix has its own test, all ntfy tests and the engine/ira-core suites pass, and CI is watched until green.
