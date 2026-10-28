# R120 + R121: the time machine before entries (28 Oct 2026)

- Boss asked whether the time machine should be used before entries. Its verdict, worked out before each entry, made no arm better: R120 passed 0 of 28 tests.
- The improved version (R121) replays each arm's own past trades with its real exits. The replay now matches real trades well (same win/loss 90% of the time for R51/R72/Solo), but past results still don't predict the next trade (correlation -0.003, 16,552 trades). It passed 0 of 28 tests.
- R120's nearest miss, R51 "skip against" (final twin p 0.039), fails the multiple-testing gate and stays a note.
- App change (display only): every time machine verdict now says "Not a proven signal"; the Hero Rs 1,000 ticket's verdict is hidden from the ticket, Jarvis and the timeline, but still logged. No trade, order, size or exit changes.
- Write-ups: research/HUNT_R120_TIME_MACHINE_GATE.md and research/HUNT_R121_TIME_MACHINE_SETUP.md.
