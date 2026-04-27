package com.example.smartattendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val serviceIntent = Intent(context, AttendanceService::class.java)

        if (action == "START_CLASS") {
            // Arrancar el servicio de monitoreo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else if (action == "STOP_CLASS") {
            // Detener el servicio a las 10 PM
            context.stopService(serviceIntent)
        }
    }
}