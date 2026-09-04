package com.fantasysea.clanservice.device.controller

import com.fantasysea.clanservice.common.response.ApiResponse
import com.fantasysea.clanservice.common.security.AUTHENTICATED_DEVICE_ID_ATTRIBUTE
import com.fantasysea.clanservice.device.dto.DeviceResponse
import com.fantasysea.clanservice.device.dto.RegisterDeviceRequest
import com.fantasysea.clanservice.device.dto.RegisterDeviceResponse
import com.fantasysea.clanservice.device.dto.UpdateDeviceRequest
import com.fantasysea.clanservice.device.service.DeviceService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Device REST API（后端设计 §13-§15）。
 * Controller 只负责 HTTP / DTO；Token 由 DeviceAuthenticationInterceptor 解析，
 * 这里只从 @RequestAttribute 读取认证结果，绝不自行解析 Token。
 */
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController(
    private val deviceService: DeviceService,
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterDeviceRequest): ApiResponse<RegisterDeviceResponse> =
        ApiResponse.success(deviceService.register(request))

    @GetMapping("/me")
    fun me(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String): ApiResponse<DeviceResponse> =
        ApiResponse.success(deviceService.getDevice(deviceId))

    @PatchMapping("/me")
    fun updateMe(
        @RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String,
        @Valid @RequestBody request: UpdateDeviceRequest,
    ): ApiResponse<DeviceResponse> = ApiResponse.success(deviceService.updateDevice(deviceId, request))

    @DeleteMapping("/me")
    fun deleteMe(@RequestAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE) deviceId: String): ApiResponse<Unit> {
        deviceService.deleteDevice(deviceId)
        return ApiResponse.success(null)
    }
}
