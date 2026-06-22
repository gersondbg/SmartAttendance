package com.example.smartattendance

import android.app.Application
import com.example.smartattendance.data.remote.SessionStore

class SmartAttendanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializar SessionStore con el contexto global de la aplicación
        SessionStore.init(this)
    }
}
