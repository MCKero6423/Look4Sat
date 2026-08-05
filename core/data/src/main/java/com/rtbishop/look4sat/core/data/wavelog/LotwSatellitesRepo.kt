/* LotwSatellitesRepo.kt — LoTW 卫星列表运行时刷新(4.5.5)。
 * 下载 ARRL 官方 config.tq6(gzip XML), 解析 <satellite name="...">,
 * 更新 LotwSatellites 动态表 + SharedPreferences 持久化(重启不丢)。
 * 触发: 设置页 WaveLog 区「更新卫星列表」按钮(用户拍板: 手动触发)。
 */
package com.rtbishop.look4sat.core.data.wavelog

import android.content.Context
import com.rtbishop.look4sat.core.domain.wavelog.ILotwSatellitesRepo
import com.rtbishop.look4sat.core.domain.wavelog.LotwSatellites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.random.Random

class LotwSatellitesRepo(private val context: Context) : ILotwSatellitesRepo {

    private val prefs = context.getSharedPreferences("lotw_satellites", Context.MODE_PRIVATE)

    /** 从 SharedPreferences 恢复上次下载的表(进程启动时调用) */
    override fun restore() {
        val saved = prefs.getString(KEY_NAMES, null)
        if (!saved.isNullOrBlank()) {
            LotwSatellites.updateNames(saved.split(",").filter { it.isNotBlank() }.toSet())
        }
    }

    /** 下载 config.tq6 → 解析 → 更新内存 + 持久化。返回结果供 UI 提示 */
    override suspend fun refresh(): ILotwSatellitesRepo.RefreshResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://lotw.arrl.org/lotw/config.tq6")
            val conn = url.openConnection()
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Look4Sat-Pro/4.5.5")
            val raw = conn.getInputStream()
            val reader = BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8))
            val text = reader.use { it.readText() }
            // 解析 <satellite name="XXX" ...> — 格式固定(ARRL 官方 XML)
            val names = Regex("""<satellite name="([^"]+)""").findAll(text)
                .map { it.groupValues[1].uppercase() }
                .toSet()
            if (names.isEmpty()) return@withContext ILotwSatellitesRepo.RefreshResult.Error("列表为空(响应异常)")
            LotwSatellites.updateNames(names)
            prefs.edit()
                .putString(KEY_NAMES, names.joinToString(","))
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply()
            ILotwSatellitesRepo.RefreshResult.Ok(names.size)
        } catch (e: Exception) {
            ILotwSatellitesRepo.RefreshResult.Error(e.message ?: "未知错误")
        }
    }

    companion object {
        private const val KEY_NAMES = "names"
        private const val KEY_UPDATED = "updated"
    }
}
