package ir.ledcontroller

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var permissionState by mutableStateOf(PermissionState.Unknown)
    private var authStarted = false
    private var suppressBackgroundLock = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        suppressBackgroundLock = false
        permissionState = if (result.values.all { it }) {
            PermissionState.Granted
        } else {
            val denied = result.filterValues { !it }.keys
            if (denied.any { !shouldShowRequestPermissionRationale(it) }) {
                PermissionState.PermanentlyDenied
            } else PermissionState.Required
        }
        if (permissionState == PermissionState.Granted) viewModel.startBle()
    }

    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        suppressBackgroundLock = false
        if (result.resultCode == Activity.RESULT_OK) onAuthenticated()
        else authStarted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionState = currentPermissionState()
        setContent {
            LedControllerApp(
                viewModel = viewModel,
                permissionState = permissionState,
                onUnlock = ::authenticate,
                onRequestPermissions = ::requestBlePermissions,
                onOpenSecuritySettings = {
                    suppressBackgroundLock = true
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                },
                onOpenBluetoothSettings = {
                    suppressBackgroundLock = true
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                onOpenAppSettings = {
                    suppressBackgroundLock = true
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                },
                onSelectDevice = { address ->
                    suppressBackgroundLock = true
                    viewModel.ble.selectDevice(address)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        permissionState = currentPermissionState()
        if (!viewModel.unlocked.value && !authStarted && !suppressBackgroundLock) authenticate()
        suppressBackgroundLock = false
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !suppressBackgroundLock) {
            authStarted = false
            viewModel.lock()
        }
    }

    private fun authenticate() {
        if (authStarted) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            authStarted = false
            return
        }
        authStarted = true
        if (Build.VERSION.SDK_INT >= 30) authenticateModern() else authenticateLegacy()
    }

    private fun authenticateModern() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = newPrompt()
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود به کنترل چراغ‌ها")
            .setSubtitle("هویت خود را با اثر انگشت یا قفل گوشی تأیید کنید")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun authenticateLegacy() {
        val biometricAvailable = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!biometricAvailable) {
            launchDeviceCredential()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ورود به کنترل چراغ‌ها")
            .setSubtitle("اثر انگشت خود را تأیید کنید")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("استفاده از رمز گوشی")
            .build()
        newPrompt().authenticate(info)
    }

    private fun newPrompt() = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (Build.VERSION.SDK_INT < 30 && errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    launchDeviceCredential()
                } else {
                    authStarted = false
                }
            }
        }
    )

    @Suppress("DEPRECATION")
    private fun launchDeviceCredential() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "ورود به کنترل چراغ‌ها",
            "PIN، الگو یا رمز گوشی را وارد کنید"
        )
        if (intent == null) {
            authStarted = false
            return
        }
        suppressBackgroundLock = true
        credentialLauncher.launch(intent)
    }

    private fun onAuthenticated() {
        authStarted = false
        viewModel.unlock()
        permissionState = currentPermissionState()
        if (permissionState == PermissionState.Granted) viewModel.startBle()
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        suppressBackgroundLock = true
        permissionLauncher.launch(permissions)
    }

    private fun currentPermissionState(): PermissionState {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            PermissionState.Granted
        } else PermissionState.Required
    }
}

enum class PermissionState { Unknown, Required, PermanentlyDenied, Granted }
