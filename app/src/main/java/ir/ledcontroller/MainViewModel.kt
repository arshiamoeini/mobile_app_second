package ir.ledcontroller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ir.ledcontroller.ble.BleController
import ir.ledcontroller.data.DeviceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DeviceStore(application)
    val ble = BleController(application, store)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock() {
        _unlocked.value = true
    }

    fun startBle() = ble.resume()

    fun lock() {
        _unlocked.value = false
        ble.suspendAndDisconnect()
    }

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }
}
