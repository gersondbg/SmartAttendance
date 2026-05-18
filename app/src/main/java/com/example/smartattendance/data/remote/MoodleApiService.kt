package com.example.smartattendance.data.remote

import com.example.smartattendance.data.remote.response.MoodleTokenResponse
import com.google.gson.JsonElement
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Query

interface MoodleApiService {

    // 1. Obtener Token real de Moodle con usuario y contraseña
    @GET("login/token.php")
    suspend fun getToken(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("service") service: String = "moodle_mobile_app"
    ): MoodleTokenResponse

    // 2. Buscar datos del usuario de forma segura
    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getUserByField(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "core_user_get_users_by_field",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("field") field: String, 
        @Field("values[0]") value: String
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getEnrolledUsers(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "core_enrol_get_enrolled_users",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("courseid") courseId: Int
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getUserCourses(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "core_enrol_get_users_courses",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("userid") userId: Int
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getCourseContents(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "core_course_get_contents",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("courseid") courseId: Int
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getAttendanceSessions(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "mod_attendance_get_sessions",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("attendanceid") attendanceId: Int
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun addAttendanceSession(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "mod_attendance_add_session",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("attendanceid") attendanceId: Int,
        @Field("sessdate") sessDate: Long,
        @Field("duration") duration: Int = 3600,
        @Field("description") description: String = "Sesión creada desde App"
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun updateAttendanceStatus(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "mod_attendance_update_user_status",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("sessionid") sessionId: Int,
        @Field("studentid") studentId: Int,
        @Field("takenbyid") teacherId: Int,
        @Field("statusid") statusId: Int,
        @Field("statusset") statusSet: Int = 0
    ): JsonElement

    @POST("webservice/rest/server.php")
    @FormUrlEncoded
    suspend fun getAttendanceSession(
        @Field("wstoken") token: String,
        @Field("wsfunction") function: String = "mod_attendance_get_session",
        @Field("moodlewsrestformat") format: String = "json",
        @Field("sessionid") sessionId: Int
    ): JsonElement
}
