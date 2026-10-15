package com.personallife.garminsync

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.GsonBuilder
import com.personallife.garminsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hcManager: HealthConnectManager
    private val networkManager = NetworkManager()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val requestPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        lifecycleScope.launch {
            updatePermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hcManager = HealthConnectManager(this)

        loadPreferences()
        setupListeners()

        lifecycleScope.launch {
            updatePermissionStatus()
            updateLastSyncDisplay()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
        binding.etGasUrl.setText(prefs.getString("gas_url", ""))
        binding.etBridgeSecret.setText(prefs.getString("bridge_secret", ""))
        binding.switchAutoSync.isChecked = prefs.getBoolean("auto_sync_enabled", false)
    }

    private fun setupListeners() {
        binding.btnGrantPermissions.setOnClickListener {
            lifecycleScope.launch {
                if (hcManager.hasAllPermissions()) {
                    Toast.makeText(this@MainActivity, "ได้รับสิทธิ์ครบถ้วนแล้ว", Toast.LENGTH_SHORT).show()
                } else {
                    requestPermissionLauncher.launch(hcManager.requiredPermissions)
                }
            }
        }

        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etGasUrl.text?.toString()?.trim() ?: ""
            val secret = binding.etBridgeSecret.text?.toString()?.trim() ?: ""

            getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("gas_url", url)
                .putString("bridge_secret", secret)
                .apply()

            Toast.makeText(this, "💾 บันทึกการตั้งค่าเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            appendLog("✅ บันทึก URL และ Secret Token สำเร็จ")
        }

        binding.btnSyncNow.setOnClickListener {
            performManualSync()
        }

        binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_sync_enabled", isChecked)
                .apply()

            if (isChecked) {
                scheduleBackgroundSync()
                appendLog("🕒 เปิดการซิงก์อัตโนมัติในพื้นหลัง (ทุก 30 นาที)")
            } else {
                cancelBackgroundSync()
                appendLog("⏹️ ปิดการซิงก์อัตโนมัติในพื้นหลัง")
            }
        }
    }

    private suspend fun updatePermissionStatus() {
        val hasPerms = hcManager.hasAllPermissions()
        if (hasPerms) {
            binding.tvPermissionStatus.text = "สถานะ: ✅ ได้รับสิทธิ์อ่าน Health Connect เรียบร้อย"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.success))
            binding.btnGrantPermissions.isEnabled = false
            binding.btnGrantPermissions.text = "ได้รับสิทธิ์แล้ว"
        } else {
            binding.tvPermissionStatus.text = "สถานะ: ⚠️ ยังไม่ได้รับสิทธิ์อ่านกิจกรรม"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.danger))
            binding.btnGrantPermissions.isEnabled = true
            binding.btnGrantPermissions.text = getString(R.string.btn_check_permissions)
        }
    }

    private fun performManualSync() {
        val url = binding.etGasUrl.text?.toString()?.trim() ?: ""
        val secret = binding.etBridgeSecret.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            Toast.makeText(this, "กรุณากรอก GAS Web App URL ก่อนซิงก์", Toast.LENGTH_LONG).show()
            appendLog("❌ ผิดพลาด: ยังไม่ได้ระบุ GAS Web App URL")
            return
        }

        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "⏳ กำลังดึงข้อมูลจาก Health Connect..."
        appendLog("🚀 เริ่มการซิงก์กิจกรรม (Manual Sync)...")

        lifecycleScope.launch {
            try {
                if (!hcManager.hasAllPermissions()) {
                    appendLog("⚠️ ยังไม่ได้รับสิทธิ์ Health Connect กรุณากดปุ่มขอสิทธิ์ก่อน")
                    binding.btnSyncNow.isEnabled = true
                    binding.btnSyncNow.text = getString(R.string.btn_sync_now)
                    return@launch
                }

                // ดึงกิจกรรมย้อนหลัง 48 ชั่วโมง
                val startTime = Instant.now().minus(48, ChronoUnit.HOURS)
                appendLog("🔍 กำลังค้นหากิจกรรมย้อนหลัง 48 ชม. ...")
                val workouts = hcManager.readRecentGarminWorkouts(startTime)

                if (workouts.isEmpty()) {
                    appendLog("ℹ️ ไม่พบกิจกรรมใหม่จาก Garmin Connect ใน Health Connect")
                    appendLog("💡 คำแนะนำ: ลองออกกำลังกายด้วย Garmin แล้วเปิดแอป Garmin Connect รอซิงก์สักครู่")
                    binding.btnSyncNow.isEnabled = true
                    binding.btnSyncNow.text = getString(R.string.btn_sync_now)
                    return@launch
                }

                appendLog("📦 พบกิจกรรม ${workouts.size} รายการ:")
                workouts.forEachIndexed { idx, it ->
                    appendLog("  [${idx + 1}] ${it.date} ${it.time} | ${it.activityType} (${it.durationMinutes} นาที) | ${it.caloriesBurned} kcal | ${it.distanceKm ?: 0.0} km")
                }

                appendLog("📤 กำลังส่งข้อมูลไปยัง GAS Web App...")
                val payload = WorkoutSyncPayload(activities = workouts)
                val result = networkManager.syncWorkouts(url, secret, payload)

                result.onSuccess { res ->
                    if (res.success) {
                        appendLog("✅ เซิร์ฟเวอร์ตอบกลับ: ${res.message ?: "บันทึกสำเร็จ"}")
                        appendLog("   (บันทึกใหม่: ${res.inserted ?: 0}, ข้ามรายการซ้ำ: ${res.skipped ?: 0})")
                        Toast.makeText(this@MainActivity, "ซิงก์สำเร็จ! บันทึกแล้ว ${res.inserted ?: 0} รายการ", Toast.LENGTH_SHORT).show()

                        getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putLong("last_sync_time", System.currentTimeMillis())
                            .apply()
                        updateLastSyncDisplay()
                    } else {
                        appendLog("❌ เซิร์ฟเวอร์ปฏิเสธ: ${res.error ?: "ไม่ทราบสาเหตุ"}")
                    }
                }.onFailure { err ->
                    appendLog("❌ การเชื่อมต่อล้มเหลว: ${err.message}")
                    Toast.makeText(this@MainActivity, "เชื่อมต่อล้มเหลว: ${err.message}", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                appendLog("❌ เกิดข้อผิดพลาดไม่คาดคิด: ${e.message}")
            } finally {
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = getString(R.string.btn_sync_now)
            }
        }
    }

    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "GarminAutoSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun cancelBackgroundSync() {
        WorkManager.getInstance(this).cancelUniqueWork("GarminAutoSync")
    }

    private fun updateLastSyncDisplay() {
        val lastSync = getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)

        if (lastSync > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            binding.tvLastSync.text = "ซิงก์ล่าสุด: ${sdf.format(Date(lastSync))}"
        } else {
            binding.tvLastSync.text = "ซิงก์ล่าสุด: ยังไม่เคยซิงก์"
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = binding.tvLogOutput.text.toString()
        binding.tvLogOutput.text = "[$time] $msg\n$current"
    }
}
