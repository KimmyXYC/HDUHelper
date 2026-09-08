package moe.nepnep.hduhelper.data.widget.xposed

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.json.JSONObject

/** Host models stay in the host class loader. No server response or another application's widgets are changed. */
internal class WidgetCenterAdapter(private val loader: ClassLoader, private val modulePath: String) {
    companion object {
        const val APP = "moe.nepnep.hduhelper"
        const val HOST = "com.miui.personalassistant"
        const val APP_ADAPTER = "$HOST.picker.business.list.adapter.PickerAppListAdapter"
        const val APP_FRAGMENT = "$HOST.picker.business.list.fragment.PickerAppListFragment"
        const val REPOSITORY = "$HOST.picker.business.detail.PickerDetailRepository"
        const val BASE_ADAPTER = "com.chad.library.adapter.base.BaseQuickAdapter"
        private const val BEANS = "$HOST.picker.business.detail.bean."
        val providers = linkedMapOf(
            "$APP.widget.QuickWidgetProvider" to Spec("日程速览", 2, 2, 1, "quick"),
            "$APP.widget.DoubleWidgetProvider" to Spec("日程双行", 4, 2, 2, "double"),
            "$APP.widget.DayWidgetProvider" to Spec("今日日程", 4, 4, 4, "day"),
        )
    }
    data class Spec(val title: String, val width: Int, val height: Int, val style: Int, val key: String)
    private val appData = type("$HOST.picker.business.list.bean.PickerListAppData")
    private val appPackage = appData.getMethod("getAppPackage")
    private val detailData = type("${BEANS}PickerDetailResponse")
    private val detailWidget = type("${BEANS}PickerDetailResponseWidget")
    private val service = type("$HOST.picker.business.detail.service.PickerDetailService")
    private val query = type("${BEANS}PickerDetailQueryParam").getMethod("getInfo")
    private val queryPackage = type("${BEANS}PickerDetailQueryParamInfo").getMethod("getAppPackage")
    private val gsonType = type("com.google.gson.Gson")
    private val gson by lazy { gsonType.getConstructor().newInstance() }
    private val fromJson = gsonType.getMethod("fromJson", String::class.java, Class::class.java)
    private val openDetail = type("$HOST.picker.business.detail.PickerDetailActivity").getMethod("startPickerDetailForApp",
        Context::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType, String::class.java)

    init {
        // Verify the complete adapter contract before enabling any hook.
        detailData.getMethod("getWidgetImplInfo")
        detailData.getMethod("getOriginStyle")
        detailWidget.getMethod("getWidgetProviderName")
        service.declaredMethods.single { it.name == "getPickerDetail" && it.parameterCount == 2 }
    }
    private fun type(name: String) = Class.forName(name, false, loader)
    private fun decode(json: JSONObject, type: Class<*>): Any = fromJson.invoke(gson, json.toString(), type)

