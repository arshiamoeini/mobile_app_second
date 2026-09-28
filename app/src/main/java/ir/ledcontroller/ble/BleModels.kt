package ir.ledcontroller.ble

data class DiscoveredDevice(val name: String, val address: String, val rssi: Int)

enum class ConnectionPhase {
    Idle, Scanning, Pairing, Connecting, Discovering, Subscribing, Ready, Error
}

data class BleUiState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val devices: List<DiscoveredDevice> = emptyList(),
    val selectedAddress: String? = null,
    val actualMask: Int? = null,
    val desiredMask: Int = 0,
    val commandPending: Boolean = false,
    val message: String? = null
)
