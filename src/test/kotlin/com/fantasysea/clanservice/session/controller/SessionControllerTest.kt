package com.fantasysea.clanservice.session.controller

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.session.dto.SessionResponse
import com.fantasysea.clanservice.session.service.SessionService
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SessionController::class)
class SessionControllerTest {
    @Autowired private lateinit var mvc: MockMvc
    @MockitoBean private lateinit var service: SessionService
    @MockitoBean private lateinit var devices: DeviceRepository
    @MockitoBean private lateinit var tokens: DeviceTokenService

    @BeforeEach
    fun authenticate() {
        `when`(tokens.hash("token")).thenReturn("hash")
        `when`(devices.findByDeviceTokenHash("hash")).thenReturn(
            Device("dev_controller", "phone", DevicePlatform.ANDROID, deviceTokenHash = "hash"),
        )
    }

    @Test
    fun `session response freezes all required and nullable fields`() {
        `when`(service.get("dev_controller", "session_01")).thenReturn(response())

        mvc.perform(get("/api/v1/sessions/session_01").header("Authorization", "Bearer token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sessionId").value("session_01"))
            .andExpect(jsonPath("$.data.controllerDeviceId").value("dev_controller"))
            .andExpect(jsonPath("$.data.controlledDeviceId").value("dev_controlled"))
            .andExpect(jsonPath("$.data.status").value("REQUESTING"))
            .andExpect(jsonPath("$.data.expiresAt").exists())
            .andExpect(jsonPath("$.data.createdAt").exists())
            .andExpect(jsonPath("$.data.acceptedAt").value(nullValue()))
            .andExpect(jsonPath("$.data.failureReason").value(nullValue()))
    }

    @Test
    fun `third party access maps to 403`() {
        `when`(service.get("dev_controller", "session_forged"))
            .thenThrow(BusinessException(ErrorCode.SESSION_NOT_ALLOWED))

        mvc.perform(get("/api/v1/sessions/session_forged").header("Authorization", "Bearer token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(40303))
    }

    private fun response() = SessionResponse(
        "session_01", "dev_controller", "dev_controlled", "REQUESTING",
        Instant.parse("2026-01-01T00:01:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
        null, null, null, null, null, null, null,
    )
}
