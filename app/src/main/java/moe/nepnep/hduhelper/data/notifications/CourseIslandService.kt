package moe.nepnep.hduhelper.data.notifications

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import moe.nepnep.hduhelper.HDUHelperApplication

/** Exists only while an explicitly enabled course countdown is visible. */
class CourseIslandService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.getParcelableExtra("notification", Notification::class.java)
        val until = intent?.getLongExtra("until", 0) ?: 0
        if (notification == null || until <= System.currentTimeMillis()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(intent.getIntExtra("id", 0), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(intent.getIntExtra("id", 0), notification)
            running = true
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hduhelper:course-countdown").apply {
                setReferenceCounted(false)
                acquire((until - System.currentTimeMillis()).coerceIn(1, 31 * 60_000L))
            }
        } catch (_: SecurityException) { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        (application as HDUHelperApplication).container.courseReminders.serviceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { @Volatile var running = false; private set }
}
