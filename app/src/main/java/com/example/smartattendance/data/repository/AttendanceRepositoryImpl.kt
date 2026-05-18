package com.example.smartattendance.data.repository

import com.example.smartattendance.data.remote.MoodleApiService
import com.example.smartattendance.data.remote.RetrofitClient
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.domain.model.Course
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.repository.AttendanceModule
import com.example.smartattendance.domain.repository.AttendanceRepository
import com.example.smartattendance.domain.repository.AttendanceSession
import com.google.gson.JsonElement

class AttendanceRepositoryImpl(
    private val apiService: MoodleApiService = RetrofitClient.instance
) : AttendanceRepository {

    private companion object {
        private const val MANUAL_ATTENDANCE_TOKEN = "fd6e1a6b6987e5f67ec7f60d2780097c"
    }

    override suspend fun login(email: String, password: String): Result<User> {
        if (email == "demo") {
            SessionStore.moodleToken = null
            SessionStore.attendanceToken = null
            SessionStore.currentUserId = 999
            SessionStore.currentUserRole = "teacher"
            return Result.success(User(999, "demo", "Profesor Demo", "demo@smart.com", "teacher"))
        }

        return try {
            val tokenResponse = apiService.getToken(username = email, password = password)
            val token = tokenResponse.token ?: return Result.failure(
                Exception(tokenResponse.error ?: "Error: verifica tus credenciales de Moodle Cloud")
            )
            SessionStore.moodleToken = token
            SessionStore.attendanceToken = requestAttendanceTokenIfAvailable(email, password) ?: MANUAL_ATTENDANCE_TOKEN

            val response = apiService.getUserByField(
                token = token,
                field = if (email.contains("@")) "email" else "username",
                value = email
            )

            if (!response.isJsonArray || response.asJsonArray.size() == 0) {
                return Result.failure(Exception("Usuario no encontrado en Moodle"))
            }

            val userObj = response.asJsonArray[0].asJsonObject
            val id = userObj.get("id").asInt
            val username = userObj.get("username").asString
            val fullname = userObj.get("fullname").asString
            val userEmail = userObj.get("email").asString
            val role = resolveRoleFromLogin(username, userEmail)

            SessionStore.currentUserId = id
            SessionStore.currentUserRole = role
            Result.success(User(id, username, fullname, userEmail, role))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUserCourses(): Result<List<Course>> {
        val token = SessionStore.moodleToken ?: return Result.success(demoCourses())
        val userId = SessionStore.currentUserId ?: return Result.failure(Exception("ID de usuario no encontrado"))

        return try {
            Result.success(parseCourses(apiService.getUserCourses(token = token, userId = userId)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getEnrolledStudents(courseId: Int): Result<List<User>> {
        val token = SessionStore.moodleToken ?: return Result.success(demoStudents())

        return try {
            val response = apiService.getEnrolledUsers(token = token, courseId = courseId)
            if (!response.isJsonArray) {
                return Result.failure(Exception(extractMoodleError(response) ?: "Respuesta inesperada del servidor"))
            }

            val students = response.asJsonArray.mapNotNull { item ->
                try {
                    val obj = item.asJsonObject
                    val id = obj.get("id").asInt
                    if (id == SessionStore.currentUserId) return@mapNotNull null

                    val roles = obj.getAsJsonArray("roles")
                    val roleShortNames = roles?.mapNotNull { role ->
                        role.asJsonObject.get("shortname")?.asString
                    }.orEmpty()
                    if (roleShortNames.isNotEmpty() && roleShortNames.none { it == "student" }) {
                        return@mapNotNull null
                    }

                    val email = if (obj.has("email") && !obj.get("email").isJsonNull) obj.get("email").asString else ""
                    val rawUsername = if (obj.has("username") && !obj.get("username").isJsonNull) obj.get("username").asString else ""
                    val username = rawUsername.ifBlank { email.substringBefore("@", "user_$id") }
                    val fullname = if (obj.has("fullname")) obj.get("fullname").asString else "Estudiante $id"

                    User(id = id, username = username, fullname = fullname, email = email, role = "student")
                } catch (_: Exception) {
                    null
                }
            }
            Result.success(students)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAttendanceModules(courseId: Int): Result<List<AttendanceModule>> {
        val token = SessionStore.moodleToken ?: return Result.success(demoModules())

        return try {
            val response = apiService.getCourseContents(token = token, courseId = courseId)
            val modules = mutableListOf<AttendanceModule>()
            if (response.isJsonArray) {
                response.asJsonArray.forEach { section ->
                    section.asJsonObject.getAsJsonArray("modules")?.forEach { module ->
                        val obj = module.asJsonObject
                        if (obj.get("modname").asString == "attendance") {
                            modules.add(
                                AttendanceModule(
                                    id = obj.get("id").asInt,
                                    instanceId = obj.get("instance").asInt,
                                    name = obj.get("name").asString
                                )
                            )
                        }
                    }
                }
            }
            Result.success(modules)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSessions(attendanceId: Int): Result<List<AttendanceSession>> {
        val token = SessionStore.attendanceToken ?: SessionStore.moodleToken ?: return Result.success(demoSessions())

        return try {
            val response = apiService.getAttendanceSessions(token = token, attendanceId = attendanceId)
            val sessions = mutableListOf<AttendanceSession>()

            if (response.isJsonObject) {
                val obj = response.asJsonObject
                if (obj.has("exception")) return Result.failure(Exception(obj.get("message").asString))

                if (obj.has("sessions")) {
                    obj.getAsJsonArray("sessions").forEach { sessions.add(parseSession(it.asJsonObject)) }
                }
                if (obj.has("value")) {
                    obj.getAsJsonArray("value").forEach { sessions.add(parseSession(it.asJsonObject)) }
                }
            } else if (response.isJsonArray) {
                response.asJsonArray.forEach { sessions.add(parseSession(it.asJsonObject)) }
            }

            Result.success(sessions.sortedByDescending { it.date })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSession(attendanceId: Int): Result<AttendanceSession> {
        val token = SessionStore.attendanceToken ?: SessionStore.moodleToken
            ?: return Result.success(AttendanceSession(999, System.currentTimeMillis() / 1000, "Sesion Demo Nueva"))

        return try {
            val now = System.currentTimeMillis() / 1000
            val response = apiService.addAttendanceSession(
                token = token,
                attendanceId = attendanceId,
                sessDate = now,
                description = "Clase SmartAttendance"
            )

            if (response.isJsonObject && response.asJsonObject.has("exception")) {
                return Result.failure(Exception(response.asJsonObject.get("message").asString))
            }

            val newId = if (response.isJsonObject && response.asJsonObject.has("sessionid")) {
                response.asJsonObject.get("sessionid").asInt
            } else {
                return Result.failure(Exception("No se pudo confirmar la creacion de la sesion"))
            }
            Result.success(AttendanceSession(newId, now, "Nueva sesion"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAttendance(sessionId: Int, userId: Int, statusId: String): Result<Boolean> {
        val token = SessionStore.attendanceToken ?: SessionStore.moodleToken ?: return Result.success(true)

        return try {
            val moodleStatusId = resolveAttendanceStatusId(token, sessionId, statusId)
            val response = apiService.updateAttendanceStatus(
                token = token,
                sessionId = sessionId,
                studentId = userId,
                teacherId = SessionStore.currentUserId ?: 0,
                statusId = moodleStatusId,
                statusSet = 0
            )
            if (response.isJsonObject && response.asJsonObject.has("exception")) {
                return Result.failure(Exception(response.asJsonObject.get("message").asString))
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseCourses(response: JsonElement): List<Course> {
        if (!response.isJsonArray) return emptyList()
        return response.asJsonArray.map {
            val obj = it.asJsonObject
            Course(obj.get("id").asInt, obj.get("fullname").asString, obj.get("shortname").asString)
        }
    }

    private fun parseSession(obj: com.google.gson.JsonObject): AttendanceSession {
        return AttendanceSession(
            id = obj.get("id").asInt,
            date = obj.get("sessdate").asLong,
            description = if (obj.has("description") && !obj.get("description").isJsonNull) {
                obj.get("description").asString.ifBlank { "Sesion" }
            } else {
                "Sesion"
            }
        )
    }

    private fun resolveRoleFromLogin(username: String, email: String): String {
        val normalized = "${username.lowercase()} ${email.lowercase()}"
        return when {
            normalized.contains("alumno") || normalized.contains("student") -> "student"
            normalized.contains("profesor") || normalized.contains("profe") || normalized.contains("teacher") -> "teacher"
            else -> "student"
        }
    }

    private suspend fun requestAttendanceTokenIfAvailable(username: String, password: String): String? {
        val serviceCandidates = listOf("mod_attendance", "smart_attendance", "smartattendance")
        for (service in serviceCandidates) {
            try {
                val response = apiService.getToken(username = username, password = password, service = service)
                if (!response.token.isNullOrBlank()) return response.token
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun resolveAttendanceStatusId(token: String, sessionId: Int, acronym: String): Int {
        val fallback = when (acronym) {
            "P" -> 5
            "A" -> 6
            "L" -> 7
            "E" -> 8
            else -> 5
        }

        return try {
            val response = apiService.getAttendanceSession(token = token, sessionId = sessionId)
            if (!response.isJsonObject || !response.asJsonObject.has("statuses")) return fallback
            response.asJsonObject.getAsJsonArray("statuses")
                .firstOrNull { item ->
                    item.asJsonObject.get("acronym")?.asString.equals(acronym, ignoreCase = true)
                }
                ?.asJsonObject
                ?.get("id")
                ?.asInt
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun extractMoodleError(response: JsonElement): String? {
        return if (response.isJsonObject && response.asJsonObject.has("message")) {
            response.asJsonObject.get("message").asString
        } else {
            null
        }
    }

    private fun demoCourses() = listOf(Course(1, "Curso de Prueba (Local)", "DEMO101"))
    private fun demoModules() = listOf(AttendanceModule(1, 1, "Asistencia Demo"))
    private fun demoSessions() = listOf(AttendanceSession(1, System.currentTimeMillis() / 1000, "Sesion de Prueba"))
    private fun demoStudents() = listOf(User(1, "messi", "Lionel Messi", "leo@smart.com", "student"))
}
