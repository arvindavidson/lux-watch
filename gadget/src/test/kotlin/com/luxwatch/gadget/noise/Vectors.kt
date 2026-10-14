package com.luxwatch.gadget.noise

/**
 * Python-SDK test vectors, loaded from the test classpath (src/test/resources).
 * See src/test/resources/README.md for where they come from.
 */
internal object Vectors {
    const val NOISE = "noise_vectors.json"
    const val FRAMING = "framing_vectors.json"
    const val BLE = "link_pairing_v5.json"

    fun text(name: String): String? = Vectors::class.java.getResource("/$name")?.readText()

    fun require(name: String): String =
        text(name) ?: error("missing test vector resource $name (see gadget/src/test/resources/README.md)")
}
