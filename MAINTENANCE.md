# IraAlgo maintenance guide

Plain-English steps to keep IraAlgo running yourself, with or without Claude. Nothing here needs coding.

> **Golden rules (never changed by anyone, including voice, backup or automation):** real orders only with your PIN and
> "AI trades go live" on · Jarvis's own trades stay on paper · automatic changes may only lower risk · exits always go.

---

## 1. Every trading day (2 minutes)

| When | What | Where |
|---|---|---|
| Morning, before 09:15 | **Log in to Zerodha** in the app (the session ends at 06:00 daily). | App opens the login itself, or Settings → Zerodha |
| Morning | Read the **Good morning** check: every ✗ line is something to fix before trading. | Home (notification at ~09:00) |
| During the day | Glance at **Problems found** on the Automation card. Serious items stay visible. | Home → Automation card |
| Evening | Read **Order review** ("Today: N orders · flaws · …") and the 15:45 day report. | Home → Automation card |

**Paper vs Live:** every money figure says **PAPER (practice money)** or **Zerodha · LIVE (real money)**. They are never
added together.

## 2. When a new APK arrives

1. Your phone shows **"IraAlgo APK ready"** (ntfy). Tap it to open the GitHub release page; you must be signed in to
   GitHub because the repo is private.
2. Download **IraAlgo.apk** and install it over the old app (same signing key, so your data stays).
3. The note says the app tests may still be running. On a trading day, prefer a build whose checks are **green**:
   GitHub → options-lab → **Actions**.

Fixed download link: `https://github.com/mevishalsonawane-ai/options-lab/releases/download/latest-apk/IraAlgo.apk`

## 3. Tokens and secrets (where each one lives)

| Secret | What for | Where it lives | When to renew |
|---|---|---|---|
| Zerodha login | trading and live prices | inside the app (encrypted) | daily (login) |
| Zerodha API key/secret | the app's Kite Connect | inside the app, wrapped by your PIN | when you change your Kite app |
| `DHAN_ACCESS_TOKEN`, `DHAN_CLIENT_ID` | historical data downloads | Claude environment → Edit → Environment variables; for the nightly GitHub job (when built): GitHub → Settings → Secrets and variables → Actions | when Dhan says it expired (up to ~30 days) |
| GitHub token (fine-grained, **IraAlgo data repository only**) | the phone's GitHub sync: recorded days and phone backups | inside the app, sealed with your PIN (Settings → Data & Harvest → GitHub sync) | every 90 days; the app reminds you 7 days before (see "GitHub token" below) |
| Backup passphrase | opens the phone backups and the recorded days uploaded to GitHub | only in your head or your password manager (the phone keeps a key made from it, never the passphrase) | never; if you change it, older uploads keep the old one |
| `NTFY_TOPIC` | APK-ready notifications | GitHub → Settings → Secrets and variables → Actions | never (delete it to stop notifications) |
| `OL_KEYSTORE_B64`, `OL_KEYSTORE_PASSWORD`, `OL_KEY_ALIAS`, `OL_KEY_PASSWORD` | signing the APK so updates install over the old app | GitHub Actions secrets | never; **if lost, new APKs won't install over the old app**. Keep your offline copy (github-secrets.txt) safe |

**Never** paste a token into a chat, an issue or a file in the repo. If one leaks, revoke it on that service and create a
new one.

### Renewing the Dhan token

1. Go to web.dhan.co → My Profile → **Access DhanHQ APIs** → **Generate Access Token** (choose the longest validity).
2. Paste it into the places in the table above, replacing the old value.
3. A new Claude session or the next GitHub nightly run picks it up. The app keeps working meanwhile on its existing
   data.

### GitHub token (for the phone's GitHub sync)

The phone uploads each recorded day and a phone backup every evening, on Wi-Fi while charging, after the MCX evening
close (23:30, or 23:55 in US winter time; 15:45 on days without an MCX session; a missed evening goes the next morning
before 08:00). They go to
the private **data-only** repository `mevishalsonawane-ai/IraAlgo`, **never options-lab**. The phone needs one token that
can do only that.

Why it is never options-lab: options-lab holds the code that CI signs and publishes as the app (`latest-apk`). A token
that can write there could push code or replace the APK. If the phone were stolen, that token could publish a malicious,
signed app. The app refuses options-lab as the upload repository. It also refuses any token that can reach options-lab.

