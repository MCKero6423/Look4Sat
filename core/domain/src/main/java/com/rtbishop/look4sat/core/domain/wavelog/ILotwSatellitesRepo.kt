/* ILotwSatellitesRepo.kt — LoTW 卫星列表刷新接口(domain 层, 4.5.5)。
 * 实现: core/data 的 LotwSatellitesRepo(下载 ARRL config.tq6)。
 */
package com.rtbishop.look4sat.core.domain.wavelog

interface ILotwSatellitesRepo {
    sealed class RefreshResult {
        data class Ok(val count: Int) : RefreshResult()
        data class Error(val message: String) : RefreshResult()
    }

    fun restore()

    suspend fun refresh(): RefreshResult
}
