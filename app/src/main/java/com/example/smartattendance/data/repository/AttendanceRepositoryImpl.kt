package com.example.smartattendance.data.repository

import com.example.smartattendance.data.remote.MoodleApiService
import com.example.smartattendance.data.remote.RetrofitClient
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.domain.model.Course
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.repository.AttendanceRepository
import com.example.smartattendance.domain.repository.AttendanceModule
import com.example.smartattendance.domain.repository.AttendanceSession
import com.google.gson.JsonElement

class AttendanceRepositoryImpl(
    private val apiService: MoodleApiService = RetrofitClient.instance
) : AttendanceRepository {

    override suspend fun login(email: String, password: String): Result<User> {
        return try {
            val tokenResponse = apiService.getToken(username = email, password = password)
            val token = tokenResponse.token ?: return Result.failure(
                Exception(tokenResponse.error ?: "Error al obtener el token de Moodle")
            )
            SessionStore.moodleToken = token

            val jsonElement = apiService.getUserByField(
                token = token,
                field = if (email.contains("@")) "email" else "username",
                value = email
            )

            if (jsonElement.isJsonArray) {
                val array = jsonElement.asJsonArray
                if (array.size() == 0) return Result.failure(Exception("Usuario no encontrado en Moodle"))

                val userObj = array[0].asJsonObject
                val id = userObj.get("id").asInt
                val username = userObj.get("username").asString
                val fullname = userObj.get("fullname").asString
                val userEmail = userObj.get("email").asString
                
                val role = if (username.contains("profe") || username.contains("admin")) "teacher" else "student"

                SessionStore.currentUserId = id
                SessionStore.currentUserRole = role
                Result.success(User(id, username, fullname, userEmail, role))
            } else {
                Result.failure(Exception("Respuesta inesperada de Moodle"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUserCourses(): Result<List<Course>> {
        return try {
            val token = SessionStore.moodleToken ?: return Result.success(demoCourses())
            val userId = SessionStore.currentUserId ?: return Result.success(demoCourses())
            val response = apiService.getUserCourses(token = token, userId = userId)
            val courses = parseCourses(response)
            Result.success(courses.ifEmpty { demoCourses() })
        } catch (e: Exception) {
            Result.success(demoCourses())
        }
    }

    override suspend fun getEnrolledStudents(courseId: Int): Result<List<User>> {
        return try {
            val token = SessionStore.moodleToken ?: return Result.failure(Exception("No hay token"))
            val response = apiService.getEnrolledUsers(token = token, courseId = courseId)
            if (response.isJsonArray) {
                val students = response.asJsonArray.mapNotNull { item ->
                    val obj = item.asJsonObject
                    val id = obj.get("id").asInt
                    if (id == SessionStore.currentUserId) return@mapNotNull null
                    User(id, obj.get("username").asString, obj.get("fullname").asString, obj.get("email").asString, "student")
                }
                Result.success(students)
            } else Result.failure(Exception("Error al listar alumnos"))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getAttendanceModules(courseId: Int): Result<List<AttendanceModule>> {
        return try {
            val token = SessionStore.moodleToken ?: return Result.failure(Exception("No hay token"))
            // Usando argumentos nombrados para evitar errores con los valores por defecto de Retrofit/Kotlin
            val response = apiService.getCourseContents(
                token = token,
                courseId = courseId
            )
            val modules = mutableListOf<AttendanceModule>()
            if (response.isJsonArray) {
                response.asJsonArray.forEach { section ->
                    val sectionObj = section.asJsonObject
                    sectionObj.getAsJsonArray("modules")?.forEach { mod ->
                        val modObj = mod.asJsonObject
                        val modName = if (modObj.has("modname")) modObj.get("modname").asString else ""
                        if (modName == "attendance") {
                            modules.add(AttendanceModule(
                                modObj.get("instance").asInt,
                                modObj.get("name").asString
                            ))
                        }
                    }
                }
            }
            Result.success(modules)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getSessions(attendanceId: Int): Result<List<AttendanceSession>> {
        return try {
            val token = SessionStore.moodleToken ?: return Result.failure(Exception("No hay token"))
            val response = apiService.getAttendanceSessions(
                token = token,
                attendanceId = attendanceId
            )
            val sessionList = mutableListOf<AttendanceSession>()
            if (response.isJsonObject) {
                val sessionsArray = response.asJsonObject.getAsJsonArray("sessions")
                sessionsArray?.forEach { sess ->
                    val s = sess.asJsonObject
                    sessionList.add(AttendanceSession(
                        s.get("id").asInt,
                        s.get("sessdate").asLong,
                        if (s.has("description")) s.get("description").asString else "Sesión"
                    ))
                }
            }
            Result.success(sessionList.sortedByDescending { it.date })
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun markAttendance(sessionId: Int, userId: Int, statusId: String): Result<Boolean> {
        return try {
            val token = SessionStore.moodleToken ?: return Result.failure(Exception("No hay token"))
            val moodleStatus = when(statusId) { 
                "P" -> "1" 
                "L" -> "2" 
                "E" -> "3" 
                "A" -> "4" 
                else -> "1" 
            }
            apiService.updateAttendanceStatus(
                token = token,
                sessionId = sessionId,
                userId = userId,
                statusId = moodleStatus,
                teacherId = SessionStore.currentUserId ?: 0
            )
            Result.success(true)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun parseCourses(response: JsonElement): List<Course> {
        if (!response.isJsonArray) return emptyList()
        return response.asJsonArray.map {
            val obj = it.asJsonObject
            Course(obj.get("id").asInt, obj.get("fullname").asString, obj.get("shortname").asString)
        }
    }

    private fun demoCourses() = listOf(Course(2, "Programacion 1", "PROG1"))
}
