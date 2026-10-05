package com.optionslab.app.ira

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.optionslab.ira.Writer
import java.util.Locale
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
 * Jarvis's on-device language model: Qwen2.5 Instruct (Q4_K_M, GGUF) - the fast 1.5B or the quality 3B, the owner's
 * choice - one exact file from Hugging Face, pinned by commit, size and SHA-256. It is downloaded only after the owner
 * says yes (the Ira screen asks), on Wi-Fi or mobile data, into the app's no-backup folder; a file that does not match
 * the fingerprint is deleted, never loaded.
 * The model only rewrites Ira's own answers ([Writer]): every number is checked against the facts, and anything that
 * fails is dropped for Ira's draft. Nothing is sent anywhere; the model runs on the phone's CPU.
 */
object IraModel {
    /** One exact model file on Hugging Face, pinned by commit, size and SHA-256. */
    data class Spec(val key: String, val name: String, val repo: String, val file: String, val commit: String,
                    val size: Long, val sha256: String, val minRam: Long, val about: String) {
        val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$file"
    }

    /** The owner's wish (2026-10-03, "the model is too slow"): the default, about twice as fast, a little less clever. */
    val FAST = Spec("fast", "Qwen2.5 1.5B", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "91cad51170dc346986eccefdc2dd33a9da36ead9", 1_117_320_736L, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        3_500_000_000L, "fast")
    /** The first model: cleverer, slower. */
    val QUALITY = Spec("quality", "Qwen2.5 3B", "Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf",
        "7dabda4d13d513e3e842b20f0d435c732f172cbe", 2_104_932_768L, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        5_500_000_000L, "best quality")
    /**
     * Boss, 4 Oct ("thinking takes too long, change to a fast and efficient model"): less than half the 1.5B's size, so
     * it loads and answers about two to three times faster. It only reads free-form words and chats - every figure and
     * action comes from the app's own rules - so a smaller model costs little.
     */
    val FASTEST = Spec("fastest", "Qwen2.5 0.5B", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "9217f5db79a29953eb74d5343926648285ec7e67", 491_400_032L, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        2_500_000_000L, "fastest")
    val SPECS = listOf(FASTEST, FAST, QUALITY)

