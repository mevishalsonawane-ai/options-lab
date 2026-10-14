package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One run of the phone's GitHub sync (TODO G1/G2, 13 Oct), the phone's side given as [Phone] so the whole run is tested with a
 * fake GitHub ([GitHubReleases.Http]):
 *
 *  - [live]: each recorded day waiting ([CloudSync.pendingDays]), oldest first: sealed by the phone, uploaded to `live-YYYY-MM`,
 *    then marked done with its size. A failure stops the run (the rest wait for the next one); what was done stays done.
 *  - [backup]: the sealed phone backup uploaded to `phone-backup`, then the old ones pruned ([CloudSync.retention]). A failed
 *    prune is not a failed backup.
 *  - [diag]: the day's redacted diagnostics ([DiagDay], G3) uploaded to `diag-YYYY-MM`, replacing that day's file.
 *
 * Words for Boss only in the outcome (redacted); never the token. Blocking: a background thread.
 */
class CloudRun(private val gh: GitHubReleases, private val phone: Phone) {

    /** A sealed file ready to send: its size, how to write it, and what to delete after. */
    class Sealed(val length: Long, val write: (java.io.OutputStream) -> Unit, val done: () -> Unit = {})

    interface Phone {
        /** Each recorded day's folder size now (bytes). */
        fun recorded(): Map<LocalDate, Long>
        /** The days uploaded, with the size each had then. */
        fun uploaded(): Map<LocalDate, Long>
        fun markUploaded(day: LocalDate, bytes: Long)
        /** [day]'s folder zipped and sealed with the passphrase key. */
        fun sealDay(day: LocalDate): Sealed
        /** The phone backup, sealed with the passphrase key (the Backup.kt format). */
        fun backup(): ByteArray
        /** False once the run must stop (WorkManager stopped the worker). */
        fun goOn(): Boolean = true
        /**
         * The backup made at [at] is on GitHub (called only after the upload worked): the phone's backup reminder may rest.
         * Never on a failed upload (13 Oct review: a failed upload must not silence the reminder).
         */
        fun backedUp(at: LocalDateTime) = Unit
    }

    /** [code]: GitHub's HTTP code when it refused (0: none, or not GitHub's answer). */
    data class Outcome(val ok: Boolean, val bytes: Long, val what: String, val why: String? = null, val days: List<LocalDate> = emptyList(),
                       val code: Int = 0)

    private fun code(e: Throwable): Int = (e as? GitHubReleases.Failure)?.code ?: 0

    private fun why(e: Throwable): String = CloudSync.redact(when (e) {
        is GitHubReleases.Failure -> e.message ?: "GitHub refused it"
        is java.net.UnknownHostException, is java.net.ConnectException -> "no connection to GitHub"
        is java.net.SocketTimeoutException -> "GitHub did not answer in time"
        is java.io.IOException -> "network: ${e.javaClass.simpleName}"
        else -> e.javaClass.simpleName
    }).take(160)

    /** The waiting recorded days (null outcome: nothing waiting). */
    fun live(today: LocalDate, minute: Int): Outcome? {
        val days = CloudSync.pendingDays(phone.recorded(), phone.uploaded(), today, minute)
        if (days.isEmpty()) return null
        val done = ArrayList<LocalDate>()
        var bytes = 0L
        for (d in days) {
            if (!phone.goOn()) break
            val size = phone.recorded()[d] ?: continue
            val sealed = try { phone.sealDay(d) } catch (e: Exception) {
                return Outcome(false, bytes, "live $d", "could not pack the day: ${why(e)}", done)
            }
            try {
                val rel = gh.ensureRelease(CloudSync.liveTag(d), "Live recordings ${CloudSync.liveTag(d).removePrefix("live-")}",
                    "The phone's recorded days, sealed with Boss's backup passphrase (research/data/decrypt_live.py opens them).")
                gh.upload(rel, CloudSync.liveAsset(d), GitHubReleases.Body(sealed.length, "application/octet-stream", sealed.write))
                phone.markUploaded(d, size)
                done += d; bytes += sealed.length
            } catch (e: Exception) {
                return Outcome(false, bytes, "live $d", why(e), done, code(e))
            } finally { runCatching { sealed.done() } }
        }
        return Outcome(true, bytes, if (done.size == 1) "live ${done[0]}" else "live ${done.size} days", days = done)
    }

