package moe.nepnep.hduhelper

import android.app.Application
import moe.nepnep.hduhelper.data.auth.AuthRepository
import moe.nepnep.hduhelper.data.auth.HduAuthApi
import moe.nepnep.hduhelper.data.auth.androidSessionStore
import moe.nepnep.hduhelper.data.settings.SettingsRepository

class HDUHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.webkit.WebView.setDataDirectorySuffix("hdu_auth")
    }

    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val network = moe.nepnep.hduhelper.data.network.NetworkMonitor(application)
    val settings = SettingsRepository(application)
    val auth = AuthRepository(androidSessionStore(application), settings, HduAuthApi())
    val campusCodes = moe.nepnep.hduhelper.data.campuscode.CampusCodeRepository(auth)
    init { auth.onSessionInvalidated(campusCodes::clear) }
}
