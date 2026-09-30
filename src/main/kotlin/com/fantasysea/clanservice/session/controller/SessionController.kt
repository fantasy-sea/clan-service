package com.fantasysea.clanservice.session.controller

import com.fantasysea.clanservice.common.response.ApiResponse
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import com.fantasysea.clanservice.session.dto.CreateSessionRequest
import com.fantasysea.clanservice.session.dto.SessionResponse
import com.fantasysea.clanservice.session.service.SessionService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/sessions")
class SessionController(private val sessionService: SessionService) {
    @PostMapping
    fun create(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String,
        @Valid @RequestBody request: CreateSessionRequest,
    ): ApiResponse<SessionResponse> = ApiResponse.success(sessionService.create(deviceId, request.targetDeviceId))

    @GetMapping("/{sessionId}")
    fun get(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable sessionId: String): ApiResponse<SessionResponse> =
        ApiResponse.success(sessionService.get(deviceId, sessionId))

    @PostMapping("/{sessionId}/accept")
    fun accept(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable sessionId: String): ApiResponse<SessionResponse> =
        ApiResponse.success(sessionService.accept(deviceId, sessionId))

    @PostMapping("/{sessionId}/reject")
    fun reject(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable sessionId: String): ApiResponse<SessionResponse> =
        ApiResponse.success(sessionService.reject(deviceId, sessionId))

    @PostMapping("/{sessionId}/end")
    fun end(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String, @PathVariable sessionId: String): ApiResponse<SessionResponse> =
        ApiResponse.success(sessionService.end(deviceId, sessionId))
}
