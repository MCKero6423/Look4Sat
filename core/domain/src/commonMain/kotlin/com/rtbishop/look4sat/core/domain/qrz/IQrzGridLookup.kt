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
package com.rtbishop.look4sat.core.domain.qrz

/**
 * Looks up a station's grid square on QRZ.
 *
 * An interface so the log screen can ask for a grid without reaching into core:data, and without
 * reading the stored cookie itself - a composable was fetching it straight out of
 * SharedPreferences through LocalContext, which put disk access in composition and bypassed the
 * repository layer entirely.
 */
interface IQrzGridLookup {

    /**
     * Look up [callsign].
     *
     * Returns [QrzGrid.SignedOut] when no cookie is stored, since the operator's remedy is the
     * same either way: put a valid cookie in settings.
     */
    suspend fun lookup(callsign: String): QrzGrid

    /**
     * Check the stored cookie by asking QRZ whose account it belongs to.
     *
     * Returns the callsign QRZ reports, or null when the cookie is absent or no longer valid. Lets
     * settings tell the operator which account they pasted rather than only claiming success.
     */
    suspend fun signedInAs(): String?
}
