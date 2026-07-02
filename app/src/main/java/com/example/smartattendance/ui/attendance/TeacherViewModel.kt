package com.example.smartattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.model.StudentSummary
import com.example.smartattendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class TeacherIntent {
    object LoadStudents : TeacherIntent()
    data class StartClass(val durationMinutes: Int) : TeacherIntent()
    object TogglePause : TeacherIntent()
    object FinishClass : TeacherIntent()
    data class UpdateStudentStatus(val studentId: Int, val status: String) : TeacherIntent()
    data class OnStudentDiscovered(
        val username: String,
        val isMoving: Boolean,
        val isMaintenanceRestart: Boolean = false,
        val rssi: Int = -100
    ) : TeacherIntent()
    object Tick : TeacherIntent()
    object ResetAttendance : TeacherIntent()
}

enum class PresenceState {
    NotSeen,
    InClass,
    Moving,
    Restarting,
    SignalLost,
    Disconnected
}

data class TeacherState(
    val isLoading: Boolean = false,
    val isClassActive: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val initialDurationSeconds: Int = 0,
    val students: Map<Int, StudentStats> = emptyMap(),
    val courseName: String = "",
    val sessionName: String = ""
)

data class StudentStats(
    val id: Int,
    val username: String,
    val fullName: String,
    val email: String,
    val attended: Boolean = false,
    val presenceState: PresenceState = PresenceState.NotSeen,
    val presentSeconds: Int = 0,
    val lastSeenAtMillis: Long = 0L,
    val maintenanceUntilMillis: Long = 0L,
    val isMoving: Boolean = false,
    val disconnections: Int = 0,
    val manualStatus: String? = null,
    val lastSeenRssi: Int = -100
)

sealed class TeacherEffect {
    data class ShowMessage(val messageRes: Int) : TeacherEffect()
    data class NavigateToSummary(val summary: List<StudentSummary>, val totalTime: Int) : TeacherEffect()
}

