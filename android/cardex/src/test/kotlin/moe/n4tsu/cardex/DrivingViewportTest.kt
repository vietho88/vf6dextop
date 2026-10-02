package moe.n4tsu.cardex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingViewportTest {
    @Test
    fun boundsExposeDimensionsAndEmptyState() {
        val area = DrivingBounds(120, 40, 1500, 840)
        assertEquals(1380, area.width)
        assertEquals(800, area.height)
        assertFalse(area.isEmpty)
        assertTrue(DrivingBounds(10, 10, 10, 20).isEmpty)
        assertTrue(DrivingBounds(20, 20, 10, 10).isEmpty)
    }

    @Test
    fun wideSurfaceUsesIntersectionOfVisibleAndStableInsets() {
        assertEquals(
            DrivingBounds(120, 40, 1500, 840),
            DrivingViewport.resolve(
                1600, 900,
                DrivingBounds(120, 0, 1600, 840),
                DrivingBounds(0, 40, 1500, 900),
            ),
        )
    }

    @Test
    fun callbacksAreClippedToTheCurrentSurface() {
        assertEquals(
            DrivingBounds(80, 0, 1280, 680),
            DrivingViewport.resolve(
                1280, 720,
                DrivingBounds(80, -20, 1500, 680),
                DrivingBounds(-100, -100, 1600, 1000),
            ),
        )
    }

    @Test
    fun emptyAndOffSurfaceCallbacksAreUnknown() {
        val surface = DrivingBounds(0, 0, 1280, 720)
        assertEquals(surface, DrivingViewport.resolve(1280, 720, null, null))
        assertEquals(
            surface,
            DrivingViewport.resolve(
                1280, 720,
                DrivingBounds(0, 0, 0, 0),
                DrivingBounds(1400, 0, 1600, 720),
            ),
        )
        val stable = DrivingBounds(40, 20, 1200, 680)
        assertEquals(
            stable,
            DrivingViewport.resolve(1280, 720, DrivingBounds(0, 0, 0, 0), stable),
        )
        assertEquals(
            stable,
            DrivingViewport.resolve(1280, 720, stable, DrivingBounds(-100, 0, -1, 720)),
        )
    }

    @Test
    fun disjointCallbacksFallBackToVisibleArea() {
        val visible = DrivingBounds(0, 0, 600, 720)
        assertEquals(
            visible,
            DrivingViewport.resolve(1280, 720, visible, DrivingBounds(700, 0, 1280, 720)),
        )
    }

    @Test
    fun surfaceWithoutDimensionsCannotBeResolved() {
        assertNull(DrivingViewport.resolve(0, 720, null, null))
        assertNull(DrivingViewport.resolve(1280, 0, null, null))
        assertNull(DrivingViewport.resolve(-1, 720, null, null))
    }

    @Test
    fun narrowerContentIsCenteredWithSideBarsOnWideSurface() {
        assertEquals(
            DrivingBounds(160, 0, 1440, 720),
            DrivingViewport.fit(1920, 1080, DrivingBounds(0, 0, 1600, 720)),
        )
    }

    @Test
    fun widerContentIsCenteredWithTopAndBottomBars() {
        assertEquals(
            DrivingBounds(100, 180, 1380, 660),
            DrivingViewport.fit(1920, 720, DrivingBounds(100, 60, 1380, 780)),
        )
    }

    @Test
    fun fittingKeepsInsetsAndUsesUniformScale() {
        val available = DrivingBounds(120, 40, 1500, 840)
        val fitted = DrivingViewport.fit(1920, 1080, available)
        assertEquals(available.width, fitted.width)
        assertEquals(776, fitted.height)
        assertEquals(DrivingBounds(120, 52, 1500, 828), fitted)
        assertEquals(1920.0 / 1080, fitted.width.toDouble() / fitted.height, 0.002)
    }

    @Test
    fun fittingTinyAreasStillProducesAtLeastOnePixel() {
        assertEquals(
            DrivingBounds(5, 7, 6, 8),
            DrivingViewport.fit(10000, 1, DrivingBounds(5, 7, 6, 8)),
        )
        val empty = DrivingBounds(5, 7, 5, 8)
        assertEquals(empty, DrivingViewport.fit(1920, 1080, empty))
        val area = DrivingBounds(5, 7, 105, 107)
        assertEquals(area, DrivingViewport.fit(0, 1080, area))
    }

    @Test
    fun clicksSubtractRenderedOffsetBeforeMapping() {
        val rendered = DrivingBounds(200, 100, 1200, 600)
        assertEquals(0f to 0f, DrivingViewport.mapClick(200f, 100f, rendered, 1920, 1080))
        assertEquals(960f to 540f, DrivingViewport.mapClick(700f, 350f, rendered, 1920, 1080))
    }

    @Test
    fun clicksOnBarsOrExclusiveEdgesAreRejected() {
        val rendered = DrivingBounds(200, 100, 1200, 600)
        assertNull(DrivingViewport.mapClick(199f, 350f, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(1200f, 350f, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(700f, 99f, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(700f, 600f, rendered, 1920, 1080))
    }

    @Test
    fun invalidClicksAndInputDimensionsAreRejected() {
        val rendered = DrivingBounds(0, 0, 1280, 720)
        assertNull(DrivingViewport.mapClick(Float.NaN, 10f, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(10f, Float.NaN, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(Float.POSITIVE_INFINITY, 10f, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(10f, Float.NEGATIVE_INFINITY, rendered, 1920, 1080))
        assertNull(DrivingViewport.mapClick(10f, 10f, rendered, 0, 1080))
        assertNull(DrivingViewport.mapClick(10f, 10f, rendered, 1920, -1))
        assertNull(DrivingViewport.mapClick(10f, 10f, DrivingBounds(0, 0, 0, 0), 1920, 1080))
    }

    @Test
    fun mappedClicksRemainBelowInputEdgesAfterFloatRounding() {
        val rendered = DrivingBounds(0, 0, 1280, 720)
        val mapped = DrivingViewport.mapClick(
            Math.nextDown(1280f), Math.nextDown(720f), rendered, 1920, 1080,
        )
        assertNotNull(mapped)
        assertTrue(mapped!!.first >= 0f && mapped.first < 1920f)
        assertTrue(mapped.second >= 0f && mapped.second < 1080f)
    }

    @Test
    fun resizingRecomputesBothRenderingAndClickMapping() {
        val before = DrivingViewport.fit(
            1920, 1080, DrivingViewport.resolve(1280, 720, null, null)!!,
        )
        val after = DrivingViewport.fit(
            1920, 1080, DrivingViewport.resolve(1920, 720, null, null)!!,
        )
        assertEquals(DrivingBounds(0, 0, 1280, 720), before)
        assertEquals(DrivingBounds(320, 0, 1600, 720), after)
        assertEquals(960f to 540f, DrivingViewport.mapClick(640f, 360f, before, 1920, 1080))
        assertEquals(960f to 540f, DrivingViewport.mapClick(960f, 360f, after, 1920, 1080))
        assertNull(DrivingViewport.mapClick(100f, 360f, after, 1920, 1080))
    }
}
