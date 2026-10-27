package com.fantasysea.clanservice.binding.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * PATCH /api/v1/bindings/{deviceId} 请求：修改已绑定设备展示的设备名称。
 */
data class UpdateBindingRequest(
    @field:NotBlank(message = "deviceName must not be blank")
    @field:Size(max = 128, message = "deviceName must be at most 128 characters")
    val deviceName: String,
)
