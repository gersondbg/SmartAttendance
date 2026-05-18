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
    data class OnStudentDiscovered(val username: String, val isMoving: Boolean, val isMaintenanceRestart: Boolean = false) : TeacherIntent()
    object Tick : TeacherIntent()
    object ResetAttendance : TeacherIntent()
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
    val presentSeconds: Int = 0,
    val lastSeenAtMillis: Long = 0L,
    val maintenanceUntilMillis: Long = 0L,
    val connected: Boolean = false,
    val isMoving: Boolean = false,
    val disconnections: Int = 0,
    val manualStatus: String? = null
)

sealed class TeacherEffect {
    data class ShowMessage(val messageRes: Int) : TeacherEffect()
    data class NavigateToSummary(val summary: List<StudentSummary>, val totalTime: Int) : TeacherEffect()
}

class TeacherViewModel(
    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
) : ViewModel() {
    private companion object {
        private const val BLE_PRESENT_GRACE_MS = 45_000L
        private const val BLE_DISCONNECT_GRACE_MS = 75_000L
    }

    private val _state = MutableStateFlow(TeacherState(
        courseName = SessionStore.activeCourseName ?: "Curso",
        sessionName = SessionStore.activeAttendanceName ?: "Asistencia"
    ))
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
            is TeacherIntent.OnStudentDiscovered -> handleStudentDiscovery(intent.username, intent.isMoving, intent.isMaintenanceRestart)
            is TeacherIntent.Tick -> handleTick()
            is TeacherIntent.ResetAttendance -> resetAttendance()
        }
    }

    private fun resetAttendance() {
        val currentState = _state.value
        val resetStudents = currentState.students.mapValues { (_, stats) ->
            stats.copy(
                presentSeconds = 0,
                connected = false,
                isMoving = false,
                disconnections = 0,
                manualStatus = null,
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
    }

    private fun loadInitialData() {
        val courseId = SessionStore.activeCourseId ?: return
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repository.getEnrolledStudents(courseId).onSuccess { list ->
                android.util.Log.d("TeacherVM", "Estudiantes recibidos de Moodle: ${list.size}")
                
                // Mapear y forzar estado inicial "A" (Ausente) para que sean visibles
                val studentsMap = list.associate { it.id to StudentStats(
                    id = it.id, 
                    username = it.username, 
                    fullName = it.fullname, 
                    email = it.email,
                    manualStatus = "A" // Forzamos estado inicial para visualización
                ) }
                
                _state.value = _state.value.copy(
                    isLoading = false, 
                    students = studentsMap,
                    courseName = SessionStore.activeCourseName ?: "Curso",
                    sessionName = SessionStore.activeAttendanceName ?: "Asistencia"
                )
            }.onFailure { error ->
                android.util.Log.e("TeacherVM", "Fallo al cargar estudiantes", error)
                _state.value = _state.value.copy(isLoading = false)
                _effect.emit(TeacherEffect.ShowMessage(com.example.smartattendance.R.string.error_loading_data))
            }
        }
    }

    private fun startClass(durationMin: Int) {
        val durationSec = durationMin * 60
        val now = System.currentTimeMillis()
        val studentsReady = _state.value.students.mapValues { (_, stats) ->
            val recentlySeen = stats.lastSeenAtMillis > 0 && now - stats.lastSeenAtMillis <= BLE_PRESENT_GRACE_MS
            stats.copy(
                connected = recentlySeen,
                manualStatus = if (recentlySeen) "P" else "A",
                presentSeconds = 0,
                disconnections = 0
            )
        }
        _state.value = _state.value.copy(
            isClassActive = true,
            isPaused = false,
            initialDurationSeconds = durationSec,
            remainingSeconds = durationSec,
            elapsedSeconds = 0,
            students = studentsReady
        )
    }

    private fun handleTick() {
        val currentState = _state.value
        if (!currentState.isClassActive || currentState.isPaused) return

        val newElapsed = currentState.elapsedSeconds + 1
        val newRemaining = (currentState.remainingSeconds - 1).coerceAtLeast(0)
        
        val now = System.currentTimeMillis()
        val updatedStudents = currentState.students.mapValues { (_, stats) ->
            var newStats = stats
            val msSinceLastSeen = if (stats.lastSeenAtMillis > 0) now - stats.lastSeenAtMillis else Long.MAX_VALUE
            val inMaintenanceRestart = now <= stats.maintenanceUntilMillis
            val countedAsPresent = stats.connected || inMaintenanceRestart || msSinceLastSeen <= BLE_PRESENT_GRACE_MS

            if (!inMaintenanceRestart && stats.lastSeenAtMillis > 0 && msSinceLastSeen > BLE_DISCONNECT_GRACE_MS) {
                newStats = newStats.copy(
                    connected = false,
                    disconnections = if (stats.connected) stats.disconnections + 1 else stats.disconnections
                )
            }

            if (countedAsPresent && !newStats.isMoving) {
                newStats = newStats.copy(presentSeconds = newStats.presentSeconds + 1)
            }
            newStats
        }

        _state.value = currentState.copy(
            elapsedSeconds = newElapsed,
            remainingSeconds = newRemaining,
            students = updatedStudents,
            isClassActive = newRemaining > 0
        )
        
        if (newRemaining == 0) finishClass()
    }

    private fun handleStudentDiscovery(username: String, isMoving: Boolean, isMaintenanceRestart: Boolean) {
        val currentState = _state.value
        val studentEntry = currentState.students.entries.firstOrNull { it.value.username.equals(username, true) }
        
        if (studentEntry != null) {
            val stats = studentEntry.value
            val now = System.currentTimeMillis()
            val updatedStats = stats.copy(
                lastSeenAtMillis = now,
                maintenanceUntilMillis = if (isMaintenanceRestart) now + BLE_DISCONNECT_GRACE_MS else stats.maintenanceUntilMillis,
                connected = true,
                isMoving = isMoving,
                manualStatus = if (currentState.isClassActive && (stats.manualStatus == null || stats.manualStatus == "A")) "P" else stats.manualStatus
            )
            val updatedStudents = currentState.students.toMutableMap()
            updatedStudents[studentEntry.key] = updatedStats
            _state.value = currentState.copy(students = updatedStudents)
        }
    }

    private fun handleManualStatusChange(studentId: Int, newStatus: String) {
        val currentState = _state.value
        val stats = currentState.students[studentId] ?: return
        val updatedStats = stats.copy(manualStatus = newStatus)
        val updatedStudents = currentState.students.toMutableMap()
        updatedStudents[studentId] = updatedStats
        
        _state.value = currentState.copy(students = updatedStudents)
            
        // Background sync of manual changes
        SessionStore.activeSessionId?.let { sid ->
            viewModelScope.launch { repository.markAttendance(sid, studentId, newStatus) }
        }
    }

    private fun finishClass() {
        val currentState = _state.value
        _state.value = currentState.copy(isClassActive = false)
        
        val summaryList = currentState.students.values.map {
            val conc = if (currentState.elapsedSeconds > 0) 
                (it.presentSeconds * 100 / currentState.elapsedSeconds).coerceIn(0, 100) 
                else 0
            val finalStatus = it.manualStatus ?: if (it.connected) "P" else "A"
            StudentSummary(
                id = it.id,
                fullName = it.fullName,
                username = it.username,
                status = finalStatus,
                concentration = conc,
                presentSeconds = it.presentSeconds
            )
        }
        
        viewModelScope.launch {
            // Sincronización masiva final con Moodle
            SessionStore.activeSessionId?.let { sid ->
                summaryList.forEach { student ->
                    repository.markAttendance(sid, student.id, student.status)
                }
            }
            _effect.emit(TeacherEffect.NavigateToSummary(summaryList, currentState.elapsedSeconds))
        }
    }
}
