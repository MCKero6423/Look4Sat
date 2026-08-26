package com.rtbishop.look4sat.core.data.aprs

import android.content.Context

/**
 * APRS config storage + service action constants (shared by feature/settings and the app service,
 * so feature never depends on app).
 */
object AprsStore {

    const val ACTION_START = "com.rtbishop.look4sat.aprs.START"
    const val ACTION_STOP = "com.rtbishop.look4sat.aprs.STOP"
    const val ACTION_REPORT_NOW = "com.rtbishop.look4sat.aprs.REPORT_NOW"
    const val SERVICE_CLASS = "com.rtbishop.look4sat.app.AprsForegroundService"

    private const val PREFS = "aprs_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server"
    private const val KEY_PORT = "port"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_SSID = "ssid"
    private const val KEY_PASSCODE = "passcode"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_STATUS = "status"
    private const val KEY_SYMBOL_TABLE = "symbol_table"
    private const val KEY_SYMBOL_CODE = "symbol_code"

    /**
     * A house, not a car.
     *
     * Only fresh installs see this: saveConfig writes every key unconditionally and the enable
     * switch calls it, so anyone who has ever turned APRS on has both symbol keys on disk and this
     * fallback cannot reach them.
     */
    private const val DEFAULT_SYMBOL_CODE = "-"
    private const val KEY_LAST_TIME = "last_report_time"
    private const val KEY_LAST_OK = "last_report_ok"
    private const val KEY_LAST_DETAIL = "last_report_detail"

    /** Read config (saved as filled; no need to re-enter each time) */
    fun loadConfig(context: Context): AprsConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AprsConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            server = p.getString(KEY_SERVER, "euro.aprs2.net") ?: "euro.aprs2.net",
            port = p.getInt(KEY_PORT, 14580),
            callsign = p.getString(KEY_CALLSIGN, "") ?: "",
            ssid = p.getString(KEY_SSID, "") ?: "",
            passcode = p.getString(KEY_PASSCODE, "") ?: "",
            intervalMin = p.getInt(KEY_INTERVAL, 5),
            statusText = p.getString(KEY_STATUS, "Look4Sat APRS") ?: "Look4Sat APRS",
            symbolTable = p.getString(KEY_SYMBOL_TABLE, "/") ?: "/",
            symbolCode = p.getString(KEY_SYMBOL_CODE, DEFAULT_SYMBOL_CODE) ?: DEFAULT_SYMBOL_CODE
        )
    }

    /** Last report result (shown on the settings card) */
    data class LastReport(val time: Long = 0L, val ok: Boolean = false, val detail: String = "")

    fun loadLastReport(context: Context): LastReport {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LastReport(
            time = p.getLong(KEY_LAST_TIME, 0L),
            ok = p.getBoolean(KEY_LAST_OK, false),
            detail = p.getString(KEY_LAST_DETAIL, "") ?: ""
        )
    }

    fun saveLastReport(context: Context, ok: Boolean, detail: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .putBoolean(KEY_LAST_OK, ok)
            .putString(KEY_LAST_DETAIL, detail)
            .apply()
    }

    /** Save config */
    fun saveConfig(context: Context, cfg: AprsConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_SERVER, cfg.server)
            .putInt(KEY_PORT, cfg.port)
            .putString(KEY_CALLSIGN, cfg.callsign)
            .putString(KEY_SSID, cfg.ssid)
            .putString(KEY_PASSCODE, cfg.passcode)
            .putInt(KEY_INTERVAL, cfg.intervalMin)
            .putString(KEY_STATUS, cfg.statusText)
            .putString(KEY_SYMBOL_TABLE, cfg.symbolTable)
            .putString(KEY_SYMBOL_CODE, cfg.symbolCode)
            .apply()
    }
}
