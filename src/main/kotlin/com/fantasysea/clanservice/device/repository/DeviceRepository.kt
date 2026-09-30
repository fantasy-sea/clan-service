package com.fantasysea.clanservice.device.repository

import com.fantasysea.clanservice.device.entity.Device
import org.springframework.data.jpa.repository.JpaRepository
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DeviceRepository : JpaRepository<Device, String> {

    fun findByDeviceTokenHash(deviceTokenHash: String): Device?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): Device?

    @Modifying(clearAutomatically = true)
    @Query("update Device d set d.online = false, d.lastSeenAt = :now where d.online = true")
    fun clearOnlineState(@Param("now") now: Instant): Int
}
