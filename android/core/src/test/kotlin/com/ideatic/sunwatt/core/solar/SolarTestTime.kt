package com.ideatic.sunwatt.core.solar

import com.ideatic.sunwatt.core.models.Constants
import java.time.Instant
import java.time.ZonedDateTime

/** An instant from Europe/Madrid wall-clock components. */
internal fun madridAt(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Instant =
    ZonedDateTime.of(y, mo, d, h, mi, 0, 0, Constants.madrid).toInstant()
