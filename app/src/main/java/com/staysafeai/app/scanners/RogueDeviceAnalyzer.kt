package com.staysafeai.app.scanners

import android.content.Context
import android.net.wifi.WifiManager
import com.staysafeai.app.models.RogueDevice
import com.staysafeai.app.models.RogueDeviceRisk
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

class RogueDeviceAnalyzer(private val context: Context) {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    companion object {
        // Camera-specific ports
        val CAMERA_PORTS = mapOf(
            554   to "RTSP stream (IP camera)",
            8554  to "RTSP alternate (IP camera)",
            80    to "HTTP (web interface)",
            443   to "HTTPS (web interface)",
            8080  to "HTTP alternate / ONVIF",
            8443  to "HTTPS alternate",
            37777 to "Dahua proprietary",
            34567 to "DVR/NVR stream",
            9000  to "Hikvision SDK",
            8000  to "Hikvision SDK alternate",
            5000  to "UPnP / camera discovery",
            1935  to "RTMP stream",
            6668  to "Tuya smart device",
            3702  to "WS-Discovery (ONVIF)",
            4567  to "Amcrest/Reolink"
        )

        // Known camera OUI prefixes (MAC first 3 bytes)
        val CAMERA_OUIS = mapOf(
            "10:52:1C" to "Hikvision",
            "44:19:B6" to "Hikvision",
            "28:57:BE" to "Dahua",
            "90:02:A9" to "Dahua",
            "D8:3A:DD" to "Tuya Smart",
            "68:57:2D" to "Tuya OEM",
            "50:02:91" to "Tuya OEM",
            "84:E3:42" to "Tuya OEM",
            "B4:A3:82" to "Reolink",
            "EC:71:DB" to "Wyze",
            "2C:AA:8E" to "Wyze Labs",
            "48:EE:0C" to "Ezviz",
            "C8:02:8F" to "Amcrest",
            "00:23:63" to "Axis",
            "AC:CC:8E" to "Espressif IoT",
            "60:01:94" to "Espressif IoT",
            "A4:CF:12" to "Espressif IoT",
            "24:6F:28" to "Espressif IoT",
            "DC:4F:22" to "Espressif IoT",
            "18:FE:34" to "Espressif IoT",
            "30:AE:A4" to "Espressif IoT",
            "00:1A:22" to "VSTARCAM",
            "C4:4B:D1" to "Foscam",
            "74:DA:38" to "Edimax",
            "80:1F:02" to "Wansview",
            "8C:AB:8E" to "Sricam",
            "F4:4E:FD" to "Generic spy cam OEM",
            "70:F1:1C" to "TP-Link camera",
            "3C:52:A1" to "TP-Link IoT"
        )

        // Ports that CONFIRM a camera (not just suspect)
        val CONFIRMED_CAMERA_PORTS = setOf(554, 8554, 37777, 34567, 9000, 3702, 4567)

        const val PORT_TIMEOUT_MS = 300  // Fast scan
        const val SUBNET_SCAN_TIMEOUT_MS = 15000L
    }

    suspend fun analyze(): List<RogueDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<RogueDevice>()

        // Step 1: Read ARP table for connected devices
        val arpDevices = readARPTable()

        // Step 2: Port scan each device concurrently
        val scanJobs = arpDevices.map { (ip, mac) ->
            async {
                val openPorts = scanPorts(ip)
                val vendor = identifyVendor(mac)
                val risk = calculateRisk(mac, ip, openPorts, vendor)
                RogueDevice(
                    ipAddress = ip,
                    macAddress = mac,
                    vendorName = vendor.name,
                    isCameraVendor = vendor.isCamera,
                    openPorts = openPorts,
                    hasCameraPort = openPorts.keys.any { it in CONFIRMED_CAMERA_PORTS },
                    hasRTSP = openPorts.containsKey(554) || openPorts.containsKey(8554),
                    hasONVIF = openPorts.containsKey(3702) || openPorts.containsKey(8080),
                    riskScore = risk.score,
                    riskLevel = risk.level,
                    riskReason = risk.reasons,
                    streamUrl = buildStreamUrl(ip, openPorts)
                )
            }
        }

