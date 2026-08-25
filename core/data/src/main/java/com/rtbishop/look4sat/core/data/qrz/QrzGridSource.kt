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
package com.rtbishop.look4sat.core.data.qrz

import com.rtbishop.look4sat.core.domain.qrz.QrzGrid
import com.rtbishop.look4sat.core.domain.qrz.QrzGridParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a station's Maidenhead locator off its QRZ.com page.
 *
 * QRZ has no free lookup API for this, so the page is fetched with the operator's own session
 * cookie and parsed. The cookie is pasted by the operator in settings and never built into the
 * app. Parsing lives in [QrzGridParser] so it can be tested without a network; this class only
 * fetches and retries.
 */
class QrzGridSource(
    private val httpClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher
) {

    /**
     * Look up [callsign]'s locator.
     *
     * Retried because this runs on a phone, mid-pass, often on mobile data - a single timeout
     * used to mean the QSO was logged without a grid and the operator was never told. Retries
     * are bounded and backed off so a genuinely unreachable QRZ costs at most a few seconds:
     * only transport failures are retried, since a page that loaded and parsed will not parse
     * differently on a second attempt.
     */
    suspend fun lookupGrid(callsign: String, cookieHeader: String): QrzGrid =
        withContext(dispatcher) {
            if (callsign.isBlank() || cookieHeader.isBlank()) return@withContext QrzGrid.SignedOut
            val url = "$DB_URL${callsign.trim().uppercase()}"
            fetchWithRetry(url, cookieHeader)?.let(QrzGridParser::parseGrid)
                ?: QrzGrid.Unreachable(MAX_ATTEMPTS)
        }

    /**
     * The callsign the pasted cookie is signed in as, so settings can show the operator whose
     * account it belongs to rather than just claiming success.
     */
    suspend fun lookupOwnCallsign(cookieHeader: String): String? = withContext(dispatcher) {
        if (cookieHeader.isBlank()) return@withContext null
        fetchWithRetry(DB_URL, cookieHeader)?.let(QrzGridParser::parseOwnCallsign)
    }

    /** Fetch [url], retrying transport failures with backoff. Null when every attempt failed. */
    private suspend fun fetchWithRetry(url: String, cookieHeader: String): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookieHeader)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body.string()
                    // A 4xx will repeat identically, so only server-side faults are worth retrying.
                    if (response.code < 500) return null
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                println("QrzGridSource attempt ${attempt + 1} failed: $exception")
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS[attempt])
        }
        return null
    }

    private companion object {
        /** Bare form is the signed-in home page; a callsign appended is that station's page. */
        const val DB_URL = "https://www.qrz.com/db/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) Look4Sat"
        const val MAX_ATTEMPTS = 3

        /** Waits before the second and third attempt. Short enough to finish inside a pass. */
        val BACKOFF_MS = longArrayOf(700L, 2_000L)
    }
}
