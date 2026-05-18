package com.example.smartattendance.data.remote

object SessionStore {
    var moodleToken: String? = null
    var attendanceToken: String? = null
    var currentUserId: Int? = null
    var currentUserRole: String? = null
    var activeCourseId: Int? = null
    var activeCourseName: String? = null
    var activeAttendanceId: Int? = null
    var activeAttendanceName: String? = null
    var activeSessionId: Int? = null

    fun clear() {
        moodleToken = null
        attendanceToken = null
        currentUserId = null
        currentUserRole = null
        activeCourseId = null
        activeCourseName = null
        activeAttendanceId = null
        activeAttendanceName = null
        activeSessionId = null
    }
}
