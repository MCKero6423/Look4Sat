package com.rtbishop.look4sat.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import com.rtbishop.look4sat.MainApplication
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.data.aprs.AprsConfig
import com.rtbishop.look4sat.core.data.aprs.AprsStore
import com.rtbishop.look4sat.core.data.aprs.AprsReporter
import com.rtbishop.look4sat.core.data.aprs.AprsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * APRS foreground service: kept alive by a system notification; keeps working across pages.
 * START_STICKY: auto-restarted after being killed by the system (same strategy as APRSdroid).
 */
class AprsForegroundService : Service() {

    companion object {
        const val ACTION_START = AprsStore.ACTION_START
        const val ACTION_STOP = AprsStore.ACTION_STOP
        const val ACTION_REPORT_NOW = AprsStore.ACTION_REPORT_NOW
        const val CHANNEL_ID = "aprs_service"
        const val NOTIF_ID = 101

        /** Alarm-driven tick, kept separate so a manual report stays distinguishable. */
        const val ACTION_ALARM_TICK = "com.rtbishop.look4sat.APRS_ALARM_TICK"

        private const val ALARM_REQUEST = 4101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reporter: AprsReporter? = null

    /**
     * Handler on the main looper, for anything that must not run on the reporter's IO thread.
     *
     * onReport is invoked from AprsReporter's Dispatchers.IO scope, and Toast construction there
     * throws because that thread has no Looper - an exception the surrounding runCatching then
     * swallowed, so every report notice was silently discarded. The messages existed and no
     * operator ever saw one.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastState: AprsState = AprsState.Idle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopReporting()
            ACTION_ALARM_TICK -> {
                // Woken by the exact alarm. Reporting once and then booking the next tick, rather
                // than using a repeating alarm, means a changed interval takes effect at once.
                if (reporter == null) startReporting() else reporter?.reportNow()
                scheduleNextTick()
            }
            ACTION_REPORT_NOW -> {
                if (reporter == null) {
                    // Service not running: start it first (Toast hint when not configured)
                    startReporting()
                }
                reporter?.reportNow()
            }
            else -> startReporting()
        }
        return START_STICKY
    }

    private fun startReporting() {
        // onStartCommand reaches here for every ACTION_START and for the null
        // intent that START_STICKY delivers on restart. Without this guard each
        // call built a fresh AprsReporter and overwrote the field, leaving the
        // previous one running with its own scope and timer: the server then
        // received one duplicate position report per leaked instance per cycle,
        // and ACTION_STOP could only ever stop the newest one.
        reporter?.let { existing ->
            if (existing.isRunning) return
            existing.stop()
        }
        val cfg = AprsStore.loadConfig(this)
        if (!cfg.enabled || cfg.callsign.isBlank()) {
            runCatching {
                Toast.makeText(this, getString(R.string.aprs_toast_not_configured), Toast.LENGTH_SHORT).show()
            }
            stopSelf()
            return
        }
        startForegroundWithNotification(cfg)
        val rep = AprsReporter(
            configProvider = { AprsStore.loadConfig(this) },
            positionProvider = { stationPosition() },
            onState = { lastState = it },
            onReport = { report ->
                AprsStore.saveLastReport(this, report.ok, report.detail)
                // Derived from this report rather than read from lastState: onState fires AFTER
                // onReport, so the notification was being rebuilt from the previous cycle's
                // verdict. For an alarm-driven report at 03:00 the notification is the only
                // surface that survives, and it was showing the wrong one.
                lastState = if (report.ok) AprsState.Connected else AprsState.Error
                updateNotification(cfg)
                // Report result always surfaces: success = short Toast, failure = long Toast + reason
                // An unverified login needs its own message: the write succeeded, so a bare
                // failure notice would send the operator looking at their network when the
                // problem is the passcode - and APRS-IS is dropping every packet meanwhile.
                val msg = when {
                    report.ok -> getString(R.string.aprs_toast_ok)
                    // Receive-only first: it logs in with -1 exactly as a wrong passcode does and
                    // the server answers "unverified" to both, so without this branch the one safe
                    // way to test a setup reported itself as a configuration error.
                    !report.verified && report.receiveOnly ->
                        getString(R.string.aprs_toast_receive_only)
                    !report.verified -> getString(R.string.aprs_toast_unverified)
                    else -> getString(R.string.aprs_toast_fail, report.detail)
                }
                mainHandler.post {
                    Toast.makeText(this, msg,
                        if (report.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
        )
        reporter = rep
        rep.start()
        // The reporter beacons once on start; the alarm carries every one after that.
        scheduleNextTick()
    }

    private fun stopReporting() {
        cancelTicks()
        reporter?.stop()
        reporter = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(cfg: AprsConfig) {
        try {
            val notif = buildNotification(cfg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Must match the manifest attribute exactly: AOSP checks the passed type is a
                // subset of the declared one and throws otherwise, which the catch below turns
                // into a silent stopSelf(). Declaring location instead would additionally require
                // a granted location permission before this call, and the settings card asks only
                // for notifications - so that combination fails silently too.
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Vendor ROM / old-system safety net: foreground-start failure only stops the service, never crashes the app
            stopSelf()
        }
    }

    private fun buildNotification(cfg: AprsConfig): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, AprsForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stateText = when (lastState) {
            AprsState.Connected -> getString(R.string.aprs_notif_connected)
            AprsState.Error -> getString(R.string.aprs_notif_error)
            else -> getString(R.string.aprs_notif_running)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radios)
            .setContentTitle(getString(R.string.aprs_notif_title, cfg.callsign))
            .setContentText(stateText)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, getString(R.string.aprs_notif_stop), stopPi)
            .build()
    }

    private fun updateNotification(cfg: AprsConfig) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(cfg))
    }

    /** Report position: station QTH from settings first (per user); live GPS as fallback when invalid */
    private fun stationPosition(): Pair<Double, Double>? {
        // 1. Station QTH (position set in settings)
        val station = runCatching {
            val container = (application as MainApplication).getMainContainer()
            container.settingsRepo.stationPosition.value
        }.getOrNull()
        if (station != null && (station.latitude != 0.0 || station.longitude != 0.0)) {
            return Pair(station.latitude, station.longitude)
        }
        // 2. Fallback: last live GPS position
        return runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )
            for (p in providers) {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                    return Pair(loc.latitude, loc.longitude)
                }
            }
            null
        }.getOrNull()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.aprs_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        reporter?.stop()
        reporter = null
        super.onDestroy()
    }

    /**
     * Book the next beacon with an exact alarm.
     *
     * A coroutine delay was used before, which Doze defeats: the timer fires but network access is
     * suspended and wake locks are ignored, even inside a foreground service. Only
     * setExactAndAllowWhileIdle survives that, and the five-minute floor keeps this well clear of
     * the system's throttle on how often such an alarm may repeat.
     */
    private fun scheduleNextTick() {
        val cfg = AprsStore.loadConfig(this)
        if (!cfg.enabled) return
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val minutes = cfg.intervalMin.coerceAtLeast(AprsReporter.MIN_INTERVAL_MIN)
        val at = System.currentTimeMillis() + minutes * 60_000L
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent())
            } else {
                // The operator revoked exact alarms. An inexact one still beacons, just whenever
                // the system decides, which beats not beaconing at all.
                alarms.set(AlarmManager.RTC_WAKEUP, at, tickIntent())
            }
        }
    }

    private fun cancelTicks() {
        val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.cancel(tickIntent()) }
    }

    private fun tickIntent(): PendingIntent = PendingIntent.getService(
        this,
        ALARM_REQUEST,
        Intent(this, AprsForegroundService::class.java).setAction(ACTION_ALARM_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

}
