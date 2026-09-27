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
package com.rtbishop.look4sat.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * core:domain is the one module shared by every platform: it holds the orbital math, the data
 * models and the repository contracts, and none of it touches Android APIs. It is a Kotlin
 * Multiplatform module (JVM for Android, Kotlin/Native for iOS) rather than a JVM one so the
 * same compiled logic runs on both platforms instead of being reimplemented.
 *
 * Android modules consume the jvm target; the iOS app consumes the framework built from the
 * ios targets. Anything JVM-only - org.json, java.net, java.io, java.util.Locale,
 * String.format - cannot live in commonMain, because Kotlin/Native has none of them.
 */
@Suppress("Unused")
internal class CoreDomainPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        applyPlugin(libs.plugins.kotlin.multiplatform)
        applyPlugin(libs.plugins.kotlin.serialization)
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.versions.jdkVersion.get().toInt())
            jvm()
            listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
                iosTarget.binaries.framework {
                    baseName = "Look4SatCore"
                    isStatic = true
                }
            }
            sourceSets {
                commonMain.dependencies {
                    implementation(libs.kotlin.coroutines)
                    implementation(libs.kotlin.serialization)
                }
                commonTest.dependencies {
                    implementation(libs.kotlin.test)
                    implementation(libs.test.coroutines)
                }
                // JVM-only tests live here: the AndroidManifest check reads the file system, and
                // the formatter oracle tests compare against java.lang.String.format.
                jvmTest.dependencies {
                    implementation(libs.test.junit4)
                }
            }
        }
    }
}
