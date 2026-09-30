package com.fantasysea.clanservice.websocket

data class WsOutboundMessage(
    val type: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    val timestamp: Long,
    val payload: Any? = null,
)

interface WsEventGateway {
    fun isReachable(deviceId: String): Boolean
    fun send(deviceId: String, message: WsOutboundMessage): Boolean
    fun closeDevice(deviceId: String)
}
