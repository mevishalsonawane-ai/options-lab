package com.optionslab.ira

import com.optionslab.engine.mcx.McxSession
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The phone's GitHub sync (TODO G1 and G2, 13 Oct): the rules, apart from the phone. One uploader, one token:
 *
 *  - G1: each recorded day (noBackupFilesDir/recordings/YYYY-MM-DD: the market file, the tick zone, the order-flow minutes and
 *    Boss's encrypted trades, as they are) is zipped, sealed with Boss's backup passphrase ([LiveSeal]) and uploaded to the
 *    release `live-YYYY-MM` as `live-YYYY-MM-DD.irlive`.
 *  - G2: the phone backup (sealed as Backup.kt seals it, with the same passphrase) goes to the release `phone-backup` every
 *    evening, and after a strategy or Pine script is added or changed (from the evening gate, [backupDelayMin]). The last
 *    [DAILY_KEPT] daily backups are kept, plus one a month before them ([retention]).
 *
 * When ([plan], [eveningDue], [liveAllowed]): every automatic run from the evening gate ([eveningFrom]; 13 Oct, Boss: "after
 * the commodities close", TODO G4): after MCX's evening close (23:30, or 23:55 in US winter time, from the app's MCX
 * calendar), so the MCX session is in the day's file and nothing uploads while any market the app trades is open; 15:45
 * on a day with no MCX session (an MCX holiday, a weekend). An evening run that was missed may still run the next morning
 * until 08:00 ([MORNING_UNTIL], an hour before any market opens). On unmetered Wi-Fi and while charging (the worker's own WorkManager constraints); only Boss's "Upload now"
 * goes at any hour without charging, still on unmetered Wi-Fi. Never a day's big file between 09:00 and 15:45 of a trading
 * day, even by hand. A missed day stays waiting and goes next evening ([pendingDays]).
 *
 * Where (Boss, 13 Oct): the private, data-only repository [DATA_REPO] (mevishalsonawane-ai/IraAlgo), never options-lab. A
 * token that can write to options-lab could push code to the branch CI signs and publishes, or replace the `latest-apk`
 * asset: a stolen phone token would then publish a signed app. So the uploads are refused for options-lab whatever is
 * configured ([repoRefused]), and the token must be a fine-grained one made for the data repository only.
 *
 * The token: a fine-grained GitHub token for the data repository only ([tokenLooksRight]); [redact] takes any token out of a
 * text before it is shown, logged or kept. Pure: the day and minute are given.
 */
object CloudSync {
    const val OWNER = "mevishalsonawane-ai"
    /** The private, data-only repository the phone uploads to (Boss, 13 Oct). */
    const val DATA_REPO = "IraAlgo"
    /** The app's code repository: CI signs and publishes from it, so it is never an upload target. */
    const val CODE_REPO = "options-lab"
    /** The data repository as the Settings field shows it. */
    const val DEFAULT_REPO = "$OWNER/$DATA_REPO"
    const val BACKUP_TAG = "phone-backup"
    const val LIVE_EXT = ".irlive"
    const val BACKUP_EXT = ".irabk"

    /** The evening run's earliest minute on a day with no MCX session (15:45 IST, after F&O's 15:40 close). */
    const val EVENING = 15 * 60 + 45
    /**
     * The evening gate when MCX's calendar cannot be read (23:55 IST: MCX's latest ordinary close, in US winter time). Never
     * earlier than a close it might be (G4, 13 Oct).
     */
    const val EVENING_SAFE = 23 * 60 + 55
    /** The day's last minute (23:59): the gate for an MCX window that does not close before midnight. */
    const val LAST_MINUTE = 23 * 60 + 59
    const val MARKET_FROM = 9 * 60
    /**
     * A missed evening's catch-up, and every automatic send, ends at 08:00 the next morning (13 Oct review of G4): an hour
     * before NSE and MCX open, so a big file or backup started late is done well before 09:00.
     */
    const val MORNING_UNTIL = 8 * 60
    /** Daily backups kept before the one-a-month rule. */
    const val DAILY_KEPT = 30
    /** No successful upload for this many days: a problem. */
    const val STALE_DAYS = 2L
    /** The token's renewal reminder starts this many days before it expires. */
    const val REMIND_DAYS = 7L
    /** A change to a strategy or a Pine script is backed up this many minutes after the last change. */
    const val DEBOUNCE_MIN = 3L
    /** At most this many recorded days uploaded in one run (the rest go next run, oldest first). */
    const val DAYS_PER_RUN = 5

    // ---- the data repository ----------------------------------------------------------------------------------------

    private val REPO_PART = Regex("^[A-Za-z0-9_.-]{1,100}$")

    /** "owner/name" from the Settings field (spaces trimmed), or null when it is not a repository name. */
    fun parseRepo(s: String?): Pair<String, String>? {
        val p = s?.trim()?.removeSuffix(".git")?.split("/") ?: return null
        if (p.size != 2 || p.any { !REPO_PART.matches(it) || it == "." || it == ".." }) return null
        return p[0] to p[1]
    }

    /**
     * Why uploads to [owner]/[repo] are refused, or null when allowed: options-lab (any owner, any case) never, so a phone
     * token can never reach the code CI signs; and the name must be a repository name.
     */
    fun repoRefused(owner: String, repo: String): String? = when {
        parseRepo("$owner/$repo") == null -> "\"$owner/$repo\" is not a GitHub repository name (owner/name)."
        parseRepo("$owner/$repo")!!.second.equals(CODE_REPO, ignoreCase = true) -> "Uploads never go to $CODE_REPO (the app's code). Use the data repository, $DEFAULT_REPO."
        else -> null
    }

    // ---- names ------------------------------------------------------------------------------------------------------

    fun liveTag(day: LocalDate): String = "live-%04d-%02d".format(Locale.ROOT, day.year, day.monthValue)
    fun liveAsset(day: LocalDate): String = "live-$day$LIVE_EXT"

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT)
    private val BACKUP_NAME = Regex("^phone-backup-(\\d{4}-\\d{2}-\\d{2}-\\d{6})\\.irabk$")
    private val LIVE_NAME = Regex("^live-(\\d{4}-\\d{2}-\\d{2})\\.irlive$")

    fun backupAsset(at: LocalDateTime): String = "phone-backup-${STAMP.format(at)}$BACKUP_EXT"

    /** When a backup asset was made (its name), or null for any other name. */
    fun backupTime(name: String): LocalDateTime? =
        BACKUP_NAME.find(name)?.let { runCatching { LocalDateTime.parse(it.groupValues[1], STAMP) }.getOrNull() }

    fun liveDay(name: String): LocalDate? = LIVE_NAME.find(name)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }

    // ---- when -------------------------------------------------------------------------------------------------------

    /**
     * Today's evening gate, the earliest minute of every automatic upload (G4, 13 Oct, Boss: "after the commodities close"):
     *  - MCX trades today ([mcxToday], today's window from the app's MCX calendar): its close (23:30, or 23:55 in US winter
     *    time; a short holiday session's own close), never before 15:45;
     *  - MCX does not trade today (an MCX holiday, a weekend): 15:45, after F&O's close;
     *  - the calendar could not be read ([calendarRead] false): the later, safe time, never earlier ([EVENING_SAFE], 23:55);
     *    a window that does not close after it opens (a session past midnight): [LAST_MINUTE].
     */
    fun eveningFrom(calendarRead: Boolean, mcxToday: McxSession.Window?): Int = when {
        !calendarRead -> EVENING_SAFE
        mcxToday == null -> EVENING
        !mcxToday.close.isAfter(mcxToday.open) -> LAST_MINUTE
        else -> maxOf(EVENING, mcxToday.close.hour * 60 + mcxToday.close.minute)
    }

    /**
     * Which day's evening run belongs to now (null: none, 08:00 until the gate): [today]'s from its gate [eveningFrom], and
     * the day before's from midnight until [MORNING_UNTIL] 08:00 (a run that was due after a late MCX close, 23:55, but
     * missed - the phone was not charging - goes the next morning, well before any market opens). Every MCX session has
     * closed by midnight, so the day before's gate has passed.
     */
    fun eveningOf(minute: Int, today: LocalDate, eveningFrom: Int = EVENING): LocalDate? = when {
        minute >= eveningFrom -> today
        minute < MORNING_UNTIL -> today.minusDays(1)
        else -> null
    }

    /**
     * May an automatic run (not Boss's tap) send now: from today's gate [eveningFrom] to midnight, or from midnight until
     * [MORNING_UNTIL] (08:00). [plan] uses it to start a run; the app checks it again before each day's file and before the
     * backup, so a run that started late stops sending once the window has ended (13 Oct review of G4).
     */
    fun autoSendAllowed(minute: Int, eveningFrom: Int = EVENING): Boolean = minute >= eveningFrom || minute < MORNING_UNTIL

    /**
     * The evening whose run is due now, or null: [eveningOf], unless that evening (or a later one) is already done
     * ([lastEvening], the evening day kept by the last run that worked). A run that starts at 23:58 and ends after midnight
     * keeps the evening it started for, so the next day is neither skipped nor run twice. A [lastEvening] after [today] is a
     * clock that went back: not trusted.
     */
    fun eveningDueDay(minute: Int, lastEvening: LocalDate?, today: LocalDate, eveningFrom: Int = EVENING): LocalDate? {
        val d = eveningOf(minute, today, eveningFrom) ?: return null
        val done = lastEvening != null && !lastEvening.isBefore(d) && !lastEvening.isAfter(today)
        return if (done) null else d
    }

    /** The evening run is due (any day: the backup runs on holidays too): [eveningDueDay] is not null. */
    fun eveningDue(minute: Int, lastEvening: LocalDate?, today: LocalDate, eveningFrom: Int = EVENING): Boolean =
        eveningDueDay(minute, lastEvening, today, eveningFrom) != null

    /**
     * Minutes until a backup after a strategy or Pine change may run: [DEBOUNCE_MIN] after the last change, and never before
     * today's evening gate [eveningFrom] (the same gate as every automatic upload; 13 Oct review, G4).
     */
    fun backupDelayMin(minute: Int, eveningFrom: Int = EVENING): Long = maxOf(DEBOUNCE_MIN, (eveningFrom - minute).toLong())

    /** What one worker run does: the phone backup and/or the waiting recorded days. */
    data class Plan(val backup: Boolean, val days: Boolean)

    /** The worker's kinds (GitHubSyncWorker's input). */
    const val KIND_EVENING = "evening"
    const val KIND_BACKUP = "backup"
    const val KIND_NOW = "now"
    const val KIND_CHECK = "check"

    /** A run's tries before it waits for the next schedule (the first try included). */
    const val MAX_TRIES = 4

    /**
     * What a worker run of [kind] does now (null: only the problems check, no network). [eveningFrom]: today's evening gate
     * (after MCX's close, 15:45 on a day without MCX; see the function [eveningFrom]):
     *  - EVENING: from the gate, once a day ([eveningDue]), or a missed one the next morning before 08:00: the days and the
     *    backup.
     *  - BACKUP (after a strategy or Pine change): the backup, from the gate, or overnight until 08:00 when the job was held
     *    past midnight ([autoSendAllowed]; the worker also needs charging and unmetered Wi-Fi, as EVENING; 08:00 to the
     *    gate, the evening run covers it).
     *  - NOW: Boss's tap on "Upload now": both, at any hour and without charging (still unmetered Wi-Fi; a day's recordings
     *    still never in a trading day's 09:00-15:45, [liveAllowed]). Also the only kind that tries again after GitHub
     *    refused the token ([authBlocked]): a tap is a deliberate retry.
     *  - CHECK, an unknown kind, or the setup not [ready]: nothing.
     * While GitHub's last answer was 401/403 ([authRefused]), no automatic kind builds a backup or sends anything.
     */
    fun plan(kind: String, minute: Int, eveningDue: Boolean, ready: Boolean, authBlocked: Boolean, eveningFrom: Int = EVENING): Plan? {
        if (!ready) return null
        if (authBlocked && kind != KIND_NOW) return null
        return when (kind) {
            KIND_EVENING -> if (eveningDue && autoSendAllowed(minute, eveningFrom)) Plan(backup = true, days = true) else null
            KIND_BACKUP -> if (autoSendAllowed(minute, eveningFrom)) Plan(backup = true, days = false) else null
            KIND_NOW -> Plan(backup = true, days = true)
            else -> null
        }
    }

    /**
     * After a run that did not work: try again (WorkManager's backoff) while fewer than [MAX_TRIES] tries were made
     * ([attempt]: WorkManager's runAttemptCount, 0 on the first try). Never after GitHub refused the token: the same token
     * would be refused again; Problems found asks for a new one.
     */
    fun retry(ok: Boolean, attempt: Int, authFailed: Boolean): Boolean = !ok && !authFailed && attempt + 1 < MAX_TRIES

    /** May a day's recordings (big files) be zipped and sent now? Never in a trading day's 09:00-15:45. */
    fun liveAllowed(minute: Int, tradingDay: Boolean): Boolean = !(tradingDay && minute in MARKET_FROM until EVENING)

    /**
     * The check a run makes again just before each thing it sends (13 Oct review of G4): a day's file ([dayFile]) never in
     * a trading day's 09:00-15:45 ([liveAllowed]), by hand or not; and a run not by Boss's hand ([manual] false) only in its
     * window ([autoSendAllowed]: from the gate [eveningFrom], or before 08:00). So a run that began at 07:59, or a long
     * upload, starts nothing new once its window has ended. Boss's backup by hand goes at any hour, as before.
     */
    fun sendNow(minute: Int, tradingDay: Boolean, manual: Boolean, dayFile: Boolean, eveningFrom: Int = EVENING): Boolean =
        (!dayFile || liveAllowed(minute, tradingDay)) && (manual || autoSendAllowed(minute, eveningFrom))

    /**
     * The recorded days to upload now, oldest first, at most [max]: every day not yet uploaded, or whose files grew since
     * (a late paper fill in the evening) - never one that only shrank (the raw tick file is deleted after 10 days on the
     * phone, and the bigger copy on GitHub must stay). Today's only from 15:45 (an automatic run only starts from the evening
     * gate, after MCX's close: [plan]).
     */
    fun pendingDays(recorded: Map<LocalDate, Long>, uploaded: Map<LocalDate, Long>, today: LocalDate, minute: Int,
                    max: Int = DAYS_PER_RUN): List<LocalDate> =
        recorded.filter { (d, bytes) ->
            bytes > 0 && !d.isAfter(today) && (d != today || minute >= EVENING) && (uploaded[d]?.let { bytes > it } ?: true)
        }.keys.sorted().take(max)

    /** The recorded days waiting for an upload (the card's "N days waiting"): before today, or today after 15:45. */
    fun daysWaiting(recorded: Map<LocalDate, Long>, uploaded: Map<LocalDate, Long>, today: LocalDate, minute: Int): Int =
        pendingDays(recorded, uploaded, today, minute, Int.MAX_VALUE).size

    /**
     * The day whose diagnostics a run of [kind] uploads ([DiagDay], G3), or null: none. The evening run: its evening
     * ([eveningDay]); Boss's "Upload now": the evening it belongs to, else today so far (the evening run replaces it later).
     * A backup after a strategy change uploads none.
     */
    fun diagDay(kind: String, eveningDay: LocalDate?, minute: Int, today: LocalDate, eveningFrom: Int = EVENING): LocalDate? = when (kind) {
        KIND_EVENING -> eveningDay
        KIND_NOW -> eveningOf(minute, today, eveningFrom) ?: today
        else -> null
    }

    /** The later of the day kept and [day] (a re-run of an older day never moves the card's "last day uploaded" back). */
    fun diagKept(kept: LocalDate?, day: LocalDate): LocalDate = if (kept != null && kept.isAfter(day)) kept else day

    /** The uploaded days as kept in the vault ("2026-10-13=1234,..."), and back; the newest [keep] only. */
    fun doneEncode(m: Map<LocalDate, Long>, keep: Int = 120): String =
        m.entries.sortedBy { it.key }.takeLast(keep).joinToString(",") { "${it.key}=${it.value}" }

    fun doneDecode(s: String?): Map<LocalDate, Long> = s.orEmpty().split(",").mapNotNull { e ->
        val (d, b) = e.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
        runCatching { LocalDate.parse(d) to b.toLong() }.getOrNull()
    }.toMap()

    // ---- retention --------------------------------------------------------------------------------------------------

    data class Retain(val keep: List<String>, val delete: List<String>)

    /**
     * Which `phone-backup` assets stay: the newest of each of the [daily] most recent days that have one, and, before them,
     * the newest of each month. Every other backup (an earlier one of a kept day, an older one of a month already kept)
     * goes. A name that is not a backup of ours is never touched.
     */
    fun retention(names: List<String>, daily: Int = DAILY_KEPT): Retain {
        val ours = names.mapNotNull { n -> backupTime(n)?.let { n to it } }
        val newestOfDay = ours.groupBy { it.second.toLocalDate() }.mapValues { (_, l) -> l.maxBy { it.second } }
        val days = newestOfDay.keys.sortedDescending()
        val keep = LinkedHashSet<String>()
        days.take(daily).forEach { keep += newestOfDay.getValue(it).first }
        days.drop(daily).groupBy { YearMonth.from(it) }.values.forEach { inMonth -> keep += newestOfDay.getValue(inMonth.max()).first }
        val delete = ours.map { it.first }.filter { it !in keep }
        return Retain(names.filter { it !in delete }, delete)
    }

    // ---- the token --------------------------------------------------------------------------------------------------

    private val FINE_GRAINED = Regex("^github_pat_[A-Za-z0-9_]{22,251}$")
    private val ANY_TOKEN = Regex("(github_pat_[A-Za-z0-9_]{8,}|gh[pousr]_[A-Za-z0-9]{16,})")
    private val AUTH_HEADER = Regex("(?i)\\b(authorization|bearer|token)(\\s*[:=]?\\s*)(bearer\\s+)?[A-Za-z0-9_.\\-]{16,}")

    /** A fine-grained GitHub token's shape (the only kind the app takes: one repository, chosen permissions, an expiry). */
    fun tokenLooksRight(token: String): Boolean = FINE_GRAINED.matches(token.trim())

    /** [text] with any GitHub token, and whatever follows "Authorization"/"Bearer"/"token", replaced by [Secrets.HIDDEN]. */
    fun redact(text: String): String =
        AUTH_HEADER.replace(ANY_TOKEN.replace(text, Secrets.HIDDEN)) { m -> m.groupValues[1] + m.groupValues[2] + Secrets.HIDDEN }

    /** The token's expiry from GitHub's response header ("2026-01-10 10:20:30 UTC"), or null. */
    fun expiryHeader(value: String?): LocalDate? = value?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The expiry kept: Boss's date, or GitHub's own when that is earlier (null: neither known). */
    fun expiryKept(entered: LocalDate?, fromGitHub: LocalDate?): LocalDate? = listOfNotNull(entered, fromGitHub).minOrNull()

    /** The renewal reminder: null while more than [REMIND_DAYS] days are left; the words otherwise. */
    fun expiryNote(expiry: LocalDate?, today: LocalDate): String? {
        expiry ?: return null
        val left = ChronoUnit.DAYS.between(today, expiry)
        return when {
            left < 0 -> "The GitHub token expired on ${day(expiry)}: uploads have stopped. Make a new one and paste it in Settings → Data & Harvest → GitHub sync."
            left == 0L -> "The GitHub token expires today. Make a new one and paste it in Settings → Data & Harvest → GitHub sync."
            left <= REMIND_DAYS -> "The GitHub token expires in $left day${if (left == 1L) "" else "s"} (${day(expiry)}). Make a new one and paste it in Settings → Data & Harvest → GitHub sync."
            else -> null
        }
    }

    // ---- the card and the problems ----------------------------------------------------------------------------------

    /**
     * What happened last: [ok] or not, when, how big, what ("live 2026-10-13", "backup"), the redacted reason, and GitHub's
     * HTTP [code] when it refused (0: none, or not GitHub's answer).
     */
    data class Last(val atMs: Long, val ok: Boolean, val bytes: Long, val what: String, val why: String? = null, val code: Int = 0) {
        fun encode(): String =
            listOf(atMs, if (ok) 1 else 0, bytes, what.replace("|", "/"), (why ?: "").replace("|", "/"), code).joinToString("|")

        companion object {
            fun decode(s: String?): Last? = runCatching {
                val p = s!!.split("|")
                Last(p[0].toLong(), p[1] == "1", p[2].toLong(), p[3], p.getOrNull(4)?.ifEmpty { null }, p.getOrNull(5)?.toIntOrNull() ?: 0)
            }.getOrNull()
        }
    }

    /** GitHub refused the token itself (401, or 403; a rate limit is reported as 429 by GitHubReleases). */
    fun authCode(code: Int): Boolean = code == 401 || code == 403

    /** The last attempt failed because GitHub refused the token: the automatic uploads and backups pause until a new one. */
    fun authRefused(last: Last?): Boolean = last != null && !last.ok && authCode(last.code)

    /** Problems found's words for a refused token (13 Oct review). */
    fun tokenInvalid(last: Last, toDay: (Long) -> LocalDate): String =
        "GitHub token invalid: GitHub refused it on ${day(toDay(last.atMs))} (${last.code}). Uploads and GitHub backups are paused " +
            "until you paste a new one (Settings → Data & Harvest → GitHub sync; MAINTENANCE.md, \"GitHub token\")."

    data class Status(
        val tokenSet: Boolean,
        val keyReady: Boolean,
        val expiry: LocalDate?,
        /** The last attempt (upload or backup), and the last that worked. */
        val lastTry: Last?,
        val lastOk: Last?,
        val lastBackupOk: Last?,
        val waiting: Int,
        /** Where the uploads go ("owner/name"). */
        val repo: String = DEFAULT_REPO,
        /** The last day whose diagnostics went up ([DiagDay], G3); null: none yet. */
        val lastDiagDay: LocalDate? = null,
    )

    /** When the automatic uploads run, for the reminders (G4, 13 Oct). */
    const val WHEN = "after the MCX evening close (15:45 on days without an MCX session), or overnight before 08:00"

    /** "13 Oct". */
    fun day(d: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(d)

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(Locale.ROOT, bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(Locale.ROOT, bytes / 1e3)
        else -> "$bytes B"
    }

    /** The card's lines (label, value). */
    fun card(s: Status, toDay: (Long) -> LocalDate, today: LocalDate): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "GitHub token" to when {
            !s.tokenSet -> "not set"
            s.expiry == null -> "saved"
            else -> "saved · expires ${day(s.expiry)}"
        }
        out += "Uploads go to" to s.repo
        out += "Backup passphrase" to if (s.keyReady) "set" else "not set"
        val t = s.lastTry
        if (s.tokenSet && authRefused(t)) out += "Token" to "✗ GitHub token invalid: paste a new one"
        out += "Last upload" to when {
            t == null -> "none yet"
            t.ok -> "${day(toDay(t.atMs))} · ${size(t.bytes)} · ✓"
            else -> "${day(toDay(t.atMs))} · ✗ ${t.why ?: "failed"}"
        }
        if (t != null && !t.ok && s.lastOk != null) out += "Last that worked" to "${day(toDay(s.lastOk.atMs))} · ${size(s.lastOk.bytes)} · ✓"
        out += "Days waiting" to if (s.waiting == 0) "none" else "${s.waiting}"
        out += "Last backup to GitHub" to (s.lastBackupOk?.let { "${day(toDay(it.atMs))} ✓" } ?: "none yet")
        out += "Diagnostics" to (s.lastDiagDay?.let { "last day uploaded: ${day(it)} ✓" } ?: "last day uploaded: none yet")
        expiryNote(s.expiry, today)?.let { out += "Token" to it }
        return out
    }

    /**
     * The open problem to show (null: none): the setup is half done, the last attempt failed, or nothing has been uploaded
     * for [STALE_DAYS] days. Only once the sync is set up (a token saved).
     */
    fun problem(s: Status, today: LocalDate, toDay: (Long) -> LocalDate): String? {
        if (!s.tokenSet) return null
        if (!s.keyReady) return "GitHub sync has a token but no backup passphrase: nothing is uploaded until you set one (Settings → Data & Harvest → GitHub sync)."
        val t = s.lastTry
        if (t != null && authRefused(t)) return tokenInvalid(t, toDay)
        if (t != null && !t.ok) return "GitHub upload failed (${day(toDay(t.atMs))}): ${t.why ?: "unknown reason"}. It tries again on Wi-Fi while charging."
        val since = s.lastOk?.atMs
        val sinceDay = since?.let(toDay)
        if (sinceDay != null && ChronoUnit.DAYS.between(sinceDay, today) >= STALE_DAYS)
            return "Nothing uploaded to GitHub since ${day(sinceDay)}: put the phone on charge on Wi-Fi $WHEN." +
                if (s.waiting > 0) " ${s.waiting} recorded day${if (s.waiting == 1) "" else "s"} waiting." else ""
        if (since == null && s.waiting >= STALE_DAYS) return "Nothing uploaded to GitHub yet: ${s.waiting} recorded days waiting. Put the phone on charge on Wi-Fi $WHEN."
        return null
    }
}
