package com.optionslab.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * SEBI's static-IP rule for API orders: Zerodha accepts an order only from the IP
 * registered on the Kite developer app. The phone reaches Zerodha through the owner's
 * WireGuard relay (docs/static-ip-relay.md); this checks, before a new position is
 * opened, that the phone's public IP right now is that registered one, so an order is
 * stopped here with a clear reason instead of being rejected at Zerodha.
 *
 * Exits are never blocked here (a wrong verdict must not trap a position); Zerodha
 * still decides. The IP is read from api.ipify.org (it sees only that a request came).
 */
object StaticIp {
    private const val KEY = "k.staticIp"            // "k." : never carried by a backup
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")

    private lateinit var app: Context
    fun init(context: Context) { app = context.applicationContext }

    /** The IP registered with Zerodha, or null when not set (then nothing is checked). */
    var registered: String?
        get() = SecurePrefs.getString(KEY)
        set(v) = SecurePrefs.put(KEY, v?.trim()?.ifEmpty { null })

    fun valid(ip: String) = IPV4.matches(ip.trim())

    /** Is a VPN (the WireGuard tunnel) carrying the phone's traffic right now? */
    fun vpnOn(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    @Volatile private var cached: Pair<Long, String>? = null

    /** The phone's public IPv4 as the internet sees it, cached for a minute (null when it cannot be read). */
    suspend fun current(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        cached?.let { (at, ip) -> if (!force && System.currentTimeMillis() - at < 60_000) return@withContext ip }
        runCatching {
            val c = URL("https://api.ipify.org").openConnection() as HttpsURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 6_000; c.useCaches = false
            try {
                if (c.responseCode != 200) null
                else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).trim() }.takeIf { valid(it) }
            } finally { c.disconnect() }
        }.getOrNull()?.also { cached = System.currentTimeMillis() to it }
    }

    data class Status(val registered: String?, val current: String?, val vpn: Boolean) {
        val matches: Boolean get() = registered != null && current == registered
    }

    suspend fun status(force: Boolean = false) = Status(registered, current(force), vpnOn())

    /**
     * Why a NEW live position must not be opened now, or null when it may. Nothing is
     * blocked while no IP is registered, or when the IP cannot be read (Zerodha decides).
     */
    suspend fun entryBlock(): String? {
        val reg = registered ?: return null
        val now = current() ?: return null
        if (now == reg) return null
        return "This phone is reaching Zerodha from $now, not your registered static IP $reg" +
            (if (vpnOn()) " (a VPN is on, but not the relay)" else ": switch on the WireGuard VPN") + ". Zerodha would reject the order."
    }
}
