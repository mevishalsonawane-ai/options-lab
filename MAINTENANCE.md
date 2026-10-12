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
| `NTFY_TOPIC` | APK-ready notifications | GitHub → Settings → Secrets and variables → Actions | never (delete it to stop notifications) |
| `OL_KEYSTORE_B64`, `OL_KEYSTORE_PASSWORD`, `OL_KEY_ALIAS`, `OL_KEY_PASSWORD` | signing the APK so updates install over the old app | GitHub Actions secrets | never; **if lost, new APKs won't install over the old app**. Keep your offline copy (github-secrets.txt) safe |

**Never** paste a token into a chat, an issue or a file in the repo. If one leaks, revoke it on that service and create a
new one.

### Renewing the Dhan token

1. Go to web.dhan.co → My Profile → **Access DhanHQ APIs** → **Generate Access Token** (choose the longest validity).
2. Paste it into the places in the table above, replacing the old value.
3. A new Claude session or the next GitHub nightly run picks it up. The app keeps working meanwhile on its existing
   data.

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
| "Night study: not working" | the overnight study didn't run | phone online overnight, battery Unrestricted |

**Copy diagnostics** (Settings → Diagnostics → Copy diagnostics) gives one text with everything a developer needs. Keys,
tokens and passwords are never included.

## 7. Backups

- **Settings → Backup**: make an encrypted backup regularly and keep it off the phone (Google Drive or a PC). Your
  trades and records otherwise live only on the phone.
- Backups never carry your PIN, Live/AI-live switches, tokens or the paper-only period.

## 8. GitHub housekeeping

- **Actions** tab: each push builds and tests the app. A red ✗ means that build has a problem; the previous green APK is
  still fine to use.
- **Releases**:
  - `latest-apk`: the app.
  - `data-YYYYMMDD`: the market data lake, large files.
  - `research-data`: older research data.
- Free GitHub Actions minutes on private repos are limited each month. If builds stop with a "minutes" message, wait for
  the monthly reset or add billing.

## 9. Long term

- Zerodha, Dhan or Android changes can eventually break a feature. The app shows it on **Problems found** and in Copy
  diagnostics; fixing it needs a developer (or Claude).
- Research findings are in `research/FINDINGS.md`. Strategy verdicts are in the scorecard. Data Brain design is in
  `research/data/ARCHITECTURE.md`.
