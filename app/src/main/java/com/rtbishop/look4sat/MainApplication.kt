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
package com.rtbishop.look4sat

import android.app.Application
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider {

    private lateinit var container: IMainContainer

    /** 全局崩溃捕获:堆栈写入 files/crash_log.txt,重启后可查看(用户要求错误报告) */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val log = StringBuilder()
                log.append("=== Crash ${System.currentTimeMillis()} ===\n")
                log.append("Thread: ").append(thread.name).append("\n")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                log.append(sw.toString()).append("\n")
                val file = java.io.File(filesDir, "crash_log.txt")
                file.appendText(log.toString())
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun getMainContainer(): IMainContainer = container

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        container = MainContainer(this)
        // trigger automatic update every 48 hours
        container.appScope.launch { checkAutoUpdate() }
        // load satellite data on every app start
        container.appScope.launch { container.satelliteRepo.initRepository() }
    }

    private suspend fun checkAutoUpdate(timeNow: Long = System.currentTimeMillis()) {
        if (container.settingsRepo.otherSettings.value.stateOfAutoUpdate) {
            val timeDelta = timeNow - container.settingsRepo.databaseState.value.updateTimestamp
            if (timeDelta > 172_800_000L) { // 48 hours in ms
                val sdf = SimpleDateFormat("d MMM yyyy - HH:mm:ss", Locale.getDefault())
                println("Started periodic data update on ${sdf.format(Date())}")
                container.databaseRepo.updateFromRemote()
            }
        }
    }
}
