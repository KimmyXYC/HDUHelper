package moe.nepnep.hduhelper.data.island

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/** Fixed Binder protocol. The host verifies the caller UID for every transaction. */
object IslandWire {
    const val APP = "moe.nepnep.hduhelper"
    const val SYSTEM_UI = "com.android.systemui"
    const val PLUGIN = "miui.systemui.plugin"
    const val AUTHORITY = "$APP.island.bridge"
    const val REQUEST = "$APP.ISLAND_HOST_REQUEST"
    const val DESCRIPTOR = "$APP.IslandHost.v2"
    const val STATUS = IBinder.FIRST_CALL_TRANSACTION
    const val TOGGLE_MUTE = STATUS + 1
    const val VERSION = 2

    fun call(host: IBinder, operation: Int, key: String = "", end: Long = 0): Bundle {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeString(key)
            request.writeLong(end)
            check(host.transact(operation, request, response, 0)) { "Island host protocol unavailable" }
            response.readException()
            return requireNotNull(response.readBundle(IslandWire::class.java.classLoader))
        } finally { request.recycle(); response.recycle() }
    }
}
