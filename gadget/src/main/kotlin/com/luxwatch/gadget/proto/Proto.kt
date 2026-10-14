package com.luxwatch.gadget.proto

import java.io.ByteArrayOutputStream

/**
 * Hand-rolled protobuf codec matching linux/src/musegadget/noise/_proto.py.
 * Only the wire types the gadget protocol uses: varint, length-delimited.
 */
object Proto {
    const val WIRE_VARINT = 0
    const val WIRE_DELIMITED = 2

    class ProtoError(message: String) : IllegalArgumentException(message)

    fun encodeVarint(value: Long): ByteArray {
        require(value >= 0) { "varint value must be non-negative" }
        // Any non-negative Long fits in the uint64 varint range.
        return encodeVarintBits(value)
    }

    /** Encodes the raw 64-bit pattern as an unsigned varint (for int64 negatives). */
    private fun encodeVarintBits(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    /** int64 fields are encoded as unsigned 64-bit two's complement. */
    fun encodeInt64(value: Long): ByteArray = encodeVarintBits(value)

    fun encodeKey(fieldNumber: Int, wireType: Int): ByteArray {
        require(fieldNumber != 0) { "invalid field number" }
        return encodeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    fun varintField(fieldNumber: Int, value: Long): ByteArray =
        encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(value)

    fun int64Field(fieldNumber: Int, value: Long): ByteArray =
        encodeKey(fieldNumber, WIRE_VARINT) + encodeInt64(value)

    fun uint32Field(fieldNumber: Int, value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL) { "uint32 out of range" }
        return varintField(fieldNumber, value)
    }

    fun boolField(fieldNumber: Int, value: Boolean): ByteArray =
        varintField(fieldNumber, if (value) 1 else 0)

    fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray =
        encodeKey(fieldNumber, WIRE_DELIMITED) + encodeVarint(value.size.toLong()) + value

    fun stringField(fieldNumber: Int, value: String): ByteArray =
        bytesField(fieldNumber, value.toByteArray(Charsets.UTF_8))

    // ---- decoding ----

    class Reader(private val buf: ByteArray) {
        var pos = 0
        val remaining: Int get() = buf.size - pos

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= buf.size) throw ProtoError("truncated varint")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw ProtoError("varint overflow")
            }
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > buf.size) throw ProtoError("truncated bytes")
            return buf.copyOfRange(pos, pos + len).also { pos += len }
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)
    }

}
