package com.rtbishop.look4sat.core.data.aprs

import com.rtbishop.look4sat.core.domain.aprs.AprsBeacon
import com.rtbishop.look4sat.core.domain.aprs.AprsPasscode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val detail: String,
    /**
     * False only when the server told us it did not verify the login.
     *
     * Carried separately from [ok] because the two are independent: a refused client's writes
     * still succeed, so the packet leaves the phone and looks sent, while aprsc discards every
     * one of them. Without surfacing it the operator can watch reports succeed for hours with
     * nothing reaching the network. A login whose response we simply could not parse leaves this
     * true, since the packets may be landing and the passcode is not at fault.
     */
    val verified: Boolean = true,
    /**
     * True when the operator asked for a receive-only connection.
     *
     * Distinguished from a refused login because both log in with -1 and the server answers
     * "unverified" to each: without this, deliberately choosing receive-only - the one way to
     * test a setup without putting anything on the network - was reported as a wrong passcode
     * and sent the operator to fix something they had set on purpose.
     */
    val receiveOnly: Boolean = false
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
        // One report now; the service's exact alarm drives every one after this. The loop that
        // used to live here relied on a coroutine delay, which Doze defeats - the timer fires on
        // schedule and then finds network access suspended, so the beacon stopped whenever the
        // screen locked while the notification still claimed it was running.
        job = scope.launch { reportOnce() }
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
            // The packet is built BEFORE connecting: there is no reason to open a session and log
            // in only to discover there is nothing to send, which happened every five minutes for
            // an operator whose QTH was unset.
            val pos = positionProvider()
            val beacon = AprsBeacon.build(
                callsign = cfg.callsign,
                ssid = cfg.ssid,
                latitude = pos?.first,
                longitude = pos?.second,
                symbolTable = cfg.symbolTable,
                symbolCode = cfg.symbolCode,
                comment = cfg.statusText
            )
            // Nothing goes out without a position. Substituting 0,0 put this station in the Gulf
            // of Guinea on the global network, under the operator's own callsign.
            if (beacon is AprsBeacon.Result.Blocked) {
                onState(AprsState.Error)
                onReport(
                    AprsReport(System.currentTimeMillis(), "", false, refusalDetail(beacon.refusal))
                )
                return
            }
            val packetLine = (beacon as AprsBeacon.Result.Line).text

            // Receive-only is a deliberate choice, not a mistake, and has to be carried through:
            // it logs in with -1 exactly as a wrong passcode does, and the server answers
            // "unverified" to both.
            val wantsReceiveOnly = !AprsPasscode.canTransmit(cfg.callsign, cfg.passcode)
            val c = client ?: AprsIsClient(
                host = cfg.server,
                port = cfg.port,
                callsign = cfg.callsign,
                ssid = cfg.ssid,
                // Never derives one: a blank or wrong entry logs in receive-only rather than
                // transmitting under a passcode the app invented for an unchecked licence.
                passcode = AprsPasscode.loginValue(cfg.callsign, cfg.passcode),
                version = "Look4Sat 4.5.4"
            ).also { client = it }
            if (!c.isConnected) c.connect()
            onState(AprsState.Connected)
            val result = c.sendPacket(packetLine)
            val sent = result?.first == true
            val detail = result?.second ?: "no connection"
            // A write that succeeded on a login the server refused to verify is not a delivered
            // packet: aprsc takes it and drops it, which is what let every real failure hide.
            // Only an explicit refusal counts against us though - a login whose response we
            // could not parse may be working fine, and blaming the passcode for that would send
            // the operator to fix something that is not broken.
            val refused = c.isRefusedByServer
            val ok = sent && !refused
            // "sent" is the write's own verdict and reads as nonsense next to a failure - the card
            // showed "failed - sent" for a refused login. When the refusal is what failed the
            // report, say that instead.
            val reported = when {
                ok -> detail
                refused && wantsReceiveOnly -> "receive-only, not forwarded"
                refused -> "login not verified"
                else -> detail
            }
            onReport(
                AprsReport(
                    System.currentTimeMillis(), packetLine, ok, reported,
                    verified = !refused, receiveOnly = wantsReceiveOnly
                )
            )
            if (ok) onState(AprsState.Connected) else onState(AprsState.Error)
        } catch (e: Exception) {
            runCatching { client?.disconnect() }
            client = null
            onState(AprsState.Error)
            onReport(AprsReport(System.currentTimeMillis(), "", false, e.message ?: "error", false))
        }
    }


    /** A short reason for a refusal, for the operator's last-report line. */
    private fun refusalDetail(refusal: AprsBeacon.Refusal): String = when (refusal) {
        AprsBeacon.Refusal.NoPosition -> "no position yet"
        AprsBeacon.Refusal.NoCallsign -> "no callsign set"
        is AprsBeacon.Refusal.ImpossiblePosition -> "position out of range"
    }




    companion object {

        /** Floor for the reporting interval, in minutes. */
        const val MIN_INTERVAL_MIN = 5
    }

}
