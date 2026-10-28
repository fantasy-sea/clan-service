package com.fantasysea.clanservice.control.service

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.service.SessionRelay
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper

class ControlServiceTest {
    private lateinit var sessions: SessionService
    private lateinit var gateway: RecordingGateway
    private lateinit var service: ControlService
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() {
        sessions = mock(SessionService::class.java)
        gateway = RecordingGateway()
        service = ControlService(sessions, gateway, InMemoryRateLimiter(CLOCK), CLOCK)
    }

    @Test
    fun `tap is normalized and forwarded to the controlled device`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        service.control("dev_controller", "ctl-1", "session_1", payload("""{"action":"TAP","x":0.25,"y":0.75,"evil":"drop"}"""))

        val (target, event) = gateway.sent.single()
        assertEquals("dev_controlled", target)
        assertEquals("CONTROL", event.type)
        assertEquals("ctl-1", event.requestId)
        assertEquals("session_1", event.sessionId)
        assertEquals("TAP", event.payloadValue()["action"])
        assertEquals(0.25, event.payloadValue()["x"])
        assertEquals(0.75, event.payloadValue()["y"])
        assertFalse(event.payloadValue().containsKey("evil"))
    }

    @Test
    fun `swipe carries normalized coordinates and duration`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        service.control(
            "dev_controller", "ctl-2", "session_1",
            payload("""{"action":"SWIPE","startX":0.1,"startY":0.2,"endX":0.3,"endY":0.4,"duration":500}"""),
        )

        val event = gateway.sent.single().second
        val value = event.payloadValue()
        assertEquals("SWIPE", value["action"])
        assertEquals(0.1, value["startX"])
        assertEquals(0.4, value["endY"])
        assertEquals(500, value["duration"])
    }

    @Test
    fun `back has only the action field`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        service.control("dev_controller", "ctl-3", "session_1", payload("""{"action":"BACK","x":9}"""))

        assertEquals(mapOf("action" to "BACK"), gateway.sent.single().second.payloadValue())
    }

    @Test
    fun `agent cannot send control`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        val error = assertThrows(BusinessException::class.java) {
            service.control("dev_controlled", "ctl-1", "session_1", payload("""{"action":"BACK"}"""))
        }

        assertEquals(ErrorCode.CONTROL_NOT_ALLOWED, error.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `invalid coordinates and actions are rejected before forwarding`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        val outOfRange = assertThrows(BusinessException::class.java) {
            service.control("dev_controller", "ctl-1", "session_1", payload("""{"action":"TAP","x":1.2,"y":0.5}"""))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, outOfRange.errorCode)

        val unknown = assertThrows(BusinessException::class.java) {
            service.control("dev_controller", "ctl-1", "session_1", payload("""{"action":"PINCH"}"""))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, unknown.errorCode)

        val badDuration = assertThrows(BusinessException::class.java) {
            service.control(
                "dev_controller", "ctl-1", "session_1",
                payload("""{"action":"SWIPE","startX":0.1,"startY":0.2,"endX":0.3,"endY":0.4,"duration":10}"""),
            )
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, badDuration.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `control result is relayed from controlled to controller`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        service.controlResult(
            "dev_controlled", "ctl-9", "session_1",
            payload("""{"scope":"COMMAND","result":"FAILED","reason":"ACCESSIBILITY_DENIED","evil":"drop"}"""),
        )

        val (target, event) = gateway.sent.single()
        assertEquals("dev_controller", target)
        assertEquals("CONTROL_RESULT", event.type)
        assertEquals("ctl-9", event.requestId)
        assertEquals("COMMAND", event.payloadValue()["scope"])
        assertEquals("FAILED", event.payloadValue()["result"])
        assertEquals("ACCESSIBILITY_DENIED", event.payloadValue()["reason"])
        assertFalse(event.payloadValue().containsKey("evil"))
    }

    @Test
    fun `controller cannot send a command result`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        val error = assertThrows(BusinessException::class.java) {
            service.controlResult("dev_controller", "ctl-9", "session_1", payload("""{"scope":"COMMAND","result":"OK"}"""))
        }

        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, error.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `control result requires COMMAND scope`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", CONNECTED_ONLY)).thenReturn(relay())

        val error = assertThrows(BusinessException::class.java) {
            service.controlResult(
                "dev_controlled", "ctl-9", "session_1",
                payload("""{"scope":"SESSION_REQUEST","result":"OK"}"""),
            )
        }

        assertEquals(ErrorCode.INVALID_PARAMETER, error.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `permission status requires both booleans`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", CONNECTING_OR_CONNECTED)).thenReturn(
            relay(RemoteSessionStatus.CONNECTING),
        )

        val error = assertThrows(BusinessException::class.java) {
            service.permissionStatus("dev_controlled", "session_1", payload("""{"screenCapture":true}"""))
        }

        assertEquals(ErrorCode.INVALID_PARAMETER, error.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `permission status is relayed while connecting`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", CONNECTING_OR_CONNECTED)).thenReturn(
            relay(RemoteSessionStatus.CONNECTING),
        )

        service.permissionStatus(
            "dev_controlled", "session_1",
            payload("""{"screenCapture":true,"accessibility":false}"""),
        )

        val (target, event) = gateway.sent.single()
        assertEquals("dev_controller", target)
        assertEquals("PERMISSION_STATUS", event.type)
        assertEquals(true, event.payloadValue()["screenCapture"])
        assertEquals(false, event.payloadValue()["accessibility"])
    }

    @Test
    fun `unreachable target returns device offline`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", CONNECTED_ONLY)).thenReturn(relay())
        gateway.delivered = false

        val error = assertThrows(BusinessException::class.java) {
            service.control("dev_controller", "ctl-1", "session_1", payload("""{"action":"HOME"}"""))
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.errorCode)
    }

    private fun relay(status: RemoteSessionStatus = RemoteSessionStatus.CONNECTED) =
        SessionRelay("session_1", "dev_controller", "dev_controlled", status)

    private fun payload(json: String) = mapper.readTree(json)

    private fun WsOutboundMessage.payloadValue(): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        return payload as Map<*, *>
    }

    private class RecordingGateway(var delivered: Boolean = true) : WsEventGateway {
        val sent = mutableListOf<Pair<String, WsOutboundMessage>>()

        override fun isReachable(deviceId: String): Boolean = true

        override fun send(deviceId: String, message: WsOutboundMessage): Boolean {
            if (!delivered) return false
            sent += deviceId to message
            return true
        }

        override fun closeDevice(deviceId: String) = Unit
    }

    companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
        val CONNECTED_ONLY = setOf(RemoteSessionStatus.CONNECTED)
        val CONNECTING_OR_CONNECTED = setOf(RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }
}
