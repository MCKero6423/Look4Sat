package com.rtbishop.look4sat.core.data.aprs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Socket-level tests against a stand-in APRS-IS server.
 *
 * These exist because the pure-logic tests could not catch the failures that matter here. A draft
 * of [AprsIsClient.sendPacket] once wrote the packet with no line terminator at all: every send
 * reported success, the server received a single unterminated stream, and nothing anywhere went
 * red. APRS-IS is a line protocol and does not acknowledge position reports, so silence is the
 * normal case - which means only a test that reads the bytes off a real socket can tell a
 * delivered packet from a lost one.
 */
class AprsIsClientSocketTest {

    /**
     * A minimal APRS-IS server. Greets, answers the login as instructed, then records whatever
     * lines arrive without acknowledging them, which is what the real network does.
     */
    private class FakeServer(
        private val greeting: String? = "# aprsc 2.1.19-g730c5c0",
        private val loginResponse: String? = "# logresp TEST verified, server FAKE",
        private val chatter: List<String> = emptyList()
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val ready = CountDownLatch(1)

        /**
         * The accepted connection. Held because closing the ServerSocket only stops it listening
         * - an established connection survives, so a test that wants a dead peer has to close
         * this one. Getting that wrong made a correct implementation look broken.
         */
        @Volatile
        private var peer: Socket? = null

        val port: Int get() = server.localPort

        /** Every complete line the client sent after logging in. */
        val received = mutableListOf<String>()

        /** Raw bytes of the client's traffic, so a missing terminator is visible. */
        val rawAfterLogin = StringBuilder()

        @Volatile
        var loginLine: String? = null

        fun start() {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        peer = client
                        val out = PrintWriter(client.getOutputStream(), true)
                        val input = BufferedReader(InputStreamReader(client.getInputStream()))
                        greeting?.let { out.print(it + "\r\n"); out.flush() }
                        loginLine = input.readLine()
                        chatter.forEach { out.print(it + "\r\n"); out.flush() }
                        loginResponse?.let { out.print(it + "\r\n"); out.flush() }
                        ready.countDown()
                        // Read lines but never acknowledge, exactly as APRS-IS treats positions.
                        while (true) {
                            val line = input.readLine() ?: break
                            synchronized(received) {
                                received += line
                                rawAfterLogin.append(line)
                            }
                        }
                    }
                }
                ready.countDown()
            }
        }

        fun awaitLogin(): Boolean = ready.await(5, TimeUnit.SECONDS)

        fun lines(): List<String> = synchronized(received) { received.toList() }

        /** Close the established connection, so the client is talking to a dead peer. */
        fun dropClient() {
            runCatching { peer?.close() }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun client(port: Int, passcode: Int = 12345) = AprsIsClient(
        host = "127.0.0.1",
        port = port,
        callsign = "TEST",
        ssid = "",
        passcode = passcode,
        softwareName = "Look4Sat",
        version = "test"
    )

    /**
     * The regression that motivated this file. Two packets must arrive as two lines; without a
     * terminator they concatenate into one stream the server can never parse, while both sends
     * report success.
     */
    @Test
    fun `each packet arrives as its own line`() {
        FakeServer().use { server ->
            server.start()
            val c = client(server.port)
            c.connect()
            assertTrue(server.awaitLogin())
            val first = c.sendPacket("TEST>APRS,TCPIP*:=0000.00N/00000.00E>one")
            val second = c.sendPacket("TEST>APRS,TCPIP*:=0000.00N/00000.00E>two")
            Thread.sleep(300)
            c.disconnect()

            assertEquals(true, first?.first)
            assertEquals(true, second?.first)
            val lines = server.lines()
            assertEquals("both packets must reach the server as separate lines", 2, lines.size)
            assertTrue(lines[0].endsWith(">one"))
            assertTrue(lines[1].endsWith(">two"))
        }
    }

    /** The login line has to be terminated too, or the server never reads it. */
    @Test
    fun `the server receives a complete login line`() {
        FakeServer().use { server ->
            server.start()
            val c = client(server.port)
            c.connect()
            assertTrue(server.awaitLogin())
            c.disconnect()
            assertEquals("user TEST pass 12345 vers Look4Sat test", server.loginLine)
        }
    }

    /** A verified login is recognised and lets reports count as delivered. */
    @Test
    fun `a verified login is not reported as refused`() {
        FakeServer().use { server ->
            server.start()
            val c = client(server.port)
            c.connect()
            assertTrue(server.awaitLogin())
            assertTrue(c.isVerified)
            assertFalse(c.isRefusedByServer)
            c.disconnect()
        }
    }

    /**
     * The case the rewrite exists for: the server accepts the connection, the write succeeds,
     * and every packet is discarded. The client must say so rather than report success.
     */
    @Test
    fun `an unverified login is flagged while the connection stays up`() {
        FakeServer(loginResponse = "# logresp TEST unverified, server FAKE").use { server ->
            server.start()
            val c = client(server.port, passcode = -1)
            c.connect()
            assertTrue(server.awaitLogin())
            assertFalse("unverified must not read as verified", c.isVerified)
            assertTrue("the server explicitly refused", c.isRefusedByServer)
            // Not a connection error: a receive-only login is legitimate and stays connected.
            assertTrue(c.isConnected)
            c.disconnect()
        }
    }

    /**
     * A chatty server used to exhaust a fixed line budget, turning an accepted login into
     * Unknown and telling the operator their passcode was wrong when it had been accepted.
     */
    @Test
    fun `keepalive chatter before the verdict does not hide it`() {
        val chatter = List(8) { "# keepalive $it" }
        FakeServer(chatter = chatter).use { server ->
            server.start()
            val c = client(server.port)
            c.connect()
            assertTrue(server.awaitLogin())
            assertTrue("the verdict must be found past the comments", c.isVerified)
            c.disconnect()
        }
    }

    /**
     * A server that sends no greeting is legitimate, and must not cost the full login window on
     * every connect - that was eight seconds per attempt.
     */
    @Test
    fun `a server without a greeting connects promptly`() {
        FakeServer(greeting = null).use { server ->
            server.start()
            val c = client(server.port)
            val started = System.currentTimeMillis()
            c.connect()
            assertTrue(server.awaitLogin())
            val elapsed = System.currentTimeMillis() - started
            c.disconnect()
            assertTrue("connect took ${elapsed}ms, expected well under the login window",
                elapsed < 6_000)
        }
    }

    /**
     * The failure a live server actually produced, and the one that mattered most.
     *
     * aprsc answers `# Invalid login: ...` and closes. That is a comment but not a logresp, so it
     * was skipped as chatter, the login timed out into Unknown - treated as "may be working" - and
     * every send afterwards reported success. Measured against euro.aprs2.net before the fix:
     * loginOutcome=Unknown, isRefusedByServer=false, sendPacket=(true, "sent").
     */
    @Test
    fun `a refused login is not reported as a successful send`() {
        FakeServer(loginResponse = "# Invalid login: bad software version").use { server ->
            server.start()
            val c = client(server.port)
            // An outright refusal throws from connect(), which is the correct outcome.
            val threw = runCatching { c.connect() }.exceptionOrNull()
            assertTrue(server.awaitLogin())
            assertFalse("a refused login must not read as verified", c.isVerified)
            val sentOk = runCatching {
                c.sendPacket("TEST>APRS,TCPIP*:=0000.00N/00000.00E>x")?.first
            }.getOrNull()
            assertTrue(
                "the refusal must surface: threw=$threw sentOk=$sentOk",
                threw != null || sentOk != true
            )
            c.disconnect()
        }
    }

    /** Sending after the server has gone must report failure, not success. */
    @Test
    fun `a send after the server closes is reported as failed`() {
        val server = FakeServer()
        server.start()
        val c = client(server.port)
        c.connect()
        assertTrue(server.awaitLogin())
        // Closing the ServerSocket alone would leave this connection alive.
        server.dropClient()
        Thread.sleep(200)
        // The first write may still land in the socket buffer; by the second the loss is certain.
        c.sendPacket("TEST>APRS,TCPIP*:=0000.00N/00000.00E>one")
        val second = c.sendPacket("TEST>APRS,TCPIP*:=0000.00N/00000.00E>two")
        c.disconnect()
        assertEquals("a send on a dead connection must not report success", false, second?.first)
    }
}
