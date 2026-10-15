package com.personallife.garminsync

import com.google.gson.annotations.SerializedName

data class WorkoutSyncPayload(
    @SerializedName("clientVersion") val clientVersion: String = "1.0.0",
    @SerializedName("source") val source: String = "GARMIN_HEALTH_CONNECT",
    @SerializedName("activities") val activities: List<WorkoutItem>
)

data class WorkoutItem(
    @SerializedName("externalId") val externalId: String,
    @SerializedName("compositeKey") val compositeKey: String,
    @SerializedName("date") val date: String,             // YYYY-MM-DD
    @SerializedName("time") val time: String,             // HH:mm:ss
    @SerializedName("activityType") val activityType: String, // 'วิ่ง', 'เดิน', 'ปั่นจักรยาน'
    @SerializedName("rawExerciseType") val rawExerciseType: String,
    @SerializedName("title") val title: String,
    @SerializedName("durationMinutes") val durationMinutes: Double,
    @SerializedName("caloriesBurned") val caloriesBurned: Double, // MUST BE ACTIVE CALORIES
    @SerializedName("distanceKm") val distanceKm: Double?,
    @SerializedName("avgHeartRate") val avgHeartRate: Int?,
    @SerializedName("avgPace") val avgPace: String?,
    @SerializedName("note") val note: String
)

data class BridgeResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("inserted") val inserted: Int?,
    @SerializedName("skipped") val skipped: Int?,
    @SerializedName("error") val error: String?
)
