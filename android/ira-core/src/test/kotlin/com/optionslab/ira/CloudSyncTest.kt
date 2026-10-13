package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSyncTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val before = 15 * 60 + 30
    private val after = 15 * 60 + 50
    private fun d(day: Int, month: Int = 10) = LocalDate.of(2026, month, day)

    // ---- names --------------------------------------------------------------------------------------------------------

    @Test fun namesAndTags() {
        assertEquals("live-2026-10", CloudSync.liveTag(today))
        assertEquals("live-2026-10-13.irlive", CloudSync.liveAsset(today))
        assertEquals(today, CloudSync.liveDay("live-2026-10-13.irlive"))
        val at = LocalDateTime.of(2026, 10, 13, 18, 5, 9)
        assertEquals("phone-backup-2026-10-13-180509.irabk", CloudSync.backupAsset(at))
        assertEquals(at, CloudSync.backupTime(CloudSync.backupAsset(at)))
        assertNull(CloudSync.backupTime("notes.txt"))
        assertNull(CloudSync.backupTime("phone-backup-2026-13-40-000000.irabk"))
    }

    // ---- when ---------------------------------------------------------------------------------------------------------

    @Test fun theEveningRunIsDueOnceAfter1545() {
        assertFalse(CloudSync.eveningDue(before, null, today))
        assertTrue(CloudSync.eveningDue(15 * 60 + 45, null, today))
        assertTrue(CloudSync.eveningDue(after, today.minusDays(1), today))
        assertFalse(CloudSync.eveningDue(after, today, today))
    }

    @Test fun bigFilesNeverGoInTradingHours() {
        assertFalse(CloudSync.liveAllowed(9 * 60, tradingDay = true))
        assertFalse(CloudSync.liveAllowed(15 * 60 + 44, tradingDay = true))
        assertTrue(CloudSync.liveAllowed(15 * 60 + 45, tradingDay = true))
        assertTrue(CloudSync.liveAllowed(8 * 60 + 59, tradingDay = true))
        assertTrue(CloudSync.liveAllowed(11 * 60, tradingDay = false))
    }

    @Test fun waitingDaysOldestFirstTodayOnlyAfterTheClose() {
        val rec = mapOf(d(9) to 10L, d(10) to 20L, d(12) to 30L, d(13) to 40L)
        assertEquals(listOf(d(9), d(10), d(12)), CloudSync.pendingDays(rec, emptyMap(), today, before))
        assertEquals(listOf(d(9), d(10), d(12), d(13)), CloudSync.pendingDays(rec, emptyMap(), today, after))
        assertEquals(listOf(d(9), d(10)), CloudSync.pendingDays(rec, emptyMap(), today, after, max = 2))
        assertEquals(4, CloudSync.daysWaiting(rec, emptyMap(), today, after))
    }

    @Test fun aDayThatGrewGoesAgainOneThatShrankNever() {
        val rec = mapOf(d(1) to 50L, d(9) to 120L, d(12) to 30L)
        // d(1)'s raw ticks were deleted on the phone (it shrank): the bigger copy on GitHub stays. d(9) had a late fill.
        val up = mapOf(d(1) to 900L, d(9) to 100L, d(12) to 30L)
        assertEquals(listOf(d(9)), CloudSync.pendingDays(rec, up, today, after))
        // An empty folder is nothing to send.
        assertEquals(emptyList(), CloudSync.pendingDays(mapOf(d(11) to 0L), emptyMap(), today, after))
    }

    @Test fun uploadedDaysRoundTripAndStaySmall() {
        val m = (1..150).associate { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) to it * 10L }
        val back = CloudSync.doneDecode(CloudSync.doneEncode(m))
        assertEquals(120, back.size)
        assertEquals(1500L, back[LocalDate.of(2026, 1, 1).plusDays(150)])
        assertEquals(emptyMap(), CloudSync.doneDecode(null))
        assertEquals(mapOf(d(1) to 5L), CloudSync.doneDecode("garbage,2026-10-01=5,=,x=y"))
    }

    // ---- retention ----------------------------------------------------------------------------------------------------

    @Test fun keepsThirtyDailyAndOneAMonth() {
        // A backup every evening from 1 June to 13 Oct, plus two extra (strategy changes) on 13 Oct and 12 Oct.
        val names = ArrayList<String>()
        var day = LocalDate.of(2026, 6, 1)
        while (!day.isAfter(today)) { names += CloudSync.backupAsset(day.atTime(18, 0)); day = day.plusDays(1) }
        names += CloudSync.backupAsset(today.atTime(11, 2))
        names += CloudSync.backupAsset(d(12).atTime(20, 30))
        names += "notes.txt"
        val r = CloudSync.retention(names)
        val kept = r.keep.mapNotNull { CloudSync.backupTime(it) }
        // 30 days (14 Sep .. 13 Oct), newest of each day.
        val days = kept.map { it.toLocalDate() }.toSet()
        for (i in 0L until 30) assertTrue(today.minusDays(i) in days, "${today.minusDays(i)}")
        assertTrue(LocalDateTime.of(2026, 10, 12, 20, 30) in kept)
        assertFalse(LocalDateTime.of(2026, 10, 12, 18, 0) in kept)
        assertTrue(today.atTime(18, 0) in kept)
        assertFalse(today.atTime(11, 2) in kept)
        // Before the 30 days: one a month, its newest (13 Sep for September, 31 Aug, 31 Jul, 30 Jun).
        val older = kept.filter { it.toLocalDate().isBefore(today.minusDays(29)) }.map { it.toLocalDate() }.sorted()
        assertEquals(listOf(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 13)), older)
        assertEquals(34, kept.size)
        // Never a name that is not ours.
        assertTrue("notes.txt" in r.keep)
        assertFalse("notes.txt" in r.delete)
        assertEquals(names.size, r.keep.size + r.delete.size)
    }

    @Test fun fewBackupsAreAllKept() {
        val names = (1..5).map { CloudSync.backupAsset(d(it).atTime(18, 0)) }
        assertEquals(emptyList(), CloudSync.retention(names).delete)
        assertEquals(emptyList(), CloudSync.retention(emptyList()).delete)
    }

    // ---- the token ----------------------------------------------------------------------------------------------------

    private val fakeToken = "github_pat_" + "11ABCDEFG0" + "a".repeat(70)

    @Test fun onlyFineGrainedTokensAreTaken() {
        assertTrue(CloudSync.tokenLooksRight(fakeToken))
        assertTrue(CloudSync.tokenLooksRight("  $fakeToken\n"))
        assertFalse(CloudSync.tokenLooksRight("ghp_" + "a".repeat(36)))
        assertFalse(CloudSync.tokenLooksRight("github_pat_short"))
        assertFalse(CloudSync.tokenLooksRight("github_pat_" + "a".repeat(30) + " space"))
        assertFalse(CloudSync.tokenLooksRight(""))
    }

    @Test fun tokensAreRedactedEverywhere() {
        val classic = "ghp_" + "Z".repeat(36)
        val text = "upload failed: Authorization: Bearer $fakeToken; also token=$classic and $fakeToken at the end"
        val r = CloudSync.redact(text)
        assertFalse(r.contains("github_pat_11"), r)
        assertFalse(r.contains("ZZZZZZZZ"), r)
        assertFalse(r.contains("aaaaaaaa"), r)
        assertTrue(r.contains(Secrets.HIDDEN))
        assertTrue(r.startsWith("upload failed:"))
        // Ordinary words and numbers stay.
        assertEquals("401: the token was refused (expired or revoked?)", CloudSync.redact("401: the token was refused (expired or revoked?)"))
        assertEquals("live 2026-10-13 · 4.2 MB", CloudSync.redact("live 2026-10-13 · 4.2 MB"))
    }

    @Test fun theExpiryAndItsReminder() {
        assertEquals(d(20, 1), CloudSync.expiryHeader("2026-01-20 10:20:30 UTC"))
        assertNull(CloudSync.expiryHeader(null))
        assertNull(CloudSync.expiryHeader("soon"))
        assertEquals(d(5, 1), CloudSync.expiryKept(d(10, 1), d(5, 1)))
        assertEquals(d(10, 1), CloudSync.expiryKept(d(10, 1), null))
        assertNull(CloudSync.expiryKept(null, null))
        assertNull(CloudSync.expiryNote(today.plusDays(8), today))
        assertNotNull(CloudSync.expiryNote(today.plusDays(7), today)).let { assertTrue(it.contains("in 7 days"), it) }
        assertNotNull(CloudSync.expiryNote(today.plusDays(1), today)).let { assertTrue(it.contains("in 1 day "), it) }
        assertNotNull(CloudSync.expiryNote(today, today)).let { assertTrue(it.contains("today"), it) }
        assertNotNull(CloudSync.expiryNote(today.minusDays(1), today)).let { assertTrue(it.contains("expired"), it) }
        assertNull(CloudSync.expiryNote(null, today))
    }

    // ---- card and problems --------------------------------------------------------------------------------------------

    private val zone = java.time.ZoneOffset.ofHoursMinutes(5, 30)
    private fun ms(day: LocalDate, h: Int = 18) = day.atTime(h, 0).toInstant(zone).toEpochMilli()
    private val toDay: (Long) -> LocalDate = { java.time.Instant.ofEpochMilli(it).atOffset(zone).toLocalDate() }
    private fun status(lastTry: CloudSync.Last? = null, lastOk: CloudSync.Last? = lastTry?.takeIf { it.ok }, waiting: Int = 0,
                       token: Boolean = true, key: Boolean = true, expiry: LocalDate? = d(10, 12)) =
        CloudSync.Status(token, key, expiry, lastTry, lastOk, lastOk?.takeIf { it.what == "backup" }, waiting)

    @Test fun theCardSaysDateSizeAndTick() {
        val ok = CloudSync.Last(ms(today), true, 4_200_000, "live 2026-10-13")
        val lines = CloudSync.card(status(ok, waiting = 1), toDay, today).toMap()
        assertEquals("13 Oct · 4.2 MB · ✓", lines["Last upload"])
        assertEquals("1", lines["Days waiting"])
        assertEquals("saved · expires 10 Dec", lines["GitHub token"])
        val bad = CloudSync.Last(ms(today), false, 0, "backup", "401: the token was refused")
        val l2 = CloudSync.card(status(bad, lastOk = ok), toDay, today).toMap()
        assertEquals("13 Oct · ✗ 401: the token was refused", l2["Last upload"])
        assertEquals("13 Oct · 4.2 MB · ✓", l2["Last that worked"])
        assertEquals("none yet", CloudSync.card(status(), toDay, today).toMap()["Last upload"])
        assertEquals("not set", CloudSync.card(status(token = false), toDay, today).toMap()["GitHub token"])
        val soon = CloudSync.card(status(expiry = today.plusDays(3)), toDay, today).toMap()
        assertTrue(soon.getValue("Token").contains("in 3 days"))
    }

    @Test fun problemsFailuresAndTwoQuietDays() {
        assertNull(CloudSync.problem(status(token = false), today, toDay))
        assertNotNull(CloudSync.problem(status(key = false), today, toDay))
        val ok = CloudSync.Last(ms(today.minusDays(1)), true, 10, "backup")
        assertNull(CloudSync.problem(status(ok), today, toDay))
        val failed = CloudSync.Last(ms(today), false, 0, "live 2026-10-13", "no connection to GitHub")
        assertNotNull(CloudSync.problem(status(failed, lastOk = ok), today, toDay)).let { assertTrue(it.contains("no connection"), it) }
        val old = CloudSync.Last(ms(today.minusDays(2)), true, 10, "backup")
        assertNotNull(CloudSync.problem(status(old, waiting = 2), today, toDay)).let { assertTrue(it.contains("since 11 Oct") && it.contains("2 recorded days"), it) }
        assertNull(CloudSync.problem(status(waiting = 1), today, toDay))
        assertNotNull(CloudSync.problem(status(waiting = 2), today, toDay))
    }

    @Test fun lastRoundTripsWithoutBreakingOnBars() {
        val l = CloudSync.Last(123L, false, 9L, "live|x", "a|b")
        val back = assertNotNull(CloudSync.Last.decode(l.encode()))
        assertEquals(CloudSync.Last(123L, false, 9L, "live/x", "a/b"), back)
        assertEquals(CloudSync.Last(1L, true, 2L, "backup"), CloudSync.Last.decode(CloudSync.Last(1L, true, 2L, "backup").encode()))
        assertNull(CloudSync.Last.decode("nope"))
    }
    // ---- 13 Oct review: the data repository, the refused token, the worker's plan ----------------------------------------

    @Test fun uploadsNeverGoToOptionsLab() {
        assertEquals("mevishalsonawane-ai/IraAlgo", CloudSync.DEFAULT_REPO)
        assertNull(CloudSync.repoRefused(CloudSync.OWNER, CloudSync.DATA_REPO))
        for (r in listOf("options-lab", "Options-Lab", "OPTIONS-LAB"))
            assertNotNull(CloudSync.repoRefused(CloudSync.OWNER, r)).let { assertTrue(it.contains("never"), it) }
        assertNotNull(CloudSync.repoRefused("someone-else", "options-lab"))
        assertNotNull(CloudSync.repoRefused(CloudSync.OWNER, "options-lab.git"))
        assertNotNull(CloudSync.repoRefused("a/b", "c"))
        assertEquals("mevishalsonawane-ai" to "IraAlgo", CloudSync.parseRepo(" mevishalsonawane-ai/IraAlgo.git "))
        assertNull(CloudSync.parseRepo("IraAlgo"))
        assertNull(CloudSync.parseRepo("a/b/c"))
        assertNull(CloudSync.parseRepo("a/.."))
        assertNull(CloudSync.parseRepo("a/b c"))
        assertNull(CloudSync.parseRepo(null))
    }

    @Test fun aRefusedTokenSaysGitHubTokenInvalidAndPausesTheAutomaticRuns() {
        val ok = CloudSync.Last(ms(today.minusDays(1)), true, 10, "backup")
        val refused = CloudSync.Last(ms(today), false, 0, "backup", "401: the token was refused", 401)
        val p = assertNotNull(CloudSync.problem(status(refused, lastOk = ok), today, toDay))
        assertTrue(p.startsWith("GitHub token invalid"), p)
        assertTrue(CloudSync.authRefused(refused))
        assertTrue(CloudSync.authRefused(refused.copy(code = 403)))
        assertFalse(CloudSync.authRefused(refused.copy(code = 429)))
        assertFalse(CloudSync.authRefused(refused.copy(code = 500)))
        assertFalse(CloudSync.authRefused(refused.copy(ok = true)))
        assertFalse(CloudSync.authRefused(null))
        assertEquals("✗ GitHub token invalid: paste a new one", CloudSync.card(status(refused, lastOk = ok), toDay, today).toMap()["Token"])
        // Another failure keeps the ordinary words.
        val other = refused.copy(code = 500, why = "500: GitHub is having trouble")
        assertFalse(assertNotNull(CloudSync.problem(status(other, lastOk = ok), today, toDay)).contains("token invalid"))
    }

    @Test fun theCodeRoundTripsAndOldRecordsReadAsZero() {
        val l = CloudSync.Last(5L, false, 0L, "backup", "403: refused", 403)
        assertEquals(l, CloudSync.Last.decode(l.encode()))
        assertEquals(0, CloudSync.Last.decode("5|0|0|backup|why")!!.code)
    }

    @Test fun theWorkersPlanForEachKind() {
        val e = CloudSync.EVENING
        fun plan(kind: String, minute: Int, due: Boolean = true, ready: Boolean = true, blocked: Boolean = false) =
            CloudSync.plan(kind, minute, due, ready, blocked)
        // EVENING: from 15:45, once a day.
        assertNull(plan(CloudSync.KIND_EVENING, e - 1))
        assertEquals(CloudSync.Plan(backup = true, days = true), plan(CloudSync.KIND_EVENING, e))
        assertNull(plan(CloudSync.KIND_EVENING, e + 60, due = false))
        // BACKUP (a strategy or Pine change): the backup only, and only from 15:45.
        assertNull(plan(CloudSync.KIND_BACKUP, 10 * 60))
        assertNull(plan(CloudSync.KIND_BACKUP, 30))                  // 00:30 is before 15:45 too
        assertEquals(CloudSync.Plan(backup = true, days = false), plan(CloudSync.KIND_BACKUP, e + 5))
        // NOW (Boss's tap): any hour.
        assertEquals(CloudSync.Plan(backup = true, days = true), plan(CloudSync.KIND_NOW, 10 * 60))
        // CHECK, an unknown kind, or not ready: nothing.
        assertNull(plan(CloudSync.KIND_CHECK, e + 5))
        assertNull(plan("other", e + 5))
        for (k in listOf(CloudSync.KIND_EVENING, CloudSync.KIND_BACKUP, CloudSync.KIND_NOW)) assertNull(plan(k, e + 5, ready = false))
        // A refused token: no automatic run builds a backup; only Boss's tap tries again.
        assertNull(plan(CloudSync.KIND_EVENING, e + 5, blocked = true))
        assertNull(plan(CloudSync.KIND_BACKUP, e + 5, blocked = true))
        assertNotNull(plan(CloudSync.KIND_NOW, e + 5, blocked = true))
    }

    @Test fun theRetryLimitAndNoRetryOnARefusedToken() {
        assertFalse(CloudSync.retry(ok = true, attempt = 0, authFailed = false))
        assertTrue(CloudSync.retry(ok = false, attempt = 0, authFailed = false))
        assertTrue(CloudSync.retry(ok = false, attempt = CloudSync.MAX_TRIES - 2, authFailed = false))
        assertFalse(CloudSync.retry(ok = false, attempt = CloudSync.MAX_TRIES - 1, authFailed = false))
        assertFalse(CloudSync.retry(ok = false, attempt = 0, authFailed = true))
        assertEquals(4, (0 until 10).takeWhile { CloudSync.retry(false, it, false) }.size + 1)   // 4 tries in all
    }

    @Test fun aChangesBackupWaitsForTheEvening() {
        assertEquals(CloudSync.DEBOUNCE_MIN, CloudSync.backupDelayMin(CloudSync.EVENING))
        assertEquals(CloudSync.DEBOUNCE_MIN, CloudSync.backupDelayMin(22 * 60))
        assertEquals(CloudSync.EVENING - 600L, CloudSync.backupDelayMin(600))
        assertEquals(CloudSync.DEBOUNCE_MIN, CloudSync.backupDelayMin(CloudSync.EVENING - 1))   // 1 minute short: the debounce
    }
}
