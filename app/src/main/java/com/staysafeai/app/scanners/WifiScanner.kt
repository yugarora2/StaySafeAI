package com.staysafeai.app.scanners

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.staysafeai.app.models.WifiDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WifiScanner(private val context: Context) {

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Known hidden camera / IoT device OUI prefixes (first 3 bytes of MAC)
    private val SUSPICIOUS_OUIS = mapOf(
        "D8:3A:DD" to "Tuya Smart (known hidden camera platform)",
        "68:57:2D" to "Tuya Smart OEM",
        "50:02:91" to "Tuya Smart OEM",
        "84:E3:42" to "Tuya Smart OEM",
        "10:52:1C" to "Hikvision (security camera)",
        "44:19:B6" to "Hikvision OEM",
        "28:57:BE" to "Dahua Technology (camera)",
        "90:02:A9" to "Dahua OEM",
        "B4:A3:82" to "Reolink Camera",
        "EC:71:DB" to "Wyze Camera",
        "2C:AA:8E" to "Wyze Labs",
        "70:F1:1C" to "TP-Link Camera",
        "3C:52:A1" to "TP-Link IoT",
        "48:EE:0C" to "Ezviz Camera (Hikvision subsidiary)",
        "C8:02:8F" to "Amcrest Camera",
        "00:23:63" to "Axis Communications (IP camera)",
        "AC:CC:8E" to "Espressif (cheap IoT/camera module)",
        "60:01:94" to "Espressif OEM",
        "A4:CF:12" to "Espressif OEM",
        "24:6F:28" to "Espressif OEM",
        "DC:4F:22" to "Espressif OEM",
        "00:1A:22" to "VSTARCAM (IP camera)",
        "C4:4B:D1" to "Foscam Camera",
        "00:25:1E" to "Foscam OEM",
        "8C:AB:8E" to "Sricam",
        "74:DA:38" to "Edimax (network camera)",
        "80:1F:02" to "Wansview camera",
        "00:90:47" to "Crestron (smart room control)"
    )

    // Ports commonly used by hidden cameras
    private val CAMERA_PORTS = listOf(80, 443, 554, 6668, 8080, 8443, 8554, 9000, 37777)

    suspend fun scan(): List<WifiDevice> {
        val devices = mutableListOf<WifiDevice>()

        // Scan for nearby access points
        val apDevices = scanAccessPoints()
        devices.addAll(apDevices)

        // Scan connected network for devices
        val networkDevices = scanLocalNetwork()
        devices.addAll(networkDevices)

        return devices
            .distinctBy { it.macAddress }
            .sortedByDescending { it.threatScore }
    }

    private suspend fun scanAccessPoints(): List<WifiDevice> {
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    val results = wifiManager.scanResults
                    val devices = results.map { result ->
                        val mac = result.BSSID?.uppercase() ?: ""
                        val ouiPrefix = mac.take(8)
                        val vendorInfo = SUSPICIOUS_OUIS.entries.find { (oui, _) ->
                            mac.startsWith(oui.uppercase())
                        }

                        WifiDevice(
                            ssid = result.SSID ?: "Hidden Network",
                            macAddress = mac,
                            signalStrength = result.level,
                            vendorName = vendorInfo?.value ?: lookupOUI(ouiPrefix),
                            isSuspicious = vendorInfo != null,
                            ipAddress = null,
                            openPorts = emptyList(),
                            frequency = result.frequency,
                            capabilities = result.capabilities,
                            threatScore = calculateThreatScore(mac, result.SSID, vendorInfo?.value)
                        )
                    }
                    cont.resume(devices)
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            )

            @Suppress("DEPRECATION")
            wifiManager.startScan()
        }
    }

    private fun scanLocalNetwork(): List<WifiDevice> {
        val devices = mutableListOf<WifiDevice>()

        try {
            val dhcpInfo = wifiManager.dhcpInfo
            val gateway = dhcpInfo.gateway
            val subnet = gateway and 0x00FFFFFF // Mask to get subnet

            // ARP table scan — read /proc/net/arp
            val arpTable = readARPTable()
            devices.addAll(arpTable)

        } catch (e: Exception) {
            // ARP read failed — permission or unavailable
        }

        return devices
    }

    private fun readARPTable(): List<WifiDevice> {
        val devices = mutableListOf<WifiDevice>()
        try {
            val arpFile = java.io.File("/proc/net/arp")
            if (!arpFile.exists()) return emptyList()

            val lines = arpFile.readLines().drop(1) // Skip header
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3].uppercase()
                    if (mac == "00:00:00:00:00:00") continue

                    val vendorInfo = SUSPICIOUS_OUIS.entries.find { (oui, _) ->
                        mac.startsWith(oui.uppercase())
                    }

                    devices.add(
                        WifiDevice(
                            ssid = "Network Device",
                            macAddress = mac,
                            signalStrength = 0,
                            vendorName = vendorInfo?.value ?: lookupOUI(mac.take(8)),
                            isSuspicious = vendorInfo != null,
                            ipAddress = ip,
                            openPorts = emptyList(),
                            frequency = 0,
                            capabilities = "",
                            threatScore = calculateThreatScore(mac, null, vendorInfo?.value)
                        )
                    )
                }
            }
        } catch (e: Exception) {}
        return devices
    }

    private fun lookupOUI(prefix: String): String {
        // Extended OUI lookup — returns generic vendor or "Unknown"
        return when {
            prefix.startsWith("00:50:F2") -> "Microsoft device"
            prefix.startsWith("00:0C:E7") -> "Apple device"
            prefix.startsWith("B8:27:EB") -> "Raspberry Pi"
            prefix.startsWith("DC:A6:32") -> "Raspberry Pi"
            prefix.startsWith("E4:5F:01") -> "Raspberry Pi"
            prefix.startsWith("18:FE:34") -> "Espressif (IoT module)"
            prefix.startsWith("30:AE:A4") -> "Espressif (IoT module)"
            else -> "Unknown vendor"
        }
    }

    private fun calculateThreatScore(
        mac: String,
        ssid: String?,
        vendor: String?
    ): Int {
        var score = 0

        // Known camera vendor = high threat
        if (vendor != null && SUSPICIOUS_OUIS.values.contains(vendor)) score += 40

        // Hidden SSID
        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") score += 15

        // Suspiciously generic names
        val suspiciousNames = listOf("camera", "cam", "spy", "hidden", "ipcam", "vstarcam")
        if (ssid?.lowercase()?.let { s -> suspiciousNames.any { s.contains(it) } } == true) score += 25

        // Random-looking MAC (no known vendor)
        if (vendor == "Unknown vendor") score += 10

        return score.coerceIn(0, 100)
    }
}
