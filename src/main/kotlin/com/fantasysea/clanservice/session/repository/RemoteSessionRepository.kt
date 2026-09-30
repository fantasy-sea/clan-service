package com.fantasysea.clanservice.session.repository

import com.fantasysea.clanservice.session.entity.RemoteSession
import com.fantasysea.clanservice.session.entity.RemoteSessionStatus
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable

interface RemoteSessionRepository : JpaRepository<RemoteSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RemoteSession s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): RemoteSession?

    @Query("select count(s) from RemoteSession s where s.status in :statuses and (s.controllerDeviceId = :deviceId or s.controlledDeviceId = :deviceId)")
    fun countActiveForDevice(@Param("deviceId") deviceId: String, @Param("statuses") statuses: Collection<RemoteSessionStatus>): Long

    @Query("select s from RemoteSession s where s.status in :statuses and (s.controllerDeviceId = :deviceId or s.controlledDeviceId = :deviceId)")
    fun findActiveForDevice(@Param("deviceId") deviceId: String, @Param("statuses") statuses: Collection<RemoteSessionStatus>): List<RemoteSession>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RemoteSession s where s.status in :statuses and (s.controllerDeviceId in (:firstId, :secondId) or s.controlledDeviceId in (:firstId, :secondId))")
    fun findActiveForDevicesForUpdate(
        @Param("firstId") firstId: String,
        @Param("secondId") secondId: String,
        @Param("statuses") statuses: Collection<RemoteSessionStatus>,
    ): List<RemoteSession>

    fun findByStatusAndExpiresAtBefore(status: RemoteSessionStatus, now: Instant, pageable: Pageable): List<RemoteSession>
    fun findByStatusAndConnectExpiresAtBefore(status: RemoteSessionStatus, now: Instant, pageable: Pageable): List<RemoteSession>
    fun findByStatusIn(statuses: Collection<RemoteSessionStatus>, pageable: Pageable): List<RemoteSession>
    @Query(
        "select s from RemoteSession s where " +
            "(s.rejectedAt is not null and s.rejectedAt < :cutoff) or " +
            "(s.endedAt is not null and s.endedAt < :cutoff) or " +
            "(s.failedAt is not null and s.failedAt < :cutoff)",
    )
    fun findTerminalBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<RemoteSession>
}
