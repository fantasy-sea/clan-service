package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.cleanup.SessionTermination
import com.fantasysea.clanservice.common.cleanup.TerminalTransitionService
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class PresenceService(
    private val deviceRepository: DeviceRepository,
    private val bindingRepository: DeviceBindingRepository,
    private val pairingRequestRepository: PairingRequestRepository,
    private val sessionRepository: RemoteSessionRepository,
    private val gateway: WsEventGateway,
    private val scheduler: TaskScheduler,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val terminalTransitions: TerminalTransitionService,
) {
    private data class OfflineTask(val marker: Any, val future: ScheduledFuture<*>)
    private val pendingOffline = ConcurrentHashMap<String, OfflineTask>()

    @Synchronized
    fun connected(deviceId: String, suppressBroadcast: Boolean = false) {
        val pending = pendingOffline.remove(deviceId)
        val reconnect = pending != null
        pending?.future?.cancel(false)
        val now = clock.instant()
        transactions.executeWithoutResult {
            val device = deviceRepository.findByIdForUpdate(deviceId) ?: return@executeWithoutResult
            device.online = true
            device.lastSeenAt = now
        }
        if (!reconnect && !suppressBroadcast) broadcastPresence(deviceId, "DEVICE_ONLINE", now)
    }

    fun ping(deviceId: String) {
        val now = clock.instant()
        transactions.executeWithoutResult {
            deviceRepository.findByIdForUpdate(deviceId)?.lastSeenAt = now
        }
    }

    @Synchronized
    fun disconnected(deviceId: String) {
        val now = clock.instant()
        transactions.executeWithoutResult {
            val device = deviceRepository.findByIdForUpdate(deviceId) ?: return@executeWithoutResult
            device.online = false
            device.lastSeenAt = now
        }
        pendingOffline.compute(deviceId) { _, current ->
            current?.future?.cancel(false)
            val marker = Any()
            OfflineTask(marker, scheduler.schedule({ finishOfflineGrace(deviceId, marker) }, now.plus(RECONNECT_GRACE)))
        }
    }

    @Synchronized
    private fun finishOfflineGrace(deviceId: String, marker: Any) {
        val pending = pendingOffline[deviceId] ?: return
        if (pending.marker !== marker) return
        // register() happens immediately before connected(); leaving the marker here lets
        // connected() recognize the reconnect and suppress an ONLINE/OFFLINE flap.
        if (gateway.isReachable(deviceId)) return
        if (!pendingOffline.remove(deviceId, pending)) return
        val now = clock.instant()
        val pairingResults = pairingRequestRepository.findByStatusAndDevice(PairingRequestStatus.PENDING, deviceId)
            .mapNotNull { terminalTransitions.expirePairing(it.id, now, onlyWhenDue = false) }
        val sessionResults = sessionRepository.findActiveForDevice(deviceId, RemoteSession.ACTIVE_STATUSES)
            .mapNotNull { terminalTransitions.terminateSession(it.id, now, SessionTermination.DEVICE_OFFLINE) }
        broadcastPresence(deviceId, "DEVICE_OFFLINE", now)
        pairingResults.forEach { result -> result.deviceIds.forEach { gateway.send(it, WsOutboundMessage(
            "PAIRING_RESULT", requestId = result.requestId, timestamp = clock.millis(), payload = mapOf("result" to "EXPIRED"))) } }
        sessionResults.forEach { result -> result.deviceIds.forEach { gateway.send(it, WsOutboundMessage(
            result.eventType, sessionId = result.sessionId, timestamp = clock.millis(), payload = mapOf("reason" to result.reason))) } }
    }

    private fun broadcastPresence(deviceId: String, type: String, lastSeenAt: java.time.Instant) {
        bindingRepository.findByDeviceLowIdOrDeviceHighId(deviceId, deviceId).forEach { binding ->
            gateway.send(binding.peerOf(deviceId), WsOutboundMessage(type, timestamp = clock.millis(), payload = mapOf(
                "deviceId" to deviceId, "lastSeenAt" to lastSeenAt,
            )))
        }
    }

    companion object { val RECONNECT_GRACE: Duration = Duration.ofSeconds(30) }
}
