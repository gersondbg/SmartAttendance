package com.example.smartattendance.data.remote

object SessionStore {
    var moodleToken: String? = null
    var currentUserId: Int? = null
    var currentUserRole: String? = null
    var activeCourseId: Int? = null
    var activeCourseName: String? = null

    fun clear() {
        moodleToken = null
        currentUserId = null
        currentUserRole = null
        activeCourseId = null
        activeCourseName = null
    }
}
