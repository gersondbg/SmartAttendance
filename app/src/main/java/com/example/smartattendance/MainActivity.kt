package com.example.smartattendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. PEDIR PERMISOS OBLIGATORIOS EN PANTALLA ANTES DE HACER CUALQUIER COSA
        checkAndRequestPermissions()

        val btnTestMode = findViewById<Button>(R.id.btnTestMode)
        val btnStopTest = findViewById<Button>(R.id.btnStopTest)

        // Botón INICIAR
        btnTestMode.setOnClickListener {
            val serviceIntent = Intent(this, AttendanceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Servicio de Asistencia Iniciado", Toast.LENGTH_SHORT).show()
        }

        // Botón DETENER
        btnStopTest.setOnClickListener {
            val serviceIntent = Intent(this, AttendanceService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "Servicio Detenido", Toast.LENGTH_SHORT).show()
        }
    }

    // --- LÓGICA PARA MOSTRAR LAS VENTANITAS DE PERMISOS ---
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Permiso de Notificaciones (Obligatorio en Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Permisos de Bluetooth (Obligatorio en Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
        }

        // Permiso de Ubicación (Obligatorio para escanear señales inalámbricas)
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Filtrar cuáles permisos aún no nos han dado
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // Si falta alguno, mostrar la ventana del sistema
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }
}