package com.fantasysea.clanservice.pairing.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class PairingRequestStatus { PENDING, ACCEPTED, REJECTED, EXPIRED }

@Entity
@Table(name = "pairing_requests")
class PairingRequest(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    val id: String,
    @Column(name = "requester_device_id", nullable = false, length = 64)
    val requesterDeviceId: String,
    @Column(name = "target_device_id", nullable = false, length = 64)
    val targetDeviceId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: PairingRequestStatus,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "handled_at")
    var handledAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
)
