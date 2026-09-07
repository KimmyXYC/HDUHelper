package moe.nepnep.hduhelper

import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import moe.nepnep.hduhelper.ui.AppViewModel
import moe.nepnep.hduhelper.ui.CampusCodeStatus
import moe.nepnep.hduhelper.ui.CampusCodeViewModel
import moe.nepnep.hduhelper.ui.screens.CampusCodeScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in: uses the existing session, never captures or logs live QR pixels or credentials. */
class CampusCodeNetworkDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleCodeRecoversAfterRealNetworkLossWithoutNavigation(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("campusNetwork") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        container.auth.initialize()
        assumeTrue(container.auth.serviceIdentity() != null)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val wifi = shell("settings get global wifi_on")
        val data = shell("settings get global mobile_data")
        assumeTrue(wifi in listOf("0", "1") && data in listOf("0", "1") && (wifi == "1" || data == "1"))
        assumeTrue(shell("settings get global airplane_mode_on") == "0")
        val store = ViewModelStore()
        lateinit var model: CampusCodeViewModel
        lateinit var app: AppViewModel
        withContext(Dispatchers.Main) {
            app = AppViewModel(container.auth, container.settings, container.network.online)
            model = CampusCodeViewModel(container.campusCodes, container.auth.state, container.auth.sessionGeneration, container.network.online)
            store.put("app", app); store.put("campus", model)
            app.onForeground(); model.setVisible(true)
        }
        compose.setContent {
            val window = LocalActivity.current!!.window
            DisposableEffect(window) {
                val protected = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { if (!protected) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            val state by model.state.collectAsStateWithLifecycle()
            HDUHelperTheme { CampusCodeScreen(state, { model.refresh() }, {}, {}) }
        }
        try {
            withTimeout(60_000) { while (model.state.value.status != CampusCodeStatus.READY) delay(200) }
            compose.onNodeWithTag("campus_qr").assertIsDisplayed()
            shell("svc wifi disable"); shell("svc data disable")
            withTimeout(20_000) {
                while (model.state.value.message != "网络未连接，联网后自动刷新") delay(200)
            }
            assertEquals(CampusCodeStatus.ERROR, model.state.value.status)
            compose.onNodeWithTag("campus_qr").assertDoesNotExist()
            if (wifi == "1") shell("svc wifi enable")
            if (data == "1") shell("svc data enable")
            withTimeout(60_000) { while (model.state.value.status != CampusCodeStatus.READY) delay(200) }
            compose.onNodeWithTag("campus_qr").assertIsDisplayed()
            assertNotNull(model.state.value.image)
        } finally {
            shell("svc wifi ${if (wifi == "1") "enable" else "disable"}")
            shell("svc data ${if (data == "1") "enable" else "disable"}")
            withContext(Dispatchers.Main) { app.onBackground(); model.setVisible(false); store.clear() }
        }
    }
}
