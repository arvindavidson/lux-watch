package com.luxwatch.gadget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepsNewestFirstAcrossRestartsAndCapsTheLength() {
        val file = tmp.newFile("history.jsonl").also { it.delete() }
        val log = ConversationLog(file, max = 3)
        for (i in 1..4) log.add(ConversationLog.Entry(i.toLong(), "q$i", "a\"$i\"\nline"))
        log.add(ConversationLog.Entry(5, null, "Stand up", from = "Reminder"))

        val again = ConversationLog(file, max = 3)
        val all = again.all()
        assertEquals(listOf(5L, 4L, 3L), all.map { it.time })
        assertNull(all[0].you)
        assertEquals("Reminder", all[0].from)
        assertEquals("a\"4\"\nline", all[1].reply)
        assertEquals("q4", all[1].you)
    }

    @Test
    fun clearEmptiesItAndANoisyFileIsSurvived() {
        val file = tmp.newFile("h.jsonl")
        file.writeText("not json\n{\"t\":1,\"reply\":\"ok\",\"from\":\"Lux\"}\n")
        val log = ConversationLog(file)
        assertEquals(1, log.all().size)
        var changes = 0
        log.onChange = { changes++ }
        log.clear()
        assertEquals(0, ConversationLog(file).all().size)
        assertEquals(1, changes)
    }
}
