package com.rtbishop.look4sat.core.domain.aprs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the one defect no other test in this project could catch.
 *
 * The APRS service passes a foreground service type to startForeground, and AOSP requires it to be
 * a subset of the type declared in the manifest, throwing IllegalArgumentException otherwise -
 * which the service's own catch turns into a silent stopSelf(). A commit changed the manifest from
 * dataSync to location and left the code passing dataSync, so APRS started, died and reported
 * nothing on every device running Android 10 or later, while the settings switch stayed on. Two
 * auditors found it by reading constants against AOSP; the unit suite could not see it at all,
 * because the service is untestable on the JVM and the app module has no test source set.
 *
 * So this reads both files as text from core:domain, which does have test infrastructure. Crude,
 * and it says nothing about whether the service works - but it fails the moment those two files
 * disagree, which is exactly the failure that shipped.
 */
class ForegroundServiceTypeTest {

    /** Manifest attribute value to the ServiceInfo constant the code must pass. */
    private val expectedConstant = mapOf(
        "dataSync" to "FOREGROUND_SERVICE_TYPE_DATA_SYNC",
        "location" to "FOREGROUND_SERVICE_TYPE_LOCATION",
        "connectedDevice" to "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE",
        "mediaPlayback" to "FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK",
        "shortService" to "FOREGROUND_SERVICE_TYPE_SHORT_SERVICE"
    )

    /** Manifest attribute value to the permission that must accompany it. */
    private val requiredPermission = mapOf(
        "dataSync" to "FOREGROUND_SERVICE_DATA_SYNC",
        "location" to "FOREGROUND_SERVICE_LOCATION",
        "connectedDevice" to "FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "mediaPlayback" to "FOREGROUND_SERVICE_MEDIA_PLAYBACK"
    )

    /**
     * Walk up from the working directory to the repository root.
     *
     * A unit test's working directory is the module directory, but that is not guaranteed across
     * Gradle versions, so the root is found by looking for settings.gradle.kts rather than assumed.
     */
    private val repoRoot: File? by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        dir
    }

    private fun read(relative: String): String? =
        repoRoot?.let { File(it, relative) }?.takeIf { it.isFile }?.readText()

    private val manifest: String? by lazy { read("app/src/main/AndroidManifest.xml") }
    private val service: String? by lazy {
        read("app/src/main/java/com/rtbishop/look4sat/AprsForegroundService.kt")
    }

    /** The type declared for the APRS service in the manifest. */
    private fun declaredType(text: String): String {
        val match = Regex("""android:foregroundServiceType="([^"]+)"""").find(text)
        assertTrue("no foregroundServiceType found in the manifest", match != null)
        return match!!.groupValues[1]
    }

    @Test
    fun `the service passes the type its manifest declares`() {
        val manifestText = manifest
        val serviceText = service
        // Skipped rather than failed if the layout moves: a broken locator must not read as a
        // broken app, and the assertion below is worthless without both files anyway.
        assumeTrue("manifest or service source not found", manifestText != null && serviceText != null)
        val declared = declaredType(manifestText!!)
        val constant = expectedConstant[declared]
        assertTrue(
            "unrecognised foregroundServiceType '$declared' - add it to this test's map",
            constant != null
        )
        assertTrue(
            "manifest declares $declared, so startForeground must pass ServiceInfo.$constant",
            serviceText!!.contains("ServiceInfo.$constant")
        )
    }

    /** Passing any type the manifest does not declare is what AOSP rejects. */
    @Test
    fun `the service passes no other foreground service type`() {
        val manifestText = manifest
        val serviceText = service
        assumeTrue("manifest or service source not found", manifestText != null && serviceText != null)
        val passed = Regex("""ServiceInfo\.(FOREGROUND_SERVICE_TYPE_\w+)""")
            .findAll(serviceText!!)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "exactly one type may be passed, and it must match the manifest",
            setOf(expectedConstant.getValue(declaredType(manifestText!!))),
            passed
        )
    }

    /** The matching permission must be declared, or startForeground throws on API 34 and up. */
    @Test
    fun `the manifest declares the permission its service type requires`() {
        val manifestText = manifest
        assumeTrue("manifest not found", manifestText != null)
        val declared = declaredType(manifestText!!)
        val permission = requiredPermission[declared]
        assertTrue("no permission mapped for '$declared'", permission != null)
        assertTrue(
            "foregroundServiceType $declared requires android.permission.$permission",
            manifestText.contains("android.permission.$permission")
        )
    }

    /**
     * A location-typed service additionally demands an already-granted location permission before
     * startForeground. The settings card requests only notifications, so declaring location would
     * fail silently for any operator who declined location access - the same class of defect this
     * whole test exists to prevent.
     */
    @Test
    fun `a location typed service would need a runtime permission this app does not request`() {
        val manifestText = manifest
        assumeTrue("manifest not found", manifestText != null)
        assumeTrue("only applies to a location-typed service", declaredType(manifestText!!) == "location")
        val card = read(
            "feature/settings/src/main/java/com/rtbishop/look4sat/feature/settings/AprsCard.kt"
        )
        assumeTrue("settings card source not found", card != null)
        assertTrue(
            "declaring location requires requesting ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION",
            card!!.contains("ACCESS_COARSE_LOCATION") || card.contains("ACCESS_FINE_LOCATION")
        )
    }
}
