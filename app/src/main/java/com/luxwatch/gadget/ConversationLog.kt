package com.luxwatch.gadget

import org.json.JSONObject
import java.io.File

/**
 * The last [max] exchanges with Lux (and messages from Muse), kept only on the
 * watch in a JSON-lines file, for the history page.
 */
class ConversationLog(private val file: File, private val max: Int = 50) {
    data class Entry(
        val time: Long,
        /** What the user said (Muse's transcript), or null for a message from Muse. */
        val you: String?,
        val reply: String,
        /** Who replied: "Lux", or a message's title. */
        val from: String = "Lux",
    )

    private val entries = ArrayList<Entry>() // oldest first

    /** Called after every change (on the caller's thread). */
    var onChange: (() -> Unit)? = null

    init {
        if (file.isFile) {
            file.readLines().mapNotNullTo(entries) { line ->
                runCatching {
                    val o = JSONObject(line)
                    Entry(o.getLong("t"), o.optString("you").takeIf { o.has("you") }, o.getString("reply"), o.optString("from", "Lux"))
                }.getOrNull()
            }
            while (entries.size > max) entries.removeAt(0)
        }
    }

    /** Newest first. */
    @Synchronized
    fun all(): List<Entry> = entries.asReversed().toList()

    fun add(entry: Entry) {
        synchronized(this) {
            entries += entry
            while (entries.size > max) entries.removeAt(0)
            save()
        }
        onChange?.invoke()
    }

    fun clear() {
        synchronized(this) {
            entries.clear()
            file.delete()
        }
        onChange?.invoke()
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(
            entries.joinToString("\n", postfix = "\n") { e ->
                JSONObject().put("t", e.time).apply { if (e.you != null) put("you", e.you) }
                    .put("reply", e.reply).put("from", e.from).toString()
            },
        )
        tmp.renameTo(file)
    }
}
