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

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.domain.qrz.IQrzGridLookup
import com.rtbishop.look4sat.core.domain.qrz.QrzGrid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * Grid lookup backed by the cookie the operator pasted into settings.
 *
 * Owns the cookie read so the log screen does not: a composable used to pull it out of
 * SharedPreferences through LocalContext on every submission, which put disk access inside
 * composition and went around the repository layer.
 */
class QrzGridLookup(
    private val preferences: SharedPreferences,
    httpClient: OkHttpClient,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : IQrzGridLookup {

    private val source = QrzGridSource(httpClient, dispatcher)

    override suspend fun lookup(callsign: String): QrzGrid {
        val cookie = preferences.getString(COOKIE_KEY, "").orEmpty()
        // No cookie and an expired one call for the same thing from the operator, so they report
        // the same way rather than adding a fourth outcome nobody could act on differently.
        if (cookie.isBlank()) return QrzGrid.SignedOut
        return source.lookupGrid(callsign, cookie)
    }

    companion object {
        /** Where the settings screen stores what the operator pasted. */
        const val PREFS_NAME = "qrz_cookie"
        const val COOKIE_KEY = "cookie"
    }
}
