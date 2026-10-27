package com.luxwatch.gadget.avatar

/**
 * Finds avatar clip links in Muse's reply, for when Muse sends them as text
 * instead of calling avatar.set_clips: "idle: https://…" lines, Markdown
 * links, or links whose file name says the state. Https links count, and
 * paths of video files on Muse's VM ("/home/…/idle.mp4"), which the watch
 * reads over its own connection.
 */
object AvatarLinks {
    /** Watch states, and the words Muse's clip names use for them. */
    private val STATE_WORDS = listOf(
        "idle" to listOf("idle", "connecting", "resting"),
        "listening" to listOf("listening", "listen"),
        "thinking" to listOf("thinking", "working", "waiting", "subagent"),
        "speaking" to listOf("speaking", "talking", "making"),
        "happy" to listOf("happy", "milestone", "celebrat", "level_up", "level-up"),
        "error" to listOf("error", "confused", "sad"),
    )
    private val URL = Regex(
        "https://[^\\s<>\"')\\]`]+|(?<![\\w/.:])/[^\\s<>\"')\\]`]+\\.(?:mp4|m4v|mov|webm)(?![\\w])",
        RegexOption.IGNORE_CASE,
    )
    private val NAME = Regex("(?im)^\\W*name\\W*[:\\-–—]\\s*\\**([^*\\n]{1,24}?)\\**\\s*$")

    data class Found(val clips: Map<String, String>, val name: String?)

    /** The clips in [text] by state (idle always present when anything was found), or null. */
    fun parse(text: String): Found? {
        val clips = linkedMapOf<String, String>()
        val unassigned = mutableListOf<String>()
        for (line in text.lines()) {
            for (m in URL.findAll(line)) {
                val url = m.value.trimEnd('.', ',', ';', ':', '*', '_')
                val before = line.substring(0, m.range.first).lowercase()
                val file = url.substringBefore('?').substringAfterLast('/').lowercase()
                // The label in front of the link wins; then the file name.
                val state = stateIn(before) ?: stateIn(file)
                if (state != null && state !in clips) clips[state] = url else if (state == null) unassigned += url
            }
        }
        if (clips.isEmpty() && unassigned.isEmpty()) return null
        if ("idle" !in clips) clips["idle"] = unassigned.firstOrNull() ?: clips.values.first()
        val name = NAME.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Found(clips, name)
    }

    /** The state named last in [s] (closest to the link), if any. */
    private fun stateIn(s: String): String? {
        var best: Pair<String, Int>? = null
        for ((state, words) in STATE_WORDS) for (w in words) {
            val i = s.lastIndexOf(w)
            if (i >= 0 && (best == null || i > best.second)) best = state to i
        }
        return best?.first
    }
}
