package xyz.regulad.closecircuit

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CloseCircuitUserPreferencesRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "user_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val CLOSE_CIRCUIT_SSID = "close_circuit_ssid"
        private const val CLOSE_CIRCUIT_PASSPHRASE = "close_circuit_passphrase"
    }

    var closeCircuitSsid: String?
        get() = sharedPreferences.getString(CLOSE_CIRCUIT_SSID, null)
        set(value) = sharedPreferences.edit().putString(CLOSE_CIRCUIT_SSID, value).apply()

    var closeCircuitPassphrase: String?
        get() = sharedPreferences.getString(CLOSE_CIRCUIT_PASSPHRASE, null)
        set(value) = sharedPreferences.edit().putString(CLOSE_CIRCUIT_PASSPHRASE, value).apply()
}
