package com.danieleze.networkshare

import android.os.Build
import android.util.Log
import com.jaredrummler.android.device.DeviceName
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.net.MulticastSocket

/**
 * Standalone WS-Discovery responder. Makes this device appear as a
 * "Computer" icon in Windows Explorer's Network view.
 *
 * Independent of WebDAVServer/FileManager — only needs an identity
 * (UUID + friendly name). Start/stop alongside WebDAVService's
 * foreground service lifecycle.
 */
class WSDiscoveryService(
    private val context: android.content.Context,
    var friendlyName: String,
    private val httpPort: Int = 49152
){
    companion object {
        private const val TAG = "WSDiscoveryService"
        private const val MULTICAST_ADDR = "239.255.255.250"
        private const val MULTICAST_PORT = 3702
        private const val SSDP_PORT = 1900
        private const val SSDP_DEVICE_TYPE = "urn:schemas-upnp-org:device:phone:1"
    }

    // Stable per-install UUID. Persist this in SharedPreferences if you
    // want it to survive reinstalls consistently — for now it's generated
    // once per instance lifetime via a simple lazy field; wire to your
    // PersistenceGuard prefs if you want it durable across app restarts.
    private val deviceUuid: String = UUID.randomUUID().toString()
    private val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    private val modelNumber = Build.MODEL
    private var modelName = "$manufacturer $modelNumber"
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val running = AtomicBoolean(false)
    private var multicastSocket: DatagramSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var llmnrSocket: MulticastSocket? = null
    private var mdnsSocket: MulticastSocket? = null
    private var ssdpNotifySocket: MulticastSocket? = null
    private var ssdpListenSocket: MulticastSocket? = null

    fun start() {
        if (running.getAndSet(true)) return
        getModelName()
        logAvailableInterfaces()
        thread(name = "WSD-Multicast") { runMulticastListener() }
        thread(name = "WSD-Http") { runHttpListener() }
        thread(name = "WSD-LLMNR") { runLlmnrListener() }
        thread(name = "WSD-MDNS") { runMdnsListener() }
        thread(name = "WSD-SSDP-Notify") { runSsdpNotify() }
        thread(name = "WSD-SSDP-Listen") { runSsdpMSearch() }
    }

    fun stop() {
        running.set(false)
        multicastSocket?.close()
        httpServerSocket?.close()
        llmnrSocket?.close()
        mdnsSocket?.close()
        ssdpNotifySocket?.close()
        ssdpListenSocket?.close()
        sendSsdpByebye()
    }

    private fun getModelName() {
        DeviceName.with(context).request { info, error ->
            val marketName = info?.marketName
            if (error == null && !marketName.isNullOrBlank()) {
                modelName = marketName
            }
        }
    }
    // ---------- UDP multicast Probe/ProbeMatch ----------

    private fun runMulticastListener() {
        try {
            Log.d(TAG, "Acquiring multicast lock...")
            val wifiManager = context.applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wifiManager.createMulticastLock("WSDiscoveryLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired: ${multicastLock?.isHeld}")

            val group = InetAddress.getByName(MULTICAST_ADDR)
            val iface = findInterface()
            Log.d(TAG, "Joining multicast group on interface: ${iface.name}")

            val mcastSocket = MulticastSocket(MULTICAST_PORT).apply {
                reuseAddress = true
            }
            multicastSocket = mcastSocket
            mcastSocket.joinGroup(InetSocketAddress(group, MULTICAST_PORT), iface)
            Log.d(TAG, "Successfully joined multicast group $MULTICAST_ADDR:$MULTICAST_PORT")

            val buf = ByteArray(8192)
            Log.d(TAG, "Listening for packets...")
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    mcastSocket.receive(packet)
                    Log.d(TAG, "RAW PACKET from ${packet.address}:${packet.port}, len=${packet.length}")
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Multicast receive error", e)
                    continue
                }

                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (msg.contains("Probe") && !msg.contains("ProbeMatch")) {
                    Log.d(TAG, "Probe detected, sending ProbeMatch...")
                    val relatesTo = extractMessageId(msg)
                    val response = buildProbeMatch(relatesTo)
                    val respBytes = response.toByteArray(Charsets.UTF_8)
                    val respPacket = DatagramPacket(
                        respBytes, respBytes.size, packet.address, packet.port
                    )
                    try {
                        DatagramSocket().use { it.send(respPacket) }
                        Log.d(TAG, "Sent ProbeMatch to ${packet.address}:${packet.port}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending ProbeMatch", e)
                    }
                }
            }
            try {
                mcastSocket.leaveGroup(InetSocketAddress(group, MULTICAST_PORT), findInterface())
                mcastSocket.close()
            } catch (_: Exception) {
                // already closed by stop(), that's fine
            }
        } catch (e: Exception) {
            Log.e(TAG, "Multicast listener failed", e)
        } finally {
            multicastLock?.release()
        }
    }

    private fun findInterface(): NetworkInterface {
        return NetworkInterface.getNetworkInterfaces().toList().firstOrNull { ni ->
            ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                    ni.inetAddresses.toList().any { !it.isLoopbackAddress }
        } ?: throw IllegalStateException("No suitable network interface found")
    }

    private fun extractMessageId(xml: String): String {
        val tag = "wsa:MessageID"
        val start = xml.indexOf("<$tag>")
        val end = xml.indexOf("</$tag>")
        return if (start != -1 && end != -1) xml.substring(start + tag.length + 2, end) else ""
    }

    private fun localIp(): String {
        findInterface().inetAddresses.toList().forEach {
            if (!it.isLoopbackAddress && it.hostAddress?.contains(":") == false) {
                return it.hostAddress ?: "0.0.0.0"
            }
        }
        return "0.0.0.0"
    }

    private fun buildProbeMatch(relatesToId: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof" xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07">
        <soap:Header>
        <wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
        <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
        <wsa:MessageID>urn:uuid:${UUID.randomUUID()}</wsa:MessageID>
        <wsa:RelatesTo>$relatesToId</wsa:RelatesTo>
        <wsd:AppSequence InstanceId="1" SequenceId="urn:uuid:${deviceUuid}" MessageNumber="1"></wsd:AppSequence>
        </soap:Header>
        <soap:Body>
        <wsd:ProbeMatches>
        <wsd:ProbeMatch>
        <wsa:EndpointReference><wsa:Address>urn:uuid:$deviceUuid</wsa:Address></wsa:EndpointReference>
        <wsd:Types>wsdp:Device</wsd:Types>
        <wsd:XAddrs>http://${localIp()}:$httpPort/$deviceUuid/</wsd:XAddrs>
        <wsd:MetadataVersion>1</wsd:MetadataVersion>
        </wsd:ProbeMatch>
        </wsd:ProbeMatches>
        </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    // ---------- TCP unicast metadata Get/GetResponse ----------

    private fun runHttpListener() {
        try {
            val server = ServerSocket(httpPort)
            httpServerSocket = server
            while (running.get()) {
                val client = try { server.accept() } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "HTTP accept error", e)
                    continue
                }
                thread(name = "WSD-Http-Client") { handleHttpClient(client) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP listener failed", e)
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use {
            val input = it.getInputStream()
            val output = it.getOutputStream()

            // Read headers to find Content-Length, then the body
            val headerBuf = StringBuilder()
            var contentLength = 0
            val byteBuf = ByteArray(1)
            var headerEnd = false
            while (!headerEnd) {
                val read = input.read(byteBuf)
                if (read == -1) return
                headerBuf.append(byteBuf[0].toInt().toChar())
                if (headerBuf.endsWith("\r\n\r\n")) headerEnd = true
            }
            val headerText = headerBuf.toString()
            Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                .find(headerText)?.let { m -> contentLength = m.groupValues[1].toInt() }

            val bodyBytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val r = input.read(bodyBytes, totalRead, contentLength - totalRead)
                if (r == -1) break
                totalRead += r
            }
            val body = String(bodyBytes, Charsets.UTF_8)
            val relatesToId = extractMessageId(body)

            val response = buildGetResponse(relatesToId)
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/soap+xml\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(httpResponse.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        }
    }

    private fun logAvailableInterfaces() {
        NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
            Log.d(TAG, "Interface: ${ni.name}, up=${ni.isUp}, loopback=${ni.isLoopback}, " +
                    "multicast=${ni.supportsMulticast()}, addrs=${ni.inetAddresses.toList().map { it.hostAddress }}")
        }
    }

    private fun buildGetResponse(relatesToId: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsx="http://schemas.xmlsoap.org/ws/2004/09/mex" xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof" xmlns:un0="http://schemas.microsoft.com/windows/pnpx/2005/10" xmlns:pub="http://schemas.microsoft.com/windows/pub/2005/07">
        <soap:Header>
        <wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
        <wsa:Action>http://schemas.xmlsoap.org/ws/2004/09/transfer/GetResponse</wsa:Action>
        <wsa:MessageID>urn:uuid:${UUID.randomUUID()}</wsa:MessageID>
        <wsa:RelatesTo>$relatesToId</wsa:RelatesTo>
        </soap:Header>
        <soap:Body>
        <wsx:Metadata>
        <wsx:MetadataSection Dialect="http://schemas.xmlsoap.org/ws/2006/02/devprof/ThisDevice">
        <wsdp:ThisDevice>
        <wsdp:FriendlyName>$friendlyName</wsdp:FriendlyName>
        <wsdp:FirmwareVersion>1.0</wsdp:FirmwareVersion>
        <wsdp:SerialNumber>1</wsdp:SerialNumber>
        </wsdp:ThisDevice>
        </wsx:MetadataSection>
        <wsx:MetadataSection Dialect="http://schemas.xmlsoap.org/ws/2006/02/devprof/ThisModel">
        <wsdp:ThisModel>
        <wsdp:Manufacturer>$manufacturer</wsdp:Manufacturer>
        <wsdp:ModelName>$modelName</wsdp:ModelName>
        <wsdp:ModelNumber>$modelNumber</wsdp:ModelNumber>
        <wsdp:PresentationUrl>http://$friendlyName:8080</wsdp:PresentationUrl> <un0:DeviceCategory>Phones</un0:DeviceCategory>
        </wsdp:ThisModel>
        </wsx:MetadataSection>
        <wsx:MetadataSection Dialect="http://schemas.xmlsoap.org/ws/2006/02/devprof/Relationship">
        <wsdp:Relationship Type="http://schemas.xmlsoap.org/ws/2006/02/devprof/host">
        <wsdp:Host>
        <wsa:EndpointReference><wsa:Address>urn:uuid:$deviceUuid</wsa:Address></wsa:EndpointReference>
        <wsdp:Types>wsdp:Device</wsdp:Types>
        <wsdp:ServiceId>urn:uuid:$deviceUuid</wsdp:ServiceId>
        </wsdp:Host>
        </wsdp:Relationship>
        </wsx:MetadataSection>
        </wsx:Metadata>
        </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    private fun runLlmnrListener() {
        try {
            val group = InetAddress.getByName("224.0.0.252")
            val socket = MulticastSocket(5355).apply { reuseAddress = true }
            llmnrSocket = socket
            socket.joinGroup(InetSocketAddress(group, 5355), findInterface())
            Log.d(TAG, "LLMNR listener started")

            val buf = ByteArray(512)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "LLMNR receive error", e)
                    continue
                }
                val data = packet.data.copyOf(packet.length)
                if (data.size < 12) continue
                val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                if ((flags and 0x8000) != 0) continue // skip responses
                val txId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val name = parseName(data)
                val qTypeOffset = 12 + name.split(".").filter { it.isNotEmpty() }.sumOf { it.length + 1 } + 1
                if (qTypeOffset + 1 >= data.size) continue
                val qType = ((data[qTypeOffset].toInt() and 0xFF) shl 8) or (data[qTypeOffset + 1].toInt() and 0xFF)
                if (qType != 1) continue // only respond to A queries
                Log.d(TAG, "LLMNR query for: $name")
                if (name.equals(friendlyName, ignoreCase = true)) {
                    val ipBytes = InetAddress.getByName(localIp()).address
                    val response = buildLlmnrAResponse(txId, name, ipBytes)
                    try {
                        DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(5355))
                        }.use {
                            it.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                        Log.d(TAG, "Sent LLMNR response for $name to ${packet.address}")
                    } catch (e: Exception) { Log.w(TAG, "LLMNR send failed", e) }
                }
            }
            try {
                socket.leaveGroup(InetSocketAddress(group, 5355), findInterface())
                socket.close()
            } catch (_: Exception) {
                // already closed by stop(), that's fine
            }
        } catch (e: Exception) { Log.e(TAG, "LLMNR listener failed", e) }
    }

    private fun runMdnsListener() {
        try {
            val group = InetAddress.getByName("224.0.0.251")
            val socket = MulticastSocket(5353).apply { reuseAddress = true }
            mdnsSocket = socket
            socket.joinGroup(InetSocketAddress(group, 5353), findInterface())
            socket.networkInterface = findInterface()
            Log.d(TAG, "mDNS listener started")

            val buf = ByteArray(512)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "mDNS receive error", e)
                    continue
                }
                val data = packet.data.copyOf(packet.length)
                if (data.size < 12) continue
                val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                if ((flags and 0x8000) != 0) continue
                val name = parseName(data)
                Log.d(TAG, "mDNS query for: $name")
                if (name.removeSuffix(".local").equals(friendlyName, ignoreCase = true)) {
                    val ipBytes = InetAddress.getByName(localIp()).address
                    val response = buildMdnsAResponse(name, ipBytes)
                    try {
                        socket.send(DatagramPacket(response, response.size, group, 5353))
                        Log.d(TAG, "Sent mDNS response for $name")
                    } catch (e: Exception) { Log.w(TAG, "mDNS send failed", e) }
                }
            }
            try { socket.leaveGroup(InetSocketAddress(group, 5353), findInterface()); socket.close() } catch (_: Exception) {}
        } catch (e: Exception) { Log.e(TAG, "mDNS listener failed", e) }
    }

    private fun parseName(data: ByteArray): String {
        val sb = StringBuilder()
        var pos = 12
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            if (len >= 0xC0) break // pointer — ignore for our purposes
            pos++
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, minOf(len, data.size - pos), Charsets.UTF_8))
            pos += len
        }
        return sb.toString()
    }

    private fun buildLlmnrAResponse(txId: Int, name: String, ipBytes: ByteArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream()

        // Encode name
        val nameBytes = java.io.ByteArrayOutputStream()
        name.split(".").filter { it.isNotEmpty() }.forEach { part ->
            nameBytes.write(part.length)
            nameBytes.write(part.toByteArray(Charsets.UTF_8))
        }
        nameBytes.write(0)
        val nameArr = nameBytes.toByteArray()

        // Header — QR=1 (response)
        buf.write(txId shr 8); buf.write(txId and 0xFF)
        buf.write(0x80); buf.write(0x00) // flags: response
        buf.write(0); buf.write(1)       // QDCOUNT: echo question
        buf.write(0); buf.write(1)       // ANCOUNT
        buf.write(0); buf.write(0)       // NSCOUNT
        buf.write(0); buf.write(0)       // ARCOUNT

        // Echo question
        buf.write(nameArr)
        buf.write(0); buf.write(1)       // Type A
        buf.write(0); buf.write(1)       // Class IN

        // Answer
        buf.write(nameArr)
        buf.write(0); buf.write(1)       // Type A
        buf.write(0); buf.write(1)       // Class IN
        buf.write(0); buf.write(0); buf.write(0); buf.write(30) // TTL 30s
        buf.write(0); buf.write(4)       // RDLENGTH
        buf.write(ipBytes)

        return buf.toByteArray()
    }

    private fun buildMdnsAResponse(name: String, ipBytes: ByteArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream()

        val nameBytes = java.io.ByteArrayOutputStream()
        name.split(".").filter { it.isNotEmpty() }.forEach { part ->
            nameBytes.write(part.length)
            nameBytes.write(part.toByteArray(Charsets.UTF_8))
        }
        nameBytes.write(0)
        val nameArr = nameBytes.toByteArray()

        // Header — txId=0, QR=1 (response), authoritative
        buf.write(0); buf.write(0)       // txId always 0 for mDNS
        buf.write(0x84); buf.write(0x00) // flags: response + authoritative
        buf.write(0); buf.write(0)       // QDCOUNT: 0 — no question echoed
        buf.write(0); buf.write(1)       // ANCOUNT: 1
        buf.write(0); buf.write(0)       // NSCOUNT
        buf.write(0); buf.write(0)       // ARCOUNT

        // Answer
        buf.write(nameArr)
        buf.write(0); buf.write(1)       // Type A
        buf.write(0); buf.write(1)       // Class IN
        buf.write(0); buf.write(0); buf.write(0); buf.write(120) // TTL 120s
        buf.write(0); buf.write(4)       // RDLENGTH
        buf.write(ipBytes)

        return buf.toByteArray()
    }

    private fun buildSsdpNotify(nts: String): String {
        val location = "http://${localIp()}:$httpPort/upnp/description.xml"
        return "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $MULTICAST_ADDR:$SSDP_PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: $location\r\n" +
                "NT: $SSDP_DEVICE_TYPE\r\n" +
                "NTS: $nts\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 NetworkShare/1.0\r\n" +
                "USN: uuid:$deviceUuid::$SSDP_DEVICE_TYPE\r\n" +
                "\r\n"
    }

    private fun buildSsdpMSearchResponse(st: String): String {
        val location = "http://${localIp()}:$httpPort/upnp/description.xml"
        return "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: $location\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 NetworkShare/1.0\r\n" +
                "ST: $st\r\n" +
                "USN: uuid:$deviceUuid::$st\r\n" +
                "\r\n"
    }

    private fun runSsdpNotify() {
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            while (running.get()) {
                val msg = buildSsdpNotify("ssdp:alive").toByteArray()
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT))
                }
                Log.d(TAG, "Sent ssdp:alive")
                Thread.sleep(30_000)
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "SSDP notify error: ${e.message}")
        }
    }

    private fun runSsdpMSearch() {
        Log.d(TAG, "SSDP M-SEARCH listener starting...")
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            val socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                joinGroup(InetSocketAddress(group, SSDP_PORT), findInterface())
            }
            ssdpListenSocket = socket
            Log.d(TAG, "SSDP M-SEARCH socket bound to port $SSDP_PORT")

            val buf = ByteArray(1024)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "SSDP M-SEARCH receive error", e)
                    continue
                }
                val message = String(packet.data, 0, packet.length)
                if (message.contains("M-SEARCH") &&
                    (message.contains("phone:1") ||
                            message.contains("ssdp:all") ||
                            message.contains("upnp:rootdevice"))
                ) {
                    val st = Regex("ST:\\s*(.+)\\r\\n").find(message)?.groupValues?.get(1)?.trim()
                        ?: "upnp:rootdevice"
                    Log.d(TAG, "M-SEARCH matched, responding to ${packet.address}:${packet.port}")
                    val response = buildSsdpMSearchResponse(st).toByteArray()
                    try {
                        DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(1900))
                        }.use { sendSocket ->
                            sendSocket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                        }
                        Log.d(TAG, "Sent SSDP response from port 1900 to ${packet.address}:${packet.port}")
                    } catch (e: Exception) {
                        Log.w(TAG, "SSDP response send failed: ${e.message}")
                    }
                }
            }
            socket.leaveGroup(InetSocketAddress(group, SSDP_PORT), findInterface())
            socket.close()
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "SSDP M-SEARCH listen error: ${e.message}")
        }
    }

    private fun sendSsdpByebye() {
        try {
            val msg = buildSsdpNotify("ssdp:byebye").toByteArray()
            val group = InetAddress.getByName(MULTICAST_ADDR)
            DatagramSocket().use { it.send(DatagramPacket(msg, msg.size, group, SSDP_PORT)) }
            Log.d(TAG, "Sent ssdp:byebye")
        } catch (e: Exception) {
            Log.w(TAG, "Byebye failed: ${e.message}")
        }
    }
}