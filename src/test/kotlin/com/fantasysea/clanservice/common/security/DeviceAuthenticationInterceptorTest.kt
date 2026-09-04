package com.fantasysea.clanservice.common.security

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class DeviceAuthenticationInterceptorTest {

    private lateinit var deviceRepository: DeviceRepository
    private lateinit var deviceTokenService: DeviceTokenService
    private lateinit var interceptor: DeviceAuthenticationInterceptor
    private lateinit var request: MockHttpServletRequest
    private lateinit var response: MockHttpServletResponse

    @BeforeEach
    fun setUp() {
        deviceRepository = mock(DeviceRepository::class.java)
        deviceTokenService = mock(DeviceTokenService::class.java)
        interceptor = DeviceAuthenticationInterceptor(deviceRepository, deviceTokenService)
        request = MockHttpServletRequest()
        response = MockHttpServletResponse()
    }

    @Test
    fun `rejects missing authorization header`() {
        val ex = assertThrows(BusinessException::class.java) {
            interceptor.preHandle(request, response, Any())
        }
        assertEquals(ErrorCode.INVALID_DEVICE_TOKEN, ex.errorCode)
    }

    @Test
    fun `rejects malformed header`() {
        request.addHeader("Authorization", "Basic abc")
        val ex = assertThrows(BusinessException::class.java) {
            interceptor.preHandle(request, response, Any())
        }
        assertEquals(ErrorCode.INVALID_DEVICE_TOKEN, ex.errorCode)
    }

    @Test
    fun `rejects empty bearer token`() {
        request.addHeader("Authorization", "Bearer ")
        assertThrows(BusinessException::class.java) {
            interceptor.preHandle(request, response, Any())
        }
    }

    @Test
    fun `rejects unknown token`() {
        request.addHeader("Authorization", "Bearer unknown-token")
        `when`(deviceTokenService.hash("unknown-token")).thenReturn("deadbeef")
        `when`(deviceRepository.findByDeviceTokenHash("deadbeef")).thenReturn(null)
        val ex = assertThrows(BusinessException::class.java) {
            interceptor.preHandle(request, response, Any())
        }
        assertEquals(ErrorCode.INVALID_DEVICE_TOKEN, ex.errorCode)
        assertNull(request.getAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE))
    }

    @Test
    fun `stores deviceId attribute for valid token`() {
        request.addHeader("Authorization", "bearer valid-token")
        `when`(deviceTokenService.hash("valid-token")).thenReturn("hash-1")
        `when`(deviceRepository.findByDeviceTokenHash("hash-1")).thenReturn(
            Device(id = "dev_01", deviceName = "爸爸的手机", platform = DevicePlatform.ANDROID, deviceTokenHash = "hash-1"),
        )

        val result = interceptor.preHandle(request, response, Any())

        assertTrue(result)
        assertEquals("dev_01", request.getAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE))
    }
}