    /**
     * [day]'s diagnostics ([DiagDay], TODO G3, 13 Oct): one small file, `diag-YYYY-MM-DD.json.gz` in the release `diag-YYYY-MM`.
     * A re-run replaces that day's file ([GitHubReleases.upload]), so the same day never has two. [report] makes the bytes
     * (already redacted and capped); a report that cannot be made is a failed outcome, never a crash of the run.
     */
    fun diag(day: LocalDate, report: () -> ByteArray): Outcome {
        val what = "diagnostics $day"
        val bytes = try { report() } catch (e: Exception) { return Outcome(false, 0, what, "could not make the diagnostics: ${why(e)}") }
        return try {
            val rel = gh.ensureRelease(DiagDay.tag(day), "Diagnostics ${DiagDay.tag(day).removePrefix("diag-")}",
                "The phone's daily diagnostics, redacted on the phone (no tokens, ids, names or rupees): research/data/read_diagnostics.py reads them.")
            gh.upload(rel, DiagDay.assetName(day), GitHubReleases.Body.of(bytes, "application/gzip"))
            Outcome(true, bytes.size.toLong(), what, days = listOf(day))
        } catch (e: Exception) { Outcome(false, 0, what, why(e), code = code(e)) }
    }

    /** The phone backup made at [now], uploaded; then the old ones pruned. */
    fun backup(now: LocalDateTime): Outcome {
        val bytes = try { phone.backup() } catch (e: Exception) { return Outcome(false, 0, "backup", "could not make the backup: ${why(e)}") }
        val rel = try {
            val r = gh.ensureRelease(CloudSync.BACKUP_TAG, "Phone backup",
                "IraAlgo's phone backups, sealed with Boss's backup passphrase. Restore one from the app: Settings → Data & Harvest → GitHub sync → Restore from GitHub.")
            gh.upload(r, CloudSync.backupAsset(now), GitHubReleases.Body.of(bytes))
            r
        } catch (e: Exception) { return Outcome(false, 0, "backup", why(e), code = code(e)) } finally { bytes.fill(0) }
        runCatching { phone.backedUp(now) }
        runCatching { prune(rel.id) }
        return Outcome(true, bytes.size.toLong(), "backup")
    }

    /** Deletes the backups [CloudSync.retention] does not keep; returns how many went. */
    fun prune(releaseId: Long): Int {
        // A backup left under its temporary name by a failed rename: named back (or dropped when the real one is there),
        // so Restore sees it and retention counts it.
        val first = gh.assets(releaseId)
        val names = first.map { it.name }.toSet()
        first.filter { it.name.endsWith(".new") }.forEach { a ->
            val base = a.name.removeSuffix(".new")
            if (CloudSync.backupTime(base) != null) runCatching { if (base in names) gh.delete(a.id) else gh.rename(a.id, base) }
        }
        val assets = gh.assets(releaseId)
        val gone = CloudSync.retention(assets.map { it.name }).delete.toSet()
        assets.filter { it.name in gone }.forEach { gh.delete(it.id) }
        return gone.size
    }

    /** The backups on GitHub, newest first (for "Restore from GitHub"). */
    fun backups(): List<Pair<GitHubReleases.Asset, LocalDateTime>> {
        val rel = gh.release(CloudSync.BACKUP_TAG) ?: return emptyList()
        return rel.assets.mapNotNull { a -> CloudSync.backupTime(a.name)?.let { a to it } }.sortedByDescending { it.second }
    }
}
