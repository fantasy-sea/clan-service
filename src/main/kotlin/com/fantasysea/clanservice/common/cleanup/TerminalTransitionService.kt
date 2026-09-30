package com.fantasysea.clanservice.common.cleanup

import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class PairingTransition(val requestId: String, val deviceIds: List<String>)
data class SessionTransition(val eventType: String, val sessionId: String, val deviceIds: List<String>, val reason: String)

enum class SessionTermination { REQUEST_TIMEOUT, CONNECT_TIMEOUT, DEVICE_OFFLINE, SERVER_RESTART }

@Service
class TerminalTransitionService(
    private val pairings: PairingRequestRepository,
    private val sessions: RemoteSessionRepository,
    private val deviceLocks: DeviceLockService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expirePairing(requestId: String, now: Instant, onlyWhenDue: Boolean): PairingTransition? {
        val initial = pairings.findById(requestId).orElse(null) ?: return null
        deviceLocks.lockPair(initial.requesterDeviceId, initial.targetDeviceId)
        val request = pairings.findByIdForUpdate(requestId) ?: return null
        if (request.status != PairingRequestStatus.PENDING) return null
        if (onlyWhenDue && now.isBefore(request.expiresAt)) return null
        request.status = PairingRequestStatus.EXPIRED
        request.handledAt = now
        return PairingTransition(request.id, listOf(request.requesterDeviceId, request.targetDeviceId))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun terminateSession(sessionId: String, now: Instant, termination: SessionTermination): SessionTransition? {
        val initial = sessions.findById(sessionId).orElse(null) ?: return null
        deviceLocks.lockPair(initial.controllerDeviceId, initial.controlledDeviceId)
        val session = sessions.findByIdForUpdate(sessionId) ?: return null
        val reason: String
        val eventType: String
        when (termination) {
            SessionTermination.REQUEST_TIMEOUT -> {
                if (session.status != RemoteSessionStatus.REQUESTING || now.isBefore(session.expiresAt)) return null
                session.status = RemoteSessionStatus.FAILED
                session.failedAt = now
                reason = "REQUEST_EXPIRED"
                eventType = "CONTROL_RESULT"
            }
            SessionTermination.CONNECT_TIMEOUT -> {
                if (session.status != RemoteSessionStatus.CONNECTING || session.connectExpiresAt?.let(now::isBefore) != false) return null
                session.status = RemoteSessionStatus.FAILED
                session.failedAt = now
                reason = "CONNECT_TIMEOUT"
                eventType = "SESSION_FAILED"
            }
            SessionTermination.DEVICE_OFFLINE -> {
                if (!session.isActive()) return null
                reason = "DEVICE_OFFLINE"
                if (session.status == RemoteSessionStatus.CONNECTED) {
                    session.status = RemoteSessionStatus.ENDED
                    session.endedAt = now
                    eventType = "SESSION_ENDED"
                } else {
                    session.status = RemoteSessionStatus.FAILED
                    session.failedAt = now
                    eventType = "SESSION_FAILED"
                }
            }
            SessionTermination.SERVER_RESTART -> {
                if (!session.isActive()) return null
                reason = "SERVER_RESTART"
                if (session.status == RemoteSessionStatus.CONNECTED) {
                    session.status = RemoteSessionStatus.ENDED
                    session.endedAt = now
                    eventType = "SESSION_ENDED"
                } else {
                    session.status = RemoteSessionStatus.FAILED
                    session.failedAt = now
                    eventType = "SESSION_FAILED"
                }
            }
        }
        session.failureReason = reason
        return SessionTransition(eventType, session.id, listOf(session.controllerDeviceId, session.controlledDeviceId), reason)
    }
}
