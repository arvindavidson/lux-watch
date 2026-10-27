package com.luxwatch.gadget.noise

/**
 * One /chat/subscribe NDJSON event, reduced to the fields the reply logic uses.
 * Mirrors parse_row in Meta's muse_chat_link.c: event or event_name; message_id
 * (else id); reply_to_message_id (else parent_message_id); text from
 * display_text > content > text; display_text_ready defaults to true.
 */
data class ChatRow(
    val event: String,
    val seq: Long,
    val msg: String,
    val replyTo: String,
    val text: String,
    val ready: Boolean,
    val activityCode: String? = null,
    val status: String? = null,
) {
    companion object {
        fun parse(line: String): ChatRow? {
            val payload = Json.obj(line, "payload")
            val top = if (payload.isEmpty()) line else line.replaceFirst(payload, "{}")
            if (Json.str(top, "type") != "event") return null
            val event = Json.str(top, "event") ?: Json.str(top, "event_name") ?: return null
            fun field(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { Json.str(payload, it) }
                    ?: keys.firstNotNullOfOrNull { Json.str(top, it) }
            return ChatRow(
                event = event,
                seq = Json.long(top, "seq") ?: 0,
                msg = field("message_id", "id") ?: "",
                replyTo = field("reply_to_message_id", "parent_message_id") ?: "",
                text = field("display_text", "content", "text") ?: "",
                ready = !Regex("\"display_text_ready\"\\s*:\\s*false").containsMatchIn(line),
                activityCode = Json.str(payload, "activity_code"),
                status = Json.str(payload, "status"),
            )
        }
    }
}

/**
 * Builds one turn's reply from subscription rows, after the /chat/stream ack has
 * given the note's message id ([noteId]) and its parent ([parentId]).
 * Follows Meta's muse_chat_link.c (subscription_event, related, on_row) and the
 * busy tracking in muse_chat_session.cpp.
 */
class ReplyAssembler(private val noteId: String, private val parentId: String) {
    private var lastSeq = 0L
    private val deltas = HashMap<String, StringBuilder>()
    private val deltaReplyTo = HashMap<String, String>()
    private val finished = LinkedHashMap<String, String>()
    /** Assistant messages of the turn in the order they started. */
    private val order = LinkedHashSet<String>()

    /** What Muse heard (the note's transcript), once the server has transcribed it. */
    var heard: String? = null
        private set

    /** True while agent.status / task.status say Muse is still working. */
    var busy = false
        private set

    val replied: Boolean get() = finished.isNotEmpty()

    /** All finished assistant messages of the turn, in order, joined with spaces. */
    val text: String get() = finished.values.joinToString(" ")

    /**
     * The reply so far, while Muse is still writing it: finished messages and
     * the text streamed into unfinished ones, in order. Equals [text] once
     * every message is done.
     */
    val live: String
        get() = order.mapNotNull { id -> finished[id] ?: deltas[id]?.toString() }
            .filter { it.isNotEmpty() }.joinToString(" ")

    /** Applies one row; returns true when [heard] or [text] changed. */
    fun accept(r: ChatRow): Boolean {
        // De-duplicate only rows that carry a sequence number.
        if (r.seq > 0) {
            if (r.seq <= lastSeq) return false
            lastSeq = r.seq
        }
        when (r.event) {
            "agent.status", "task.status" -> {
                val code = r.activityCode
                val status = r.status
                if (code != null) busy = code.isNotEmpty() && code != "online" && code != "idle"
                else if (status != null) busy = status.isNotEmpty() && status != "completed" && status != "failed"
                return false
            }
        }
        val append = r.event == "delta.text_append"
        val start = r.event == "delta.message_start"
        val done = r.event == "delta.message_done"
        val full = r.event == "message.assistant"
        val user = r.event == "message.user"
        if (!(append || start || done || full || user) || r.msg.isEmpty()) return false

        if (user) {
            if (r.msg != noteId && (parentId.isEmpty() || r.msg != parentId)) return false
            // "<transcript>\n[file:audio/wav ...]", or "[Voice note]" before transcription.
            val said = r.text.substringBefore("\n[file:").trim()
            if (said.isEmpty() || said == "[Voice note]" || heard != null) return false
            heard = said
            return true
        }

        val replyTo = r.replyTo.ifEmpty { deltaReplyTo[r.msg] ?: "" }
        if (!related(replyTo)) return false
        if (start || append) {
            order += r.msg
            val sb = deltas.getOrPut(r.msg) { StringBuilder() }
            if (r.replyTo.isNotEmpty()) deltaReplyTo[r.msg] = r.replyTo
            if (append) sb.append(r.text)
            return false
        }
        // message_done always completes a message; a persisted message.assistant
        // only once its display text is ready.
        if (!done && !r.ready) return false
        val text = r.text.ifEmpty { deltas[r.msg]?.toString() ?: "" }
        if (text.isEmpty() || finished.containsKey(r.msg)) return false
        order += r.msg
        finished[r.msg] = text
        return true
    }

    /** Explicit parents must match the ack; unparented live replies follow the note. */
    private fun related(replyTo: String): Boolean =
        replyTo.isEmpty() || replyTo == noteId || (parentId.isNotEmpty() && replyTo == parentId)
}
