# Split stop (Boss, 30 Oct: "Make stop bot different for index and comodities")

1. The main button and the quick-settings tile now read "Stop index bots": they stop NSE and BSE only (every NSE arm, strategy, Pine script, Jarvis's trades); the MCX bots keep trading.
2. The MCX card has its own smaller "Stop commodities" (you choose whether it also sells what the MCX arms hold) and its own "Start again".
3. Each side shows its state and reason, e.g. "Commodity bots: stopped by you 10:42" or "stopped by the daily loss limit".
4. Jarvis: "stop all" and the emergency exit still stop both; "stop the index bots", "stop the commodity bots", "start the commodity bots again" act on one side, with the same confirm and PIN rules.
5. Unchanged: the kill switch stays global; a loss-limit stop is never lifted today; with commodities on their own limits the NSE paper loss stops only the index side, otherwise both.
6. Each side's stop is saved and restored after a restart; a stop saved by an older build counts as both stopped (its loss stop follows the loss rules, as before).
7. Open positions on a stopped side keep their stops, targets and exits (tested for the MCX arms and S5 Trail); nothing new touches disk on the order path.
8. Every stop and start is in the Autopilot change timeline; Undo does the opposite (lifting the index stop asks your PIN).
9. Tests: new core and app tests for each case listed in the brief, plus Compose tests for both buttons; What's New entry added. A reviewer agent's 3 findings were fixed.
10. CI on 9cba23a: all green (engine, PC harness, app tests 0-3/4, Build APK); no emulator job runs for this app's branch.
