package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one press of a direction key does to a slider row.
 *
 * The rewatch threshold is picked one percent at a time, so the arithmetic of the keys is the part
 * of the row that has to be right: the composable only forwards the key event.
 */
class SettingsSliderValueTest {

    private fun move(
        value: Int,
        direction: Int,
        repeatCount: Int = 0,
        step: Int = 1
    ): Int = settingsSliderValueAfterKeyPress(
        value = value,
        direction = direction,
        minimum = THRESHOLD_MIN,
        maximum = THRESHOLD_MAX,
        step = step,
        repeatCount = repeatCount
    )

    @Test
    fun `one press moves one step`() {
        assertEquals(81, move(80, direction = 1))
        assertEquals(84, move(85, direction = -1))
    }

    @Test
    fun `a key held past six repeats moves faster`() {
        // Holding right has to cross the range, otherwise reaching the far end costs fifteen presses.
        assertEquals(83, move(80, direction = 1, repeatCount = 7))
        assertEquals(80, move(83, direction = -1, repeatCount = 12))
    }

    @Test
    fun `the value never leaves the range`() {
        assertEquals(THRESHOLD_MIN, move(THRESHOLD_MIN, direction = -1))
        assertEquals(THRESHOLD_MAX, move(THRESHOLD_MAX, direction = 1))
        assertEquals(THRESHOLD_MAX, move(94, direction = 1, repeatCount = 30))
        assertEquals(THRESHOLD_MIN, move(82, direction = -1, repeatCount = 30))
    }

    @Test
    fun `a value outside the range is pulled back into it`() {
        assertEquals(THRESHOLD_MIN, move(40, direction = 0))
        assertEquals(THRESHOLD_MAX, move(120, direction = 0))
    }

    @Test
    fun `a direction or a step that cannot move anything leaves the value alone`() {
        assertEquals(90, move(90, direction = 0))
        assertEquals(90, move(90, direction = 1, step = 0))
        assertEquals(90, move(90, direction = -1, step = 0))
    }

    @Test
    fun `a range of a single value cannot be left`() {
        assertEquals(
            90,
            settingsSliderValueAfterKeyPress(
                value = 90,
                direction = 1,
                minimum = 90,
                maximum = 90,
                repeatCount = 30
            )
        )
    }

    @Test
    fun `a coarser step moves by that step and accelerates from it`() {
        assertEquals(83, move(80, direction = 1, step = 3))
        assertEquals(89, move(80, direction = 1, step = 3, repeatCount = 7))
    }

    private companion object {
        const val THRESHOLD_MIN = 80
        const val THRESHOLD_MAX = 95
    }
}
