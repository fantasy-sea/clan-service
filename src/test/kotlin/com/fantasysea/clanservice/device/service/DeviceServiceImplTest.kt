package com.fantasysea.clanservice.device.service

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.device.dto.RegisterDeviceRequest
import com.fantasysea.clanservice.device.dto.UpdateDeviceRequest
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DeviceServiceImplTest {

    private lateinit var deviceRepository: DeviceRepository
    private lateinit var deviceTokenService: DeviceTokenService
    private lateinit var ulidGenerator: UlidGenerator
    private lateinit var deviceService: DeviceServiceImpl

    @BeforeEach
    fun setUp() {
        deviceRepository = mock(DeviceRepository::class.java)
        deviceTokenService = mock(DeviceTokenService::class.java)
        ulidGenerator = mock(UlidGenerator::class.java)
        deviceService = DeviceServiceImpl(deviceRepository, deviceTokenService, ulidGenerator)
    }

    @Test
    fun `register stores hash and returns raw token once`() {
        `when`(ulidGenerator.ulid()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        `when`(deviceTokenService.generate()).thenReturn("raw-token-abc")
        `when`(deviceTokenService.hash("raw-token-abc")).thenReturn("sha256-hex-hash")
        `when`(deviceRepository.save(org.mockito.ArgumentMatchers.any(Device::class.java)))
            .thenAnswer { invocation -> invocation.getArgument<Device>(0) }

        val response = deviceService.register(
            RegisterDeviceRequest(deviceName = "爸爸的手机", platform = "ANDROID", model = "Pixel 8", androidVersion = "16"),
        )

        assertEquals("dev_01ARZ3NDEKTSV4RRFFQ69G5FAV", response.deviceId)
        assertEquals("raw-token-abc", response.deviceToken)

        val captor = ArgumentCaptor.forClass(Device::class.java)
        verify(deviceRepository).save(captor.capture())
        val saved = captor.value
        assertEquals("sha256-hex-hash", saved.deviceTokenHash)
        assertEquals(DevicePlatform.ANDROID, saved.platform)
        assertFalse(saved.online)
    }

    @Test
    fun `register rejects unknown platform`() {
        val ex = assertThrows(BusinessException::class.java) {
            deviceService.register(RegisterDeviceRequest(deviceName = "手机", platform = "WINDOWS"))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, ex.errorCode)
    }

    @Test
    fun `getDevice throws device not found`() {
        `when`(deviceRepository.findById("dev_missing")).thenReturn(Optional.empty())
        val ex = assertThrows(BusinessException::class.java) {
            deviceService.getDevice("dev_missing")
        }
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `updateDevice applies partial patch`() {
        val device = Device(id = "dev_1", deviceName = "old name", platform = DevicePlatform.ANDROID, deviceTokenHash = "h")
        `when`(deviceRepository.findById("dev_1")).thenReturn(Optional.of(device))

        val result = deviceService.updateDevice("dev_1", UpdateDeviceRequest(deviceName = "new name"))

        assertEquals("new name", result.deviceName)
        assertEquals("dev_1", result.deviceId)
    }

    @Test
    fun `updateDevice rejects empty patch`() {
        val ex = assertThrows(BusinessException::class.java) {
            deviceService.updateDevice("dev_1", UpdateDeviceRequest())
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, ex.errorCode)
    }

    @Test
    fun `updateDevice rejects unknown platform`() {
        val device = Device(id = "dev_1", deviceName = "old name", platform = DevicePlatform.ANDROID, deviceTokenHash = "h")
        `when`(deviceRepository.findById("dev_1")).thenReturn(Optional.of(device))

        val ex = assertThrows(BusinessException::class.java) {
            deviceService.updateDevice("dev_1", UpdateDeviceRequest(platform = "WINDOWS"))
        }
        assertEquals(ErrorCode.INVALID_PARAMETER, ex.errorCode)
    }

    @Test
    fun `deleteDevice removes device`() {
        val device = Device(id = "dev_1", deviceName = "old name", platform = DevicePlatform.ANDROID, deviceTokenHash = "h")
        `when`(deviceRepository.findById("dev_1")).thenReturn(Optional.of(device))

        deviceService.deleteDevice("dev_1")

        verify(deviceRepository).delete(device)
    }

    @Test
    fun `deleteDevice throws when missing`() {
        `when`(deviceRepository.findById("dev_missing")).thenReturn(Optional.empty())
        val ex = assertThrows(BusinessException::class.java) {
            deviceService.deleteDevice("dev_missing")
        }
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `markOnline sets online and lastSeen`() {
        val device = Device(id = "dev_1", deviceName = "phone", platform = DevicePlatform.ANDROID, deviceTokenHash = "h")
        `when`(deviceRepository.findById("dev_1")).thenReturn(Optional.of(device))

        deviceService.markOnline("dev_1")

        org.junit.jupiter.api.Assertions.assertTrue(device.online)
        org.junit.jupiter.api.Assertions.assertNotNull(device.lastSeenAt)
    }
}
