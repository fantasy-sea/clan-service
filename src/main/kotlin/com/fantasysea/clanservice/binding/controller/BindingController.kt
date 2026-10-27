package com.fantasysea.clanservice.binding.controller

import com.fantasysea.clanservice.binding.dto.BindingResponse
import com.fantasysea.clanservice.binding.dto.UpdateBindingRequest
import com.fantasysea.clanservice.binding.service.BindingService
import com.fantasysea.clanservice.common.response.ApiResponse
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bindings")
class BindingController(private val bindingService: BindingService) {
    @GetMapping
    fun list(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String): ApiResponse<List<BindingResponse>> =
        ApiResponse.success(bindingService.list(deviceId))

    @GetMapping("/{deviceId}")
    fun get(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) authenticatedDeviceId: String,
        @PathVariable deviceId: String,
    ): ApiResponse<BindingResponse> =
        ApiResponse.success(bindingService.get(authenticatedDeviceId, deviceId))

    @PatchMapping("/{deviceId}")
    fun rename(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) authenticatedDeviceId: String,
        @PathVariable deviceId: String,
        @Valid @RequestBody request: UpdateBindingRequest,
    ): ApiResponse<BindingResponse> =
        ApiResponse.success(bindingService.rename(authenticatedDeviceId, deviceId, request))

    @DeleteMapping("/{deviceId}")
    fun delete(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) authenticatedDeviceId: String,
        @PathVariable deviceId: String,
    ): ApiResponse<Unit> {
        bindingService.unbind(authenticatedDeviceId, deviceId)
        return ApiResponse.success(null)
    }
}
