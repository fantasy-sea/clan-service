package com.fantasysea.clanservice.common.cleanup

import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingCodeRepository
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Duration
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.data.domain.PageRequest

@Component
class LifecycleCleanupService(
    private val devices: DeviceRepository,
    private val codes: PairingCodeRepository,
    private val pairings: PairingRequestRepository,
    private val sessions: RemoteSessionRepository,
    private val gateway: WsEventGateway,
    private val limiter: InMemoryRateLimiter,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val terminalTransitions: TerminalTransitionService,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverAfterRestart() {
        val now = clock.instant()
        transactions.executeWithoutResult { devices.clearOnlineState(now) }
        while (true) {
            val batch = pairings.findByStatus(PairingRequestStatus.PENDING, page())
            if (batch.isEmpty()) break
            batch.forEach { terminalTransitions.expirePairing(it.id, now, onlyWhenDue = false) }
        }
        while (true) {
            val batch = sessions.findByStatusIn(ACTIVE, page())
            if (batch.isEmpty()) break
            batch.forEach { terminalTransitions.terminateSession(it.id, now, SessionTermination.SERVER_RESTART) }
        }
    }

    @Scheduled(fixedDelay = 5_000)
    fun advanceTimeouts() {
        val now = clock.instant()
        val events = mutableListOf<Pair<List<String>, WsOutboundMessage>>()
        pairings.findByStatusAndExpiresAtBefore(PairingRequestStatus.PENDING, now, page()).forEach { pairing ->
            terminalTransitions.expirePairing(pairing.id, now, onlyWhenDue = true)?.let {
                events += it.deviceIds to WsOutboundMessage(
                    "PAIRING_RESULT", requestId = it.requestId, timestamp = clock.millis(), payload = mapOf("result" to "EXPIRED"))
            }
        }
        sessions.findByStatusAndExpiresAtBefore(RemoteSessionStatus.REQUESTING, now, page()).forEach { session ->
            terminalTransitions.terminateSession(session.id, now, SessionTermination.REQUEST_TIMEOUT)?.let {
                events += it.deviceIds to WsOutboundMessage(
                    "CONTROL_RESULT", sessionId = it.sessionId, timestamp = clock.millis(), payload = mapOf(
                        "scope" to "SESSION_REQUEST", "result" to "EXPIRED"))
            }
        }
        sessions.findByStatusAndConnectExpiresAtBefore(RemoteSessionStatus.CONNECTING, now, page()).forEach { session ->
            terminalTransitions.terminateSession(session.id, now, SessionTermination.CONNECT_TIMEOUT)?.let {
                events += it.deviceIds to WsOutboundMessage(
                    "SESSION_FAILED", sessionId = it.sessionId, timestamp = clock.millis(), payload = mapOf("reason" to it.reason))
            }
        }
        events.forEach { (targets, event) -> targets.forEach { gateway.send(it, event) } }
    }

    @Scheduled(fixedDelay = 60_000)
    fun deleteOldRecords() {
        val now = clock.instant()
        transactions.executeWithoutResult {
            codes.deleteAllInBatch(codes.findByExpiresAtBefore(now.minus(CODE_RETENTION), page()))
            pairings.deleteAllInBatch(pairings.findByStatusNotAndHandledAtBefore(PairingRequestStatus.PENDING, now.minus(PAIRING_RETENTION), page()))
            sessions.deleteAllInBatch(sessions.findTerminalBefore(now.minus(SESSION_RETENTION), page()))
        }
        limiter.evictExpired(now)
    }

    companion object {
        const val BATCH_SIZE = 500
        val CODE_RETENTION: Duration = Duration.ofMinutes(10)
        val PAIRING_RETENTION: Duration = Duration.ofHours(24)
        val SESSION_RETENTION: Duration = Duration.ofDays(7)
        val ACTIVE = setOf(RemoteSessionStatus.REQUESTING, RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }

    private fun page() = PageRequest.of(0, BATCH_SIZE)
}
