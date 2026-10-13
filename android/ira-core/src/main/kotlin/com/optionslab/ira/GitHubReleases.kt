package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.time.LocalDate

/**
 * GitHub's REST API for release assets, as the phone's sync uses it (TODO G1/G2, 13 Oct): who the token belongs to (GET /user,
 * which also says when the token expires), a release by tag (made when missing), its assets, an upload (an asset of the same
 * name is replaced, never leaving no copy: [upload]), a rename, a delete and a download. Nothing else: no commit, no branch,
 * no workflow. Only ever the data repository: options-lab is refused at construction ([CloudSync.repoRefused], 13 Oct).
 *
 * The network itself is [Http], given by the caller (the phone's HTTPS, or a test's fake), so every rule here is tested
 * without a network. The token goes only into the Authorization header of requests to api.github.com and
 * uploads.github.com; a download redirect (to GitHub's file store) is followed WITHOUT it. No error carries the token: the
 * words are GitHub's own short message, redacted ([CloudSync.redact]). Blocking: call it on a background thread.
 */
class GitHubReleases(private val http: Http, private val token: String,
                     private val owner: String = CloudSync.OWNER, private val repo: String = CloudSync.DATA_REPO) {

    init { CloudSync.repoRefused(owner, repo)?.let { throw IllegalArgumentException(it) } }

    fun interface Http { fun send(r: Request): Response }

    /** [body]: streamed out with its length known (a big file is never held in memory). */
    class Body(val length: Long, val contentType: String, val write: (OutputStream) -> Unit) {
        companion object {
            fun of(bytes: ByteArray, type: String = "application/octet-stream") = Body(bytes.size.toLong(), type) { it.write(bytes) }
        }
    }

    /** A request; redirects are never followed by the [Http] itself (the caller decides who sees the token). */
    class Request(val method: String, val url: String, val headers: Map<String, String>, val body: Body? = null,
                  /** The most of the response body to read (bigger is an error). */
                  val maxBytes: Long = 4L shl 20)

    /** [headers]: names in lower case. */
    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    class Failure(val code: Int, why: String) : IOException(why)

    data class Asset(val id: Long, val name: String, val size: Long)
    data class Release(val id: Long, val tag: String, val assets: List<Asset>)
    data class User(val login: String, val expires: LocalDate?)

    private val api = "https://api.github.com"
    private val uploads = "https://uploads.github.com"
    private val base get() = "$api/repos/$owner/$repo"

    private fun headers(accept: String = "application/vnd.github+json") = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to accept,
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "IraAlgo",
    )

    private fun call(method: String, url: String, body: Body? = null, ok: Set<Int> = setOf(200, 201, 204)): Response {
        val r = http.send(Request(method, url, headers(), body))
        if (r.code !in ok) throw failure(r)
        return r
    }

    private fun failure(r: Response): Failure {
        val said = runCatching { (Json.parse(r.text) as? Map<*, *>)?.get("message")?.toString() }.getOrNull()
        val what = when (r.code) {
            401 -> "the token was refused (expired or revoked?)"
            403 -> "GitHub refused it (the token needs Contents: read and write on $repo)"
            404 -> "not found (is the token for $repo?)"
            422 -> "GitHub could not accept it"
            in 500..599 -> "GitHub is having trouble"
            else -> "GitHub answered ${r.code}"
        }
        val words = said?.take(100)?.let { "$what: $it" } ?: what
        // A 403 for GitHub's rate limit is not a refused token: kept as 429, so the uploads are not paused for it.
        val code = if (r.code == 403 && (said?.contains("rate limit", ignoreCase = true) == true || r.headers["x-ratelimit-remaining"] == "0")) 429 else r.code
        return Failure(code, CloudSync.redact("${r.code}: $words"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(r: Response): Map<String, Any?> = Json.parse(r.text) as? Map<String, Any?> ?: throw IOException("GitHub's answer was not understood")

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: throw IOException("GitHub's answer was not understood")

    private fun asset(m: Map<*, *>) = Asset(num(m["id"]), m["name"]?.toString() ?: "", (m["size"] as? Number)?.toLong() ?: 0L)

    // ---- the token ----------------------------------------------------------------------------------------------------

    /** Who the token belongs to, and its expiry if GitHub says (fine-grained tokens do). Throws [Failure] when refused. */
    fun user(): User {
        val r = call("GET", "$api/user")
        return User(obj(r)["login"]?.toString() ?: "?", CloudSync.expiryHeader(r.headers["github-authentication-token-expiration"]))
    }

    /** True when the token can see the data repository (a token made for another repository gets 404). */
    fun canSeeRepo(): Boolean {
        val r = http.send(Request("GET", base, headers()))
        return when (r.code) { 200 -> true; 404 -> false; else -> throw failure(r) }
    }

    /**
     * True when the token reaches the app's code ([CloudSync.CODE_REPO]): it sees it while it is private, or may push to it.
     * Such a token is refused (13 Oct review): a stolen phone could otherwise push code CI signs. Read-only (GET).
     */
    fun reachesCode(): Boolean {
        val r = http.send(Request("GET", "$api/repos/${CloudSync.OWNER}/${CloudSync.CODE_REPO}", headers()))
        if (r.code == 404) return false
        // Any other refusal is ambiguous (SSO, a policy): taken as "reaches", so it fails closed. A rate limit is an error.
        if (r.code == 403 || r.code == 401) { val f = failure(r); if (f.code == 429) throw f; return true }
        if (r.code != 200) throw failure(r)
        val o = obj(r)
        if (o["private"] == true) return true
        val perms = o["permissions"] as? Map<*, *> ?: return false
        return perms["push"] == true || perms["maintain"] == true || perms["admin"] == true
    }

    // ---- releases -----------------------------------------------------------------------------------------------------

    /** The release [tag] with its assets, or null when there is none. */
    fun release(tag: String): Release? {
        val r = http.send(Request("GET", "$base/releases/tags/${enc(tag)}", headers()))
        if (r.code == 404) return null
        if (r.code != 200) throw failure(r)
        val o = obj(r)
        val id = num(o["id"])
        return Release(id, tag, assets(id))
    }

    /** The release [tag], made (a pre-release, so it never shows as the app's latest) when it does not exist yet. */
    fun ensureRelease(tag: String, title: String, notes: String): Release {
        release(tag)?.let { return it }
        val body = Json.write(mapOf("tag_name" to tag, "name" to title, "body" to notes, "prerelease" to true, "draft" to false))
        val r = http.send(Request("POST", "$base/releases", headers(), Body.of(body.toByteArray(Charsets.UTF_8), "application/json")))
        // Made meanwhile by another run (422 "already_exists"): read it.
        if (r.code == 422) return release(tag) ?: throw failure(r)
        if (r.code != 201 && r.code != 200) throw failure(r)
        return Release(num(obj(r)["id"]), tag, emptyList())
    }

    /** Every asset of release [id] (100 a page). */
    fun assets(id: Long): List<Asset> {
        val out = ArrayList<Asset>()
        var page = 1
        while (page <= 50) {
            val r = call("GET", "$base/releases/$id/assets?per_page=100&page=$page")
            val list = Json.parse(r.text) as? List<*> ?: throw IOException("GitHub's answer was not understood")
            list.forEach { (it as? Map<*, *>)?.let { m -> out += asset(m) } }
            if (list.size < 100) break
            page++
        }
        return out
    }

    /**
     * Uploads [body] as [name] to [release], replacing an asset of that name. Returns the new asset. A replacement never
     * leaves no copy (13 Oct review): the new file goes up as [tempName] first, then the old one is deleted and the new one
     * renamed. A failure at any step leaves the old copy, or the new one under its temporary name; the next upload of that
     * name puts the final name back and then removes the temporary one.
     */
    fun upload(release: Release, name: String, body: Body): Asset {
        val now = assets(release.id)
        val old = now.firstOrNull { it.name == name }
        val stale = now.filter { it.name == tempName(name) }
        if (old == null) {
            val a = post(release, name, body)
            stale.forEach { runCatching { delete(it.id) } }
            return a
        }
        stale.forEach { delete(it.id) }                 // the old copy is still there
        val fresh = post(release, tempName(name), body)
        delete(old.id)
        return rename(fresh.id, name)
    }

    private fun post(release: Release, name: String, body: Body): Asset {
        val r = http.send(Request("POST", "$uploads/repos/$owner/$repo/releases/${release.id}/assets?name=${enc(name)}", headers(), body))
        if (r.code != 201 && r.code != 200) throw failure(r)
        return asset(obj(r))
    }

    /** Renames asset [assetId] to [name] (PATCH). Returns it. */
    fun rename(assetId: Long, name: String): Asset {
        val body = Json.write(mapOf("name" to name)).toByteArray(Charsets.UTF_8)
        return asset(obj(call("PATCH", "$base/releases/assets/$assetId", Body.of(body, "application/json"), ok = setOf(200))))
    }

    fun delete(assetId: Long) {
        call("DELETE", "$base/releases/assets/$assetId", ok = setOf(204, 404))
    }

    /**
     * An asset's bytes (at most [maxBytes]). GitHub answers with a redirect to its file store: that second request carries
     * no token (the store would refuse it, and it must never see it).
     */
    fun download(assetId: Long, maxBytes: Long = 64L shl 20): ByteArray {
        val first = http.send(Request("GET", "$base/releases/assets/$assetId", headers("application/octet-stream"), maxBytes = maxBytes))
        if (first.code == 200) return first.body
        if (first.code !in 300..399) throw failure(first)
        val to = first.headers["location"] ?: throw IOException("GitHub's download had no address")
        if (!to.startsWith("https://")) throw IOException("refusing a download that is not HTTPS")
        val r = http.send(Request("GET", to, mapOf("Accept" to "application/octet-stream", "User-Agent" to "IraAlgo"), maxBytes = maxBytes))
        if (r.code != 200) throw Failure(r.code, "${r.code}: the download failed")
        return r.body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** The temporary name a replacement goes up under ([upload]). Never a live or backup name. */
        fun tempName(name: String) = "$name.new"
    }
}
