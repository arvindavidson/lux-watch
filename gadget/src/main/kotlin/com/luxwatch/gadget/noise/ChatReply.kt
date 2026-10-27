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
 * Follows Meta's muse_chat_link.c (subscription_event, related, on_row), the
 * busy tracking in muse_chat_session.cpp, and the newer rules of the SDK's
 * muse_turn.py: replies and statuses answering something else are dropped
 * (and stay dropped), and a terminal task.status ends the turn at once.
 */
class ReplyAssembler(private val noteId: String, private val parentId: String) {
    private var lastSeq = 0L
    private val deltas = HashMap<String, StringBuilder>()
    private val finished = LinkedHashMap<String, String>()
    /** Assistant messages of the turn in the order they started. */
    private val order = LinkedHashSet<String>()
    /** Messages first seen answering something else: never ours, even without a parent later. */
    private val rejected = LinkedHashSet<String>()

    /** What Muse heard (the note's transcript), once the server has transcribed it. */
    var heard: String? = null
        private set

    /** True while agent.status / task.status say Muse is still working on this request. */
    var busy = false
        private set

    /** Muse reported this request's task finished (completed, failed, cancelled). */
    var taskFinished = false
        private set

    val replied: Boolean get() = finished.isNotEmpty()

    /** Every message that started streaming has finished. */
    val allDone: Boolean get() = order.all { it in finished }

    /**
     * Muse is done answering: a reply is in, every message is finished, Muse
     * isn't working, and it said the task is over (else the caller waits for
     * a quiet spell).
     */
    val complete: Boolean get() = replied && allDone && !busy && taskFinished

    /** Finished messages (id to text) in the order they finished. */
    val doneMessages: List<Pair<String, String>> get() = finished.entries.map { it.key to it.value }

    /** All finished assistant messages of the turn, in order, joined with spaces. */
    val text: String get() = order.mapNotNull { finished[it] }.joinToString(" ")

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
                status(r)
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

        if (!ours(r.msg, r.replyTo)) return false
        if (start || append) {
            order += r.msg
            val sb = deltas.getOrPut(r.msg) { StringBuilder() }
            if (append && r.msg !in finished) sb.append(r.text)
            return false
        }
        // message_done always completes a message; a persisted message.assistant
        // only once its display text is ready.
        if (!done && !r.ready) return false
        order += r.msg
        val text = r.text.ifEmpty { deltas[r.msg]?.toString() ?: "" }
        if (text.isEmpty() || finished.containsKey(r.msg)) return false
        finished[r.msg] = text
        return true
    }

    /**
     * A message answers this request: its parent is the note, the note's
     * parent, or one of our replies; or it has no parent and wasn't seen
     * answering something else. A message with a foreign parent is rejected
     * for good (muse_chat_link.c `related`).
     */
    private fun ours(msg: String, replyTo: String): Boolean {
        if (msg in rejected) return false
        if (replyTo.isEmpty() || isOurs(replyTo)) return true
        if (msg !in order) {
            rejected += msg
            if (rejected.size > 256) rejected.remove(rejected.first())
        }
        return false
    }

    private fun isOurs(id: String) = id == noteId || (parentId.isNotEmpty() && id == parentId) || id in order

    private fun status(r: ChatRow) {
        // Shared chats carry statuses for other requests too: one naming another
        // parent mustn't change this turn (muse_turn.py _status).
        if (r.replyTo.isNotEmpty() && !isOurs(r.replyTo)) return
        val status = r.status
        val terminal = status != null && status in TERMINAL
        if (r.event == "task.status" && status != null) taskFinished = terminal
        val code = r.activityCode
        if (code != null) busy = code.isNotEmpty() && code != "online" && code != "idle" && code !in TERMINAL
        else if (status != null) busy = status.isNotEmpty() && status != "idle" && !terminal
        if (r.event == "task.status" && taskFinished) busy = false
    }

    private companion object {
        val TERMINAL = setOf("completed", "failed", "errored", "cancelled", "canceled")
    }
}
