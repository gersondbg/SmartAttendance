package com.example.smartattendance.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// 1. Definimos los EVENTOS que la UI envía (Intents)
sealed class LoginIntent {
    data class LoginUser(val email: String, val pass: String) : LoginIntent()
    object DemoLogin : LoginIntent()
}

// 2. Definimos el ESTADO de la pantalla
data class LoginState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val errorMessage: String? = null
)

// 3. Definimos EFECTOS (eventos de un solo uso como navegación o Toasts)
sealed class LoginEffect {
    data class NavigateToHome(val user: User) : LoginEffect()
    data class ShowToast(val messageRes: Int) : LoginEffect()
}

class LoginViewModel(
    private val loginUseCase: LoginUseCase = LoginUseCase(AttendanceRepositoryImpl())
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    private val _effect = MutableSharedFlow<LoginEffect>()
    val effect: SharedFlow<LoginEffect> = _effect

    // Función centralizada para procesar EVENTOS
    fun onIntent(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.LoginUser -> performLogin(intent.email, intent.pass)
            is LoginIntent.DemoLogin -> enterDemoMode()
        }
    }

    private fun performLogin(email: String, pass: String) {
        if (email.isEmpty() || pass.isEmpty()) {
            viewModelScope.launch { _effect.emit(LoginEffect.ShowToast(com.example.smartattendance.R.string.error_empty_credentials)) }
            return
        }

        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        
        viewModelScope.launch {
            val result = loginUseCase(email, pass)
            result.onSuccess { user ->
                _state.value = _state.value.copy(isLoading = false, user = user)
                _effect.emit(LoginEffect.NavigateToHome(user))
            }.onFailure { error ->
                _state.value = _state.value.copy(isLoading = false, errorMessage = error.message)
                _effect.emit(LoginEffect.ShowToast(com.example.smartattendance.R.string.invalid_credentials))
            }
        }
    }

    private fun enterDemoMode() {
        val demoUser = User(2, "demo_teacher", "Profesor de Prueba", "demo@moodle.com", "teacher")
        _state.value = _state.value.copy(user = demoUser)
        viewModelScope.launch {
            _effect.emit(LoginEffect.NavigateToHome(demoUser))
        }
    }
}
