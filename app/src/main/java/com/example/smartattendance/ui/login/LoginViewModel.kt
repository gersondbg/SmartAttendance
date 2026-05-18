package com.example.smartattendance.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartattendance.data.repository.AttendanceRepositoryImpl
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val loginUseCase: LoginUseCase = LoginUseCase(AttendanceRepositoryImpl())
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    fun login(email: String, password: String = "") {
        if (email.isEmpty()) {
            _loginState.value = LoginState.Error("Por favor ingresa un usuario")
            return
        }

        // El bypass ahora se maneja principalmente en la Activity, 
        // pero dejamos esto por consistencia si se llama directamente.
        if (email.trim().lowercase() == "demo") {
            _loginState.value = LoginState.Success(
                User(2, "demo_teacher", "Profesor de Prueba", "demo@moodle.com", "teacher")
            )
            return
        }

        _loginState.value = LoginState.Loading
        viewModelScope.launch {
            val result = loginUseCase(email, password)
            result.onSuccess { user ->
                _loginState.value = LoginState.Success(user)
            }.onFailure { error ->
                _loginState.value = LoginState.Error(error.message ?: "Error desconocido")
            }
        }
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        data class Success(val user: User) : LoginState()
        data class Error(val message: String) : LoginState()
    }
}
