package com.fantasysea.clanservice.session.service

import com.fantasysea.clanservice.session.entity.RemoteSessionStatus

/**
 * P4 实时消息的会话上下文：只包含路由/权限判断需要的最小字段。
 *
 * MVP 约定控制权属于 controllerDeviceId；未来支持控制权切换时再落库
 * control_device_id，并让 [controlDeviceId] 返回持久化值。
 */
data class SessionRelay(
    val sessionId: String,
    val controllerDeviceId: String,
    val controlledDeviceId: String,
    val status: RemoteSessionStatus,
) {
    val controlDeviceId: String get() = controllerDeviceId

    fun peerOf(deviceId: String): String = when (deviceId) {
        controllerDeviceId -> controlledDeviceId
        controlledDeviceId -> controllerDeviceId
        else -> throw IllegalArgumentException("Device is not a session participant")
    }
}
