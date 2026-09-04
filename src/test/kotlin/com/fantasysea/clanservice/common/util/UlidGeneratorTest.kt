package com.fantasysea.clanservice.common.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UlidGeneratorTest {

    private val generator = UlidGenerator()

    @Test
    fun `generates 26 char crockford base32 string`() {
        val ulid = generator.ulid()
        assertEquals(26, ulid.length)
        assertTrue(ulid.all { it in ALPHABET }, "unexpected chars in " + ulid)
        assertTrue(ulid.none { it in "ILOU" }, "must not contain I/L/O/U")
    }

    @Test
    fun `generates unique values`() {
        val values = (1..1000).map { generator.ulid() }.toSet()
        assertEquals(1000, values.size)
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
