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
package com.rtbishop.look4sat.core.data.cw

import android.content.Context
import android.util.Log

/**
 * Minimal crash-probe logger for diagnosing crashes that produce no Java
 * stack trace (native faults, low-memory kills). Each step appends one line
 * to `files/probe_cw.txt`; if the process dies mid-way the last line shows
 * exactly where. No adb or logcat required.
 */
internal object CwProbe {

    /** Keep the diagnostic file bounded: the decoder writes two lines per
     * 1.5 s inference tick (~170 KB/hour), so without a cap it grows without
     * limit on every release build. Truncate instead of deleting so the
     * probe keeps the last diagnostics before a crash. */
    private const val MAX_FILE_BYTES = 1_048_576L // 1 MiB

    private var dir: java.io.File? = null

    fun init(context: Context) {
        dir = context.filesDir
    }

    fun step(label: String) {
        val target = dir ?: return
        runCatching {
            val line = "${System.currentTimeMillis()} $label"
            val file = java.io.File(target, "probe_cw.txt")
            if (file.length() > MAX_FILE_BYTES) file.delete()
            file.appendText("$line\n")
            Log.i("CwProbe", line)
        }
    }
}
