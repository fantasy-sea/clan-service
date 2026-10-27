package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.device.repository.DeviceRepository
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper

@Component
class ClanWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val deviceRepository: DeviceRepository,
    private val tokenService: DeviceTokenService,
    private val registry: WebSocketConnectionRegistry,
    private val presenceService: PresenceService,
    private val rateLimiter: InMemoryRateLimiter,
    private val scheduler: TaskScheduler,
    private val clock: Clock,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(ClanWebSocketHandler::class.java)

    private data class State(
        val session: WebSocketSession,
        @Volatile
        var authenticated: Boolean = false,
        @Volatile
        var lastActivityMillis: Long,
        var authTimeout: ScheduledFuture<*>? = null,
    )
    private val states = ConcurrentHashMap<String, State>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val state = State(session = session, lastActivityMillis = clock.millis())
        states[session.id] = state
        state.authTimeout = scheduler.schedule({
            if (states[session.id]?.authenticated != true) closePolicy(session, "Authentication timeout")
        }, clock.instant().plus(AUTH_TIMEOUT))
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val state = states[session.id] ?: return
        state.lastActivityMillis = clock.millis()
        if (message.payload.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            sendError(session, null, "FRAME_TOO_LARGE", "Text frame exceeds 64 KiB")
            session.close(CloseStatus.TOO_BIG_TO_PROCESS)
            return
        }
        val node = runCatching { objectMapper.readTree(message.payload) }.getOrElse {
            if (state.authenticated) {
                sendError(session, null, "INVALID_MESSAGE", "Malformed JSON")
            } else {
                authFailed(session, null)
            }
            return
        }
        val type = node.path("type").asText()
        val requestId = node.path("requestId").asText().takeIf { it.isNotBlank() }
        if (!state.authenticated) {
            if (type != "AUTH") {
                authFailed(session, requestId)
                return
            }
            authenticate(session, state, node.path("payload").path("deviceToken").asText(), requestId)
            return
        }
        when (type) {
            "PING" -> {
                val deviceId = registry.deviceId(session) ?: return
                presenceService.ping(deviceId)
                registry.send(deviceId, WsOutboundMessage("PONG", requestId = requestId, timestamp = clock.millis(), payload = emptyMap<String, Any>()))
            }
            "AUTH" -> sendError(session, requestId, "ALREADY_AUTHENTICATED", "Connection is already authenticated")
            else -> sendError(session, requestId, "UNSUPPORTED_MESSAGE", "Unsupported message type")
        }
    }

    private fun authenticate(session: WebSocketSession, state: State, rawToken: String, requestId: String?) {
        try {
            val ip = session.attributes[REMOTE_IP_ATTRIBUTE]?.toString() ?: "unknown"
            rateLimiter.check("websocket-auth", ip, 10, Duration.ofMinutes(1))
            if (rawToken.isBlank()) throw BusinessException(ErrorCode.INVALID_DEVICE_TOKEN)
            val device = deviceRepository.findByDeviceTokenHash(tokenService.hash(rawToken))
                ?: throw BusinessException(ErrorCode.INVALID_DEVICE_TOKEN)
            val replacedExistingConnection = registry.register(device.id, session)
            presenceService.connected(device.id, suppressBroadcast = replacedExistingConnection)
            // 只有注册与在线状态都写成功后才算认证完成：否则一旦写库失败，
            // 这条连接会以「已认证」留在内存里，后续 AUTH 只会得到 ALREADY_AUTHENTICATED。
            state.authenticated = true
            state.authTimeout?.cancel(false)
            registry.send(device.id, WsOutboundMessage("AUTH_SUCCESS", requestId = requestId, timestamp = clock.millis(), payload = mapOf(
                "deviceId" to device.id, "heartbeatIntervalSeconds" to 20, "idleTimeoutSeconds" to 60,
                "reconnectGraceSeconds" to 30,
            )))
        } catch (ex: BusinessException) {
            failAuthentication(session, requestId, ex.errorCode, ex.retryAfterSeconds)
        } catch (ex: Exception) {
            // 基础设施异常（数据库/方言等）：必须回一帧 AUTH_FAILED 再关闭，
            // 否则客户端只能干等到认证超时，与服务端日志一样看不到原因。
            log.error("WebSocket authentication failed", ex)
            failAuthentication(session, requestId, ErrorCode.INTERNAL_ERROR, null)
        }
    }

    private fun failAuthentication(
        session: WebSocketSession,
        requestId: String?,
        errorCode: ErrorCode,
        retryAfterSeconds: Long?,
    ) {
        val payload = mutableMapOf<String, Any>("code" to errorCode.code, "message" to errorCode.defaultMessage)
        retryAfterSeconds?.let { payload["retryAfter"] = it }
        sendDirect(session, WsOutboundMessage("AUTH_FAILED", requestId = requestId, timestamp = clock.millis(), payload = payload))
        closePolicy(session, "Authentication failed")
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        sendError(session, null, "BINARY_NOT_SUPPORTED", "Binary frames are not supported")
        session.close(CloseStatus.NOT_ACCEPTABLE)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        states.remove(session.id)?.authTimeout?.cancel(false)
        registry.remove(session)?.let(presenceService::disconnected)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        if (session.isOpen) session.close(CloseStatus.SERVER_ERROR)
    }

    @Scheduled(fixedDelay = 5_000)
    fun closeIdleConnections() {
        val cutoff = clock.millis() - IDLE_TIMEOUT.toMillis()
        states.values.filter { it.authenticated && it.lastActivityMillis < cutoff }.forEach {
            closePolicy(it.session, "Idle timeout")
        }
    }

    private fun authFailed(session: WebSocketSession, requestId: String?) {
        sendDirect(session, WsOutboundMessage("AUTH_FAILED", requestId = requestId, timestamp = clock.millis(), payload = mapOf(
            "code" to ErrorCode.INVALID_DEVICE_TOKEN.code,
            "message" to ErrorCode.INVALID_DEVICE_TOKEN.defaultMessage,
        )))
        closePolicy(session, "Authentication required")
    }

    private fun sendError(session: WebSocketSession, requestId: String?, code: String, message: String) =
        sendDirect(session, WsOutboundMessage("ERROR", requestId = requestId, timestamp = clock.millis(), payload = mapOf("code" to code, "message" to message)))

    private fun sendDirect(session: WebSocketSession, message: WsOutboundMessage) {
        registry.send(session, message)
    }

    private fun closePolicy(session: WebSocketSession, reason: String) {
        if (session.isOpen) runCatching { session.close(CloseStatus.POLICY_VIOLATION.withReason(reason)) }
    }

    companion object {
        const val REMOTE_IP_ATTRIBUTE = "remoteIp"
        const val MAX_TEXT_BYTES = 65_536
        val AUTH_TIMEOUT: Duration = Duration.ofSeconds(5)
        val IDLE_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
