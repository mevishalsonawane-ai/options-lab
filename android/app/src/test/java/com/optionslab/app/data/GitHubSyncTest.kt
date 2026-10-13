package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.strategy.Json
import com.optionslab.ira.CloudSync
import com.optionslab.ira.GitHubReleases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate

/**
 * GitHub sync after the 13 Oct review: the uploads go to the data repository (never options-lab), the token's only PIN-less
 * copy is the upload worker's and is bound to that repository, Restore from GitHub needs the PIN, a failed upload leaves the
 * backup reminder as it was, a refused token pauses the automatic runs, and HttpsGitHub keeps the token to GitHub's API.
 * A fake GitHub ([FakeGitHub]); every token here is a made-up test value.
 */
class GitHubSyncTest : RobolectricTest() {
    private val token = "github_pat_" + "TEST" + "y".repeat(60)
    private val pin = "482913"
    private val gh = FakeGitHub()

    /** GitHub for the data repository: the user, the repository, releases and assets; [uploadCode] answers the uploads. */
    class FakeGitHub : GitHubReleases.Http {
        val seen = ArrayList<GitHubReleases.Request>()
        var uploadCode = 201
        var codeRepo: Map<String, Any?>? = null
        /** What the write probe on options-lab answers (403: the token cannot write there; 422: it can). */
        var probeCode = 403
        val assets = ArrayList<Map<String, Any?>>()
        var releaseMade = false
        /** False: the token no longer sees the data repository (GitHub then answers 404). */
        var seesRepo = true
        /** Called during each upload, before GitHub answers (a test moves the clock here: the upload takes time). */
        var onUpload: ((GitHubReleases.Request) -> Unit)? = null

        private fun json(code: Int, v: Any?) = GitHubReleases.Response(code, emptyMap(), Json.write(v).toByteArray())

        override fun send(r: GitHubReleases.Request): GitHubReleases.Response {
            seen += r
            val u = r.url
            return when {
                u == "https://api.github.com/user" -> json(200, mapOf("login" to "boss"))
                u == "https://api.github.com/repos/mevishalsonawane-ai/IraAlgo" ->
                    if (seesRepo) json(200, mapOf("id" to 1, "private" to true)) else json(404, mapOf("message" to "Not Found"))
                u == "https://api.github.com/repos/mevishalsonawane-ai/options-lab" -> codeRepo?.let { json(200, it) } ?: json(404, mapOf("message" to "Not Found"))
                r.method == "POST" && u == "https://api.github.com/repos/mevishalsonawane-ai/options-lab/git/refs" ->
                    json(probeCode, mapOf("message" to when (probeCode) {
                        422 -> "Object does not exist"; 404 -> "Not Found"; 429 -> "Too many requests"
                        else -> "Resource not accessible by personal access token"
                    }))
                u.endsWith("/releases/tags/phone-backup") -> if (releaseMade) json(200, mapOf("id" to 7)) else json(404, mapOf("message" to "Not Found"))
                r.method == "POST" && u.endsWith("/repos/mevishalsonawane-ai/IraAlgo/releases") -> { releaseMade = true; json(201, mapOf("id" to 7)) }
                u.contains("/releases/7/assets?per_page") -> json(200, assets)
                u.startsWith("https://uploads.github.com/repos/mevishalsonawane-ai/IraAlgo/releases/7/assets") ->
                    run {
                        onUpload?.invoke(r)
                        if (uploadCode != 201) json(uploadCode, mapOf("message" to "Bad credentials"))
                        else {
                            val a = mapOf("id" to 100 + assets.size, "name" to u.substringAfter("name="), "size" to (r.body?.length ?: 0))
                            assets += a; json(201, a)
                        }
                    }
                else -> json(404, mapOf("message" to "Not Found"))
            }
        }
    }

