package xyz.regulad.closecircuit

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.regulad.regulib.wifi.WifiP2pManagerView.Companion.getWifiP2pManagerView
import java.net.NetworkInterface


class CloseCircuitViewModel(private val application: Application) : AndroidViewModel(application) {
    private val wifiP2pManagerView = application.getWifiP2pManagerView()
    private val wifiManager = application.getSystemService<WifiManager>()!!

    val groupInfoState = wifiP2pManagerView.thisGroupInfo.onlyNewParcelable(viewModelScope)
    val groupInterfaceState =
        wifiP2pManagerView.thisGroupInfo.transformOnlyNewParcelableSuspendable(viewModelScope) { groupInfo ->
            if (groupInfo == null) return@transformOnlyNewParcelableSuspendable null

            // this is the name of the interface, but there is no guarantee it is immediately available
            val networkInterfaceName = groupInfo.`interface`

            simpleRetryUntilNotNull {
                NetworkInterface.getByName(networkInterfaceName)?.let {
                    Log.d(TAG, "Found network interface $networkInterfaceName for the group")
                    return@simpleRetryUntilNotNull it
                }

                Log.d(TAG, "Couldn't find network interface $networkInterfaceName, retrying...")
                Log.d(
                    TAG,
                    "Current network interfaces: ${
                        NetworkInterface.getNetworkInterfaces().asSequence().map { it.name }.toList()
                    }"
                )

                null
            }
        }

    private val preferencesRepository = CloseCircuitUserPreferencesRepository(application)

    companion object {
        private const val TAG = "CloseCircuitViewModel"

        private val wifiIntentFilter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION) // can't inline because we want to recieve dat
        }

        private const val WIFI_DIRECT_RETRY_INTERVAL = 5_000L

        // we can afford to probe very frequently, the wifi devices we use don't even support TWT so its not like
        // we are keeping the WiFi radio on more than we need to
        private const val WSDD_PROBE_INTERVAL = 1_000L

        // if the camera doesn't respond to 3 consequent probes, we'll consider it delinquent and remove it
        private const val CAMERA_DELINQUENCY_INTERVAL = WSDD_PROBE_INTERVAL * 3
    }

    private val _wifiState = MutableStateFlow(wifiManager.isWifiEnabled)
    val wifiState = _wifiState.asStateFlow()

    private val wsDiscoveryClient =
        WSDiscovery11(application, viewModelScope, WSDD_PROBE_INTERVAL, setOf("dn:NetworkVideoTransmitter"))
    private val wsDiscoveryONVIFRoutingTable =
        WSDiscoveryONVIFRoutingTable(wsDiscoveryClient.probeMatchFlow, viewModelScope, CAMERA_DELINQUENCY_INTERVAL)

    val accessibleMjpegStreams = wsDiscoveryONVIFRoutingTable.accessibleMjpegStreams

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)

                when (wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> _wifiState.value = true
                    WifiManager.WIFI_STATE_DISABLED -> _wifiState.value = false
                }
            }
        }
    }

    private val groupInfoCollectionJob: Job
    private val interfaceUpdatingJob: Job

    init {
        application.registerReceiver(broadcastReceiver, wifiIntentFilter)

        groupInfoCollectionJob = viewModelScope.launch {
            var lastGroup = wifiP2pManagerView.thisGroupInfo.value
            groupInfoState.collect { groupInfo ->
                Log.d(TAG, "Received an updated group info: $groupInfo")
                groupInfo?.let { info ->
                    info.networkName?.let { preferencesRepository.closeCircuitSsid = it }
                    info.passphrase?.let { preferencesRepository.closeCircuitPassphrase = it }
                }

                if (groupInfo == null && lastGroup != null) {
                    Log.d(
                        TAG,
                        "The group died. In ${WIFI_DIRECT_RETRY_INTERVAL}ms, if the group isn't back, we'll try to create it."
                    )
                    viewModelScope.launch {
                        delay(WIFI_DIRECT_RETRY_INTERVAL)
                        if (wifiP2pManagerView.thisGroupInfo.value == null) {
                            Log.d(TAG, "The group is still dead. Trying to resuscitate it.")
                            setupAp()
                        }
                    }
                }
                lastGroup = groupInfo
            }
        }

        interfaceUpdatingJob = viewModelScope.launch {
            groupInterfaceState.collect { networkInterface ->
                Log.d(TAG, "Received an updated network interface: $networkInterface")

                if (networkInterface != null) {
                    wsDiscoveryClient.reinitialize(networkInterface)
                } else if (wsDiscoveryClient.isInitialized) {
                    wsDiscoveryClient.close()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        try {
            application.unregisterReceiver(broadcastReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was already unregistered or not yet registered, failing gracefully")
        }

        runBlocking {
            if (wifiP2pManagerView.thisGroupInfo.value != null) {
                wifiP2pManagerView.removeGroup()
            }

            groupInfoCollectionJob.cancelAndJoin()
            wifiP2pManagerView.teardown()
            wifiP2pManagerView.teardown()
            wsDiscoveryClient.close()
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
    }
}
