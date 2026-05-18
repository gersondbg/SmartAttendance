package com.example.smartattendance.ui.attendance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class StudentPresenceService : Service(), SensorEventListener {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val smartUuid = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var username: String = ""
    private var stable: Boolean = true
    private var advertising = false
    private var maintenanceRestart = false
    private var movingStartedAt = 0L
    private var stableStartedAt = 0L
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastZ: Float? = null

    private val restartRunnable = object : Runnable {
        override fun run() {
            startMaintenanceRestart()
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            notifyState()
            broadcastState()
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            notifyState("Error BLE: $errorCode")
            broadcastState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAdvertising()
                stopMotionSensor()
                handler.removeCallbacks(restartRunnable)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STABILITY -> {
                stable = intent.getBooleanExtra(EXTRA_STABLE, true)
                startAdvertising()
            }
            else -> {
                username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty().ifBlank { username }
                stable = intent?.getBooleanExtra(EXTRA_STABLE, true) ?: stable
                startForeground(NOTIFICATION_ID, buildNotification())
                startMotionSensor()
                startAdvertising()
                handler.removeCallbacks(restartRunnable)
                handler.postDelayed(restartRunnable, 10 * 60 * 1000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        stopMotionSensor()
        handler.removeCallbacks(restartRunnable)
        super.onDestroy()
    }

    private fun startMotionSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopMotionSensor() {
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
    }

    private fun startAdvertising() {
        startAdvertisingWithPrefix(if (stable) "SA-" else "MV-")
    }

    private fun startMaintenanceRestart() {
        maintenanceRestart = true
        startAdvertisingWithPrefix("RS-")
        handler.postDelayed({
            maintenanceRestart = false
            startAdvertising()
        }, 3_000L)
    }

    private fun startAdvertisingWithPrefix(prefix: String) {
        if (username.isBlank() || bluetoothAdapter?.isEnabled != true) {
            advertising = false
            notifyState("Bluetooth apagado")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            advertising = false
            notifyState("Permiso BLE pendiente")
            return
        }

        stopAdvertising()
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            advertising = false
            notifyState("BLE advertising no soportado")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(smartUuid)
            .addServiceData(smartUuid, "$prefix$username".take(20).toByteArray(Charsets.UTF_8))
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {
            advertising = false
            notifyState("No se pudo iniciar BLE")
        }
    }

    private fun stopAdvertising() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {
        }
        advertising = false
    }

    private fun notifyState(message: String? = null) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Presencia del alumno",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Mantiene activa la senal Bluetooth de asistencia"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String? = null): Notification {
        val tapIntent = Intent(this, StudentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = message ?: when {
            maintenanceRestart -> "Reforzando senal Bluetooth"
            advertising -> "Enviando senal Bluetooth"
            else -> "Preparando senal Bluetooth"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Smart Attendance activo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER || maintenanceRestart) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        val lastMagnitude = run {
            val lx = lastX
            val ly = lastY
            val lz = lastZ
            if (lx != null && ly != null && lz != null) kotlin.math.sqrt(lx * lx + ly * ly + lz * lz) else magnitude
        }
        val delta = kotlin.math.abs(x - (lastX ?: x)) +
            kotlin.math.abs(y - (lastY ?: y)) +
            kotlin.math.abs(z - (lastZ ?: z))
        lastX = x
        lastY = y
        lastZ = z
        val now = System.currentTimeMillis()
        val movingNow = delta > 0.65f ||
            kotlin.math.abs(magnitude - lastMagnitude) > 0.35f ||
            kotlin.math.abs(magnitude - 9.81f) > 0.9f

        if (movingNow) {
            if (movingStartedAt == 0L) movingStartedAt = now
            stableStartedAt = 0L
            if (stable && now - movingStartedAt >= 350L) {
                stable = false
                startAdvertising()
                broadcastState()
            }
        } else {
            if (stableStartedAt == 0L) stableStartedAt = now
            movingStartedAt = 0L
            if (!stable && now - stableStartedAt >= 1_800L) {
                stable = true
                startAdvertising()
                broadcastState()
            }
        }
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_STABLE, stable)
            .putExtra(EXTRA_ADVERTISING, advertising)
            .putExtra(EXTRA_RESTARTING, maintenanceRestart)
        sendBroadcast(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val CHANNEL_ID = "student_presence"
        private const val NOTIFICATION_ID = 9201
        private const val ACTION_START = "com.example.smartattendance.student.START"
        private const val ACTION_STOP = "com.example.smartattendance.student.STOP"
        private const val ACTION_UPDATE_STABILITY = "com.example.smartattendance.student.STABILITY"
        const val ACTION_STATE_CHANGED = "com.example.smartattendance.student.STATE_CHANGED"
        private const val EXTRA_USERNAME = "username"
        const val EXTRA_STABLE = "stable"
        const val EXTRA_ADVERTISING = "advertising"
        const val EXTRA_RESTARTING = "restarting"

        fun start(context: Context, username: String, stable: Boolean) {
            val intent = Intent(context, StudentPresenceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_STABLE, stable)
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateStability(context: Context, stable: Boolean) {
            val intent = Intent(context, StudentPresenceService::class.java)
                .setAction(ACTION_UPDATE_STABILITY)
                .putExtra(EXTRA_STABLE, stable)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StudentPresenceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
