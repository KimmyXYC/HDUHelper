package moe.nepnep.hduhelper.data.update

import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*

data class AppRelease(val tag: String, val notes: String) {
    val url: String get() = "https://github.com/KimmyXYC/HDUHelper/releases/tag/$tag"
}

internal data class AppVersion(val major: BigInteger, val minor: BigInteger, val patch: BigInteger) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String, local: Boolean = false): AppVersion? {
            val pattern = if (local) "v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:\\.[0-9a-f]{7})?" else "v([0-9]+)\\.([0-9]+)\\.([0-9]+)"
            val match = Regex(pattern).matchEntire(value) ?: return null
            return AppVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(), match.groupValues[3].toBigInteger())
        }
    }
}

fun interface UpdateSource {
    suspend fun check(currentVersion: String): AppRelease?
}

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://api.github.com/repos/KimmyXYC/HDUHelper/releases/latest",
) : UpdateSource {
    override suspend fun check(currentVersion: String): AppRelease? {
        val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HDUHelper").build()
        val body = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException(when (it.code) {
                                404 -> "暂无可用正式版"
                                403, 429 -> "检查过于频繁，请稍后重试"
                                else -> "更新服务暂不可用"
                            })
                            it.body.string()
                        }
                    }
                    if (!continuation.isCancelled) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
        return parseRelease(body, currentVersion)
    }

    internal fun parseRelease(body: String, currentVersion: String): AppRelease? {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            require(root["draft"]?.jsonPrimitive?.boolean == false && root["prerelease"]?.jsonPrimitive?.boolean == false)
            val tag = root.getValue("tag_name").jsonPrimitive.content
            val remote = requireNotNull(AppVersion.parse(tag))
            val local = requireNotNull(AppVersion.parse(currentVersion, local = true))
            require(root.getValue("assets").jsonArray.any {
                it.jsonObject["name"]?.jsonPrimitive?.content == "HDUHelper-$tag.apk"
            })
            if (remote <= local) return null
            val notes = root["body"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "暂无更新说明"
            return AppRelease(tag, notes)
        } catch (error: IllegalArgumentException) {
            throw IOException("更新信息无法识别，请稍后重试", error)
        } catch (error: NoSuchElementException) {
            throw IOException("更新信息不完整，请稍后重试", error)
        }
    }
}

interface UpdateCheckStore {
    var lastAttempt: Long?
}

class AndroidUpdateCheckStore(context: android.content.Context) : UpdateCheckStore {
    private val preferences = context.getSharedPreferences("update_check", android.content.Context.MODE_PRIVATE)
    override var lastAttempt: Long?
        get() = if (preferences.contains("last_attempt")) preferences.getLong("last_attempt", 0) else null
        set(value) { preferences.edit().apply { if (value == null) remove("last_attempt") else putLong("last_attempt", value) }.apply() }
}
