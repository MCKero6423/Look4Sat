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
package com.rtbishop.look4sat.core.data.source

import android.content.ContentResolver
import androidx.core.net.toUri
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class RemoteSource(
    private val dispatcher: CoroutineDispatcher,
    private val contentResolver: ContentResolver,
    private val httpClient: OkHttpClient
) : IRemoteSource {

    override suspend fun getFileBytes(uri: String): ByteArray? = withContext(dispatcher) {
        try {
            val fileUri = uri.toUri()
            contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("RemoteSource file stream exception: $exception")
            null
        }
    }

    override suspend fun getNetworkBytes(url: String): ByteArray? = withContext(dispatcher) {
        try {
            val networkRequest = Request.Builder().url(url).build()
            // The whole body is read here, which also returns the connection to OkHttp's pool
            httpClient.newCall(networkRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.bytes()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("RemoteSource network stream exception: $exception")
            null
        }
    }

    override suspend fun getAmSatCatalog(): String? = withContext(dispatcher) {
        try {
            val request = Request.Builder()
                .url("https://www.amsat.org/status/api/v1/catalog.php")
                .header("User-Agent", "Look4Sat/4.5.7")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("RemoteSource amsat catalog exception: $exception")

            null
        }
    }

    override suspend fun getAmSatReports(hours: Int, limit: Int): String? = withContext(dispatcher) {
        try {
            val request = Request.Builder()
                .url("https://www.amsat.org/status/api/v1/reports.php?hours=$hours&limit=$limit")
                .header("User-Agent", "Look4Sat/4.5.7")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("RemoteSource amsat reports exception: $exception")

            null
        }
    }

    override suspend fun getAmSatSummary(hours: Int): String? = withContext(dispatcher) {
        try {
            val request = Request.Builder()
                .url("https://www.amsat.org/status/api/v1/summary.php?hours=$hours")
                .header("User-Agent", "Look4Sat/4.5.7")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            println("RemoteSource amsat summary exception: $exception")
            null
        }
    }
}
