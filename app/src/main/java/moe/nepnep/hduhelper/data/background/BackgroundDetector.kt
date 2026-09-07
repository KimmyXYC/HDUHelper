package moe.nepnep.hduhelper.data.background

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.net.toUri

object BackgroundDetector {
    val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    @android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi") // Read-only MIUI operation; denied hidden API access stays UNKNOWN.
    fun read(context: Context, uid: Int = context.applicationInfo.uid): BackgroundStatus {
        val battery = runCatching {
            if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(BackgroundWire.APP)) PermissionState.ALLOWED else PermissionState.RESTRICTED
        }.getOrDefault(PermissionState.UNKNOWN)
        if (!isXiaomi) return BackgroundStatus(PermissionState.UNSUPPORTED, battery, PermissionState.UNSUPPORTED)
        val autostart = runCatching {
            val method = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            autostartState(method.invoke(context.getSystemService(AppOpsManager::class.java), 10008, uid, BackgroundWire.APP) as? Int)
        }.getOrDefault(PermissionState.UNKNOWN)
        val vendor = runCatching {
            // PowerKeeper's getPowerSaveAppConfigure call can WRITE settings. Query its table instead.
            context.contentResolver.query("content://com.miui.powerkeeper.configure/userTable".toUri(), arrayOf("bgControl"),
                "userId = ? AND pkgName = ?", arrayOf((uid / 100000).toString(), BackgroundWire.APP), null)?.use { cursor ->
                if (cursor.moveToFirst()) vendorBatteryState(cursor.getString(0)) else PermissionState.UNKNOWN
            } ?: PermissionState.UNKNOWN
        }.getOrDefault(PermissionState.UNKNOWN)
        return BackgroundStatus(autostart, battery, vendor)
    }
}
