package com.ideatic.sunwatt.work

import com.ideatic.sunwatt.core.data.SampleData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class RedrawScheduleTest {
    @Test
    fun `one plant redraws at the next full hour`() {
        // 13:40 Madrid on 2026-10-17 is 11:40 UTC.
        val next = RedrawSchedule.next(SampleData.now, rotatingPlants = false)
        assertEquals(Instant.parse("2026-10-17T12:00:02Z"), next)
    }

    @Test
    fun `several plants redraw at the next quarter hour`() {
        assertEquals(Instant.parse("2026-10-17T11:45:02Z"), RedrawSchedule.next(SampleData.now, rotatingPlants = true))
    }

    @Test
    fun `exactly on a boundary goes to the following one`() {
        assertEquals(Instant.parse("2026-10-17T13:00:02Z"), RedrawSchedule.next(Instant.parse("2026-10-17T12:00:00Z"), false))
    }

    @Test
    fun `redraws when the shown solar data turns stale before the next hour`() {
        val staleAt = Instant.parse("2026-10-17T11:50:00Z")
        assertEquals(Instant.parse("2026-10-17T11:50:02Z"), RedrawSchedule.next(SampleData.now, false, staleAt))
    }

    @Test
    fun `a stale time in the past or after the boundary keeps the boundary`() {
        val hour = Instant.parse("2026-10-17T12:00:02Z")
        assertEquals(hour, RedrawSchedule.next(SampleData.now, false, Instant.parse("2026-10-17T11:00:00Z")))
        assertEquals(hour, RedrawSchedule.next(SampleData.now, false, Instant.parse("2026-10-17T12:30:00Z")))
    }
}
