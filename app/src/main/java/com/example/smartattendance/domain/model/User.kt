package com.example.smartattendance.domain.model

data class User(
    val id: Int,
    val username: String,
    val fullname: String,
    val email: String,
    val role: String // "teacher" o "student" (determinado por el dominio del correo)
)
