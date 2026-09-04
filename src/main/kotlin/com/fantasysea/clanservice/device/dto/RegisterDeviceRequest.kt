package com.fantasysea.clanservice.device.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterDeviceRequest(
    @field:NotBlank(message = "deviceName must not be blank")
    @field:Size(max = 128, message = "deviceName must be at most 128 characters")
    val deviceName: String,

    @field:NotBlank(message = "platform must not be blank")
    @field:Size(max = 32, message = "platform must be at most 32 characters")
    val platform: String,

    @field:Size(max = 128, message = "model must be at most 128 characters")
    val model: String? = null,

    @field:Size(max = 64, message = "androidVersion must be at most 64 characters")
    val androidVersion: String? = null,
)