    @Before fun setUp() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        ProblemsHost.clear(GitHubSync.PROBLEM); ProblemsHost.clear(GitHubSync.PROBLEM_TOKEN)
        GitHubSync.init(context)
        GitHubSync.http = gh
        Background.at(Background.WED, 18, 0)
    }

    @After fun tearDown() {
        GitHubSync.http = HttpsGitHub
        Market.testClock = null
    }

    private fun ready() = runBlocking {
        GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray())
        GitHubSync.setPassphrase("Test-Passphrase-42!".toCharArray())
        assertTrue(GitHubSync.ready())
        gh.seen.clear()
    }

    // ---- the clock checked again during a run (13 Oct review of G4) ------------------------------------------------------

    /** Two recorded days waiting (Mon and Tue before [Background.WED]); the names their uploads carry. */
    private fun twoDaysRecorded(): List<String> {
        LiveRecorder.attachForTest(context)
        val root = LiveRecorder.rootForTest()!!
        return listOf(Background.WED.minusDays(2), Background.WED.minusDays(1)).map { d ->
            java.io.File(root, d.toString()).apply { mkdirs() }.resolve("market.gz").writeBytes(ByteArray(2048) { it.toByte() })
            CloudSync.liveAsset(d)
        }
    }

    private fun sent(): List<String> = gh.seen.filter { it.url.startsWith("https://uploads.github.com/") }.map { it.url.substringAfter("name=") }

    @Test fun anAutomaticRunStartsNothingNewOnceItsWindowEnds() {
        ready()
        val (mon, tue) = twoDaysRecorded()
        try {
            // A missed evening caught up at 07:59 on a trading day; the clock passes 08:00 while Monday's file uploads.
            Background.at(Background.WED, 7, 59)
            gh.onUpload = { Background.at(Background.WED, 8, 0) }
            assertTrue(runBlocking { GitHubSync.run(withBackup = true, withDays = true, eveningFrom = 23 * 60 + 30) })
            val names = sent()
            assertEquals(names.toString(), 1, names.count { it.startsWith(mon) })
            assertTrue("Tuesday waits for the next evening: $names", names.none { it.startsWith(tue) })
            assertTrue("no backup after 08:00: $names", names.none { it.startsWith("phone-backup") })
        } finally { gh.onUpload = null; LiveRecorder.detachForTest() }
    }

    @Test fun aTapStartsNoDayFileOnceTheMarketOpensButItsBackupGoes() {
        ready()
        val (mon, tue) = twoDaysRecorded()
        try {
            // Boss's tap at 08:59; the clock passes 09:00 while Monday's file uploads: no Tuesday, the backup still goes.
            Background.at(Background.WED, 8, 59)
            gh.onUpload = { Background.at(Background.WED, 9, 0) }
            assertTrue(runBlocking { GitHubSync.run(withBackup = true, withDays = true, manual = true) })
            val names = sent()
            assertEquals(names.toString(), 1, names.count { it.startsWith(mon) })
            assertTrue(names.toString(), names.none { it.startsWith(tue) })
            assertEquals(names.toString(), 1, names.count { it.startsWith("phone-backup") })
        } finally { gh.onUpload = null; LiveRecorder.detachForTest() }
    }

    @Test fun anEveningRunSendsBothDaysAndTheBackup() {
        ready()
        val (mon, tue) = twoDaysRecorded()
        try {
            Background.at(Background.WED, 23, 30)
            assertTrue(runBlocking { GitHubSync.run(withBackup = true, withDays = true, eveningFrom = 23 * 60 + 30) })
            val names = sent()
            assertTrue(names.toString(), names.any { it.startsWith(mon) } && names.any { it.startsWith(tue) })
            assertTrue(names.toString(), names.any { it.startsWith("phone-backup") })
        } finally { LiveRecorder.detachForTest() }
    }

    // ---- G3: the day's diagnostics ------------------------------------------------------------------------------------

    @Test fun theEveningRunSendsTheDaysDiagnosticsRedactedAndReplacesThemOnARerun() {
        ready()
        Background.at(Background.WED, 23, 40)
        val bodies = ArrayList<ByteArray>()
        gh.onUpload = { r -> if (r.url.contains("name=diag-")) bodies += java.io.ByteArrayOutputStream().also { o -> r.body!!.write(o) }.toByteArray() }
        try {
            assertTrue(runBlocking { GitHubSync.run(withBackup = false, withDays = false, eveningFrom = 23 * 60 + 30, diagDay = Background.WED) })
        } finally { gh.onUpload = null }
        val name = "diag-${Background.WED}.json.gz"
        assertEquals(listOf(name), sent())
        assertEquals(Background.WED, GitHubSync.status().lastDiagDay)
        assertTrue(GitHubSync.diagLine(), GitHubSync.diagLine().contains("diagnostics ${Background.WED}"))
        // The file: gzip JSON of that day, with no token, PIN or passphrase in it.
        val raw = String(java.util.zip.GZIPInputStream(bodies.single().inputStream()).readBytes(), Charsets.UTF_8)
        val o = Json.parse(raw) as Map<*, *>
        assertEquals("iraalgo-diagnostics", o["kind"])
        assertEquals(Background.WED.toString(), o["day"])
        for (secret in listOf(token, "github_pat_", pin, "Test-Passphrase-42!")) assertFalse(secret, raw.contains(secret))
        // Only the data repository was written.
        assertTrue(gh.seen.filter { it.method != "GET" }.joinToString { it.url }, gh.seen.filter { it.method != "GET" }.all { "IraAlgo" in it.url })
        // A re-run for an older day never moves the card's day back.
        assertTrue(runBlocking { GitHubSync.run(withBackup = false, withDays = false, eveningFrom = 23 * 60 + 30, diagDay = Background.WED.minusDays(1)) })
        assertEquals(Background.WED, GitHubSync.status().lastDiagDay)
    }

    @Test fun aFailedDiagnosticsUploadShowsAndKeepsTheLastDay() {
        ready()
        Background.at(Background.WED, 23, 40)
        gh.uploadCode = 500
        assertFalse(runBlocking { GitHubSync.run(withBackup = false, withDays = false, eveningFrom = 23 * 60 + 30, diagDay = Background.WED) })
        assertNull(GitHubSync.status().lastDiagDay)
        val last = GitHubSync.status().lastTry!!
        assertFalse(last.ok)
        assertTrue(last.what, last.what.startsWith("diagnostics"))
        assertFalse("a server error is not a refused token", GitHubSync.authBlocked())
    }

    @Test fun noDiagnosticsDayNoDiagnosticsUpload() {
        ready()
        Background.at(Background.WED, 23, 40)
        assertTrue(runBlocking { GitHubSync.run(withBackup = true, withDays = false, eveningFrom = 23 * 60 + 30) })
        assertTrue(sent().toString(), sent().none { it.startsWith("diag-") })
    }

    // ---- the data repository ------------------------------------------------------------------------------------------

    @Test fun theTokenIsSavedForTheDataRepository() {
        ready()
        assertEquals("mevishalsonawane-ai" to "IraAlgo", GitHubSync.repo())
        assertTrue(GitHubSync.workerCopyOk())
        assertNull("the PIN-less copy is gone", SecurePrefs.getString("github.token.dev"))
        for (k in listOf("github.token.pin", "github.token.worker")) assertFalse(k, SecurePrefs.getString(k)!!.contains(token))
        assertTrue(GitHubSync.diagLine().contains("to mevishalsonawane-ai/IraAlgo"))
    }

    @Test fun optionsLabIsRefusedAsTheUploadRepository() = runBlocking {
        for (repo in listOf("mevishalsonawane-ai/options-lab", "mevishalsonawane-ai/Options-Lab", "options-lab")) {
            try { GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray(), repo); fail(repo) }
            catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains(token)) }
        }
        assertTrue("nothing was sent to GitHub", gh.seen.isEmpty())
        assertFalse(GitHubSync.tokenSet)
        // A repository set to options-lab by any other way is never used: not ready, and the worker's copy does not open.
        ready()
        SecurePrefs.put("github.repo", "mevishalsonawane-ai/options-lab")
        assertNull(GitHubSync.repo())
        assertFalse(GitHubSync.ready())
        assertFalse(GitHubSync.workerCopyOk())
        assertFalse(GitHubSync.run(withBackup = true, withDays = true, manual = true))
        assertTrue(gh.seen.isEmpty())
    }

    @Test fun aTokenThatReachesOptionsLabIsRefused() = runBlocking {
        gh.codeRepo = mapOf("private" to true)
        try { GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray()); fail() }
        catch (e: IOException) { assertTrue(e.message, e.message!!.contains("options-lab")); assertFalse(e.message!!.contains(token)) }
        assertFalse(GitHubSync.tokenSet)
    }

    /** Boss's token (13 Oct): IraAlgo only. options-lab is public and Boss owns it, so GitHub's GET says admin: still accepted. */
    @Test fun anIraAlgoOnlyTokenIsAcceptedThoughBossOwnsOptionsLab() = runBlocking {
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("admin" to true, "push" to true, "pull" to true))
        gh.probeCode = 403
        assertEquals("boss", GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray()))
        assertTrue(GitHubSync.tokenSet)
        assertTrue(gh.seen.any { it.method == "POST" && it.url.endsWith("/options-lab/git/refs") })
        // A token that can write there (the probe gets past GitHub's access check) is refused.
        gh.probeCode = 422
        try { GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray()); fail() }
        catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("can also reach options-lab"))
            // GitHub's answer that decided is shown, so an unexpected refusal can be diagnosed; never the token.
            assertTrue(e.message, e.message!!.contains("(GitHub said: probe 422: Object does not exist)"))
            assertFalse(e.message!!.contains(token))
        }
    }

    // ---- the options-lab check at each run (13 Oct review) --------------------------------------------------------------

    @Test fun bossesTokenUploadsNormallyAtARun() = runBlocking {
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("admin" to true, "push" to true, "pull" to true))
        gh.probeCode = 403
        ready()
        assertTrue(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue(gh.seen.any { it.url.startsWith("https://uploads.github.com/") })
        assertFalse(GitHubSync.authBlocked())
    }

    @Test fun aProbeThatGetsPastTheAccessCheckPausesTheRunsAsRefused() = runBlocking {
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("admin" to true))
        gh.probeCode = 403
        ready()
        gh.probeCode = 422                                   // its access was widened on GitHub after it was pasted
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue(gh.seen.none { it.url.startsWith("https://uploads.github.com/") })
        assertTrue(GitHubSync.authBlocked())
        assertNull(SecurePrefs.getString("backup.last"))
    }

    @Test fun aCheckThatFailsSkipsTheRunWithoutRefusingTheToken() = runBlocking {
        gh.codeRepo = mapOf("private" to false, "permissions" to mapOf("admin" to true))
        gh.probeCode = 403
        ready()
        gh.probeCode = 429                                   // rate limited: no answer
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue("nothing is sent without an answer", gh.seen.none { it.url.startsWith("https://uploads.github.com/") })
        assertFalse("not a refused token: the automatic runs go on", GitHubSync.authBlocked())
        assertNull(SecurePrefs.getString("backup.last"))
        assertTrue(Diag.lines().toString(), Diag.lines().any { it.contains("upload skipped this time") && !it.contains(token) })
        // The next run, with GitHub answering again, uploads.
        gh.probeCode = 403
        assertTrue(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue(gh.seen.any { it.url.startsWith("https://uploads.github.com/") })
    }

    @Test fun theWorkersCopyOpensOnlyForItsRepository() {
        ready()
        SecurePrefs.put("github.repo", "mevishalsonawane-ai/another-data")
        assertFalse("bound to IraAlgo", GitHubSync.workerCopyOk())
    }

    @Test fun anOldOptionsLabTokenAndThePinLessCopyAreForgotten() {
        SecurePrefs.putAll(mapOf("github.token.pin" to "v2:old", "github.token.dev" to "k1:00:00", "github.key.live" to "1000:00:00",
            "github.key.backup" to "00:00"))
        GitHubSync.check()
        assertNull(SecurePrefs.getString("github.token.dev"))
        assertNull(SecurePrefs.getString("github.token.pin"))
        assertTrue(ProblemsHost.open().any { it.text.contains("never options-lab") })
        // It stands at the next check too, until a new token is pasted.
        GitHubSync.check()
        assertTrue(ProblemsHost.open().any { it.text.contains("never options-lab") })
        ready()
        GitHubSync.check()
        assertFalse(ProblemsHost.open().any { it.text.contains("never options-lab") })
    }

    @Test fun aTokenThatLostItsAccessIsTakenAsRefused() = runBlocking {
        ready()
        gh.seesRepo = false
        gh.uploadCode = 404
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue("a 404 from a repository the token no longer sees is a refused token", GitHubSync.authBlocked())
    }

    @Test fun aTokenWidenedToOptionsLabLaterIsStoppedAtTheNextRun() = runBlocking {
        ready()
        gh.codeRepo = mapOf("private" to true)
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue(gh.seen.none { it.url.startsWith("https://uploads.github.com/") })
        assertTrue(GitHubSync.authBlocked())
        assertNull(SecurePrefs.getString("backup.last"))
    }

    // ---- restore needs the PIN ----------------------------------------------------------------------------------------

    @Test fun restoreFromGitHubOpensWithThePinOnly() = runBlocking {
        ready()
        try { GitHubSync.openRestore("000000".toCharArray()); fail() } catch (e: IOException) { assertFalse(e.message!!.contains(token)) }
        val r = GitHubSync.openRestore(pin.toCharArray())
        assertEquals(emptyList<GitHubSync.Saved>(), r.list)
    }

    // ---- the backup reminder ------------------------------------------------------------------------------------------

    @Test fun aFailedUploadLeavesBackupLastUnset() = runBlocking {
        ready()
        gh.uploadCode = 500
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertNull("a failed upload must not silence the backup reminder", SecurePrefs.getString("backup.last"))
        gh.uploadCode = 201
        assertTrue(GitHubSync.run(withBackup = true, withDays = false))
        assertEquals(Background.WED.toString(), SecurePrefs.getString("backup.last"))
    }

    @Test fun aRefusedTokenPausesTheAutomaticRunsAndSaysSo() = runBlocking {
        ready()
        gh.uploadCode = 401
        assertFalse(GitHubSync.run(withBackup = true, withDays = false))
        assertTrue(GitHubSync.authBlocked())
        assertNull(SecurePrefs.getString("backup.last"))
        assertTrue(ProblemsHost.open().any { it.text.startsWith("GitHub token invalid") })
        // An automatic run builds no backup and sends nothing.
        gh.seen.clear()
        gh.uploadCode = 201
        assertFalse(GitHubSync.run(withBackup = true, withDays = true))
        assertTrue(gh.seen.isEmpty())
        // Boss's tap tries again; a new token ends the pause.
        assertTrue(GitHubSync.run(withBackup = true, withDays = false, manual = true))
        gh.uploadCode = 403
        assertFalse(GitHubSync.run(withBackup = true, withDays = false, manual = true))
        assertTrue(GitHubSync.authBlocked())
        GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), pin.toCharArray())
        assertFalse(GitHubSync.authBlocked())
    }

    // ---- HttpsGitHub --------------------------------------------------------------------------------------------------

    private fun req(url: String, auth: Boolean = true) =
        GitHubReleases.Request("GET", url, if (auth) mapOf("Authorization" to "Bearer $token") else emptyMap())

    @Test fun httpsGitHubRefusesNonHttpsAndKeepsTheTokenToGitHubsApi() {
        for (url in listOf("http://api.github.com/user", "ftp://api.github.com/user", "file:///etc/hosts", "not a url",
            "https://evil.example.com/user", "https://api.github.com.evil.example.com/user", "https://objects.githubusercontent.com/x")) {
            try { HttpsGitHub.send(req(url)); fail(url) } catch (e: IOException) {
                assertFalse(url, e.message!!.contains(token))
                assertFalse(url, e.message!!.contains("evil"))
            }
        }
        assertNull(HttpsGitHub.refused(req("https://api.github.com/user")))
        assertNull(HttpsGitHub.refused(req("https://uploads.github.com/repos/a/b/releases/1/assets?name=x")))
        assertNull("a download from the file store goes without the token", HttpsGitHub.refused(req("https://objects.githubusercontent.com/x", auth = false)))
        assertNotNull(HttpsGitHub.refused(req("http://objects.githubusercontent.com/x", auth = false)))
    }

    @Test fun httpsGitHubCapsTheAnswer() {
        val b = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(b, HttpsGitHub.readCapped(ByteArrayInputStream(b), 200_000))
        try { HttpsGitHub.readCapped(ByteArrayInputStream(b), 199_999); fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("bigger than expected"))
        }
        assertEquals(0, HttpsGitHub.readCapped(ByteArrayInputStream(ByteArray(0)), 0).size)
    }

    @Test fun githubsOwnErrorTextNeverCarriesTheToken() {
        val echo = GitHubReleases.Http { r ->
            GitHubReleases.Response(401, emptyMap(), Json.write(mapOf("message" to "Bad credentials for ${r.headers["Authorization"]}")).toByteArray())
        }
        try { GitHubReleases(echo, token).user(); fail() } catch (e: GitHubReleases.Failure) {
            assertFalse(e.message, e.message!!.contains(token))
            assertEquals(401, e.code)
        }
    }

    @Test fun theCardsLinesNameTheDataRepository() {
        ready()
        val lines = GitHubSync.card(GitHubSync.status()).toMap()
        assertEquals("mevishalsonawane-ai/IraAlgo", lines["Uploads go to"])
        assertTrue(CloudSync.DEFAULT_REPO.endsWith("IraAlgo"))
    }
}
