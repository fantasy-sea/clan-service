package com.fantasysea.clanservice.websocket

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.CloseStatus
import tools.jackson.databind.json.JsonMapper

class WebSocketConnectionRegistryTest {
    @Test
    fun `new connection replaces old and stale close cannot remove new connection`() {
        val registry = WebSocketConnectionRegistry(JsonMapper.builder().build())
        val old = session("old")
        val replacement = session("replacement")

        registry.register("dev_1", old)
        registry.register("dev_1", replacement)

        verify(old).close(argThat<CloseStatus> { it.getCode() == WebSocketConnectionRegistry.REPLACED_CLOSE_CODE })
        assertNull(registry.remove(old))
        assertTrue(registry.isReachable("dev_1"))
    }

    private fun session(id: String): WebSocketSession = mock(WebSocketSession::class.java).also {
        `when`(it.id).thenReturn(id)
        `when`(it.isOpen).thenReturn(true)
    }
}
