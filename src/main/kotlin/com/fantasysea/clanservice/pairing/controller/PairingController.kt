package com.fantasysea.clanservice.pairing.controller

import com.fantasysea.clanservice.common.response.ApiResponse
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import com.fantasysea.clanservice.pairing.dto.PairingCodeResponse
import com.fantasysea.clanservice.pairing.dto.PairingRequestResponse
import com.fantasysea.clanservice.pairing.dto.PairingVerifyResponse
import com.fantasysea.clanservice.pairing.dto.VerifyPairingCodeRequest
import com.fantasysea.clanservice.pairing.service.PairingService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/pairing")
class PairingController(private val pairingService: PairingService) {
    @PostMapping("/codes")
    fun createCode(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String): ApiResponse<PairingCodeResponse> =
        ApiResponse.success(pairingService.createCode(deviceId))

    @PostMapping("/verify")
    fun verify(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String,
        @Valid @RequestBody request: VerifyPairingCodeRequest,
    ): ApiResponse<PairingVerifyResponse> = ApiResponse.success(pairingService.verify(deviceId, request.pairingCode))

    @PostMapping("/requests/{requestId}/accept")
    fun accept(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable requestId: String): ApiResponse<PairingRequestResponse> =
        ApiResponse.success(pairingService.accept(deviceId, requestId))

    @PostMapping("/requests/{requestId}/reject")
    fun reject(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable requestId: String): ApiResponse<PairingRequestResponse> =
        ApiResponse.success(pairingService.reject(deviceId, requestId))

    @PostMapping("/requests/{requestId}/cancel")
    fun cancel(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable requestId: String): ApiResponse<PairingRequestResponse> =
        ApiResponse.success(pairingService.cancel(deviceId, requestId))
}
