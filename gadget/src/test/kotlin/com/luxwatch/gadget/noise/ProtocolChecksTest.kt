package com.luxwatch.gadget.noise

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the protocol check programs under `gradle test`. The vector checks
 * are skipped (not passed) while their Python-SDK vector files are absent.
 */
class ProtocolChecksTest {
    @Test
    fun sessionFraming() = SessionFramingCheck.main(emptyArray())

    @Test
    fun noiseAndFramingVectors() {
        assumeVectors(Vectors.NOISE, Vectors.FRAMING)
        Verify.main(emptyArray())
    }

    @Test
    fun blePairingVectors() {
        assumeVectors(Vectors.BLE)
        BleVectorCheck.main(emptyArray())
    }

    private fun assumeVectors(vararg names: String) {
        val missing = names.filter { Vectors.text(it) == null }
        assumeTrue(
            "test vectors not in gadget/src/test/resources: $missing (see README.md there)",
            missing.isEmpty(),
        )
    }
}
