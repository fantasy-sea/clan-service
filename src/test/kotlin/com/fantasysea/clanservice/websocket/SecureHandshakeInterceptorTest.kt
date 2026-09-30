package com.fantasysea.clanservice.websocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.socket.WebSocketHandler

class SecureHandshakeInterceptorTest {
    private val interceptor = SecureHandshakeInterceptor()

    @Test
    fun `rejects every supported token query parameter without reflecting it`() {
        listOf("token", "access_token", "authorization").forEach { name ->
            val servletRequest = MockHttpServletRequest("GET", "/ws").apply { queryString = "$name=secret-marker" }
            val servletResponse = MockHttpServletResponse()

            val allowed = interceptor.beforeHandshake(
                ServletServerHttpRequest(servletRequest), ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler::class.java), mutableMapOf(),
            )

            assertFalse(allowed)
            assertEquals(400, servletResponse.status)
            assertFalse(servletResponse.contentAsString.contains("secret-marker"))
        }
    }

    @Test
    fun `allows ordinary query parameters`() {
        val servletRequest = MockHttpServletRequest("GET", "/ws").apply {
            queryString = "client=android"
            remoteAddr = "192.0.2.10"
        }
        val attributes = mutableMapOf<String, Any>()

        assertTrue(interceptor.beforeHandshake(
            ServletServerHttpRequest(servletRequest), ServletServerHttpResponse(MockHttpServletResponse()),
            mock(WebSocketHandler::class.java), attributes,
        ))
        assertEquals("192.0.2.10", attributes[ClanWebSocketHandler.REMOTE_IP_ATTRIBUTE])
    }
}
