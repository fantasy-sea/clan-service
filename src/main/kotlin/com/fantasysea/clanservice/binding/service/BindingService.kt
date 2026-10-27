package com.fantasysea.clanservice.binding.service

import com.fantasysea.clanservice.binding.dto.BindingResponse
import com.fantasysea.clanservice.binding.dto.UpdateBindingRequest
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Instant
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
            devices[binding.peerOf(deviceId)]?.let { peer -> bindingResponse(peer, binding.boundAt) }
        }
    }

    /**
     * 查询调用方与指定设备之间的单条绑定。
     * 自身、未绑定和第三方一律返回 BINDING_NOT_FOUND，避免泄露设备是否存在。
     */
    @Transactional(readOnly = true)
    fun get(deviceId: String, peerId: String): BindingResponse {
        if (deviceId == peerId) throw BusinessException(ErrorCode.BINDING_NOT_FOUND)
        val (low, high) = normalize(deviceId, peerId)
        val binding = bindingRepository.findPair(low, high) ?: throw BusinessException(ErrorCode.BINDING_NOT_FOUND)
        val peer = deviceRepository.findById(peerId).orElseThrow { BusinessException(ErrorCode.BINDING_NOT_FOUND) }
        return bindingResponse(peer, binding.boundAt)
    }

    /**
     * 修改已绑定对端设备的展示名称。
     * 名称保存在 devices.device_name，对端本机及其他绑定关系都会看到新名称。
     * 自身、未绑定和第三方统一返回 BINDING_NOT_FOUND，避免泄露设备是否存在。
     */
    @Transactional
    fun rename(deviceId: String, peerId: String, request: UpdateBindingRequest): BindingResponse {
        if (deviceId == peerId) throw BusinessException(ErrorCode.BINDING_NOT_FOUND)
        val name = request.deviceName.trim()
        if (name.isEmpty()) throw BusinessException(ErrorCode.INVALID_PARAMETER, "deviceName must not be blank")
        val (low, high) = normalize(deviceId, peerId)
        val binding = bindingRepository.findPair(low, high) ?: throw BusinessException(ErrorCode.BINDING_NOT_FOUND)
        val peer = deviceRepository.findById(peerId).orElseThrow { BusinessException(ErrorCode.BINDING_NOT_FOUND) }
        peer.deviceName = name
        return bindingResponse(peer, binding.boundAt)
    }

    @Transactional
    fun unbind(deviceId: String, peerId: String) {
        deviceLockService.lockPair(deviceId, peerId)
        val (low, high) = normalize(deviceId, peerId)
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

    private fun normalize(first: String, second: String): Pair<String, String> =
        if (first < second) first to second else second to first

    private fun bindingResponse(peer: Device, boundAt: Instant): BindingResponse = BindingResponse(
        deviceId = peer.id,
        deviceName = peer.deviceName,
        platform = peer.platform,
        model = peer.model,
        androidVersion = peer.androidVersion,
        online = peer.online && gateway.isReachable(peer.id),
        lastSeenAt = peer.lastSeenAt,
        boundAt = boundAt,
        createdAt = peer.createdAt,
    )
}
