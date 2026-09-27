/*
 * WavelogQueue.kt - WaveLog local log queue (4.5.2).
 *
 * Pure Kotlin (no Android deps): storage goes through the IWavelogQueueStore interface,
 * implemented with SharedPreferences in core/data.
 * Queue capped at 500 entries (oldest dropped beyond that).
 */
package com.rtbishop.look4sat.core.domain.wavelog

import com.rtbishop.look4sat.core.domain.utility.synchronizedOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Storage abstraction (SharedPreferences impl lives in core/data) */
interface IWavelogQueueStore {
    fun load(): String
    fun save(json: String)
}

/** QSO entry awaiting upload (local queue element, mirrors POST /api/v2/qso fields) */
data class WavelogQso(
    val id: String,              // 本地唯一 id(UUID)
    val timeUtcMs: Long,         // 回车时刻 UTC 毫秒(本地显示 + 组装 qso_date/time_on)
    val call: String,
    val mode: String,
    val freqTxHz: Long,          // 上行(回车那一秒多普勒修正)
    val freqRxHz: Long,          // 下行
    val satName: String,
    /**
     * NORAD catalogue number of the satellite, or 0 when it was not recorded.
     *
     * Carried because the name alone cannot decide the LoTW spelling - sources disagree, and
     * the same satellite named two ways would upload two ways. Zero means a QSO logged before
     * this field existed; those fall back to resolving from the name.
     */
    val catnum: Int = 0,
    val sessionId: String = "",  // 场次 ID: 卫星名-AOS 时间戳(过境仰角 0 秒), 空=未分组(旧数据)
    val gridsquare: String = "", // 对方网格(QRZ 爬虫填入, 4.5.5), 空=未查到
    val uploaded: Boolean = false // 是否已成功上传(4.5.2 修复: 成功后保留标记, 表格打勾)
)

/**
 * Every mutator is a read-modify-write over the single stored blob and serialises on a private
 * monitor, so the Compose thread and the upload coroutine cannot drop each other's entries. The
 * monitor is a platform actual (utility/SynchronizedOn.kt) because kotlin.jvm.Synchronized is an
 * error in common code since Kotlin 2.1.
 */
class WavelogQueue(private val store: IWavelogQueueStore) {

    private val key = "wavelog_queue"
    private val lock = Any()

    fun all(): List<WavelogQso> {
        val raw = store.load()
        return try {
            Json.parseToJsonElement(raw).jsonArray.map { element ->
                val o = element.jsonObject
                WavelogQso(
                    id = o.getValue("id").jsonPrimitive.content,
                    timeUtcMs = o["timeUtcMs"].readLong(),
                    call = o["call"].readString(),
                    mode = o["mode"].readString(),
                    freqTxHz = o["freqTxHz"].readLong(),
                    freqRxHz = o["freqRxHz"].readLong(),
                    satName = o["satName"].readString(),
                    catnum = o["catnum"].readInt(),
                    sessionId = o["sessionId"].readString(),
                    gridsquare = o["gridsquare"].readString(),
                    uploaded = o["uploaded"].readBoolean()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(qso: WavelogQso) {
        synchronizedOn(lock) {
            val list = all().toMutableList()
            list.add(0, qso) // 最新在前
            if (list.size > 500) list.removeAt(list.size - 1)
            save(list)
        }
    }

    fun remove(id: String) {
        synchronizedOn(lock) { save(all().filter { it.id != id }) }
    }

    fun removeAll(ids: Set<String>) {
        synchronizedOn(lock) { save(all().filter { it.id !in ids }) }
    }

    /** Mark as uploaded (kept in the queue; checkmark in the table) */
    fun markUploaded(id: String) {
        synchronizedOn(lock) { save(all().map { if (it.id == id) it.copy(uploaded = true) else it }) }
    }

    /** Update a QSO's counterpart grid (async backfill from the QRZ scraper, 4.5.5) */
    fun updateGridsquare(id: String, grid: String) {
        synchronizedOn(lock) { save(all().map { if (it.id == id) it.copy(gridsquare = grid) else it }) }
    }

    /** Remove all uploaded entries (optional; keeps the queue lean) */
    fun removeUploaded() {
        synchronizedOn(lock) { save(all().filter { !it.uploaded }) }
    }

    private fun save(list: List<WavelogQso>) {
        val arr = buildJsonArray {
            list.forEach { q ->
                add(buildJsonObject {
                    put("id", q.id); put("timeUtcMs", q.timeUtcMs); put("call", q.call)
                    put("mode", q.mode); put("freqTxHz", q.freqTxHz)
                    put("freqRxHz", q.freqRxHz); put("satName", q.satName)
                    put("catnum", q.catnum)
                    put("sessionId", q.sessionId)
                    put("gridsquare", q.gridsquare)
                    put("uploaded", q.uploaded)
                })
            }
        }
        store.save(arr.toString())
    }
}

/*
 * org.json's opt* readers never threw: a decimal ("1234.0", or the string "1234.0") was coerced to
 * a whole number, a missing or mismatched field fell back to the default, and a field holding an
 * object was stringified. kotlinx answers null for the first two - which turned a readable
 * timestamp into 0L, i.e. a QSO uploaded as 1970 - and throws for the third, which took the whole
 * list down with it. These keep the old behaviour, except that a JSON null becomes the empty
 * string or 0 instead of the literal "null".
 */
private fun JsonElement?.readLong(default: Long = 0L): Long {
    val primitive = this as? JsonPrimitive ?: return default
    primitive.longOrNull?.let { return it }
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: default
}

private fun JsonElement?.readInt(default: Int = 0): Int {
    val primitive = this as? JsonPrimitive ?: return default
    primitive.intOrNull?.let { return it }
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt() ?: default
}

private fun JsonElement?.readString(default: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull ?: default

private fun JsonElement?.readBoolean(default: Boolean = false): Boolean =
    (this as? JsonPrimitive)?.booleanOrNull ?: default
