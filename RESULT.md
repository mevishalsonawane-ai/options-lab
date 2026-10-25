# ntfy alerts replace Telegram (25 Oct 2026)

**Removed:** all of Telegram: the TDLib library (smaller APK, its CI job gone), the login, the Signal tester and its paper arm. On the first start after the update the app deletes its Telegram data (login folder, message log, `telegram.` settings). An open paper trade of the old arm keeps its stop, target and square-off until it closes.

**Set up:** Settings → Automation → **ntfy alerts**. Type your topic once (stored encrypted, shown as `topic set (****)`), server `https://ntfy.sh` or your own (https only), optional access token and prefix. Tap **Test**: it shows the last hour's messages and how each would be read; nothing is traded or marked read. Then switch **On** (off by default).

**What trades:** `BUY NIFTY 24500 CE`, `NIFTY 24500 PE BUY @ 120 SL 90 TGT 180`, `BANKNIFTY CE`, `SENSEX PUT`, `NIFTY bullish/bearish/up/down`, optional `weekly`/`next`/a date, `2 lots`, `ATM/ITM/OTM`. Your IraThinker JSON signals (`BUY_CALL`/`BUY_PUT`; `entry/stop/target1` read as the underlying's levels; `option` = the contract; `premium` = no chasing beyond +5%), for indices and NSE F&O stocks (`ADANIENT.NS` → its option; "Stock options too" switch). `sell/exit/close/book NIFTY` closes what it holds (never a short). Your `^NSEI Tripwire … Moving DOWN/UP` alerts buy a NIFTY PE/CE at ATM.

**Not traded (reason shown under Alerts):** test signals, status check-ins, two indices, CE and PE together, no direction, stocks named only in words, futures, MCX, crypto, BANKEX, numbers it can't place, contradicting prices, messages older than 5 minutes, holidays, weekends, before 09:15 or after the square-off, a repeated message id or signal id.

**Safety limits:** paper by default; live only with its own **Trade live** switch (off) + your PIN + AI trades go live. Every buy passes the kill switch, day lock, account guard, order fingerprint, max 3 trades a day, one trade per index, at most 1 lot unless you allow up to 5. Stop always (message's, else −20%), target message's else +40%, out by 15:15 (15:05 on expiry). It is an outside message, **not a tested strategy**.

**Security:** the topic is a password: anyone who knows it can post a trade. Your current topic is short and public, so move your sender to a long random topic (20+ characters) and add an ntfy access token if you can. The app never logs or shows the topic or token.
