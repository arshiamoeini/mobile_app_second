package ir.ledcontroller.ble

import java.util.UUID

object LedProtocol {
    const val VERSION: Byte = 1
    const val CAPABILITIES: Byte = 0x0F
    const val LED_COUNT = 4

    val SERVICE_UUID: UUID = UUID.fromString("7e57d004-2b97-4db4-b160-5d8f9d7a1000")
    val INFO_UUID: UUID = UUID.fromString("7e57d004-2b97-4db4-b160-5d8f9d7a1001")
    val CONTROL_UUID: UUID = UUID.fromString("7e57d004-2b97-4db4-b160-5d8f9d7a1002")
    val STATE_UUID: UUID = UUID.fromString("7e57d004-2b97-4db4-b160-5d8f9d7a1003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun sanitizeMask(mask: Int): Int = mask and CAPABILITIES.toInt()

    fun withLed(mask: Int, index: Int, enabled: Boolean): Int {
        require(index in 0 until LED_COUNT)
        val bit = 1 shl index
        return sanitizeMask(if (enabled) mask or bit else mask and bit.inv())
    }

    fun isLedOn(mask: Int, index: Int): Boolean {
        require(index in 0 until LED_COUNT)
        return sanitizeMask(mask) and (1 shl index) != 0
    }
}
