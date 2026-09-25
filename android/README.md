# Options Lab for Android

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
