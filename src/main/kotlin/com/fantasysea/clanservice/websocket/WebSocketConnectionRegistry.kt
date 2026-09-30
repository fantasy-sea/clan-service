package com.fantasysea.clanservice.websocket

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import tools.jackson.databind.ObjectMapper

@Component
class WebSocketConnectionRegistry(private val objectMapper: ObjectMapper) : WsEventGateway {
    private data class Connection(val raw: WebSocketSession, val safe: WebSocketSession)
    private val byDevice = ConcurrentHashMap<String, Connection>()
    private val deviceBySession = ConcurrentHashMap<String, String>()
    private val safeBySession = ConcurrentHashMap<String, WebSocketSession>()

    fun register(deviceId: String, session: WebSocketSession): Boolean {
        val connection = Connection(session, safeSession(session))
        deviceBySession[session.id] = deviceId
        val old = byDevice.put(deviceId, connection)
        if (old != null && old.raw.id != session.id) {
            deviceBySession.remove(old.raw.id, deviceId)
            safeBySession.remove(old.raw.id)
            runCatching { old.raw.close(CloseStatus(REPLACED_CLOSE_CODE, "Connection replaced")) }
        }
        return old != null && old.raw.id != session.id
    }

    fun remove(session: WebSocketSession): String? {
        safeBySession.remove(session.id)
        val deviceId = deviceBySession.remove(session.id) ?: return null
        val removed = byDevice.computeIfPresent(deviceId) { _, current -> if (current.raw.id == session.id) null else current }
        return if (removed == null && byDevice[deviceId]?.raw?.id != session.id) deviceId else null
    }

    fun deviceId(session: WebSocketSession): String? = deviceBySession[session.id]

    override fun isReachable(deviceId: String): Boolean = byDevice[deviceId]?.raw?.isOpen == true

    override fun send(deviceId: String, message: WsOutboundMessage): Boolean {
        val connection = byDevice[deviceId] ?: return false
        if (!connection.raw.isOpen) return false
        return runCatching {
            connection.safe.sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
            true
        }.getOrDefault(false)
    }

    fun send(session: WebSocketSession, message: WsOutboundMessage): Boolean {
        if (!session.isOpen) return false
        return runCatching {
            safeSession(session).sendMessage(TextMessage(objectMapper.writeValueAsString(message)))
            true
        }.getOrDefault(false)
    }

    override fun closeDevice(deviceId: String) {
        val connection = byDevice.remove(deviceId) ?: return
        deviceBySession.remove(connection.raw.id, deviceId)
        safeBySession.remove(connection.raw.id)
        runCatching { connection.raw.close(CloseStatus.NORMAL) }
    }

    private fun safeSession(session: WebSocketSession): WebSocketSession =
        safeBySession.computeIfAbsent(session.id) {
            ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MS, SEND_BUFFER_BYTES)
        }

    companion object {
        const val REPLACED_CLOSE_CODE = 4001
        const val SEND_TIMEOUT_MS = 10_000
        const val SEND_BUFFER_BYTES = 65_536
    }
}
