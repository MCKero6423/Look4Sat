package com.rtbishop.look4sat.app

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reporter: AprsReporter? = null
    private var lastState: AprsState = AprsState.Idle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopReporting()
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
                updateNotification(cfg)
                // Report result always surfaces: success = short Toast, failure = long Toast + reason
                val msg = if (report.ok) {
                    getString(R.string.aprs_toast_ok)
                } else {
                    getString(R.string.aprs_toast_fail, report.detail)
                }
                runCatching {
                    Toast.makeText(this, msg,
                        if (report.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
        )
        reporter = rep
        rep.start()
    }

    private fun stopReporting() {
        reporter?.stop()
        reporter = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(cfg: AprsConfig) {
        try {
            val notif = buildNotification(cfg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
}
