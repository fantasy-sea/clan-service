package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.control.service.ControlService
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.signaling.service.SignalingService
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * P4 WebSocket 消息路由：只做 type 分发、公共 ID 校验和 ERROR 映射，
 * 会话状态/权限/转发规则分别在 SessionService、ControlService、SignalingService。
 *
 * 返回值只用于“回给当前连接”的错误；成功转发由各 Service 直接投递对端。
 */
@Component
class WsMessageRouter(
    private val sessions: SessionService,
    private val signaling: SignalingService,
    private val control: ControlService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(WsMessageRouter::class.java)

    fun route(
        deviceId: String,
        type: String,
        requestId: String?,
        sessionId: String?,
        payload: JsonNode,
    ): WsOutboundMessage? {
        // 未知 type 是传输层错误，不要求 sessionId；保持既有 UNSUPPORTED_MESSAGE 语义。
        if (type !in SUPPORTED_TYPES) {
            return errorMessage("UNSUPPORTED_MESSAGE", "Unsupported message type", requestId, sessionId)
        }
        return try {
            val sid = requireSessionId(sessionId)
            requireValidRequestId(requestId)
            when (type) {
                "SESSION_CONNECTED" -> sessions.connect(deviceId, sid)
                "WEBRTC_OFFER" -> signaling.offer(deviceId, requestId, sid, payload)
                "WEBRTC_ANSWER" -> signaling.answer(deviceId, requestId, sid, payload)
                "ICE_CANDIDATE" -> signaling.iceCandidate(deviceId, requestId, sid, payload)
                "PERMISSION_STATUS" -> control.permissionStatus(deviceId, sid, payload)
                "CONTROL" -> control.control(deviceId, requireRequestId(requestId), sid, payload)
                "CONTROL_RESULT" -> control.controlResult(deviceId, requireRequestId(requestId), sid, payload)
                else -> Unit
            }
            null
        } catch (ex: BusinessException) {
            errorMessage(
                code = ex.errorCode.name,
                message = ex.message ?: ex.errorCode.defaultMessage,
                requestId = requestId,
                sessionId = sessionId,
                retryAfterSeconds = ex.retryAfterSeconds,
            )
        } catch (ex: Exception) {
            // 只记录类型/设备/会话和堆栈；异常里的业务文案可能包含客户端输入，绝不回传。
            log.error("Realtime message failed: type={} deviceId={} sessionId={}", type, deviceId, sessionId, ex)
            errorMessage(ErrorCode.INTERNAL_ERROR.name, ErrorCode.INTERNAL_ERROR.defaultMessage, requestId, sessionId)
        }
    }

    private fun requireSessionId(sessionId: String?): String {
        if (sessionId == null || sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid sessionId")
        }
        return sessionId
    }

    private fun requireValidRequestId(requestId: String?) {
        if (requestId != null && (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_LENGTH)) {
            throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid requestId")
        }
    }

    private fun requireRequestId(requestId: String?): String = requestId
        ?: throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid requestId")

    private fun errorMessage(
        code: String,
        message: String,
        requestId: String?,
        sessionId: String?,
        retryAfterSeconds: Long? = null,
    ): WsOutboundMessage {
        val payload = buildMap<String, Any> {
            put("code", code)
            put("message", message)
            if (retryAfterSeconds != null) put("retryAfter", retryAfterSeconds)
        }
        // 超长 ID 本身可能接近整帧上限，错误帧只回带合法范围内的关联 ID。
        return WsOutboundMessage(
            type = "ERROR",
            requestId = requestId?.takeIf { it.length <= MAX_REQUEST_ID_LENGTH },
            sessionId = sessionId?.takeIf { it.length <= MAX_SESSION_ID_LENGTH },
            timestamp = clock.millis(),
            payload = payload,
        )
    }

    companion object {
        const val MAX_SESSION_ID_LENGTH = 64
        const val MAX_REQUEST_ID_LENGTH = 128
        private val SUPPORTED_TYPES = setOf(
            "SESSION_CONNECTED",
            "WEBRTC_OFFER",
            "WEBRTC_ANSWER",
            "ICE_CANDIDATE",
            "PERMISSION_STATUS",
            "CONTROL",
            "CONTROL_RESULT",
        )
    }
}