        // Step 3: Also scan gateway range for undeclared devices
        val gatewayDevices = scanGatewayRange()
        val gatewayJobs = gatewayDevices
            .filter { ip -> arpDevices.none { it.first == ip } }
            .map { ip ->
                async {
                    val openPorts = scanPorts(ip)
                    if (openPorts.isEmpty()) return@async null
                    val risk = calculateRisk("", ip, openPorts, VendorInfo("Unknown", false))
                    RogueDevice(
                        ipAddress = ip,
                        macAddress = "Unknown",
                        vendorName = "Unknown (not in ARP)",
                        isCameraVendor = false,
                        openPorts = openPorts,
                        hasCameraPort = openPorts.keys.any { it in CONFIRMED_CAMERA_PORTS },
                        hasRTSP = openPorts.containsKey(554) || openPorts.containsKey(8554),
                        hasONVIF = openPorts.containsKey(3702) || openPorts.containsKey(8080),
                        riskScore = risk.score,
                        riskLevel = risk.level,
                        riskReason = risk.reasons,
                        streamUrl = buildStreamUrl(ip, openPorts)
                    )
                }
            }

        withTimeoutOrNull(SUBNET_SCAN_TIMEOUT_MS) {
            devices.addAll(scanJobs.awaitAll())
            devices.addAll(gatewayJobs.awaitAll().filterNotNull())
        }

