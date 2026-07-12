package com.example.smartattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

sealed class SessionSelectionIntent {
    data class LoadModules(val courseId: Int) : SessionSelectionIntent()
    data class LoadSessions(val attendanceId: Int) : SessionSelectionIntent()
    data class SelectModule(val id: Int, val name: String) : SessionSelectionIntent()
    data class SelectSession(val id: Int) : SessionSelectionIntent()
    object CreateSession : SessionSelectionIntent()
    object GoBack : SessionSelectionIntent()
}

data class SessionSelectionState(
    val isLoading: Boolean = false,
    val isSelectingModule: Boolean = true,
    val items: List<GenericSelectionAdapter.SelectionItem> = emptyList(),
    val titleRes: Int = com.example.smartattendance.R.string.select_module,
    val showCreateButton: Boolean = false,
    val currentAttendanceId: Int = 0
)

sealed class SessionSelectionEffect {
    data class NavigateToTeacher(val userName: String?) : SessionSelectionEffect()
    data class NavigateToSummaryPreview(
        val sessionId: Int,
        val sessionTitle: String,
        val sessionDescription: String
    ) : SessionSelectionEffect()
    object Exit : SessionSelectionEffect()
    data class ShowError(val message: String) : SessionSelectionEffect()
}

class SessionSelectionViewModel(
    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
) : ViewModel() {

    private val _state = MutableStateFlow(SessionSelectionState())
    val state: StateFlow<SessionSelectionState> = _state

    private val _effect = MutableSharedFlow<SessionSelectionEffect>()
    val effect: SharedFlow<SessionSelectionEffect> = _effect

    fun onIntent(intent: SessionSelectionIntent) {
        when (intent) {
            is SessionSelectionIntent.LoadModules -> loadModules(intent.courseId)
            is SessionSelectionIntent.LoadSessions -> loadSessions(intent.attendanceId)
            is SessionSelectionIntent.SelectModule -> {
                _state.value = _state.value.copy(currentAttendanceId = intent.id)
                onIntent(SessionSelectionIntent.LoadSessions(intent.id))
            }
            is SessionSelectionIntent.SelectSession -> selectSession(intent.id)
            is SessionSelectionIntent.CreateSession -> createNewSession()
            is SessionSelectionIntent.GoBack -> {
                if (_state.value.isSelectingModule) {
                    viewModelScope.launch { _effect.emit(SessionSelectionEffect.Exit) }
                }
            }
        }
    }

    private fun selectSession(sessionId: Int) {
        val selected = _state.value.items.firstOrNull { it.id == sessionId }
        SessionStore.activeSessionId = sessionId
        viewModelScope.launch {
            if (selected?.isPast == true && !SessionStore.activeClassRunning) {
                _effect.emit(
                    SessionSelectionEffect.NavigateToSummaryPreview(
                        sessionId = sessionId,
                        sessionTitle = selected.title,
                        sessionDescription = selected.subtitle
                    )
                )
            } else {
                _effect.emit(SessionSelectionEffect.NavigateToTeacher(null))
            }
        }
    }

    private fun loadModules(courseId: Int) {
        _state.value = _state.value.copy(
            isLoading = true,
            isSelectingModule = true,
            titleRes = com.example.smartattendance.R.string.select_module,
            showCreateButton = false
        )
        viewModelScope.launch {
            repository.getAttendanceModules(courseId)
                .onSuccess { modules ->
                    val items = modules.map {
                        GenericSelectionAdapter.SelectionItem(
                            id = it.instanceId,
                            title = it.name,
                            subtitle = "Modulo de Asistencia"
                        )
                    }
                    _state.value = _state.value.copy(isLoading = false, items = items)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false)
                    _effect.emit(SessionSelectionEffect.ShowError(error.message ?: "Error al cargar modulos"))
                }
        }
    }

    private fun loadSessions(attendanceId: Int) {
        _state.value = _state.value.copy(
            isLoading = true,
            isSelectingModule = false,
            titleRes = com.example.smartattendance.R.string.select_session_moodle,
            showCreateButton = true,
            currentAttendanceId = attendanceId
        )
        viewModelScope.launch {
            repository.getSessions(attendanceId)
                .onSuccess { sessions ->
                    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm 'Lima'", java.util.Locale("es", "PE"))
                    sdf.timeZone = TimeZone.getTimeZone("America/Lima")
                    val items = sessions.map {
                        GenericSelectionAdapter.SelectionItem(
                            id = it.id,
                            title = sdf.format(java.util.Date(it.date * 1000)),
                            subtitle = it.description,
                            startsAtSeconds = it.date,
                            isPast = isBeforeTodayLima(it.date)
                        )
                    }
                    _state.value = _state.value.copy(isLoading = false, items = items)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false, items = emptyList())
                    _effect.emit(SessionSelectionEffect.ShowError(error.message ?: "Error desconocido"))
                }
        }
    }

    private fun createNewSession() {
        val attendanceId = _state.value.currentAttendanceId
        if (attendanceId == 0) return

        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repository.createSession(attendanceId)
                .onSuccess { newSession ->
                    SessionStore.activeSessionId = newSession.id
                    _effect.emit(SessionSelectionEffect.NavigateToTeacher(null))
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false)
                    _effect.emit(SessionSelectionEffect.ShowError(error.message ?: "Error desconocido"))
                }
        }
    }

    private fun isBeforeTodayLima(timestampSeconds: Long): Boolean {
        val zone = TimeZone.getTimeZone("America/Lima")
        val session = Calendar.getInstance(zone).apply { timeInMillis = timestampSeconds * 1000L }
        val today = Calendar.getInstance(zone)
        return session.get(Calendar.YEAR) < today.get(Calendar.YEAR) ||
            (session.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                session.get(Calendar.DAY_OF_YEAR) < today.get(Calendar.DAY_OF_YEAR))
    }
}
