package com.fantasysea.clanservice.common.persistence

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.device.entity.Device
import com.fantasysea.clanservice.device.repository.DeviceRepository
import org.springframework.stereotype.Component

@Component
class DeviceLockService(private val deviceRepository: DeviceRepository) {
    fun lockAll(deviceIds: Collection<String>): List<Device> = deviceIds.distinct().sorted().map { id ->
        deviceRepository.findByIdForUpdate(id) ?: throw BusinessException(ErrorCode.DEVICE_NOT_FOUND)
    }

    fun lockPair(firstId: String, secondId: String): Pair<Device, Device> {
        val ids = listOf(firstId, secondId).distinct().sorted()
        if (ids.size != 2) throw BusinessException(ErrorCode.SELF_PAIRING)
        val locked = lockAll(ids)
        val byId = locked.associateBy { it.id }
        return byId.getValue(firstId) to byId.getValue(secondId)
    }

    fun lock(deviceId: String): Device =
        deviceRepository.findByIdForUpdate(deviceId) ?: throw BusinessException(ErrorCode.DEVICE_NOT_FOUND)
}
