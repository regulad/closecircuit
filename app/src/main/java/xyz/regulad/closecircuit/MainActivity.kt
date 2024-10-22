package xyz.regulad.closecircuit

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import xyz.regulad.closecircuit.CloseCircuitViewModel.Companion.ANDROID_IP_WEBCAM_DEFAULT_PORT
import xyz.regulad.closecircuit.ui.theme.CloseCircuitTheme
import xyz.regulad.regulib.FlowCache.Companion.TAG
import xyz.regulad.regulib.compose.ImmersiveFullscreenContent
import xyz.regulad.regulib.compose.KeepScreenOn
import xyz.regulad.regulib.compose.WithDesiredOrientation
import xyz.regulad.regulib.compose.components.ByteQRCode
import xyz.regulad.regulib.showToast
import xyz.regulad.regulib.wifi.RUNTIME_REQUIRED_WIFI_PERMISSIONS

class MainActivity : ComponentActivity() {
    private val closeCircuitViewModel: CloseCircuitViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloseCircuitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    KeepScreenOn()
                    WithDesiredOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ImmersiveFullscreenContent()
                    }

                    val thisGroupInfo by closeCircuitViewModel.wifiP2pManagerView.thisGroupInfo.collectAsState()
                    val connectableClients by closeCircuitViewModel.wifiP2pSubnetScanner.reachableAddresses.collectAsState()

                    val context = LocalContext.current

                    val permissionState =
                        rememberMultiplePermissionsState(
                            permissions = RUNTIME_REQUIRED_WIFI_PERMISSIONS.toList(),
                            onPermissionsResult = { permissions ->
                                if (!permissions.all { it.value }) {
                                    context.showToast("Permissions are required to continue")
                                }
                            }
                        )

                    LaunchedEffect(permissionState.allPermissionsGranted, thisGroupInfo) {
                        if (permissionState.allPermissionsGranted && thisGroupInfo == null) {
                            closeCircuitViewModel.trySetupGroup()
                        } else {
                            permissionState.launchMultiplePermissionRequest()
                        }
                    }

                    Box(
                        Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    ) {
                        if (connectableClients.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                connectableClients.forEach { client ->
                                    LaunchedEffect(client) {
                                        Log.d(TAG, "Connecting to $client")
                                    }

                                    MjpegView(
                                        url = "http://${client.hostAddress!!}:${ANDROID_IP_WEBCAM_DEFAULT_PORT.toInt()}/video",
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .fillMaxSize(1F / connectableClients.size)
                                    )
                                }
                            }
                        } else if (thisGroupInfo != null) {
                            WifiNetworkScreen(thisGroupInfo!!.networkName, thisGroupInfo!!.passphrase)
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WifiNetworkScreen(ssid: String, passphrase: String) {
    Box(
        Modifier.fillMaxSize()
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SSID: $ssid",
                    modifier = Modifier.padding(16.dp)
                )
                Text(
                    text = "Passphrase: $passphrase",
                    modifier = Modifier.padding(16.dp)
                )
            }

            ByteQRCode(
                "WIFI:T:WPA;S:$ssid;P:$passphrase;H:true;".toByteArray(charset = Charsets.UTF_8),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
