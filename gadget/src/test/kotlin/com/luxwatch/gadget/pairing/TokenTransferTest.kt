package com.luxwatch.gadget.pairing

import com.luxwatch.gadget.noise.Vectors
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The watch side of the browser → watch token transfer, against a vector the web page's code made. */
class TokenTransferTest {
    private val v = JSONObject(Vectors.require("token_transfer.json"))
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val priv = hex(v.getString("watch_priv"))
    private val pub = hex(v.getString("watch_pub"))
    private val message = hex(v.getString("message"))
    private val chunks = v.getJSONArray("chunks").let { a -> List(a.length()) { hex(a.getString(it)) } }

    @Test
    fun opensWhatTheBrowserSealed() {
        assertEquals(v.getString("token"), TokenTransfer.open(priv, pub, message))
    }

    @Test
    fun assemblesTheChunks() {
        val a = TokenTransfer.Assembler()
        chunks.dropLast(1).forEach { assertNull(a.feed(it)) }
        assertArrayEquals(message, a.feed(chunks.last()))
        // A resend starts over at index 0 and works again.
        chunks.dropLast(1).forEach { assertNull(a.feed(it)) }
        assertArrayEquals(message, a.feed(chunks.last()))
    }

    @Test
    fun dropsOutOfOrderChunks() {
        val a = TokenTransfer.Assembler()
        assertNull(a.feed(chunks[0]))
        assertNull(a.feed(chunks[2])) // skipped one: start over
        chunks.drop(1).forEach { assertNull(a.feed(it)) } // no index 0 yet
    }

    @Test
    fun rejectsTamperingAndOtherKeys() {
        val tampered = message.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertThrows(TokenTransfer.TransferError::class.java) { TokenTransfer.open(priv, pub, tampered) }
        val other = TokenTransfer.Receiver()
        assertThrows(TokenTransfer.TransferError::class.java) { other.open(message) }
        assertThrows(TokenTransfer.TransferError::class.java) { TokenTransfer.open(priv, pub, message.copyOf(80)) }
    }
}
