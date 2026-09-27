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
package com.rtbishop.look4sat.core.domain.utility

/**
 * Runs [block] while holding [lock]'s monitor, the way kotlin.jvm.Synchronized used to hold it
 * before core:domain became a multiplatform module.
 *
 * The annotation survives in common code as an optional expectation, but the stdlib deprecated it
 * there in Kotlin 1.8 and made it an error in 2.1: "Synchronizing methods on a class instance is
 * not supported on platforms other than JVM." The monitor therefore moves behind a platform
 * actual, which keeps the JVM semantics exactly and lets the iOS side say what it does instead.
 */
internal expect fun <T> synchronizedOn(lock: Any, block: () -> T): T
