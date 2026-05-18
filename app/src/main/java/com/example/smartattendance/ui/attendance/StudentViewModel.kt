package com.example.smartattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.remote.SessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class StudentIntent {
    data class Init(val userName: String, val username: String) : StudentIntent()
    object ActivateVisibility : StudentIntent()
    data class UpdateStability(val isStable: Boolean) : StudentIntent()
    object Tick : StudentIntent()
    object Logout : StudentIntent()
}

data class StudentState(
    val userName: String = "",
    val username: String = "",
    val isVisibilityActive: Boolean = false,
    val isStable: Boolean = true,
    val totalSeconds: Int = 0,
    val presentSeconds: Int = 0,
    val concentrationPercent: Int = 0
)

sealed class StudentEffect {
    data class ShowMessage(val message: String) : StudentEffect()
    object NavigateToLogin : StudentEffect()
    data class StartAdvertising(val prefix: String, val username: String) : StudentEffect()
    data class StartPresenceService(val username: String, val stable: Boolean) : StudentEffect()
    data class UpdatePresenceService(val stable: Boolean) : StudentEffect()
    object StopAdvertising : StudentEffect()
    object StopPresenceService : StudentEffect()
}

class StudentViewModel : ViewModel() {

    private val _state = MutableStateFlow(StudentState())
    val state: StateFlow<StudentState> = _state

    private val _effect = MutableSharedFlow<StudentEffect>()
    val effect: SharedFlow<StudentEffect> = _effect

    fun onIntent(intent: StudentIntent) {
        when (intent) {
            is StudentIntent.Init -> {
                _state.value = _state.value.copy(userName = intent.userName, username = intent.username)
            }
            is StudentIntent.ActivateVisibility -> {
                _state.value = _state.value.copy(
                    isVisibilityActive = true,
                    totalSeconds = 0,
                    presentSeconds = 0,
                    concentrationPercent = 0
                )
                viewModelScope.launch {
                    val prefix = if (_state.value.isStable) "SA-" else "MV-"
                    _effect.emit(StudentEffect.StartAdvertising(prefix, _state.value.username))
                    _effect.emit(StudentEffect.StartPresenceService(_state.value.username, _state.value.isStable))
                }
            }
            is StudentIntent.UpdateStability -> {
                if (_state.value.isStable != intent.isStable) {
                    val oldStable = _state.value.isStable
                    _state.value = _state.value.copy(isStable = intent.isStable)
                    
                    if (_state.value.isVisibilityActive) {
                        viewModelScope.launch {
                            val prefix = if (intent.isStable) "SA-" else "MV-"
                            _effect.emit(StudentEffect.StartAdvertising(prefix, _state.value.username))
                            _effect.emit(StudentEffect.UpdatePresenceService(intent.isStable))
                        }
                    }
                }
            }
            is StudentIntent.Tick -> handleTick()
            is StudentIntent.Logout -> logout()
        }
    }

    private fun handleTick() {
        val currentState = _state.value
        if (!currentState.isVisibilityActive) return

        val newTotal = currentState.totalSeconds + 1
        var newPresent = currentState.presentSeconds
        
        if (currentState.isStable) {
            newPresent++
        } else {
            if (newPresent > 0) newPresent--
        }

        val newPercent = if (newTotal == 0) 0 else (newPresent * 100) / newTotal

        _state.value = currentState.copy(
            totalSeconds = newTotal,
            presentSeconds = newPresent,
            concentrationPercent = newPercent
        )
    }

    private fun logout() {
        SessionStore.clear()
        _state.value = StudentState()
        viewModelScope.launch {
            _effect.emit(StudentEffect.StopAdvertising)
            _effect.emit(StudentEffect.StopPresenceService)
            _effect.emit(StudentEffect.NavigateToLogin)
        }
    }
}
