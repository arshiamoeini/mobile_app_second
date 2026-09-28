package ir.ledcontroller.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import ir.ledcontroller.data.DeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class BleController(
    context: Context,
    private val store: DeviceStore
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private var active = false
    private var gatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var operationTimeout: Runnable? = null
    private var reconnectAttempt = 0
    private var commandInFlight = false
    private var writeResponseReceived = false
    private var stateResponseReceived = false
    private var selectedDevice: BluetoothDevice? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::addScanResult)
        override fun onScanFailed(errorCode: Int) {
            stopScan()
            fail("اسکن بلوتوث انجام نشد (کد $errorCode)", reconnect = false)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (device.address != selectedDevice?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> {
                    scope.launch { store.save(device.address) }
                    connect(device)
                }
                BluetoothDevice.BOND_NONE -> if (_state.value.phase == ConnectionPhase.Pairing) {
                    fail("جفت‌سازی لغو شد یا ناموفق بود", reconnect = false)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun bluetoothAvailable(): Boolean = adapter != null
    fun bluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun resume() {
        if (active) return
        active = true
        scope.launch {
            val address = store.selectedAddress.first()
            _state.update { it.copy(selectedAddress = address) }
            if (address == null) startScan() else connectAddress(address)
        }
    }

    fun suspendAndDisconnect() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        _state.update {
            it.copy(
                phase = ConnectionPhase.Idle,
                actualMask = null,
                commandPending = false,
                message = null
            )
        }
    }

    fun startScan() {
        if (!active || scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            fail("بلوتوث خاموش است یا BLE در دسترس نیست", reconnect = false)
            return
        }
        closeGatt()
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LedProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _state.update { it.copy(phase = ConnectionPhase.Scanning, devices = emptyList(), message = null) }
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    fun selectDevice(address: String) {
        stopScan()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            fail("آدرس دستگاه معتبر نیست", reconnect = false)
            return
        }
        selectedDevice = device
        _state.update { it.copy(selectedAddress = address, message = null) }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            scope.launch { store.save(address) }
            connect(device)
        } else {
            _state.update { it.copy(phase = ConnectionPhase.Pairing) }
            if (!device.createBond()) fail("شروع جفت‌سازی ممکن نشد", reconnect = false)
        }
    }

    fun retry() {
        reconnectAttempt = 0
        val address = _state.value.selectedAddress
        if (address == null) startScan() else connectAddress(address)
    }

    fun setLed(index: Int, enabled: Boolean) {
        val currentDesired = _state.value.desiredMask
        val next = LedProtocol.withLed(currentDesired, index, enabled)
        _state.update { it.copy(desiredMask = next, commandPending = next != it.actualMask) }
        sendLatestIfPossible()
    }

    fun forgetDevice() {
        suspendAndDisconnect()
        scope.launch {
            store.clear()
            _state.value = BleUiState(message = "برد از اپ حذف شد؛ Bond را در تنظیمات Bluetooth گوشی نیز حذف کنید")
        }
    }

    fun close() {
        suspendAndDisconnect()
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        scope.cancel()
    }

    private fun addScanResult(result: ScanResult) {
        val device = result.device ?: return
        val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "LEDCTRL"
        if (!name.startsWith("LEDCTRL-", ignoreCase = true)) return
        val item = DiscoveredDevice(name, device.address, result.rssi)
        _state.update { old ->
            val devices = (old.devices.filterNot { it.address == item.address } + item)
                .sortedByDescending { it.rssi }
            old.copy(devices = devices)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value.phase == ConnectionPhase.Scanning) {
            _state.update { it.copy(phase = ConnectionPhase.Idle) }
        }
    }

    private fun connectAddress(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            fail("برد ذخیره‌شده پیدا نشد", reconnect = false)
            return
        }
        selectedDevice = device
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.update { it.copy(phase = ConnectionPhase.Pairing) }
            if (!device.createBond()) fail("برای اتصال دوباره باید برد را جفت کنید", reconnect = false)
        } else connect(device)
    }

    private fun connect(device: BluetoothDevice) {
        if (!active) return
        stopScan()
        closeGatt()
        selectedDevice = device
        _state.update { it.copy(phase = ConnectionPhase.Connecting, message = null, actualMask = null) }
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        armTimeout(CONNECT_TIMEOUT_MS, "مهلت اتصال تمام شد") { fail(it, reconnect = true) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                clearTimeout()
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    reconnectAttempt = 0
                    _state.update { it.copy(phase = ConnectionPhase.Discovering) }
                    if (!g.discoverServices()) fail("کشف سرویس شروع نشد", reconnect = true)
                    else armTimeout(DISCOVERY_TIMEOUT_MS, "کشف سرویس طول کشید") { fail(it, true) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("ارتباط با برد قطع شد", reconnect = true)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("خطای اتصال GATT: $status", reconnect = true)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                clearTimeout()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("سرویس‌های برد خوانده نشدند", true)
                    return@post
                }
                val service: BluetoothGattService = g.getService(LedProtocol.SERVICE_UUID) ?: run {
                    fail("پروتکل LEDCTRL نسخه ۱ روی برد پیدا نشد", false)
                    return@post
                }
                val info = service.getCharacteristic(LedProtocol.INFO_UUID)
                controlCharacteristic = service.getCharacteristic(LedProtocol.CONTROL_UUID)
                stateCharacteristic = service.getCharacteristic(LedProtocol.STATE_UUID)
                if (info == null || controlCharacteristic == null || stateCharacteristic == null) {
                    fail("ساختار سرویس BLE ناقص است", false)
                    return@post
                }
                subscribeToState(g, stateCharacteristic!!)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handleDescriptorWrite(g, descriptor, status)
        }

        @Deprecated("API 33 callback retained for Android 6 compatibility")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleCharacteristicRead(g, c, c.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleCharacteristicRead(g, c, value, status)
        }

        @Deprecated("API 33 callback retained for Android 6 compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleStateValue(g, c, c.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleStateValue(g, c, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt || c.uuid != LedProtocol.CONTROL_UUID) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    commandInFlight = false
                    fail("فرمان به برد نرسید", true)
                } else {
                    writeResponseReceived = true
                    if (stateResponseReceived) {
                        finishCommand()
                    } else {
                        armStateFallback(g)
                    }
                }
            }
        }
    }

    private fun subscribeToState(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        _state.update { it.copy(phase = ConnectionPhase.Subscribing) }
        val descriptor = characteristic.getDescriptor(LedProtocol.CCCD_UUID)
        if (descriptor == null || !g.setCharacteristicNotification(characteristic, true)) {
            fail("فعال‌کردن اعلان وضعیت ممکن نشد", true)
            return
        }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!started) fail("ثبت اعلان وضعیت شروع نشد", true)
        else armTimeout(OPERATION_TIMEOUT_MS, "فعال‌سازی اعلان وضعیت طول کشید") { fail(it, true) }
    }

    private fun handleDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = handler.post {
        if (g !== gatt || descriptor.uuid != LedProtocol.CCCD_UUID) return@post
        clearTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("اعلان وضعیت فعال نشد", true)
            return@post
        }
        val info = g.getService(LedProtocol.SERVICE_UUID)?.getCharacteristic(LedProtocol.INFO_UUID)
        if (info == null || !g.readCharacteristic(info)) fail("خواندن نسخه پروتکل شروع نشد", true)
        else armTimeout(OPERATION_TIMEOUT_MS, "پاسخ نسخه پروتکل دریافت نشد") { fail(it, true) }
    }

    private fun handleCharacteristicRead(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) = handler.post {
        if (g !== gatt) return@post
        clearTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("خواندن اطلاعات برد ناموفق بود", true)
            return@post
        }
        when (characteristic.uuid) {
            LedProtocol.INFO_UUID -> {
                if (value.size < 2 || value[0] != LedProtocol.VERSION) {
                    fail("نسخه پروتکل برد با اپ سازگار نیست", false)
                    return@post
                }
                val stateChar = stateCharacteristic
                if (stateChar == null || !g.readCharacteristic(stateChar)) fail("خواندن وضعیت شروع نشد", true)
                else armTimeout(OPERATION_TIMEOUT_MS, "وضعیت برد دریافت نشد") { fail(it, true) }
            }
            LedProtocol.STATE_UUID -> acceptState(value)
        }
    }

    private fun handleStateValue(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        handler.post {
            if (g === gatt && characteristic.uuid == LedProtocol.STATE_UUID) acceptState(value)
        }
    }

    private fun acceptState(value: ByteArray) {
        if (value.isEmpty()) return
        clearTimeout()
        val mask = LedProtocol.sanitizeMask(value[0].toInt())
        _state.update {
            val firstReady = it.actualMask == null
            it.copy(
                phase = ConnectionPhase.Ready,
                actualMask = mask,
                desiredMask = if (firstReady) mask else it.desiredMask,
                commandPending = if (firstReady) false else commandInFlight || it.desiredMask != mask,
                message = null
            )
        }
        if (commandInFlight) {
            stateResponseReceived = true
            if (writeResponseReceived) {
                finishCommand()
            } else {
                armTimeout(OPERATION_TIMEOUT_MS, "پاسخ نوشتن فرمان دریافت نشد") { fail(it, true) }
            }
        } else {
            sendLatestIfPossible()
        }
    }

    private fun sendLatestIfPossible() {
        if (!active || _state.value.phase != ConnectionPhase.Ready || commandInFlight) return
        val actual = _state.value.actualMask ?: return
        val desired = LedProtocol.sanitizeMask(_state.value.desiredMask)
        if (desired == actual) {
            _state.update { it.copy(commandPending = false) }
            return
        }
        val g = gatt ?: return
        val characteristic = controlCharacteristic ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val payload = byteArrayOf(desired.toByte())
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        if (!started) {
            fail("ارسال فرمان شروع نشد", true)
            return
        }
        commandInFlight = true
        writeResponseReceived = false
        stateResponseReceived = false
        _state.update { it.copy(commandPending = true) }
        armTimeout(OPERATION_TIMEOUT_MS, "پاسخ نوشتن فرمان دریافت نشد") { fail(it, true) }
    }

    private fun armStateFallback(g: BluetoothGatt) {
        armTimeout(OPERATION_TIMEOUT_MS, "برد تغییر وضعیت را تأیید نکرد") {
            val stateChar = stateCharacteristic
            if (stateChar == null || !g.readCharacteristic(stateChar)) fail(it, true)
            else armTimeout(OPERATION_TIMEOUT_MS, "وضعیت برد قابل خواندن نیست") { message -> fail(message, true) }
        }
    }

    private fun finishCommand() {
        clearTimeout()
        commandInFlight = false
        writeResponseReceived = false
        stateResponseReceived = false
        _state.update { it.copy(commandPending = it.desiredMask != it.actualMask) }
        sendLatestIfPossible()
    }

    private fun fail(message: String, reconnect: Boolean) {
        clearTimeout()
        closeGatt()
        _state.update {
            it.copy(
                phase = ConnectionPhase.Error,
                actualMask = null,
                commandPending = false,
                message = message
            )
        }
        if (reconnect && active && _state.value.selectedAddress != null) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val delays = longArrayOf(1_000, 2_000, 4_000, 8_000, 30_000)
        val delay = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        handler.postDelayed({
            if (active) _state.value.selectedAddress?.let(::connectAddress)
        }, delay)
    }

    private fun closeGatt() {
        clearTimeout()
        commandInFlight = false
        writeResponseReceived = false
        stateResponseReceived = false
        controlCharacteristic = null
        stateCharacteristic = null
        gatt?.let { old ->
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
        gatt = null
    }

    private fun armTimeout(delay: Long, message: String, action: (String) -> Unit) {
        clearTimeout()
        operationTimeout = Runnable { operationTimeout = null; action(message) }.also {
            handler.postDelayed(it, delay)
        }
    }

    private fun clearTimeout() {
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val OPERATION_TIMEOUT_MS = 5_000L
    }
}
