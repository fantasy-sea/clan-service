package com.fantasysea.clanservice.pairing.repository

import com.fantasysea.clanservice.pairing.entity.PairingRequest
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable

interface PairingRequestRepository : JpaRepository<PairingRequest, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingRequest p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PairingRequest?

    @Query("select count(p) from PairingRequest p where p.status = :status and ((p.requesterDeviceId = :a and p.targetDeviceId = :b) or (p.requesterDeviceId = :b and p.targetDeviceId = :a))")
    fun countForPairAndStatus(@Param("a") a: String, @Param("b") b: String, @Param("status") status: PairingRequestStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingRequest p where p.status = :status and ((p.requesterDeviceId = :a and p.targetDeviceId = :b) or (p.requesterDeviceId = :b and p.targetDeviceId = :a))")
    fun findForPairAndStatusForUpdate(
        @Param("a") a: String,
        @Param("b") b: String,
        @Param("status") status: PairingRequestStatus,
    ): List<PairingRequest>

    fun findByStatusAndExpiresAtBefore(status: PairingRequestStatus, now: Instant, pageable: Pageable): List<PairingRequest>
    fun findByStatus(status: PairingRequestStatus, pageable: Pageable): List<PairingRequest>
    fun findByStatusAndTargetDeviceId(status: PairingRequestStatus, targetDeviceId: String): List<PairingRequest>
    @Query("select p from PairingRequest p where p.status = :status and (p.requesterDeviceId = :deviceId or p.targetDeviceId = :deviceId)")
    fun findByStatusAndDevice(@Param("status") status: PairingRequestStatus, @Param("deviceId") deviceId: String): List<PairingRequest>
    fun findByStatusNotAndHandledAtBefore(status: PairingRequestStatus, cutoff: Instant, pageable: Pageable): List<PairingRequest>
}
