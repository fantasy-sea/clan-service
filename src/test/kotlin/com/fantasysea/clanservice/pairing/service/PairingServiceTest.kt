package com.fantasysea.clanservice.pairing.service

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
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingCodeRepository
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.pairing.security.PairingCodeGenerator
import com.fantasysea.clanservice.pairing.security.PairingCodeHasher
import com.fantasysea.clanservice.websocket.WsEventGateway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
