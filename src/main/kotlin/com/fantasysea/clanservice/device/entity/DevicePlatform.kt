package com.fantasysea.clanservice.device.entity

/**
 * 设备平台。Device 只是设备，不携带角色；角色属于 remote_sessions（后端设计 §5）。
 */
enum class DevicePlatform {
    ANDROID,
    IOS,
}
