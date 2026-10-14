package com.luxwatch.gadget.noise

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInvokeTest {
    private val notify = CommandSpec(
        name = "watch.notify",
        description = "Show a \"message\" on the watch.",
        required = listOf(CommandSpec.Param("text", "string", "What to show.")),
        optional = listOf(CommandSpec.Param("speak", "boolean", "Read it aloud.")),
        timeoutMs = 10_000,
    )

    private fun registered(t: FakeTransport, handler: CommandHandler): MuseSession = runBlocking {
        val s = MuseSession(t, "node-1", commands = listOf(notify), onInvoke = handler)
        val job = launch { s.register() }
        val control = withTimeout(2_000) {
            while (t.stream("/link-control") == null) delay(5)
            t.stream("/link-control")!!
        }
        val reg = JSONObject(messages(t).first())
        val reply = """{"type":"res","id":"${reg.getString("id")}","ok":true}"""
        control.ch.send(chunk(reply))
        job.join()
        s
    }

    private fun chunk(vararg msgs: String) =
        NoiseTransport.StreamEvent.BodyChunk(msgs.fold(ByteArray(0)) { a, m -> a + SessionFraming.encodeMessage(m.toByteArray()) }, false)

    private fun messages(t: FakeTransport): List<String> {
        val buf = java.io.ByteArrayOutputStream().apply { write(t.body("/link-control")) }
        return SessionFraming.drainMessages(buf)
    }

    private fun resultFor(t: FakeTransport, id: String): JSONObject = runBlocking {
        withTimeout(2_000) {
            while (true) {
                messages(t).map(::JSONObject).firstOrNull { it.optString("method") == "link.result" && it.optString("id") == id }
                    ?.let { return@withTimeout it }
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    @Test
    fun registerAdvertisesTheCommands() {
        val t = FakeTransport()
        registered(t) { _, _ -> CommandResult.Ok() }
        val params = JSONObject(messages(t).first()).getJSONObject("params")
        val cmd = params.getJSONObject("commands_v2").getJSONObject("watch.notify")
        assertEquals("Show a \"message\" on the watch.", cmd.getString("description"))
        assertEquals("string", cmd.getJSONObject("required").getJSONObject("text").getString("type"))
        assertEquals("boolean", cmd.getJSONObject("optional").getJSONObject("speak").getString("type"))
        assertEquals(10_000, cmd.getInt("timeout_ms"))
    }

    @Test
    fun invokeRunsTheHandlerAndReturnsItsPayload() {
        val t = FakeTransport()
        var got: Pair<String, String>? = null
        val s = registered(t) { cmd, params ->
            got = cmd to params
            CommandResult.Ok("""{"shown":true}""")
        }
        // params carries its own "id": the invoke's id must still win.
        val invoke = """{"method":"link.invoke","params":{"id":"inner","text":"Stand up","speak":false},"id":"inv-7","command":"watch.notify"}"""
        runBlocking { t.stream("/link-control")!!.ch.send(chunk(invoke)) }
        val r = resultFor(t, "inv-7")
        assertTrue(r.getBoolean("ok"))
        assertTrue(r.getJSONObject("payload").getBoolean("shown"))
        assertEquals("watch.notify", got!!.first)
        assertEquals("Stand up", CommandParams.string(got!!.second, "text"))
        assertEquals(false, CommandParams.bool(got!!.second, "speak"))
        s.close()
    }

    @Test
    fun unknownCommandsAndFailuresAreErrors() {
        val t = FakeTransport()
        val s = registered(t) { _, _ -> throw IllegalStateException("boom") }
        runBlocking {
            t.stream("/link-control")!!.ch.send(
                chunk(
                    """{"method":"link.invoke","id":"a","command":"system.run","params":{"command":"ls"}}""",
                    """{"method":"link.invoke","id":"b","command":"watch.notify","params":{"text":"x"}}""",
                ),
            )
        }
        val a = resultFor(t, "a")
        assertFalse(a.getBoolean("ok"))
        assertEquals("unsupported command: system.run", a.getString("error"))
        val b = resultFor(t, "b")
        assertFalse(b.getBoolean("ok"))
        assertEquals("watch.notify failed: boom", b.getString("error"))
        s.close()
    }
}
