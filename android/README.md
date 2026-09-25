# IraAlgo for Android

The PC harness, standalone on a phone. No server of its own: the 170 expiry
chains and the harvested partitions ship inside the APK, every backtest and
health check runs on the device, and the only network it touches is the same
unauthenticated Upstox candle endpoint the PC harvester uses.

## What it does, mapped to the PC

| PC command | On the phone |
|---|---|
| `expiry-put` (all flags, holdout, sizing, `--out`) | **Trials** - tokens for every flag, ink-drawn equity curve with the sealed holdout fenced off, losers, sizing, CSV export |
| the variants measured in `docs/` | **Trials → The Arms** - naked 0.75%, the 1.00% roll cell, hedged with dated lots, dated naked, stress spread, and your own settings, side by side from one pass of the data |
| `regime` (and `--ledger`) | **Health** - the five kill conditions on a dial; source is the backtest, the paper ledger, or a CSV exported by the PC |
| `live-ticket` / `live-settle` | **Ticket** - price the live chain, record as paper (nothing is sent), live mark, settle from the 15:00-15:29 average or by hand |
| `ic` | **Cabinet → The IC Table** |
| `indicators/`, `strategy/signal_backtest.py` | **Cabinet → Signal Lab** - UT Bot and LinReg replayed on any harvested day |
| `harvest.cli` + `harvest_nightly.ps1` | **Cabinet → Data & Harvest**, and scheduled at 15:45 every market day |
| `sizing.py`, `costs.py`, `lots.py`, `provenance.py` | **Cabinet** drawers of the same names |

