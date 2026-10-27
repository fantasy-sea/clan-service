package com.fantasysea.clanservice.binding.controller

import com.fantasysea.clanservice.binding.dto.BindingResponse
import com.fantasysea.clanservice.binding.dto.UpdateBindingRequest
import com.fantasysea.clanservice.binding.service.BindingService
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import java.time.Instant
import org.hamcrest.Matchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(BindingController::class)
class BindingControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var bindingService: BindingService

    // 拦截器依赖（切片可能加载 WebMvcConfig/Interceptor，提供 mock 以便上下文启动）。
    @MockitoBean
    private lateinit var deviceRepository: DeviceRepository

    @MockitoBean
    private lateinit var deviceTokenService: DeviceTokenService

    private val authenticatedDeviceId = "dev_00000000000000000000000001"
    private val peerDeviceId = "dev_00000000000000000000000002"
    private val boundAt = Instant.parse("2026-01-01T00:00:00Z")
    private val createdAt = Instant.parse("2025-12-01T00:00:00Z")

    private val bindingResponse = BindingResponse(
        deviceId = peerDeviceId,
        deviceName = "妈妈的手机",
        platform = DevicePlatform.ANDROID,
        model = "Pixel 8",
        androidVersion = "16",
        online = true,
        lastSeenAt = Instant.parse("2026-01-02T00:00:00Z"),
        boundAt = boundAt,
        createdAt = createdAt,
    )

    @BeforeEach
    fun setUp() {
        `when`(deviceTokenService.hash(anyString())).thenReturn("some-hash")
        `when`(deviceRepository.findByDeviceTokenHash("some-hash")).thenReturn(
            Device(
                id = authenticatedDeviceId,
                deviceName = "爸爸的手机",
                platform = DevicePlatform.ANDROID,
                deviceTokenHash = "some-hash",
            ),
        )
    }

    @Test
    fun `list returns success envelope with binding fields`() {
        `when`(bindingService.list(authenticatedDeviceId)).thenReturn(listOf(bindingResponse))

        mockMvc.perform(
            get("/api/v1/bindings")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.message").value("success"))
            .andExpect(jsonPath("$.data[0].deviceId").value(peerDeviceId))
            .andExpect(jsonPath("$.data[0].deviceName").value("妈妈的手机"))
            .andExpect(jsonPath("$.data[0].platform").value("ANDROID"))
            .andExpect(jsonPath("$.data[0].online").value(true))
            .andExpect(jsonPath("$.data[0].boundAt").exists())
            .andExpect(jsonPath("$.data[0].createdAt").exists())
            .andExpect(jsonPath("$.data[0].deviceToken").doesNotExist())
    }

    @Test
    fun `get returns the single binding for the authenticated device`() {
        `when`(bindingService.get(authenticatedDeviceId, peerDeviceId)).thenReturn(bindingResponse)

        mockMvc.perform(
            get("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").value(peerDeviceId))
            .andExpect(jsonPath("$.data.createdAt").exists())
            .andExpect(jsonPath("$.data.deviceTokenHash").doesNotExist())
    }

    @Test
    fun `get unknown or unbound device returns 40201 envelope`() {
        `when`(bindingService.get(authenticatedDeviceId, peerDeviceId))
            .thenThrow(BusinessException(ErrorCode.BINDING_NOT_FOUND))

        mockMvc.perform(
            get("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(40201))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }

    @Test
    fun `patch binding renames the peer device`() {
        `when`(bindingService.rename(authenticatedDeviceId, peerDeviceId, UpdateBindingRequest("新名字")))
            .thenReturn(bindingResponse.copy(deviceName = "新名字"))

        mockMvc.perform(
            patch("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"deviceName":"新名字"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.deviceId").value(peerDeviceId))
            .andExpect(jsonPath("$.data.deviceName").value("新名字"))
            .andExpect(jsonPath("$.data.deviceTokenHash").doesNotExist())
    }

    @Test
    fun `patch blank deviceName returns 40001 envelope`() {
        mockMvc.perform(
            patch("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"deviceName":"  "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(40001))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }

    @Test
    fun `patch unknown binding returns 40201 envelope`() {
        `when`(bindingService.rename(authenticatedDeviceId, peerDeviceId, UpdateBindingRequest("新名字")))
            .thenThrow(BusinessException(ErrorCode.BINDING_NOT_FOUND))

        mockMvc.perform(
            patch("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"deviceName":"新名字"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(40201))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }

    @Test
    fun `delete returns success envelope with null data`() {
        mockMvc.perform(
            delete("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }

    @Test
    fun `delete unknown binding returns 40201 envelope`() {
        doThrow(BusinessException(ErrorCode.BINDING_NOT_FOUND))
            .`when`(bindingService).unbind(authenticatedDeviceId, peerDeviceId)

        mockMvc.perform(
            delete("/api/v1/bindings/$peerDeviceId")
                .header("Authorization", "Bearer valid-token")
                .requestAttr(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, authenticatedDeviceId),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(40201))
            .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
    }
}
