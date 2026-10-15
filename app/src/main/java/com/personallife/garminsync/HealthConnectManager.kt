package com.personallife.garminsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class HealthConnectManager(private val context: Context) {

    private val client by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(requiredPermissions)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readRecentGarminWorkouts(
        startTime: Instant,
        endTime: Instant = Instant.now()
    ): List<WorkoutItem> {
        val workouts = mutableListOf<WorkoutItem>()
        val zoneBangkok = ZoneId.of("Asia/Bangkok")
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(zoneBangkok)
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(zoneBangkok)

        try {
            // 1. อ่าน ExerciseSessionRecord ทั้งหมดในช่วงเวลา
            val sessionResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )

            for (session in sessionResponse.records) {
                // กรองเฉพาะรายการที่มาจาก Garmin Connect (หรือยอมรับทั้งหมดในโหมด PoC)
                val packageName = session.metadata.dataOrigin.packageName
                val isGarmin = packageName.contains("garmin", ignoreCase = true)

                val sessionStart = session.startTime
                val sessionEnd = session.endTime
                val durationMillis = Duration.between(sessionStart, sessionEnd).toMillis()
                val durationMinutes = Math.round((durationMillis / 60000.0) * 10.0) / 10.0

                if (durationMinutes <= 0.0) continue

                // 2. Aggregate Active Calories (แคลอรี่เผาผลาญจากการออกกำลังกาย ห้ามใช้ Total BMR)
                var activeCalories = 0.0
                try {
                    val calAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    activeCalories = calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                } catch (e: Exception) {
                    // หากอ่านไม่สำเร็จ ให้เป็น 0.0
                }

                // 3. Aggregate Distance
                var distanceKm: Double? = null
                try {
                    val distAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    val meters = distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                    if (meters != null && meters > 0) {
                        distanceKm = Math.round((meters / 1000.0) * 100.0) / 100.0
                    }
                } catch (e: Exception) {
                    // Ignored
                }

                // 4. อ่านค่าเฉลี่ย HeartRate ตัวอย่าง
                var avgHeartRate: Int? = null
                try {
                    val hrResponse = client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    val samples = hrResponse.records.flatMap { it.samples }
                    if (samples.isNotEmpty()) {
                        avgHeartRate = samples.map { it.beatsPerMinute }.average().toInt()
                    }
                } catch (e: Exception) {
                    // Ignored
                }

                // 5. คำนวณ Pace
                var avgPace: String? = null
                if (distanceKm != null && distanceKm > 0.05 && durationMinutes > 0) {
                    val totalPaceSeconds = ((durationMinutes * 60) / distanceKm).toInt()
                    val paceMin = totalPaceSeconds / 60
                    val paceSec = totalPaceSeconds % 60
                    avgPace = String.format(Locale.US, "%d:%02d /km", paceMin, paceSec)
                }

                val dateStr = dateFormatter.format(sessionStart)
                val timeStr = timeFormatter.format(sessionStart)
                val typeThai = mapExerciseTypeToThai(session.exerciseType)
                val title = session.title ?: if (isGarmin) "Garmin Workout" else "Workout"
                val rawType = getExerciseTypeName(session.exerciseType)

                // Composite Key ป้องกันซ้ำ: ใช้เวลา epoch วินาที + ชนิดกีฬา
                val compositeKey = "HC_GARMIN_${sessionStart.epochSecond}_${rawType}"
                val externalId = session.metadata.id

                val note = buildString {
                    append(title)
                    if (!avgPace.isNullOrEmpty()) append(" | Pace: $avgPace")
                    append(" | Source: $packageName")
                }

                workouts.add(
                    WorkoutItem(
                        externalId = externalId,
                        compositeKey = compositeKey,
                        date = dateStr,
                        time = timeStr,
                        activityType = typeThai,
                        rawExerciseType = rawType,
                        title = title,
                        durationMinutes = durationMinutes,
                        caloriesBurned = Math.round(activeCalories).toDouble(),
                        distanceKm = distanceKm,
                        avgHeartRate = avgHeartRate,
                        avgPace = avgPace,
                        note = note
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return workouts
    }

    private fun mapExerciseTypeToThai(exerciseType: Int): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "วิ่ง"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "เดิน"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "ปั่นจักรยาน"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "ว่ายน้ำ"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "เวทเทรนนิ่ง"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "โยคะ"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "แบดมินตัน"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "ฟุตบอล"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "คาร์ดิโอ"
            else -> "ออกกำลังกาย"
        }
    }

    private fun getExerciseTypeName(exerciseType: Int): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
            else -> "EXERCISE_$exerciseType"
        }
    }
}
