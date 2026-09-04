package com.fantasysea.clanservice.device.dto

/**
 * 注册响应。deviceToken 仅在此响应中返回一次，客户端须安全保存。
 */
data class RegisterDeviceResponse(
    val deviceId: String,
    val deviceToken: String,
)
