package com.luxwatch.gadget.noise

/** Byte-for-byte verification against vectors from the Python SDK. */
object Verify {
    private fun hex(s: String): ByteArray = Noise.hexToBytes(s)

    // Minimal JSON string extraction (vectors are flat string->string maps,
    // plus one string->list-of-strings).
    private fun loadStrings(text: String): Map<String, String> {
        return Regex("\"([^\"]+)\":\\s*\"([0-9a-f]+)\"")
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun check(name: String, actual: ByteArray, expectedHex: String) {
        val expected = hex(expectedHex)
        if (!actual.contentEquals(expected)) {
            println("FAIL $name")
            println("  expected: $expectedHex")
            println("  actual:   " + actual.joinToString("") { "%02x".format(it) })
            throw AssertionError(name)
        }
        println("PASS $name (${actual.size} bytes)")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val framingText = Vectors.require(Vectors.FRAMING)
        val nv = loadStrings(Vectors.require(Vectors.NOISE))
        val fv = loadStrings(framingText)

        // Fixed keys matching the Python generator (note: the test responder
        // consumes two keys, so the initiator's static key is the 4th).
        val initE = hex("11".repeat(32))
        val initS = hex("44".repeat(32))

        val init = Noise.Initiator(fixedEphemeralPriv = initE, fixedStaticPriv = initS)
        init.initialize()
        val m1 = init.writeMessage1()
        check("noise.msg1", m1, nv["msg1"]!!)

        // Feed the Python responder's msg2 (validates our decrypt path).
        init.readMessage2(hex(nv["msg2"]!!))
        println("PASS noise.readMessage2 (no exception)")

        val m3 = init.writeMessage3()
        check("noise.msg3", m3, nv["msg3"]!!)

        val (c1, _) = init.split()
        val ct = c1.encryptWithAd(ByteArray(0), "hello-noise-test".toByteArray())
        check("noise.transport_ct", ct, nv["transport_ct"]!!)

        // Protobuf primitives.
        check("proto.varint(1)", com.luxwatch.gadget.proto.Proto.encodeVarint(1), fv["varint_1"]!!)
        check("proto.varint(300)", com.luxwatch.gadget.proto.Proto.encodeVarint(300), fv["varint_300"]!!)
        check(
            "proto.int64field(1,-1)",
            com.luxwatch.gadget.proto.Proto.int64Field(1, -1), fv["int64_neg1"]!!
        )

        // Service frame + chunk framing.
        val req = Framing.AppRequest(
            verb = "POST", path = "/chat/stream",
            headers = listOf("Content-Type" to "application/json"),
            body = "{\"message\":\"hi\"}".toByteArray(), endBody = true
        )
        val svc = Framing.encodeServiceFrame(1, req)
        check("framing.service_frame", svc, fv["service_frame"]!!)
        val chunks = Framing.encodeNoiseFrames(svc, 0x123456789ABCDEF)
        val chunkHex = Regex("\"chunks\":\\s*\\[\\s*\"([0-9a-f]+)\"")
            .find(framingText)!!.groupValues[1]
        check("framing.chunk[0]", chunks[0], chunkHex)

        println("\nALL VECTORS MATCH")
    }
}
