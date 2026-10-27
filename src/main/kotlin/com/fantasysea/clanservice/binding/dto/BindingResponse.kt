package com.fantasysea.clanservice.binding.dto

import com.fantasysea.clanservice.device.entity.DevicePlatform
import java.time.Instant

data class BindingResponse(
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val model: String?,
    val androidVersion: String?,
    val online: Boolean,
    val lastSeenAt: Instant?,
    val boundAt: Instant,
    val createdAt: Instant?,
)

data class DeviceSummary(
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val model: String?,
    val androidVersion: String?,
)
