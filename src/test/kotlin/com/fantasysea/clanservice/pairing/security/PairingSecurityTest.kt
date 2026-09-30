package com.fantasysea.clanservice.pairing.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingSecurityTest {
    @Test
    fun `HMAC is deterministic and never stores the plaintext code`() {
        val hasher = PairingCodeHasher(PairingProperties("0123456789abcdef0123456789abcdef"))
        val first = hasher.hash("000042")

        assertEquals(first, hasher.hash("000042"))
        assertNotEquals(first, hasher.hash("000043"))
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertNotEquals("000042", first)
    }

    @Test
    fun `HMAC key shorter than 32 bytes is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingCodeHasher(PairingProperties("too-short"))
        }
    }

    @Test
    fun `generated codes always preserve the six digit wire shape`() {
        val generator = PairingCodeGenerator()
        repeat(100) { assertTrue(generator.generate().matches(Regex("^[0-9]{6}$"))) }
    }
}
