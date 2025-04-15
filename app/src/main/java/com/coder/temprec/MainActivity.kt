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
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    private lateinit var temperatureTextView: TextView
    private lateinit var scanButton: Button
    private lateinit var historyButton: Button
    private lateinit var recordButton: Button

    private val temperatureHistory = mutableListOf<String>()
    private var latestTemperature: String? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcd1234-ab12-cd34-ef56-abcdef123456")

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
            checkBluetoothEnabled()
        } else {
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
            setPadding(30, 50, 30, 30)
        }

        scanButton = Button(this).apply {
            text = "🔍 Scan Bluetooth"
            setOnClickListener { startScan() }
        }

        historyButton = Button(this).apply {
            text = "📜 View History"
            setOnClickListener { temphistory() }
        }

        recordButton = Button(this).apply {
            text = "📍 Record Temperature"
            setOnClickListener { recordTemperature() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 100, 30, 30)
            addView(temperatureTextView)
            addView(scanButton)
            addView(historyButton)
            addView(recordButton)
        }

        setContentView(layout)

        bluetoothAdapter = bluetoothManager.adapter ?: run {
            temperatureTextView.text = "❌ Bluetooth not supported on this device"
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }

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
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            startScan()
        }
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        deviceFound = false
        temperatureTextView.text = "🔎 Scanning for ESP32-Thermo..."

        try {
            scanner?.startScan(scanCallback) ?: run {
                temperatureTextView.text = "❌ Bluetooth scanner not available"
                return
            }

            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
                if (!deviceFound) {
                    runOnUiThread {
                        temperatureTextView.text = "❌ ESP32-Thermo not found"
                    }
                }
            }, 10000)
        } catch (e: Exception) {
            temperatureTextView.text = "❌ Error starting scan: ${e.message}"
        }
    }

    private fun stopScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            ) {
                scanner?.stopScan(scanCallback)
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
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name
                } else null
            } else device.name

            if (deviceName == "ESP32-Thermo") {
                deviceFound = true
                stopScan()
                connectToDevice(device)
                runOnUiThread {
                    temperatureTextView.text = "🔗 Connecting to ESP32-Thermo..."
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            temperatureTextView.text = "❌ Scan failed with error: $errorCode"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                temperatureTextView.text = "❌ Bluetooth Connect permission denied"
                return
            }
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: Exception) {
            temperatureTextView.text = "❌ Error connecting: ${e.message}"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
                runOnUiThread {
                    temperatureTextView.text = "🔍 Discovering services..."
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    temperatureTextView.text = "❌ Disconnected from ESP32-Thermo"
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                        runOnUiThread {
                            temperatureTextView.text = "🔄 Setting up temperature notifications..."
                        }
                    }
                } else {
                    temperatureTextView.text = "❌ Temperature characteristic not found"
                }
            } else {
                temperatureTextView.text = "❌ Service discovery failed: $status"
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                val value = characteristic.getStringValue(0)
                latestTemperature = value
                runOnUiThread {
                    temperatureTextView.text = "🌡 Temperature: $value °C"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    temperatureTextView.text = "❌ Error reading temperature"
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            runOnUiThread {
                temperatureTextView.text =
                    if (status == BluetoothGatt.GATT_SUCCESS) "✅ Ready for temperature readings..."
                    else "❌ Notification setup failed: $status"
            }
        }
    }

    private fun temphistory() {
        if (temperatureHistory.isEmpty()) {
            Toast.makeText(this, "No temperature history yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val historyString = temperatureHistory.joinToString("\n") { "🌡 $it °C" }

        AlertDialog.Builder(this)
            .setTitle("Temperature History")
            .setMessage(historyString)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun recordTemperature() {
        latestTemperature?.let {
            temperatureHistory.add(it)
            Toast.makeText(this, "✅ Temperature recorded: $it °C", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, "⚠️ No temperature to record", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        bluetoothGatt?.close()
    }
}
