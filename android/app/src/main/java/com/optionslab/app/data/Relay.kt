package com.optionslab.app.data

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.optionslab.app.security.SecurePrefs
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * The static-IP relay built into the app: an SSH connection to the owner's own cloud
 * server (the IP registered with Zerodha), through which only the Zerodha ORDER calls
 * travel. Nothing is installed on the server (every Ubuntu cloud image runs SSH) and
 * nothing on the phone (no VPN app).
 *
 * How: a small SOCKS5 endpoint on the phone's loopback hands each connection to an SSH
 * "direct-tcpip" channel, so TLS runs end to end between the app and api.kite.trade
 * (certificate pinning included); the server only forwards bytes it cannot read.
 * Only api.kite.trade and api.ipify.org (the IP check) may be reached through it.
 *
 * The key pair is made on the phone; only the public half is ever shown. The server's
 * host key is remembered on first connect and any change is refused.
 */
object Relay {
    private const val K_ON = "relay.on"
    private const val K_HOST = "relay.host"
    private const val K_USER = "relay.user"
    private const val K_PRV = "relay.key"
    private const val K_PUB = "relay.pub"
    private const val K_HOSTKEY = "relay.hostkey"
    private val ALLOWED = setOf("api.kite.trade", "api.ipify.org")

    var enabled: Boolean
        get() = SecurePrefs.getBoolean(K_ON, false)
        set(v) { SecurePrefs.put(K_ON, v); if (!v) close() }
    var host: String?
        get() = SecurePrefs.getString(K_HOST)
        set(v) { SecurePrefs.put(K_HOST, v?.trim()?.ifEmpty { null }); close() }
    var user: String
        get() = SecurePrefs.getString(K_USER) ?: "ubuntu"
        set(v) { SecurePrefs.put(K_USER, v.trim().ifEmpty { "ubuntu" }); close() }

    /** The public key to paste into the server ("Add SSH keys" when creating it). */
    val publicKey: String? get() = SecurePrefs.getString(K_PUB)

    /** Make the phone's key pair (RSA 3072). A new key replaces the old one: paste it again. */
    @Synchronized fun newKey(): String {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val prv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "iraalgo-relay") }.toString(Charsets.UTF_8.name()).trim()
        kp.dispose()
        SecurePrefs.putAll(mapOf(K_PRV to String(prv, Charsets.UTF_8), K_PUB to pub, K_HOSTKEY to null))
        close()
        return pub
    }

    /** Forget the server's remembered identity (after the server was rebuilt). */
    fun forgetServer() { SecurePrefs.put(K_HOSTKEY, null); close() }

    // ---- the SSH session ---------------------------------------------------------------

    @Volatile private var session: Session? = null
    @Volatile private var socks: ServerSocket? = null

    /** Remembers the server's key on first use; a different key later is refused. */
    private object Tofu : HostKeyRepository {
        override fun check(host: String?, key: ByteArray?): Int {
            val k = key?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: return HostKeyRepository.NOT_INCLUDED
            val known = SecurePrefs.getString(K_HOSTKEY)
            if (known == null) { SecurePrefs.put(K_HOSTKEY, k); return HostKeyRepository.OK }
            return if (known == k) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "iraalgo"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    @Synchronized private fun connect(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        runCatching { session?.disconnect() }
        val h = host ?: throw IOException("Relay: no server IP set (More → Zerodha → Static IP)")
        val prv = SecurePrefs.getString(K_PRV) ?: throw IOException("Relay: no key yet: tap Create key, then add it to the server")
        val jsch = JSch()
        jsch.addIdentity("iraalgo", prv.toByteArray(Charsets.UTF_8), SecurePrefs.getString(K_PUB)?.toByteArray(Charsets.UTF_8), null)
        jsch.hostKeyRepository = Tofu
        val s = jsch.getSession(user, h, 22)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey")
        s.timeout = 15_000
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        try {
            s.connect(15_000)
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            throw IOException(when {
                m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) ->
                    "Relay: the server's identity changed. If you rebuilt the server, tap Forget server and connect again."
                m.contains("Auth fail", true) -> "Relay: the server refused the key. Paste the app's public key into the server's SSH keys."
                m.contains("timeout", true) || m.contains("connect", true) -> "Relay: cannot reach $h on port 22 (is the server running?)"
                else -> "Relay: $m"
            })
        }
        session = s
        return s
    }

    @Synchronized private fun socksServer(): ServerSocket {
        socks?.takeIf { !it.isClosed }?.let { return it }
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        socks = ss
        Thread({
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                Thread({ serve(client) }, "relay-conn").apply { isDaemon = true }.start()
            }
        }, "relay-socks").apply { isDaemon = true }.start()
        return ss
    }

    /**
     * The proxy to reach Zerodha through, or null when the relay is off. When it is on
     * and cannot connect this THROWS: an order must never slip out from another IP.
     */
    fun proxy(): Proxy? {
        if (!enabled) return null
        connect()
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), socksServer().localPort))
    }

    /** Connect ahead of time (market hours), so the first order does not wait for the handshake. */
    fun warm() { if (enabled) runCatching { connect() } }

    @Synchronized fun close() {
        runCatching { socks?.close() }; socks = null
        runCatching { session?.disconnect() }; session = null
    }

    val connected: Boolean get() = session?.isConnected == true

    // ---- a minimal SOCKS5 CONNECT, loopback only, allow-listed destinations only ----------

    private fun serve(client: Socket) {
        client.use { c ->
            val inp = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            if (inp.readUnsignedByte() != 5) return
            repeat(inp.readUnsignedByte()) { inp.readUnsignedByte() }
            out.write(byteArrayOf(5, 0)); out.flush()                         // no authentication (loopback)
            if (inp.readUnsignedByte() != 5 || inp.readUnsignedByte() != 1) return
            inp.readUnsignedByte()
            val dest = when (inp.readUnsignedByte()) {
                1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress }
                3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
                4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress }
                else -> return
            }
            val port = inp.readUnsignedShort()
            if (port != 443 || !allowed(dest)) { reply(out, 2); return }
            val ch = try {
                (connect().openChannel("direct-tcpip") as ChannelDirectTCPIP).apply { setHost(dest); setPort(port) }
            } catch (e: Exception) { reply(out, 1); return }
            val fromServer = ch.inputStream
            val toServer = ch.outputStream
            try { ch.connect(15_000) } catch (e: Exception) { reply(out, 5); return }
            reply(out, 0)
            val up = Thread({ pump(inp, toServer); runCatching { ch.disconnect() } }, "relay-up").apply { isDaemon = true; start() }
            pump(fromServer, out)
            runCatching { ch.disconnect() }
            up.join(1_000)
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        runCatching { out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush() }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n); to.flush()
            }
        }
    }

    /** A destination by name, or an IP that one of the allowed names resolves to. */
    private fun allowed(dest: String): Boolean {
        if (dest in ALLOWED) return true
        return ALLOWED.any { name -> runCatching { InetAddress.getAllByName(name).any { it.hostAddress == dest } }.getOrDefault(false) }
    }
}
