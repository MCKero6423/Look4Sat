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

    /**
     * The upload finished. [failedCount] entries stay in the queue.
     *
     * [reason] is machine-readable so the UI can pick its own wording; [firstError] carries the
     * server's own explanation for the first failure, which is worth showing verbatim because it
     * is the only thing that says WHY Wavelog refused a QSO.
     */
    data class Done(
        val successCount: Int,
        val failedCount: Int,
        val reason: Reason = Reason.COMPLETED,
        val firstError: String = ""
    ) : UploadOutcome()

    /** Why an upload ended, for the UI to phrase. */
    enum class Reason {
        /** Ran to completion. Check the counts. */
        COMPLETED,

        /** No server, key or station id configured. */
        NOT_CONFIGURED,

        /** The station profile could not be read - wrong id, or a key without permission. */
        NO_STATION_INFO
    }
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
            return UploadOutcome.Done(0, queue.all().size, UploadOutcome.Reason.NOT_CONFIGURED)
        }

        // 1. Fetch station info (station grid); fall back to user QTH when v1 lacks the endpoint
        val stationGrid = getStationGrid(url, apiKey, stationId) ?: userQthGrid()
        if (stationGrid.isNullOrBlank()) {
            return UploadOutcome.Done(0, queue.all().size, UploadOutcome.Reason.NO_STATION_INFO)
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
        var uploaded = 0
        var failed = 0
        var firstError = ""
        for (qso in entries) {
            // Entries already confirmed by the server are skipped, and NOT counted: adding them to
            // the total made a re-run report "N uploaded" for QSOs that went up days ago.
            if (qso.uploaded) continue
            val result = WaveLogApi.postQso(url, apiKey, stationId, qso, qso.gridsquare)
            if (result is WavelogResult.Success) {
                uploaded++
                queue.markUploaded(qso.id)
            } else {
                failed++
                if (firstError.isBlank()) firstError = (result as? WavelogResult.Failure)?.message ?: ""
            }
        }
        return UploadOutcome.Done(uploaded, failed, UploadOutcome.Reason.COMPLETED, firstError)
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
