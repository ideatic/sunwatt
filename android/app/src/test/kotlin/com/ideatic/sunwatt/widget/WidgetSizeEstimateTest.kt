package com.ideatic.sunwatt.widget

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetSizeEstimateTest {
    @Test
    fun `phone estimate is close to the real MIUI widget`() {
        // Redmi Note 8 Pro: 393 x 851 dp screen; the launcher's large widget is 369 x 427 dp, medium 369 x 212 dp.
        val large = estimatedWidgetSize(393, 851, WidgetSize.LARGE)
        assertEquals(369f, large.width.value, 0.5f)
        assertTrue(large.height in 380.dp..427.dp)
        val medium = estimatedWidgetSize(393, 851, WidgetSize.MEDIUM)
        assertEquals(369f, medium.width.value, 0.5f)
        assertTrue(medium.height in 190.dp..212.dp)
        val small = estimatedWidgetSize(393, 851, WidgetSize.SMALL)
        assertEquals(large.width / 2, small.width)
    }

    @Test
    fun `landscape uses the short side`() {
        assertEquals(estimatedWidgetSize(393, 851, WidgetSize.LARGE), estimatedWidgetSize(851, 393, WidgetSize.LARGE))
    }

    @Test
    fun `a tablet estimate stays small enough not to clip`() {
        // A 4 x 4 widget on a 6-column tablet grid is about 500 dp wide; drawing larger would cut the content.
        val large = estimatedWidgetSize(800, 1280, WidgetSize.LARGE)
        assertTrue(large.width <= 400.dp)
        assertTrue(large.height <= 440.dp)
    }
}
