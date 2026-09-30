package com.fantasysea.clanservice.pairing.controller

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.pairing.dto.PairingCodeResponse
import com.fantasysea.clanservice.pairing.dto.PairingRequestResponse
import com.fantasysea.clanservice.pairing.service.PairingService
import java.time.Instant
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(PairingController::class)
class PairingControllerTest {
    @Autowired private lateinit var mvc: MockMvc
    @MockitoBean private lateinit var pairingService: PairingService
    @MockitoBean private lateinit var devices: DeviceRepository
    @MockitoBean private lateinit var tokens: DeviceTokenService

    @BeforeEach
    fun authenticate() {
        `when`(tokens.hash("token")).thenReturn("hash")
        `when`(devices.findByDeviceTokenHash("hash")).thenReturn(
            Device(DEVICE_ID, "phone", DevicePlatform.ANDROID, deviceTokenHash = "hash"),
        )
    }

    @Test
    fun `create code keeps HTTP 200 envelope and ISO instant`() {
        `when`(pairingService.createCode(DEVICE_ID)).thenReturn(PairingCodeResponse("000042", EXPIRES))

        mvc.perform(post("/api/v1/pairing/codes").header("Authorization", "Bearer token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.pairingCode").value("000042"))
            .andExpect(jsonPath("$.data.expiresAt").value("2026-01-01T00:01:00Z"))
    }

    @Test
    fun `expired request uses 410 and stable envelope`() {
        `when`(pairingService.accept(DEVICE_ID, "pairreq_01"))
            .thenThrow(BusinessException(ErrorCode.PAIRING_REQUEST_EXPIRED))

        mvc.perform(post("/api/v1/pairing/requests/pairreq_01/accept").header("Authorization", "Bearer token"))
            .andExpect(status().isGone)
            .andExpect(jsonPath("$.code").value(40106))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `verify rejects a non six digit code before service`() {
        mvc.perform(
            post("/api/v1/pairing/verify")
                .header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pairingCode\":\"42\"}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(40001))
    }

    companion object {
        const val DEVICE_ID = "dev_01"
        val EXPIRES: Instant = Instant.parse("2026-01-01T00:01:00Z")
    }
}
