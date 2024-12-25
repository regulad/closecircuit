package xyz.regulad.closecircuit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.regulad.regulib.StateFlowMapView.Companion.asMutableMap
import java.net.URL

class WSDiscoveryONVIFRoutingTable(
    val incomingProbeMatches: Flow<WSDiscovery11.ProbeMatch>,
    coroutineScope: CoroutineScope,
    val delinquencyInterval: Long,
) {
    // endpoint address URN -> Pair<first XAddr, last seen timestamp>
    private val internalMapState: MutableStateFlow<Map<String, Pair<String?, Long>>> = MutableStateFlow(emptyMap())
    private val internalMap = internalMapState.asMutableMap() // highkey my favorite thing I've developed

    private val _accessibleMjpegStreams = MutableStateFlow<Set<String>>(emptySet())
    val accessibleMjpegStreams = _accessibleMjpegStreams.asStateFlow()

    // at this point, I would like to implement a generic ONVIF camera client to get the MJPEG stream,
    // but unfortunately, Android IP webcam does not properly implement ONVIF.
    // with our incoming probematches, the only real thing we can do is create our own urls from the probe matches

    // example incoming XAddr: http://192.168.0.233:8080/onvif/device_service
    // example outgoing url for said XAddr: http://192.168.0.233:8080/video

    private fun doUpdate() {
        val it = internalMapState.value

        val now = System.currentTimeMillis()
        val delinquentAddresses = it.filterValues { (_, lastSeen) -> now - lastSeen > delinquencyInterval }.keys
        _accessibleMjpegStreams.value = it.filterKeys { it !in delinquentAddresses }.values.mapNotNull { (xAddr, _) ->
            xAddr?.let { xAddr ->
                // convert the URL
                val xAddrURL = URL(xAddr)
                val newUrl = URL(xAddrURL.protocol, xAddrURL.host, xAddrURL.port, "/video")
                newUrl.toString()
            }
        }.toSet()
    }

    init {
        coroutineScope.launch {
            incomingProbeMatches.collect { probeMatch ->
                internalMap[probeMatch.endpointReference.address] =
                    probeMatch.xAddrs.firstOrNull() to System.currentTimeMillis()
            }
        }

        coroutineScope.launch {
            internalMapState.collect {
                doUpdate()
            }
        }

        coroutineScope.launch {
            val updateIntervalTime = Math.min(1000, delinquencyInterval)
            while (isActive) {
                doUpdate()
                delay(updateIntervalTime) // less than the interval
            }
        }
    }
}
