package com.fantasysea.clanservice.pairing.repository

import com.fantasysea.clanservice.pairing.entity.PairingCode
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable

interface PairingCodeRepository : JpaRepository<PairingCode, Long> {
    fun existsByCodeHash(codeHash: String): Boolean

    @Modifying
    @Query(
        value = "INSERT IGNORE INTO pairing_codes (device_id, code_hash, expires_at, consumed_at, created_at) " +
            "VALUES (:deviceId, :codeHash, :expiresAt, NULL, :createdAt)",
        nativeQuery = true,
    )
    fun insertIgnoringDuplicate(
        @Param("deviceId") deviceId: String,
        @Param("codeHash") codeHash: String,
        @Param("expiresAt") expiresAt: Instant,
        @Param("createdAt") createdAt: Instant,
    ): Int

    @Query("select p from PairingCode p where p.codeHash = :codeHash")
    fun findCodeByHash(@Param("codeHash") codeHash: String): PairingCode?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingCode p where p.codeHash = :codeHash")
    fun findByCodeHashForUpdate(@Param("codeHash") codeHash: String): PairingCode?

    @Modifying
    @Query("update PairingCode p set p.consumedAt = :now where p.deviceId = :deviceId and p.consumedAt is null")
    fun consumeActiveForDevice(@Param("deviceId") deviceId: String, @Param("now") now: Instant): Int

    fun findByExpiresAtBefore(cutoff: Instant, pageable: Pageable): List<PairingCode>
}
