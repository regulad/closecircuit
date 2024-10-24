package xyz.regulad.closecircuit

import android.annotation.SuppressLint
import android.app.Application
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import xyz.regulad.regulib.wifi.SubnetScanner
import xyz.regulad.regulib.wifi.WifiP2pManagerView.Companion.WIFI_P2P_SUBNET
import xyz.regulad.regulib.wifi.WifiP2pManagerView.Companion.WIFI_P2P_SUBNET_INFO
import xyz.regulad.regulib.wifi.WifiP2pManagerView.Companion.getWifiP2pManagerView


class CloseCircuitViewModel(application: Application) : AndroidViewModel(application) {
    val wifiP2pManagerView = application.getWifiP2pManagerView()
    private val preferencesRepository = CloseCircuitUserPreferencesRepository(application)

    companion object {
        private const val TAG = "CloseCircuitViewModel"
        const val ANDROID_IP_WEBCAM_DEFAULT_PORT: UShort = 8080u

        val ANDROID_IP_WEBCAM_SCANNER =
            SubnetScanner.createHttpPortAssessor(ANDROID_IP_WEBCAM_DEFAULT_PORT, "/greet.html")
    }

    protected fun finalize() {
        close() // hail mary
    }

    private fun close() {
        if (wifiP2pManagerView.thisGroupInfo.value != null) {
            runBlocking {
                wifiP2pManagerView.removeGroup()
            }
        }

        backgroundJobCoroutineScope.cancel()
        wifiP2pManagerView.teardown()
        wifiP2pManagerView.teardown()
        networkSubnetScanner.stopScanning()
    }

    override fun onCleared() {
        super.onCleared()
        close()
    }

    private val backgroundJobCoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val networkSubnetScanner = SubnetScanner.fromCidr(WIFI_P2P_SUBNET, ANDROID_IP_WEBCAM_SCANNER)

    init {
        backgroundJobCoroutineScope.launch {
            wifiP2pManagerView.thisGroupInfo.collect { groupInfo ->
                Log.d(TAG, "Group info: $groupInfo")
                groupInfo?.let { info ->
                    info.networkName?.let { preferencesRepository.closeCircuitSsid = it }
                    info.passphrase?.let { preferencesRepository.closeCircuitPassphrase = it }
                }
            }
        }
    }

    @SuppressLint("MissingPermission", "InlinedApi", "ObsoleteSdkInt")
    suspend fun setupAp() {
        if (wifiP2pManagerView.requestGroupInfo() != null) {
            wifiP2pManagerView.removeGroup()
        }

        if (Build.VERSION.SDK_INT < VERSION_CODES.Q || preferencesRepository.closeCircuitSsid == null || preferencesRepository.closeCircuitPassphrase == null) {
            wifiP2pManagerView.createGroup()
        } else {
            wifiP2pManagerView.createGroup(
                WifiP2pConfig.Builder().apply {
                    setNetworkName(preferencesRepository.closeCircuitSsid!!)
                    setPassphrase(preferencesRepository.closeCircuitPassphrase!!)
                    setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ)
                }.build()
            )
        }

        Log.d(TAG, "P2p legacy group created successfully!")
    }
}
