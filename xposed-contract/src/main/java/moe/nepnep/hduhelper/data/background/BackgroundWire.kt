package moe.nepnep.hduhelper.data.background

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

object BackgroundWire {
    const val APP = "moe.nepnep.hduhelper"
    const val AUTHORITY = "$APP.background.bridge"
    const val REQUEST = "$APP.BACKGROUND_HOST_REQUEST"
    const val DESCRIPTOR = "$APP.BackgroundHost.v1"
    const val VERSION = 1
    const val POLICY_VERSION = 2
    const val GROUP = "background"
    const val ENABLED = "enabled"
    const val STATUS = IBinder.FIRST_CALL_TRANSACTION

    fun status(host: IBinder): Bundle {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(DESCRIPTOR)
            check(host.transact(STATUS, input, output, 0))
            output.readException()
            return requireNotNull(output.readBundle(BackgroundWire::class.java.classLoader))
        } finally { input.recycle(); output.recycle() }
    }
}
