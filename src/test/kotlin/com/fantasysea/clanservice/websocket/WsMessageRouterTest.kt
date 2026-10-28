package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.control.service.ControlService
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.signaling.service.SignalingService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper

class WsMessageRouterTest {
    private lateinit var sessions: SessionService
    private lateinit var signaling: SignalingService
    private lateinit var control: ControlService
    private lateinit var router: WsMessageRouter
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() {
        sessions = mock(SessionService::class.java)
        signaling = mock(SignalingService::class.java)
        control = mock(ControlService::class.java)
        router = WsMessageRouter(sessions, signaling, control, CLOCK)
    }

    @Test
    fun `all P4 types are dispatched to the owning service`() {
        val payload = payload("{}")

        assertNull(router.route("dev_controller", "SESSION_CONNECTED", null, "session_1", payload))
        assertNull(router.route("dev_controller", "WEBRTC_OFFER", "sig-1", "session_1", payload))
        assertNull(router.route("dev_controlled", "WEBRTC_ANSWER", "sig-2", "session_1", payload))
        assertNull(router.route("dev_controller", "ICE_CANDIDATE", "ice-1", "session_1", payload))
        assertNull(router.route("dev_controlled", "PERMISSION_STATUS", null, "session_1", payload))
        assertNull(router.route("dev_controller", "CONTROL", "ctl-1", "session_1", payload))
        assertNull(router.route("dev_controlled", "CONTROL_RESULT", "ctl-1", "session_1", payload))

        verify(sessions).connect("dev_controller", "session_1")
        verify(signaling).offer("dev_controller", "sig-1", "session_1", payload)
        verify(signaling).answer("dev_controlled", "sig-2", "session_1", payload)
        verify(signaling).iceCandidate("dev_controller", "ice-1", "session_1", payload)
        verify(control).permissionStatus("dev_controlled", "session_1", payload)
        verify(control).control("dev_controller", "ctl-1", "session_1", payload)
        verify(control).controlResult("dev_controlled", "ctl-1", "session_1", payload)
    }

    @Test
    fun `missing session id is rejected`() {
        val error = router.route("dev_controller", "CONTROL", "ctl-1", null, payload("{}"))!!

        assertEquals("ERROR", error.type)
        assertEquals("INVALID_PARAMETER", error.payloadValue()["code"])
        verifyNoInteractions(sessions, signaling, control)
    }

    @Test
    fun `missing request id for control is rejected`() {
        val error = router.route("dev_controller", "CONTROL", null, "session_1", payload("{}"))!!

        assertEquals("ERROR", error.type)
        assertEquals("INVALID_PARAMETER", error.payloadValue()["code"])
        verifyNoInteractions(sessions, signaling, control)
    }

    @Test
    fun `oversized ids are rejected without echoing them`() {
        val longSession = "s".repeat(WsMessageRouter.MAX_SESSION_ID_LENGTH + 1)
        val longRequest = "r".repeat(WsMessageRouter.MAX_REQUEST_ID_LENGTH + 1)

        val sessionError = router.route("dev_controller", "CONTROL", "ctl-1", longSession, payload("{}"))!!
        assertEquals("Invalid sessionId", sessionError.payloadValue()["message"])
        assertNull(sessionError.sessionId)

        val requestError = router.route("dev_controller", "CONTROL", longRequest, "session_1", payload("{}"))!!
        assertEquals("Invalid requestId", requestError.payloadValue()["message"])
        assertNull(requestError.requestId)
        verifyNoInteractions(sessions, signaling, control)
    }

    @Test
    fun `business exception maps to error with code and retry after`() {
        `when`(sessions.connect("dev_controller", "session_1")).thenThrow(
            BusinessException(ErrorCode.RATE_LIMITED, retryAfterSeconds = 17),
        )

        val error = router.route("dev_controller", "SESSION_CONNECTED", null, "session_1", payload("{}"))!!

        assertEquals("ERROR", error.type)
        assertEquals("session_1", error.sessionId)
        assertEquals("RATE_LIMITED", error.payloadValue()["code"])
        assertEquals(17L, error.payloadValue()["retryAfter"])
    }

    @Test
    fun `unexpected exception returns generic internal error`() {
        `when`(control.control("dev_controller", "ctl-1", "session_1", payload("{}"))).thenThrow(
            RuntimeException("secret-marker-never-returned"),
        )

        val error = router.route("dev_controller", "CONTROL", "ctl-1", "session_1", payload("{}"))!!

        assertEquals("ERROR", error.type)
        assertEquals("INTERNAL_ERROR", error.payloadValue()["code"])
        assertFalse(error.payloadValue().toString().contains("secret-marker-never-returned"))
    }

    @Test
    fun `unknown type returns unsupported message even without session id`() {
        val error = router.route("dev_controller", "UNKNOWN", "req-1", null, payload("{}"))!!

        assertEquals("ERROR", error.type)
        assertEquals("UNSUPPORTED_MESSAGE", error.payloadValue()["code"])
        verifyNoInteractions(sessions, signaling, control)
    }

    private fun payload(json: String) = mapper.readTree(json)

    private fun WsOutboundMessage.payloadValue(): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        return payload as Map<*, *>
    }

    companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
    }
}