        devices
            .filter { it.riskScore > 10 }
            .sortedByDescending { it.riskScore }
    }

    // ── Port Scanner ─────────────────────────────────────────────────────────

    private suspend fun scanPorts(ip: String): Map<Int, String> = withContext(Dispatchers.IO) {
        val openPorts = mutableMapOf<Int, String>()
        val jobs = CAMERA_PORTS.map { (port, description) ->
            async {
                if (isPortOpen(ip, port)) {
                    Pair(port, description)
                } else null
            }
        }
        jobs.awaitAll().filterNotNull().forEach { (port, desc) ->
            openPorts[port] = desc
        }
        openPorts
    }

    private fun isPortOpen(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), PORT_TIMEOUT_MS)
            socket.close()
            true
        } catch (e: IOException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    // ── ARP Table ────────────────────────────────────────────────────────────

    private fun readARPTable(): List<Pair<String, String>> {
        val devices = mutableListOf<Pair<String, String>>()
        try {
            val lines = java.io.File("/proc/net/arp").readLines().drop(1)
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3].uppercase()
                    if (mac != "00:00:00:00:00:00" && ip.isNotEmpty()) {
                        devices.add(Pair(ip, mac))
                    }
                }
            }
        } catch (e: Exception) {}
        return devices
    }

    // ── Gateway Range Scan ───────────────────────────────────────────────────

    private fun scanGatewayRange(): List<String> {
        val dhcp = wifiManager.dhcpInfo
        val gateway = dhcp.gateway
        if (gateway == 0) return emptyList()

        // Build subnet prefix e.g. "192.168.1."
        val subnet = String.format(
            "%d.%d.%d.",
            gateway and 0xFF,
            (gateway shr 8) and 0xFF,
            (gateway shr 16) and 0xFF
        )

        return (1..254).map { "$subnet$it" }
    }

    // ── Vendor Identification ─────────────────────────────────────────────────

    private fun identifyVendor(mac: String): VendorInfo {
        val upperMac = mac.uppercase()
        val match = CAMERA_OUIS.entries.find { (oui, _) ->
            upperMac.startsWith(oui.uppercase())
        }
        return if (match != null) {
            VendorInfo(name = match.value, isCamera = true)
        } else {
            VendorInfo(name = lookupGenericOUI(upperMac), isCamera = false)
        }
    }

    private fun lookupGenericOUI(mac: String): String = when {
        mac.startsWith("B8:27:EB") || mac.startsWith("DC:A6:32") || mac.startsWith("E4:5F:01") -> "Raspberry Pi"
        mac.startsWith("00:50:F2") -> "Microsoft"
        mac.startsWith("38:F9:D3") || mac.startsWith("F0:18:98") -> "Apple"
        mac.startsWith("00:1C:B3") -> "Apple"
        mac.startsWith("FC:FB:FB") -> "Cisco"
        else -> "Unknown vendor"
    }

    // ── Risk Scoring ─────────────────────────────────────────────────────────

    private fun calculateRisk(
        mac: String,
        ip: String,
        openPorts: Map<Int, String>,
        vendor: VendorInfo
    ): RogueDeviceRisk {
        var score = 0
        val reasons = mutableListOf<String>()

        // Known camera vendor = very strong signal
        if (vendor.isCamera) {
            score += 45
            reasons.add("Known camera manufacturer: ${vendor.name}")
        }

        // RTSP port open = confirmed camera stream
        if (openPorts.containsKey(554)) {
            score += 40
            reasons.add("RTSP stream port 554 open — live camera stream confirmed")
        }
        if (openPorts.containsKey(8554)) {
            score += 35
            reasons.add("RTSP alternate port 8554 open")
        }

        // ONVIF = IP camera standard protocol
        if (openPorts.containsKey(3702)) {
            score += 30
            reasons.add("ONVIF WS-Discovery port 3702 — IP camera protocol")
        }

        // Proprietary camera ports
        if (openPorts.containsKey(37777)) {
            score += 35
            reasons.add("Dahua DVR port 37777 open")
        }
        if (openPorts.containsKey(9000)) {
            score += 30
            reasons.add("Hikvision SDK port 9000 open")
        }
        if (openPorts.containsKey(34567)) {
            score += 30
            reasons.add("DVR/NVR stream port 34567 open")
        }
        if (openPorts.containsKey(6668)) {
            score += 25
            reasons.add("Tuya smart device port 6668 open")
        }
        if (openPorts.containsKey(4567)) {
            score += 25
            reasons.add("Amcrest/Reolink port 4567 open")
        }

        // HTTP on IoT = likely has web interface
        if (openPorts.containsKey(80) && vendor.isCamera) {
            score += 10
            reasons.add("Web interface accessible")
        }

        // Unknown vendor with camera ports = very suspicious
        if (!vendor.isCamera && openPorts.keys.any { it in CONFIRMED_CAMERA_PORTS }) {
            score += 20
            reasons.add("Unknown device with camera-specific ports open")
        }

        // Multiple camera ports = very high confidence
        val cameraPortCount = openPorts.keys.count { it in CONFIRMED_CAMERA_PORTS }
        if (cameraPortCount >= 3) {
            score += 15
            reasons.add("$cameraPortCount camera-specific ports simultaneously open")
        }

        val level = when {
            score >= 70 -> "HIGH"
            score >= 40 -> "MEDIUM"
            score >= 20 -> "LOW"
            else -> "NONE"
        }

        return RogueDeviceRisk(
            score = score.coerceIn(0, 100),
            level = level,
            reasons = reasons
        )
    }

    // ── Stream URL Builder ────────────────────────────────────────────────────

    private fun buildStreamUrl(ip: String, openPorts: Map<Int, String>): String? {
        return when {
            openPorts.containsKey(554)  -> "rtsp://$ip:554/stream"
            openPorts.containsKey(8554) -> "rtsp://$ip:8554/stream"
            openPorts.containsKey(80)   -> "http://$ip:80"
            openPorts.containsKey(8080) -> "http://$ip:8080"
            else -> null
        }
    }

    data class VendorInfo(val name: String, val isCamera: Boolean)
}
