/* LotwSatellites.kt — LoTW 支持的卫星名列表(112 个, 静态内置)。
 * 来源: https://lotw.arrl.org/lotw/config.tq6 (gzip XML, ARRL 官方!)
 * WaveLog 用同一来源更新 satellite 表 lotw 字段(Update_model.php lotw_sats())。
 * 运行时可由 LotwSatellitesRepo 手动刷新(设置页 WaveLog 区按钮触发)。
 */
package com.rtbishop.look4sat.core.domain.wavelog

object LotwSatellites {
    private val staticNames: Set<String> = setOf("AISAT1", "AO-10", "AO-109", "AO-123", "AO-13", "AO-16", "AO-21", "AO-27", "AO-3", "AO-4", "AO-40", "AO-51", "AO-6", "AO-7", "AO-73", "AO-8", "AO-85", "AO-91", "AO-92", "ARISS", "Arsene", "BO-102", "BY70-1", "CAS-2T", "CAS-3H", "CAS-4A", "CAS-4B", "DO-64", "EO-79", "EO-88", "FO-118", "FO-12", "FO-20", "FO-29", "FO-99", "FS-3", "HO-107", "HO-113", "HO-119", "HO-68", "INSPR7", "IO-117", "IO-86", "JO-97", "KEDR", "LEDSAT", "LO-19", "LO-78", "LO-87", "LO-90", "MAYA-3", "MAYA-4", "MIREX", "MO-112", "MO-122", "NO-103", "NO-104", "NO-44", "NO-83", "NO-84", "PO-101", "QO-100", "RS-1", "RS-10", "RS-11", "RS-12", "RS-13", "RS-15", "RS-2", "RS-44", "RS-5", "RS-6", "RS-7", "RS-8", "SAREX", "SO-121", "SO-124", "SO-125", "SO-35", "SO-41", "SO-50", "SO-67", "SONATE", "TAURUS", "TEVEL1", "TEVEL2", "TEVEL3", "TEVEL4", "TEVEL5", "TEVEL6", "TEVEL7", "TEVEL8", "TO-108", "UKUBE1", "UO-14", "UVSQ", "VO-52", "XW-2A", "XW-2B", "XW-2C", "XW-2D", "XW-2E", "XW-2F", "TEV2-1", "TEV2-2", "TEV2-3", "TEV2-4", "TEV2-5", "TEV2-6", "TEV2-7", "TEV2-8", "TEV2-9")

    @Volatile
    private var dynamicNames: Set<String> = emptySet()

    /** 当前生效的卫星名: 静态内置 ∪ 运行时更新 */
    val names: Set<String>
        get() = if (dynamicNames.isEmpty()) staticNames else staticNames + dynamicNames

    /** 运行时更新(设置页按钮 → LotwSatellitesRepo.refresh() 后调用) */
    fun updateNames(newNames: Set<String>) {
        if (newNames.isNotEmpty()) dynamicNames = newNames
    }
}
