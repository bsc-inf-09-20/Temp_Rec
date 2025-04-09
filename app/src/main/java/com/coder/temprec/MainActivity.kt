package com.coder.temprec

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var temperatureTextView: TextView

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcd1234-ab12-cd34-ef56-abcdef123456")
    private val REQUEST_ENABLE_BT = 1

    // Flag to track if the desired device is found
    private var deviceFound = false

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val scanner by lazy {
        bluetoothManager.adapter?.bluetoothLeScanner
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.i("Permission", "All permissions granted")
            checkBluetoothEnabled()
        } else {
            Log.e("Permission", "Some permissions are not granted!")
            showPermissionDeniedMessage()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startScan()
        } else {
            temperatureTextView.text = "❌ Bluetooth must be enabled to use this app"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        temperatureTextView = TextView(this).apply {
            text = "🌡 Waiting for temperature..."
            textSize = 24f
            setPadding(30, 100, 30, 30)
        }
        setContentView(temperatureTextView)

        // Initialize BluetoothAdapter
        bluetoothAdapter = bluetoothManager.adapter ?: run {
            temperatureTextView.text = "❌ Bluetooth not supported on this device"
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        // Request permissions with explanation
        requestPermissionsWithRationale()
    }

    private fun requestPermissionsWithRationale() {
        val permissionsToRequest = getRequiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            checkBluetoothEnabled()
            return
        }

        val shouldShowRationale = permissionsToRequest.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        }

        if (shouldShowRationale) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app needs Bluetooth and Location permissions to scan for and connect to the temperature sensor device.")
                .setPositiveButton("OK") { _, _ ->
                    permissionsLauncher.launch(permissionsToRequest)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    temperatureTextView.text = "❌ Permissions required to use this app"
                }
                .create()
                .show()
        } else {
            permissionsLauncher.launch(permissionsToRequest)
        }
    }

    private fun showPermissionDeniedMessage() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Denied")
            .setMessage("This app requires Bluetooth and Location permissions to function properly. Please grant these permissions in Settings.")
            .setPositiveButton("OK") { _, _ ->
                temperatureTextView.text = "❌ Permissions denied. Please enable in Settings."
            }
            .create()
            .show()
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) and above
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            // Android 11 (API 30) and below
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                } else {
                    temperatureTextView.text = "❌ Bluetooth Connect permission denied"
                }
            } else {
                // For Android 11 and below
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            }
        } else {
            startScan()
        }
    }

    private fun startScan() {
        // Check permissions again just to be safe
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("Permission", "Bluetooth permissions not granted!")
                return
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("Permission", "Location permission not granted!")
            return
        }

        // Set scanning message in the UI and reset the flag
        deviceFound = false
        temperatureTextView.text = "🔎 Scanning for ESP32-Thermo..."

        try {
            scanner?.startScan(scanCallback) ?: run {
                temperatureTextView.text = "❌ Bluetooth scanner not available"
                return
            }

            // Stop scan after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
                // If no device found after 10 seconds, update UI
                if (!deviceFound) {
                    runOnUiThread {
                        temperatureTextView.text = "❌ ESP32-Thermo not found"
                    }
                }
            }, 10000)
        } catch (e: Exception) {
            Log.e("BLE", "Scan error: ${e.message}")
            temperatureTextView.text = "❌ Error starting scan: ${e.message}"
        }
    }

    private fun stopScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    scanner?.stopScan(scanCallback)
                }
            } else {
                scanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            Log.e("BLE", "Error stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            // Safe way to get device name
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name
                } else {
                    null
                }
            } else {
                device.name
            }

            Log.i("BLE", "Device found: $deviceName / ${device.address}")

            if (deviceName == "ESP32-Thermo") {
                Log.i("BLE", "ESP32-Thermo found, connecting...")
                deviceFound = true
                stopScan()
                connectToDevice(device)
                runOnUiThread {
                    temperatureTextView.text = "🔗 Connecting to ESP32-Thermo..."
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed with error: $errorCode")
            runOnUiThread {
                temperatureTextView.text = "❌ Scan failed with error: $errorCode"
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("Permission", "Bluetooth connect permission denied!")
                    temperatureTextView.text = "❌ Bluetooth Connect permission denied"
                    return
                }
            }
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            Log.e("BLE", "Error connecting: ${e.message}")
            temperatureTextView.text = "❌ Error connecting: ${e.message}"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BLE", "Connected to GATT server")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread {
                            temperatureTextView.text = "❌ Bluetooth Connect permission denied"
                        }
                        return
                    }
                }

                gatt.discoverServices()
                runOnUiThread {
                    temperatureTextView.text = "🔍 Discovering services..."
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BLE", "Disconnected from GATT server")
                runOnUiThread {
                    temperatureTextView.text = "❌ Disconnected from ESP32-Thermo"
                }
            } else {
                Log.e("BLE", "Connection state changed with error $status")
                runOnUiThread {
                    temperatureTextView.text = "❌ Connection error: $status"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Services discovered successfully")

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e("BLE", "Service not found")
                    runOnUiThread {
                        temperatureTextView.text = "❌ Temperature service not found"
                    }
                    return
                }

                val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.e("BLE", "Characteristic not found")
                    runOnUiThread {
                        temperatureTextView.text = "❌ Temperature characteristic not found"
                    }
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return
                    }
                }

                // Enable notifications
                gatt.setCharacteristicNotification(characteristic, true)

                // Write descriptor to enable notifications
                val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    runOnUiThread {
                        temperatureTextView.text = "🔄 Setting up temperature notifications..."
                    }
                } else {
                    Log.e("BLE", "Client characteristic config descriptor not found")
                    runOnUiThread {
                        temperatureTextView.text = "❌ Notification setup failed"
                    }
                }
            } else {
                Log.e("BLE", "Service discovery failed with status: $status")
                runOnUiThread {
                    temperatureTextView.text = "❌ Service discovery failed: $status"
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                val value = characteristic.getStringValue(0)
                Log.i("BLE", "Temperature received: $value")
                runOnUiThread {
                    temperatureTextView.text = "🌡 Temperature: $value °C"
                }
            } catch (e: Exception) {
                Log.e("BLE", "Error reading characteristic: ${e.message}")
                runOnUiThread {
                    temperatureTextView.text = "❌ Error reading temperature"
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "Descriptor written successfully")
                runOnUiThread {
                    temperatureTextView.text = "✅ Ready for temperature readings..."
                }
            } else {
                Log.e("BLE", "Descriptor write failed: $status")
                runOnUiThread {
                    temperatureTextView.text = "❌ Notification setup failed: $status"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("BLE", "Error closing GATT: ${e.message}")
        }
    }
}