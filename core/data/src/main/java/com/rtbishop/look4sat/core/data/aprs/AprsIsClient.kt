package com.rtbishop.look4sat.core.data.aprs

import com.rtbishop.look4sat.core.domain.aprs.AprsLogin
import com.rtbishop.look4sat.core.domain.aprs.AprsPacket
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * APRS-IS TCP client. Plain text: the server greets, the client logs in, then one packet per line.
 *
 * Two things here decide whether the operator can trust the app at all. A packet sent on a dead
 * socket must not report success, and a login the server refused to verify must not look like a
 * working connection - an unverified client stays connected while the server silently drops
 * everything it sends.
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

    /**
     * What the server said about this login, or null before a login has been attempted.
     *
     * Kept as state rather than only thrown, because [AprsLogin.Outcome.Unverified] is not a
     * connection error: the socket is up and writes succeed. The operator has to be told, or
     * they will watch reports "succeed" for hours while nothing reaches the network.
     */
    @Volatile
    var loginOutcome: AprsLogin.Outcome? = null
        private set

    val isConnected: Boolean
        get() = synchronized(lock) { socket?.isConnected == true && socket?.isClosed == false }

    /** True when the server verified the passcode, so packets from this client are accepted. */
    val isVerified: Boolean get() = loginOutcome is AprsLogin.Outcome.Verified

    /**
     * True only when the server told us it did NOT verify the login.
     *
     * Distinct from `!isVerified` on purpose. [AprsLogin.Outcome.Unverified] means the server
     * said so and really is discarding our packets. [AprsLogin.Outcome.Unknown] means we could
     * not recognise its answer - the packets may well be landing - so blaming the operator's
     * passcode for that would send them to fix something that is not broken.
     */
    val isRefusedByServer: Boolean get() = loginOutcome is AprsLogin.Outcome.Unverified

    /**
     * Connect and log in. Blocking; call from a background thread.
     *
     * Throws when the connection cannot be made or the server rejected the login outright.
     * A login the server accepted but did not verify returns normally and leaves
     * [loginOutcome] as [AprsLogin.Outcome.Unverified] for the caller to surface.
     */
    @Throws(Exception::class)
    fun connect() {
        disconnect()
        loginOutcome = null
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.tcpNoDelay = true
            synchronized(lock) {
                socket = s
                writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1), true)
                reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1), 256)
            }
            s.soTimeout = LOGIN_MS
            // The spec has the client log in AFTER the server's identification line, so read the
            // greeting first. Anything starting with # is a comment and may be skipped.
            readGreeting(s)?.let { refusal ->
                // The server refused before we even logged in. Previously this verdict was
                // computed and then overwritten by readLoginResponse, so the branch was a lie.
                loginOutcome = refusal
                throw IllegalArgumentException(refusal.detail)
            }
            val login = AprsLogin.line(callsign, ssid, passcode, version, filter)
            writer?.print(login)
            writer?.print(CRLF)
            writer?.flush()
            loginOutcome = readLoginResponse()
            s.soTimeout = timeoutSec * 1000
            val outcome = loginOutcome
            if (outcome is AprsLogin.Outcome.Rejected) throw IllegalArgumentException(outcome.detail)
        } catch (e: Exception) {
            // Close the local socket before re-throwing so it does not leak when the failure
            // lands after connect() but before the field assignment - otherwise periodic
            // reconnects accumulate file descriptors until no more can be opened.
            runCatching { s.close() }
            synchronized(lock) {
                writer = null
                reader = null
                socket = null
            }
            throw e
        }
    }

    /**
     * Consume the server's greeting comment, returning a refusal when it is not one.
     *
     * Absence is tolerated because some servers send none, but on a short probe window rather
     * than the full login timeout: waiting LOGIN_MS for a greeting that will never come cost
     * eight seconds on every single connect to such a server.
     */
    private fun readGreeting(socket: Socket): AprsLogin.Outcome.Rejected? {
        val previous = socket.soTimeout
        return try {
            socket.soTimeout = GREETING_MS
            val line = reader?.readLine() ?: return null
            // A server that opens with anything but a comment is refusing us.
            if (line.startsWith("#")) null else AprsLogin.Outcome.Rejected(line.trim())
        } catch (ignored: IOException) {
            null
        } finally {
            runCatching { socket.soTimeout = previous }
        }
    }

    /**
     * Read lines until the login verdict arrives, skipping keepalive comments.
     *
     * Bounded by the read timeout, so an unresponsive server cannot hang the caller.
     */
    private fun readLoginResponse(): AprsLogin.Outcome {
        // Bounded by a deadline, not a line count: comments are free to skip, and a chatty
        // server that sent six of them before its verdict used to exhaust a fixed budget and
        // turn an accepted login into Unknown - telling the operator their passcode was wrong
        // when it had just been accepted.
        val deadline = System.currentTimeMillis() + LOGIN_MS
        while (System.currentTimeMillis() < deadline) {
            val line = try {
                reader?.readLine()
            } catch (timeout: SocketTimeoutException) {
                return AprsLogin.Outcome.Unknown("no response within ${LOGIN_MS}ms")
            } catch (failure: IOException) {
                return AprsLogin.Outcome.Rejected(failure.message ?: "login read failed")
            } ?: return AprsLogin.Outcome.Rejected("connection closed during login")
            AprsLogin.parse(line)?.let { return it }
        }
        return AprsLogin.Outcome.Unknown("no login response recognised")
    }

    /**
     * Send one packet and report what happened.
     *
     * Returns null when there is no connection to write to. Otherwise a pair of whether the
     * packet went out and a detail string for the operator.
     */
    fun sendPacket(packetLine: String): Pair<Boolean, String>? {
        synchronized(lock) {
            val w = writer ?: return null
            // Reading the response inside the same lock: disconnect() may run concurrently and
            // null these fields, and reading outside the lock raced with that - the read could
            // hit a just-closed socket and be reported as a successful send.
            // CRLF explicitly rather than println: the spec requires "TNC2 format terminated by
            // a carriage return, line feed sequence", and println emits the platform separator,
            // a bare LF on Android. An earlier draft of this method sent no terminator at all,
            // which leaves the server's line reader waiting forever while every send reports
            // success - exactly the failure this class exists to prevent.
            w.print(packetLine)
            w.print(CRLF)
            w.flush()
            if (w.checkError()) return Pair(false, "write failed")
            val s = socket ?: return Pair(false, "not connected")
            val previousTimeout = s.soTimeout
            return try {
                s.soTimeout = ACK_MS
                classifyAck(reader?.readLine())
            } catch (timeout: SocketTimeoutException) {
                // Silence is the normal case: APRS-IS does not acknowledge a position report, so
                // nothing arriving means the line went out and the server had nothing to say.
                // Telling this apart from a broken connection is the point of this method - the
                // previous version treated EVERY exception as success, so a dead socket reported
                // "sent OK" and made every real failure invisible, including a rejected login.
                Pair(true, "sent")
            } catch (failure: IOException) {
                Pair(false, failure.message ?: "read failed")
            } finally {
                runCatching { s.soTimeout = previousTimeout }
            }
        }
    }

    /** Interpret whatever the server sent back after a packet. */
    private fun classifyAck(response: String?): Pair<Boolean, String> = when {
        response == null -> Pair(false, "connection closed by server")
        // Comments are keepalives and server chatter, not a verdict on this packet.
        response.startsWith("#") -> Pair(true, "sent")
        response.contains("Invalid", ignoreCase = true) ||
            response.contains("error", ignoreCase = true) -> Pair(false, response.trim())
        else -> Pair(true, response.trim())
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

    private companion object {
        /** Line terminator the protocol requires, independent of the platform's own. */
        const val CRLF = "\r\n"

        const val CONNECT_MS = 30_000
        const val LOGIN_MS = 8_000
        const val ACK_MS = 3_000

        /** Probe window for the greeting, short because its absence is legitimate. */
        const val GREETING_MS = 2_000
    }
}
