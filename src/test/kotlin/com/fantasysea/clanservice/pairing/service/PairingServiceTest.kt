package com.fantasysea.clanservice.pairing.service

import com.fantasysea.clanservice.binding.entity.DeviceBinding
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.pairing.entity.PairingCode
import com.fantasysea.clanservice.pairing.entity.PairingRequest
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingCodeRepository
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.pairing.security.PairingCodeGenerator
import com.fantasysea.clanservice.pairing.security.PairingCodeHasher
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

class PairingServiceTest {
    private lateinit var codes: PairingCodeRepository
    private lateinit var requests: PairingRequestRepository
    private lateinit var bindings: DeviceBindingRepository
    private lateinit var locks: DeviceLockService
    private lateinit var hasher: PairingCodeHasher
    private lateinit var gateway: WsEventGateway
    private lateinit var service: PairingService

    @BeforeEach
    fun setUp() {
        codes = mock(PairingCodeRepository::class.java)
        requests = mock(PairingRequestRepository::class.java)
        bindings = mock(DeviceBindingRepository::class.java)
        locks = mock(DeviceLockService::class.java)
        hasher = mock(PairingCodeHasher::class.java)
        gateway = mock(WsEventGateway::class.java)
        val ulid = mock(UlidGenerator::class.java)
        `when`(ulid.ulid()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        service = PairingService(
            codes, requests, bindings, locks, hasher, mock(PairingCodeGenerator::class.java), ulid,
            gateway, AfterCommitExecutor(), InMemoryRateLimiter(CLOCK), mock(DeliveryCompensator::class.java), CLOCK,
        )
        `when`(hasher.hash("000042")).thenReturn("hash")
    }

    @Test
    fun `self pairing does not consume the code`() {
        val code = code("dev_requester")
        `when`(codes.findCodeByHash("hash")).thenReturn(code)

        val error = assertThrows(BusinessException::class.java) { service.verify("dev_requester", "000042") }

        assertEquals(ErrorCode.SELF_PAIRING, error.errorCode)
        assertNull(code.consumedAt)
        verify(codes, never()).findByCodeHashForUpdate("hash")
    }

    @Test
    fun `offline target leaves a valid code reusable`() {
        val code = code("dev_target")
        `when`(codes.findCodeByHash("hash")).thenReturn(code)
        `when`(codes.findByCodeHashForUpdate("hash")).thenReturn(code)
        `when`(locks.lockPair("dev_requester", "dev_target")).thenReturn(device("dev_requester") to device("dev_target"))
        `when`(gateway.isReachable("dev_requester")).thenReturn(true)
        `when`(gateway.isReachable("dev_target")).thenReturn(false)

        val error = assertThrows(BusinessException::class.java) { service.verify("dev_requester", "000042") }

        assertEquals(ErrorCode.DEVICE_OFFLINE, error.errorCode)
        assertNull(code.consumedAt)
        verify(requests, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `requester cancels a pending request and both sides receive rejected result`() {
        val request = pending()
        stubCancelRequest(request)

        val response = service.cancel("dev_requester", request.id)

        assertEquals("REJECTED", response.status)
        assertEquals(PairingRequestStatus.REJECTED, request.status)
        assertNotNull(request.handledAt)
        verify(bindings, never()).save(any(DeviceBinding::class.java))
        assertResultEvent("dev_requester", "REJECTED")
        assertResultEvent("dev_target", "REJECTED")
    }

    @Test
    fun `target cannot cancel a request`() {
        val request = pending()
        stubCancelRequest(request)

        val error = assertThrows(BusinessException::class.java) { service.cancel("dev_target", request.id) }

        assertEquals(ErrorCode.PAIRING_REQUEST_NOT_ALLOWED, error.errorCode)
        assertEquals(PairingRequestStatus.PENDING, request.status)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `already handled request cannot be cancelled`() {
        val request = pending(status = PairingRequestStatus.ACCEPTED)
        stubCancelRequest(request)

        val error = assertThrows(BusinessException::class.java) { service.cancel("dev_requester", request.id) }

        assertEquals(ErrorCode.PAIRING_REQUEST_ALREADY_HANDLED, error.errorCode)
        verifyNoInteractions(gateway)
    }

    @Test
    fun `cancel after deadline expires the request and reports expiry`() {
        val request = pending(expiresAt = NOW)
        stubCancelRequest(request)

        val error = assertThrows(BusinessException::class.java) { service.cancel("dev_requester", request.id) }

        assertEquals(ErrorCode.PAIRING_REQUEST_EXPIRED, error.errorCode)
        assertEquals(PairingRequestStatus.EXPIRED, request.status)
        assertResultEvent("dev_requester", "EXPIRED")
    }

    private fun pending(
        status: PairingRequestStatus = PairingRequestStatus.PENDING,
        expiresAt: Instant = NOW.plusSeconds(60),
    ) = PairingRequest(
        id = "pairreq_01",
        requesterDeviceId = "dev_requester",
        targetDeviceId = "dev_target",
        status = status,
        expiresAt = expiresAt,
        createdAt = NOW.minusSeconds(1),
    )

    private fun stubCancelRequest(request: PairingRequest) {
        `when`(requests.findById(request.id)).thenReturn(Optional.of(request))
        `when`(locks.lockPair(request.requesterDeviceId, request.targetDeviceId))
            .thenReturn(device(request.requesterDeviceId) to device(request.targetDeviceId))
        `when`(requests.findByIdForUpdate(request.id)).thenReturn(request)
    }

    private fun assertResultEvent(deviceId: String, result: String) {
        val event = ArgumentCaptor.forClass(WsOutboundMessage::class.java)
        verify(gateway).send(
            eq(deviceId) ?: deviceId,
            event.capture() ?: WsOutboundMessage("unused", timestamp = 0),
        )
        assertEquals("PAIRING_RESULT", event.value.type)
        assertEquals(result, (event.value.payload as Map<*, *>)["result"])
    }

    private fun code(target: String) = PairingCode(
        deviceId = target,
        codeHash = "hash",
        expiresAt = NOW.plusSeconds(60),
        createdAt = NOW,
    )

    private fun device(id: String) = Device(id, id, DevicePlatform.ANDROID, deviceTokenHash = "hash-$id")

    companion object {
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