**Create it** (on a PC or the phone's browser, signed in to GitHub):

1. github.com → your photo (top right) → **Settings** → **Developer settings** → **Personal access tokens** →
   **Fine-grained tokens** → **Generate new token**.
2. Token name: `IraAlgo phone uploads`. Resource owner: `mevishalsonawane-ai`. Expiration: **90 days** (Custom → 90
   days from today).
3. Repository access: **Only select repositories** → **`IraAlgo`**. Only that one: never options-lab, never "All
   repositories".
4. Permissions → Repository permissions → **Contents: Read and write**. (Metadata: Read-only is added by itself.) Leave
   everything else at "No access". Never give it **Workflows**, Actions, Secrets or Administration.
5. **Generate token** and copy it (it starts with `github_pat_`). GitHub shows it only once. Note the expiry date.

**Paste it** in the app, never in a chat or a file:

1. Settings → Data & Harvest → **GitHub sync** → **Paste token…**
2. **Data repository** stays `mevishalsonawane-ai/IraAlgo`. Paste the token, type the expiry date (YYYY-MM-DD) and your
   app PIN, then tap **Check and save**.
   - The app asks GitHub who the token belongs to.
   - It checks that the token can see IraAlgo and **cannot** reach options-lab.
   - A wrong, expired or too-wide token is refused, with the reason.
3. First time only: **Set passphrase…** and choose a backup passphrase (at least 10 characters, not weak). Write it down
   somewhere safe: without it the uploads cannot be opened, by anyone.

**If you had an options-lab token in the app** (from the first version of GitHub sync): the app removed it by itself and
says so in Problems found. Delete that token on GitHub (Settings → Developer settings → Fine-grained tokens → the old
token → Delete). Then make the IraAlgo token as above.

**Renew it** (the app shows "The GitHub token expires in N days" in Problems found, with a notification, from 7 days before):

1. GitHub → Settings → Developer settings → Fine-grained tokens → `IraAlgo phone uploads` → **Regenerate token** → 90
   days → copy it. (Or generate a new one as above and delete the old one.)
2. In the app: GitHub sync → **New token…** → paste, the new expiry date, your PIN → Check and save.

**"GitHub token invalid"** in Problems found means one of these:
- GitHub refused the token (401/403): it expired or was revoked.
- The token no longer sees IraAlgo.
- The token can now also reach options-lab. The phone checks this before every upload. Until you paste a new one, the evening uploads and the GitHub backups pause; nothing is lost on the
phone. "Upload now" tries once more. Make a new token as above and paste it.

**If it leaks:** delete it on GitHub at once (same page → Delete), then make a new one. What a leaked token can and cannot
do:
- It can replace or delete the uploads in IraAlgo's releases, and change IraAlgo's files (for example `CATALOG.json`, the
  list of checked days).
- It cannot touch options-lab, the app or its releases.
- It cannot change IraAlgo's workflow (no Workflows permission). The workflow pins the checker's code by hash, so the
  nightly job refuses to run a changed checker.
- It cannot read the uploads: they stay sealed by your passphrase.

After a leak, look at IraAlgo's recent commits (GitHub → IraAlgo → Commits) for anything not made by
`github-actions[bot]`.

**How the phone keeps it:**
- Sealed with your PIN, as the Zerodha secret is. Everything you do by hand (Restore from GitHub) asks for the PIN.
- The evening upload runs with nobody there, so it cannot ask for the PIN. It has its own copy, bound to IraAlgo and
  locked by a key in this phone's Keystore that never leaves the phone.
- The token is never in a backup, in Copy diagnostics or in the logs.

**The nightly check** runs in IraAlgo (GitHub → IraAlgo → Actions → "Phone uploads (nightly check)", 03:22 IST), with
IraAlgo's own access.
- It starts from its last catalogue (`CATALOG.json` in IraAlgo) and downloads only what is new.
- It checks each recorded day whole (not cut, the right size and month) and each backup's header, without your
  passphrase. It never has your passphrase: do not add it as a secret, anywhere.
- It adds every good file to `CATALOG.json`. A bad file turns the run red and, with `NTFY_TOPIC` set as an IraAlgo
  secret, sends your phone a notification.
- Days with no upload and an old backup show as warnings in the run's summary.
- It needs IraAlgo → Settings → Actions → General → Workflow permissions: **Read and write permissions**.
- "Run workflow" on that page runs it now.

**What the card says:** "Last upload: date · size · ✓/✗", "Days waiting", "Last backup to GitHub". A failed upload, or
none for 2 days, also shows in Problems found. Most often the fix is: leave the phone on charge, on Wi-Fi, overnight (uploads start after the MCX evening close,
15:45 on days without an MCX session).

## 4. Static IP (needed for live orders)

SEBI requires live orders from your registered static IP. The app's relay uses your server **144.24.125.164**.

- If the morning check says **"Not on your registered static IP"**, the relay server is down. Start the Oracle server,
  check its public IP is attached, then reopen the app.
- Paper trading does not need it.

## 5. If something looks wrong: make everything safe in one step

1. **Kill switch ON** (Settings → Bot settings → Kill switch): every new entry is refused. Exits and square-offs still
   go through.
2. Switch **Mode → Paper** (Settings → Zerodha → Mode).
3. Optional: set a **Paper-only period** (Settings → Bot settings) so going Live asks your PIN and reminds you why.
4. To close open positions now, say or type **"Jarvis, emergency exit"**. It asks for your fingerprint on the screen
   (or a yes in your own voice). You can also close each position by hand from its card.

**Three gates, the fingerprint and the flat-by (22 Oct, borrowed from a day-trader kit).** A real order of your own now
shows three gates with ticks before it goes: the checks passed and the order still matches what they cleared (its
"fingerprint"), a typed **yes** for that one order (Settings → Zerodha → Real orders; on by default, turning it off asks
your PIN), and your PIN. A bot's live trade has its own three: its Trade-live switch with AI trades live, the PIN it was
armed with, and the fingerprint at the door (on its row under Settings → Zerodha). If an order changed after its check,
or the check is from another day, the app refuses it, names the field and shows a banner; closing a position is never
refused. Separately, any bot position still open at **15:25** (stock and fund options 15:20, MCX ten minutes before its
close) is closed by the flat-by, after the bot's own exit and the 15:05 expiry square-off had their chance; positions held
by design (overnight, NRML, the Expiry Put ticket) and your own trades are left alone, and the kill switch never stops it.
Every bot entry also gets a **time machine** line: the odds that the target or the stop came first on the index's own
past days at that time, with a one-word verdict. It only reports; ask Jarvis "what did the time machine say".

## 6. What common problems mean

| You see | It means | Do this |
|---|---|---|
| "Live price stream dropped / quiet" | the phone's internet changed or dropped; prices fall back to Zerodha quotes | usually nothing; check Wi-Fi/data if frequent |
| "Positions not protected: no prices since …" | no prices at all for a while | check internet now; consider closing open trades by hand |
| "Not on your registered static IP" | relay server down | see section 4 |
| "The screen froze N s in …" | a developer bug (work on the screen's thread) | note it; it needs a fix |
| "App start took N s" | slow start | note it if over 2 s |
| "Background time is spent" | nightly work ran out of budget | keep the phone charging overnight; report it if daily |
| "Strategy X is armed but research says it loses" | a past decision of yours, or the research, rules it out | disarm it, or accept it knowingly |
| "Charges eat the profit" (Order review) | too many small trades | look at that strategy's re-entry rules |
| "Zerodha sync: mismatch" | app and Zerodha disagree on a position or stop | check Kite; fix the position there |
| "GitHub upload failed …" / "Nothing uploaded to GitHub since …" | the evening upload did not run or GitHub refused it | leave the phone charging on Wi-Fi overnight (uploads start after the MCX evening close, 15:45 on days without MCX); if it says the token was refused, renew it (section 3) |
| "Night study: not working" | the overnight study didn't run | phone online overnight, battery Unrestricted |

**Copy diagnostics** (Settings → Diagnostics → Copy diagnostics) gives one text with everything a developer needs. Keys,
tokens and passwords are never included.

## 7. Backups

- **Automatic (GitHub sync)**: once the GitHub token and the backup passphrase are set (section 3), the phone uploads a
  sealed backup to the IraAlgo data repository every evening. A strategy or Pine script you add or change goes up in
  that evening's backup (after the MCX evening close, 15:45 on days without MCX, on Wi-Fi while charging). GitHub keeps the last 30 daily backups plus one per
  month before them (IraAlgo's release `phone-backup`).
- **By hand, Settings → Security → Backup**: an encrypted file you keep yourself (Google Drive or a PC).
- Backups never carry your PIN, Live/AI-live switches, tokens or the paper-only period.

### Restore on a new phone

1. Install the APK (section 2), choose the app PIN, and link Zerodha (API key and secret, then the login): the app opens
   its settings only once Zerodha is linked.
2. Settings → Data & Harvest → GitHub sync → **Paste token…** (the token from section 3; make a new one if you no longer
   have it).
3. **Restore from GitHub…** → your app PIN (it opens the token) → pick a date → enter the **backup passphrase** → check what it holds → **Replace and
   restart** → your app PIN. The app closes; open it again.
4. Everything comes back **switched off**: strategies, ORB arms and Pine scripts (with their stop-loss, target and profit
   lock). Your PIN, Live/AI-live, tokens and the paper-only period are never in a backup: set them again. Switch on only
   what you want, as before.
5. GitHub sync → **Set passphrase…** again (the same passphrase), so this phone's uploads open with it too.

### Opening the recorded days on a PC

The days are in the IraAlgo repository's releases `live-YYYY-MM` (one `live-YYYY-MM-DD.irlive` file a day). Download them, then:

```
pip install cryptography
python research/data/decrypt_live.py live-2026-10-13.irlive --extract days/
```

It asks for the backup passphrase and checks every file against the day's manifest. Your own trades stay encrypted
inside (only the phone opens them: Data & Harvest → Recordings → Export, with your PIN).

### Daily diagnostics (for the research sessions)

With each evening upload (same time and rules as above; "Upload now" sends today's so far, and the evening run replaces it)
the phone sends one small file for that day: IraAlgo's release `diag-YYYY-MM`, asset `diag-YYYY-MM-DD.json.gz`. A re-run
replaces that day's file, so there is only ever one per day. The GitHub sync card's **Diagnostics** line says the last day
that went up; a failed upload shows in Problems found like any other. Since 14 Oct: a day whose file did not go up is sent
again by the next run (up to 3 days back), and until then the card says "✗ diagnostics <day> not uploaded"; an "Upload
now" before the evening close is marked **partial** on the card until the evening run sends the whole day. A day sent
late is built then: its decisions, trades, orders, errors and timeline are that day's (since 20 Oct also the
`app_health` and `stream` lines), but the arms' switches, `mode`, the other `health.lines` and `workers` are as at its
`generated_at` (a later date there marks such a catch-up; schema 2 says it again in `health.lines_as_of`).

It is **not sealed**, on purpose: it is cleaned on the phone first, and the research sessions have no passphrase. What is
never in it: tokens, keys, PINs, account or client ids, your name, phone or e-mail, broker order ids, and rupee balances,
margins or P&L (an arm's P&L is in points and percent of the premium only). Every text goes through the diary's redaction
and then a final scrub (GitHub tokens, JWTs, long letter-and-digit runs, digit runs of 9 or more, client-id shapes in any
case, rupee amounts, every figure of a clause with a money word such as margin, required, available, cash, P&L, MTM,
realised, net or charges, and lakh-grouped figures like 1,23,456); tests prove it with a set of fake secrets and real
Kite and arm texts. A live order carries no fill price and no quantities, a live trade no lots, an order's message only its reason (the words before its first
figure), and the other arms' lines say "(net hidden)" instead of their rupee net. The recordings and backups, which
hold your own trades, stay sealed.

Read one on a PC (no passphrase needed):

```
python research/data/read_diagnostics.py diag-2026-10-13.json.gz          # a short summary
python research/data/read_diagnostics.py --json diag-2026-10-13.json.gz   # the whole file
```

Schema 2 since 20 Oct 2026 (schema 1 before; the reader takes both). Schema 2 keeps every schema-1 key and adds the
severities: `health.errors` now holds only what failed. A **warning** is a risk or a fault the app rode out by itself (a
stream drop it reconnected, the paper ticket near its breakeven, the daily loss limit); a **notice** is information or a
form's hint (the morning check, the day report, "that passphrase is too easy to guess"). A schema-1 file counted all of
them as errors; `read_diagnostics.py` sorts its kinds by the same words as the phone.

Built by `android/ira-core/.../DiagDay.kt`; a sample is `research/data/tests/diag_sample.json` (and the schema-1 one,
`diag_sample_v1.json`), and a test keeps the phone and the sample in step:

| Key | What |
| --- | --- |
| `schema`, `kind`, `day`, `generated_at`, `app`, `privacy`, `mode` | the file itself; the app's switches as on/off |
| `arms[]` | one row per arm: `state` (armed, off, parked, awaiting_approval), `automatic`, `live_cleared`, `parked_indices`, `status` and `status_kind`; `today` (decisions, signals, taken, skipped, `skips` by reason, entries, exits, paper, live, wins, losses, points, avg_pct, sum_pct) and `all_days` (trades, since, wins, losses, points, avg_pct, sum_pct); `trades[]` (account paper/live, source label, right, entry/exit time and premium, why, points, pct, lots (null for a live trade), open, unconfirmed, trail) |
| `other_arms[]` | the other paper arms' one-line states (Night, VIX divergence, MCX, forge ideas) |
| `decisions_since`, `decisions[]` | each verdict: time, arm, verdict, `kind` (taken, guard, lock, day_lock, kill_switch, flow, liquidity, pretrade, event, cap, exposure, lapsed, skipped_by_boss, refused, order_refused, no_quote, error, or a waiting kind), `outcome` (taken, skipped, waiting, holding, error), bar |
| `orders[]`, `orders_by_source` | the day's orders, paper and live, with who placed each (never the order id); `avg` (the fill price), `qty` and `filled` are null for live orders, and `message` is the reason only |
| `health` | `errors` by kind (schema 2: only what failed), `warnings` and `notices` (schema 2), `unreadable`, `stream` drops (schema 2: with `times`), `incidents` (schema 2: the self-healing monitor's "incident:"/"recovered:" lines), `lines` (app_health, speed, stream, github, order_speed, slippage in points, order_review, order_flow, zerodha_sync; app_health and stream are the day's own, the others as at `lines_as_of`), `lines_as_of` (schema 2), `workers`, `sync` (failed, skipped, lines), `diary_lines` |
| `timeline[]` | the Autopilot change timeline of the day; `own: true` is your own switch (the trail, the parks) |
| `truncated` | how many items each long list lost to the size cap (256 KB before gzip; the newest are kept) |

Decisions for Liquidity's books and Hero come from the app's memory since its last start (`decisions_since`); the other
arms' come from the diagnostics diary. A morning catch-up run reads the evening's day from the diary and the books.

## 8. GitHub housekeeping

- **Actions** tab: each push builds and tests the app. A red ✗ means that build has a problem; the previous green APK is
  still fine to use.
- **Releases**:
  - `latest-apk`: the app.
  - `data-YYYYMMDD`: the market data lake, large files.
  - `research-data`: older research data.
  - The phone's uploads are not here: they are in the private data repository **IraAlgo** (`live-YYYY-MM`: one sealed
    file per recorded day; `phone-backup`: the phone's sealed backups). Don't delete those by hand unless you mean to.
- **Protect main** (so nothing reaches `main` before CI passes): options-lab → **Settings** → **Rules** → **Rulesets** →
  **New ruleset** → **New branch ruleset**.
  1. Name: `protect main`. Enforcement status: **Active**.
  2. Target branches → **Add target** → **Include default branch**.
  3. Tick **Require a pull request before merging**, **Block force pushes** and **Restrict deletions**.
  4. Tick **Require status checks to pass** → **Add checks**: `Engine parity with the PC`, `Build APK`,
     `PC harness tests`, and the four `App tests (JVM, Robolectric, Compose UI)` shards. (A check appears in the list
     after it has run once.)
  5. **Create**. (Older screens call this Settings → Branches → Add branch protection rule → `main` → "Require status
     checks to pass before merging".) If GitHub says rules on a private repository need a paid plan (GitHub Pro), that
     is GitHub's limit: either upgrade, or keep merging only after the Actions tab shows green.
- Free GitHub Actions minutes on private repos are limited each month. If builds stop with a "minutes" message, wait for
  the monthly reset or add billing.
- **Tests never depend on the real calendar** (25 Oct 2026: ten app tests failed only when CI ran on a Sunday). Every app
  test runs with the market's clock pinned to noon IST on a trading day (`MarketClockRule`, pinned from the moment a test
  class loads, so a class's fields read the same day as its body), and the fake Zerodha stamps its orders from that clock.
  A test that needs another day or hour sets `Market.testClock` itself from `Market.today()`, never from `LocalDate.now()`
  or `ZonedDateTime.now()`. If a test is green on a weekday and red at the weekend, after 15:15 IST or on a holiday, it is
  reading the wall clock somewhere: fix the test, not the calendar.

## 9. Long term

- Zerodha, Dhan or Android changes can eventually break a feature. The app shows it on **Problems found** and in Copy
  diagnostics; fixing it needs a developer (or Claude).
- Research findings are in `research/FINDINGS.md`. Strategy verdicts are in the scorecard. Data Brain design is in
  `research/data/ARCHITECTURE.md`.
