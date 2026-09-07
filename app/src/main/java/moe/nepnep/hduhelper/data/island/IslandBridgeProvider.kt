package moe.nepnep.hduhelper.data.island

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow

internal object IslandHostConnection {
    val binder = MutableStateFlow<IBinder?>(null)
    val revision = MutableStateFlow(0L)
    @Synchronized fun changed() { revision.value += 1 }
    fun register(host: IBinder) {
        host.linkToDeath({ if (binder.value === host) { binder.value = null; changed() } }, 0)
        binder.value = host
        changed()
    }
}

/** Accept only the platform-signed SystemUI UID, never an arbitrary app's host Binder. */
class IslandBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val pm = requireNotNull(context).packageManager
        val caller = Binder.getCallingUid()
        val systemUiUid = pm.getPackageUid(IslandWire.SYSTEM_UI, 0)
        check(caller == systemUiUid && pm.checkSignatures(caller, android.os.Process.SYSTEM_UID) == PackageManager.SIGNATURE_MATCH) {
            "Only platform SystemUI may register an island host"
        }
        when (method) {
            "register" -> {
                require(extras?.getInt("version") == IslandWire.VERSION)
                IslandHostConnection.register(requireNotNull(extras.getBinder("host")))
            }
            "changed" -> IslandHostConnection.changed()
            else -> throw IllegalArgumentException("Unsupported island bridge method")
        }
        return Bundle.EMPTY
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
