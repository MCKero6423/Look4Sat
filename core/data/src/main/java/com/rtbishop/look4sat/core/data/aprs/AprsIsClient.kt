package com.rtbishop.look4sat.core.data.aprs

import com.rtbishop.look4sat.core.domain.aprs.AprsPacket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * APRS-IS TCP 客户端(从 APRSdroid TcpUploader.scala 逆向移植)。
 * 纯文本协议:登录一行 + 每行一个包;断线 30 秒重连。
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

    /** 建立连接 + 登录(同步阻塞,调用方放后台线程) */
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
        // 登录行
        val login = AprsPacket.formatLogin(callsign, ssid, passcode, version) + filter
        writer?.println(login)
        // 读登录响应(aprsc 返回 # logresp ... verified/unverified)
        runCatching {
            s.soTimeout = 8000
            val resp = reader?.readLine()
            if (resp != null && (resp.contains("Invalid", ignoreCase = true) ||
                    resp.contains("unverified", ignoreCase = true))) {
                throw IllegalArgumentException(resp.trim())
            }
            // 恢复超时
            s.soTimeout = timeoutSec * 1000
        }
    }

    /**
     * 发送一个 APRS 包(一行)并尝试读服务器确认。
     * 返回 null=发送失败;Pair(ok, detail)=发送结果(服务器错误原文在 detail)
     */
    fun sendPacket(packetLine: String): Pair<Boolean, String>? {
        synchronized(lock) {
            val w = writer ?: return null
            w.println(packetLine)
            if (w.checkError()) return Pair(false, "write failed")
        }
        // 尝试读服务器响应(短超时 3s;APRS-IS 对格式错误会回错误行)
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

    /** 读一行(服务器响应,超时抛异常) */
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
