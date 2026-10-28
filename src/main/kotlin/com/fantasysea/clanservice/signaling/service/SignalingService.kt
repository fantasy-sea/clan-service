package com.fantasysea.clanservice.signaling.service

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.signaling.dto.IceCandidatePayload
import com.fantasysea.clanservice.signaling.dto.SdpPayload
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

/**
 * P4 WebRTC 信令转发。
 *
 * 服务端不解析、不保存 SDP/ICE，只校验会话状态、方向、大小后转发给对端；
 * 视频媒体由两端 WebRTC（必要时经独立 TURN）直接传输。
 */
@Service
class SignalingService(
    private val sessions: SessionService,
    private val gateway: WsEventGateway,
    private val rateLimiter: InMemoryRateLimiter,
    private val clock: Clock,
) {
    fun offer(deviceId: String, requestId: String?, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTING_OR_CONNECTED)
        if (deviceId != relay.controllerDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        val sdp = SdpPayload.parse(payload)
        send(relay.controlledDeviceId, "WEBRTC_OFFER", requestId, sessionId, sdp.toPayload())
    }

    fun answer(deviceId: String, requestId: String?, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTING_OR_CONNECTED)
        if (deviceId != relay.controlledDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        val sdp = SdpPayload.parse(payload)
        send(relay.controllerDeviceId, "WEBRTC_ANSWER", requestId, sessionId, sdp.toPayload())
    }

    fun iceCandidate(deviceId: String, requestId: String?, sessionId: String, payload: JsonNode) {
        rateLimiter.check(NAMESPACE, deviceId, LIMIT, WINDOW)
        val relay = sessions.requireRelay(deviceId, sessionId, CONNECTING_OR_CONNECTED)
        val candidate = IceCandidatePayload.parse(payload)
        send(relay.peerOf(deviceId), "ICE_CANDIDATE", requestId, sessionId, candidate.toPayload())
    }

    private fun send(targetDeviceId: String, type: String, requestId: String?, sessionId: String, payload: Any) {
        val delivered = gateway.send(
            targetDeviceId,
            WsOutboundMessage(type = type, requestId = requestId, sessionId = sessionId, timestamp = clock.millis(), payload = payload),
        )
        if (!delivered) throw BusinessException(ErrorCode.DEVICE_OFFLINE)
    }

    companion object {
        const val NAMESPACE = "ws-signaling"
        const val LIMIT = 120
        val WINDOW: Duration = Duration.ofMinutes(1)
        private val CONNECTING_OR_CONNECTED = setOf(RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }
}
