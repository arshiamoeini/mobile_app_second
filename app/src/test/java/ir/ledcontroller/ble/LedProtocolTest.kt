package ir.ledcontroller.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedProtocolTest {
    @Test fun maskIsLimitedToFourOutputs() {
        assertEquals(0x0F, LedProtocol.sanitizeMask(0xFF))
    }

    @Test fun individualLedCanBeChangedWithoutChangingOthers() {
        val initial = 0b0101
        val enabled = LedProtocol.withLed(initial, 1, true)
        assertEquals(0b0111, enabled)
        assertTrue(LedProtocol.isLedOn(enabled, 2))

        val disabled = LedProtocol.withLed(enabled, 0, false)
        assertEquals(0b0110, disabled)
        assertFalse(LedProtocol.isLedOn(disabled, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLedIndexIsRejected() {
        LedProtocol.withLed(0, 4, true)
    }
}
