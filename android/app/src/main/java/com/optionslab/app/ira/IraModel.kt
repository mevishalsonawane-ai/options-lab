package com.optionslab.app.ira

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.optionslab.ira.Writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JarvisAlgo's on-device language model: Qwen2.5 3B Instruct (Q4_K_M, GGUF), one exact file from Hugging Face, pinned by
 * commit, size and SHA-256. It is downloaded only after the owner says yes (the Ira screen asks), over an unmetered
 * connection, into the app's no-backup folder; a file that does not match the fingerprint is deleted, never loaded.
 * The model only rewrites Ira's own answers ([Writer]): every number is checked against the facts, and anything that
 * fails is dropped for Ira's draft. Nothing is sent anywhere; the model runs on the phone's CPU.
 */
object IraModel {
    const val NAME = "Qwen2.5 3B"
    const val FILE = "qwen2.5-3b-instruct-q4_k_m.gguf"
    const val COMMIT = "7dabda4d13d513e3e842b20f0d435c732f172cbe"
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/$COMMIT/$FILE"
    const val SIZE = 2_104_932_768L
    const val SHA256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d"

    /** The only hosts the download may touch: Hugging Face and its file CDN (rule 2's list for the model). */
    fun hostAllowed(host: String?): Boolean = host != null &&
        (host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co"))

    enum class Status { UNSUPPORTED, ABSENT, DOWNLOADING, VERIFYING, READY, FAILED }
    data class State(val status: Status = Status.ABSENT, val done: Long = 0, val message: String? = null,
                     val loaded: Boolean = false, val writing: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var handle = 0L
    private var idle: Job? = null
    /** "Later" this run: asked again the next time the app starts. */
    @Volatile private var deferred = false

    fun file(c: Context) = File(c.noBackupFilesDir, FILE)
    fun part(c: Context) = File(c.noBackupFilesDir, "$FILE.part")

    fun init(context: Context) {
        val c = context.applicationContext
        app = c
        val status = when {
            !supported(c) -> Status.UNSUPPORTED
            ready(c) -> Status.READY
            file(c).length() == SIZE -> Status.VERIFYING   // the whole file is here but its check was not remembered
            else -> Status.ABSENT
        }
        _state.value = State(status = status, done = part(c).length())
        if (status == Status.VERIFYING) scope.launch(Dispatchers.IO) { recheck(c) }
    }

    /**
     * A model file already on the phone: checked against its fingerprint again instead of downloaded again. A match is
     * remembered and used; anything else is deleted. True when it matched.
     */
    internal fun recheck(c: Context): Boolean {
        val f = file(c)
        _state.update { it.copy(status = Status.VERIFYING, message = null) }
        val ok = f.length() == SIZE && runCatching { ModelDownload.sha256(f) }.getOrNull() == SHA256
        if (ok) { markVerified(c); _state.value = State(status = Status.READY, done = SIZE) }
        else { f.delete(); _state.value = State(status = if (supported(c)) Status.ABSENT else Status.UNSUPPORTED, done = part(c).length()) }
        return ok
    }

    /** JarvisAlgo on a 64-bit ARM phone with the dot-product and half-precision instructions and at least ~6 GB of RAM. */
    fun supported(c: Context): Boolean {
        if (!com.optionslab.app.BuildConfig.JARVIS || "arm64-v8a" !in Build.SUPPORTED_ABIS) return false
        val features = runCatching { File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.joinToString(" ") }.getOrDefault("")
        if (!(" asimddp" in " $features" && " asimdhp" in " $features")) return false
        val mem = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return mem.totalMem >= 5_500_000_000L
    }

    /** Why it cannot run here, in words. */
    fun unsupportedWhy(c: Context): String = when {
        !com.optionslab.app.BuildConfig.JARVIS -> "The model is in JarvisAlgo only."
        "arm64-v8a" !in Build.SUPPORTED_ABIS -> "The model needs a 64-bit ARM phone."
        else -> "This phone's processor or memory is not enough for the model (it needs ARMv8.2 with dot-product instructions and about 6 GB of RAM)."
    }

    /** The file is there and was checked against its fingerprint (the check is remembered by size and time). */
    private fun ready(c: Context): Boolean {
        val f = file(c)
        return f.length() == SIZE && runCatching { com.optionslab.app.security.SecurePrefs.getString("ira.model.verified") }.getOrNull() == "$SIZE:${f.lastModified()}"
    }

    internal fun markVerified(c: Context) {
        val f = file(c)
        runCatching { com.optionslab.app.security.SecurePrefs.put("ira.model.verified", "$SIZE:${f.lastModified()}") }
    }

    internal fun publish(s: State) { _state.value = s }
    internal fun publish(f: (State) -> State) { _state.update(f) }

    // ---- the question when the Ira screen opens -------------------------------------------------------------------

    /** Ask the owner now? Only when the model is missing here, not asked yet this run, and not declined for good. */
    fun shouldAsk(): Boolean {
        val s = _state.value.status
        if (s != Status.ABSENT && s != Status.FAILED) return false
        if (deferred) return false
        return runCatching { com.optionslab.app.security.SecurePrefs.getString("ira.model.ask") }.getOrNull() != "never"
    }

    fun later() { deferred = true }
    fun never() { deferred = true; runCatching { com.optionslab.app.security.SecurePrefs.put("ira.model.ask", "never") } }

    /** Starts (or resumes) the download - only from the owner's tap. */
    fun download(c: Context) { deferred = true; ModelDownload.start(c) }

    fun cancel(c: Context) = ModelDownload.cancel(c)

    /** Deletes the model (and any part of it). */
    suspend fun delete(c: Context) = lock.withLock {
        unloadLocked()
        file(c).delete(); part(c).delete()
        runCatching { com.optionslab.app.security.SecurePrefs.put("ira.model.verified", null) }
        _state.value = State(status = if (supported(c)) Status.ABSENT else Status.UNSUPPORTED)
    }

    // ---- using it ---------------------------------------------------------------------------------------------------

    /** The owner's switch: write answers with the model (on by default once it is ready). */
    var enabled: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("ira.model.use", true) }.getOrDefault(true)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("ira.model.use", v) } }

    fun usable(): Boolean = enabled && _state.value.status == Status.READY

    /**
     * Ira's [draft] for [question], rewritten by the model, or null (not ready, too slow, or the result failed the
     * checks - then the draft stands). One at a time; at most [TIMEOUT_MS].
     */
    suspend fun rewrite(question: String, facts: List<String>, draft: String): String? = withContext(Dispatchers.Default) {
        val c = app ?: return@withContext null
        if (!usable()) return@withContext null
        lock.withLock {
            idle?.cancel()
            _state.update { it.copy(writing = true) }
            try {
                if (handle == 0L) {
                    if (!LlmNative.ensure()) { _state.update { it.copy(message = "The model runner did not load on this phone.") }; return@withLock null }
                    handle = LlmNative.load(file(c).path, threads())
                    if (handle == 0L) { _state.update { it.copy(message = "The model file did not open; delete it and download again.") }; return@withLock null }
                    _state.update { it.copy(loaded = true, message = null) }
                }
                val watchdog = scope.launch { delay(TIMEOUT_MS); LlmNative.cancel() }
                val bytes = try { LlmNative.generate(handle, Writer.prompt(question, facts, draft), MAX_TOKENS) } finally { watchdog.cancel() }
                bytes?.let { Writer.check(String(it, Charsets.UTF_8), facts, draft) }
            } finally {
                _state.update { it.copy(writing = false) }
                idle = scope.launch { delay(IDLE_MS); lock.withLock { unloadLocked() } }
            }
        }
    }

    private fun unloadLocked() {
        if (handle != 0L) { runCatching { LlmNative.free(handle) }; handle = 0L; _state.update { it.copy(loaded = false) } }
    }

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    const val MAX_TOKENS = 160
    const val TIMEOUT_MS = 90_000L
    /** The model leaves memory after this long unused. */
    const val IDLE_MS = 10 * 60_000L
}

/** The JNI bridge to llama.cpp (src/jarvis/cpp), present in JarvisAlgo only. */
internal object LlmNative {
    @Volatile private var ok: Boolean? = null
    fun ensure(): Boolean = synchronized(this) {
        ok ?: runCatching { System.loadLibrary("jarvis_llm"); true }.getOrDefault(false).also { ok = it }
    }
    external fun load(path: String, threads: Int): Long
    external fun generate(handle: Long, prompt: String, maxTokens: Int): ByteArray?
    external fun cancel()
    external fun free(handle: Long)
}
