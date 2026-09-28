package ir.ledcontroller

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.ledcontroller.ble.ConnectionPhase
import ir.ledcontroller.ble.LedProtocol

private val AppColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    secondary = Color(0xFF4D6358),
    background = Color(0xFFF5FBF6),
    surface = Color.White,
    error = Color(0xFFBA1A1A)
)

@Composable
fun LedControllerApp(
    viewModel: MainViewModel,
    permissionState: PermissionState,
    onUnlock: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectDevice: (String) -> Unit
) {
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    val bleState by viewModel.ble.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deviceSecure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    MaterialTheme(colorScheme = AppColors) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize()) {
                when {
                    !unlocked -> LockScreen(deviceSecure, onUnlock, onOpenSecuritySettings)
                    permissionState != PermissionState.Granted -> PermissionScreen(
                        permanentlyDenied = permissionState == PermissionState.PermanentlyDenied,
                        onRequest = onRequestPermissions,
                        onOpenSettings = onOpenAppSettings
                    )
                    !viewModel.ble.bluetoothAvailable() -> MessageScreen(
                        title = "BLE در دسترس نیست",
                        message = "این گوشی از Bluetooth Low Energy پشتیبانی نمی‌کند."
                    )
                    !viewModel.ble.bluetoothEnabled() -> MessageScreen(
                        title = "بلوتوث خاموش است",
                        message = "بلوتوث گوشی را روشن کنید و سپس دوباره تلاش کنید.",
                        action = "تنظیمات بلوتوث",
                        onAction = onOpenBluetoothSettings
                    )
                    else -> ControlScreen(
                        state = bleState,
                        onScan = viewModel.ble::startScan,
                        onSelect = onSelectDevice,
                        onRetry = viewModel.ble::retry,
                        onToggle = viewModel.ble::setLed,
                        onForget = {
                            viewModel.ble.forgetDevice()
                            onOpenBluetoothSettings()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LockScreen(
    secure: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit
) = CenteredColumn {
    Text("کنترل چراغ‌ها", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    Text(
        if (secure) "برای ورود، هویت خود را با اثر انگشت یا قفل گوشی تأیید کنید."
        else "برای استفاده از اپ ابتدا PIN، الگو، رمز یا اثر انگشت گوشی را فعال کنید."
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = if (secure) onUnlock else onOpenSecuritySettings) {
        Text(if (secure) "باز کردن اپ" else "رفتن به تنظیمات امنیتی")
    }
}

@Composable
private fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) = CenteredColumn {
    Text("اجازه دسترسی به بلوتوث", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "برای پیدا کردن و اتصال به ESP32 این اجازه لازم است. در Androidهای قدیمی نام اجازه «موقعیت مکانی» است، اما اپ موقعیت شما را دریافت یا ذخیره نمی‌کند."
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = if (permanentlyDenied) onOpenSettings else onRequest) {
        Text(if (permanentlyDenied) "بازکردن تنظیمات اپ" else "دادن اجازه")
    }
}

@Composable
private fun ControlScreen(
    state: ir.ledcontroller.ble.BleUiState,
    onScan: () -> Unit,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onToggle: (Int, Boolean) -> Unit,
    onForget: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("کنترل چراغ‌ها", style = MaterialTheme.typography.headlineMedium)
        ConnectionCard(state.phase, state.message)

        when (state.phase) {
            ConnectionPhase.Ready -> {
                repeat(LedProtocol.LED_COUNT) { index ->
                    LedRow(
                        index = index,
                        checked = LedProtocol.isLedOn(state.desiredMask, index),
                        actual = state.actualMask?.let { LedProtocol.isLedOn(it, index) },
                        enabled = true,
                        onCheckedChange = { onToggle(index, it) }
                    )
                }
                if (state.commandPending) Text("در حال تأیید وضعیت از برد…")
            }
            ConnectionPhase.Scanning, ConnectionPhase.Idle -> {
                if (state.devices.isEmpty()) {
                    Text(if (state.phase == ConnectionPhase.Scanning) "در حال جست‌وجوی بردهای LEDCTRL…" else "بردی پیدا نشد.")
                } else {
                    Text("برد موردنظر را انتخاب کنید:", style = MaterialTheme.typography.titleMedium)
                    state.devices.forEach { device ->
                        Card(onClick = { onSelect(device.address) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text("${device.address}  •  ${device.rssi} dBm")
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onScan, enabled = state.phase != ConnectionPhase.Scanning) {
                    Text("اسکن دوباره")
                }
            }
            ConnectionPhase.Error -> Button(onClick = onRetry) { Text("تلاش دوباره") }
            else -> Unit
        }

        if (state.selectedAddress != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onForget) { Text("حذف برد و بازکردن تنظیمات Pairing") }
        }
    }
}

@Composable
private fun ConnectionCard(phase: ConnectionPhase, message: String?) {
    val (label, color) = when (phase) {
        ConnectionPhase.Ready -> "متصل" to Color(0xFF16834B)
        ConnectionPhase.Error -> "خطا" to MaterialTheme.colorScheme.error
        ConnectionPhase.Scanning -> "در حال جست‌وجو" to Color(0xFF806000)
        ConnectionPhase.Pairing -> "در حال جفت‌سازی؛ کد روی برچسب برد را وارد کنید" to Color(0xFF806000)
        ConnectionPhase.Connecting -> "در حال اتصال" to Color(0xFF806000)
        ConnectionPhase.Discovering -> "در حال بررسی سرویس" to Color(0xFF806000)
        ConnectionPhase.Subscribing -> "در حال دریافت وضعیت" to Color(0xFF806000)
        ConnectionPhase.Idle -> "آماده جست‌وجو" to Color.Gray
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxSize(), shape = CircleShape, color = color) {}
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (phase in setOf(
                    ConnectionPhase.Scanning,
                    ConnectionPhase.Connecting,
                    ConnectionPhase.Discovering,
                    ConnectionPhase.Subscribing,
                    ConnectionPhase.Pairing
                )
            ) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun LedRow(
    index: Int,
    checked: Boolean,
    actual: Boolean?,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("چراغ ${index + 1}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (actual) {
                        true -> "وضعیت برد: روشن"
                        false -> "وضعیت برد: خاموش"
                        null -> "وضعیت برد: نامشخص"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun MessageScreen(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {}
) = CenteredColumn {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(message)
    if (action != null) {
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}
