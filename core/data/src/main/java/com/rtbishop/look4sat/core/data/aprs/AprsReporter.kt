package com.rtbishop.look4sat.core.data.aprs

import com.rtbishop.look4sat.core.domain.aprs.AprsPacket
import com.rtbishop.look4sat.core.domain.aprs.AprsPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** APRS 连接状态 */
enum class AprsState { Idle, Connecting, Connected, Disconnected, Error }

/** APRS 配置(SharedPreferences 持久化,填入即保存) */
data class AprsConfig(
    val enabled: Boolean = false,
    val server: String = "euro.aprs2.net",
    val port: Int = 14580,
    val callsign: String = "",
    val ssid: String = "",
    val passcode: String = "",
    val intervalMin: Int = 5,
    val statusText: String = "Look4Sat APRS",
    val symbolTable: String = "/",
    val symbolCode: String = ">",
    val includeCourseSpeed: Boolean = true,
    val includeAltitude: Boolean = true
)

/** APRS 上报结果 */
data class AprsReport(
    val timestamp: Long,
    val packet: String,
    val ok: Boolean,
    val detail: String
)

/** 上报调度器(周期上报 + 手动触发),连接管理放在前台服务 */
class AprsReporter(
    private val configProvider: () -> AprsConfig,
    private val positionProvider: () -> Pair<Double, Double>? = { null },
    private val onState: (AprsState) -> Unit = {},
    private val onReport: (AprsReport) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: AprsIsClient? = null
    private var job: Job? = null
    private var manualJob: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** 启动周期上报(前台服务调用) */
    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled || cfg.callsign.isBlank()) {
            onState(AprsState.Error)
            return
        }
        job = scope.launch {
            while (isActive) {
                reportOnce()
                delay(cfg.intervalMin.coerceAtLeast(1) * 60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { client?.disconnect() }
        client = null
        onState(AprsState.Idle)
    }

    /** 手动触发一次(立即上报,不等待周期) */
    fun reportNow() {
        manualJob?.cancel()
        manualJob = scope.launch { reportOnce() }
    }

    private suspend fun reportOnce() {
        val cfg = configProvider()
        if (!cfg.enabled || cfg.callsign.isBlank()) return
        onState(AprsState.Connecting)
        try {
            val c = client ?: AprsIsClient(
                host = cfg.server,
                port = cfg.port,
                callsign = cfg.callsign,
                ssid = cfg.ssid,
                passcode = cfg.passcode.toIntOrNull()?.takeIf { it >= 0 } ?: AprsPacket.passcode(cfg.callsign),
                version = "Look4Sat 4.5.4"
            ).also { client = it }
            if (!c.isConnected) c.connect()
            onState(AprsState.Connected)

            val pos = positionProvider()
            val packetLine = buildPositionPacket(cfg, pos?.first, pos?.second)
            val ok = c.sendPacket(packetLine)
            onReport(AprsReport(System.currentTimeMillis(), packetLine, ok,
                if (ok) "OK" else "send failed"))
            if (ok) onState(AprsState.Connected) else onState(AprsState.Error)
        } catch (e: Exception) {
            runCatching { client?.disconnect() }
            client = null
            onState(AprsState.Error)
            onReport(AprsReport(System.currentTimeMillis(), "", false, e.message ?: "error"))
        }
    }

    /** 构造位置包:BG7NTA-5>APRS:=DDMM.MMN/DDDMM.MME<状态文本 */
    private fun buildPositionPacket(cfg: AprsConfig, lat: Double? = null, lon: Double? = null): String {
        val source = AprsPacket.formatCallSsid(cfg.callsign, cfg.ssid)
        val pos = AprsPosition(
            latitude = lat ?: 0.0,
            longitude = lon ?: 0.0,
            symbolTable = cfg.symbolTable.firstOrNull() ?: '/',
            symbolCode = cfg.symbolCode.firstOrNull() ?: '>'
        )
        return "$source>APRS:=${pos.toUncompressedString()}${cfg.statusText}"
    }
}
