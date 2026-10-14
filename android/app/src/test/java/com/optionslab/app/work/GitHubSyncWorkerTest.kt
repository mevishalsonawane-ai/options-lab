package com.optionslab.app.work

import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.optionslab.app.data.GitHubSync
import com.optionslab.app.data.GitHubSyncTest
import com.optionslab.app.data.HttpsGitHub
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.CloudSync
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * GitHub sync's worker after the 13 Oct review: what each kind does, the evening gate for every automatic kind (the backup
 * after a strategy or Pine change included; G4, 13 Oct: after MCX's close from the MCX calendar - 23:30 on Wed 15 Oct 2025,
 * US summer time - and 15:45 on a day without MCX; a missed evening the next morning before 08:00, kept as its own evening),
 * charging for all but Boss's "Upload now", the retry limit, and no retry and no backup built while GitHub refuses the
 * token. A fake GitHub; the token is a made-up test value.
 */
class GitHubSyncWorkerTest : RobolectricTest() {
    private val token = "github_pat_" + "TEST" + "z".repeat(60)
    private val gh = GitHubSyncTest.FakeGitHub()

    @Before fun setUp() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        GitHubSync.init(context)
        GitHubSync.http = gh
        Background.at(WED, 18, 0)
    }

    @After fun tearDown() {
        GitHubSync.http = HttpsGitHub
        Market.testClock = null
    }

    private fun ready() = runBlocking {
        GitHubSync.saveToken(token, LocalDate.of(2027, 1, 10), "482913".toCharArray())
        GitHubSync.setPassphrase("Test-Passphrase-42!".toCharArray())
        gh.seen.clear()
    }

    private fun worker(kind: String, attempt: Int = 0) = TestListenableWorkerBuilder.from(context, GitHubSyncWorker::class.java)
        .setInputData(workDataOf(GitHubSyncWorker.KIND to kind)).setRunAttemptCount(attempt).build()

    private fun run(kind: String, attempt: Int = 0) = runBlocking { worker(kind, attempt).doWork() }

    /** The recordings and backups sent (the day's diagnostics, G3, are counted apart: [diags]). */
    private fun uploads() = gh.seen.count { it.url.startsWith("https://uploads.github.com/") && !it.url.contains("name=diag-") }

    /** The days whose diagnostics were sent (G3), in order. */
    private fun diags() = gh.seen.filter { it.url.startsWith("https://uploads.github.com/") && it.url.contains("name=diag-") }
        .map { it.url.substringAfter("name=diag-").removeSuffix(".json.gz") }

    @Test fun notSetUpOrACheckSendsNothing() {
        for (k in listOf(GitHubSyncWorker.EVENING, GitHubSyncWorker.BACKUP, GitHubSyncWorker.NOW, GitHubSyncWorker.CHECK))
            assertTrue(k, run(k) is ListenableWorker.Result.Success)
        ready()
        assertTrue(run(GitHubSyncWorker.CHECK) is ListenableWorker.Result.Success)
        assertTrue(run("something else") is ListenableWorker.Result.Success)
        assertTrue("no network for a check", gh.seen.isEmpty())
    }

    @Test fun theEveningRunOnlyAfterTheMcxCloseAndOnceADay() {
        ready()
        // Wednesday: MCX trades until 23:30, so not at 15:45 any more (G4), nor at 23:29.
        Background.at(WED, 15, 45)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("MCX is still open", 0, uploads())
        Background.at(WED, 23, 29)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(0, uploads())
        Background.at(WED, 23, 30)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("the backup went up", 1, uploads())
        assertEquals(WED.toString(), SecurePrefs.getString("backup.last"))
        assertEquals(WED.toString(), SecurePrefs.getString("github.evening"))
        // G3: the evening's diagnostics went with it, once; the card says the day.
        assertEquals(listOf(WED.toString()), diags())
        assertEquals(WED, GitHubSync.status().lastDiagDay)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("done for today", 1, uploads())
        assertEquals(1, diags().size)
        // After midnight: Wednesday's evening is done, so not again.
        Background.at(WED.plusDays(1), 0, 30)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("not twice", 1, uploads())
    }

    @Test fun aDayWithoutMcxRunsFrom1545() {
        ready()
        val sat = WED.plusDays(3)                       // Saturday: no MCX session
        Background.at(sat, 15, 44)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(0, uploads())
        Background.at(sat, 15, 45)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        assertEquals(sat.toString(), SecurePrefs.getString("github.evening"))
    }

    @Test fun aMissedEveningRunsTheNextMorningOnceAndTheNextDayIsNotSkipped() {
        ready()
        val thu = WED.plusDays(1)
        // Wednesday's 23:30 run was missed (the phone was not charging): Thursday 00:30 it runs, kept as Wednesday's.
        Background.at(thu, 0, 30)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        assertEquals(WED.toString(), SecurePrefs.getString("github.evening"))
        // Its diagnostics are Wednesday's, not Thursday's (G3).
        assertEquals(listOf(WED.toString()), diags())
        Background.at(thu, 1, 30)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("once", 1, uploads())
        // 08:00 to Thursday's close: nothing.
        Background.at(thu, 8, 0)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        Background.at(thu, 16, 0)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        // Thursday's own evening still runs.
        Background.at(thu, 23, 30)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(2, uploads())
        assertEquals(thu.toString(), SecurePrefs.getString("github.evening"))
        assertEquals(listOf(WED.toString(), thu.toString()), diags())
        assertEquals(thu, GitHubSync.status().lastDiagDay)
    }

    @Test fun theBackupAfterAChangeWaitsForTheMcxClose() {
        ready()
        Background.at(WED, 11, 0)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertEquals(0, uploads())
        Background.at(WED, 16, 0)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertEquals("MCX is still open", 0, uploads())
        Background.at(WED, 23, 30)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        assertEquals("a backup after a change sends no diagnostics", 0, diags().size)
        // Boss's tap: any hour, with today's diagnostics so far (the evening run replaces them).
        Background.at(WED, 11, 0)
        assertTrue(run(GitHubSyncWorker.NOW) is ListenableWorker.Result.Success)
        assertEquals(2, uploads())
        assertEquals(listOf(WED.toString()), diags())
    }

    @Test fun aFailureIsTriedFourTimesInAll() {
        ready()
        gh.uploadCode = 500
        for (attempt in 0 until CloudSync.MAX_TRIES - 1)
            assertTrue("try ${attempt + 1}", run(GitHubSyncWorker.NOW, attempt) is ListenableWorker.Result.Retry)
        assertTrue("the last try", run(GitHubSyncWorker.NOW, CloudSync.MAX_TRIES - 1) is ListenableWorker.Result.Success)
        assertNull("no backup reached GitHub: the reminder stays", SecurePrefs.getString("backup.last"))
    }

    @Test fun aRefusedTokenIsNotRetriedAndPausesTheAutomaticKinds() {
        ready()
        gh.uploadCode = 401
        Background.at(WED, 23, 40)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)   // no retry: the same token is refused
        assertTrue(GitHubSync.authBlocked())
        gh.seen.clear()
        gh.uploadCode = 201
        Background.at(WED.plusDays(1), 23, 40)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertTrue("nothing built or sent", gh.seen.isEmpty())
        assertNull(SecurePrefs.getString("backup.last"))
        // Boss's tap tries again.
        assertTrue(run(GitHubSyncWorker.NOW) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        assertFalse(GitHubSync.authBlocked())
    }

    @Test fun everyAutomaticKindNeedsChargingAndWifiUploadNowOnlyWifi() {
        val wm = Background.workManager(context)
        Background.at(WED, 11, 0)
        GitHubSyncWorker.backupSoon(context)
        val b = Background.unique(context, "github.sync.backup").single()
        assertTrue(b.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, b.constraints.requiredNetworkType)
        assertEquals("waits for MCX's 23:30 close", TimeUnit.MINUTES.toMillis(23 * 60 + 30 - 11 * 60L), b.initialDelayMillis)
        GitHubSyncWorker.now(context)
        val n = Background.unique(context, "github.sync.now").single()
        assertFalse(n.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, n.constraints.requiredNetworkType)
        GitHubSyncWorker.schedule(context)
        val e = Background.unique(context, "github.sync.evening").single()
        assertTrue(e.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, e.constraints.requiredNetworkType)
        wm.cancelAllWork()
    }
}
