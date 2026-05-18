package com.example.smartattendance.domain.repository

import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.model.Course

interface AttendanceRepository {
    suspend fun login(email: String, password: String): Result<User>
    suspend fun getCurrentUserCourses(): Result<List<Course>>
    suspend fun getEnrolledStudents(courseId: Int): Result<List<User>>
    suspend fun getAttendanceModules(courseId: Int): Result<List<AttendanceModule>>
    suspend fun getSessions(attendanceId: Int): Result<List<AttendanceSession>>
    suspend fun createSession(attendanceId: Int): Result<AttendanceSession>
    suspend fun markAttendance(sessionId: Int, userId: Int, statusId: String): Result<Boolean>
}

data class AttendanceModule(val id: Int, val instanceId: Int, val name: String)
data class AttendanceSession(val id: Int, val date: Long, val description: String)
