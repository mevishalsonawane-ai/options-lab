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
        /** What GET /repos/<owner>/options-lab answers (null: 404, the token cannot see it). */
        var codeRepo: Map<String, Any?>? = null
        /** What the write probe (POST /repos/<owner>/options-lab/git/refs) answers, with [probeHeaders] and [probeMessage]. */
        var probeCode = 403
        var probeHeaders: Map<String, String> = emptyMap()
        var probeMessage = "Resource not accessible by personal access token"
        /** Answers a request whose method and URL match with a code, then forgets it. */
        var failOnce: Triple<String, String, Int>? = null

        private fun json(code: Int, v: Any?, headers: Map<String, String> = emptyMap()) =
            GitHubReleases.Response(code, headers, Json.write(v).toByteArray())

        private fun assetJson(id: Long, a: Pair<String, ByteArray>) = mapOf("id" to id, "name" to a.first, "size" to a.second.size)

        override fun send(r: GitHubReleases.Request): GitHubReleases.Response {
            seen += r
            fail?.let { (part, code) -> if (r.url.contains(part)) return json(code, mapOf("message" to "Bad credentials")) }
            failOnce?.let { (method, part, code) ->
                if (r.method == method && r.url.contains(part)) { failOnce = null; return json(code, mapOf("message" to "Server Error")) }
            }
            val u = r.url
            val body = r.body?.let { b -> ByteArrayOutputStream().also { b.write(it) }.toByteArray().also { assertEquals(b.length, it.size.toLong()) } }
            Regex("^https://api.github.com/user$").find(u)?.let {
                return json(200, mapOf("login" to "boss"), expiryHeader?.let { mapOf("github-authentication-token-expiration" to it) } ?: emptyMap())
            }
            Regex("^https://api.github.com/repos/mevishalsonawane-ai/IraAlgo$").find(u)?.let { return json(200, mapOf("id" to 1)) }
            Regex("/releases/tags/([^/?]+)$").find(u)?.let { m ->
                val rel = releases.firstOrNull { it.tag == m.groupValues[1] } ?: return json(404, mapOf("message" to "Not Found"))
                return json(200, mapOf("id" to rel.id, "tag_name" to rel.tag))
            }
            if (r.method == "POST" && u.endsWith("/repos/mevishalsonawane-ai/IraAlgo/releases")) {
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
            Regex("^https://uploads.github.com/repos/mevishalsonawane-ai/IraAlgo/releases/(\\d+)/assets\\?name=(.+)$").find(u)?.let { m ->
                val rel = releases.first { it.id == m.groupValues[1].toLong() }
                val name = java.net.URLDecoder.decode(m.groupValues[2], "UTF-8")
                if (rel.assets.values.any { it.first == name }) return json(422, mapOf("message" to "already_exists"))
                val id = nextId++; rel.assets[id] = name to body!!
                return json(201, assetJson(id, name to body))
            }
            Regex("^https://api.github.com/repos/mevishalsonawane-ai/options-lab$").find(u)?.let {
                assertEquals("GET", r.method)
                return codeRepo?.let { json(200, it) } ?: json(404, mapOf("message" to "Not Found"))
            }
            if (u == "https://api.github.com/repos/mevishalsonawane-ai/options-lab/git/refs") {
                assertEquals("POST", r.method)
                return json(probeCode, mapOf("message" to probeMessage), probeHeaders)
            }
            Regex("/releases/assets/(\\d+)$").find(u)?.let { m ->
                val id = m.groupValues[1].toLong()
                val rel = releases.firstOrNull { id in it.assets } ?: return json(404, mapOf("message" to "Not Found"))
                if (r.method == "DELETE") { rel.assets.remove(id); return GitHubReleases.Response(204, emptyMap(), ByteArray(0)) }
                if (r.method == "PATCH") {
                    val name = (Json.parse(String(body!!)) as Map<*, *>)["name"].toString()
                    if (rel.assets.any { it.key != id && it.value.first == name }) return json(422, mapOf("message" to "already_exists"))
                    rel.assets[id] = name to rel.assets.getValue(id).second
                    return json(200, assetJson(id, rel.assets.getValue(id)))
                }
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
        val backedUp = ArrayList<LocalDateTime>()
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
        override fun backedUp(at: LocalDateTime) { backedUp += at }
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
    // ---- 13 Oct review ------------------------------------------------------------------------------------------------

    @Test fun theClientRefusesOptionsLab() {
        for (r in listOf("options-lab", "Options-Lab")) {
            val e = assertFailsWith<IllegalArgumentException> { GitHubReleases(gh, token, CloudSync.OWNER, r) }
            assertFalse(e.message!!.contains(token))
        }
        assertTrue(gh.seen.isEmpty(), "nothing was sent")
        // The default is the data repository.
        GitHubReleases(gh, token).canSeeRepo()
        assertEquals("https://api.github.com/repos/mevishalsonawane-ai/IraAlgo", gh.seen.single().url)
    }

    @Test fun aTokenThatReachesTheCodeIsSpotted() {
        assertFalse(client.reachesCode())                                         // 404: cannot see it
        assertEquals(1, gh.seen.size, "no probe when it cannot see the code")
        gh.codeRepo = mapOf("private" to true)
        assertTrue(client.reachesCode())                                          // sees the private code repository
        assertTrue(gh.seen.all { it.method == "GET" }, "no probe for a private code repository")
        gh.codeRepo = mapOf("id" to 1)
        assertTrue(client.reachesCode(), "private not said: fails closed")
        // An ambiguous refusal fails closed; a rate limit is an error, not an answer.
        val refusing = GitHubReleases({ GitHubReleases.Response(403, emptyMap(), Json.write(mapOf("message" to "SAML enforcement")).toByteArray()) }, token)
        assertTrue(refusing.reachesCode())
        val limited = GitHubReleases({ GitHubReleases.Response(403, mapOf("x-ratelimit-remaining" to "0"), ByteArray(0)) }, token)
        assertFailsWith<GitHubReleases.Failure> { limited.reachesCode() }
    }

    /** Boss's token (13 Oct): IraAlgo only, Contents read/write. options-lab is public and Boss owns it, so GitHub's GET says admin. */
    private val publicOwned = mapOf("private" to false, "permissions" to mapOf("admin" to true, "maintain" to true, "push" to true, "pull" to true))

    @Test fun bossesIraAlgoOnlyTokenIsAccepted() {
        gh.codeRepo = publicOwned
        gh.probeCode = 403
        assertFalse(client.reachesCode(), "the user's admin role is not the token's grant")
        assertEquals(listOf("GET", "POST"), gh.seen.map { it.method })
        gh.probeCode = 404; gh.probeMessage = "Not Found"
        assertFalse(client.reachesCode())
        // Also when the GET says read only: the probe alone decides.
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("pull" to true, "push" to false))
        gh.probeCode = 403
        assertFalse(client.reachesCode())
    }

    @Test fun aTokenThatCanWriteTheCodeIsRefused() {
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("pull" to true, "push" to false))   // whatever the GET says
        // 422 and 409 got past GitHub's access check; 200/201 should be impossible; 5xx and a redirect are not understood.
        for (code in listOf(422, 409, 201, 200, 500, 502, 302)) {
            gh.probeCode = code; gh.probeMessage = "Object does not exist"
            assertTrue(client.reachesCode(), "probe $code")
        }
    }

    @Test fun theProbeIsAWriteThatCanNeverBeMade() {
        gh.codeRepo = publicOwned
        client.reachesCode()
        val p = gh.seen.single { it.method == "POST" }
        assertEquals("https://api.github.com/repos/mevishalsonawane-ai/options-lab/git/refs", p.url)
        assertEquals("Bearer $token", p.headers["Authorization"])
        val bytes = ByteArrayOutputStream().also { p.body!!.write(it) }.toByteArray()
        assertEquals("application/json", p.body!!.contentType)
        assertEquals(bytes.size.toLong(), p.body!!.length)
        val o = Json.parse(String(bytes)) as Map<*, *>
        assertEquals(setOf("ref", "sha"), o.keys)
        assertEquals("refs/heads/__iraalgo_token_probe__", o["ref"])
        assertEquals("0".repeat(40), o["sha"])
        // Never a real sha: the only 40-hex run in the request is the all-zero one.
        val hex = Regex("[0-9a-fA-F]{40}").findAll(String(bytes) + p.url).map { it.value }.toList()
        assertEquals(listOf("0".repeat(40)), hex)
        // Nothing else was asked of options-lab, and nothing at all of any other repository.
        assertTrue(gh.seen.all { it.url.startsWith("https://api.github.com/repos/mevishalsonawane-ai/options-lab") })
        assertEquals(2, gh.seen.size)
    }

    @Test fun theProbeThrowsOnARefusedTokenOrARateLimit() {
        gh.codeRepo = publicOwned
        gh.probeCode = 401; gh.probeMessage = "Bad credentials"
        assertEquals(401, assertFailsWith<GitHubReleases.Failure> { client.reachesCode() }.code)
        gh.probeCode = 429; gh.probeMessage = "Too many requests"
        assertEquals(429, assertFailsWith<GitHubReleases.Failure> { client.reachesCode() }.code)
        gh.probeCode = 403; gh.probeMessage = "You have exceeded a secondary rate limit"
        assertEquals(429, assertFailsWith<GitHubReleases.Failure> { client.reachesCode() }.code)
        gh.probeMessage = "Resource not accessible by personal access token"; gh.probeHeaders = mapOf("x-ratelimit-remaining" to "0")
        assertEquals(429, assertFailsWith<GitHubReleases.Failure> { client.reachesCode() }.code)
        // No error carries the token.
        gh.probeCode = 401; gh.probeHeaders = emptyMap()
        assertFalse(assertFailsWith<GitHubReleases.Failure> { client.reachesCode() }.message!!.contains(token))
    }

    @Test fun aReplacementNeverLeavesNoCopy() {
        val rel = client.ensureRelease("live-2026-10", "t", "n")
        client.upload(rel, "a.irlive", GitHubReleases.Body.of(byteArrayOf(1)))
        // The new copy goes up under a temporary name; the upload fails: the old copy stays, alone.
        gh.failOnce = Triple("POST", "a.irlive.new", 500)
        assertFailsWith<GitHubReleases.Failure> { client.upload(rel, "a.irlive", GitHubReleases.Body.of(byteArrayOf(2, 2))) }
        assertEquals(listOf("a.irlive" to 1L), client.assets(rel.id).map { it.name to it.size })
        // The rename fails after the old copy went: the new copy is there under its temporary name.
        gh.failOnce = Triple("PATCH", "/releases/assets/", 500)
        assertFailsWith<GitHubReleases.Failure> { client.upload(rel, "a.irlive", GitHubReleases.Body.of(byteArrayOf(3, 3, 3))) }
        assertEquals(listOf("a.irlive.new" to 3L), client.assets(rel.id).map { it.name to it.size })
        // The next upload puts the final name back first, then removes the temporary copy.
        val a = client.upload(rel, "a.irlive", GitHubReleases.Body.of(byteArrayOf(4, 4, 4, 4)))
        assertEquals("a.irlive", a.name)
        assertEquals(listOf("a.irlive" to 4L), client.assets(rel.id).map { it.name to it.size })
        // A clean replacement: upload as .new, delete the old, rename. One asset, the new bytes.
        client.upload(rel, "a.irlive", GitHubReleases.Body.of(byteArrayOf(5)))
        assertEquals(listOf("GET", "POST", "DELETE", "PATCH"), gh.seen.takeLast(4).map { it.method })
        assertEquals(listOf("a.irlive" to 1L), client.assets(rel.id).map { it.name to it.size })
        tokenOnlyInAuthorization()
    }

    @Test fun theBackupReminderRestsOnlyAfterAnUploadThatWorked() {
        gh.fail = "uploads.github.com" to 500
        assertFalse(run.backup(LocalDateTime.of(2026, 10, 13, 18, 0)).ok)
        assertTrue(phone.backedUp.isEmpty())
        gh.fail = null
        assertTrue(run.backup(LocalDateTime.of(2026, 10, 13, 18, 5)).ok)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 13, 18, 5)), phone.backedUp)
    }

    @Test fun aRefusedTokenCarriesItsCodeAndARateLimitDoesNot() {
        gh.fail = "uploads.github.com" to 401
        assertEquals(401, run.backup(LocalDateTime.of(2026, 10, 13, 18, 0)).code)
        phone.rec[LocalDate.of(2026, 10, 9)] = 10
        gh.fail = "uploads.github.com" to 403
        assertEquals(403, assertNotNull(run.live(today, after)).code)
        // GitHub's rate limit answers 403 too: kept as 429, so the uploads are not paused for it.
        val limited = GitHubReleases({ GitHubReleases.Response(403, mapOf("x-ratelimit-remaining" to "0"),
            Json.write(mapOf("message" to "API rate limit exceeded")).toByteArray()) }, token)
        assertEquals(429, assertFailsWith<GitHubReleases.Failure> { limited.user() }.code)
    }

    @Test fun aBackupLeftUnderItsTemporaryNameIsNamedBackByThePrune() {
        val rel = FakeGitHub.Rel(gh.nextId++, CloudSync.BACKUP_TAG); gh.releases += rel
        val stuck = CloudSync.backupAsset(LocalDateTime.of(2026, 10, 12, 18, 0))
        rel.assets[gh.nextId++] = "$stuck.new" to ByteArray(3)
        val both = CloudSync.backupAsset(LocalDateTime.of(2026, 10, 11, 18, 0))
        rel.assets[gh.nextId++] = both to ByteArray(1)
        rel.assets[gh.nextId++] = "$both.new" to ByteArray(2)
        run.prune(rel.id)
        assertEquals(listOf(both, stuck), gh.names(CloudSync.BACKUP_TAG).sorted())
        assertEquals(listOf(stuck, both), run.backups().map { it.first.name })
    }
}
