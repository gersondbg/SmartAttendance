package com.example.smartattendance.data.remote.response

import com.google.gson.annotations.SerializedName

data class MoodleTokenResponse(
    @SerializedName("token") val token: String?,
    @SerializedName("privatetoken") val privateToken: String?,
    @SerializedName("error") val error: String?
)
