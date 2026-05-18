package com.example.smartattendance.ui.attendance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
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

/**
 * Servicio en primer plano para mostrar tiempo, presentes y concentración media
 * aunque la app esté minimizada o la pantalla bloqueada.
 */
class TeacherClassSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val smartUuid = ParcelUuid(UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb"))
    private val scanRunnable = object : Runnable {
        override fun run() {
            scanOnce()
            handler.postDelayed(this, 6_000L)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.removeCallbacks(tick)
                handler.removeCallbacks(scanRunnable)
                stopScan()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                handler.removeCallbacks(tick)
                handler.post(tick)
                handler.removeCallbacks(scanRunnable)
                handler.post(scanRunnable)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(scanRunnable)
        stopScan()
        super.onDestroy()
    }

    private fun scanOnce() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
            scanner.stopScan(scanCallback)
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val filters = listOf(ScanFilter.Builder().setServiceUuid(smartUuid).build())
            scanner.startScan(filters, settings, scanCallback)
            handler.postDelayed({ stopScan() }, 4_500L)
        } catch (_: Exception) {
        }
    }

    private fun stopScan() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
        } catch (_: Exception) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val data = result?.scanRecord?.getServiceData(smartUuid) ?: return
            val deviceName = String(data, Charsets.UTF_8)
            val username = deviceName.substringAfter("-").trim()
            if (username.isBlank()) return
            val intent = Intent(ACTION_STUDENT_DETECTED)
                .setPackage(packageName)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_MOVING, deviceName.contains("MV-"))
                .putExtra(EXTRA_RESTART, deviceName.contains("RS-"))
            sendBroadcast(intent)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Clase en curso",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Resumen de asistencia y concentración"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val s = TeacherClassNotificationState
        val rem = s.remainingSeconds.coerceAtLeast(0)
        val timeStr = String.format("%02d:%02d", rem / 60, rem % 60)
        val title = if (s.paused) "Clase en RECESO" else "Clase en curso"
        val course = s.courseTitle.ifBlank { "SmartAttendance" }
        val line1 = "Tiempo: $timeStr  |  Presentes: ${s.presentCount}/${s.totalStudents}"
        val line2 = "Concentración media: ${s.averageConcentrationPercent}%"
        val tap = Intent(this, TeacherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText("$line1 · $line2")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$course\n\n$line1\n$line2"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "teacher_class_session"
        private const val NOTIFICATION_ID = 9101
        const val ACTION_START = "com.example.smartattendance.TeacherClassSession.START"
        const val ACTION_STOP = "com.example.smartattendance.TeacherClassSession.STOP"
        const val ACTION_STUDENT_DETECTED = "com.example.smartattendance.TeacherClassSession.STUDENT_DETECTED"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_MOVING = "moving"
        const val EXTRA_RESTART = "restart"

        fun start(context: Context) {
            val i = Intent(context, TeacherClassSessionService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TeacherClassSessionService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
