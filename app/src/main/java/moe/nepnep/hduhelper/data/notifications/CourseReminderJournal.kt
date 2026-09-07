package moe.nepnep.hduhelper.data.notifications

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class ReminderJournal(
    @kotlinx.serialization.Transient val legacyPosted: List<Int> = emptyList(),
    val owner: String? = null,
    val island: Boolean = false,
    val records: Map<String, CourseReminderRecord> = emptyMap(),
    val posted: Map<String, Int> = emptyMap(),
    val nextId: Int = 400_000,
    val token: String = "",
    val alarmAt: Long = 0,
) {
    fun dueAlarmAt(now: Long): Long? = alarmAt.takeIf { token.isNotEmpty() && it > 0 && it <= now }

    fun accepts(token: String, at: Long) = token.isNotEmpty() && token == this.token && at == alarmAt

    fun reconcile(owner: String?, validKeys: Set<String>, activeKeys: Set<String>, island: Boolean, now: Long): ReminderJournal {
        val sameOwner = this.owner == owner
        val keep = if (!sameOwner || this.island && !island) emptySet() else if (island) activeKeys else validKeys
        return copy(owner = owner, island = island,
            // Retain dismissed/delivered occurrences across toggle changes until their window ends.
            records = if (sameOwner) records.filterValues { it.expires > now } else emptyMap(),
            posted = posted.filterKeys { it in keep },
        )
    }
}

/** Delivery history contains hashes only and is deliberately excluded from Android backup. */
internal class CourseReminderJournalStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun read(): ReminderJournal {
        if (!file.exists()) return ReminderJournal()
        val text = file.readText()
        val book = json.decodeFromString<ReminderJournal>(text)
        val legacy = json.parseToJsonElement(text).jsonObject["live"]?.jsonPrimitive?.booleanOrNull == true
        return if (legacy) book.copy(legacyPosted = book.posted.values.toList(), posted = emptyMap()) else book
    }

    @Synchronized fun save(journal: ReminderJournal) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        try {
            temporary.outputStream().use { it.write(json.encodeToString(journal).toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
}
