package com.example.smartattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
            is SessionSelectionIntent.SelectSession -> {
                // Guardamos el ID de la sesión seleccionada antes de navegar
                com.example.smartattendance.data.remote.SessionStore.activeSessionId = intent.id
                viewModelScope.launch { _effect.emit(SessionSelectionEffect.NavigateToTeacher(null)) }
            }
            is SessionSelectionIntent.CreateSession -> createNewSession()
            is SessionSelectionIntent.GoBack -> {
                if (!_state.value.isSelectingModule) {
                    // Logic to reload modules should be triggered from UI or here
                } else {
                    viewModelScope.launch { _effect.emit(SessionSelectionEffect.Exit) }
                }
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
                    // IMPORTANTE: Para Moodle Cloud, usamos el instanceId para cargar las sesiones
                    // El cmid (it.id) sirve para el contexto del curso, pero la API de asistencia pide el instanceId.
                    val items = modules.map { GenericSelectionAdapter.SelectionItem(it.instanceId, it.name, "Módulo de Asistencia") }
                    _state.value = _state.value.copy(isLoading = false, items = items)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false)
                    _effect.emit(SessionSelectionEffect.ShowError(error.message ?: "Error al cargar módulos"))
                }
        }
    }

    private fun loadSessions(attendanceId: Int) {
        _state.value = _state.value.copy(
            isLoading = true, 
            isSelectingModule = false, 
            titleRes = com.example.smartattendance.R.string.select_session_moodle,
            showCreateButton = true
        )
        viewModelScope.launch {
            repository.getSessions(attendanceId)
                .onSuccess { sessions ->
                    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm 'Lima'", java.util.Locale("es", "PE"))
                    sdf.timeZone = TimeZone.getTimeZone("America/Lima")
                    val items = sessions.map { 
                        GenericSelectionAdapter.SelectionItem(it.id, sdf.format(java.util.Date(it.date * 1000)), it.description)
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
                    // Auto-select the newly created session
                    com.example.smartattendance.data.remote.SessionStore.activeSessionId = newSession.id
                    _effect.emit(SessionSelectionEffect.NavigateToTeacher(null))
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoading = false)
                    _effect.emit(SessionSelectionEffect.ShowError(error.message ?: "Error desconocido"))
                }
        }
    }
}
