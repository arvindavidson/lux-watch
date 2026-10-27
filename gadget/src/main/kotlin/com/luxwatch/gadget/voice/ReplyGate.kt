package com.luxwatch.gadget.voice

import org.json.JSONObject

/**
 * Which of Muse's call audio to play after an interruption: a port of the
 * SDK's voice_gate.py. Provider response ids identify stale audio; a new
 * caller turn's matching turn_started admits its reply and what follows.
 */
class ReplyGate {
    enum class Verdict { DROP, HOLD, PLAY }
    private enum class State { STALE, WAITING, PLAYING, CONTINUING }

    private val ids = LinkedHashMap<String, State>()
    private val items = HashSet<String>()
    private var fenced = false
    private var item: String? = null
    private var confirmed = false
    private var blocked = false
    private var turnFresh = false
    private var flushPlaying = false
    private var flushWaiting = false

    /** The response being generated now, which barge-in cancels. */
    var openResponse: String? = null
        private set

    val waiting: Boolean get() = has(State.WAITING)
    val playing: Boolean get() = has(State.PLAYING, State.CONTINUING)

    fun interrupt(item: String? = null) {
        fenced = true
        for (k in ids.keys) ids[k] = State.STALE
        this.item = item
        confirmed = false; blocked = false; turnFresh = false
        openResponse = null
        flushPlaying = true; flushWaiting = true
    }

    /** (flush what's playing, flush what's waiting), once. */
    fun takeFlush(): Pair<Boolean, Boolean> = (flushPlaying to flushWaiting).also {
        flushPlaying = false; flushWaiting = false
    }

    fun event(event: JSONObject) {
        when (event.optString("type")) {
            "input_audio_buffer.speech_started" -> {
                val id = identifier(event.opt("item_id"))
                if (id != null) {
                    if (id in items) return // a delayed or repeated start can't pick an older caller
                    if (items.size == MAX_IDS) throw IllegalStateException("too many caller input identities")
                    items += id
                }
                interrupt(id)
            }
            "hatch.voice.turn_started" -> {
                val id = identifier(event.opt("item_id"))
                if (item == null || id == null || identifier(event.opt("submission_message_id")) == null) return
                if (id != item) {
                    flushWaiting = flushWaiting || waiting
                    flushPlaying = flushPlaying || has(State.CONTINUING)
                    replace(setOf(State.WAITING, State.CONTINUING), State.STALE)
                    blocked = true; turnFresh = false
                    return
                }
                val hadReply = waiting || playing
                replace(setOf(State.WAITING, State.CONTINUING), State.PLAYING)
                confirmed = true; blocked = false
                turnFresh = !hadReply
            }
            "hatch.voice.input_suppressed" ->
                if (item != null && identifier(event.opt("item_id")) == item) interrupt()
            "response.created", "response.done", "response.completed" -> {
                val responseId = identifier(event.optJSONObject("response")?.opt("id")) ?: return
                if (event.optString("type") == "response.created") openResponse = responseId
                else if (responseId == openResponse) openResponse = null
            }
        }
    }

    fun audio(event: JSONObject): Verdict {
        val responseId = if (event.optString("type") == "continuous_audio_video.delta") {
            identifier(event.opt("response_id"))
        } else null
        if (responseId == null) return if (fenced) Verdict.DROP else Verdict.PLAY
        ids[responseId]?.let { return verdict(it) }
        if (ids.size == MAX_IDS) throw IllegalStateException("too many provider response identities")
        val state = when {
            !fenced -> State.PLAYING
            item == null || blocked -> State.STALE
            confirmed -> {
                flushPlaying = flushPlaying || playing
                replace(setOf(State.PLAYING, State.CONTINUING), State.STALE)
                (if (turnFresh) State.PLAYING else State.CONTINUING).also { turnFresh = false }
            }
            else -> {
                flushWaiting = flushWaiting || waiting
                replace(setOf(State.WAITING), State.STALE)
                State.WAITING
            }
        }
        ids[responseId] = state
        return verdict(state)
    }

    private fun verdict(s: State) = when (s) {
        State.WAITING -> Verdict.HOLD
        State.STALE -> Verdict.DROP
        else -> Verdict.PLAY
    }

    private fun has(vararg states: State) = ids.values.any { it in states }

    private fun replace(from: Set<State>, to: State) {
        for ((k, v) in ids) if (v in from) ids[k] = to
    }

    companion object {
        const val MAX_IDS = 128

        /** A usable id: a non-empty string without NUL, at most 128 UTF-8 bytes. */
        fun identifier(value: Any?): String? {
            if (value !is String || value.isEmpty() || '\u0000' in value) return null
            return if (value.toByteArray(Charsets.UTF_8).size <= 128) value else null
        }
    }
}
