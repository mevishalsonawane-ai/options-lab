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
 * GitHub sync's worker after the 13 Oct review: what each kind does, the 15:45 gate for every automatic kind (the backup
 * after a strategy or Pine change included), charging for all but Boss's "Upload now", the retry limit, and no retry and no
 * backup built while GitHub refuses the token. A fake GitHub; the token is a made-up test value.
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

    private fun uploads() = gh.seen.count { it.url.startsWith("https://uploads.github.com/") }

    @Test fun notSetUpOrACheckSendsNothing() {
        for (k in listOf(GitHubSyncWorker.EVENING, GitHubSyncWorker.BACKUP, GitHubSyncWorker.NOW, GitHubSyncWorker.CHECK))
            assertTrue(k, run(k) is ListenableWorker.Result.Success)
        ready()
        assertTrue(run(GitHubSyncWorker.CHECK) is ListenableWorker.Result.Success)
        assertTrue(run("something else") is ListenableWorker.Result.Success)
        assertTrue("no network for a check", gh.seen.isEmpty())
    }

    @Test fun theEveningRunOnlyFrom1545AndOnceADay() {
        ready()
        Background.at(WED, 15, 44)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals(0, uploads())
        Background.at(WED, 15, 45)
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("the backup went up", 1, uploads())
        assertEquals(WED.toString(), SecurePrefs.getString("backup.last"))
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)
        assertEquals("done for today", 1, uploads())
    }

    @Test fun theBackupAfterAChangeWaitsFor1545() {
        ready()
        Background.at(WED, 11, 0)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertEquals(0, uploads())
        Background.at(WED, 16, 0)
        assertTrue(run(GitHubSyncWorker.BACKUP) is ListenableWorker.Result.Success)
        assertEquals(1, uploads())
        // Boss's tap: any hour.
        Background.at(WED, 11, 0)
        assertTrue(run(GitHubSyncWorker.NOW) is ListenableWorker.Result.Success)
        assertEquals(2, uploads())
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
        assertTrue(run(GitHubSyncWorker.EVENING) is ListenableWorker.Result.Success)   // no retry: the same token is refused
        assertTrue(GitHubSync.authBlocked())
        gh.seen.clear()
        gh.uploadCode = 201
        Background.at(WED.plusDays(1), 18, 0)
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
        assertEquals("waits for 15:45", TimeUnit.MINUTES.toMillis(CloudSync.EVENING - 11 * 60L), b.initialDelayMillis)
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
