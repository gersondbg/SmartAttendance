package com.example.smartattendance.data.repository

import com.example.smartattendance.data.remote.MoodleApiService
import com.example.smartattendance.data.remote.RetrofitClient
import com.example.smartattendance.data.remote.SessionStore
import com.example.smartattendance.domain.model.Course
import com.example.smartattendance.domain.model.User
import com.example.smartattendance.domain.repository.AttendanceRepository

class AttendanceRepositoryImpl(
    private val apiService: MoodleApiService = RetrofitClient.instance
) : AttendanceRepository {

    private companion object {
        const val DEMO_COURSE_ID = 2
    }

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
                if (array.size() == 0) {
                    return Result.failure(Exception("Usuario no encontrado en Moodle"))
                }

                val userObj = array[0].asJsonObject
                val username = userObj.get("username").asString
                val fullname = userObj.get("fullname").asString
                val userEmail = userObj.get("email").asString
                val id = userObj.get("id").asInt
                val role = resolveRoleFromMoodle(token, id, username)

                SessionStore.currentUserId = id
                SessionStore.currentUserRole = role
                loadAndStoreActiveCourse(token, id)
                Result.success(User(id, username, fullname, userEmail, role))
            } else if (jsonElement.isJsonObject) {
                val errorObj = jsonElement.asJsonObject
                val errorMsg = errorObj.get("message")?.asString ?: "Error en el servicio de Moodle"
                Result.failure(Exception(errorMsg))
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
            val token = SessionStore.moodleToken ?: return Result.failure(Exception("No hay token de sesion"))
            val response = apiService.getEnrolledUsers(token = token, courseId = courseId)

            if (response.isJsonArray) {
                val students = response.asJsonArray.mapNotNull { item ->
                    val userObj = item.asJsonObject
                    val id = userObj.get("id")?.asInt ?: return@mapNotNull null
                    val email = userObj.get("email")?.asString.orEmpty()
                    val usernameFromMoodle = userObj.get("username")?.asString.orEmpty()
                    val username = usernameFromMoodle.ifBlank { email.substringBefore("@") }
                    val fullname = userObj.get("fullname")?.asString ?: username
                    
                    // Solo excluimos al profesor actual para que no se cuente a si mismo como alumno
                    if (id == SessionStore.currentUserId) return@mapNotNull null
                    
                    // Si el nombre contiene profesor o admin, tambien lo saltamos de la lista de alumnos
                    if (username.contains("profe", ignoreCase = true) || username.contains("admin", ignoreCase = true)) {
                        return@mapNotNull null
                    }

                    User(id, username, fullname, email, "student")
                }
                Result.success(students)
            } else if (response.isJsonObject) {
                val err = response.asJsonObject
                val msg = err.get("message")?.asString
                    ?: err.get("error")?.asString
                    ?: err.get("exception")?.asString
                    ?: "Respuesta Moodle no valida al listar matriculados"
                Result.failure(Exception(msg))
            } else {
                Result.failure(Exception("Respuesta inesperada de Moodle al listar estudiantes"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun loadAndStoreActiveCourse(token: String, userId: Int) {
        try {
            val courses = parseCourses(apiService.getUserCourses(token = token, userId = userId))
            val selectedCourse = courses.firstOrNull()
            SessionStore.activeCourseId = selectedCourse?.id
            SessionStore.activeCourseName = selectedCourse?.fullname
        } catch (e: Exception) {
            SessionStore.activeCourseId = null
            SessionStore.activeCourseName = null
        }
    }

    private fun parseCourses(response: com.google.gson.JsonElement): List<Course> {
        if (!response.isJsonArray) return emptyList()
        return response.asJsonArray.mapNotNull { item ->
            val courseObj = item.asJsonObject
            val id = courseObj.get("id")?.asInt ?: return@mapNotNull null
            val fullname = courseObj.get("fullname")?.asString ?: "Curso $id"
            val shortname = courseObj.get("shortname")?.asString ?: fullname
            Course(id, fullname, shortname)
        }
    }

    private suspend fun resolveRoleFromMoodle(token: String, userId: Int, username: String): String {
        return try {
            val response = apiService.getEnrolledUsers(token = token, courseId = DEMO_COURSE_ID)
            if (response.isJsonArray) {
                val matchedUser = response.asJsonArray
                    .map { it.asJsonObject }
                    .firstOrNull { it.get("id")?.asInt == userId }

                if (matchedUser != null) {
                    resolveRoleFromUserObject(matchedUser, username)
                } else {
                    resolveRoleFromUsername(username)
                }
            } else {
                resolveRoleFromUsername(username)
            }
        } catch (e: Exception) {
            resolveRoleFromUsername(username)
        }
    }

    private fun resolveRoleFromUserObject(userObj: com.google.gson.JsonObject, username: String): String {
        val roles = userObj.getAsJsonArray("roles")
        if (roles != null) {
            roles.forEach { roleElement ->
                val roleObj = roleElement.asJsonObject
                val shortName = roleObj.get("shortname")?.asString.orEmpty()
                val roleName = roleObj.get("name")?.asString.orEmpty()
                if (shortName.contains("teacher", ignoreCase = true) ||
                    roleName.contains("teacher", ignoreCase = true) ||
                    roleName.contains("profesor", ignoreCase = true)
                ) {
                    return "teacher"
                }
                if (shortName.contains("student", ignoreCase = true) ||
                    shortName.contains("estudiante", ignoreCase = true) ||
                    roleName.contains("student", ignoreCase = true) ||
                    roleName.contains("estudiante", ignoreCase = true)
                ) {
                    return "student"
                }
            }
        }
        return resolveRoleFromUsername(username)
    }

    private fun resolveRoleFromUsername(username: String): String {
        return when {
            username.contains("profesor", ignoreCase = true) -> "teacher"
            username.contains("profe", ignoreCase = true) -> "teacher"
            username.contains("admin", ignoreCase = true) -> "teacher"
            else -> "student"
        }
    }

    private fun demoStudents(): List<User> = listOf(
        User(101, "alumno1", "Alumno 1", "alumno1@uni.edu.pe", "student"),
        User(102, "alumno2", "Alumno 2", "alumno2@uni.edu.pe", "student")
    )

    private fun demoCourses(): List<Course> = listOf(
        Course(DEMO_COURSE_ID, "Programacion 1", "PROG1")
    )
}
