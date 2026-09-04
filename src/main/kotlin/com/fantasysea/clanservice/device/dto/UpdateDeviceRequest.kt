package com.fantasysea.clanservice.device.dto

import jakarta.validation.constraints.Size

/**
 * PATCH /api/v1/devices/me 请求：null 表示不修改该字段。
 */
data class UpdateDeviceRequest(
    @field:Size(min = 1, max = 128, message = "deviceName must be between 1 and 128 characters")
    val deviceName: String? = null,

    @field:Size(min = 1, max = 32, message = "platform must be at most 32 characters")
    val platform: String? = null,

    @field:Size(max = 128, message = "model must be at most 128 characters")
    val model: String? = null,

    @field:Size(max = 64, message = "androidVersion must be at most 64 characters")
    val androidVersion: String? = null,
)
