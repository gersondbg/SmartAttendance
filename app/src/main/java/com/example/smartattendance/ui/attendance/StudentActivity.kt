package com.example.smartattendance.ui.attendance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.smartattendance.databinding.ActivityStudentBinding
import com.example.smartattendance.ui.login.LoginActivity

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.ParcelUuid
import java.util.UUID
import com.example.smartattendance.R
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class StudentActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityStudentBinding
    private val viewModel: StudentViewModel by viewModels()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var classTimer: CountDownTimer? = null
    private var pendingAutoStart = false

    private val presenceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != StudentPresenceService.ACTION_STATE_CHANGED) return
            val stable = intent.getBooleanExtra(StudentPresenceService.EXTRA_STABLE, true)
            viewModel.onIntent(StudentIntent.UpdateStability(stable))
        }
    }
    
    private val SMART_UUID = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
        }
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Toast.makeText(this@StudentActivity, getString(R.string.ble_error, errorCode), Toast.LENGTH_SHORT).show()
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
        
        viewModel.onIntent(StudentIntent.Init(userName, username))

        setupUI()
        observeViewModel()
        registerPresenceReceiver()
        requestBluetoothSetup()
        pendingAutoStart = true
        tryAutoStartPresence()
    }



    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateUI(state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is StudentEffect.ShowMessage -> Toast.makeText(this@StudentActivity, effect.message, Toast.LENGTH_SHORT).show()
                        is StudentEffect.NavigateToLogin -> {
                            val intent = Intent(this@StudentActivity, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                        }
                        is StudentEffect.StartAdvertising -> {
                            if (effect.prefix == "SA-") {
                                Toast.makeText(this@StudentActivity, getString(R.string.connected_msg), Toast.LENGTH_SHORT).show()
                            }
                        }
                        is StudentEffect.StartPresenceService -> StudentPresenceService.start(this@StudentActivity, effect.username, effect.stable)
                        is StudentEffect.UpdatePresenceService -> StudentPresenceService.updateStability(this@StudentActivity, effect.stable)
                        is StudentEffect.StopAdvertising -> stopAdvertising()
                        is StudentEffect.StopPresenceService -> StudentPresenceService.stop(this@StudentActivity)
                    }
                }
            }
        }
    }

    private fun updateUI(state: StudentState) {
        binding.tvWelcome.text = getString(R.string.hello_user, state.userName)
        
        if (!state.isVisibilityActive) {
            binding.tvStatus.text = getString(R.string.bluetooth_waiting)
            binding.tvSignalInfo.text = getString(R.string.no_presence_sending)
            return
        }

        if (state.isStable) {
            binding.tvStatus.setTextColor(android.graphics.Color.rgb(22, 163, 74))
            binding.tvStatus.text = "Bluetooth: ENVIANDO SENAL"
            binding.tvSignalInfo.text = "Estado: ESTABLE | Concentracion local: ${state.concentrationPercent}%"
            binding.tvSignalInfo.setTextColor(android.graphics.Color.rgb(22, 163, 74))
        } else {
            binding.tvStatus.setTextColor(android.graphics.Color.rgb(220, 38, 38))
            binding.tvStatus.text = "Bluetooth: ENVIANDO SENAL"
            binding.tvSignalInfo.text = "Estado: MOVIMIENTO | Concentracion local: ${state.concentrationPercent}%"
            binding.tvSignalInfo.setTextColor(android.graphics.Color.rgb(202, 138, 4))
        }
    }

    private fun registerPresenceReceiver() {
        val filter = IntentFilter(StudentPresenceService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(presenceStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(presenceStateReceiver, filter)
        }
    }

    private fun startAdvertising(prefix: String, username: String) {
        if (bluetoothAdapter == null) return
        if (bleAdvertiser == null) bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }

        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val nameToAdvertise = "$prefix$username".take(20)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(SMART_UUID)
            .addServiceData(SMART_UUID, nameToAdvertise.toByteArray(Charsets.UTF_8))
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {}
    }

    private fun stopAdvertising() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {}
    }

    private fun setupUI() {
        val courseName = com.example.smartattendance.data.remote.SessionStore.activeCourseName ?: "General"
        binding.tvCourseName.text = "Curso: $courseName"

        binding.btnActivate.setOnClickListener {
            activatePresenceOrWarn()
        }
        binding.btnLogout.setOnClickListener { viewModel.onIntent(StudentIntent.Logout) }
    }

    private fun activatePresenceOrWarn() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, getString(R.string.bluetooth_required), Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.onIntent(StudentIntent.ActivateVisibility)
        startLocalTimer()
    }

    private fun tryAutoStartPresence() {
        if (!pendingAutoStart) return
        val permissionsOk = requiredPermissions().all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsOk && bluetoothAdapter?.isEnabled == true) {
            pendingAutoStart = false
            activatePresenceOrWarn()
        }
    }

    private fun startLocalTimer() {
        classTimer?.cancel()
        classTimer = object : CountDownTimer(7200_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                viewModel.onIntent(StudentIntent.Tick)
            }
            override fun onFinish() { }
        }.start()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // La deteccion real de movimiento vive en StudentPresenceService para funcionar con pantalla bloqueada.
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun requestBluetoothSetup() {
        val permissions = requiredPermissions()

        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2002)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2002) tryAutoStartPresence()
    }

    override fun onDestroy() {
        super.onDestroy()
        classTimer?.cancel()
        sensorManager.unregisterListener(this)
        try {
            unregisterReceiver(presenceStateReceiver)
        } catch (_: Exception) {
        }
    }
}
