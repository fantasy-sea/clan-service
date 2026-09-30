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
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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

    private fun requesting() = RemoteSession(
        id = "session_01",
        controllerDeviceId = "dev_controller",
        controlledDeviceId = "dev_controlled",
        status = RemoteSessionStatus.REQUESTING,
        expiresAt = NOW.plusSeconds(60),
        createdAt = NOW,
    )

    companion object { val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z") }
}
