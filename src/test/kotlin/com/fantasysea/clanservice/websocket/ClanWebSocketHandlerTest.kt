package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import java.time.Clock
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.json.JsonMapper

class ClanWebSocketHandlerTest {
    @Test
    fun `valid first AUTH frame authenticates and returns negotiated timings`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val mapper = JsonMapper.builder().build()
            val devices = mock(DeviceRepository::class.java)
            val tokens = mock(DeviceTokenService::class.java)
            val registry = mock(WebSocketConnectionRegistry::class.java)
            val presence = mock(PresenceService::class.java)
            val session = session()
            `when`(tokens.hash("raw-token")).thenReturn("hash")
            `when`(devices.findByDeviceTokenHash("hash")).thenReturn(
                Device("dev_1", "phone", DevicePlatform.ANDROID, deviceTokenHash = "hash"),
            )
            `when`(registry.register("dev_1", session)).thenReturn(false)
            val handler = ClanWebSocketHandler(
                mapper, devices, tokens, registry, presence, InMemoryRateLimiter(CLOCK),
                ConcurrentTaskScheduler(executor), CLOCK, mock(WsMessageRouter::class.java),
            )

            handler.afterConnectionEstablished(session)
            handler.handleMessage(session, TextMessage(
                "{\"type\":\"AUTH\",\"requestId\":\"r1\",\"payload\":{\"deviceToken\":\"raw-token\"}}",
            ))

            verify(presence).connected("dev_1", false)
            val event = ArgumentCaptor.forClass(WsOutboundMessage::class.java)
            verify(registry).send(
                org.mockito.Mockito.eq("dev_1") ?: "dev_1",
                event.capture() ?: WsOutboundMessage("unused", timestamp = 0),
            )
            assertEquals("AUTH_SUCCESS", event.value.type)
            assertEquals(20, (event.value.payload as Map<*, *>)["heartbeatIntervalSeconds"])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `malformed first frame returns generic AUTH_FAILED and closes without reflection`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val mapper = JsonMapper.builder().build()
            val session = session()
            val registry = mock(WebSocketConnectionRegistry::class.java)
            val handler = ClanWebSocketHandler(
                mapper, mock(DeviceRepository::class.java), mock(DeviceTokenService::class.java),
                registry, mock(PresenceService::class.java),
                InMemoryRateLimiter(CLOCK), ConcurrentTaskScheduler(executor), CLOCK,
                mock(WsMessageRouter::class.java),
            )

            handler.afterConnectionEstablished(session)
            handler.handleMessage(session, TextMessage("{secret-marker"))

            val event = ArgumentCaptor.forClass(WsOutboundMessage::class.java)
            verify(registry).send(
                org.mockito.Mockito.eq(session) ?: session,
                event.capture() ?: WsOutboundMessage("unused", timestamp = 0),
            )
            assertEquals("AUTH_FAILED", event.value.type)
            assertFalse(event.value.payload.toString().contains("secret-marker"))
            verify(session).close(org.mockito.ArgumentMatchers.argThat<CloseStatus> { it.getCode() == 1008 })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `authenticated P4 frame is routed and error is returned on the same connection`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val mapper = JsonMapper.builder().build()
            val devices = mock(DeviceRepository::class.java)
            val tokens = mock(DeviceTokenService::class.java)
            val registry = mock(WebSocketConnectionRegistry::class.java)
            val presence = mock(PresenceService::class.java)
            val router = mock(WsMessageRouter::class.java)
            val session = session()
            `when`(tokens.hash("raw-token")).thenReturn("hash")
            `when`(devices.findByDeviceTokenHash("hash")).thenReturn(
                Device("dev_1", "phone", DevicePlatform.ANDROID, deviceTokenHash = "hash"),
            )
            `when`(registry.register("dev_1", session)).thenReturn(false)
            `when`(registry.deviceId(session)).thenReturn("dev_1")
            val error = WsOutboundMessage(
                "ERROR", requestId = "c1", sessionId = "s1", timestamp = 0,
                payload = mapOf("code" to "INVALID_PARAMETER"),
            )
            `when`(router.route("dev_1", "CONTROL", "c1", "s1", mapper.readTree("{}"))).thenReturn(error)
            val handler = ClanWebSocketHandler(
                mapper, devices, tokens, registry, presence, InMemoryRateLimiter(CLOCK),
                ConcurrentTaskScheduler(executor), CLOCK, router,
            )

            handler.afterConnectionEstablished(session)
            handler.handleMessage(session, TextMessage(
                "{\"type\":\"AUTH\",\"requestId\":\"a1\",\"payload\":{\"deviceToken\":\"raw-token\"}}",
            ))
            handler.handleMessage(session, TextMessage(
                "{\"type\":\"CONTROL\",\"requestId\":\"c1\",\"sessionId\":\"s1\",\"payload\":{}}",
            ))

            val event = ArgumentCaptor.forClass(WsOutboundMessage::class.java)
            verify(registry).send(
                org.mockito.Mockito.eq(session) ?: session,
                event.capture() ?: WsOutboundMessage("unused", timestamp = 0),
            )
            assertEquals("ERROR", event.value.type)
            assertEquals("s1", event.value.sessionId)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun session(): WebSocketSession = mock(WebSocketSession::class.java).also {
        `when`(it.id).thenReturn("ws_1")
        `when`(it.isOpen).thenReturn(true)
        `when`(it.attributes).thenReturn(mutableMapOf<String, Any>(ClanWebSocketHandler.REMOTE_IP_ATTRIBUTE to "192.0.2.1"))
    }

    companion object {
        val CLOCK: Clock = Clock.systemUTC()
    }
}
