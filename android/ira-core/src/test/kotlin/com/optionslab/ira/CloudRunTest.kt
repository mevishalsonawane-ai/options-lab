package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The release client and a whole sync run against a fake GitHub (no network). */
class CloudRunTest {
    private val token = "github_pat_" + "TEST" + "x".repeat(60)

    /** A small GitHub: releases by tag with assets, every request kept; [fail] answers a matching URL with a code. */
    private class FakeGitHub : GitHubReleases.Http {
        class Rel(val id: Long, val tag: String, val assets: MutableMap<Long, Pair<String, ByteArray>> = LinkedHashMap())
        val releases = ArrayList<Rel>()
        val seen = ArrayList<GitHubReleases.Request>()
        var nextId = 100L
        var fail: Pair<String, Int>? = null
        var expiryHeader: String? = "2027-01-10 10:20:30 UTC"

        private fun json(code: Int, v: Any?, headers: Map<String, String> = emptyMap()) =
            GitHubReleases.Response(code, headers, Json.write(v).toByteArray())

        private fun assetJson(id: Long, a: Pair<String, ByteArray>) = mapOf("id" to id, "name" to a.first, "size" to a.second.size)

        override fun send(r: GitHubReleases.Request): GitHubReleases.Response {
            seen += r
            fail?.let { (part, code) -> if (r.url.contains(part)) return json(code, mapOf("message" to "Bad credentials")) }
            val u = r.url
            val body = r.body?.let { b -> ByteArrayOutputStream().also { b.write(it) }.toByteArray().also { assertEquals(b.length, it.size.toLong()) } }
            Regex("^https://api.github.com/user$").find(u)?.let {
                return json(200, mapOf("login" to "boss"), expiryHeader?.let { mapOf("github-authentication-token-expiration" to it) } ?: emptyMap())
            }
            Regex("^https://api.github.com/repos/mevishalsonawane-ai/options-lab$").find(u)?.let { return json(200, mapOf("id" to 1)) }
            Regex("/releases/tags/([^/?]+)$").find(u)?.let { m ->
                val rel = releases.firstOrNull { it.tag == m.groupValues[1] } ?: return json(404, mapOf("message" to "Not Found"))
                return json(200, mapOf("id" to rel.id, "tag_name" to rel.tag))
            }
            if (r.method == "POST" && u.endsWith("/repos/mevishalsonawane-ai/options-lab/releases")) {
                val o = Json.parse(String(body!!)) as Map<*, *>
                val rel = Rel(nextId++, o["tag_name"].toString()); releases += rel
                assertEquals(true, o["prerelease"])
                return json(201, mapOf("id" to rel.id))
            }
            Regex("/releases/(\\d+)/assets\\?per_page=100&page=(\\d+)$").find(u)?.let { m ->
                val rel = releases.first { it.id == m.groupValues[1].toLong() }
                val page = m.groupValues[2].toInt()
                return json(200, rel.assets.entries.toList().drop((page - 1) * 100).take(100).map { assetJson(it.key, it.value) })
            }
            Regex("^https://uploads.github.com/repos/mevishalsonawane-ai/options-lab/releases/(\\d+)/assets\\?name=(.+)$").find(u)?.let { m ->
                val rel = releases.first { it.id == m.groupValues[1].toLong() }
                val name = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8")
                if (rel.assets.values.any { it.first == name }) return json(422, mapOf("message" to "already_exists"))
                val id = nextId++; rel.assets[id] = name to body!!
                return json(201, assetJson(id, name to body))
            }
            Regex("/releases/assets/(\\d+)$").find(u)?.let { m ->
                val id = m.groupValues[1].toLong()
                val rel = releases.firstOrNull { id in it.assets } ?: return json(404, mapOf("message" to "Not Found"))
                if (r.method == "DELETE") { rel.assets.remove(id); return GitHubReleases.Response(204, emptyMap(), ByteArray(0)) }
                return GitHubReleases.Response(302, mapOf("location" to "https://objects.githubusercontent.com/store/$id?sig=1"), ByteArray(0))
            }
            Regex("^https://objects.githubusercontent.com/store/(\\d+)").find(u)?.let { m ->
                val id = m.groupValues[1].toLong()
                return GitHubReleases.Response(200, emptyMap(), releases.first { id in it.assets }.assets.getValue(id).second)
            }
            return json(404, mapOf("message" to "Not Found"))
        }

