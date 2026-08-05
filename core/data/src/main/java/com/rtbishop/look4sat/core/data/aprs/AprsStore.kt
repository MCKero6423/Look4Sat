package com.rtbishop.look4sat.core.data.aprs

import android.content.Context

/**
 * APRS 配置存取 + 服务 action 常量(被 feature/settings 和 app 服务共用,
 * 避免 feature 反向依赖 app)。
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

    /** 读取配置(填入即保存,无需每次打开重填) */
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
            symbolCode = p.getString(KEY_SYMBOL_CODE, ">") ?: ">"
        )
    }

    /** 保存配置 */
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