class TeacherViewModel(
    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
) : ViewModel() {

    private companion object {
        private const val SIGNAL_LOST_MS = 4_000L
        private const val DISCONNECTED_MS = 10_000L
        private const val RESTART_GRACE_MS = 60_000L
    }

    private val _state = MutableStateFlow(
        TeacherState(
            courseName = SessionStore.activeCourseName ?: "Curso",
            sessionName = SessionStore.activeAttendanceName ?: "Asistencia"
        )
    )
    val state: StateFlow<TeacherState> = _state

    private val _effect = MutableSharedFlow<TeacherEffect>()
    val effect: SharedFlow<TeacherEffect> = _effect

    fun onIntent(intent: TeacherIntent) {
        when (intent) {
            is TeacherIntent.LoadStudents -> loadInitialData()
            is TeacherIntent.StartClass -> startClass(intent.durationMinutes)
            is TeacherIntent.TogglePause -> _state.value = _state.value.copy(isPaused = !_state.value.isPaused)
            is TeacherIntent.FinishClass -> finishClass()
            is TeacherIntent.UpdateStudentStatus -> handleManualStatusChange(intent.studentId, intent.status)
            is TeacherIntent.OnStudentDiscovered -> handleStudentDiscovery(
                intent.username,
                intent.isMoving,
                intent.isMaintenanceRestart,
                intent.rssi
            )
            is TeacherIntent.Tick -> handleTick()
            is TeacherIntent.ResetAttendance -> resetAttendance()
        }
        AttendanceEventBus.updateFromTeacherState(_state.value)
    }

    private fun resetAttendance() {
        val currentState = _state.value
        val resetStudents = currentState.students.mapValues { (_, stats) ->
            stats.copy(
                attended = false,
                presenceState = PresenceState.NotSeen,
                presentSeconds = 0,
                isMoving = false,
                disconnections = 0,
                manualStatus = "A",
                lastSeenAtMillis = 0L,
                maintenanceUntilMillis = 0L
            )
        }
        _state.value = currentState.copy(
            elapsedSeconds = 0,
            remainingSeconds = currentState.initialDurationSeconds,
            students = resetStudents,
            isPaused = false
        )
        AttendanceEventBus.publish(AttendanceEvent("RESET", "Asistencia reiniciada", "El profesor reinicio los estados de la clase."))
    }

    private fun loadInitialData() {
        val courseId = SessionStore.activeCourseId ?: return
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repository.getEnrolledStudents(courseId).onSuccess { list ->
                val studentsMap = list.associate {
                    it.id to StudentStats(
                        id = it.id,
                        username = it.username,
                        fullName = it.fullname,
                        email = it.email,
                        manualStatus = "A"
                    )
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    students = studentsMap,
                    courseName = SessionStore.activeCourseName ?: "Curso",
                    sessionName = SessionStore.activeAttendanceName ?: "Asistencia"
                )
            }.onFailure {
                _state.value = _state.value.copy(isLoading = false)
                _effect.emit(TeacherEffect.ShowMessage(com.example.smartattendance.R.string.error_loading_data))
            }
        }
    }

    private fun startClass(durationMin: Int) {
        val durationSec = durationMin * 60
        val resetForClass = _state.value.students.mapValues { (_, stats) ->
            stats.copy(
                attended = false,
                presenceState = PresenceState.NotSeen,
                presentSeconds = 0,
                disconnections = 0,
                manualStatus = "A",
                lastSeenAtMillis = 0L,
                maintenanceUntilMillis = 0L,
                isMoving = false
            )
        }
        _state.value = _state.value.copy(
            isClassActive = true,
            isPaused = false,
            initialDurationSeconds = durationSec,
            remainingSeconds = durationSec,
            elapsedSeconds = 0,
            students = resetForClass
        )
        AttendanceEventBus.publish(AttendanceEvent("CLASS_STARTED", "Clase iniciada", "Duracion programada: $durationMin minutos."))
    }

    private fun handleTick() {
        val currentState = _state.value
        if (!currentState.isClassActive || currentState.isPaused) return

        val now = System.currentTimeMillis()
        val updatedStudents = currentState.students.mapValues { (_, stats) ->
            val sinceLastSeen = if (stats.lastSeenAtMillis > 0) now - stats.lastSeenAtMillis else Long.MAX_VALUE
            val inRestart = now <= stats.maintenanceUntilMillis

            var nextState = stats.presenceState
            var newDisconnections = stats.disconnections

            if (inRestart) {
                nextState = PresenceState.Restarting
            } else if (stats.lastSeenAtMillis == 0L) {
                nextState = PresenceState.NotSeen
            } else if (sinceLastSeen > DISCONNECTED_MS) {
                if (stats.presenceState != PresenceState.Disconnected) {
                    newDisconnections += 1
                }
                nextState = PresenceState.Disconnected
            } else if (sinceLastSeen > SIGNAL_LOST_MS) {
                nextState = PresenceState.SignalLost
            }

            val shouldAddConcentration = nextState == PresenceState.InClass
            stats.copy(
                presenceState = nextState,
                disconnections = newDisconnections,
                presentSeconds = if (shouldAddConcentration) stats.presentSeconds + 1 else stats.presentSeconds
            )
        }

        val newRemaining = (currentState.remainingSeconds - 1).coerceAtLeast(0)
        _state.value = currentState.copy(
            elapsedSeconds = currentState.elapsedSeconds + 1,
            remainingSeconds = newRemaining,
            students = updatedStudents,
            isClassActive = newRemaining > 0
        )

        if (newRemaining == 0) finishClass()
    }
    
    private var maxDetectionMeters: Int = 15
    
    fun setMaxDetectionMeters(meters: Int) {
        maxDetectionMeters = meters
    }
    
    private fun getRssiCutoff(meters: Int): Int {
        // Approximate formula: 1m = -50, 5m = -70, 15m = -90
        return when {
            meters <= 2 -> -60
            meters <= 5 -> -70
            meters <= 10 -> -80
            else -> -95
        }
    }
    
    private fun getSignalStrengthText(rssi: Int): String {
        return when {
            rssi > -60 -> "Fuerte"
            rssi > -80 -> "Media"
            else -> "DÃ©bil"
        }
    }

    private fun handleStudentDiscovery(username: String, isMoving: Boolean, isMaintenanceRestart: Boolean, rssi: Int) {
        val currentState = _state.value
        val studentEntry = currentState.students.entries.firstOrNull {
            it.value.username.equals(username, ignoreCase = true)
        } ?: return
        
        val cutoff = getRssiCutoff(maxDetectionMeters)
        if (rssi < cutoff) {
            // Signal is too weak, student is outside the selected range. Ignore packet.
            return
        }

        val now = System.currentTimeMillis()
        val stats = studentEntry.value
        val attended = currentState.isClassActive || stats.attended
        val presenceState = when {
            isMaintenanceRestart -> PresenceState.Restarting
            isMoving -> PresenceState.Moving
            else -> PresenceState.InClass
        }
        val updatedStats = stats.copy(
            attended = attended,
            manualStatus = if (attended) "P" else stats.manualStatus,
            presenceState = presenceState,
            lastSeenAtMillis = now,
            maintenanceUntilMillis = if (isMaintenanceRestart) now + RESTART_GRACE_MS else stats.maintenanceUntilMillis,
            isMoving = isMoving,
            lastSeenRssi = rssi
        )

        val updatedStudents = currentState.students.toMutableMap()
        updatedStudents[studentEntry.key] = updatedStats
        _state.value = currentState.copy(students = updatedStudents)
        val eventTitle = when (presenceState) {
            PresenceState.InClass -> "Alumno detectado"
            PresenceState.Moving -> "Alumno en movimiento"
            PresenceState.Restarting -> "Reinicio tecnico"
            else -> "Cambio de presencia"
        }
        AttendanceEventBus.publish(AttendanceEvent("PRESENCE", eventTitle, "${updatedStats.fullName}: ${presenceState.name} / RSSI $rssi"))
    }

    private fun handleManualStatusChange(studentId: Int, newStatus: String) {
        val currentState = _state.value
        val stats = currentState.students[studentId] ?: return
        val updatedStats = stats.copy(
            manualStatus = newStatus,
            attended = newStatus == "P" || stats.attended
        )
        val updatedStudents = currentState.students.toMutableMap()
        updatedStudents[studentId] = updatedStats
        _state.value = currentState.copy(students = updatedStudents)

        SessionStore.activeSessionId?.let { sid ->
            viewModelScope.launch { repository.markAttendance(sid, studentId, newStatus) }
        }
    }

    private fun presenceLabel(state: PresenceState): String {
        return when (state) {
            PresenceState.NotSeen -> "NO VISTO"
            PresenceState.InClass -> "EN AULA"
            PresenceState.Moving -> "MOVIMIENTO"
            PresenceState.Restarting -> "REINICIO APP"
            PresenceState.SignalLost -> "SIN SE?AL"
            PresenceState.Disconnected -> "DESCONECTADO"
        }
    }

    private fun finishClass() {
        val currentState = _state.value
        _state.value = currentState.copy(isClassActive = false)
        AttendanceEventBus.publish(AttendanceEvent("CLASS_FINISHED", "Clase finalizada", "Se genero el resumen y se sincronizara con Moodle."))

        val summaryList = currentState.students.values.map {
            val concentration = if (currentState.elapsedSeconds > 0) {
                (it.presentSeconds * 100 / currentState.elapsedSeconds).coerceIn(0, 100)
            } else {
                0
            }
            val finalStatus = it.manualStatus ?: if (it.attended) "P" else "A"
            StudentSummary(
                id = it.id,
                fullName = it.fullName,
                username = it.username,
                status = finalStatus,
                concentration = concentration,
                presentSeconds = it.presentSeconds
            )
        }

        viewModelScope.launch {
            SessionStore.activeSessionId?.let { sid ->
                summaryList.forEach { student ->
                    repository.markAttendance(sid, student.id, student.status)
                }
            }
            _effect.emit(TeacherEffect.NavigateToSummary(summaryList, currentState.elapsedSeconds))
        }
    }
}
