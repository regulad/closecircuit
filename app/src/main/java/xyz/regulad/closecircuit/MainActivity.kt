package xyz.regulad.closecircuit

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.hardware.Sensor.TYPE_ACCELEROMETER
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.regulad.closecircuit.CloseCircuitViewModel.Companion.ANDROID_IP_WEBCAM_DEFAULT_PORT
import xyz.regulad.closecircuit.ui.theme.CloseCircuitTheme
import xyz.regulad.regulib.compose.*
import xyz.regulad.regulib.compose.components.ByteQRCode
import xyz.regulad.regulib.compose.components.DynamicColumnRowGridLayout
import xyz.regulad.regulib.compose.components.MjpegView
import xyz.regulad.regulib.showToast
import xyz.regulad.regulib.wifi.RUNTIME_REQUIRED_WIFI_PERMISSIONS
import kotlin.time.Duration.Companion.seconds

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
                    ImmersiveFullscreenContent()

                    val thisGroupInfo by closeCircuitViewModel.wifiP2pManagerView.thisGroupInfo.collectAsState()
                    val connectableClients by closeCircuitViewModel.networkSubnetScanner.reachableAddresses.collectAsState()

                    val context = LocalContext.current

                    val permissionState =
                        rememberMultiplePermissionsState(
                            permissions = (RUNTIME_REQUIRED_WIFI_PERMISSIONS).toList(),
                            onPermissionsResult = { permissions ->
                                if (!permissions.all { it.value }) {
                                    context.showToast("Permissions are required to continue")
                                }
                            }
                        )

                    LaunchedEffect(permissionState.allPermissionsGranted) {
                        if (permissionState.allPermissionsGranted) {
                            withContext(Dispatchers.Main) {
                                closeCircuitViewModel.setupAp()
                            }
                        } else {
                            permissionState.launchMultiplePermissionRequest()
                        }
                    }

                    // Z positive linear acceleration: the phone (car) is moving forward

                    val sensorEvent by rememberSensorState(TYPE_ACCELEROMETER) // i would use TYPE_LINEAR_ACCELERATION but it's not available on all devices
                    val backingUp by remember {
                        derivedStateOf {
                            (sensorEvent?.values?.get(2) ?: 0F) < -3F // TODO: MAGIC NUMBER ALERT (make configurable)
                        }
                    }

                    var taps by remember { mutableStateOf(0) }
                    val timeSinceLastBackedUp by rememberDurationSinceComposition(backingUp, taps)
                    val deviceActive = backingUp || timeSinceLastBackedUp == null || timeSinceLastBackedUp!! < 60.seconds // don't derive this

                    WithBrightness(if (deviceActive) 1.0F else 0.1F)

                    Box(
                        Modifier
                            .fillMaxSize()
                    ) {
                        if (connectableClients.isNotEmpty()) {
                            DynamicColumnRowGridLayout(
                                modifier = Modifier.fillMaxSize(),
                                items = connectableClients.toList(),
                            ) { client, modifier ->
                                Box(
                                    modifier = modifier.background(Color.DarkGray)
                                ) {
                                    if (deviceActive) {
                                        MjpegView(
                                            url = "http://${client.hostAddress!!}:${ANDROID_IP_WEBCAM_DEFAULT_PORT.toInt()}/video",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Surface(onClick = { taps++ }, modifier = Modifier.fillMaxSize()) {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                Text(
                                                    text = "Sleeping (tap or move to wake)",
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        }
                                    }
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
