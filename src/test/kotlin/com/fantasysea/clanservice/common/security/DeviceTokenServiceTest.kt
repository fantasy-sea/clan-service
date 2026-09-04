package com.fantasysea.clanservice.common.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceTokenServiceTest {

    private val tokenService = DeviceTokenServiceImpl()

    @Test
    fun `generated token is url safe base64 without padding`() {
        val token = tokenService.generate()
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `generated tokens are unique`() {
        val tokens = (1..100).map { tokenService.generate() }.toSet()
        assertEquals(100, tokens.size)
    }

    @Test
    fun `hash produces 64 char sha256 hex`() {
        val hash = tokenService.hash("some-token")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(hash, tokenService.hash("some-token"))
        assertTrue(tokenService.hash("other-token") != hash)
    }

    @Test
    fun `matches verifies and rejects`() {
        val token = tokenService.generate()
        val hash = tokenService.hash(token)
        assertTrue(tokenService.matches(token, hash))
        assertFalse(tokenService.matches("other-token", hash))
    }
}
