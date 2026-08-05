/* ILotwSatellitesRepo.kt - LoTW satellite list refresh interface (domain layer, 4.5.5).
 * Impl: LotwSatellitesRepo in core/data (downloads ARRL config.tq6).
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
