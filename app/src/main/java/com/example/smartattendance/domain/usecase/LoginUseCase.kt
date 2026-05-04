package com.example.smartattendance.domain.usecase

import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.repository.AttendanceRepository

class LoginUseCase(private val repository: AttendanceRepository) {
    suspend operator fun invoke(email: String, password: String): Result<User> {
        return repository.login(email, password)
    }
}
