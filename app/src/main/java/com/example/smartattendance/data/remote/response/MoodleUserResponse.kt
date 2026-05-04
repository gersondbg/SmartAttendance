package com.example.smartattendance.data.remote.response

import com.google.gson.annotations.SerializedName

data class MoodleUserResponse(
    val id: Int,
    val username: String,
    val fullname: String,
    val email: String,
    @SerializedName("profileimageurlsmall") val profileImage: String? = null
)
