package com.fantasysea.clanservice.device.repository

import com.fantasysea.clanservice.device.entity.Device
import org.springframework.data.jpa.repository.JpaRepository

interface DeviceRepository : JpaRepository<Device, String> {

    fun findByDeviceTokenHash(deviceTokenHash: String): Device?
}
