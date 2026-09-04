package com.fantasysea.clanservice.device.service

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.security.DeviceTokenService
import com.fantasysea.clanservice.common.util.UlidGenerator
import com.fantasysea.clanservice.device.dto.DeviceResponse
import com.fantasysea.clanservice.device.dto.RegisterDeviceRequest
import com.fantasysea.clanservice.device.dto.RegisterDeviceResponse
import com.fantasysea.clanservice.device.dto.UpdateDeviceRequest
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.entity.DevicePlatform
import com.fantasysea.clanservice.device.repository.DeviceRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceServiceImpl(
    private val deviceRepository: DeviceRepository,
    private val deviceTokenService: DeviceTokenService,
    private val ulidGenerator: UlidGenerator,
) : DeviceService {

    @Transactional
    override fun register(request: RegisterDeviceRequest): RegisterDeviceResponse {
        val rawToken = deviceTokenService.generate()
        val device = Device(
            id = "dev_" + ulidGenerator.ulid(),
            deviceName = request.deviceName.trim(),
            platform = parsePlatform(request.platform.trim()),
            model = request.model?.takeIf { it.isNotBlank() },
            androidVersion = request.androidVersion?.takeIf { it.isNotBlank() },
            deviceTokenHash = deviceTokenService.hash(rawToken),
        )
        deviceRepository.save(device)
        return RegisterDeviceResponse(deviceId = device.id, deviceToken = rawToken)
    }

    @Transactional(readOnly = true)
    override fun getDevice(deviceId: String): DeviceResponse = DeviceResponse.from(findEntity(deviceId))

    @Transactional
    override fun updateDevice(deviceId: String, request: UpdateDeviceRequest): DeviceResponse {
        val hasChange = request.deviceName != null || request.platform != null ||
            request.model != null || request.androidVersion != null
        if (!hasChange) {
            throw BusinessException(ErrorCode.INVALID_PARAMETER, "At least one field must be provided")
        }
        val device = findEntity(deviceId)
        request.deviceName?.let { value ->
            if (value.isBlank()) throw BusinessException(ErrorCode.INVALID_PARAMETER, "deviceName must not be blank")
            device.deviceName = value.trim()
        }
        request.platform?.let { value ->
            if (value.isBlank()) throw BusinessException(ErrorCode.INVALID_PARAMETER, "platform must not be blank")
            device.platform = parsePlatform(value.trim())
        }
        request.model?.let { value -> device.model = value.takeIf { it.isNotBlank() } }
        request.androidVersion?.let { value -> device.androidVersion = value.takeIf { it.isNotBlank() } }
        return DeviceResponse.from(device)
    }

    @Transactional
    override fun deleteDevice(deviceId: String) {
        deviceRepository.delete(findEntity(deviceId))
    }

    @Transactional
    override fun markOnline(deviceId: String) {
        val device = findEntity(deviceId)
        device.online = true
        device.lastSeenAt = Instant.now()
    }

    @Transactional
    override fun markOffline(deviceId: String) {
        val device = findEntity(deviceId)
        device.online = false
        device.lastSeenAt = Instant.now()
    }

    @Transactional
    override fun updateLastSeen(deviceId: String) {
        findEntity(deviceId).lastSeenAt = Instant.now()
    }

    private fun findEntity(deviceId: String): Device =
        deviceRepository.findById(deviceId).orElseThrow {
            BusinessException(ErrorCode.DEVICE_NOT_FOUND)
        }

    private fun parsePlatform(value: String): DevicePlatform =
        DevicePlatform.entries.firstOrNull { it.name == value }
            ?: throw BusinessException(ErrorCode.INVALID_PARAMETER, "Unknown platform: " + value)
}
