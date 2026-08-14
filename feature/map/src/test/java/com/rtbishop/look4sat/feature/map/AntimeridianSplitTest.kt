package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A ground track must be cut into polylines that never span the antimeridian,
 * and the cut must be seamless: the closing point of one segment and the opening
 * point of the next sit on opposite edges at the same interpolated latitude.
 *
 * Regression: the previous implementation only appended the outgoing edge point,
 * so every following segment began inland (e.g. at -178) and the drawn track
 * broke at 180 degrees. It also reused the next sample's latitude for the edge,
 * which made the closing leg jump.
 */
class AntimeridianSplitTest {

    private fun pos(lat: Double, lon: Double) = GeoPos(lat, lon)

    private fun assertNoSegmentSpansTheEdge(segments: List<List<GeoPos>>) {
        segments.forEachIndexed { index, segment ->
            segment.zipWithNext { a, b ->
                assertTrue(
                    "segment $index still spans the antimeridian: $a -> $b",
                    abs(b.longitude - a.longitude) <= 180.0
                )
            }
        }
    }

    private fun assertSeamsAreOnOppositeEdges(segments: List<List<GeoPos>>) {
        segments.zipWithNext { previous, next ->
            val exit = previous.last()
            val entry = next.first()
            assertEquals("exit longitude", 180.0, abs(exit.longitude), 1e-9)
            assertEquals("entry longitude", 180.0, abs(entry.longitude), 1e-9)
            assertEquals("edges must be opposite", 0.0, exit.longitude + entry.longitude, 1e-9)
            assertEquals("latitude must be continuous", exit.latitude, entry.latitude, 1e-9)
        }
    }

    @Test
    fun `eastward crossing is cut seamlessly`() {
        val segments = splitAtAntimeridian(
            listOf(
                pos(10.0, 165.0), pos(12.0, 172.0), pos(14.0, 179.0),
                pos(16.0, -178.0), pos(18.0, -171.0)
            )
        )

        assertEquals(2, segments.size)
        assertEquals(180.0, segments[0].last().longitude, 1e-9)
        assertEquals(-180.0, segments[1].first().longitude, 1e-9)
        assertNoSegmentSpansTheEdge(segments)
        assertSeamsAreOnOppositeEdges(segments)
    }

    @Test
    fun `westward crossing is cut seamlessly`() {
        val segments = splitAtAntimeridian(
            listOf(
                pos(10.0, -165.0), pos(12.0, -172.0), pos(14.0, -179.0),
                pos(16.0, 178.0), pos(18.0, 171.0)
            )
        )

        assertEquals(2, segments.size)
        assertEquals(-180.0, segments[0].last().longitude, 1e-9)
        assertEquals(180.0, segments[1].first().longitude, 1e-9)
        assertNoSegmentSpansTheEdge(segments)
        assertSeamsAreOnOppositeEdges(segments)
    }

    @Test
    fun `edge latitude is interpolated rather than copied`() {
        // 179 -> -178 spans three degrees of longitude and two of latitude, so
        // the edge sits one degree past the first sample, not at the second one.
        val segments = splitAtAntimeridian(listOf(pos(14.0, 179.0), pos(16.0, -178.0)))

        assertEquals(14.0 + 2.0 / 3.0, segments[0].last().latitude, 1e-9)
    }

    @Test
    fun `track that stays near the edge is not split`() {
        val segments = splitAtAntimeridian(listOf(pos(10.0, 175.0), pos(12.0, 178.0)))

        assertEquals(1, segments.size)
        assertEquals(2, segments[0].size)
    }

    @Test
    fun `repeated crossings all produce seams`() {
        val segments = splitAtAntimeridian(
            listOf(pos(0.0, 170.0), pos(5.0, -175.0), pos(10.0, 175.0), pos(15.0, -170.0))
        )

        assertEquals(4, segments.size)
        assertNoSegmentSpansTheEdge(segments)
        assertSeamsAreOnOppositeEdges(segments)
    }

    @Test
    fun `empty and single point tracks are handled`() {
        assertEquals(listOf(emptyList<GeoPos>()), splitAtAntimeridian(emptyList()))
        assertEquals(1, splitAtAntimeridian(listOf(pos(1.0, 2.0))).size)
    }
}
