package com.luxwatch.gadget.voice

/**
 * Cuts a reply that is still streaming in into pieces the watch can start
 * speaking right away: each whole sentence as soon as it's complete, so the
 * first words play while Muse is still writing the rest.
 *
 * Feed it the reply so far each time it grows; it returns only the new text
 * ready to speak. With `final = true` it returns everything left.
 */
class SpeechChunker {
    /** How much of the reply has been handed out. */
    private var spoken = 0

    fun next(text: String, final: Boolean = false): List<String> {
        if (spoken > text.length) spoken = text.length // the reply was rewritten shorter
        val rest = text.substring(spoken)
        val end = if (final) rest.length else readyUpTo(rest)
        if (end <= 0) return emptyList()
        spoken += end
        val piece = rest.substring(0, end).trim()
        return if (piece.isEmpty()) emptyList() else listOf(piece)
    }

    /** Length of the leading run of [rest] that ends a sentence (or a long clause), else 0. */
    private fun readyUpTo(rest: String): Int {
        var cut = 0
        for (m in SENTENCE_END.findAll(rest)) cut = m.range.last + 1
        if (cut > 0) return cut
        // A long sentence with no end yet: speak up to its last pause rather than wait.
        if (rest.length >= LONG_CLAUSE) {
            val pause = CLAUSE_END.findAll(rest).lastOrNull() ?: return 0
            if (pause.range.last + 1 >= MIN_CLAUSE) return pause.range.last + 1
        }
        return 0
    }

    private companion object {
        /** . ! ? … (and closing quotes or brackets) followed by a space or a line break; or a line break. */
        val SENTENCE_END = Regex("""[.!?…]+["'”’)\]]*\s+|\n+""")
        val CLAUSE_END = Regex("""[,;:—–]\s+""")
        const val LONG_CLAUSE = 120
        const val MIN_CLAUSE = 40
    }
}
