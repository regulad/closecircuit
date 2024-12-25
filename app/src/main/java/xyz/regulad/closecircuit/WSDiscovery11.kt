package xyz.regulad.closecircuit

import android.app.Application
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.dom4j.DocumentHelper
import org.dom4j.Namespace
import org.dom4j.Node
import org.dom4j.QName
import java.net.*
import java.util.*

/**
 * This class implements a complete ad-hoc WS-Discovery client and allows for the discovery of devices on the local network.
 *
 * It is implemented according to [https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html](https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html).
 *
 * Note that this client does NOT implement any elements of the managed mode of the WS-Discovery protocol.
 *
 * The class should be attached to an [AndroidViewModel], and the coroutine scope should be the [AndroidViewModel.viewModelScope].
 *
 * @param probeDispatchIntervalMs The interval between dispatching probe messages to the network. Per the spec, this isn't strictly required, but it helps cases where the network may be unreliable. Set to `null` for no probes, only responses to hello messages.
 */
class WSDiscovery11(
    private val application: Application,
    private val coroutineScope: CoroutineScope,
    private val probeDispatchIntervalMs: Long? = null,
    private val probeTypesSet: Set<String> = emptySet()
) {
    companion object {
        /**
         * The port used by the WS-Discovery protocol.
         *
         * TCP (incoming):
         * - Probe responses (probe match; PM) messages are received on this port.
         * - Resolve match (RM) messages are received on this port.
         *
         * TCP (outgoing):
         * -
         *
         * UDP (incoming):
         * - Hello messages are received on this port.
         * - Bye messages are received on this port.
         *
         * UDP (outgoing):
         * - Multicast Probe messages are sent to this port.
         * - Multicast Resolve messages are sent to this port.
         *
         * [See OASIS documentation](https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html#:~:text=%C2%B7-,DISCOVERY_PORT,-%3A%20port%203702%20%5B)
         *
         * [See IANA registration](https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml?search=3702)
         */
        private const val DISCOVERY_PORT = 3702;

        /**
         * The multicast address for IPv4.
         *
         * Incoming Hello messages from services and outgoing Probe messages are received/sent to this address respectively.
         *
         * [See OASIS documentation](https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html#:~:text=IPv4%20multicast%20address)
         */
        private const val IPV4_MULTICAST_ADDRESS = "239.255.255.250";

        /**
         * See [IPV4_MULTICAST_ADDRESS].
         */
        private val IPV4_INET_ADDRESS = InetAddress.getByName(IPV4_MULTICAST_ADDRESS);

        /**
         * The multicast address for IPv6.
         *
         * Functions identically to [IPV4_MULTICAST_ADDRESS] but for IPv6.
         *
         * [See OASIS documentation](https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html#:~:text=IPv6%20multicast%20address)
         */
        private const val IPV6_MULTICAST_ADDRESS = "[FF02::C]";

        /**
         * See [IPV6_MULTICAST_ADDRESS].
         */
        private val IPV6_INET_ADDRESS = InetAddress.getByName(IPV6_MULTICAST_ADDRESS);

        // these are sensible norms
        private val MULTICAST_PACKET_TTL = 4
        private val MULTICAST_SOCKET_TIMEOUT = 5000

        // https://en.wikipedia.org/wiki/User_Datagram_Protocol#:~:text=The%20field%20size%20sets%20a,20%2Dbyte%20IP%20header).
        private val UDP_MAX_PACKET_SIZE = 65_507

        private const val TAG = "WSDiscovery"
    }

    // https://docs.oasis-open.org/ws-dd/discovery/1.1/os/wsdd-discovery-1.1-spec-os.html#WSAddressing:~:text=2%C2%A0%C2%A0%C2%A0%C2%A0%C2%A0%20Model-,2.1%20Endpoint%20References,-As%20part%20of
    // https://www.w3.org/TR/2006/REC-ws-addr-core-20060509/#:~:text=SHOULD%20be%20used.-,2.2%20Endpoint%20Reference%20XML%20Infoset%20Representation,-This%20section%20defines
    data class EndpointReference(
        val address: String,
        // the rest of the EndpointReference spec is OPTIONAL to implement and I *really* don't care
        // todo: impl EndpointReference
    ) {
        companion object {
            fun fromXMLNode(node: Node): EndpointReference {
                val addressNode =
                    node.selectSingleNode("//*[local-name()='Address' and namespace-uri()='http://schemas.xmlsoap.org/ws/2004/08/addressing']")
                return EndpointReference(addressNode.text)
            }
        }
    }

    // https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html#:~:text=/s%3AEnvelope/s%3ABody/d%3AProbeMatches/d,d%3AMetadataVersion%20in%20Section%204.1%20Hello.
    data class ProbeMatch(
        val endpointReference: EndpointReference,
        val types: Set<String>,
        val scopes: Set<String>,
        val xAddrs: Set<String>,
        val metadataVersion: Int
    ) {
        companion object {
            fun fromXMLNode(node: Node): ProbeMatch {
                val endpointReferenceElement =
                    node.selectSingleNode("//*[local-name()='EndpointReference' and namespace-uri()='http://schemas.xmlsoap.org/ws/2004/08/addressing']")
                val endpointReference = EndpointReference.fromXMLNode(endpointReferenceElement)

                val typesElement =
                    node.selectSingleNode("//*[local-name()='Types' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val types = typesElement.text.split(" ").toSet()

                val scopesElement =
                    node.selectSingleNode("//*[local-name()='Scopes' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val scopes = scopesElement.text.split(" ").toSet()

                val xAddrsElement =
                    node.selectSingleNode("//*[local-name()='XAddrs' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val xAddrs = xAddrsElement.text.split(" ").toSet()

                val metadataVersionElement =
                    node.selectSingleNode("//*[local-name()='MetadataVersion' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val metadataVersion = metadataVersionElement.text.toInt()

                return ProbeMatch(endpointReference, types, scopes, xAddrs, metadataVersion)
            }
        }
    }

    // curiously almost exactly the same as the probematch
    // https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html#:~:text=/s%3AEnvelope/s%3ABody/d%3AResolveMatches/d,d%3AMetadataVersion%20in%20Section%204.1%20Hello.
    data class ResolveMatch(
        val endpointReference: EndpointReference,
        val types: Set<String>,
        val scopes: Set<String>,
        val xAddrs: Set<String>,
        val metadataVersion: Int
    ) {
        companion object {
            fun fromXMLNode(node: Node): ResolveMatch {
                val endpointReferenceElement =
                    node.selectSingleNode("//*[local-name()='EndpointReference' and namespace-uri()='http://schemas.xmlsoap.org/ws/2004/08/addressing']")
                val endpointReference = EndpointReference.fromXMLNode(endpointReferenceElement)

                val typesElement =
                    node.selectSingleNode("//*[local-name()='Types' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val types = typesElement.text.split(" ").toSet()

                val scopesElement =
                    node.selectSingleNode("//*[local-name()='Scopes' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val scopes = scopesElement.text.split(" ").toSet()

                val xAddrsElement =
                    node.selectSingleNode("//*[local-name()='XAddrs' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val xAddrs = xAddrsElement.text.split(" ").toSet()

                val metadataVersionElement =
                    node.selectSingleNode("//*[local-name()='MetadataVersion' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                val metadataVersion = metadataVersionElement.text.toInt()

                return ResolveMatch(endpointReference, types, scopes, xAddrs, metadataVersion)
            }
        }
    }

    private val _probeMatchFlow = MutableSharedFlow<ProbeMatch>()
    val probeMatchFlow = _probeMatchFlow.asSharedFlow()

    private val _resolveMatchFlow = MutableSharedFlow<ResolveMatch>()
    val resolveMatchFlow = _resolveMatchFlow.asSharedFlow()

    var isInitialized = false
        private set

    private val wifiManager = application.getSystemService<WifiManager>()!!
    private val multicastLock: MulticastLock

    // https://developer.android.com/reference/java/net/MulticastSocket
    // unicast responses are also received on this socket
    private lateinit var multicastSocket: MulticastSocket
    private lateinit var socketReadJob: Job

    private var probeJob: Job? = null

    init {
        multicastLock = wifiManager.createMulticastLock("WSDiscovery")
        multicastLock.setReferenceCounted(true)
    }

    private fun initialize(netIf: NetworkInterface) {
        multicastSocket = MulticastSocket(DISCOVERY_PORT).apply {
            timeToLive = MULTICAST_PACKET_TTL
            soTimeout = MULTICAST_SOCKET_TIMEOUT
            networkInterface = netIf
        }

        if (netIf.supportsMulticast4()) {
            multicastSocket.joinGroup(IPV4_INET_ADDRESS)
        }

        if (netIf.supportsMulticast6()) {
            multicastSocket.joinGroup(IPV6_INET_ADDRESS)
        }

        if (!netIf.supportsMulticast4() && !netIf.supportsMulticast6()) {
            Log.w(TAG, "Network interface does not support multicast, continuing but no way to guarantee it works")
        }

        socketReadJob = coroutineScope.launch {
            Log.d(TAG, "Listening for incoming WS-Discovery messages")
            // using the multicast lock here may block, run it on the IO dispatcher
            withContext(Dispatchers.IO) {
                // we need to use the multicast lock on the outer layer, as if we used it in the while loop,
                // it would be released while a packet may be incoming
                multicastLock.use {
                    while (isActive) {
                        try {
                            val buffer = ByteArray(UDP_MAX_PACKET_SIZE)
                            val packet = DatagramPacket(buffer, buffer.size)
                            // this blocks, run it on the IO dispatcher
                            runBlockingCancellable {
                                multicastSocket.receive(packet)
                            }

                            // process the packet
                            val xmlDocumentString = String(buffer, 0, packet.length)
                            Log.d(TAG, "Received WS-Discovery message: $xmlDocumentString")
                            val xmlDocument = DocumentHelper.parseText(xmlDocumentString)

                            val envelope =
                                xmlDocument.selectSingleNode("//*[local-name()='Envelope' and namespace-uri()='http://www.w3.org/2003/05/soap-envelope']")
                            if (envelope == null) {
                                Log.w(TAG, "Received WS-Discovery message without an envelope")
                                continue
                            }

                            val header =
                                envelope.selectSingleNode("//*[local-name()='Header' and namespace-uri()='http://www.w3.org/2003/05/soap-envelope']")
                            if (header == null) {
                                Log.w(TAG, "Received WS-Discovery message without a header")
                                continue
                            }

                            val body =
                                envelope.selectSingleNode("//*[local-name()='Body' and namespace-uri()='http://www.w3.org/2003/05/soap-envelope']")
                            if (body == null) {
                                Log.w(TAG, "Received WS-Discovery message without a body")
                                continue
                            }

                            val actionElement =
                                header.selectSingleNode("//*[local-name()='Action' and namespace-uri()='http://schemas.xmlsoap.org/ws/2004/08/addressing']")
                            if (actionElement == null) {
                                Log.w(TAG, "Received WS-Discovery message without an action")
                                continue
                            }

                            when (val action = actionElement.text) {
                                // interestingly, the oasis spec has different actions than the xmlsoap spec
                                // https://schemas.xmlsoap.org/ws/2005/04/discovery/ws-discovery.wsdl vs https://docs.oasis-open.org/ws-dd/ns/discovery/2009/01
                                // they appear to be cross-compatible, however.

                                "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/Hello", "http://schemas.xmlsoap.org/ws/2005/04/discovery/Hello" -> {
                                    // Android IP webcam, the target camera for this app, the mecca of Engineering that it is, doesn't implement Hello or Goodbye.
                                    // Because of this, this implementation of WSDiscovery does not handle Hello or Goodbye messages.

                                    Log.d(TAG, "Received WS-Discovery Hello message")
                                    // todo: impl
                                }

                                "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/Bye", "http://schemas.xmlsoap.org/ws/2005/04/discovery/Bye" -> {
                                    Log.d(TAG, "Received WS-Discovery Bye message")
                                    // todo: impl
                                }

                                "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/ProbeMatches", "http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches" -> {
                                    Log.d(TAG, "Received WS-Discovery ProbeMatches message")

                                    val probeMatchesElement =
                                        body.selectSingleNode("//*[local-name()='ProbeMatches' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                                    val probeMatchElementList =
                                        probeMatchesElement.selectNodes("//*[local-name()='ProbeMatch' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                                    val probeMatchList = probeMatchElementList.map { ProbeMatch.fromXMLNode(it) }
                                    probeMatchList.forEach { _probeMatchFlow.emit(it) }
                                }

                                "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/ResolveMatches", "http://schemas.xmlsoap.org/ws/2005/04/discovery/ResolveMatches" -> {
                                    Log.d(TAG, "Received WS-Discovery ResolveMatches message")

                                    val resolveMatchesElement =
                                        body.selectSingleNode("//*[local-name()='ResolveMatches' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                                    val resolveMatchElementList =
                                        resolveMatchesElement.selectNodes("//*[local-name()='ResolveMatch' and namespace-uri()='http://schemas.xmlsoap.org/ws/2005/04/discovery']")
                                    val resolveMatchList = resolveMatchElementList.map { ResolveMatch.fromXMLNode(it) }
                                    resolveMatchList.forEach { _resolveMatchFlow.emit(it) }
                                }

                                "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01/Probe", "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe" -> {
                                    // probe, ignore completely
                                }

                                // if the message is something else, it doesn't necessarily mean its invalid, but we don't care about it.
                                // examples are Probes and Resolves sent by other clients: they are for services to respond to, not for us to respond to.
                                else -> {
                                    Log.w(TAG, "Received WS-Discovery message with unknown action: $action")
                                }
                            }
                        } catch (
                            e: Exception
                        ) {
                            if (e !is CancellationException && e !is SocketTimeoutException) {
                                Log.e(TAG, "Error while processing WS-Discovery message", e)
                            }
                        }
                    }
                }
                multicastLock.suspendUntilReleased() // unless something is wrong, this should never suspend
            }
        }

        // at this point, the machinery is ready to use
        isInitialized = true

        dispatchProbe()

        if (probeDispatchIntervalMs != null) {
            probeJob = coroutineScope.launch {
                delay(probeDispatchIntervalMs)
                while (isActive) {
                    dispatchProbe()
                    delay(probeDispatchIntervalMs)
                }
            }
        }
    }

    /**
     * Reinitializes the WS-Discovery client by recreating the underlying [MulticastSocket] and rejoining groups. Needed for situations like leaving/joining a group or network.
     */
    suspend fun reinitialize(netIf: NetworkInterface) {
        if (isInitialized) {
            // no need to close if we never initialized
            close()
        }
        initialize(netIf)
    }

    suspend fun close() {
        if (!isInitialized) {
            throw IllegalStateException("WS-Discovery client not initialized!")
        }

        probeJob?.cancelAndJoin()
        socketReadJob.cancelAndJoin()

        withContext(Dispatchers.IO) {
            multicastSocket.leaveGroup(IPV4_INET_ADDRESS)
            multicastSocket.leaveGroup(IPV6_INET_ADDRESS)
            multicastSocket.close()
        }

        isInitialized = false
    }

    fun createProbe(): String {
        val doc = DocumentHelper.createDocument()

        // Create namespaces
        // s, a, & d are convention over SOAP-ENV, wsa, & wsdd, but because it's XML it doesn't matter
        val soapNamespace = Namespace.get("s", "http://www.w3.org/2003/05/soap-envelope")
        val addressingNamespace = Namespace.get("a", "http://schemas.xmlsoap.org/ws/2004/08/addressing")
        val discoveryNamespace = Namespace.get("d", "http://schemas.xmlsoap.org/ws/2005/04/discovery")

        // Create SOAP Envelope
        val envelope = doc.addElement(QName("Envelope", soapNamespace))

        // Add other namespaces to envelope
        envelope.add(addressingNamespace)
        envelope.add(discoveryNamespace)

        // Create Header
        val header = envelope.addElement(QName("Header", soapNamespace))

        // Add Action
        val action = header.addElement(QName("Action", addressingNamespace))
        action.text = "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"

        // Add MessageID
        val messageId = header.addElement(QName("MessageID", addressingNamespace))
        messageId.text = "urn:uuid:${UUID.randomUUID()}"

        // Add To
        val to = header.addElement(QName("To", addressingNamespace))
        to.text = "urn:schemas-xmlsoap-org:ws:2005:04:discovery"

        // Create Body
        val body = envelope.addElement(QName("Body", soapNamespace))

        // Add Probe element
        val probe = body.addElement(QName("Probe", discoveryNamespace))

        // Optional: Add Types if you're looking for specific service types
        if (probeTypesSet.isNotEmpty()) {
            val types = probe.addElement(QName("Types", discoveryNamespace))
            types.text = probeTypesSet.joinToString(" ")
        }

        return doc.asXML()
    }

    /**
     * Dispatches a probe message to the network.
     *
     * Useful for "catching up" with the state of the network when the client may not have been listening to Hello and Bye messages.
     */
    fun dispatchProbe() {
        if (!isInitialized) {
            throw IllegalStateException("WS-Discovery client not initialized!")
        }

        val probeMessage = createProbe()

        Log.d(TAG, "Dispatching WS-Discovery probe message: $probeMessage")

        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val probeMessageUTF8Bytes = probeMessage.toByteArray(Charsets.UTF_8)

                val currentInterface =
                    multicastSocket.networkInterface // we use If2, never If1 since then it would be either ipv4-only or ipv6 only

                if (!currentInterface.supportsMulticast()) {
                    throw IllegalStateException("Network interface does not support multicast")
                }

                if (currentInterface.supportsMulticast4()) {
                    try {
                        val packet = DatagramPacket(
                            probeMessageUTF8Bytes,
                            probeMessageUTF8Bytes.size,
                            IPV4_INET_ADDRESS,
                            DISCOVERY_PORT
                        )
                        multicastSocket.send(packet)
                        Log.d(TAG, "WS-Discovery probe message dispatched via IPv4")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while sending WS-Discovery probe message via IPv4", e)
                    }
                }

                if (currentInterface.supportsMulticast6()) {
                    try {
                        val packet = DatagramPacket(
                            probeMessageUTF8Bytes,
                            probeMessageUTF8Bytes.size,
                            IPV6_INET_ADDRESS,
                            DISCOVERY_PORT
                        )
                        multicastSocket.send(packet)
                        Log.d(TAG, "WS-Discovery probe message dispatched via IPv6")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while sending WS-Discovery probe message via IPv6", e)
                    }
                }
            }
        }
    }
}
