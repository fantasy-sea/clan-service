package com.fantasysea.clanservice.control.service

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.control.dto.ControlCommand
import com.fantasysea.clanservice.control.dto.ControlResultPayload
import com.fantasysea.clanservice.control.dto.PermissionStatusPayload
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * P4 设备控制转发。
 *
 * 安全规则（后端设计 §47/§66）：
 * - 身份来自已认证的 WebSocket 连接，不信任 payload 里的 deviceId；
 * - CONTROL 只能由 controlDeviceId（MVP = controllerDeviceId）发出，目标是 controlledDeviceId；
 * - CONTROL_RESULT / PERMISSION_STATUS 只能由被控端回传；
 * - 每条消息都重新校验 Session 状态，不做离线缓存或事件重放。
 */
@Service
class ControlService(
    private val sessions: SessionService,
    private val gateway: WsEventGateway,
    private val rateLimiter: InMemoryRateLimiter,
    private val clock: Clock,
) {
    fun control(deviceId: String, requestId: String, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTED_ONLY)
        if (deviceId != relay.controlDeviceId) throw BusinessException(ErrorCode.CONTROL_NOT_ALLOWED)
        val command = ControlCommand.parse(payload)
        send(relay.controlledDeviceId, "CONTROL", requestId, sessionId, command.toPayload())
    }

    fun controlResult(deviceId: String, requestId: String, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTED_ONLY)
        if (deviceId != relay.controlledDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        val result = ControlResultPayload.parse(payload)
        send(relay.controlDeviceId, "CONTROL_RESULT", requestId, sessionId, result.toPayload())
    }

    fun permissionStatus(deviceId: String, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTING_OR_CONNECTED)
        if (deviceId != relay.controlledDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        val status = PermissionStatusPayload.parse(payload)
        send(relay.controllerDeviceId, "PERMISSION_STATUS", null, sessionId, status.toPayload())
    }

    private fun send(targetDeviceId: String, type: String, requestId: String?, sessionId: String, payload: Any) {
        val delivered = gateway.send(
            targetDeviceId,
            WsOutboundMessage(type = type, requestId = requestId, sessionId = sessionId, timestamp = clock.millis(), payload = payload),
        )
        if (!delivered) throw BusinessException(ErrorCode.DEVICE_OFFLINE)
    }

    companion object {
        const val NAMESPACE = "ws-control"
        const val LIMIT = 600
        val WINDOW: Duration = Duration.ofMinutes(1)
        private val CONNECTED_ONLY = setOf(RemoteSessionStatus.CONNECTED)
        private val CONNECTING_OR_CONNECTED = setOf(RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }
}
