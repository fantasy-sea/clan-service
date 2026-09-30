package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.pairing.entity.PairingCode
import com.fantasysea.clanservice.pairing.repository.PairingCodeRepository
import com.fantasysea.clanservice.pairing.security.PairingCodeHasher
import com.fantasysea.clanservice.pairing.service.PairingService
import com.fantasysea.clanservice.session.service.SessionService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["clan.websocket.container-enabled=true"],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketWorkflowIT {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var devices: DeviceRepository
    @Autowired private lateinit var tokens: DeviceTokenService
    @Autowired private lateinit var codes: PairingCodeRepository
    @Autowired private lateinit var codeHasher: PairingCodeHasher
    @Autowired private lateinit var pairingService: PairingService
    @Autowired private lateinit var sessionService: SessionService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var clock: Clock

    @Test
    fun `two devices complete auth pairing and session workflow without replay`() {
        val suffix = System.nanoTime().toString()
        val firstId = "dev_ws_a_$suffix"
        val secondId = "dev_ws_b_$suffix"
        val firstToken = "first-device-token-$suffix"
        val secondToken = "second-device-token-$suffix"
        devices.saveAndFlush(Device(firstId, "controller", DevicePlatform.ANDROID, deviceTokenHash = tokens.hash(firstToken)))
        devices.saveAndFlush(Device(secondId, "controlled", DevicePlatform.ANDROID, deviceTokenHash = tokens.hash(secondToken)))

        val first = connect(firstToken)
        val second = connect(secondToken)
        var replacement: TestSocket? = null
        try {
            assertEquals(firstId, first.await("AUTH_SUCCESS").path("payload").path("deviceId").asText())
            assertEquals(secondId, second.await("AUTH_SUCCESS").path("payload").path("deviceId").asText())

            first.socket.sendText("{\"type\":\"PING\",\"requestId\":\"ping-1\",\"payload\":{}}", true).join()
            assertEquals("ping-1", first.await("PONG").path("requestId").asText())

            val rawCode = "123456"
            val now = clock.instant()
            codes.saveAndFlush(PairingCode(
                deviceId = secondId,
                codeHash = codeHasher.hash(rawCode),
                expiresAt = now.plusSeconds(60),
                createdAt = now,
            ))
            val request = pairingService.verify(firstId, rawCode)
            assertEquals(request.requestId, second.await("PAIRING_REQUEST").path("requestId").asText())

            pairingService.accept(secondId, request.requestId)
            assertEquals("ACCEPTED", first.await("PAIRING_RESULT").path("payload").path("result").asText())
            first.await("PAIRING_SUCCEEDED")
            assertEquals("ACCEPTED", second.await("PAIRING_RESULT").path("payload").path("result").asText())
            second.await("PAIRING_SUCCEEDED")

            val session = sessionService.create(firstId, secondId)
            assertEquals(session.sessionId, second.await("CONTROL_REQUEST").path("sessionId").asText())
            sessionService.accept(secondId, session.sessionId)
            assertEquals("ACCEPTED", first.await("CONTROL_RESULT").path("payload").path("result").asText())
            first.await("SESSION_STARTED")
            assertEquals("ACCEPTED", second.await("CONTROL_RESULT").path("payload").path("result").asText())
            second.await("SESSION_STARTED")

            sessionService.end(firstId, session.sessionId)
            first.await("SESSION_ENDED")
            second.await("SESSION_ENDED")

            replacement = connect(firstToken)
            replacement.await("AUTH_SUCCESS")
            assertEquals(WebSocketConnectionRegistry.REPLACED_CLOSE_CODE, first.closed.get(5, TimeUnit.SECONDS))
            assertNull(replacement.messages.poll(300, TimeUnit.MILLISECONDS), "reconnect must not replay business events")
        } finally {
            replacement?.close()
            first.close()
            second.close()
        }
    }

    private fun connect(token: String): TestSocket {
        val listener = TestListener(mapper)
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create("ws://127.0.0.1:$port/ws"), listener)
            .get(5, TimeUnit.SECONDS)
        socket.sendText(mapper.writeValueAsString(mapOf(
            "type" to "AUTH",
            "requestId" to "auth-1",
            "payload" to mapOf("deviceToken" to token),
        )), true).join()
        return TestSocket(socket, listener.messages, listener.closed, listener.errors, mapper)
    }

    private class TestListener(private val mapper: ObjectMapper) : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        val errors = LinkedBlockingQueue<Throwable>()
        val closed = CompletableFuture<Int>()
        private val partial = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) {
                messages.offer(partial.toString())
                partial.setLength(0)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closed.complete(statusCode)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.offer(error)
        }
    }

    private data class TestSocket(
        val socket: WebSocket,
        val messages: LinkedBlockingQueue<String>,
        val closed: CompletableFuture<Int>,
        val errors: LinkedBlockingQueue<Throwable>,
        val mapper: ObjectMapper,
    ) {
        fun await(type: String): JsonNode {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                errors.poll()?.let { throw AssertionError("WebSocket failed while awaiting $type", it) }
                val remaining = deadline - System.nanoTime()
                val raw = messages.poll(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: continue
                val node = mapper.readTree(raw)
                if (node.path("type").asText() == type) return node
            }
            throw AssertionError("Timed out awaiting WebSocket event $type")
        }

        fun close() {
            if (!closed.isDone) runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(2, TimeUnit.SECONDS) }
        }
    }
}
