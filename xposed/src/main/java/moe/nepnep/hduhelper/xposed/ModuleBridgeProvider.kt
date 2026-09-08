package moe.nepnep.hduhelper.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import moe.nepnep.hduhelper.data.background.BackgroundWire
import moe.nepnep.hduhelper.data.background.ModuleWire

/** Only the same-signed main app can read framework state or change module preferences. */
class ModuleBridgeProvider : ContentProvider() {
    @Volatile private var service: XposedService? = null

    override fun onCreate(): Boolean {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                this@ModuleBridgeProvider.service = service
                changed()
            }
            override fun onServiceDied(service: XposedService) {
                if (this@ModuleBridgeProvider.service === service) this@ModuleBridgeProvider.service = null
                changed()
            }
        })
        return true
    }

    private fun changed() { context?.contentResolver?.notifyChange(ModuleWire.URI, null) }

    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref") // Return the actual remote persistence result.
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        check(Binder.getCallingUid() == context.packageManager.getPackageUid(BackgroundWire.APP, 0))
        // ContentProvider.call does not enforce the manifest read/write permission on all paths.
        context.enforceCallingPermission(ModuleWire.PERMISSION, "Only the signed HDUHelper app may configure hooks")
        val identity = Binder.clearCallingIdentity()
        try {
            val current = service
            val result = Bundle().apply { putInt("protocol", ModuleWire.VERSION) }
            when (method) {
                "status" -> {
                    result.putString("modulePath", context.applicationInfo.sourceDir)
                    result.putBoolean("active", current != null)
                    if (current != null) {
                        result.putInt("api", current.apiVersion)
                        result.putStringArrayList("scope", ArrayList(current.scope))
                        result.putBoolean("remote", current.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L)
                    }
                }
                "background" -> {
                    require(extras?.containsKey("enabled") == true)
                    val supported = current != null && current.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L
                    result.putBoolean("success", supported && runCatching {
                        val prefs = current!!.getRemotePreferences(BackgroundWire.GROUP)
                        val desired = extras.getBoolean("enabled")
                        if (prefs.getBoolean(BackgroundWire.ENABLED, false) == desired && !extras.getBoolean("force")) true
                        else prefs.edit().putBoolean(BackgroundWire.ENABLED, desired).commit()
                    }.getOrDefault(false))
                }
                else -> throw IllegalArgumentException("Unknown module bridge operation")
            }
            return result
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
