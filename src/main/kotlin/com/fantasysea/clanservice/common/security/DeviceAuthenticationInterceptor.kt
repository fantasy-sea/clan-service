package com.fantasysea.clanservice.common.security

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.device.repository.DeviceRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** 认证成功后写入 request attribute 的 deviceId 键名。 */
const val AUTHENTICATED_DEVICE_ID_ATTRIBUTE = "deviceId"

/**
 * 解析 Authorization: Bearer <deviceToken>，哈希后查库，把 deviceId 写入 request attribute。
 * 身份只来自认证头，绝不信任请求体中的任何 deviceId（后端设计 §65）。
 * Controller 不解析 Token，只从 @RequestAttribute 读取认证结果。
 */
@Component
class DeviceAuthenticationInterceptor(
    private val deviceRepository: DeviceRepository,
    private val deviceTokenService: DeviceTokenService,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?: throw BusinessException(ErrorCode.INVALID_DEVICE_TOKEN, "Missing Authorization header")
        val rawToken = parseBearerToken(header)
            ?: throw BusinessException(ErrorCode.INVALID_DEVICE_TOKEN, "Malformed Authorization header")
        val device = deviceRepository.findByDeviceTokenHash(deviceTokenService.hash(rawToken))
            ?: throw BusinessException(ErrorCode.INVALID_DEVICE_TOKEN)
        request.setAttribute(AUTHENTICATED_DEVICE_ID_ATTRIBUTE, device.id)
        return true
    }

    private fun parseBearerToken(header: String): String? {
        val parts = header.trim().split(" ", limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true) || parts[1].isBlank()) return null
        return parts[1].trim()
    }
}
