package com.example.smartattendance.data.remote

import android.content.Context
import android.content.SharedPreferences

object SessionStore {
    private const val PREF_NAME = "SmartAttendancePrefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var moodleToken: String?
        get() = prefs.getString("moodleToken", null)
        set(value) = prefs.edit().putString("moodleToken", value).apply()

    var attendanceToken: String?
        get() = prefs.getString("attendanceToken", null)
        set(value) = prefs.edit().putString("attendanceToken", value).apply()

    var currentUserId: Int?
        get() = if (prefs.contains("currentUserId")) prefs.getInt("currentUserId", -1) else null
        set(value) = if (value != null) prefs.edit().putInt("currentUserId", value).apply() else prefs.edit().remove("currentUserId").apply()

    var currentUserRole: String?
        get() = prefs.getString("currentUserRole", null)
        set(value) = prefs.edit().putString("currentUserRole", value).apply()

    var activeCourseId: Int?
        get() = if (prefs.contains("activeCourseId")) prefs.getInt("activeCourseId", -1) else null
        set(value) = if (value != null) prefs.edit().putInt("activeCourseId", value).apply() else prefs.edit().remove("activeCourseId").apply()

    var activeCourseName: String?
        get() = prefs.getString("activeCourseName", null)
        set(value) = prefs.edit().putString("activeCourseName", value).apply()

    var activeAttendanceId: Int?
        get() = if (prefs.contains("activeAttendanceId")) prefs.getInt("activeAttendanceId", -1) else null
        set(value) = if (value != null) prefs.edit().putInt("activeAttendanceId", value).apply() else prefs.edit().remove("activeAttendanceId").apply()

    var activeAttendanceName: String?
        get() = prefs.getString("activeAttendanceName", null)
        set(value) = prefs.edit().putString("activeAttendanceName", value).apply()

    var activeSessionId: Int?
        get() = if (prefs.contains("activeSessionId")) prefs.getInt("activeSessionId", -1) else null
        set(value) = if (value != null) prefs.edit().putInt("activeSessionId", value).apply() else prefs.edit().remove("activeSessionId").apply()

    var currentUserName: String?
        get() = prefs.getString("currentUserName", null)
        set(value) = prefs.edit().putString("currentUserName", value).apply()

    var currentUserUsername: String?
        get() = prefs.getString("currentUserUsername", null)
        set(value) = prefs.edit().putString("currentUserUsername", value).apply()

    var activeClassRunning: Boolean
        get() = prefs.getBoolean("activeClassRunning", false)
        set(value) = prefs.edit().putBoolean("activeClassRunning", value).apply()

    var activeClassStartedAtMillis: Long
        get() = prefs.getLong("activeClassStartedAtMillis", 0L)
        set(value) = prefs.edit().putLong("activeClassStartedAtMillis", value).apply()

    var activeClassDurationSeconds: Int
        get() = prefs.getInt("activeClassDurationSeconds", 0)
        set(value) = prefs.edit().putInt("activeClassDurationSeconds", value).apply()

    var activeClassPaused: Boolean
        get() = prefs.getBoolean("activeClassPaused", false)
        set(value) = prefs.edit().putBoolean("activeClassPaused", value).apply()

    var activeClassPausedRemainingSeconds: Int
        get() = prefs.getInt("activeClassPausedRemainingSeconds", 0)
        set(value) = prefs.edit().putInt("activeClassPausedRemainingSeconds", value).apply()

    fun clearActiveClass() {
        prefs.edit()
            .remove("activeClassRunning")
            .remove("activeClassStartedAtMillis")
            .remove("activeClassDurationSeconds")
            .remove("activeClassPaused")
            .remove("activeClassPausedRemainingSeconds")
            .apply()
    }
    fun clear() {
        prefs.edit().clear().apply()
    }
}
