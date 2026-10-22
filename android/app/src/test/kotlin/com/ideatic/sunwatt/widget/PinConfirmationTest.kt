package com.ideatic.sunwatt.widget

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinConfirmationTest {
    @Test
    fun `confirms once after a pin request`() {
        // Pixel sends the success callback and the first update: the message must show once.
        val pins = PinConfirmation()
        pins.requested(at = 1_000)
        assertTrue(pins.takeConfirmation(at = 3_000))
        assertFalse(pins.takeConfirmation(at = 3_100))
    }

    @Test
    fun `no confirmation without a request`() {
        // A widget update at boot or after an app update is not a pin.
        assertFalse(PinConfirmation().takeConfirmation(at = 3_000))
    }

    @Test
    fun `an old request does not confirm a later update`() {
        val pins = PinConfirmation()
        pins.requested(at = 1_000)
        assertFalse(pins.takeConfirmation(at = 1_000 + PinConfirmation.WINDOW_MS + 1))
    }
}
