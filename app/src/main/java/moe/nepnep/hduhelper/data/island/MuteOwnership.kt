package moe.nepnep.hduhelper.data.island

import kotlinx.serialization.Serializable

/** Only the mode and opaque event deadlines cross into SystemUI; no account/course data. */
@Serializable
data class MuteOwnership(val originalMode: Int? = null, val ends: Map<String, Long> = emptyMap()) {
    val owned: Boolean get() = originalMode != null && ends.isNotEmpty()

    fun acquire(key: String, end: Long, now: Long, currentMode: Int): MuteOwnership {
        if (key.isBlank() || end <= now || end - now > 24 * 60 * 60_000L) return this
        if (!owned && currentMode == SILENT) return this
        if (owned && currentMode != SILENT) return MuteOwnership()
        return copy(originalMode = originalMode ?: currentMode, ends = ends + (key to end))
    }

    fun expire(now: Long) = copy(ends = ends.filterValues { it > now })
    fun userChangedMode() = MuteOwnership()
    fun shouldRestore(currentMode: Int) = originalMode != null && currentMode == SILENT

    companion object { const val SILENT = 0 }
}
