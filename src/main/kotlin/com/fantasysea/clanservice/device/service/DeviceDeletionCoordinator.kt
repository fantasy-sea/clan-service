package com.fantasysea.clanservice.device.service

import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import org.springframework.stereotype.Component

@Component
class DeviceDeletionCoordinator(
    private val bindings: DeviceBindingRepository,
    private val sessions: RemoteSessionRepository,
    private val gateway: WsEventGateway,
    private val afterCommit: AfterCommitExecutor,
    private val clock: Clock,
    private val deviceLocks: DeviceLockService,
    private val pairingRequests: PairingRequestRepository,
) {
    fun notifyAfterDelete(deviceId: String) {
        val bindingPeers = bindings.findByDeviceLowIdOrDeviceHighId(deviceId, deviceId).map { it.peerOf(deviceId) }
        val pendingPairingPeers = pairingRequests.findByStatusAndDevice(PairingRequestStatus.PENDING, deviceId).map {
            if (it.requesterDeviceId == deviceId) it.targetDeviceId else it.requesterDeviceId
        }
        val initialSessions = sessions.findActiveForDevice(deviceId, RemoteSession.ACTIVE_STATUSES)
        val sessionPeers = initialSessions.map { if (it.controllerDeviceId == deviceId) it.controlledDeviceId else it.controllerDeviceId }
        deviceLocks.lockAll(bindingPeers + pendingPairingPeers + sessionPeers + deviceId)

        val peers = bindings.findByDeviceLowIdOrDeviceHighId(deviceId, deviceId).map { it.peerOf(deviceId) }.toSet()
        val pendingPairings = pairingRequests.findByStatusAndDevice(PairingRequestStatus.PENDING, deviceId).map {
            Triple(it.id, if (it.requesterDeviceId == deviceId) it.targetDeviceId else it.requesterDeviceId, it.expiresAt)
        }
        val activeSessions = sessions.findActiveForDevice(deviceId, RemoteSession.ACTIVE_STATUSES)
            .mapNotNull { sessions.findByIdForUpdate(it.id) }
            .map {
                Triple(it.id, if (it.controllerDeviceId == deviceId) it.controlledDeviceId else it.controllerDeviceId, it.status)
            }
        afterCommit.execute {
            gateway.closeDevice(deviceId)
            val offline = WsOutboundMessage("DEVICE_OFFLINE", timestamp = clock.millis(), payload = mapOf(
                "deviceId" to deviceId, "lastSeenAt" to clock.instant(),
            ))
            peers.forEach { gateway.send(it, offline) }
            pendingPairings.forEach { (requestId, peerId, expiresAt) ->
                gateway.send(peerId, WsOutboundMessage(
                    "PAIRING_RESULT", requestId = requestId, timestamp = clock.millis(),
                    payload = mapOf("result" to "EXPIRED", "expiresAt" to expiresAt),
                ))
            }
            activeSessions.forEach { (sessionId, peerId, status) ->
                val (eventType, payload) = when (status) {
                    RemoteSessionStatus.REQUESTING -> "CONTROL_RESULT" to mapOf(
                        "scope" to "SESSION_REQUEST", "result" to "FAILED", "reason" to "DEVICE_DELETED",
                    )
                    RemoteSessionStatus.CONNECTING -> "SESSION_FAILED" to mapOf("reason" to "DEVICE_DELETED")
                    RemoteSessionStatus.CONNECTED -> "SESSION_ENDED" to mapOf("reason" to "DEVICE_DELETED")
                    else -> return@forEach
                }
                gateway.send(
                    peerId,
                    WsOutboundMessage(eventType, sessionId = sessionId, timestamp = clock.millis(), payload = payload),
                )
            }
        }
    }
}
