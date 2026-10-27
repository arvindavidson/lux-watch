package com.luxwatch.gadget.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarLinksTest {
    @Test
    fun stateLines() {
        val f = AvatarLinks.parse(
            """
            I can't reach your watch to run the command myself. Here are the links:
            name: Lux
            idle: https://files.example/a/connecting.mp4?sig=1
            thinking: https://files.example/a/working.mp4
            speaking — https://files.example/a/making_something.mp4.
            happy: https://files.example/a/milestone_level_up.mp4
            """.trimIndent(),
        )!!
        assertEquals("https://files.example/a/connecting.mp4?sig=1", f.clips["idle"])
        assertEquals("https://files.example/a/working.mp4", f.clips["thinking"])
        assertEquals("https://files.example/a/making_something.mp4", f.clips["speaking"])
        assertEquals("https://files.example/a/milestone_level_up.mp4", f.clips["happy"])
        assertEquals("Lux", f.name)
    }

    @Test
    fun markdownListsAndFileNames() {
        val f = AvatarLinks.parse(
            """
            - **Connecting / idle** ([connecting.mp4](https://x.example/v/connecting.mp4))
            - [Working](https://x.example/v/waiting_for_subagents.mp4)
            - https://x.example/v/milestone_level_up.mp4
            """.trimIndent(),
        )!!
        assertEquals("https://x.example/v/connecting.mp4", f.clips["idle"])
        assertEquals("https://x.example/v/waiting_for_subagents.mp4", f.clips["thinking"])
        assertEquals("https://x.example/v/milestone_level_up.mp4", f.clips["happy"])
        assertNull(f.name)
    }

    @Test
    fun anUnlabelledLinkBecomesIdleAndHttpIsIgnored() {
        val f = AvatarLinks.parse("Here you go: https://x.example/v/clip1.mp4 and http://insecure.example/a.mp4")!!
        assertEquals(mapOf("idle" to "https://x.example/v/clip1.mp4"), f.clips)
        assertNull(AvatarLinks.parse("Sorry, I don't have avatar clips."))
    }

    @Test
    fun readsFilePathsOnMusesComputerToo() {
        val found = AvatarLinks.parse(
            """
            name: Lux
            idle: /home/muse/avatar/connecting.mp4
            thinking: `/home/muse/avatar/working.MP4`
            (see https://example.com/notes.txt, and /etc/passwd isn't a clip)
            """.trimIndent(),
        )!!
        assertEquals("/home/muse/avatar/connecting.mp4", found.clips["idle"])
        assertEquals("/home/muse/avatar/working.MP4", found.clips["thinking"])
        assertEquals(setOf("idle", "thinking"), found.clips.keys)
    }
}
