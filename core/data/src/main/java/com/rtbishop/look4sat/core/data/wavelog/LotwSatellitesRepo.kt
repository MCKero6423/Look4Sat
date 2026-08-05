/* LotwSatellitesRepo.kt - runtime refresh of the LoTW satellite list (4.5.5).
 * Downloads ARRL's official config.tq6 (gzip XML), parses <satellite name="...">,
 * updates the LotwSatellites dynamic set + persists to SharedPreferences (survives restarts).
 * Trigger: the "Update sats" button in the settings WaveLog section (user decided: manual).
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

    /** Restore the last downloaded list from SharedPreferences (call at process start) */
    override fun restore() {
        val saved = prefs.getString(KEY_NAMES, null)
        if (!saved.isNullOrBlank()) {
            LotwSatellites.updateNames(saved.split(",").filter { it.isNotBlank() }.toSet())
        }
    }

    /** Download config.tq6 -> parse -> update memory + persist. Result is surfaced in the UI */
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
            // Parse <satellite name="XXX" ...> - fixed format (official ARRL XML)
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
