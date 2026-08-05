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

/** APRS connection state */
enum class AprsState { Idle, Connecting, Connected, Disconnected, Error }

/** APRS config (persisted in SharedPreferences, saved as filled) */
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

/** APRS report result */
data class AprsReport(
    val timestamp: Long,
    val packet: String,
    val ok: Boolean,
    val detail: String
)

/** Report scheduler (periodic + manual trigger); connection management lives in the foreground service */
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

    /** Start periodic reporting (called by the foreground service) */
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

    /** Trigger one report manually (immediately, without waiting for the cycle) */
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
            val result = c.sendPacket(packetLine)
            val ok = result?.first == true
            val detail = result?.second ?: "no connection"
            onReport(AprsReport(System.currentTimeMillis(), packetLine, ok, detail))
            if (ok) onState(AprsState.Connected) else onState(AprsState.Error)
        } catch (e: Exception) {
            runCatching { client?.disconnect() }
            client = null
            onState(AprsState.Error)
            onReport(AprsReport(System.currentTimeMillis(), "", false, e.message ?: "error"))
        }
    }

    /** Build position packet: BG7NTA-5>APRS:=DDMM.MMN/DDDMM.MME<status text */
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
