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

    // ---- G3: the day's diagnostics ------------------------------------------------------------------------------------

    @Test fun theDiagnosticsLineSaysTheLastDayUploaded() {
        assertEquals("last day uploaded: none yet", CloudSync.card(status(), toDay, today).toMap()["Diagnostics"])
        val s = status().copy(lastDiagDay = d(12))
        assertEquals("last day uploaded: 12 Oct ✓", CloudSync.card(s, toDay, today).toMap()["Diagnostics"])
        // It follows the backup line on the card.
        val keys = CloudSync.card(s, toDay, today).map { it.first }
        assertEquals(keys.indexOf("Last backup to GitHub") + 1, keys.indexOf("Diagnostics"))
    }

    @Test fun whichDaysDiagnosticsARunUploads() {
        val gate = 23 * 60 + 30
        // The evening run: its own evening, also when it runs as the next morning's catch-up.
        assertEquals(d(13), CloudSync.diagDay(CloudSync.KIND_EVENING, d(13), 23 * 60 + 40, today, gate))
        assertEquals(d(12), CloudSync.diagDay(CloudSync.KIND_EVENING, d(12), 7 * 60, today, gate))
        assertNull(CloudSync.diagDay(CloudSync.KIND_EVENING, null, 12 * 60, today, gate))
        // Upload now: the evening it belongs to (after the gate: today; before 08:00: yesterday), else today so far.
        assertEquals(d(13), CloudSync.diagDay(CloudSync.KIND_NOW, null, 23 * 60 + 40, today, gate))
        assertEquals(d(12), CloudSync.diagDay(CloudSync.KIND_NOW, null, 6 * 60, today, gate))
        assertEquals(d(13), CloudSync.diagDay(CloudSync.KIND_NOW, null, 11 * 60, today, gate))
        // A backup after a strategy change, and the problems check: none.
        assertNull(CloudSync.diagDay(CloudSync.KIND_BACKUP, d(13), 23 * 60 + 40, today, gate))
        assertNull(CloudSync.diagDay(CloudSync.KIND_CHECK, d(13), 23 * 60 + 40, today, gate))
        assertNull(CloudSync.diagDay("weird", d(13), 23 * 60 + 40, today, gate))
    }

    // ---- 14 Oct review of G3: a failed day is sent again, a pre-close upload is partial ---------------------------------

    @Test fun aRunAlsoSendsTheMissedDaysOfTheLastThree() {
        val full = CloudSync.DIAG_FULL
        // Nothing kept yet (the first run): its own day only, never days before the diagnostics began.
        assertEquals(listOf(d(13)), CloudSync.diagDays(d(13), emptyMap()))
        assertEquals(emptyList(), CloudSync.diagDays(null, mapOf(d(12) to full)))
        // 10 Oct full, 11 Oct failed (tried), 12 Oct partial only: 11 and 12 go again, oldest first, then 13.
        val done = mapOf(d(10) to full, d(11) to CloudSync.DIAG_TRIED, d(12) to CloudSync.DIAG_PARTIAL)
        assertEquals(listOf(d(11), d(12), d(13)), CloudSync.diagDays(d(13), done))
        // A day missed with no record at all (the phone was off two evenings) goes too, once the record began before it.
        assertEquals(listOf(d(11), d(12), d(13)), CloudSync.diagDays(d(13), mapOf(d(10) to full)))
        // At most three days back: 9 Oct is too old.
        assertEquals(listOf(d(10), d(11), d(12), d(13)), CloudSync.diagDays(d(13), mapOf(d(5) to CloudSync.DIAG_TRIED)))
        // A re-run of an older day than the record's first sends that day only.
        assertEquals(listOf(d(8)), CloudSync.diagDays(d(8), mapOf(d(12) to full)))
        // Its own day is sent again even when full (the evening run replaces a re-run).
        assertEquals(listOf(d(13)), CloudSync.diagDays(d(13), mapOf(d(12) to full, d(13) to full)))
        // Across a month's end.
        assertEquals(listOf(d(30, 9), d(1), d(2)), CloudSync.diagDays(d(2), mapOf(d(29, 9) to full)))
    }

    @Test fun anUploadBeforeTheEveningGateIsPartialAndNeverLowersAFullDay() {
        val gate = 23 * 60 + 30
        assertEquals(CloudSync.DIAG_PARTIAL, CloudSync.diagState(d(13), today, 11 * 60, gate))
        assertEquals(CloudSync.DIAG_PARTIAL, CloudSync.diagState(d(13), today, gate - 1, gate))
        assertEquals(CloudSync.DIAG_FULL, CloudSync.diagState(d(13), today, gate, gate))
        assertEquals(CloudSync.DIAG_FULL, CloudSync.diagState(d(12), today, 1 * 60, gate), "a past day is whole")
        var m = CloudSync.diagMark(emptyMap(), d(13), CloudSync.DIAG_TRIED)
        assertEquals(CloudSync.DIAG_TRIED, m[d(13)])
        m = CloudSync.diagMark(m, d(13), CloudSync.DIAG_FULL)
        assertEquals(CloudSync.DIAG_FULL, CloudSync.diagMark(m, d(13), CloudSync.DIAG_PARTIAL)[d(13)])
        assertEquals(CloudSync.DIAG_FULL, CloudSync.diagMark(m, d(13), CloudSync.DIAG_TRIED)[d(13)])
        // Kept and read back; a broken entry is dropped; an odd state is held to the known ones.
        val kept = CloudSync.diagEncode(mapOf(d(13) to 2, d(11) to 0, d(12) to 1))
        assertEquals("2026-10-11=0,2026-10-12=1,2026-10-13=2", kept)
        assertEquals(mapOf(d(11) to 0, d(12) to 1, d(13) to 2), CloudSync.diagDecode(kept))
        assertEquals(mapOf(d(13) to 2), CloudSync.diagDecode("2026-10-13=7,junk,2026-13-40=1,2026-10-14=x"))
        assertEquals(emptyMap(), CloudSync.diagDecode(null))
        assertEquals(60, CloudSync.diagDecode(CloudSync.diagEncode((1..90).associate { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) to 2 })).size)
    }

    @Test fun theCardNamesTheDaysThatNeverWentUpOnceTheirWindowEnded() {
        val full = CloudSync.DIAG_FULL
        // Nothing kept: nothing missed.
        assertEquals(emptyList(), CloudSync.diagMissed(emptyMap(), today, 12 * 60))
        // 10 Oct full, 11 Oct failed, 12 Oct partial. At 12:00 on the 13th, the 12th's window (its evening and the morning
        // catch-up to 08:00) is over: 11 and 12 are missed. Today is not, its evening is still to come.
        val done = mapOf(d(10) to full, d(11) to CloudSync.DIAG_TRIED, d(12) to CloudSync.DIAG_PARTIAL)
        assertEquals(listOf(d(11), d(12)), CloudSync.diagMissed(done, today, 12 * 60))
        // At 07:00, the 12th's morning catch-up may still send it: only the 11th.
        assertEquals(listOf(d(11)), CloudSync.diagMissed(done, today, 7 * 60))
        // A day with no record at all after the first is missed too; at most a week back.
        assertEquals(listOf(d(11), d(12)), CloudSync.diagMissed(mapOf(d(10) to full), today, 9 * 60))
        assertEquals((6..12).map { d(it) }, CloudSync.diagMissed(mapOf(d(1) to CloudSync.DIAG_TRIED), today, 9 * 60))
        // The card's line, in red (✗), saying the next run tries again while the days are within its reach.
        val s = status().copy(lastDiagDay = d(10), diagMissed = listOf(d(11), d(12)))
        val card = CloudSync.card(s, toDay, today).toMap()
        assertEquals("last day uploaded: 10 Oct ✓", card["Diagnostics"])
        assertEquals("✗ diagnostics 11 Oct, 12 Oct not uploaded · the next run tries again", card["Diagnostics missed"])
        val old = CloudSync.card(status().copy(lastDiagDay = d(10), diagMissed = listOf(d(6))), toDay, today).toMap()
        assertEquals("✗ diagnostics 6 Oct not uploaded", old["Diagnostics missed"])
        assertNull(CloudSync.card(status().copy(lastDiagDay = d(12)), toDay, today).toMap()["Diagnostics missed"])
    }

    @Test fun aPartialDayIsSaidOnTheCard() {
        val s = status().copy(lastDiagDay = d(13), diagPartial = true)
        val line = CloudSync.card(s, toDay, today).toMap()["Diagnostics"]!!
        assertEquals("last day uploaded: 13 Oct · partial (sent before the close; the evening run replaces it)", line)
        assertFalse(line.contains("✓"))
    }

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
        // 00:30: a job held past midnight still runs overnight, until 08:00 (13 Oct review of G4); 08:00 does not.
        assertEquals(CloudSync.Plan(backup = true, days = false), plan(CloudSync.KIND_BACKUP, 30))
        assertNull(plan(CloudSync.KIND_BACKUP, 8 * 60))
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

    // ---- G4 (13 Oct, Boss: "after the commodities close"): the evening gate after MCX's close ---------------------------

    private val cal = com.optionslab.engine.mcx.McxSession.DEFAULT
    private fun gate(day: LocalDate) = CloudSync.eveningFrom(true, cal.window(day))
    private val mcx2330 = 23 * 60 + 30
    private val mcx2355 = 23 * 60 + 55

    @Test fun theGateIsMcxsCloseFromItsCalendar() {
        // Tue 13 Oct 2026, US summer time: MCX closes 23:30.
        assertEquals(mcx2330, gate(d(13)))
        // Mon 2 Nov 2026, the first day after the US clocks went back: 23:55.
        assertEquals(mcx2355, gate(d(2, 11)))
        // An MCX holiday with only the evening session (Dussehra, 20 Oct): still its close.
        assertEquals(mcx2330, gate(d(20)))
        // Diwali Muhurat on a Sunday (18:00-19:00): after that session.
        assertEquals(19 * 60, gate(d(8, 11)))
        // MCX shut all day (Gandhi Jayanti, 2 Oct; Christmas): 15:45, after F&O's close.
        assertEquals(CloudSync.EVENING, gate(d(2)))
        assertEquals(CloudSync.EVENING, gate(d(25, 12)))
        // A weekend: 15:45.
        assertEquals(CloudSync.EVENING, gate(d(17)))
        assertEquals(CloudSync.EVENING, gate(d(18)))
        // The calendar could not be read: the safe, later 23:55, whatever window was passed.
        assertEquals(CloudSync.EVENING_SAFE, CloudSync.eveningFrom(false, null))
        assertEquals(CloudSync.EVENING_SAFE, CloudSync.eveningFrom(false, cal.window(d(13))))
        assertEquals(mcx2355, CloudSync.EVENING_SAFE)
    }

    @Test fun theGateIsNeverBefore1545NorBeforeAnOddClose() {
        val w = { a: Int, b: Int -> com.optionslab.engine.mcx.McxSession.Window(java.time.LocalTime.of(a, 0), java.time.LocalTime.of(b, 0)) }
        // A morning-only session (09:00-14:00): 15:45 still, after F&O's close.
        assertEquals(CloudSync.EVENING, CloudSync.eveningFrom(true, w(9, 14)))
        // A session that ends at 17:00: 17:00.
        assertEquals(17 * 60, CloudSync.eveningFrom(true, w(9, 17)))
        // A window that does not close after it opens (past midnight, or empty): the day's last minute, never earlier.
        assertEquals(CloudSync.LAST_MINUTE, CloudSync.eveningFrom(true, w(18, 0)))
        assertEquals(CloudSync.LAST_MINUTE, CloudSync.eveningFrom(true, w(18, 18)))
    }

    @Test fun theEveningRunWaitsForMcxsCloseJustBeforeAndJustAfter() {
        val g = gate(today)                                                   // 23:30
        assertFalse(CloudSync.eveningDue(CloudSync.EVENING, null, today, g))  // 15:45: MCX still trades
        assertFalse(CloudSync.eveningDue(22 * 60, null, today, g))
        assertFalse(CloudSync.eveningDue(g - 1, null, today, g))              // 23:29
        assertTrue(CloudSync.eveningDue(g, null, today, g))                   // 23:30
        assertEquals(today, CloudSync.eveningDueDay(g, today.minusDays(1), today, g))
        assertNull(CloudSync.eveningDueDay(g + 10, today, today, g))          // done tonight
        // US winter time: 23:54 no, 23:55 yes.
        val nov2 = d(2, 11)
        assertFalse(CloudSync.eveningDue(mcx2355 - 1, null, nov2, gate(nov2)))
        assertTrue(CloudSync.eveningDue(mcx2355, null, nov2, gate(nov2)))
        // A day without MCX: from 15:45, as before.
        val sat = d(17)
        assertFalse(CloudSync.eveningDue(CloudSync.EVENING - 1, null, sat, gate(sat)))
        assertTrue(CloudSync.eveningDue(CloudSync.EVENING, null, sat, gate(sat)))
        // The old callers (no gate given) keep 15:45.
        assertTrue(CloudSync.eveningDue(CloudSync.EVENING, null, today))
    }

    @Test fun aMissedEveningRunsTheNextMorningOnceAndNeverSkipsADay() {
        val mon = d(2, 11); val tue = d(3, 11)
        val g = gate(mon)                                                      // 23:55
        // Monday's run was due at 23:55 but the phone was not charging: Tuesday 00:30 it is still Monday's evening.
        assertEquals(mon, CloudSync.eveningOf(30, tue, gate(tue)))
        assertEquals(mon, CloudSync.eveningDueDay(30, d(1, 11), tue, gate(tue)))
        assertEquals(mon, CloudSync.eveningDueDay(7 * 60 + 59, null, tue, gate(tue)))   // until 07:59
        // 08:00 to the gate: nothing (an hour before the markets open, and while they are open).
        assertNull(CloudSync.eveningOf(8 * 60, tue, gate(tue)))
        assertNull(CloudSync.eveningDueDay(8 * 60 + 59, null, tue, gate(tue)))
        assertNull(CloudSync.eveningOf(9 * 60, tue, gate(tue)))
        assertNull(CloudSync.eveningDueDay(9 * 60, d(1, 11), tue, gate(tue)))
        assertNull(CloudSync.eveningDueDay(15 * 60 + 45, d(1, 11), tue, gate(tue)))
        // Monday done at 00:30 Tuesday (kept as Monday): not again that morning ...
        assertNull(CloudSync.eveningDueDay(31, mon, tue, gate(tue)))
        assertNull(CloudSync.eveningDueDay(8 * 60, mon, tue, gate(tue)))
        // ... and Tuesday's own evening still runs after Tuesday's close (no day skipped).
        assertEquals(tue, CloudSync.eveningDueDay(gate(tue), mon, tue, gate(tue)))
        // Monday's ran at 23:58 and ended after midnight: kept as Monday, so Tuesday morning does not run it twice ...
        assertNull(CloudSync.eveningDueDay(5, mon, tue, gate(tue)))
        // ... and Tuesday's evening is not skipped (had it been kept as Tuesday, the run that started on Monday would hide it).
        assertNotNull(CloudSync.eveningDueDay(gate(tue), mon, tue, gate(tue)))
        assertNull(CloudSync.eveningDueDay(gate(tue), tue, tue, gate(tue)), "kept as Tuesday would skip Tuesday")
        // Monday at its own gate, before midnight, is Monday's.
        assertEquals(mon, CloudSync.eveningDueDay(g, d(1, 11), mon, g))
        // A Friday's evening missed: Saturday morning runs it; Saturday's own evening (no MCX) from 15:45.
        val fri = d(16); val sat = d(17)
        assertEquals(fri, CloudSync.eveningDueDay(60, d(15), sat, gate(sat)))
        assertEquals(sat, CloudSync.eveningDueDay(CloudSync.EVENING, fri, sat, gate(sat)))
        // Two evenings missed: the morning runs the latest; the older day's files go with it (pendingDays).
        assertEquals(mon, CloudSync.eveningDueDay(60, d(30), tue, gate(tue)))
        // A kept evening after today is a clock that went back: not trusted.
        assertEquals(tue, CloudSync.eveningDueDay(gate(tue), d(9, 11), tue, gate(tue)))
    }

    @Test fun theWorkersPlanWithTheMcxGate() {
        val g = mcx2330
        fun plan(kind: String, minute: Int, due: Boolean = true) = CloudSync.plan(kind, minute, due, true, false, eveningFrom = g)
        val both = CloudSync.Plan(backup = true, days = true)
        val backup = CloudSync.Plan(backup = true, days = false)
        // EVENING: not at 15:45 or 23:29 on an MCX day; from 23:30; the next morning until 07:59; never 08:00 to the gate.
        assertNull(plan(CloudSync.KIND_EVENING, CloudSync.EVENING))
        assertNull(plan(CloudSync.KIND_EVENING, g - 1))
        assertEquals(both, plan(CloudSync.KIND_EVENING, g))
        assertEquals(both, plan(CloudSync.KIND_EVENING, 30))
        assertEquals(both, plan(CloudSync.KIND_EVENING, 7 * 60 + 59))
        assertNull(plan(CloudSync.KIND_EVENING, 8 * 60))
        assertNull(plan(CloudSync.KIND_EVENING, 8 * 60 + 58))           // the 08:58 catch-up of the review: no more
        assertNull(plan(CloudSync.KIND_EVENING, 9 * 60))
        assertNull(plan(CloudSync.KIND_EVENING, 30, due = false))
        // BACKUP: from the gate, or held past midnight until 07:59; never 08:00 to the gate.
        assertNull(plan(CloudSync.KIND_BACKUP, CloudSync.EVENING))
        assertNull(plan(CloudSync.KIND_BACKUP, g - 1))
        assertEquals(backup, plan(CloudSync.KIND_BACKUP, g))
        assertEquals(backup, plan(CloudSync.KIND_BACKUP, 5))
        assertEquals(backup, plan(CloudSync.KIND_BACKUP, 7 * 60 + 59))
        assertNull(plan(CloudSync.KIND_BACKUP, 8 * 60))
        // NOW (Boss's tap): unchanged, any hour.
        assertEquals(both, plan(CloudSync.KIND_NOW, CloudSync.EVENING))
        assertEquals(both, plan(CloudSync.KIND_NOW, 10 * 60))
        // CHECK: nothing; a refused token: no automatic kind.
        assertNull(plan(CloudSync.KIND_CHECK, g))
        assertNull(CloudSync.plan(CloudSync.KIND_EVENING, g, true, true, true, eveningFrom = g))
        assertNull(CloudSync.plan(CloudSync.KIND_BACKUP, g, true, true, true, eveningFrom = g))
        // A day without MCX: the old 15:45.
        assertEquals(both, CloudSync.plan(CloudSync.KIND_EVENING, CloudSync.EVENING, true, true, false, eveningFrom = CloudSync.EVENING))
        // The calendar unread: 23:55.
        assertNull(CloudSync.plan(CloudSync.KIND_EVENING, mcx2330, true, true, false, eveningFrom = CloudSync.EVENING_SAFE))
        assertEquals(both, CloudSync.plan(CloudSync.KIND_EVENING, mcx2355, true, true, false, eveningFrom = CloudSync.EVENING_SAFE))
    }

    @Test fun aChangesBackupWaitsForMcxsClose() {
        // 11:00 on an MCX day closing 23:30: 750 minutes.
        assertEquals(mcx2330 - 11 * 60L, CloudSync.backupDelayMin(11 * 60, mcx2330))
        // 16:00 (after the old 15:45): still waits for 23:30.
        assertEquals(mcx2330 - 16 * 60L, CloudSync.backupDelayMin(16 * 60, mcx2330))
        // 23:29: the debounce (1 minute short); after the close: the debounce.
        assertEquals(CloudSync.DEBOUNCE_MIN, CloudSync.backupDelayMin(mcx2330 - 1, mcx2330))
        assertEquals(CloudSync.DEBOUNCE_MIN, CloudSync.backupDelayMin(mcx2330 + 10, mcx2330))
        // US winter, or the calendar unread: 23:55.
        assertEquals(mcx2355 - 20 * 60L, CloudSync.backupDelayMin(20 * 60, CloudSync.EVENING_SAFE))
        // No MCX today: 15:45, as before.
        assertEquals(CloudSync.EVENING - 600L, CloudSync.backupDelayMin(600, gate(d(17))))
        // Never in a trading day's 09:00-15:45 for a day's big file, whatever the gate.
        assertFalse(CloudSync.liveAllowed(15 * 60 + 44, tradingDay = true))
    }

    @Test fun theRemindersSayAfterTheMcxClose() {
        val toDay = { ms: Long -> LocalDate.ofEpochDay(ms) }
        fun st(last: CloudSync.Last?, waiting: Int) = CloudSync.Status(true, true, null, last, last, null, waiting)
        val old = CloudSync.Last(today.minusDays(3).toEpochDay(), true, 10, "backup")
        for (p in listOf(CloudSync.problem(st(old, 1), today, toDay), CloudSync.problem(st(null, 3), today, toDay))) {
            assertNotNull(p)
            assertTrue(p.contains("after the MCX evening close (15:45 on days without an MCX session)"), p)
            assertFalse(p.contains("after 15:45."), p)
        }
    }

    @Test fun aRunChecksTheClockAgainBeforeEachSend() {
        val g = mcx2330
        // The automatic window: the gate to midnight, and midnight to 07:59.
        assertTrue(CloudSync.autoSendAllowed(g, g))
        assertTrue(CloudSync.autoSendAllowed(23 * 60 + 59, g))
        assertTrue(CloudSync.autoSendAllowed(0, g))
        assertTrue(CloudSync.autoSendAllowed(7 * 60 + 59, g))
        assertFalse(CloudSync.autoSendAllowed(8 * 60, g))
        assertFalse(CloudSync.autoSendAllowed(9 * 60 + 5, g))
        assertFalse(CloudSync.autoSendAllowed(CloudSync.EVENING, g))
        assertFalse(CloudSync.autoSendAllowed(g - 1, g))
        assertTrue(CloudSync.autoSendAllowed(CloudSync.EVENING))         // a day without MCX: 15:45
        // The review's case: an automatic run that began at 07:59 reaches 08:00, then 09:05 on a trading day: no new day's
        // file and no backup.
        for (m in listOf(8 * 60, 9 * 60 + 5)) {
            assertFalse(CloudSync.sendNow(m, tradingDay = true, manual = false, dayFile = true, eveningFrom = g), "day at $m")
            assertFalse(CloudSync.sendNow(m, tradingDay = true, manual = false, dayFile = false, eveningFrom = g), "backup at $m")
        }
        // Inside the window: both go.
        assertTrue(CloudSync.sendNow(7 * 60 + 59, tradingDay = true, manual = false, dayFile = true, eveningFrom = g))
        assertTrue(CloudSync.sendNow(7 * 60 + 59, tradingDay = true, manual = false, dayFile = false, eveningFrom = g))
        assertTrue(CloudSync.sendNow(g + 5, tradingDay = true, manual = false, dayFile = true, eveningFrom = g))
        // An evening run that runs past midnight keeps going overnight.
        assertTrue(CloudSync.sendNow(10, tradingDay = true, manual = false, dayFile = true, eveningFrom = g))
        // Boss's tap: a day's file never in a trading day's 09:00-15:45, but any hour otherwise; the backup at any hour.
        assertFalse(CloudSync.sendNow(9 * 60 + 5, tradingDay = true, manual = true, dayFile = true, eveningFrom = g))
        assertTrue(CloudSync.sendNow(9 * 60 + 5, tradingDay = false, manual = true, dayFile = true, eveningFrom = g))
        assertTrue(CloudSync.sendNow(8 * 60 + 30, tradingDay = true, manual = true, dayFile = true, eveningFrom = g))
        assertTrue(CloudSync.sendNow(9 * 60 + 5, tradingDay = true, manual = true, dayFile = false, eveningFrom = g))
        assertTrue(CloudSync.sendNow(CloudSync.EVENING, tradingDay = true, manual = true, dayFile = true, eveningFrom = g))
    }
}
