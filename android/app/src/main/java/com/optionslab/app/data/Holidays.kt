package com.optionslab.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * NSE trading holidays. Weekends were never the only days the market is
 * shut: on a holiday the watch, the 11:01 ticket, the harvest and every
 * strategy schedule must stand down, and the masthead must not say OPEN.
 *
 * The list comes from NSE's own holiday master (fetched on the phone, cached,
 * refreshed weekly) and can be corrected by hand in More → Schedule.
 * Not sensitive, so it is a plain file.
 */
object Holidays {
    private lateinit var file: File

    fun init(context: Context) { file = File(context.applicationContext.filesDir, "holidays.json") }

    data class Book(val fetched: LocalDate?, val nse: Map<LocalDate, String>, val added: Set<LocalDate>, val removed: Set<LocalDate>) {
        fun holiday(d: LocalDate) = (d in nse || d in added) && d !in removed
        fun upcoming(from: LocalDate): List<Pair<LocalDate, String>> =
            ((nse.keys + added) - removed).filter { !it.isBefore(from) }.sorted().map { it to (nse[it] ?: "added by you") }
    }

    @Volatile private var cache: Book? = null

    @Synchronized
    fun book(): Book {
        cache?.let { return it }
        val b = runCatching {
            val o = JSONObject(file.readText())
            fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { LocalDate.parse(a.getString(it)) }.toSet() } ?: emptySet()
            val nse = o.optJSONObject("nse")?.let { m -> m.keys().asSequence().associate { LocalDate.parse(it) to m.getString(it) } } ?: emptyMap()
            Book(o.optString("fetched").takeIf { it.isNotBlank() }?.let(LocalDate::parse), nse, set("added"), set("removed"))
        }.getOrElse { Book(null, emptyMap(), emptySet(), emptySet()) }
        cache = b
        return b
    }

    @Synchronized
    private fun save(b: Book) {
        val o = JSONObject()
        b.fetched?.let { o.put("fetched", it.toString()) }
        o.put("nse", JSONObject().apply { b.nse.forEach { (d, n) -> put(d.toString(), n) } })
        o.put("added", JSONArray().apply { b.added.sorted().forEach { put(it.toString()) } })
        o.put("removed", JSONArray().apply { b.removed.sorted().forEach { put(it.toString()) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cache = b
    }

    fun isHoliday(d: LocalDate): Boolean = book().holiday(d)

    fun add(d: LocalDate) = book().let { save(it.copy(added = it.added + d, removed = it.removed - d)) }
    fun remove(d: LocalDate) = book().let { save(it.copy(added = it.added - d, removed = it.removed + d)) }

    /** Whether the cached list is older than a week, or does not cover this year. */
    fun stale(today: LocalDate): Boolean {
        val b = book()
        return b.fetched == null || b.fetched.isBefore(today.minusDays(7)) || b.fetched.year != today.year
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val NSE_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    /**
     * Fetch NSE's trading-holiday master. NSE answers its API only to a
     * browser that visited the site first, so the home page is read for its
     * cookies and those are sent with the API call. Nothing of the owner's is sent.
     */
    fun refresh(): Int {
        val cookies = get("https://www.nseindia.com/", null).second
        val (body, _) = get("https://www.nseindia.com/api/holiday-master?type=trading", cookies)
        val o = JSONObject(body)
        val out = HashMap<LocalDate, String>()
        // "FO" is the derivatives segment the app trades; "CM" (cash) almost always matches it.
        for (seg in listOf("FO", "CM")) {
            val a = o.optJSONArray(seg) ?: continue
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                val d = runCatching { LocalDate.parse(h.getString("tradingDate").trim(), NSE_DATE) }.getOrNull() ?: continue
                out.putIfAbsent(d, h.optString("description", "NSE holiday"))
            }
            if (out.isNotEmpty()) break
        }
        if (out.isEmpty()) throw IOException("NSE returned no holidays")
        val b = book()
        save(b.copy(fetched = Market.today(), nse = out))
        return out.size
    }

    private fun get(url: String, cookie: String?): Pair<String, String?> {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            c.setRequestProperty("Referer", "https://www.nseindia.com/resources/exchange-communication-holidays")
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (c.responseCode !in 200..299) throw IOException("NSE answered ${c.responseCode}")
            val set = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                .map { it.substringBefore(';') }.filter { "=" in it }.joinToString("; ").ifEmpty { null }
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return body to set
        } catch (e: IOException) {
            throw IOException("Could not read NSE's holiday list: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}
