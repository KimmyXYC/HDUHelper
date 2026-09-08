package moe.nepnep.hduhelper.data.background

import android.net.Uri

/** Increment only for incompatible companion protocol changes; app versions are independent. */
object ModuleWire {
    const val PACKAGE = "moe.nepnep.hduhelper.xposed"
    const val PERMISSION = "$PACKAGE.permission.CONFIGURE"
    const val VERSION = 1
    val URI: Uri = Uri.parse("content://$PACKAGE.bridge")
}
