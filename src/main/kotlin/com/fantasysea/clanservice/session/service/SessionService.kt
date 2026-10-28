package com.fantasysea.clanservice.session.service

import com.fantasysea.clanservice.binding.dto.DeviceSummary
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.pairing.service.DeliveryCompensator
import com.fantasysea.clanservice.session.dto.SessionResponse
import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SessionService(
    private val sessionRepository: RemoteSessionRepository,
    private val bindingRepository: DeviceBindingRepository,
    private val deviceLockService: DeviceLockService,
    private val ulidGenerator: UlidGenerator,
    private val gateway: WsEventGateway,
    private val afterCommit: AfterCommitExecutor,
    private val deliveryCompensator: DeliveryCompensator,
    private val clock: Clock,
) {
    @Transactional(noRollbackFor = [BusinessException::class])
    fun create(controllerId: String, controlledId: String): SessionResponse {
        if (controllerId == controlledId) throw BusinessException(ErrorCode.DEVICES_NOT_BOUND)
        val (controller, controlled) = deviceLockService.lockPair(controllerId, controlledId)
        val (low, high) = normalize(controllerId, controlledId)
        if (!bindingRepository.existsByDeviceLowIdAndDeviceHighId(low, high)) {
            throw BusinessException(ErrorCode.DEVICES_NOT_BOUND)
        }
        val now = clock.instant()
        val active = sessionRepository.findActiveForDevicesForUpdate(controllerId, controlledId, RemoteSession.ACTIVE_STATUSES)
        active.forEach { existing -> expireIfDue(existing, now) }
        if (active.any { it.isActive() }) throw BusinessException(ErrorCode.SESSION_CONFLICT)
        if (!gateway.isReachable(controllerId) || !gateway.isReachable(controlledId)) {
            throw BusinessException(ErrorCode.DEVICE_OFFLINE)
        }
        val session = RemoteSession(
            id = "session_${ulidGenerator.ulid()}",
            controllerDeviceId = controllerId,
            controlledDeviceId = controlledId,
            status = RemoteSessionStatus.REQUESTING,
            expiresAt = now.plus(REQUEST_TTL),
            createdAt = now,
        )
        sessionRepository.save(session)
        afterCommit.execute {
            val delivered = gateway.send(controlledId, event("CONTROL_REQUEST", session, mapOf(
                "controller" to controller.summary(), "expiresAt" to session.expiresAt,
            )))
            if (!delivered) {
                try {
                    deliveryCompensator.failSession(session.id, clock.instant())
                } catch (ex: BusinessException) {
                    if (ex.errorCode != ErrorCode.DEVICE_NOT_FOUND) throw ex
                }
                throw BusinessException(ErrorCode.DEVICE_OFFLINE)
            }
        }
        return SessionResponse.from(session)
    }

    @Transactional(readOnly = true)
    fun get(deviceId: String, sessionId: String): SessionResponse {
        val session = sessionRepository.findById(sessionId).orElseThrow { BusinessException(ErrorCode.SESSION_NOT_FOUND) }
        requireParticipant(session, deviceId)
        return SessionResponse.from(session)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun accept(deviceId: String, sessionId: String): SessionResponse {
        val session = lockSessionAndDevices(sessionId)
        if (deviceId != session.controlledDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        if (session.status != RemoteSessionStatus.REQUESTING) throw BusinessException(ErrorCode.SESSION_INVALID_STATE)
        val now = clock.instant()
        if (!now.isBefore(session.expiresAt)) {
            fail(session, now, "REQUEST_EXPIRED")
            afterCommit.execute { sendBoth(session, event("CONTROL_RESULT", session, controlResult("EXPIRED"))) }
            throw BusinessException(ErrorCode.SESSION_REQUEST_EXPIRED)
        }
        if (!gateway.isReachable(session.controllerDeviceId) || !gateway.isReachable(session.controlledDeviceId)) {
            fail(session, now, "DEVICE_OFFLINE")
            afterCommit.execute { sendBoth(session, event("CONTROL_RESULT", session, controlResult("FAILED", "DEVICE_OFFLINE"))) }
            throw BusinessException(ErrorCode.DEVICE_OFFLINE)
        }
        session.status = RemoteSessionStatus.CONNECTING
        session.acceptedAt = now
        session.connectExpiresAt = now.plus(CONNECT_TTL)
        afterCommit.execute {
            sendBoth(session, event("CONTROL_RESULT", session, controlResult("ACCEPTED")))
            sendBoth(session, event("SESSION_STARTED", session, mapOf("connectExpiresAt" to session.connectExpiresAt)))
        }
        return SessionResponse.from(session)
    }

    /**
     * P4：由任一参与方在 WebRTC 真正连通后调用。
     * 首次 CONNECTING -> CONNECTED 并向双方广播 SESSION_CONNECTED；重复调用幂等，
     * 不重复广播。连接截止时间或对端可达性失败会推进到 FAILED。
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun connect(deviceId: String, sessionId: String): SessionResponse {
        val session = lockSessionAndDevices(sessionId)
        requireParticipant(session, deviceId)
        if (session.status == RemoteSessionStatus.CONNECTED) return SessionResponse.from(session)
        if (session.status != RemoteSessionStatus.CONNECTING) throw BusinessException(ErrorCode.SESSION_INVALID_STATE)
        val now = clock.instant()
        if (session.connectExpiresAt?.let { now.isBefore(it) } != true) {
            fail(session, now, "CONNECT_TIMEOUT")
            afterCommit.execute {
                sendBoth(session, event("SESSION_FAILED", session, mapOf("reason" to "CONNECT_TIMEOUT")))
            }
            throw BusinessException(ErrorCode.SESSION_CONNECT_TIMEOUT)
        }
        val peerId = if (deviceId == session.controllerDeviceId) session.controlledDeviceId else session.controllerDeviceId
        if (!gateway.isReachable(peerId)) {
            fail(session, now, "DEVICE_OFFLINE")
            afterCommit.execute {
                sendBoth(session, event("SESSION_FAILED", session, mapOf("reason" to "DEVICE_OFFLINE")))
            }
            throw BusinessException(ErrorCode.DEVICE_OFFLINE)
        }
        session.status = RemoteSessionStatus.CONNECTED
        session.connectedAt = now
        afterCommit.execute {
            sendBoth(session, event("SESSION_CONNECTED", session, mapOf("connectedAt" to session.connectedAt)))
        }
        return SessionResponse.from(session)
    }

    /**
     * P4：校验实时消息的会话归属与状态，只读，不修改状态。
     * 控制/信令服务用返回的 [SessionRelay] 判断方向并选择转发目标。
     */
    @Transactional(readOnly = true)
    fun requireRelay(
        deviceId: String,
        sessionId: String,
        allowed: Set<RemoteSessionStatus>,
    ): SessionRelay {
        val session = sessionRepository.findById(sessionId).orElseThrow { BusinessException(ErrorCode.SESSION_NOT_FOUND) }
        if (!session.involves(deviceId)) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        if (session.status !in allowed) throw BusinessException(ErrorCode.SESSION_INVALID_STATE)
        return SessionRelay(session.id, session.controllerDeviceId, session.controlledDeviceId, session.status)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun reject(deviceId: String, sessionId: String): SessionResponse {
        val session = lockSessionAndDevices(sessionId)
        if (deviceId != session.controlledDeviceId) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
        if (session.status != RemoteSessionStatus.REQUESTING) throw BusinessException(ErrorCode.SESSION_INVALID_STATE)
        val now = clock.instant()
        if (!now.isBefore(session.expiresAt)) {
            fail(session, now, "REQUEST_EXPIRED")
            afterCommit.execute { sendBoth(session, event("CONTROL_RESULT", session, controlResult("EXPIRED"))) }
            throw BusinessException(ErrorCode.SESSION_REQUEST_EXPIRED)
        }
        session.status = RemoteSessionStatus.REJECTED
        session.rejectedAt = now
        afterCommit.execute { sendBoth(session, event("CONTROL_RESULT", session, controlResult("REJECTED"))) }
        return SessionResponse.from(session)
    }

    @Transactional
    fun end(deviceId: String, sessionId: String): SessionResponse {
        val session = lockSessionAndDevices(sessionId)
        requireParticipant(session, deviceId)
        if (!session.isActive()) throw BusinessException(ErrorCode.SESSION_INVALID_STATE)
        session.status = RemoteSessionStatus.ENDED
        session.endedAt = clock.instant()
        afterCommit.execute { sendBoth(session, event("SESSION_ENDED", session, mapOf("reason" to "ENDED_BY_DEVICE"))) }
        return SessionResponse.from(session)
    }

    private fun lockSessionAndDevices(sessionId: String): RemoteSession {
        val initial = sessionRepository.findById(sessionId).orElseThrow { BusinessException(ErrorCode.SESSION_NOT_FOUND) }
        deviceLockService.lockPair(initial.controllerDeviceId, initial.controlledDeviceId)
        return sessionRepository.findByIdForUpdate(sessionId) ?: throw BusinessException(ErrorCode.SESSION_NOT_FOUND)
    }

    private fun requireParticipant(session: RemoteSession, deviceId: String) {
        if (!session.involves(deviceId)) throw BusinessException(ErrorCode.SESSION_NOT_ALLOWED)
    }

    private fun fail(session: RemoteSession, now: java.time.Instant, reason: String) {
        session.status = RemoteSessionStatus.FAILED
        session.failedAt = now
        session.failureReason = reason
    }

    private fun expireIfDue(session: RemoteSession, now: java.time.Instant) {
        when {
            session.status == RemoteSessionStatus.REQUESTING && !now.isBefore(session.expiresAt) -> {
                fail(session, now, "REQUEST_EXPIRED")
                afterCommit.execute { sendBoth(session, event("CONTROL_RESULT", session, controlResult("EXPIRED"))) }
            }
            session.status == RemoteSessionStatus.CONNECTING && session.connectExpiresAt?.let { !now.isBefore(it) } == true -> {
                fail(session, now, "CONNECT_TIMEOUT")
                afterCommit.execute { sendBoth(session, event("SESSION_FAILED", session, mapOf("reason" to "CONNECT_TIMEOUT"))) }
            }
        }
    }

    private fun controlResult(result: String, reason: String? = null): Map<String, Any?> =
        mapOf("scope" to "SESSION_REQUEST", "result" to result, "reason" to reason)

    private fun event(type: String, session: RemoteSession, payload: Any) =
        WsOutboundMessage(type = type, sessionId = session.id, timestamp = clock.millis(), payload = payload)

    private fun sendBoth(session: RemoteSession, event: WsOutboundMessage) {
        gateway.send(session.controllerDeviceId, event)
        gateway.send(session.controlledDeviceId, event)
    }

    private fun normalize(a: String, b: String) = if (a < b) a to b else b to a
    private fun Device.summary() = DeviceSummary(id, deviceName, platform, model, androidVersion)

    companion object {
        val REQUEST_TTL: Duration = Duration.ofSeconds(60)
        val CONNECT_TTL: Duration = Duration.ofSeconds(120)
    }
}
