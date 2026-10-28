package com.fantasysea.clanservice.session.service

import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.pairing.service.DeliveryCompensator
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class SessionServiceTest {
    private lateinit var sessions: RemoteSessionRepository
    private lateinit var locks: DeviceLockService
    private lateinit var gateway: WsEventGateway
    private lateinit var service: SessionService

    @BeforeEach
    fun setUp() {
        sessions = mock(RemoteSessionRepository::class.java)
        locks = mock(DeviceLockService::class.java)
        gateway = mock(WsEventGateway::class.java)
        service = SessionService(
            sessions,
            mock(DeviceBindingRepository::class.java),
            locks,
            mock(UlidGenerator::class.java),
            gateway,
            AfterCommitExecutor(),
            mock(DeliveryCompensator::class.java),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    @Test
    fun `third party cannot query a session`() {
        val session = requesting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))

        val error = assertThrows(BusinessException::class.java) { service.get("dev_intruder", session.id) }

        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, error.errorCode)
    }

    @Test
    fun `only controlled device can reject and result has full response shape`() {
        val session = requesting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val denied = assertThrows(BusinessException::class.java) { service.reject("dev_controller", session.id) }
        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, denied.errorCode)

        val response = service.reject("dev_controlled", session.id)
        assertEquals("REJECTED", response.status)
        assertNotNull(response.rejectedAt)
        verify(locks, org.mockito.Mockito.times(2)).lockPair("dev_controller", "dev_controlled")
    }

    @Test
    fun `reject after request deadline transitions to failed and reports expiry`() {
        val session = RemoteSession(
            id = "session_expired",
            controllerDeviceId = "dev_controller",
            controlledDeviceId = "dev_controlled",
            status = RemoteSessionStatus.REQUESTING,
            expiresAt = NOW,
            createdAt = NOW.minusSeconds(60),
        )
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val error = assertThrows(BusinessException::class.java) { service.reject("dev_controlled", session.id) }

        assertEquals(ErrorCode.SESSION_REQUEST_EXPIRED, error.errorCode)
        assertEquals(RemoteSessionStatus.FAILED, session.status)
        assertEquals("REQUEST_EXPIRED", session.failureReason)
    }

    @Test
    fun `connect moves connecting session to connected and notifies both parties`() {
        val session = connecting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)
        `when`(gateway.isReachable("dev_controller")).thenReturn(true)
        `when`(gateway.isReachable("dev_controlled")).thenReturn(true)

        val response = service.connect("dev_controlled", session.id)

        assertEquals("CONNECTED", response.status)
        assertEquals(NOW, response.connectedAt)
        assertEquals(RemoteSessionStatus.CONNECTED, session.status)
        assertEventSent("dev_controller", "SESSION_CONNECTED", session.id)
        assertEventSent("dev_controlled", "SESSION_CONNECTED", session.id)
    }

    @Test
    fun `connect is idempotent when already connected`() {
        val session = connected()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val response = service.connect("dev_controller", session.id)

        assertEquals("CONNECTED", response.status)
        assertEquals(NOW, response.connectedAt)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `third party cannot connect a session`() {
        val session = connecting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val error = assertThrows(BusinessException::class.java) { service.connect("dev_intruder", session.id) }

        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, error.errorCode)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `connect in requesting state is rejected`() {
        val session = requesting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val error = assertThrows(BusinessException::class.java) { service.connect("dev_controller", session.id) }

        assertEquals(ErrorCode.SESSION_INVALID_STATE, error.errorCode)
    }

    @Test
    fun `connect after deadline fails the session and reports timeout`() {
        val session = connecting(connectExpiresAt = NOW)
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)

        val error = assertThrows(BusinessException::class.java) { service.connect("dev_controller", session.id) }

        assertEquals(ErrorCode.SESSION_CONNECT_TIMEOUT, error.errorCode)
        assertEquals(RemoteSessionStatus.FAILED, session.status)
        assertEquals("CONNECT_TIMEOUT", session.failureReason)
        assertEventSent("dev_controller", "SESSION_FAILED", session.id)
        assertEventSent("dev_controlled", "SESSION_FAILED", session.id)
    }

    @Test
    fun `connect with offline peer fails the session`() {
        val session = connecting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))
        `when`(sessions.findByIdForUpdate(session.id)).thenReturn(session)
        `when`(gateway.isReachable("dev_controller")).thenReturn(true)
        `when`(gateway.isReachable("dev_controlled")).thenReturn(false)

        val error = assertThrows(BusinessException::class.java) { service.connect("dev_controller", session.id) }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.errorCode)
        assertEquals(RemoteSessionStatus.FAILED, session.status)
        assertEquals("DEVICE_OFFLINE", session.failureReason)
    }

    @Test
    fun `requireRelay rejects unknown, third party and wrong status`() {
        val session = connecting()
        `when`(sessions.findById(session.id)).thenReturn(Optional.of(session))

        val missing = assertThrows(BusinessException::class.java) {
            service.requireRelay("dev_controller", "missing", setOf(RemoteSessionStatus.CONNECTED))
        }
        assertEquals(ErrorCode.SESSION_NOT_FOUND, missing.errorCode)

        val intruder = assertThrows(BusinessException::class.java) {
            service.requireRelay("dev_intruder", session.id, setOf(RemoteSessionStatus.CONNECTED))
        }
        assertEquals(ErrorCode.SESSION_NOT_ALLOWED, intruder.errorCode)

        val wrongState = assertThrows(BusinessException::class.java) {
            service.requireRelay("dev_controller", session.id, setOf(RemoteSessionStatus.CONNECTED))
        }
        assertEquals(ErrorCode.SESSION_INVALID_STATE, wrongState.errorCode)
    }

    private fun assertEventSent(deviceId: String, type: String, sessionId: String) {
        val event = ArgumentCaptor.forClass(com.fantasysea.clanservice.websocket.WsOutboundMessage::class.java)
        verify(gateway).send(
            eq(deviceId) ?: deviceId,
            event.capture() ?: com.fantasysea.clanservice.websocket.WsOutboundMessage("unused", timestamp = 0),
        )
        assertEquals(type, event.value.type)
        assertEquals(sessionId, event.value.sessionId)
    }

    private fun requesting() = RemoteSession(
        id = "session_01",
        controllerDeviceId = "dev_controller",
        controlledDeviceId = "dev_controlled",
        status = RemoteSessionStatus.REQUESTING,
        expiresAt = NOW.plusSeconds(60),
        createdAt = NOW,
    )

    private fun connecting(connectExpiresAt: Instant = NOW.plusSeconds(120)) = RemoteSession(
        id = "session_connecting",
        controllerDeviceId = "dev_controller",
        controlledDeviceId = "dev_controlled",
        status = RemoteSessionStatus.CONNECTING,
        expiresAt = NOW.plusSeconds(60),
        acceptedAt = NOW.minusSeconds(1),
        connectExpiresAt = connectExpiresAt,
        createdAt = NOW.minusSeconds(2),
    )

    private fun connected() = connecting().apply {
        status = RemoteSessionStatus.CONNECTED
        connectedAt = NOW
    }

    companion object { val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z") }
}
