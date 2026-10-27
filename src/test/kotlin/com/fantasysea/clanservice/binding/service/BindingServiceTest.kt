package com.fantasysea.clanservice.binding.service

import com.fantasysea.clanservice.binding.entity.DeviceBinding
import com.fantasysea.clanservice.binding.dto.UpdateBindingRequest
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class BindingServiceTest {
    private lateinit var bindings: DeviceBindingRepository
    private lateinit var devices: DeviceRepository
    private lateinit var sessions: RemoteSessionRepository
    private lateinit var locks: DeviceLockService
    private lateinit var gateway: WsEventGateway
    private lateinit var service: BindingService

    @BeforeEach
    fun setUp() {
        bindings = mock(DeviceBindingRepository::class.java)
        devices = mock(DeviceRepository::class.java)
        sessions = mock(RemoteSessionRepository::class.java)
        locks = mock(DeviceLockService::class.java)
        gateway = mock(WsEventGateway::class.java)
        service = BindingService(
            bindings,
            devices,
            sessions,
            locks,
            gateway,
            AfterCommitExecutor(),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    @Test
    fun `list maps peer summary and live reachability`() {
        val binding = DeviceBinding.of("dev_a", "dev_b", BOUND_AT)
        val peer = device("dev_b", online = true)
        `when`(bindings.findByDeviceLowIdOrDeviceHighId("dev_a", "dev_a")).thenReturn(listOf(binding))
        `when`(devices.findAllById(listOf("dev_b"))).thenReturn(listOf(peer))
        `when`(gateway.isReachable("dev_b")).thenReturn(true)

        val result = service.list("dev_a").single()

        assertEquals("dev_b", result.deviceId)
        assertEquals("设备-dev_b", result.deviceName)
        assertEquals(DevicePlatform.ANDROID, result.platform)
        assertTrue(result.online)
        assertEquals(peer.lastSeenAt, result.lastSeenAt)
        assertEquals(BOUND_AT, result.boundAt)
        assertEquals(peer.createdAt, result.createdAt)
    }

    @Test
    fun `list reports offline when the device row or live connection is missing`() {
        val binding = DeviceBinding.of("dev_a", "dev_b", BOUND_AT)
        val peer = device("dev_b", online = true)
        `when`(bindings.findByDeviceLowIdOrDeviceHighId("dev_a", "dev_a")).thenReturn(listOf(binding))
        `when`(devices.findAllById(listOf("dev_b"))).thenReturn(listOf(peer))
        `when`(gateway.isReachable("dev_b")).thenReturn(false)

        assertFalse(service.list("dev_a").single().online)

        `when`(devices.findAllById(listOf("dev_b"))).thenReturn(emptyList())
        assertTrue(service.list("dev_a").isEmpty())
    }

    @Test
    fun `get normalizes the pair and can return an offline peer`() {
        val binding = DeviceBinding.of("dev_a", "dev_z", BOUND_AT)
        val peer = device("dev_a", online = false)
        `when`(bindings.findPair("dev_a", "dev_z")).thenReturn(binding)
        `when`(devices.findById("dev_a")).thenReturn(Optional.of(peer))

        val result = service.get("dev_z", "dev_a")

        assertEquals("dev_a", result.deviceId)
        assertFalse(result.online)
        assertEquals(BOUND_AT, result.boundAt)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `get rejects self unbound and missing peer bindings with 40201`() {
        val self = assertThrows(BusinessException::class.java) { service.get("dev_a", "dev_a") }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, self.errorCode)
        verifyNoInteractions(bindings)

        `when`(bindings.findPair("dev_a", "dev_z")).thenReturn(null)
        val unbound = assertThrows(BusinessException::class.java) { service.get("dev_a", "dev_z") }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, unbound.errorCode)

        `when`(bindings.findPair("dev_a", "dev_z")).thenReturn(DeviceBinding.of("dev_a", "dev_z", BOUND_AT))
        `when`(devices.findById("dev_z")).thenReturn(Optional.empty())
        val missing = assertThrows(BusinessException::class.java) { service.get("dev_a", "dev_z") }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, missing.errorCode)
    }

    @Test
    fun `rename trims the name updates the peer and returns the binding`() {
        val binding = DeviceBinding.of("dev_b", "dev_z", BOUND_AT)
        val peer = device("dev_z", online = true)
        `when`(bindings.findPair("dev_b", "dev_z")).thenReturn(binding)
        `when`(devices.findById("dev_z")).thenReturn(Optional.of(peer))
        `when`(gateway.isReachable("dev_z")).thenReturn(true)

        val result = service.rename("dev_b", "dev_z", UpdateBindingRequest("  新名字  "))

        assertEquals("新名字", peer.deviceName)
        assertEquals("dev_z", result.deviceId)
        assertEquals("新名字", result.deviceName)
        assertEquals(DevicePlatform.ANDROID, result.platform)
        assertTrue(result.online)
        assertEquals(BOUND_AT, result.boundAt)
    }

    @Test
    fun `rename rejects self blank names unbound and missing peers`() {
        val self = assertThrows(BusinessException::class.java) {
            service.rename("dev_a", "dev_a", UpdateBindingRequest("新名字"))
        }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, self.errorCode)

        val blank = assertThrows(BusinessException::class.java) {
            service.rename("dev_a", "dev_b", UpdateBindingRequest("   "))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, blank.errorCode)
        verifyNoInteractions(bindings, devices, gateway)

        `when`(bindings.findPair("dev_a", "dev_b")).thenReturn(null)
        val unbound = assertThrows(BusinessException::class.java) {
            service.rename("dev_a", "dev_b", UpdateBindingRequest("新名字"))
        }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, unbound.errorCode)

        `when`(bindings.findPair("dev_a", "dev_b")).thenReturn(DeviceBinding.of("dev_a", "dev_b", BOUND_AT))
        `when`(devices.findById("dev_b")).thenReturn(Optional.empty())
        val missing = assertThrows(BusinessException::class.java) {
            service.rename("dev_a", "dev_b", UpdateBindingRequest("新名字"))
        }
        assertEquals(ErrorCode.BINDING_NOT_FOUND, missing.errorCode)
    }

    @Test
    fun `unbind ends only sessions with the peer and publishes after commit`() {
        val caller = device("dev_a")
        val peer = device("dev_b")
        val binding = DeviceBinding.of("dev_a", "dev_b", BOUND_AT)
        val related = session("session_1", "dev_a", "dev_b")
        val unrelated = session("session_2", "dev_a", "dev_c")
        `when`(locks.lockPair("dev_a", "dev_b")).thenReturn(caller to peer)
        `when`(bindings.findPair("dev_a", "dev_b")).thenReturn(binding)
        `when`(sessions.findActiveForDevice("dev_a", RemoteSession.ACTIVE_STATUSES))
            .thenReturn(listOf(related, unrelated))
        `when`(sessions.findByIdForUpdate("session_1")).thenReturn(related)

        service.unbind("dev_a", "dev_b")

        assertEquals(RemoteSessionStatus.ENDED, related.status)
        assertEquals(NOW, related.endedAt)
        assertEquals("UNBOUND", related.failureReason)
        assertEquals(RemoteSessionStatus.REQUESTING, unrelated.status)
        verify(bindings).delete(binding)
        verify(sessions, never()).findByIdForUpdate("session_2")

        val event = ArgumentCaptor.forClass(WsOutboundMessage::class.java)
        verify(gateway).send(
            eq("dev_a") ?: "dev_a",
            event.capture() ?: WsOutboundMessage("unused", timestamp = 0),
        )
        assertEquals("SESSION_ENDED", event.value.type)
        assertEquals("session_1", event.value.sessionId)
        assertEquals(mapOf("reason" to "UNBOUND"), event.value.payload)
        verify(gateway).send(
            eq("dev_b") ?: "dev_b",
            any(WsOutboundMessage::class.java) ?: WsOutboundMessage("unused", timestamp = 0),
        )
    }

    @Test
    fun `unbind unknown binding returns 40201 without deleting or publishing`() {
        `when`(locks.lockPair("dev_a", "dev_b")).thenReturn(device("dev_a") to device("dev_b"))
        `when`(bindings.findPair("dev_a", "dev_b")).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) { service.unbind("dev_a", "dev_b") }

        assertEquals(ErrorCode.BINDING_NOT_FOUND, error.errorCode)
        verify(bindings, never()).delete(any(DeviceBinding::class.java) ?: DeviceBinding.of("dev_a", "dev_b", BOUND_AT))
        verifyNoInteractions(gateway)
    }

    private fun device(id: String, online: Boolean = true) = Device(
        id = id,
        deviceName = "设备-$id",
        platform = DevicePlatform.ANDROID,
        model = "Pixel",
        androidVersion = "16",
        deviceTokenHash = "hash-$id",
        online = online,
        lastSeenAt = NOW.minusSeconds(30),
        createdAt = NOW.minusSeconds(3_600),
    )

    private fun session(id: String, controllerId: String, controlledId: String) = RemoteSession(
        id = id,
        controllerDeviceId = controllerId,
        controlledDeviceId = controlledId,
        status = RemoteSessionStatus.REQUESTING,
        expiresAt = NOW.plusSeconds(60),
        createdAt = NOW,
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val BOUND_AT: Instant = Instant.parse("2025-12-31T00:00:00Z")
    }
}
