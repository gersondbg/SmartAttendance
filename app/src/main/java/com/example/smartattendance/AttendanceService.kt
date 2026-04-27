package com.example.smartattendance

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class AttendanceService : Service(), SensorEventListener {

    private val CHANNEL_ID = "AttendanceChannel"
    private var totalSamples = 0
    private var successSamples = 0
    private val TARGET_LAPTOP_NAME = "RandyPC" // Nombre Bluetooth de tu PC

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isStable = false // Eje Z cerca a 9.8

    // Bucle que se ejecuta cada minuto para simular el muestreo
    private val handler = Handler(Looper.getMainLooper())
    private val samplingRunnable = object : Runnable {
        override fun run() {
            takeSample()
            handler.postDelayed(this, 60000) // 60,000 ms = 1 minuto (en produccion poner 300,000 para 5 min)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Iniciando asistencia...")
        startForeground(1, notification)

        // Registrar sensor y empezar bucle
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        handler.post(samplingRunnable)

        return START_STICKY
    }

    private fun takeSample() {
        totalSamples++

        // AQUÍ IRÍA LA LÓGICA BLUETOOTH BLE COMPLETA.
        // Para simplificar este MVP, simularemos que la encuentra si el celular está estable.
        val laptopFound = true // Simulación de BluetoothLeScanner detectando 'Laptop_Asistencia'

        if (laptopFound && isStable) {
            successSamples++
        }

        val percentage = if (totalSamples == 0) 0 else (successSamples * 100) / totalSamples
        updateNotification("Permanencia: $percentage% ($successSamples/$totalSamples)")
    }

    // --- LECTURA DEL ACELERÓMETRO ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Si Z está entre 9.0 y 10.5, y X, Y están cerca a 0 (reposo horizontal)
            isStable = (z in 9.0..10.5 && x in -1.5..1.5 && y in -1.5..1.5)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- NOTIFICACIONES ---
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asistencia en Curso (Aula 101)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Asistencia", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(samplingRunnable)
        sensorManager.unregisterListener(this)

        // Lanzar notificación final con el resultado
        val percentage = if (totalSamples == 0) 0 else (successSamples * 100) / totalSamples
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clase Terminada")
            .setContentText("Porcentaje de asistencia final: $percentage%")
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .build()
        notificationManager.notify(2, finalNotification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}