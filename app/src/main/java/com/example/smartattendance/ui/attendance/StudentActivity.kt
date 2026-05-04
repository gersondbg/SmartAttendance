package com.example.smartattendance.ui.attendance

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.databinding.ActivityStudentBinding
import com.example.smartattendance.ui.login.LoginActivity

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.ParcelUuid
import java.util.UUID
class StudentActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityStudentBinding
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    
    private var isVisibilityActive = false
    private var currentUsername: String = ""

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isStable = true

    // Para el porcentaje local
    private var totalSeconds = 0
    private var presentSeconds = 0
    private var classTimer: CountDownTimer? = null
    
    private val SMART_UUID = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
        }
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Toast.makeText(this@StudentActivity, "Fallo al iniciar BLE: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val userName = intent.getStringExtra("USER_NAME") ?: "Alumno"
        val username = intent.getStringExtra("USER_USERNAME") ?: userName
        currentUsername = username
        setupUI(userName)
        requestBluetoothSetup()
        checkBluetoothStatus()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            checkBluetoothStatus()
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Si está sobre la mesa
            val currentlyStable = (z in 8.5..11.0 && x in -2.0..2.0 && y in -2.0..2.0)
            if (currentlyStable != isStable) {
                isStable = currentlyStable
                restartAdvertising()
                updateUI()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun restartAdvertising() {
        if (!isVisibilityActive || bluetoothAdapter == null) return
        if (bleAdvertiser == null) bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }

        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val prefix = if (isStable) "SA-" else "MV-"
        val nameToAdvertise = "$prefix$currentUsername".take(20)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(SMART_UUID)
            .addServiceData(SMART_UUID, nameToAdvertise.toByteArray(Charsets.UTF_8))
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {}
    }

    private fun setupUI(name: String) {
        binding.tvWelcome.text = "Hola, $name"
        binding.tvSignalInfo.text = "Senal pendiente"
        binding.btnActivate.setOnClickListener {
            activateVisibility()
        }
        binding.btnLogout.setOnClickListener { logout() }
    }

    private fun checkBluetoothStatus() {
        if (bluetoothAdapter == null) {
            binding.tvStatus.text = "Bluetooth no disponible"
            binding.tvStatus.setTextColor(android.graphics.Color.RED)
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            binding.tvStatus.text = "Bluetooth: DESACTIVADO"
            binding.tvSignalInfo.text = "No estas enviando presencia"
            binding.tvStatus.setTextColor(android.graphics.Color.RED)
        }
    }

    private fun requestBluetoothSetup() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2002)
        }
    }

    private fun activateVisibility() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Activa Bluetooth primero", Toast.LENGTH_SHORT).show()
            return
        }

        isVisibilityActive = true
        totalSeconds = 0
        presentSeconds = 0
        restartAdvertising()
        startClassTimer()
        updateUI()
        
        Toast.makeText(this, "Conectado. Manten el celular sobre la mesa.", Toast.LENGTH_LONG).show()
    }

    private fun startClassTimer() {
        classTimer?.cancel()
        classTimer = object : CountDownTimer(7200_000, 1000) { // 2 horas
            override fun onTick(millisUntilFinished: Long) {
                totalSeconds++
                if (isStable) {
                    presentSeconds++
                } else {
                    if (presentSeconds > 0) presentSeconds--
                }
                updateUI()
            }
            override fun onFinish() { }
        }.start()
    }

    private fun updateUI() {
        if (!isVisibilityActive) return
        
        val percent = if (totalSeconds == 0) 0 else (presentSeconds * 100) / totalSeconds
        
        if (isStable) {
            binding.tvStatus.text = "Transmitiendo presencia (BLE) - $percent%"
            binding.tvStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74))
            binding.tvSignalInfo.text = "Dispositivo estable. Concentracion subiendo."
            binding.tvSignalInfo.setTextColor(android.graphics.Color.rgb(22, 163, 74))
        } else {
            binding.tvStatus.text = "Transmitiendo presencia (BLE) - $percent%"
            binding.tvStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38))
            binding.tvSignalInfo.text = "Te estas moviendo. Concentracion bajando!"
            binding.tvSignalInfo.setTextColor(android.graphics.Color.rgb(202, 138, 4))
        }
    }

    private fun logout() {
        SessionStore.clear()
        classTimer?.cancel()
        isVisibilityActive = false
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {}
        
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        classTimer?.cancel()
        sensorManager.unregisterListener(this)
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {}
    }
}
