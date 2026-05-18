package com.example.smartattendance.domain.model

import java.io.Serializable

data class StudentSummary(
    val id: Int,
    val fullName: String,
    val username: String,
    val status: String,
    val concentration: Int,
    val presentSeconds: Int
) : Serializable
