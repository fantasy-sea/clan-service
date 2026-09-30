package com.fantasysea.clanservice.binding.repository

import com.fantasysea.clanservice.binding.entity.DeviceBinding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DeviceBindingRepository : JpaRepository<DeviceBinding, Long> {
    fun existsByDeviceLowIdAndDeviceHighId(deviceLowId: String, deviceHighId: String): Boolean
    fun findByDeviceLowIdOrDeviceHighId(deviceLowId: String, deviceHighId: String): List<DeviceBinding>

    @Query("select b from DeviceBinding b where (b.deviceLowId = :low and b.deviceHighId = :high)")
    fun findPair(@Param("low") low: String, @Param("high") high: String): DeviceBinding?
}
