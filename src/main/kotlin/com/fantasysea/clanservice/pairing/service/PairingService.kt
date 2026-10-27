package com.fantasysea.clanservice.pairing.service

import com.fantasysea.clanservice.binding.dto.DeviceSummary
import com.fantasysea.clanservice.binding.entity.DeviceBinding
import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.persistence.DeviceLockService
import com.fantasysea.clanservice.common.ratelimit.InMemoryRateLimiter
import com.fantasysea.clanservice.common.transaction.AfterCommitExecutor
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.pairing.dto.PairingCodeResponse
import com.fantasysea.clanservice.pairing.dto.PairingRequestResponse
import com.fantasysea.clanservice.pairing.dto.PairingVerifyResponse
import com.fantasysea.clanservice.pairing.entity.PairingRequest
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingCodeRepository
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.pairing.security.PairingCodeGenerator
import com.fantasysea.clanservice.pairing.security.PairingCodeHasher
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PairingService(
    private val codeRepository: PairingCodeRepository,
    private val requestRepository: PairingRequestRepository,
    private val bindingRepository: DeviceBindingRepository,
    private val deviceLockService: DeviceLockService,
    private val codeHasher: PairingCodeHasher,
    private val codeGenerator: PairingCodeGenerator,
    private val ulidGenerator: UlidGenerator,
    private val gateway: WsEventGateway,
    private val afterCommit: AfterCommitExecutor,
    private val rateLimiter: InMemoryRateLimiter,
    private val deliveryCompensator: DeliveryCompensator,
    private val clock: Clock,
) {
    @Transactional
    fun createCode(deviceId: String): PairingCodeResponse {
        deviceLockService.lock(deviceId)
        val now = clock.instant()
        codeRepository.consumeActiveForDevice(deviceId, now)
        repeat(MAX_CODE_ATTEMPTS) {
            val raw = codeGenerator.generate()
            val hash = codeHasher.hash(raw)
            if (!codeRepository.existsByCodeHash(hash)) {
                val expiresAt = now.plus(CODE_TTL)
                if (codeRepository.insertIgnoringDuplicate(deviceId, hash, expiresAt, now) == 1) {
                    return PairingCodeResponse(raw, expiresAt)
                }
            }
        }
        throw BusinessException(ErrorCode.INTERNAL_ERROR, "Unable to allocate pairing code")
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun verify(deviceId: String, rawCode: String): PairingVerifyResponse {
        rateLimiter.check("pairing-verify-device", deviceId, 5, Duration.ofMinutes(1))
        if (!rawCode.matches(Regex("^[0-9]{6}$"))) throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)
        val codeHash = codeHasher.hash(rawCode)
        val initial = codeRepository.findCodeByHash(codeHash) ?: throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)
        val targetId = initial.deviceId
        if (targetId == deviceId) throw BusinessException(ErrorCode.SELF_PAIRING)
        val (requester, target) = deviceLockService.lockPair(deviceId, targetId)
        val code = codeRepository.findByCodeHashForUpdate(codeHash) ?: throw BusinessException(ErrorCode.PAIRING_CODE_INVALID)
        val now = clock.instant()
        if (code.consumedAt != null) throw BusinessException(ErrorCode.PAIRING_CODE_USED)
        if (!now.isBefore(code.expiresAt)) throw BusinessException(ErrorCode.PAIRING_CODE_EXPIRED)
        val (low, high) = normalize(deviceId, targetId)
        if (bindingRepository.existsByDeviceLowIdAndDeviceHighId(low, high)) {
            throw BusinessException(ErrorCode.BINDING_ALREADY_EXISTS)
        }
        requestRepository.findForPairAndStatusForUpdate(deviceId, targetId, PairingRequestStatus.PENDING).forEach { pending ->
            if (now.isBefore(pending.expiresAt)) {
                throw BusinessException(ErrorCode.PAIRING_REQUEST_ALREADY_EXISTS)
            }
            pending.status = PairingRequestStatus.EXPIRED
            pending.handledAt = now
            afterCommit.execute { sendPairingResult(pending, "EXPIRED") }
        }
        if (!gateway.isReachable(requester.id) || !gateway.isReachable(target.id)) {
            throw BusinessException(ErrorCode.DEVICE_OFFLINE)
        }
        code.consumedAt = now
        val request = PairingRequest(
            id = "pairreq_${ulidGenerator.ulid()}",
            requesterDeviceId = requester.id,
            targetDeviceId = target.id,
            status = PairingRequestStatus.PENDING,
            expiresAt = now.plus(REQUEST_TTL),
            createdAt = now,
        )
        requestRepository.save(request)
        afterCommit.execute {
            val delivered = gateway.send(target.id, message("PAIRING_REQUEST", request.id, payload = mapOf(
                "requester" to requester.summary(), "expiresAt" to request.expiresAt,
            )))
            if (!delivered) {
                try {
                    deliveryCompensator.expirePairing(request.id, clock.instant())
                } catch (ex: BusinessException) {
                    if (ex.errorCode != ErrorCode.DEVICE_NOT_FOUND) throw ex
                }
                throw BusinessException(ErrorCode.DEVICE_OFFLINE)
            }
        }
        return PairingVerifyResponse(request.id, request.expiresAt)
    }

    @Transactional(noRollbackFor = [BusinessException::class])
    fun accept(deviceId: String, requestId: String): PairingRequestResponse = handle(deviceId, requestId, true)

    @Transactional(noRollbackFor = [BusinessException::class])
    fun reject(deviceId: String, requestId: String): PairingRequestResponse = handle(deviceId, requestId, false)

    /**
     * 请求方取消仍处于 PENDING 的配对请求。
     * 取消复用 REJECTED 状态，不创建绑定；双方收到 PAIRING_RESULT(REJECTED)。
     */
    @Transactional(noRollbackFor = [BusinessException::class])
    fun cancel(deviceId: String, requestId: String): PairingRequestResponse {
        val initial = requestRepository.findById(requestId).orElseThrow {
            BusinessException(ErrorCode.PAIRING_REQUEST_NOT_FOUND)
        }
        deviceLockService.lockPair(initial.requesterDeviceId, initial.targetDeviceId)
        val request = requestRepository.findByIdForUpdate(requestId)
            ?: throw BusinessException(ErrorCode.PAIRING_REQUEST_NOT_FOUND)
        if (deviceId != request.requesterDeviceId) throw BusinessException(ErrorCode.PAIRING_REQUEST_NOT_ALLOWED)
        if (request.status != PairingRequestStatus.PENDING) throw BusinessException(ErrorCode.PAIRING_REQUEST_ALREADY_HANDLED)
        val now = clock.instant()
        if (!now.isBefore(request.expiresAt)) {
            request.status = PairingRequestStatus.EXPIRED
            request.handledAt = now
            afterCommit.execute { sendPairingResult(request, "EXPIRED") }
            throw BusinessException(ErrorCode.PAIRING_REQUEST_EXPIRED)
        }
        request.status = PairingRequestStatus.REJECTED
        request.handledAt = now
        afterCommit.execute { sendPairingResult(request, request.status.name) }
        return PairingRequestResponse.from(request)
    }

    private fun handle(deviceId: String, requestId: String, accepted: Boolean): PairingRequestResponse {
        val initial = requestRepository.findById(requestId).orElseThrow {
            BusinessException(ErrorCode.PAIRING_REQUEST_NOT_FOUND)
        }
        val (requester, target) = deviceLockService.lockPair(initial.requesterDeviceId, initial.targetDeviceId)
        val request = requestRepository.findByIdForUpdate(requestId)
            ?: throw BusinessException(ErrorCode.PAIRING_REQUEST_NOT_FOUND)
        if (deviceId != request.targetDeviceId) throw BusinessException(ErrorCode.PAIRING_REQUEST_NOT_ALLOWED)
        if (request.status != PairingRequestStatus.PENDING) throw BusinessException(ErrorCode.PAIRING_REQUEST_ALREADY_HANDLED)
        val now = clock.instant()
        if (!now.isBefore(request.expiresAt)) {
            request.status = PairingRequestStatus.EXPIRED
            request.handledAt = now
            afterCommit.execute { sendPairingResult(request, "EXPIRED") }
            throw BusinessException(ErrorCode.PAIRING_REQUEST_EXPIRED)
        }
        request.status = if (accepted) PairingRequestStatus.ACCEPTED else PairingRequestStatus.REJECTED
        request.handledAt = now
        if (accepted) {
            val (low, high) = normalize(request.requesterDeviceId, request.targetDeviceId)
            if (!bindingRepository.existsByDeviceLowIdAndDeviceHighId(low, high)) {
                bindingRepository.save(DeviceBinding.of(low, high, now))
            }
        }
        afterCommit.execute {
            sendPairingResult(request, request.status.name)
            if (accepted) {
                gateway.send(requester.id, message("PAIRING_SUCCEEDED", request.id, payload = mapOf("device" to target.summary())))
                gateway.send(target.id, message("PAIRING_SUCCEEDED", request.id, payload = mapOf("device" to requester.summary())))
            }
        }
        return PairingRequestResponse.from(request)
    }

    private fun sendPairingResult(request: PairingRequest, result: String) {
        val payload = mapOf("result" to result, "expiresAt" to request.expiresAt)
        val event = message("PAIRING_RESULT", request.id, payload = payload)
        gateway.send(request.requesterDeviceId, event)
        gateway.send(request.targetDeviceId, event)
    }

    private fun message(type: String, requestId: String, payload: Any) =
        WsOutboundMessage(type = type, requestId = requestId, timestamp = clock.millis(), payload = payload)

    private fun normalize(a: String, b: String) = if (a < b) a to b else b to a
    private fun Device.summary() = DeviceSummary(id, deviceName, platform, model, androidVersion)

    companion object {
        val CODE_TTL: Duration = Duration.ofSeconds(60)
        val REQUEST_TTL: Duration = Duration.ofSeconds(60)
        const val MAX_CODE_ATTEMPTS = 20
    }
}
