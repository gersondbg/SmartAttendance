package com.example.smartattendance.ui.attendance

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartattendance.databinding.ActivityTeacherDashboardBinding
import kotlinx.coroutines.launch

class TeacherDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTeacherDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTeacherDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            AttendanceEventBus.dashboard.collect { state -> render(state) }
        }
    }

    private fun render(state: AttendanceDashboardState) = with(binding) {
        tvTitle.text = "Dashboard en tiempo real"
        tvCourse.text = "${state.courseName}\n${state.sessionName}"
        tvSessionState.text = when {
            state.paused -> "ESTADO: CLASE PAUSADA"
            state.classActive -> "ESTADO: CLASE ACTIVA"
            else -> "ESTADO: CLASE NO INICIADA"
        }
        tvAttendanceValue.text = "${state.attendanceCount}/${state.totalStudents}"
        tvLiveValue.text = state.livePresentCount.toString()
        tvConcentrationValue.text = "${state.averageConcentration}%"
        tvCutsValue.text = state.totalCuts.toString()
        tvPresenceBreakdown.text = "Distribución en vivo\nEn aula: ${state.livePresentCount}   Movimiento: ${state.movingCount}\nSin señal: ${state.signalLostCount}   Desconectados: ${state.disconnectedCount}"
        progressAttendance.max = state.totalStudents.coerceAtLeast(1)
        progressAttendance.progress = state.attendanceCount.coerceAtMost(progressAttendance.max)
        progressConcentration.progress = state.averageConcentration.coerceIn(0, 100)
        tvEvents.text = if (state.recentEvents.isEmpty()) {
            "Sin eventos todavía. Inicia la clase o escanea alumnos para ver actividad en tiempo real."
        } else {
            state.recentEvents.joinToString("\n\n") { "${it.timeLabel}  |  ${it.title}\n${it.detail}" }
        }
    }
}
