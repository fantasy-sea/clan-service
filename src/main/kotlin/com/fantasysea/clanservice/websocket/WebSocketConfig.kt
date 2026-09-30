package com.fantasysea.clanservice.websocket

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: ClanWebSocketHandler,
    private val handshakeInterceptor: SecureHandshakeInterceptor,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws").addInterceptors(handshakeInterceptor).setAllowedOriginPatterns("*")
    }

    @Bean
    @ConditionalOnProperty(prefix = "clan.websocket", name = ["container-enabled"], havingValue = "true", matchIfMissing = true)
    fun webSocketContainer(): ServletServerContainerFactoryBean = ServletServerContainerFactoryBean().apply {
        setMaxTextMessageBufferSize(ClanWebSocketHandler.MAX_TEXT_BYTES)
        setMaxBinaryMessageBufferSize(1)
        setMaxSessionIdleTimeout(ClanWebSocketHandler.IDLE_TIMEOUT.toMillis())
    }
}

@Configuration
class SecureHandshakeInterceptor : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val forbidden = try {
            request.uri.rawQuery.orEmpty().split('&').asSequence()
                .filter { it.isNotBlank() }
                .map { URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8).lowercase() }
                .any { it in FORBIDDEN_TOKEN_PARAMETERS }
        } catch (_: IllegalArgumentException) {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            return false
        }
        if (forbidden) {
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            return false
        }
        attributes[ClanWebSocketHandler.REMOTE_IP_ATTRIBUTE] = request.remoteAddress?.address?.hostAddress ?: "unknown"
        return true
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) = Unit

    companion object {
        val FORBIDDEN_TOKEN_PARAMETERS = setOf("token", "access_token", "authorization")
    }
}
