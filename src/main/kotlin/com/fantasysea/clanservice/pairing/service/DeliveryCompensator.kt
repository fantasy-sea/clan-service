package com.fantasysea.clanservice.pairing.service

import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.fantasysea.clanservice.common.persistence.DeviceLockService

@Service
class DeliveryCompensator(
    private val pairings: PairingRequestRepository,
    private val sessions: RemoteSessionRepository,
    private val deviceLocks: DeviceLockService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun expirePairing(requestId: String, now: Instant) {
        val initial = pairings.findById(requestId).orElse(null) ?: return
        deviceLocks.lockPair(initial.requesterDeviceId, initial.targetDeviceId)
        pairings.findByIdForUpdate(requestId)?.takeIf { it.status == PairingRequestStatus.PENDING }?.let {
            it.status = PairingRequestStatus.EXPIRED
            it.handledAt = now
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failSession(sessionId: String, now: Instant) {
        val initial = sessions.findById(sessionId).orElse(null) ?: return
        deviceLocks.lockPair(initial.controllerDeviceId, initial.controlledDeviceId)
        sessions.findByIdForUpdate(sessionId)?.takeIf { it.status == RemoteSessionStatus.REQUESTING }?.let {
            it.status = RemoteSessionStatus.FAILED
            it.failedAt = now
            it.failureReason = "DEVICE_OFFLINE"
        }
    }
}
