# Garmin Sync Companion (Android PoC)

Companion App สำหรับดึงข้อมูลการออกกำลังกายจาก **Garmin Connect** ผ่าน **Android Health Connect** และซิงก์เข้าสู่ **Personal Life Dashboard (Google Sheets)** โดยอัตโนมัติ

## คุณสมบัติเด่น
- อ่านข้อมูลกิจกรรม (ExerciseSessionRecord), Active Calories, ระยะทาง, และอัตราการเต้นของหัวใจจาก Health Connect
- รองรับการกรองเฉพาะกิจกรรมจาก Garmin Connect
- ป้องกันข้อมูลซ้ำด้วย Composite Key ระดับวินาที (`HC_GARMIN_<epoch>_<type>`)
- มีปุ่ม **Sync Now** สำหรับสั่งซิงก์ทันที และระบบ **Auto-Sync เบื้องหลังด้วย WorkManager**
- ปลอดภัยด้วย `X-Bridge-Token`

## การ Build เป็น APK ด้วย GitHub Actions (ฟรี 100%)
1. อัปโหลดโฟลเดอร์นี้ขึ้นสู่ GitHub Repository ใหม่ของคุณ (เช่น `garmin-sync-companion`)
2. GitHub Actions จะเริ่มทำงานอัตโนมัติทันที
3. เมื่อ Build เสร็จ (ใช้เวลา ~2-3 นาที):
   - ไปที่แท็บ **Actions** บน GitHub
   - กดเข้าไปใน Workflow ล่าสุด
   - เลื่อนลงมาที่หมวด **Artifacts** ด้านล่าง จะพบไฟล์ `GarminSyncCompanion-Debug-APK`
   - ดาวน์โหลดและติดตั้งลงในโทรศัพท์ Android 14 ของคุณได้ทันที!
