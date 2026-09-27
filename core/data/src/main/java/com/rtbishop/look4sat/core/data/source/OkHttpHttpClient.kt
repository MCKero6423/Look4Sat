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

import com.rtbishop.look4sat.core.domain.source.HttpResult
import com.rtbishop.look4sat.core.domain.source.IHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Android [IHttpClient] for the shared Wavelog/QRZ code, which cannot reach java.net on iOS.
 *
 * Connect and read timeouts are the 15 s the previous HttpURLConnection client used, applied to
 * the passed client so the caller keeps one connection pool. The response body is returned for
 * error codes as well, which is what reading errorStream did.
 */
class OkHttpHttpClient(
    baseClient: OkHttpClient,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : IHttpClient {

    private val dispatcher = dispatcher

    private val client = baseClient.newBuilder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        execute {
            Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).withHeaders(headers).build()
        }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        execute { Request.Builder().url(url).withHeaders(headers).build() }

    private suspend fun execute(buildRequest: () -> Request): HttpResult = withContext(dispatcher) {
        try {
            // Built in here, not by the caller: a URL OkHttp refuses to parse has to come back as
            // the HTTP -1 the old client reported, not as an exception thrown at the caller.
            val request = buildRequest()
            client.newCall(request).execute().use { response ->
                HttpResult(response.code, response.body.string())
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            HttpResult(NO_RESPONSE, "", exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    private companion object {
        /** Matches HttpURLConnection's connect/read timeout in the original WaveLog client. */
        const val TIMEOUT_MS = 15_000L

        /** What HttpURLConnection's responseCode() reported when a request never got a response. */
        const val NO_RESPONSE = -1

        /** WaveLog's v2 and v1 endpoints take application/json in both directions. */
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
