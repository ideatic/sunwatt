package com.ideatic.sunwatt.work

import com.ideatic.sunwatt.core.models.Constants
import java.time.Instant

/**
 * When the widgets must be drawn again without a network request: the price "now" changes every hour, and with several
 * plants a rotating widget changes plant every quarter hour ([com.ideatic.sunwatt.core.timeline.TimelineBuilder.rotationInterval]).
 * Android has no timeline of entries like WidgetKit, so the redraw is scheduled for the next entry time.
 */
object RedrawSchedule {
    /** Seconds after the boundary, so the redraw happens inside the new hour. */
    private const val MARGIN_SECONDS = 2L

    /**
     * The next entry time after [now]: the next full hour (the offsets of every price region are whole hours, so UTC
     * hours are market hours), or the next quarter hour when [rotatingPlants]. Swift counts rotation steps from
     * 2001-01-01 UTC, which is a multiple of 15 minutes, so quarter hours of the clock are the rotation steps.
     * [staleAt]: when the shown solar data turns stale, if that comes first.
     */
    fun next(now: Instant, rotatingPlants: Boolean, staleAt: Instant? = null): Instant {
        val step = if (rotatingPlants) Constants.refreshInterval.inWholeSeconds else 3600L
        val boundary = (Math.floorDiv(now.epochSecond, step) + 1) * step
        val next = Instant.ofEpochSecond(boundary + MARGIN_SECONDS)
        // A widget is drawn once per update: redraw when the shown solar data turns stale, so the badge appears then.
        val stale = staleAt?.plusSeconds(MARGIN_SECONDS)?.takeIf { staleAt.isAfter(now) && it.isBefore(next) }
        return stale ?: next
    }
}
