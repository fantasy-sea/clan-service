package com.fantasysea.clanservice.device.controller

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.dto.DeviceResponse
import com.fantasysea.clanservice.device.dto.RegisterDeviceRequest
import com.fantasysea.clanservice.device.dto.RegisterDeviceResponse
import com.fantasysea.clanservice.device.dto.UpdateDeviceRequest
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.device.service.DeviceService
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(DeviceController::class)
class DeviceControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var deviceService: DeviceService

    // 拦截器依赖（切片可能加载 WebMvcConfig/Interceptor，提供 mock 以便上下文启动）。
    @MockitoBean
    private lateinit var deviceRepository: DeviceRepository

    @MockitoBean
    private lateinit var deviceTokenService: DeviceTokenService

    private val authenticatedDeviceId = "dev_00000000000000000000000001"

    private val deviceResponse = DeviceResponse(
        deviceId = authenticatedDeviceId,
        deviceName = "爸爸的手机",
        platform = DevicePlatform.ANDROID,
        model = "Pixel 8",
        androidVersion = "16",
        online = true,
        lastSeenAt = null,
        createdAt = null,
    )

    @BeforeEach
    fun setUp() {
        `when`(deviceTokenService.hash(org.mockito.ArgumentMatchers.anyString())).thenReturn("some-hash")
        `when`(deviceRepository.findByDeviceTokenHash("some-hash")).thenReturn(
            Device(id = authenticatedDeviceId, deviceName = "爸爸的手机", platform = DevicePlatform.ANDROID, deviceTokenHash = "some-hash"),
        )
    }

    @Test
    fun `register returns success envelope`() {
        `when`(
            deviceService.register(
                RegisterDeviceRequest(deviceName = "爸爸的手机", platform = "ANDROID", model = "Pixel 8", androidVersion = "16"),
            ),
        ).thenReturn(RegisterDeviceResponse("dev_01", "raw-token"))

        mockMvc.perform(
            post("/api/v1/devices/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\":\"爸爸的手机\",\"platform\":\"ANDROID\",\"model\":\"Pixel 8\",\"androidVersion\":\"16\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data.deviceId").value("dev_01"))
            .andExpect(jsonPath("$.data.deviceToken").value("raw-token"))
    }

    @Test
    fun `register rejects blank deviceName with 40001`() {
        mockMvc.perform(
            post("/api/v1/devices/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\":\"  \",\"platform\":\"ANDROID\"}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }

    @Test
    fun `register with unknown platform returns 40001 envelope`() {
        `when`(
            deviceService.register(RegisterDeviceRequest(deviceName = "手机", platform = "WINDOWS")),
        ).thenThrow(BusinessException(ErrorCode.INVALID_PARAMETER, "Unknown platform: WINDOWS"))

        mockMvc.perform(
            post("/api/v1/devices/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\":\"手机\",\"platform\":\"WINDOWS\"}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(40001))
    }

    @Test
    fun `me returns success envelope`() {
        `when`(deviceService.getDevice(authenticatedDeviceId)).thenReturn(deviceResponse)

        mockMvc.perform(
            get("/api/v1/devices/me")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").value(authenticatedDeviceId))
            .andExpect(jsonPath("$.data.deviceName").value("爸爸的手机"))
            .andExpect(jsonPath("$.data.deviceToken").doesNotExist())
    }

    @Test
    fun `me with unknown device returns 40003 envelope`() {
        `when`(deviceService.getDevice(authenticatedDeviceId))
            .thenThrow(BusinessException(ErrorCode.DEVICE_NOT_FOUND))

        mockMvc.perform(
            get("/api/v1/devices/me")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(40003))
    }

    @Test
    fun `unexpected exception returns 50001 envelope`() {
        `when`(deviceService.getDevice(authenticatedDeviceId)).thenThrow(RuntimeException("boom"))

        mockMvc.perform(
            get("/api/v1/devices/me")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value(50001))
            .andExpect(jsonPath("$.message").value("Internal server error"))
    }

    @Test
    fun `patch me updates device`() {
        `when`(deviceService.updateDevice(authenticatedDeviceId, UpdateDeviceRequest(deviceName = "新名字")))
            .thenReturn(deviceResponse.copy(deviceName = "新名字"))

        mockMvc.perform(
            patch("/api/v1/devices/me")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\":\"新名字\"}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceName").value("新名字"))
    }

    @Test
    fun `delete me returns success envelope with null data`() {
        mockMvc.perform(
            delete("/api/v1/devices/me")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }
}
