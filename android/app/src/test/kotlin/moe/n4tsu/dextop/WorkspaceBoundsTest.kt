package moe.n4tsu.dextop

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceBoundsTest {
    private val usable = WorkspaceBounds(0, 0, 1280, 720)

    @Test fun movesRightOffscreenWindowWithoutCollapsingItsWidth() {
        assertEquals(
            WorkspaceBounds(680, 40, 1280, 440),
            WorkspaceBounds.fit(WorkspaceBounds(1800, 40, 2400, 440), usable),
        )
    }

    @Test fun movesBottomOffscreenWindowWithoutCollapsingItsHeight() {
        assertEquals(
            WorkspaceBounds(20, 320, 620, 720),
            WorkspaceBounds.fit(WorkspaceBounds(20, 850, 620, 1250), usable),
        )
    }

    @Test fun translatesNegativeCoordinatesWithoutCroppingTheWindow() {
        assertEquals(
            WorkspaceBounds(0, 0, 600, 400),
            WorkspaceBounds.fit(WorkspaceBounds(-400, -200, 200, 200), usable),
        )
    }

    @Test fun constrainsOversizedWindowToAvailableSpace() {
        assertEquals(usable, WorkspaceBounds.fit(WorkspaceBounds(-100, -100, 2000, 1400), usable))
    }

    @Test fun preservesWindowAlreadyInsideUsableArea() {
        val source = WorkspaceBounds(20, 40, 620, 440)
        assertEquals(source, WorkspaceBounds.fit(source, usable))
        assertEquals(usable, WorkspaceBounds.fit(usable, usable))
    }

    @Test fun respectsInsetOriginWhenTranslatingTheWholeWindow() {
        assertEquals(
            WorkspaceBounds(660, 30, 1260, 430),
            WorkspaceBounds.fit(WorkspaceBounds(1800, -100, 2400, 300), WorkspaceBounds(40, 30, 1260, 700)),
        )
    }

    @Test fun emptyOrInvertedSourceFallsBackToUsableArea() {
        assertEquals(usable, WorkspaceBounds.fit(WorkspaceBounds(10, 10, 10, 20), usable))
        assertEquals(usable, WorkspaceBounds.fit(WorkspaceBounds(30, 30, 10, 10), usable))
    }

    @Test fun emptyUsableAreaRemainsEmpty() {
        val empty = WorkspaceBounds(10, 20, 10, 20)
        assertEquals(empty, WorkspaceBounds.fit(usable, empty))
    }

    @Test fun extremeSavedCoordinatesDoNotOverflow() {
        assertEquals(
            usable,
            WorkspaceBounds.fit(WorkspaceBounds(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), usable),
        )
    }
}
