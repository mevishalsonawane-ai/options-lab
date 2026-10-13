package com.optionslab.ira

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
 *    evening, and a few minutes after a strategy or Pine script is added or changed. The last [DAILY_KEPT] daily backups
 *    are kept, plus one a month before them ([retention]).
 *
 * When ([eveningDue], [liveAllowed]): after 15:45, on unmetered Wi-Fi and while charging (the worker's own WorkManager
 * constraints), and never a day's big file between 09:00 and 15:45 of a trading day, even by hand. A missed day stays
 * waiting and goes next evening ([pendingDays]).
 *
 * The token: a fine-grained GitHub token for options-lab only ([tokenLooksRight]); [redact] takes any token out of a text
 * before it is shown, logged or kept. Pure: the day and minute are given.
 */
object CloudSync {
    const val OWNER = "mevishalsonawane-ai"
    const val REPO = "options-lab"
    const val BACKUP_TAG = "phone-backup"
    const val LIVE_EXT = ".irlive"
    const val BACKUP_EXT = ".irabk"

    /** The evening run's earliest minute (15:45 IST). */
    const val EVENING = 15 * 60 + 45
    const val MARKET_FROM = 9 * 60
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

    /** The evening run is due: after 15:45 and not yet done [today] (any day: the backup runs on holidays too). */
    fun eveningDue(minute: Int, lastEvening: LocalDate?, today: LocalDate): Boolean = minute >= EVENING && lastEvening != today

    /** May a day's recordings (big files) be zipped and sent now? Never in a trading day's 09:00-15:45. */
    fun liveAllowed(minute: Int, tradingDay: Boolean): Boolean = !(tradingDay && minute in MARKET_FROM until EVENING)

    /**
     * The recorded days to upload now, oldest first, at most [max]: every day not yet uploaded, or whose files grew since
     * (a late paper fill in the evening) - never one that only shrank (the raw tick file is deleted after 10 days on the
     * phone, and the bigger copy on GitHub must stay). Today's only from 15:45.
     */
    fun pendingDays(recorded: Map<LocalDate, Long>, uploaded: Map<LocalDate, Long>, today: LocalDate, minute: Int,
                    max: Int = DAYS_PER_RUN): List<LocalDate> =
        recorded.filter { (d, bytes) ->
            bytes > 0 && !d.isAfter(today) && (d != today || minute >= EVENING) && (uploaded[d]?.let { bytes > it } ?: true)
        }.keys.sorted().take(max)

    /** The recorded days waiting for an upload (the card's "N days waiting"): before today, or today after 15:45. */
    fun daysWaiting(recorded: Map<LocalDate, Long>, uploaded: Map<LocalDate, Long>, today: LocalDate, minute: Int): Int =
        pendingDays(recorded, uploaded, today, minute, Int.MAX_VALUE).size

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

    /** What happened last: [ok] or not, when, how big, what ("live 2026-10-13", "backup"), and the redacted reason. */
    data class Last(val atMs: Long, val ok: Boolean, val bytes: Long, val what: String, val why: String? = null) {
        fun encode(): String = listOf(atMs, if (ok) 1 else 0, bytes, what.replace("|", "/"), (why ?: "").replace("|", "/")).joinToString("|")

        companion object {
            fun decode(s: String?): Last? = runCatching {
                val p = s!!.split("|")
                Last(p[0].toLong(), p[1] == "1", p[2].toLong(), p[3], p.getOrNull(4)?.ifEmpty { null })
            }.getOrNull()
        }
    }

    data class Status(
        val tokenSet: Boolean,
        val keyReady: Boolean,
        val expiry: LocalDate?,
        /** The last attempt (upload or backup), and the last that worked. */
        val lastTry: Last?,
        val lastOk: Last?,
        val lastBackupOk: Last?,
        val waiting: Int,
    )

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
        out += "Backup passphrase" to if (s.keyReady) "set" else "not set"
        val t = s.lastTry
        out += "Last upload" to when {
            t == null -> "none yet"
            t.ok -> "${day(toDay(t.atMs))} · ${size(t.bytes)} · ✓"
            else -> "${day(toDay(t.atMs))} · ✗ ${t.why ?: "failed"}"
        }
        if (t != null && !t.ok && s.lastOk != null) out += "Last that worked" to "${day(toDay(s.lastOk.atMs))} · ${size(s.lastOk.bytes)} · ✓"
        out += "Days waiting" to if (s.waiting == 0) "none" else "${s.waiting}"
        out += "Last backup to GitHub" to (s.lastBackupOk?.let { "${day(toDay(it.atMs))} ✓" } ?: "none yet")
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
        if (t != null && !t.ok) return "GitHub upload failed (${day(toDay(t.atMs))}): ${t.why ?: "unknown reason"}. It tries again on Wi-Fi while charging."
        val since = s.lastOk?.atMs
        val sinceDay = since?.let(toDay)
        if (sinceDay != null && ChronoUnit.DAYS.between(sinceDay, today) >= STALE_DAYS)
            return "Nothing uploaded to GitHub since ${day(sinceDay)}: put the phone on charge on Wi-Fi after 15:45." +
                if (s.waiting > 0) " ${s.waiting} recorded day${if (s.waiting == 1) "" else "s"} waiting." else ""
        if (since == null && s.waiting >= STALE_DAYS) return "Nothing uploaded to GitHub yet: ${s.waiting} recorded days waiting. Put the phone on charge on Wi-Fi after 15:45."
        return null
    }
}
