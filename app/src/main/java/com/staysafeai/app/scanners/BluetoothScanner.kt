package com.staysafeai.app.scanners

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.staysafeai.app.models.BluetoothDeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BluetoothScanner(private val context: Context) {

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val discoveredDevices = mutableListOf<BluetoothDeviceInfo>()
    private var leScanner: BluetoothLeScanner? = null
    private var leScanCallback: ScanCallback? = null

    // Known bug/camera Bluetooth OUI prefixes
    private val SUSPICIOUS_BT_OUIS = mapOf(
        "00:1A:7D" to "Generic hidden camera module",
        "BC:6A:29" to "Tuya BT camera",
        "A4:C1:38" to "Xiaomi IoT device",
        "F4:4E:FD" to "Hidden BT microphone",
        "7C:01:0A" to "BT spy device OEM",
        "44:A6:1E" to "Generic BT camera"
    )

    // Suspicious BT device name patterns
    private val SUSPICIOUS_NAME_PATTERNS = listOf(
        "cam", "camera", "spy", "hidden", "bug", "mic",
        "listen", "record", "watch", "eye", "view"
    )

    suspend fun scan(durationMs: Long = 5000): List<BluetoothDeviceInfo> {
        discoveredDevices.clear()

        // Classic BT scan (for older bugs)
        startClassicScan()

        // BLE scan (for modern BT bugs)
        startLEScan()

        delay(durationMs)

        stopScans()

        return analyzeDevices(discoveredDevices)
    }

    private suspend fun startClassicScan() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(
                            BluetoothDevice.EXTRA_DEVICE
                        ) ?: return

                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                        val name = device.name ?: ""
                        val address = device.address ?: ""

                        val isSuspicious = checkIfSuspicious(name, address)
                        val threatScore = calculateThreatScore(name, address, rssi)

                        discoveredDevices.add(
                            BluetoothDeviceInfo(
                                name = name.ifEmpty { "Unknown Device" },
                                macAddress = address,
                                rssi = rssi,
                                deviceType = "Classic BT",
                                isSuspicious = isSuspicious,
                                threatScore = threatScore,
                                vendorHint = getVendorHint(address),
                                isNameless = name.isEmpty(),
                                distanceEstimate = rssiToDistance(rssi)
                            )
                        )
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(receiver, filter)
        bluetoothAdapter?.startDiscovery()
    }

    private fun startLEScan() {
        leScanner = bluetoothAdapter?.bluetoothLeScanner

        leScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: ""
                val address = device.address ?: ""
                val rssi = result.rssi

                val isSuspicious = checkIfSuspicious(name, address)
                val threatScore = calculateThreatScore(name, address, rssi)

                // Check manufacturer data for camera signatures
                val manufacturerData = result.scanRecord?.manufacturerSpecificData
                val hasCameraManufData = manufacturerData?.let {
                    // Common camera manufacturer IDs
                    it.indexOfKey(0x004C) < 0 && it.size() > 0 // Not Apple = more suspicious
                } ?: false

                val existing = discoveredDevices.find { it.macAddress == address }
                if (existing == null) {
                    discoveredDevices.add(
                        BluetoothDeviceInfo(
                            name = name.ifEmpty { "Unnamed BLE Device" },
                            macAddress = address,
                            rssi = rssi,
                            deviceType = "BLE",
                            isSuspicious = isSuspicious || hasCameraManufData,
                            threatScore = threatScore + if (hasCameraManufData) 10 else 0,
                            vendorHint = getVendorHint(address),
                            isNameless = name.isEmpty(),
                            distanceEstimate = rssiToDistance(rssi),
                            txPower = result.txPower
                        )
                    )
                } else {
                    // Update RSSI for triangulation
                    existing.rssi = rssi
                    existing.distanceEstimate = rssiToDistance(rssi)
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner?.startScan(null, settings, leScanCallback)
    }

    private fun checkIfSuspicious(name: String, mac: String): Boolean {
        val nameLower = name.lowercase()
        val isSuspiciousName = SUSPICIOUS_NAME_PATTERNS.any { nameLower.contains(it) }
        val isSuspiciousOUI = SUSPICIOUS_BT_OUIS.any { (oui, _) ->
            mac.uppercase().startsWith(oui.uppercase())
        }
        val isNameless = name.isEmpty()
        return isSuspiciousName || isSuspiciousOUI || isNameless
    }

    private fun getVendorHint(mac: String): String? {
        return SUSPICIOUS_BT_OUIS.entries.find { (oui, _) ->
            mac.uppercase().startsWith(oui.uppercase())
        }?.value
    }

    private fun calculateThreatScore(name: String, mac: String, rssi: Int): Int {
        var score = 0
        val nameLower = name.lowercase()

        // Known suspicious vendor
        if (SUSPICIOUS_BT_OUIS.any { (oui, _) -> mac.uppercase().startsWith(oui.uppercase()) }) {
            score += 40
        }

        // Suspicious name
        if (SUSPICIOUS_NAME_PATTERNS.any { nameLower.contains(it) }) score += 30

        // Completely nameless device
        if (name.isEmpty()) score += 20

        // Very close to phone (strong signal = nearby)
        if (rssi > -50) score += 10

        return score.coerceIn(0, 100)
    }

    private fun analyzeDevices(devices: List<BluetoothDeviceInfo>): List<BluetoothDeviceInfo> {
        return devices
            .distinctBy { it.macAddress }
            .sortedByDescending { it.threatScore }
    }

    // Convert RSSI to approximate distance in meters
    private fun rssiToDistance(rssi: Int): Float {
        // Path loss model: d = 10 ^ ((TxPower - RSSI) / (10 * n))
        val txPower = -59 // Typical BT TX power at 1m
        val n = 2.0 // Path loss exponent (free space = 2)
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n)).toFloat()
    }

    // Get real-time RSSI for triangulation (hot/cold guidance)
    fun getLiveRSSI(targetMac: String, callback: (Int) -> Unit) {
        leScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device.address == targetMac) {
                        callback(result.rssi)
                    }
                }
            }
        )
    }

    private fun stopScans() {
        bluetoothAdapter?.cancelDiscovery()
        leScanCallback?.let { leScanner?.stopScan(it) }
    }
}
