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

sealed class SummaryIntent {
    data class Init(val list: List<StudentSummary>, val courseName: String, val totalTime: Int) : SummaryIntent()
    object SyncToMoodle : SummaryIntent()
}

data class SummaryState(
    val summaryList: List<StudentSummary> = emptyList(),
    val courseName: String = "",
    val totalTime: Int = 0,
    val isSyncing: Boolean = false,
    val syncProgress: Int = 0,
    val syncTotal: Int = 0,
    val syncSuccess: Boolean = false
)

sealed class SummaryEffect {
    data class ShowMessage(val message: String) : SummaryEffect()
    data class ShowError(val messageRes: Int) : SummaryEffect()
}

class SummaryViewModel(
    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
) : ViewModel() {

    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state

    private val _effect = MutableSharedFlow<SummaryEffect>()
    val effect: SharedFlow<SummaryEffect> = _effect

    fun onIntent(intent: SummaryIntent) {
        when (intent) {
            is SummaryIntent.Init -> {
                _state.value = _state.value.copy(
                    summaryList = intent.list,
                    courseName = intent.courseName,
                    totalTime = intent.totalTime
                )
                syncData()
            }
            is SummaryIntent.SyncToMoodle -> syncData()
        }
    }

    private fun syncData() {
        val sessionId = SessionStore.activeSessionId
        if (sessionId == null) {
            viewModelScope.launch { _effect.emit(SummaryEffect.ShowError(com.example.smartattendance.R.string.moodle_sync_error)) }
            return
        }

        val list = _state.value.summaryList
        if (list.isEmpty()) return

        _state.value = _state.value.copy(isSyncing = true, syncSuccess = false, syncTotal = list.size, syncProgress = 0)

        viewModelScope.launch {
            var successCount = 0
            val errors = mutableListOf<String>()

            list.forEach { student ->
                repository.markAttendance(sessionId, student.id, student.status)
                    .onSuccess {
                        successCount++
                        _state.value = _state.value.copy(syncProgress = successCount)
                    }
                    .onFailure { error ->
                        errors.add("${student.fullName}: ${error.message ?: "Error Moodle"}")
                    }
            }

            val allSynced = successCount == list.size
            _state.value = _state.value.copy(isSyncing = false, syncSuccess = allSynced)

            val message = if (allSynced) {
                "Sincronizacion automatica completa"
            } else {
                val firstError = errors.firstOrNull() ?: "Moodle rechazo la sincronizacion"
                "Sincronizados $successCount de ${list.size}. $firstError"
            }
            _effect.emit(SummaryEffect.ShowMessage(message))
        }
    }
}