    /** Which model the owner chose (the 1.5B unless another was picked). */
    val choice: Spec
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getString("ira.model.choice") }.getOrNull()
            .let { k -> SPECS.firstOrNull { it.key == k } } ?: FAST

    val NAME: String get() = choice.name
    val FILE: String get() = choice.file
    val COMMIT: String get() = choice.commit
    val URL: String get() = choice.url
    val SIZE: Long get() = choice.size
    val SHA256: String get() = choice.sha256

    /** The remembered check of [s]'s file (the first model kept its old key, so its check still counts). */
    private fun verifiedKey(s: Spec = choice) = if (s == QUALITY) "ira.model.verified" else "ira.model.verified.${s.key}"

    /**
     * The owner picks the fast or the quality model: the one in memory leaves, and the chosen one is used if it is on
     * the phone, else downloaded when the owner says so (the other file is removed once the new one is checked).
     */
    suspend fun choose(c: Context, s: Spec) {
        if (s == choice) return
        // A download under way is stopped (a cancel when none runs would overwrite the new model's state).
        if (_state.value.status == Status.DOWNLOADING || _state.value.status == Status.VERIFYING) ModelDownload.cancel(c)
        lock.withLock {
            unloadLocked()
            runCatching { com.optionslab.app.security.SecurePrefs.put("ira.model.choice", s.key) }
            // The new model's state is published before anything waiting on the lock can load a file.
            deferred = false
            init(c)
        }
    }

    /** Models on the phone other than the chosen one (a whole file or part of one), with the bytes each takes. */
    fun others(c: Context): List<Pair<Spec, Long>> = SPECS.filter { it != choice }.mapNotNull { o ->
        val bytes = File(c.noBackupFilesDir, o.file).length() + File(c.noBackupFilesDir, "${o.file}.part").length()
        if (bytes > 0) o to bytes else null
    }

    /** The owner deletes a model that is not the chosen one (the chosen one is never touched here). */
    suspend fun deleteOther(c: Context, o: Spec) = lock.withLock {
        if (o == choice) return@withLock
        File(c.noBackupFilesDir, o.file).delete(); File(c.noBackupFilesDir, "${o.file}.part").delete()
        runCatching { com.optionslab.app.security.SecurePrefs.put(verifiedKey(o), null) }
    }

    /** Once the chosen model is checked and ready: the other model's file goes (it can be 2 GB). */
    internal fun dropOthers(c: Context) {
        for (o in SPECS) if (o != choice) {
            File(c.noBackupFilesDir, o.file).delete(); File(c.noBackupFilesDir, "${o.file}.part").delete()
            runCatching { com.optionslab.app.security.SecurePrefs.put(verifiedKey(o), null) }
        }
    }

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
    /** Which of the model's memory slots each prompt reads into (used under [lock] only; as many as the runner has). */
    private val slots = com.optionslab.ira.PromptSlots(LlmNative.SLOTS)
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
    internal fun recheck(c: Context, spec: Spec = choice): Boolean {
        val f = File(c.noBackupFilesDir, spec.file)
        _state.update { it.copy(status = Status.VERIFYING, message = null) }
        val ok = f.length() == spec.size && runCatching { ModelDownload.sha256(f) }.getOrNull() == spec.sha256
        // The fingerprint is checked against the model whose file this is; the state is published only while it is chosen.
        if (ok) { markVerified(c, spec); if (spec == choice) _state.value = State(status = Status.READY, done = spec.size) }
        else { f.delete(); if (spec == choice) _state.value = State(status = if (supported(c)) Status.ABSENT else Status.UNSUPPORTED, done = part(c).length()) }
        return ok
    }

    /** Jarvis on a 64-bit ARM phone with the dot-product and half-precision instructions and at least ~6 GB of RAM. */
    fun supported(c: Context): Boolean {
        if (!com.optionslab.app.BuildConfig.JARVIS || "arm64-v8a" !in Build.SUPPORTED_ABIS) return false
        val features = runCatching { File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.joinToString(" ") }.getOrDefault("")
        if (!(" asimddp" in " $features" && " asimdhp" in " $features")) return false
        val mem = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return mem.totalMem >= choice.minRam
    }

    /** Why it cannot run here, in words. */
    fun unsupportedWhy(c: Context): String = when {
        !com.optionslab.app.BuildConfig.JARVIS -> "The model is in IraAlgo only."
        "arm64-v8a" !in Build.SUPPORTED_ABIS -> "The model needs a 64-bit ARM phone."
        else -> "This phone's processor or memory is not enough for the model (it needs ARMv8.2 with dot-product instructions and about ${(choice.minRam + 500_000_000L) / 1_000_000_000} GB of RAM)."
    }

    /** The file is there and was checked against its fingerprint (the check is remembered by size and time). */
    private fun ready(c: Context): Boolean {
        val f = file(c)
        return f.length() == SIZE && runCatching { com.optionslab.app.security.SecurePrefs.getString(verifiedKey()) }.getOrNull() == "$SIZE:${f.lastModified()}"
    }

    internal fun markVerified(c: Context, spec: Spec = choice) {
        val f = File(c.noBackupFilesDir, spec.file)
        runCatching { com.optionslab.app.security.SecurePrefs.put(verifiedKey(spec), "${spec.size}:${f.lastModified()}") }
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
        runCatching { com.optionslab.app.security.SecurePrefs.put(verifiedKey(), null) }
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
            // Checked again under the lock: the model may have been switched while this waited.
            if (!usable()) return@withLock null
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
                val bytes = try { gently { Writer.prompt(question, facts, draft).let { p -> LlmNative.generate(handle, p, MAX_TOKENS, slots.pick(p), false) } } } finally { watchdog.cancel() }
                bytes?.let { Writer.check(String(it, Charsets.UTF_8), facts, draft) }
            } finally {
                _state.update { it.copy(writing = false) }
                idle = idleUnload()
            }
        }
    }

    /**
     * The model's raw reply to [prompt] (at most [maxTokens]), or null - for [com.optionslab.ira.Intents], whose caller
     * checks the reply against its fixed list before anything is done with it. [oneLine]: the caller keeps only the
     * reply's first line, so the model stops at its end instead of writing on to [maxTokens] (the chat's "Boss: ..." it
     * would make up next, read by no one).
     */
    suspend fun complete(prompt: String, maxTokens: Int = 40, timeoutMs: Long = TIMEOUT_MS, oneLine: Boolean = false): String? = withContext(Dispatchers.Default) {
        val c = app ?: return@withContext null
        if (!usable()) return@withContext null
        lock.withLock {
            // Checked again under the lock: the model may have been switched while this waited.
            if (!usable()) return@withLock null
            idle?.cancel()
            _state.update { it.copy(writing = true) }
            try {
                if (handle == 0L) {
                    if (!LlmNative.ensure()) return@withLock null
                    handle = LlmNative.load(file(c).path, threads())
                    if (handle == 0L) return@withLock null
                    _state.update { it.copy(loaded = true, message = null) }
                }
                val watchdog = scope.launch { delay(timeoutMs) ; LlmNative.cancel() }
                val bytes = try { gently { LlmNative.generate(handle, prompt, maxTokens, slots.pick(prompt), oneLine) } } finally { watchdog.cancel() }
                bytes?.let { String(it, Charsets.UTF_8) }
            } finally {
                _state.update { it.copy(writing = false) }
                idle = idleUnload()
            }
        }
    }

    /**
     * Settings → Test the model: is it set up right? The file's fingerprint was checked when it arrived (READY); this
     * loads it on the phone's processor and asks for a fixed short reply, timing both. Plain words back, never throws.
     */
    suspend fun selfTest(): String = withContext(Dispatchers.Default) {
        val c = app ?: return@withContext "The app is not ready yet."
        when (_state.value.status) {
            Status.UNSUPPORTED -> return@withContext unsupportedWhy(c)
            Status.READY -> Unit
            else -> return@withContext "The model is not on the phone yet (or the download is not finished)."
        }
        if (!enabled) return@withContext "The model is switched off: switch on \"Write answers with $NAME\" first."
        val wasLoaded = _state.value.loaded
        val t0 = System.nanoTime()
        val reply = runCatching { complete("<|im_start|>system\nReply with exactly the words asked for.<|im_end|>\n" +
            "<|im_start|>user\nSay: Jarvis is ready.<|im_end|>\n<|im_start|>assistant\n", 16, oneLine = true) }.getOrNull()
        val secs = (System.nanoTime() - t0) / 1e9
        val said = reply?.trim()?.lineSequence()?.firstOrNull()?.take(60)?.replace("%", "")
        when {
            reply == null -> _state.value.message ?: "The model did not answer: delete it and download it again."
            said.isNullOrBlank() -> "The model loaded but wrote nothing: delete it and download it again."
            !said.lowercase().contains("ready") -> "The model answered \"$said\" in %.1f s, not the words asked for: it runs, but delete and download it again if answers look odd.".format(Locale.ENGLISH, secs)
            else -> "Working: $NAME answered \"$said\" in %.1f s%s on this phone. The file matched its fingerprint when it was downloaded.".format(Locale.ENGLISH, secs,
                if (wasLoaded) "" else " (including loading it into memory)")
        }
    }

    /**
     * Leaves memory after [IDLE_MS] unused - also while Jarvis listens (root cause, 4 Oct: kept loaded for as long as
     * listening was on, it starved the phone's on-device recognizer, and Jarvis stopped hearing his name).
     */
    private fun idleUnload(): Job = scope.launch {
        // While Jarvis listens, it leaves after a minute (the recognizer needs the memory); otherwise after 10 minutes.
        delay(if (JarvisVoice.wanted) 60_000L else IDLE_MS)
        lock.withLock { unloadLocked() }
    }

    /**
     * Loaded ahead (the Ira page opened, voice off): the first typed question does not wait the seconds loading takes.
     * Never while Jarvis listens - held in memory then, it starved the speech recognizer (Boss, 4 Oct).
     */
    fun preload() {
        val c = app ?: return
        if (!usable() || JarvisVoice.wanted) return
        scope.launch {
            lock.withLock {
                if (handle != 0L || !usable() || JarvisVoice.wanted) return@withLock
                idle?.cancel()
                if (!LlmNative.ensure()) return@withLock
                handle = LlmNative.load(file(c).path, threads())
                if (handle != 0L) _state.update { it.copy(loaded = true, message = null) }
                // Listening switched on while it loaded (it takes seconds): it leaves at once (review, 4 Oct).
                if (JarvisVoice.wanted) { unloadLocked(); return@withLock }
                idle = idleUnload()
            }
        }
    }

    /**
     * Listening has started: a model loaded ahead (the Ira page opened with voice off) leaves now, unless it is writing -
     * then it leaves a minute after its reply. Held in memory while listening, it starved the speech recognizer (4 Oct).
     */
    fun leaveForListening() {
        scope.launch {
            if (_state.value.writing) return@launch          // its own idle timer (a minute while listening) follows the reply
            lock.withLock { if (!_state.value.writing) { idle?.cancel(); unloadLocked() } }
        }
    }

    private fun unloadLocked() {
        if (handle != 0L) { runCatching { LlmNative.free(handle) }; handle = 0L; slots.clear(); _state.update { it.copy(loaded = false) } }
    }

    /**
     * The phone's fast cores (those within 80% of the fastest clock), two to four: the model's work is split evenly, so a
     * slow core would hold every step back. Half the cores when the clocks cannot be read.
     */
    private fun threads(): Int {
        val freqs = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull() }
        }.getOrDefault(emptyList())
        val fast = freqs.maxOrNull()?.let { top -> freqs.count { it >= top * 0.8 } }
        return (fast ?: (Runtime.getRuntime().availableProcessors() / 2)).coerceIn(2, 4)
    }

    /** Stops what the model is writing now (a new question came): the answer already shown stands. */
    fun stopWriting() { if (_state.value.writing) runCatching { LlmNative.cancel() } }

    /**
     * Runs [f] just below normal priority (the model's threads inherit it): the screen and the voice still come first,
     * but the model is no longer held to the phone's slow background cores.
     */
    private inline fun <T> gently(f: () -> T): T {
        val tid = android.os.Process.myTid()
        val was = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
        try { return f() } finally { runCatching { android.os.Process.setThreadPriority(was) } }
    }

    const val MAX_TOKENS = 160
    /** A rewrite not done by then is dropped (the answer already shown stands). */
    const val TIMEOUT_MS = 30_000L
    /** The model leaves memory after this long unused. */
    const val IDLE_MS = 10 * 60_000L
}

/** The JNI bridge to llama.cpp (src/jarvis/cpp), present in Jarvis only. */
internal object LlmNative {
    @Volatile private var ok: Boolean? = null
    fun ensure(): Boolean = synchronized(this) {
        ok ?: runCatching { System.loadLibrary("jarvis_llm"); true }.getOrDefault(false).also { ok = it }
    }
    external fun load(path: String, threads: Int): Long
    /** The runner's memory slots (SLOTS in jarvis_llm.cpp): [generate]'s slot is one of 0 until this. */
    const val SLOTS = 4
    external fun generate(handle: Long, prompt: String, maxTokens: Int, slot: Int, oneLine: Boolean): ByteArray?
    external fun cancel()
    external fun free(handle: Long)
}
