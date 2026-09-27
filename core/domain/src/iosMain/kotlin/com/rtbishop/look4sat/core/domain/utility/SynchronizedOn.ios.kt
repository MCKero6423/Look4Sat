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
 * Kotlin/Native has no equivalent of a JVM monitor, and nothing on iOS calls these mutators
 * concurrently yet: the iOS app is not built (M2), and its tests are single threaded. This is a
 * placeholder, not a lock - M2 has to give it a real one before any second thread touches state.
 */
internal actual fun <T> synchronizedOn(lock: Any, block: () -> T): T = block()
