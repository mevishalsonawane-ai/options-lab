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
| GitHub token (fine-grained, options-lab only) | the phone's GitHub sync: recorded days and phone backups | inside the app, sealed with your PIN (Settings → Data & Harvest → GitHub sync) | every 90 days; the app reminds you 7 days before (see "GitHub token" below) |
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

The phone uploads each recorded day and a phone backup to GitHub every evening (after 15:45, on Wi-Fi while charging).
It needs one token that can do only that.

**Create it** (on a PC or the phone's browser, signed in to GitHub):

1. github.com → your photo (top right) → **Settings** → **Developer settings** → **Personal access tokens** →
   **Fine-grained tokens** → **Generate new token**.
2. Token name: `IraAlgo phone`. Resource owner: `mevishalsonawane-ai`. Expiration: **90 days**.
3. Repository access: **Only select repositories** → `options-lab`. Nothing else.
4. Permissions → Repository permissions → **Contents: Read and write**. (Metadata: Read-only is added by itself.) Leave
   everything else at "No access".
5. **Generate token** and copy it (it starts with `github_pat_`). GitHub shows it only once. Note the expiry date.

**Paste it** in the app, never in a chat or a file:

1. Settings → Data & Harvest → **GitHub sync** → **Paste token…**
2. Paste the token, type the expiry date (YYYY-MM-DD) and your app PIN → **Check and save**. The app asks GitHub who the
   token belongs to and whether it can see options-lab; a wrong or expired token is refused with the reason.
3. First time only: **Set passphrase…** and choose a backup passphrase (at least 10 characters, not weak). Write it down
   somewhere safe: without it the uploads cannot be opened, by anyone.

**Renew it** (the app shows "The GitHub token expires in N days" in Problems found, with a notification, from 7 days before):

1. GitHub → Settings → Developer settings → Fine-grained tokens → `IraAlgo phone` → **Regenerate token** → 90 days →
   copy it. (Or generate a new one as above and delete the old one.)
2. In the app: GitHub sync → **New token…** → paste, the new expiry date, your PIN → Check and save.

**If it leaks:** delete it on GitHub at once (same page → Delete), then make a new one. A leaked token can only touch
options-lab's files and releases; the uploads themselves stay sealed by your passphrase.

**What the card says:** "Last upload: date · size · ✓/✗", "Days waiting", "Last backup to GitHub". A failed upload, or
none for 2 days, also shows in Problems found. Most often the fix is: leave the phone on charge, on Wi-Fi, after 15:45.

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
| "GitHub upload failed …" / "Nothing uploaded to GitHub since …" | the evening upload did not run or GitHub refused it | leave the phone charging on Wi-Fi after 15:45; if it says the token was refused, renew it (section 3) |
| "Night study: not working" | the overnight study didn't run | phone online overnight, battery Unrestricted |

**Copy diagnostics** (Settings → Diagnostics → Copy diagnostics) gives one text with everything a developer needs. Keys,
tokens and passwords are never included.

## 7. Backups

- **Automatic (GitHub sync)**: once the GitHub token and the backup passphrase are set (section 3), the phone uploads a
  sealed backup every evening, and a few minutes after you add or change a strategy or Pine script. GitHub keeps the last
  30 daily backups plus one per month before them (release `phone-backup`).
- **By hand, Settings → Security → Backup**: an encrypted file you keep yourself (Google Drive or a PC).
- Backups never carry your PIN, Live/AI-live switches, tokens or the paper-only period.

### Restore on a new phone

1. Install the APK (section 2), choose the app PIN, and link Zerodha (API key and secret, then the login): the app opens
   its settings only once Zerodha is linked.
2. Settings → Data & Harvest → GitHub sync → **Paste token…** (the token from section 3; make a new one if you no longer
   have it).
3. **Restore from GitHub…** → pick a date → enter the **backup passphrase** → check what it holds → **Replace and
   restart** → your app PIN. The app closes; open it again.
4. Everything comes back **switched off**: strategies, ORB arms and Pine scripts (with their stop-loss, target and profit
   lock). Your PIN, Live/AI-live, tokens and the paper-only period are never in a backup: set them again. Switch on only
   what you want, as before.
5. GitHub sync → **Set passphrase…** again (the same passphrase), so this phone's uploads open with it too.

### Opening the recorded days on a PC

The days are in the GitHub releases `live-YYYY-MM` (one `live-YYYY-MM-DD.irlive` file a day). Download them, then:

```
pip install cryptography
python research/data/decrypt_live.py live-2026-10-13.irlive --extract days/
```

It asks for the backup passphrase and checks every file against the day's manifest. Your own trades stay encrypted
inside (only the phone opens them: Data & Harvest → Recordings → Export, with your PIN).

## 8. GitHub housekeeping

- **Actions** tab: each push builds and tests the app. A red ✗ means that build has a problem; the previous green APK is
  still fine to use.
- **Releases**:
  - `latest-apk`: the app.
  - `data-YYYYMMDD`: the market data lake, large files.
  - `research-data`: older research data.
  - `live-YYYY-MM`: the phone's recorded days, one sealed file a day (GitHub sync).
  - `phone-backup`: the phone's sealed backups (GitHub sync). Don't delete these by hand unless you mean to.
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

## 9. Long term

- Zerodha, Dhan or Android changes can eventually break a feature. The app shows it on **Problems found** and in Copy
  diagnostics; fixing it needs a developer (or Claude).
- Research findings are in `research/FINDINGS.md`. Strategy verdicts are in the scorecard. Data Brain design is in
  `research/data/ARCHITECTURE.md`.
