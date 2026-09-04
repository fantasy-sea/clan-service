package com.fantasysea.clanservice.device.dto

import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import java.time.Instant

/**
 * 设备信息响应。绝不包含 deviceToken / deviceTokenHash（后端设计2 Entity 规则）。
 */
data class DeviceResponse(
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val model: String?,
    val androidVersion: String?,
    val online: Boolean,
    val lastSeenAt: Instant?,
    val createdAt: Instant?,
) {
    companion object {
        fun from(device: Device): DeviceResponse = DeviceResponse(
            deviceId = device.id,
            deviceName = device.deviceName,
            platform = device.platform,
            model = device.model,
            androidVersion = device.androidVersion,
            online = device.online,
            lastSeenAt = device.lastSeenAt,
            createdAt = device.createdAt,
        )
    }
}
