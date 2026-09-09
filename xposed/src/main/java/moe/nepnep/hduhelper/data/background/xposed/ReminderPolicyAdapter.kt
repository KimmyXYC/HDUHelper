package moe.nepnep.hduhelper.data.background.xposed

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import io.github.libxposed.api.XposedInterface
import moe.nepnep.hduhelper.data.background.BackgroundIdentity
import moe.nepnep.hduhelper.data.background.BackgroundPolicy
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/** Resolve the whole ROM contract before installing; every exemption requires an owned alarm. */
internal class ReminderPolicyAdapter(loader: ClassLoader) {
    private val alarmType = Class.forName("com.android.server.alarm.Alarm", false, loader)
    private val core = Class.forName("com.android.server.alarm.AlarmManagerService", false, loader)
    private val vendor = Class.forName("com.android.server.alarm.AlarmManagerServiceStubImpl", false, loader)
    private val store = Class.forName("com.android.server.alarm.LazyAlarmStore", false, loader)
    private val broadcasts = Class.forName("com.android.server.am.BroadcastQueueModernStubImpl", false, loader)
    private val record = Class.forName("com.android.server.am.BroadcastRecord", false, loader)
    private val skipPolicy = Class.forName("com.android.server.am.BroadcastSkipPolicy", false, loader)
    private val activityManager = Class.forName("com.android.server.am.ActivityManagerService", false, loader)
    private val operation = alarmType.getDeclaredField("operation").apply { isAccessible = true }
    private val getIntent = PendingIntent::class.java.getDeclaredMethod("getIntent").apply { isAccessible = true }
    private val setPolicy = alarmType.getDeclaredMethod("setPolicyElapsed", Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType).apply { isAccessible = true; check(returnType == Boolean::class.javaPrimitiveType) }
    private val vendorPolicies = listOf(
        booleanMethod(vendor, "checkAlarmIsAllowedSend", Context::class.java, alarmType) to true,
        booleanMethod(vendor, "alignAlarmLocked", alarmType) to false,
        booleanMethod(vendor, "adjustAlarmLocked", alarmType) to false,
        booleanMethod(vendor, "isExemptFromSsru", alarmType) to true,
    )
    private val androidPolicies = listOf(
        booleanMethod(core, "adjustDeliveryTimeBasedOnBucketLocked", alarmType) to 1,
        // Some shipped ROMs retain the compiler's synthetic name for this method.
        booleanMethod(core, listOf("adjustDeliveryTimeBasedOnDeviceIdle", "lambda\$triggerAlarmsLocked\$22"), alarmType) to 2,
        booleanMethod(core, "adjustDeliveryTimeBasedOnBatterySaver", alarmType) to 3,
    )
    private val backgroundRestricted = booleanMethod(core, "isBackgroundRestricted", alarmType)
    private val trigger = core.getDeclaredMethod("triggerAlarmsLocked", ArrayList::class.java, Long::class.javaPrimitiveType)
    private val removePending = store.getDeclaredMethod("removePendingAlarms", Long::class.javaPrimitiveType)
    private val frozen = booleanMethod(core, "isUidFrozen", Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val thaw = core.getDeclaredMethod("unFreezeActionForUids", IntArray::class.java).apply { isAccessible = true }
    private val autostart = booleanMethod(broadcasts, "checkApplicationAutoStart",
        Class.forName("com.android.server.am.BroadcastQueue", false, loader), record, ResolveInfo::class.java)
    private val skipMessage = skipPolicy.getDeclaredMethod("shouldSkipMessage", record, ResolveInfo::class.java,
        Boolean::class.javaPrimitiveType).apply { check(returnType == String::class.java) }
    private val startMode = activityManager.getDeclaredMethod("getAppStartModeLOSP", Int::class.javaPrimitiveType,
        String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java).apply {
        check(returnType == Int::class.javaPrimitiveType)
    }
    private val broadcastIntent = record.getDeclaredField("intent").apply { isAccessible = true }
    private val broadcastCaller = record.getDeclaredField("callerPackage").apply { isAccessible = true }
    private val broadcastUid = record.getDeclaredField("callingUid").apply { isAccessible = true }
    private val broadcastAlarm = record.getDeclaredField("alarm").apply { isAccessible = true }
    private val broadcastUser = record.getDeclaredField("userId").apply { isAccessible = true }
    private val delivering = ThreadLocal<Any?>()
    private val starting = ThreadLocal<Int?>()
    val exemptions = AtomicLong()
    val starts = AtomicLong()
    val thaws = AtomicLong()

    fun install(module: XposedInterface, identity: () -> BackgroundIdentity?, enabled: () -> Boolean) {
        fun owned(alarm: Any?): Boolean = enabled() && runCatching {
            if (alarm == null) return@runCatching false
            val pending = operation.get(alarm) as? PendingIntent ?: return@runCatching false
            val intent = getIntent.invoke(pending) as? Intent ?: return@runCatching false
            pending.isBroadcast && pending.isImmutable && BackgroundPolicy.reminder(identity(),
                pending.creatorPackage, pending.creatorUid, intent.action,
                intent.component?.packageName, intent.component?.className)
        }.getOrDefault(false)

        fun ownBroadcast(value: Any?, info: Any?): Boolean = runCatching {
            if (value == null) return@runCatching false
            val target = (info as? ResolveInfo)?.activityInfo ?: return@runCatching false
            if (!target.enabled || !target.applicationInfo.enabled ||
                target.applicationInfo.flags and ApplicationInfo.FLAG_STOPPED != 0) return@runCatching false
            val intent = broadcastIntent.get(value) as? Intent ?: return@runCatching false
            val component = intent.component
            (component == null || component.packageName == target.packageName && component.className == target.name) &&
                BackgroundPolicy.broadcast(identity(), enabled(), broadcastCaller.get(value) as? String,
                    broadcastUid.getInt(value), broadcastAlarm.getBoolean(value), intent.action,
                    target.packageName, target.applicationInfo.uid, target.name, broadcastUser.getInt(value))
        }.getOrDefault(false)

        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            vendorPolicies.forEach { (method, result) ->
                handles += module.hook(method).intercept { chain ->
                    if (owned(chain.args.lastOrNull())) { exemptions.incrementAndGet(); result } else chain.proceed()
                }
            }
            androidPolicies.forEach { (method, policy) ->
                handles += module.hook(method).intercept { chain ->
                    val alarm = chain.args[0]
                    if (owned(alarm)) {
                        // Preserve REQUESTER time. Remove only this alarm's added standby/Doze delay.
                        exemptions.incrementAndGet()
                        setPolicy.invoke(alarm, policy, 0L)
                    } else chain.proceed()
                }
            }
            handles += module.hook(backgroundRestricted).intercept { chain ->
                if (owned(chain.args[0])) { exemptions.incrementAndGet(); false } else chain.proceed()
            }
            handles += module.hook(trigger).intercept { chain ->
                val previous = delivering.get()
                delivering.set(chain.thisObject)
                try { chain.proceed() } finally {
                    if (previous == null) delivering.remove() else delivering.set(previous)
                }
            }
            handles += module.hook(removePending).intercept { chain ->
                val pending = chain.proceed()
                val service = delivering.get()
                // Thaw only when an actual owned reminder is due, before the Aurogon cache branch.
                // No UID-wide permanent exception and no change to the returned alarm list.
                if (service != null && (pending as? List<*>)?.any(::owned) == true) {
                    identity()?.let { own ->
                        runCatching {
                            if (frozen.invoke(service, own.appUid) == true) {
                                thaw.invoke(service, intArrayOf(own.appUid))
                                if (frozen.invoke(service, own.appUid) == false) thaws.incrementAndGet()
                                else android.util.Log.w("HDUBackground", "System declined reminder thaw")
                            }
                        }.onFailure { android.util.Log.w("HDUBackground", "Reminder thaw failed", it) }
                    }
                }
                pending
            }
            handles += module.hook(autostart).intercept { chain ->
                if (ownBroadcast(chain.args[1], chain.args[2])) { starts.incrementAndGet(); true } else chain.proceed()
            }
            handles += module.hook(skipMessage).intercept { chain ->
                val previous = starting.get()
                starting.set(if (ownBroadcast(chain.args[0], chain.args[1])) identity()?.appUid else null)
                try { chain.proceed() } finally {
                    if (previous == null) starting.remove() else starting.set(previous)
                }
            }
            handles += module.hook(startMode).intercept { chain ->
                // Only the background-policy check nested inside this exact receiver delivery.
                // BroadcastSkipPolicy still performs permission, export, user and package checks.
                if (enabled() && starting.get() != null && starting.get() == chain.args[0] &&
                    chain.args[1] == moe.nepnep.hduhelper.data.background.BackgroundWire.APP) 0
                else chain.proceed()
            }
            // Undo relevant AOT inlining, including policy recalculation after another alarm fires.
            core.declaredMethods.filter {
                it.name == "setImplLocked" || it.name == "triggerAlarmsLocked" || it.name.startsWith("lambda\$")
            }.forEach(module::deoptimize)
            broadcasts.declaredMethods.filter { it.name == "checkSkipReceiver" }.forEach(module::deoptimize)
            skipPolicy.declaredMethods.filter { it.name.startsWith("shouldSkip") }.forEach(module::deoptimize)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    private fun booleanMethod(type: Class<*>, name: String, vararg parameters: Class<*>?): Method =
        booleanMethod(type, listOf(name), *parameters)

    private fun booleanMethod(type: Class<*>, names: List<String>, vararg parameters: Class<*>?): Method =
        names.firstNotNullOfOrNull { name ->
            runCatching { type.getDeclaredMethod(name, *parameters) }.getOrNull()
        }?.also { check(it.returnType == Boolean::class.javaPrimitiveType) }
            ?: error("Unsupported ${type.name} method: $names")
}
