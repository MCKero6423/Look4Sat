package com.rtbishop.look4sat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
 * APRS 前台服务:系统通知保活,切换页面不断线。
 * START_STICKY:被系统杀掉后自动重启(APRSdroid 同款策略)。
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
            ACTION_REPORT_NOW -> reporter?.reportNow()
            else -> startReporting()
        }
        return START_STICKY
    }

    private fun startReporting() {
        val cfg = AprsStore.loadConfig(this)
        if (!cfg.enabled || cfg.callsign.isBlank()) {
            stopSelf()
            return
        }
        startForegroundWithNotification(cfg)
        val rep = AprsReporter(
            configProvider = { AprsStore.loadConfig(this) },
            positionProvider = { lastKnownPosition() },
            onState = { lastState = it },
            onReport = { updateNotification(cfg) }
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
            // 厂商 ROM / 旧系统兼容兜底:启动前台失败只停服务,不崩进程
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

    /** 取最近已知位置(APRSdroid PeriodicGPS 同思路,无权限时返回 null) */
    private fun lastKnownPosition(): Pair<Double, Double>? {
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