        fun names(tag: String) = releases.first { it.tag == tag }.assets.values.map { it.first }
    }

    private class FakePhone(val gh: FakeGitHub) : CloudRun.Phone {
        val rec = LinkedHashMap<LocalDate, Long>()
        val up = LinkedHashMap<LocalDate, Long>()
        var sealedDays = ArrayList<LocalDate>()
        var cleaned = 0
        var backupBytes = "backup".toByteArray()
        var stopAfter = Int.MAX_VALUE
        override fun recorded() = rec
        override fun uploaded() = up
        override fun markUploaded(day: LocalDate, bytes: Long) { up[day] = bytes }
        override fun sealDay(day: LocalDate): CloudRun.Sealed {
            sealedDays += day
            val b = "sealed $day".toByteArray()
            return CloudRun.Sealed(b.size.toLong(), { it.write(b) }, { cleaned++ })
        }
        override fun backup() = backupBytes.copyOf()
        override fun goOn() = sealedDays.size < stopAfter
    }

    private val gh = FakeGitHub()
    private val client = GitHubReleases(gh, token)
    private val phone = FakePhone(gh)
    private val run = CloudRun(client, phone)
    private val today = LocalDate.of(2026, 10, 13)
    private val after = 15 * 60 + 50

    private fun tokenOnlyInAuthorization() {
        for (r in gh.seen) {
            assertFalse(r.url.contains(token), r.url)
            r.headers.forEach { (k, v) -> if (k != "Authorization") assertFalse(v.contains(token), k) }
            if (r.url.startsWith("https://api.github.com/") || r.url.startsWith("https://uploads.github.com/"))
                assertEquals("Bearer $token", r.headers["Authorization"])
            else assertNull(r.headers["Authorization"], "no token beyond GitHub's API: ${r.url}")
        }
    }

    @Test fun theTokenIsCheckedWithGetUser() {
        val u = client.user()
        assertEquals("boss", u.login)
        assertEquals(LocalDate.of(2027, 1, 10), u.expires)
        assertTrue(client.canSeeRepo())
        gh.fail = "/user" to 401
        val e = assertFailsWith<GitHubReleases.Failure> { client.user() }
        assertEquals(401, e.code)
        assertTrue(e.message!!.contains("refused"), e.message)
        assertFalse(e.message!!.contains(token))
        tokenOnlyInAuthorization()
    }

