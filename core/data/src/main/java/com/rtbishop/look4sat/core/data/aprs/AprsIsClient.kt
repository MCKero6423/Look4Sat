package com.rtbishop.look4sat.core.data.aprs

import com.rtbishop.look4sat.core.domain.aprs.AprsPacket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * APRS-IS TCP client (reverse-ported from APRSdroid TcpUploader.scala).
 * Plain-text protocol: one login line + one packet per line; 30 s reconnect after drop.
 */
class AprsIsClient(
    private val host: String,
    private val port: Int,
    private val callsign: String,
    private val ssid: String,
    private val passcode: Int,
    private val version: String,
    private val filter: String = "",
    private val timeoutSec: Int = 120
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val lock = Any()

    val isConnected: Boolean
        get() = synchronized(lock) { socket?.isConnected == true && !socket!!.isClosed }

    /** Connect + login (synchronous/blocking; call from a background thread) */
    @Throws(Exception::class)
    fun connect() {
        disconnect()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 30_000)
        s.soTimeout = timeoutSec * 1000
        s.tcpNoDelay = true
        synchronized(lock) {
            socket = s
            writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1), true)
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1), 256)
        }
        // Login line
        val login = AprsPacket.formatLogin(callsign, ssid, passcode, version) + filter
        writer?.println(login)
        // Read the login response (aprsc replies # logresp ... verified/unverified)
        runCatching {
            s.soTimeout = 8000
            val resp = reader?.readLine()
            if (resp != null && (resp.contains("Invalid", ignoreCase = true) ||
                    resp.contains("unverified", ignoreCase = true))) {
                throw IllegalArgumentException(resp.trim())
            }
            // Restore timeout
            s.soTimeout = timeoutSec * 1000
        }
    }

    /**
     * Sends one APRS packet (one line) and tries to read the server ack.
     * Returns null=failed to send; Pair(ok, detail)=result (server error text lives in detail)
     */
    fun sendPacket(packetLine: String): Pair<Boolean, String>? {
        synchronized(lock) {
            val w = writer ?: return null
            w.println(packetLine)
            if (w.checkError()) return Pair(false, "write failed")
        }
        // Try to read the server response (short 3 s timeout; APRS-IS replies with an error line on bad format)
        return runCatching {
            val s = socket ?: return@runCatching Pair(true, "OK")
            val oldTimeout = s.soTimeout
            s.soTimeout = 3000
            try {
                val resp = reader?.readLine()
                if (resp != null && (resp.contains("Invalid", ignoreCase = true) ||
                        resp.contains("error", ignoreCase = true))) {
                    Pair(false, resp.trim())
                } else {
                    Pair(true, if (resp.isNullOrBlank()) "OK" else resp.trim())
                }
            } finally {
                s.soTimeout = oldTimeout
            }
        }.getOrElse { Pair(true, "OK") }
    }

    /** Read one line (server response; throws on timeout) */
    fun readLine(): String? {
        return reader?.readLine()
    }

    fun disconnect() {
        synchronized(lock) {
            runCatching { writer?.close() }
            runCatching { reader?.close() }
            runCatching { socket?.close() }
            writer = null
            reader = null
            socket = null
        }
    }
}
