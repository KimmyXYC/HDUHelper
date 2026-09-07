package moe.nepnep.hduhelper.data.background

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow

internal object BackgroundHostConnection {
    val binder = MutableStateFlow<IBinder?>(null)
    val revision = MutableStateFlow(0L)
    @Synchronized private fun changed() { revision.value += 1 }
    fun register(host: IBinder) {
        if (binder.value !== host) {
            host.linkToDeath({ if (binder.value === host) { binder.value = null; changed() } }, 0)
            binder.value = host
        }
        changed()
    }
}

/** A system_server-only registration channel. No arbitrary package/query arguments. */
class BackgroundBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.SYSTEM_UID) { "Only system server may register a background host" }
        require(method == "register" && extras?.getInt("version") == BackgroundWire.VERSION)
        BackgroundHostConnection.register(requireNotNull(extras.getBinder("host")))
        return Bundle.EMPTY
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
