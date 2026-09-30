package com.fantasysea.clanservice.binding.service

import com.fantasysea.clanservice.binding.dto.BindingResponse
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BindingService(
    private val bindingRepository: DeviceBindingRepository,
    private val deviceRepository: DeviceRepository,
    private val sessionRepository: RemoteSessionRepository,
    private val deviceLockService: DeviceLockService,
    private val gateway: WsEventGateway,
    private val afterCommit: AfterCommitExecutor,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(deviceId: String): List<BindingResponse> {
        val bindings = bindingRepository.findByDeviceLowIdOrDeviceHighId(deviceId, deviceId)
        val devices = deviceRepository.findAllById(bindings.map { it.peerOf(deviceId) }).associateBy { it.id }
        return bindings.mapNotNull { binding ->
            devices[binding.peerOf(deviceId)]?.let { peer ->
                BindingResponse(peer.id, peer.deviceName, peer.platform, peer.model, peer.androidVersion,
                    peer.online && gateway.isReachable(peer.id), peer.lastSeenAt, binding.boundAt)
            }
        }
    }

    @Transactional
    fun unbind(deviceId: String, peerId: String) {
        deviceLockService.lockPair(deviceId, peerId)
        val (low, high) = if (deviceId < peerId) deviceId to peerId else peerId to deviceId
        val binding = bindingRepository.findPair(low, high) ?: throw BusinessException(ErrorCode.BINDING_NOT_FOUND)
        val now = clock.instant()
        val sessions = sessionRepository.findActiveForDevice(deviceId, RemoteSession.ACTIVE_STATUSES)
            .filter { it.involves(peerId) }
            .mapNotNull { sessionRepository.findByIdForUpdate(it.id) }
        sessions.forEach {
            it.status = RemoteSessionStatus.ENDED
            it.endedAt = now
            it.failureReason = "UNBOUND"
        }
        bindingRepository.delete(binding)
        afterCommit.execute {
            sessions.forEach { session ->
                val event = WsOutboundMessage("SESSION_ENDED", sessionId = session.id, timestamp = clock.millis(), payload = mapOf("reason" to "UNBOUND"))
                gateway.send(session.controllerDeviceId, event)
                gateway.send(session.controlledDeviceId, event)
            }
        }
    }
}