    fun installed(context: Context): List<AppWidgetProviderInfo> {
        val pm = context.packageManager
        val own = pm.getPackageInfo(APP, PackageManager.GET_SIGNING_CERTIFICATES)
        val module = pm.getPackageArchiveInfo(modulePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return emptyList()
        val ownCerts = own.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val moduleCerts = module.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (ownCerts.isEmpty() || ownCerts != moduleCerts) return emptyList()
        return AppWidgetManager.getInstance(context).installedProviders.filter { info ->
            info.provider.packageName == APP && info.provider.className in providers && info.profile == Process.myUserHandle() &&
                pm.getReceiverInfo(info.provider, PackageManager.GET_META_DATA).let {
                    it.enabled && it.metaData?.getBoolean("miuiWidget") == true && it.processName == "$APP:widgetProvider"
                }
        }.sortedBy { providers.keys.indexOf(it.provider.className) }
    }

    fun isOwnRow(row: Any?): Boolean = row != null && appData.isInstance(row) && appPackage.invoke(requireNotNull(row)) == APP

    fun mergeRows(context: Context, original: List<*>?): List<*> {
        val installed = installed(context)
        if (installed.isEmpty()) return original.orEmpty()
        val info = context.packageManager.getPackageInfo(APP, 0)
        val icon = context.packageManager.getApplicationIcon(APP)
        val row = decode(JSONObject().put("expansionType", "1").put("appPackage", APP).put("appName", "杭电助手")
            .put("appVersionCode", info.longVersionCode).put("appVersionName", info.versionName.orEmpty())
            .put("appIcon", "android.resource://$APP/drawable/widget_app_icon?version=${info.lastUpdateTime}").put("appWidgetAmount", installed.size).put("appNamePinyin", "hangdianzhushou")
            .put("itemType", 1000), appData)
        appData.getMethod("setLocalAppIcon", android.graphics.drawable.Drawable::class.java).invoke(row, icon)
        return listOf(row) + original.orEmpty().filterNot(::isOwnRow)
    }

    fun open(context: Context, openSource: Int) {
        if (installed(context).isEmpty()) return
        openDetail.invoke(null, context, APP, "杭电助手", openSource, 1, "hduhelper.local.quick")
    }

    /** Suspend functions may return a value synchronously. Other requests keep their original continuation. */
    fun wrapService(context: Context, original: Any): Any = Proxy.newProxyInstance(loader, arrayOf(service)) { _, method, args ->
        if (method.name == "getPickerDetail" && args?.size == 2 &&
            runCatching { queryPackage.invoke(query.invoke(args[0])) == APP }.getOrDefault(false)) {
            val local = runCatching { details(context) }.getOrDefault(emptyList())
            if (local.isNotEmpty()) return@newProxyInstance local
        }
        try { method.invoke(original, *(args ?: emptyArray())) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    fun details(context: Context): List<Any> {
        val info = context.packageManager.getPackageInfo(APP, 0)
        return installed(context).map { provider ->
            val spec = providers.getValue(provider.provider.className)
            val widget = JSONObject().put("widgetProviderName", provider.provider.className).put("widgetTitle", spec.title)
                .put("desc", "今天的日程、课程与考试").put("lightPreviewUrl", preview(context, spec, false, info.lastUpdateTime))
                .put("darkPreviewUrl", preview(context, spec, true, info.lastUpdateTime))
                .put("sort", spec.style).put("appWidgetWidth", spec.width).put("appWidgetHeight", spec.height)
                .put("installStatus", 1).put("isIndependentProcessWidget", true).put("isRealEditable", false)
            decode(JSONObject().put("implUniqueCode", "hduhelper.local.${spec.key}").put("abilityCode", "hduhelper.local")
                .put("abilityVersion", 1).put("implType", 1).put("style", spec.style).put("originStyle", spec.style)
                .put("appPackage", APP).put("appName", "杭电助手").put("appIcon", "android.resource://$APP/drawable/widget_app_icon?version=${info.lastUpdateTime}")
                .put("appInstalledVersionName", info.versionName.orEmpty()).put("appVersionName", info.versionName.orEmpty())
                .put("appVersionCode", info.longVersionCode).put("appDownloadUrl", "").put("appPublisherName", "杭电助手")
                .put("appPermissionUrl", "").put("appPrivacyUrl", "")
                .put("installInfo", JSONObject().put("upgradeInfo", "请更新杭电助手").put("unInstallInfo", "请安装杭电助手"))
                .put("widgetImplInfo", widget).put("useAppIcon", true), detailData)
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi") // Resources live in the separately installed main APK.
    private fun preview(context: Context, spec: Spec, dark: Boolean, version: Long): String {
        val name = "widget_preview_${spec.key}_${if (dark) "dark" else "light"}"
        check(context.packageManager.getResourcesForApplication(APP).getIdentifier(name, "drawable", APP) != 0) {
            "Update HDUHelper to install its widget previews"
        }
        return Uri.Builder().scheme("android.resource").authority(APP).appendPath("drawable").appendPath(name)
            .appendQueryParameter("version", version.toString()).build().toString()
    }
}
