package moe.nepnep.hduhelper

import android.app.NotificationManager
import android.media.AudioManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import moe.nepnep.hduhelper.data.island.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class IslandDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as HDUHelperApplication).container

    @Test fun activatedModuleHasAuthenticatedReadyHost(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandEnabled") == "true")
        repeat(30) {
            val state = container.island.refreshNow()
            if (state.ready) {
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nIsland ready: $state\n") })
                assertTrue(state.muteSupported)
                return@runBlocking
            }
            delay(300)
        }
        fail("Island host unavailable: ${container.island.state.value}")
    }

    @Test fun moduleAppCannotRegisterAnImpostorSystemUiHost() {
        val result = runCatching {
            context.contentResolver.call(android.net.Uri.parse("content://${IslandWire.AUTHORITY}"), "register", null,
                Bundle().apply { putInt("version", IslandWire.VERSION); putBinder("host", android.os.Binder()) })
        }
        assertTrue(result.isFailure)
    }

    @Test fun muteRestoresAfterExpiryWithoutChangingMediaAlarmOrDnd(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandMute") == "true")
        val initial = container.island.refreshNow()
        assertTrue("Module must be active", initial.ready && initial.muteSupported)
        assumeTrue(!initial.ringerSilent && !initial.mutedByModule)
        val audio = context.getSystemService(AudioManager::class.java)
        val manager = context.getSystemService(NotificationManager::class.java)
        val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val dnd = manager.currentInterruptionFilter
        val key = "island-mute-device-test-${java.util.UUID.randomUUID()}"
        try {
            assertTrue(container.island.toggleMute(key, System.currentTimeMillis() + 3500))
            assertTrue(container.island.state.value.ringerSilent)
            assertTrue(container.island.state.value.mutedByModule)
            assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            assertEquals(dnd, manager.currentInterruptionFilter)
            withTimeout(10_000) {
                while (container.island.refreshNow().mutedByModule) delay(200)
            }
            assertEquals(initial.ringerSilent, container.island.state.value.ringerSilent)
            assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            assertEquals(dnd, manager.currentInterruptionFilter)
        } finally {
            if (key in container.island.refreshNow().muteKeys) container.island.toggleMute(key, System.currentTimeMillis() + 1000)
        }
    }
    @Test fun systemMuteTileCancelsOwnershipAndLaterUserSilenceIsPreserved(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandMute") == "true")
        val initial = container.island.refreshNow()
        assertTrue(initial.ready && initial.muteSupported)
        val audio = context.getSystemService(AudioManager::class.java)
        assumeTrue(!initial.ringerSilent && !initial.mutedByModule && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL)
        val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun findTile(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == "静音" || node.contentDescription?.toString()?.startsWith("静音") == true) {
                var target: android.view.accessibility.AccessibilityNodeInfo? = node
                repeat(5) { if (target?.isClickable == true) return target; target = target?.parent }
            }
            for (i in 0 until node.childCount) findTile(node.getChild(i))?.let { return it }
            return null
        }
        fun tile() = automation.windows.firstNotNullOfOrNull { findTile(it.root) }
        val key = "island-mute-manual-${java.util.UUID.randomUUID()}"
        var changedToUserSilent = false
        try {
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            delay(1000)
            assumeTrue("System mute tile is not available to accessibility", tile() != null)
            val deadline = System.currentTimeMillis() + 8000
            assertTrue(container.island.toggleMute(key, deadline))
            assertTrue(tile()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(5000) { while (container.island.refreshNow().ringerSilent) delay(100) }
            assertFalse(container.island.state.value.mutedByModule)
            assertTrue(tile()!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            changedToUserSilent = true
            withTimeout(5000) { while (!container.island.refreshNow().ringerSilent) delay(100) }
            delay((deadline - System.currentTimeMillis() + 500).coerceAtLeast(1))
            assertTrue(container.island.refreshNow().ringerSilent)
            assertFalse(container.island.state.value.mutedByModule)
            assertEquals(dnd, context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
        } finally {
            if (key in container.island.refreshNow().muteKeys) container.island.toggleMute(key, System.currentTimeMillis() + 1000)
            if (changedToUserSilent && container.island.refreshNow().ringerSilent) {
                tile()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                withTimeout(5000) { while (container.island.refreshNow().ringerSilent) delay(100) }
            }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    @Test fun userManuallyLeavingSilentModeReleasesOwnership(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandManualMute") == "true")
        val initial = container.island.refreshNow()
        assertTrue(initial.ready && initial.muteSupported && !initial.ringerSilent && !initial.mutedByModule)
        val key = "island-manual-probe-${java.util.UUID.randomUUID()}"
        try {
            assertTrue(container.island.toggleMute(key, System.currentTimeMillis() + 120_000))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nManual mute probe ready: disable silent mode using the system control.\n") })
            withTimeout(110_000) { while (container.island.refreshNow().ringerSilent) delay(250) }
            assertFalse(container.island.state.value.mutedByModule)
            assertTrue(container.island.state.value.muteKeys.isEmpty())
        } finally {
            if (key in container.island.refreshNow().muteKeys) container.island.toggleMute(key, System.currentTimeMillis() + 1000)
        }
    }

    @Test fun beginAppExitMuteProbe(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandExitMute") == "true")
        val initial = container.island.refreshNow()
        assertTrue(initial.ready && initial.muteSupported && !initial.ringerSilent && !initial.mutedByModule)
        val key = "island-exit-probe-${java.util.UUID.randomUUID()}"
        val audio = context.getSystemService(AudioManager::class.java)
        val checkpoint = org.json.JSONObject().put("key", key).put("mode", audio.ringerMode)
            .put("dnd", context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
            .put("music", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("alarm", audio.getStreamVolume(AudioManager.STREAM_ALARM))
        context.cacheDir.resolve("island-mute-probe.json").writeText(checkpoint.toString())
        assertTrue(container.island.toggleMute(key, System.currentTimeMillis() + 15_000))
        assertTrue(container.island.state.value.mutedByModule)
        // Paired with finishAppExitMuteProbe after the shell stops this app and waits for expiry.
    }

    @Test fun finishAppExitMuteProbe(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("islandExitMute") == "true")
        val file = context.cacheDir.resolve("island-mute-probe.json")
        val checkpoint = org.json.JSONObject(file.readText())
        val key = checkpoint.getString("key")
        try {
            withTimeout(10_000) { while (!container.island.refreshNow().ready) delay(200) }
            val state = container.island.state.value // STATUS only reads; it never expires/restores a mute lease.
            assertFalse(state.mutedByModule)
            assertFalse(state.ringerSilent)
            val audio = context.getSystemService(AudioManager::class.java)
            assertEquals(checkpoint.getInt("mode"), audio.ringerMode)
            assertEquals(checkpoint.getInt("dnd"), context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
            assertEquals(checkpoint.getInt("music"), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(checkpoint.getInt("alarm"), audio.getStreamVolume(AudioManager.STREAM_ALARM))
        } finally {
            if (key in container.island.refreshNow().muteKeys) container.island.toggleMute(key, System.currentTimeMillis() + 1000)
            file.delete()
        }
    }

}
