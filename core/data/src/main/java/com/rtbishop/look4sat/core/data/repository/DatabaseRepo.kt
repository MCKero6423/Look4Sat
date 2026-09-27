/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DatabaseRepo(
    private val dispatcher: CoroutineDispatcher,
    private val dataParser: DataParser,
    private val localSource: ILocalSource,
    private val remoteSource: IRemoteSource,
    private val settingsRepo: ISettingsRepo
) : IDatabaseRepo {

    private val customSourceType = "Other"

    /**
     * Type key for satellites fetched from a custom URL.
     *
     * Separate from customSourceType because setSatelliteTypeIds overwrites rather than merges, so
     * sharing "Other" with manual file import meant each wiped the other's type index. The
     * satellites stayed in the database and stayed selectable either way - only their grouping in
     * the type filter was lost - but the two sources are different things and deserve different
     * keys.
     */
    private val customUrlType = "Custom"

    override suspend fun updateTLEFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileBytes(uri)?.let { data ->
            val entries = parseSatelliteData(uri, unwrapIfZipped(uri, data))
            localSource.insertEntries(entries)
            settingsRepo.setSatelliteTypeIds(customSourceType, entries.map { it.catnum })
            importedCount = entries.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateTransceiversFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileBytes(uri)?.let { data ->
            val transceivers = dataParser.parseJSON(unwrapIfZipped(uri, data).decodeToString())
            localSource.insertRadios(transceivers)
            importedCount = transceivers.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateFromRemote() = withContext(dispatcher) {
        val dataSourcesSettings = settingsRepo.dataSourcesSettings.value
        // A custom URL REPLACES the built-in sources rather than joining them. The previous map
        // overwrote only the "All" value and still fetched the other 26, so switching this on meant
        // "my source AND yours" - which defeats the reasons for setting one: a mirror, a filtered
        // subset, an offline server, or a network where Celestrak is unreachable. On a blocked link
        // the real behaviour was 26 failing requests.
        //
        // Satellites already stored do not disappear: insertEntries is OnConflictStrategy.REPLACE
        // and nothing is deleted before the insert, so rows the new source does not mention survive.
        // The type index for the skipped keys goes stale rather than empty, which is the honest
        // outcome - it is the last known membership, not a claim about this fetch.
        //
        // The key is customUrlType, not "All": setSatelliteTypeIds early-returns on "All", so
        // indexing under it was always a no-op and satellites from a custom URL were never
        // reachable by the type filter at all. They now are.
        val tleUrls = if (dataSourcesSettings.useCustomTLE && dataSourcesSettings.tleUrl.isNotBlank()) {
            mapOf(customUrlType to dataSourcesSettings.tleUrl)
        } else {
            Sources.satelliteDataUrls.filterValues { it.isNotBlank() }
        }
        val radioUrls = if (dataSourcesSettings.useCustomTransceivers &&
            dataSourcesSettings.transceiversUrl.isNotBlank()
        ) {
            mapOf("SatNOGS" to dataSourcesSettings.transceiversUrl)
        } else {
            Sources.transceiversDataUrls.filterValues { it.isNotBlank() }
        }
        // launch all network requests concurrently
        val tleJobs = tleUrls.values.map { url -> async { url to remoteSource.getNetworkBytes(url) } }
        val radioJobs = radioUrls.values.map { url -> async { url to remoteSource.getNetworkBytes(url) } }
        val tleResults = tleJobs.awaitAll()
        val radioResults = radioJobs.awaitAll()
        // Orbital elements are counted on their own. A combined count let a successful transceivers
        // fetch stand in for a failed orbital one: with a custom TLE URL there are two requests
        // rather than 28, so if that URL was down and SatNOGS answered, the total was 1, no
        // exception was raised, and setUpdateSuccessful stamped a fresh timestamp for an update
        // that refreshed no orbital data at all - which also suppressed the 48-hour auto-update
        // retry that keys off that timestamp. The failure existed before but 26 other sources hid
        // it; replacing them made it easy to hit.
        if (tleResults.none { it.second != null }) {
            throw java.io.IOException("No orbital data source could be downloaded")
        }
        // parse fetched data concurrently and associate with types
        val importedEntries = tleResults.flatMap { (url, data) ->
            val type = tleUrls.entries.find { it.value == url }?.key ?: customSourceType
            data?.let { parseSatelliteData(url, unwrapIfZipped(url, it)) }.orEmpty().also { entries ->
                settingsRepo.setSatelliteTypeIds(type, entries.map { it.catnum })
            }
        }
        val importedRadios = radioResults.flatMap { (url, data) ->
            data?.let { dataParser.parseJSON(unwrapIfZipped(url, it).decodeToString()) }.orEmpty()
        }
        // insert parsed data into the database
        localSource.insertEntries(importedEntries)
        localSource.insertRadios(importedRadios)
        setUpdateSuccessful(System.currentTimeMillis())
    }

    override suspend fun clearAllData() = withContext(dispatcher) {
        localSource.deleteEntries()
        localSource.deleteRadios()
        setUpdateSuccessful(0L)
    }

    private suspend fun parseSatelliteData(url: String, data: ByteArray): List<OrbitalData> {
        val text = data.decodeToString()
        return when {
            hasCsvHint(url) || looksLikeCsv(text) -> dataParser.parseCSV(text)
            else -> dataParser.parseTLE(text)
        }
    }

    private fun hasCsvHint(url: String): Boolean {
        return url.contains("FORMAT=csv", ignoreCase = true) ||
            url.endsWith(".csv", ignoreCase = true) ||
            url.endsWith(".csv.zip", ignoreCase = true)
    }

    private fun looksLikeCsv(text: String): Boolean {
        val line = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isEmpty()) return false
        return line.contains("OBJECT_NAME", ignoreCase = true) ||
            line.contains("NORAD_CAT_ID", ignoreCase = true) ||
            line.count { it == ',' } >= 4
    }

    private suspend fun setUpdateSuccessful(timestamp: Long) {
        settingsRepo.updateDatabaseState(
            DatabaseState(localSource.getRadiosTotal(), localSource.getEntriesTotal(), timestamp)
        )
    }

    private fun unwrapIfZipped(url: String, data: ByteArray): ByteArray =
        if (url.endsWith(".zip", ignoreCase = true)) {
            ZipInputStream(ByteArrayInputStream(data)).apply { nextEntry }.readBytes()
        } else {
            data
        }
}
