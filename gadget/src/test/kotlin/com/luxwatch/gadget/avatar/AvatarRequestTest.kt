package com.luxwatch.gadget.avatar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** docs/muse-avatar-request.md must match its sources (gadget/tools/make_avatar_request.py). */
class AvatarRequestTest {
    @Test
    fun requestIsUpToDate() {
        val root = File("..")
        val avatar = "gadget/src/main/kotlin/com/luxwatch/gadget/avatar"
        fun read(rel: String) = File(root, rel).readText().trimEnd('\n')
        val expected = buildList {
            add(read("docs/muse-avatar-prompt.md"))
            for (name in listOf("PixelAvatar.kt", "LuxPixel.kt")) {
                add("$name:\n```kotlin\n${read("$avatar/$name")}\n```")
            }
        }.joinToString("\n\n") + "\n"
        assertEquals(
            "docs/muse-avatar-request.md is stale: run gadget/tools/make_avatar_request.py",
            expected,
            File(root, "docs/muse-avatar-request.md").readText(),
        )
    }
}
