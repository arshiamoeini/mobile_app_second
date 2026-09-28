package ir.ledcontroller.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.deviceDataStore by preferencesDataStore(name = "selected_device")

class DeviceStore(private val context: Context) {
    private val addressKey = stringPreferencesKey("public_ble_address")

    val selectedAddress: Flow<String?> = context.deviceDataStore.data.map { it[addressKey] }

    suspend fun save(address: String) {
        context.deviceDataStore.edit { it[addressKey] = address }
    }

    suspend fun clear() {
        context.deviceDataStore.edit { it.remove(addressKey) }
    }
}
