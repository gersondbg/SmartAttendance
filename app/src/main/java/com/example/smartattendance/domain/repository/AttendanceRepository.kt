package com.example.smartattendance.domain.repository

import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.model.Course

interface AttendanceRepository {
    suspend fun login(email: String, password: String): Result<User>
    suspend fun getCurrentUserCourses(): Result<List<Course>>
    suspend fun getEnrolledStudents(courseId: Int): Result<List<User>>
}
