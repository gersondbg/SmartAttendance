package com.example.smartattendance.ui.attendance

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AttendanceEvent(
    val type: String,
    val title: String,
    val detail: String,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
}

data class AttendanceDashboardState(
    val courseName: String = "Curso",
    val sessionName: String = "Asistencia",
    val classActive: Boolean = false,
    val paused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val totalStudents: Int = 0,
    val attendanceCount: Int = 0,
    val livePresentCount: Int = 0,
    val movingCount: Int = 0,
    val signalLostCount: Int = 0,
    val disconnectedCount: Int = 0,
    val averageConcentration: Int = 0,
    val totalCuts: Int = 0,
    val recentEvents: List<AttendanceEvent> = emptyList()
)

object AttendanceEventBus {
    private const val MAX_EVENTS = 20

    private val _events = MutableSharedFlow<AttendanceEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AttendanceEvent> = _events.asSharedFlow()

    private val _dashboard = MutableStateFlow(AttendanceDashboardState())
    val dashboard: StateFlow<AttendanceDashboardState> = _dashboard.asStateFlow()

    fun publish(event: AttendanceEvent) {
        _events.tryEmit(event)
        _dashboard.value = _dashboard.value.copy(
            recentEvents = (listOf(event) + _dashboard.value.recentEvents).take(MAX_EVENTS)
        )
    }

    fun updateFromTeacherState(state: TeacherState) {
        val students = state.students.values.toList()
        val average = if (students.isNotEmpty() && state.elapsedSeconds > 0) {
            students.map { (it.presentSeconds * 100 / state.elapsedSeconds).coerceIn(0, 100) }.average().toInt()
        } else 0

        _dashboard.value = _dashboard.value.copy(
            courseName = state.courseName,
            sessionName = state.sessionName,
            classActive = state.isClassActive,
            paused = state.isPaused,
            elapsedSeconds = state.elapsedSeconds,
            remainingSeconds = state.remainingSeconds,
            totalStudents = students.size,
            attendanceCount = students.count { it.attended || it.manualStatus == "P" },
            livePresentCount = students.count { it.presenceState == PresenceState.InClass },
            movingCount = students.count { it.presenceState == PresenceState.Moving },
            signalLostCount = students.count { it.presenceState == PresenceState.SignalLost },
            disconnectedCount = students.count { it.presenceState == PresenceState.Disconnected },
            averageConcentration = average,
            totalCuts = students.sumOf { it.disconnections }
        )
    }
}