Plus what only a phone can do: a live watch that rewrites one ongoing
notification every minute during market hours (index levels, the open
ticket's mark and cushion to breakeven), risk alerts as the index nears the
breakeven or the strike, price alarms, an entry reminder at 10:55 on expiry
days, optional automatic paper tickets at 11:01 and settlement at 15:35.

## Parity

`engine/` is plain Kotlin/JVM with no Android dependency. Its tests replay
the bundled chains and assert, session by session, the same strike, credit,
forward, settlement, cost and net P&L as the PC's `run_backtest` - for the
naked, dated-lot hedged and 1% roll ledgers - plus the chain-derived lots,
the `regime` output, the provenance manifest, UT Bot and LinReg bar for bar,
the signal backtest and the IC table.

```bash
cd android
./gradlew :engine:test        # needs only a JDK
```

Regenerate the bundled data and reference ledgers after the PC data changes:

```bash
PYTHONPATH=. python tools/export_android_assets.py
```

## Building the APK

```bash
cd android
./gradlew :app:assembleRelease     # needs the Android SDK (ANDROID_HOME)
```

CI (`.github/workflows/android.yml`) builds both APKs on every push and
uploads them as the `options-lab-apk` artifact. To sign the release APK with
your own key, add repository secrets `OL_KEYSTORE_B64` (base64 of a .jks),
`OL_KEYSTORE_PASSWORD`, `OL_KEY_ALIAS` and `OL_KEY_PASSWORD`; without them the
release APK is signed with the debug key so it still installs.

## Zerodha (Kite Connect)

Cabinet → **Zerodha** is the broker, as `Trading_app/nifty_trading_bot/zerodha`
uses it on the PC:

- **Setup:** API key, API secret and the Redirect URL registered at
  developers.kite.trade. All three go straight into the encrypted vault; the
  secret is never displayed again.
- **Login:** Kite's own login page opens inside the app. The app catches the
  redirect before it loads, reads the `request_token` (matched on the exact
  registered scheme, host, port and path, so a lookalike page cannot pass),
  and exchanges it with `sha256(api_key + request_token + api_secret)` exactly
  as `kite_login.py` does. The page keeps no cookies, cache or form data.
- **No refresh token exists** for individual Kite developers: the access token
  ends at about 06:00 IST, so you log in once per trading day (the PC does the
  same). A 09:14 notification reminds you.
- **Data:** with a session, index quotes, the live chain (Kite 1-minute
  candles, same engine code) and the ticket's live mark come from Kite;
  without one, or if your plan lacks historical data, Upstox is used.
- **Orders:** funds, positions, today's orders (with cancel), a manual order
  form, and "Send to Zerodha" on the day's ticket. Nothing is ever sent by
  itself: at 11:01 the order is only *prepared* and you are notified. To send
  you review each leg and its limit price, hold the button for 1.5 s, and
  prove it is you again with your PIN or fingerprint. Real orders are off
  until you enable them and are refused on a compromised device.
- **Gates** (tested in `engine/.../KiteTest.kt`): whole lots, a lot cap
  (default 2 - the measured book depth), orders per day, order-value cap
  (kite_adapter.py's Rs 5,00,000 default), prices on the tick, LIMIT orders at
  the best bid/offer, and **NRML** for the expiry put - MIS would be squared
  off by Zerodha before the settlement the strategy holds to. A hedged ticket
  buys the wing first and sells the put only after the wing has fully filled.
  Fills replace the priced credit in the ledger, so settlement and the health
  checks measure the trade that happened.
- **Trade tab:** your account live from Kite, refreshed every 15 s in market
  hours:
  - **Positions:** net and day books, realised, unrealised and M2M. Square off
    one position, or all of them; shorts are bought back before any long is
    sold.
  - **Order book:** modify (quantity, LIMIT/SL/SL-M/MARKET, price, trigger)
    and cancel, both behind your PIN or fingerprint.
  - **Trade book**, **holdings** (sell from delivery) and **funds** (SPAN,
    exposure, premium, M2M).
  - **The day's P&L curve:** one encrypted sample a minute.

  Exits skip the caps that limit new risk (lots, daily count, value), so they
  can never trap you in a position. Before an exit is sent the position is
  re-read, and nothing is sent if it changed since you reviewed it.

## Live mode and sandbox mode

One switch (Cabinet → Zerodha → Mode) decides where every LIVE figure comes from:

| | LIVE · Zerodha | SANDBOX |
|---|---|---|
| index levels, sparklines, ticker | Kite quote + 1-min candles | Upstox public candles |
| option chain for the ticket | Kite 1-min candles; without the historical add-on, Kite live quotes, priced and labelled at the minute taken | Upstox public candles |
| ticket live mark | Kite quotes on the exact NFO contracts | Upstox candles |
| settlement (15:00-15:29 average) | Kite index candles, or enter it by hand | Upstox index candles |
| expiry calendar | Kite instruments | Upstox instrument master |
| live watch, risk alerts, price alarms | Kite | Upstox |
| funds, positions, orders | Kite | - |
| real orders | allowed (still review + hold + PIN) | never |

In LIVE mode there is no fallback: without today's Zerodha login the app says
so instead of showing another feed's numbers under a live label. Analysis -
Trials, the arms, Health, the IC table, Signal Lab, sizing, costs - runs on the
bundled record in both modes. The harvester also stays on Upstox in both: it
builds the research record, and mixing a second source into it is the
provenance and units trap the PC harness guards against.

## Tools

The Tools tab runs IraAlgo's option analytics (`engine/.../options`, checked
against IraAlgo's own Python and TypeScript) on one priced chain. In LIVE mode
the chain comes from Zerodha; in SANDBOX mode it comes from Upstox's public
candles. It includes:

- The chain, with Black-76 IV and delta off the parity forward.
- OI walls, PCR and max pain.
- The IV smile and skew.
- Gamma exposure, with the sign flip.
- The expected move (1σ and 2σ, today and to expiry), and the synthetic future.
- The strategy builder. Pick one of IraAlgo's single-expiry templates to see
  the legs placed on the chain, the payoff at expiry and today, max
  profit/loss, breakevens, net credit and the probability of profit.
  - In LIVE mode the basket opens the usual Zerodha review, with wings bought
    first.
  - In SANDBOX mode it becomes paper orders.

Trials and Health now live together under the **Lab** tab. The Lab also holds
IraAlgo's **Portfolio Backtester**, **Portfolio Analyzer** and **SIP
Backtester** (`engine/.../portfolio`, checked against IraAlgo's services to
about 1e-14).

- **Portfolio Backtester:** a weighted NSE basket with rebalancing,
  statutory costs, a benchmark, Monte Carlo, health grade and insights.
- **Portfolio Analyzer:** your Zerodha holdings, run over the last year.
- **SIP Backtester:** XIRR, step-up, and a comparison with a lump sum and
  with the benchmark.

Daily prices come from Kite when you are logged in and your plan includes
historical data. Otherwise they come from Upstox's public daily candles, and
the source is shown under each report.

## Paper trading (sandbox)

In SANDBOX mode the Trade tab is a paper account running IraAlgo's own
sandbox engine (`engine/.../sandbox`, checked step by step against the Python
on 16 scenarios). It covers:

- MARKET, LIMIT, SL and SL-M orders on NIFTY and BANKNIFTY options, as NRML or
  MIS.
- Margin with the IraAlgo leverage defaults.
- Modify, cancel and close.
- MIS square-off at 15:15, and expiry settlement at the last price.
- Catch-up after the phone was off, and a reset to a chosen capital.

Prices are Upstox's public 1-minute candles, so nothing reaches Zerodha. The
live watch keeps filling resting orders and notifies you of fills and
settlements. The account is stored in one encrypted vault file.

## Security

- **No screenshots or screen recording.** The window is `FLAG_SECURE` from
  before the first frame: screenshots, recordings, casting and the recents
  preview show a blank surface. Dialogs inherit it.
- **Locked by default.** PIN (6+ digits, typed on the app's own pad, never the
  system keyboard) plus optional fingerprint or face. Strong biometrics unlock
  a Keystore key that is destroyed if a new finger or face is enrolled. Weak
  (Class 2) face unlock is opt-in and labelled as a convenience.
- **The PIN is never stored** - a salted PBKDF2-SHA256 verifier, compared in
  constant time, with escalating lockouts and an optional erase after ten
  wrong attempts. Re-seals whenever the app leaves the screen (configurable).
- **Encrypted at rest.** Ledger, alarms, settings and PIN verifier are
  AES-256-GCM with a non-exportable Android Keystore key (StrongBox where
  present).
- **Nothing is logged.** The app never writes to logcat; release builds also
  strip every `Log` call and `printStackTrace` via R8, and are obfuscated.
  Network errors carry no URL, instrument key or response body.
- **Network:** HTTPS only; only system certificate authorities are trusted,
  so a user-installed CA cannot intercept the traffic. No credentials exist.
- **Device integrity:** root, Frida/Xposed, debugger and a build-time SHA-256
  of the bundled chains are checked. On a compromised device biometrics are
  withdrawn; you can choose to refuse such devices entirely.
- **Sandboxed from everything else on the phone.** It cannot read SMS,
  mail or accounts, contacts, call logs, calendar, files, photos, or any
  other app's data, and it cannot even list the apps you have installed (no
  `<queries>`). Every such permission is explicitly removed in the manifest,
  and the build *fails* if the final merged manifest - after every library -
  holds anything outside `app/permissions-allowlist.txt`, asks to see other
  apps, or declares an accessibility or notification-listener service. The
  app re-checks its own permissions at runtime and shows them in plain words
  under Cabinet → Security. The only file it ever touches is the one you pick
  in the system file picker to import or export.
- No backups, no device transfer, touches through overlays are ignored, and
  notifications show "Unlock to read" on the lock screen.
