package com.fantasysea.clanservice.binding.entity

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeviceBindingTest {
    @Test
    fun `normalizes both directions into the same database order`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val forward = DeviceBinding.of("dev_a", "dev_z", now)
        val reverse = DeviceBinding.of("dev_z", "dev_a", now)

        assertEquals(forward.deviceLowId, reverse.deviceLowId)
        assertEquals(forward.deviceHighId, reverse.deviceHighId)
        assertEquals("dev_z", forward.peerOf("dev_a"))
    }

    @Test
    fun `rejects a self binding`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceBinding.of("dev_a", "dev_a", Instant.EPOCH)
        }
    }
}
