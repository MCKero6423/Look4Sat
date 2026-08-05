/*
 * WavelogUploader.kt - WaveLog queue upload scheduler (4.5.2).
 *
 * Manual/periodic uploads share: per-batch grid check (user QTH first 4 chars vs station grid first 4 chars)
 * -> POST /api/v2/qso -> success removes from the queue.
 * Grid mismatch: returns NeedConfirm (UI dialog "Ignore and upload / Cancel"),
 * retrying the batch with force=true once confirmed.
 */
package com.rtbishop.look4sat.core.domain.wavelog

import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import org.json.JSONObject

sealed class UploadOutcome {
    data class NeedConfirm(val stationGrid: String, val userGrid: String) : UploadOutcome()
    data class Done(
        val successCount: Int,
        val failedCount: Int,
        val message: String,
        val firstError: String = ""
    ) : UploadOutcome()
}

class WavelogUploader(
    private val settingsRepo: ISettingsRepo,
    private val queue: WavelogQueue
) {

    // Station grid cache (refreshed before each upload; stale value kept on failure)
    private var cachedStationGrid: String? = null

    /** Upload the whole queue. force=true skips the grid confirm (user chose "Ignore and upload") */
    suspend fun uploadQueue(force: Boolean = false): UploadOutcome {
        val settings = settingsRepo.otherSettings.value
        val url = settings.wavelogUrl
        val apiKey = settings.wavelogApiKey
        val stationId = settings.wavelogStationId
        if (url.isBlank() || apiKey.isBlank() || stationId.isBlank()) {
            return UploadOutcome.Done(0, queue.all().size, "未配置 WaveLog 服务器")
        }

        // 1. Fetch station info (station grid); fall back to user QTH when v1 lacks the endpoint
        val stationGrid = getStationGrid(url, apiKey, stationId) ?: userQthGrid()
        if (stationGrid.isNullOrBlank()) {
            return UploadOutcome.Done(0, queue.all().size, "无法获取站点信息(检查站点 ID/密钥权限)")
        }

        // 2. Grid check: cloud station grid first 4 chars vs current station QTH first 4 chars
        //   (guards against a misconfigured station; unrelated to the QSO counterpart grid - per user)
        if (!force) {
            val userGrid = userQthGrid()
            if (userGrid != null && stationGrid.take(4).lowercase() != userGrid.take(4).lowercase()) {
                return UploadOutcome.NeedConfirm(stationGrid, userGrid)
            }
        }

        // 3. Upload one by one. ADIF gridsquare = counterpart grid (the QSO partner); blank until the scraper lands
        val entries = queue.all()
        var ok = 0
        var fail = 0
        var firstError = ""
        for (qso in entries) {
            if (qso.uploaded) { ok++; continue }
            val result = WaveLogApi.postQso(url, apiKey, stationId, qso, qso.gridsquare)
            if (result is WavelogResult.Success) {
                ok++
                queue.markUploaded(qso.id)
            } else {
                fail++
                if (firstError.isBlank()) firstError = (result as? WavelogResult.Failure)?.message ?: ""
            }
        }
        val message = if (fail == 0) "成功上传 $ok 条" else "成功 $ok 条, 失败 $fail 条(保留待重试)"
        return UploadOutcome.Done(ok, fail, message, firstError)
    }

    private suspend fun getStationGrid(url: String, apiKey: String, stationId: String): String? {
        val result = WaveLogApi.getStation(url, apiKey, stationId)
        if (result is WavelogResult.Success) {
            return try {
                JSONObject(result.message).optString("gridsquare").takeIf { it.isNotBlank() }
                    ?: cachedStationGrid
            } catch (_: Exception) { cachedStationGrid }
        }
        return cachedStationGrid
    }

    /** User's current QTH grid (first 4 chars; null when no QTH = check skipped) */
    private fun userQthGrid(): String? {
        return settingsRepo.stationPosition.value.qthLocator?.takeIf { it.length >= 4 }
    }
}
