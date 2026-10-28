package com.fantasysea.clanservice.signaling.service

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

class SignalingServiceTest {
    private lateinit var sessions: SessionService
    private lateinit var gateway: RecordingGateway
    private lateinit var service: SignalingService
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() {
        sessions = mock(SessionService::class.java)
        gateway = RecordingGateway()
        service = SignalingService(sessions, gateway, InMemoryRateLimiter(CLOCK), CLOCK)
    }

    @Test
    fun `offer goes from controller to controlled`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())

        service.offer("dev_controller", "sig-1", "session_1", payload("""{"sdp":"v=0-offer","evil":"drop"}"""))

        val (target, event) = gateway.sent.single()
        assertEquals("dev_controlled", target)
        assertEquals("WEBRTC_OFFER", event.type)
        assertEquals("sig-1", event.requestId)
        assertEquals("session_1", event.sessionId)
        assertEquals(mapOf("sdp" to "v=0-offer"), event.payload as Map<*, *>)
    }

    @Test
    fun `answer goes from controlled to controller`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", ACTIVE)).thenReturn(relay())

        service.answer("dev_controlled", "sig-2", "session_1", payload("""{"sdp":"v=0-answer"}"""))

        val (target, event) = gateway.sent.single()
        assertEquals("dev_controller", target)
        assertEquals("WEBRTC_ANSWER", event.type)
        assertEquals(mapOf("sdp" to "v=0-answer"), event.payload as Map<*, *>)
    }

    @Test
    fun `ice candidate from either side goes to the peer`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())
        `when`(sessions.requireRelay("dev_controlled", "session_1", ACTIVE)).thenReturn(relay())

        service.iceCandidate(
            "dev_controller", "ice-1", "session_1",
            payload("""{"candidate":"candidate:1 1 UDP 1 192.0.2.1 5000 typ host","sdpMid":"0","sdpMLineIndex":0}"""),
        )
        service.iceCandidate(
            "dev_controlled", "ice-2", "session_1",
            payload("""{"candidate":"","sdpMid":"audio"}"""),
        )

        assertEquals(2, gateway.sent.size)
        assertEquals("dev_controlled", gateway.sent[0].first)
        assertEquals("ICE_CANDIDATE", gateway.sent[0].second.type)
        assertEquals("candidate:1 1 UDP 1 192.0.2.1 5000 typ host", (gateway.sent[0].second.payload as Map<*, *>)["candidate"])
        assertEquals(0, (gateway.sent[0].second.payload as Map<*, *>)["sdpMLineIndex"])
        assertFalse((gateway.sent[0].second.payload as Map<*, *>).containsKey("usernameFragment"))

        assertEquals("dev_controller", gateway.sent[1].first)
        assertEquals("", (gateway.sent[1].second.payload as Map<*, *>)["candidate"])
    }

    @Test
    fun `wrong signaling direction is rejected`() {
        `when`(sessions.requireRelay("dev_controlled", "session_1", ACTIVE)).thenReturn(relay())
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())

        val offer = assertThrows(BusinessException::class.java) {
            service.offer("dev_controlled", "sig-1", "session_1", payload("""{"sdp":"v=0"}"""))
        }
        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, offer.errorCode)

        val answer = assertThrows(BusinessException::class.java) {
            service.answer("dev_controller", "sig-2", "session_1", payload("""{"sdp":"v=0"}"""))
        }
        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, answer.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `blank and oversized sdp are rejected`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())

        val blank = assertThrows(BusinessException::class.java) {
            service.offer("dev_controller", "sig-1", "session_1", payload("""{"sdp":"   "}"""))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, blank.errorCode)

        val oversized = assertThrows(BusinessException::class.java) {
            service.offer("dev_controller", "sig-1", "session_1", payload("""{"sdp":"${"a".repeat(33 * 1024)}"}"""))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, oversized.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `oversized ice candidate and invalid mline index are rejected`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())

        val oversized = assertThrows(BusinessException::class.java) {
            service.iceCandidate(
                "dev_controller", "ice-1", "session_1",
                payload("""{"candidate":"${"a".repeat(5 * 1024)}"}"""),
            )
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, oversized.errorCode)

        val invalidIndex = assertThrows(BusinessException::class.java) {
            service.iceCandidate(
                "dev_controller", "ice-1", "session_1",
                payload("""{"candidate":"candidate:1","sdpMLineIndex":256}"""),
            )
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, invalidIndex.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `session state rejection is propagated without forwarding`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenThrow(
            BusinessException(ErrorCode.SESSION_INVALID_STATE),
        )

        val error = assertThrows(BusinessException::class.java) {
            service.offer("dev_controller", "sig-1", "session_1", payload("""{"sdp":"v=0"}"""))
        }

        assertEquals(ErrorCode.SESSION_INVALID_STATE, error.errorCode)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `unreachable peer returns device offline`() {
        `when`(sessions.requireRelay("dev_controller", "session_1", ACTIVE)).thenReturn(relay())
        gateway.delivered = false

        val error = assertThrows(BusinessException::class.java) {
            service.offer("dev_controller", "sig-1", "session_1", payload("""{"sdp":"v=0"}"""))
        }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.errorCode)
    }

    private fun relay(status: RemoteSessionStatus = RemoteSessionStatus.CONNECTING) =
        SessionRelay("session_1", "dev_controller", "dev_controlled", status)

    private fun payload(json: String) = mapper.readTree(json)

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
        val ACTIVE = setOf(RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }
}