    @Test fun recordedDaysGoToTheirMonthsReleaseOnce() {
        phone.rec[LocalDate.of(2026, 9, 30)] = 10
        phone.rec[LocalDate.of(2026, 10, 1)] = 20
        phone.rec[today] = 30
        // Before 15:45 today's day waits.
        val first = assertNotNull(run.live(today, 15 * 60))
        assertTrue(first.ok)
        assertEquals(listOf(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)), first.days)
        assertEquals(listOf("live-2026-09-30.irlive"), gh.names("live-2026-09"))
        assertEquals(listOf("live-2026-10-01.irlive"), gh.names("live-2026-10"))
        // After it, only today goes; a second run has nothing to do.
        assertEquals(listOf(today), assertNotNull(run.live(today, after)).days)
        assertNull(run.live(today, after))
        assertEquals(3, phone.cleaned)
        // A late fill grew today's folder: today goes again, replacing its asset (never two of one day).
        phone.rec[today] = 35
        assertEquals(listOf(today), assertNotNull(run.live(today, after)).days)
        assertEquals(listOf("live-2026-10-01.irlive", "live-2026-10-13.irlive"), gh.names("live-2026-10").sorted())
        assertEquals(35L, phone.up[today])
        tokenOnlyInAuthorization()
    }

    @Test fun aFailureStopsTheRunAndTheDayWaits() {
        phone.rec[LocalDate.of(2026, 10, 9)] = 10
        phone.rec[LocalDate.of(2026, 10, 12)] = 10
        gh.fail = "uploads.github.com" to 500
        val o = assertNotNull(run.live(today, after))
        assertFalse(o.ok)
        assertTrue(o.why!!.startsWith("500"), o.why)
        assertEquals(emptyList(), o.days)
        assertEquals(1, phone.sealedDays.size)
        assertEquals(1, phone.cleaned)            // the sealed temp file goes even after a failure
        assertTrue(phone.up.isEmpty())
        gh.fail = null
        assertEquals(2, assertNotNull(run.live(today, after)).days.size)
    }

    @Test fun aStoppedWorkerStopsBetweenDays() {
        (1..4).forEach { phone.rec[LocalDate.of(2026, 10, it)] = 5 }
        phone.stopAfter = 2
        assertEquals(2, assertNotNull(run.live(today, after)).days.size)
    }

    @Test fun theBackupGoesUpAndOldOnesArePruned() {
        // 40 evenings already there.
        val rel = FakeGitHub.Rel(gh.nextId++, CloudSync.BACKUP_TAG); gh.releases += rel
        for (i in 1..40) rel.assets[gh.nextId++] = CloudSync.backupAsset(today.minusDays(i.toLong()).atTime(18, 0)) to ByteArray(1)
        val o = run.backup(LocalDateTime.of(2026, 10, 13, 18, 1, 2))
        assertTrue(o.ok, o.why)
        assertEquals(6L, o.bytes)
        val names = gh.names(CloudSync.BACKUP_TAG)
        assertTrue("phone-backup-2026-10-13-180102.irabk" in names)
        // 3 Sep .. 13 Oct were there: 30 daily (14 Sep .. 13 Oct) and, before them, September's newest (13 Sep).
        assertEquals(31, names.size)
        assertTrue("phone-backup-2026-09-13-180000.irabk" in names)
        assertFalse("phone-backup-2026-09-12-180000.irabk" in names)
        // Restore's list: newest first, and the download follows GitHub's redirect without the token.
        val list = run.backups()
        assertEquals(LocalDateTime.of(2026, 10, 13, 18, 1, 2), list.first().second)
        assertContentEquals("backup".toByteArray(), client.download(list.first().first.id))
        tokenOnlyInAuthorization()
    }

    @Test fun aFailedBackupSaysWhyAndAFailedPruneDoesNotFailIt() {
        gh.fail = "uploads.github.com" to 403
        val o = run.backup(LocalDateTime.of(2026, 10, 13, 18, 0))
        assertFalse(o.ok)
        assertTrue(o.why!!.contains("403"), o.why)
        // Old backups to prune, and every delete refused: the backup itself still counts as done.
        val rel = gh.releases.first { it.tag == CloudSync.BACKUP_TAG }
        for (i in 1..40) rel.assets[gh.nextId++] = CloudSync.backupAsset(LocalDate.of(2026, 10, 13).minusDays(i.toLong()).atTime(18, 0)) to ByteArray(1)
        gh.fail = "/releases/assets/" to 500
        assertTrue(run.backup(LocalDateTime.of(2026, 10, 13, 19, 0)).ok)
        assertEquals(41, rel.assets.size)
        gh.fail = null
        val p = object : CloudRun.Phone by phone { override fun backup(): ByteArray = throw IllegalStateException("vault unreadable") }
        val bad = CloudRun(client, p).backup(LocalDateTime.of(2026, 10, 13, 20, 0))
        assertFalse(bad.ok)
        assertTrue(bad.why!!.contains("could not make the backup"))
    }

    @Test fun noBackupsYetIsAnEmptyList() {
        assertEquals(emptyList(), run.backups())
    }

    @Test fun anExistingReleaseIsUsedAndAPreviousAssetReplaced() {
        val r1 = client.ensureRelease("live-2026-10", "t", "n")
        val r2 = client.ensureRelease("live-2026-10", "t", "n")
        assertEquals(r1.id, r2.id)
        assertEquals(1, gh.releases.size)
        client.upload(r2, "a.irlive", GitHubReleases.Body.of(byteArrayOf(1)))
        client.upload(client.release("live-2026-10")!!, "a.irlive", GitHubReleases.Body.of(byteArrayOf(2, 3)))
        val assets = client.assets(r1.id)
        assertEquals(1, assets.size)
        assertEquals(2L, assets[0].size)
    }

    @Test fun pagesOfAssetsAreAllRead() {
        val rel = FakeGitHub.Rel(gh.nextId++, "big"); gh.releases += rel
        repeat(230) { rel.assets[gh.nextId++] = "f$it" to ByteArray(0) }
        assertEquals(230, client.assets(rel.id).size)
    }
}
