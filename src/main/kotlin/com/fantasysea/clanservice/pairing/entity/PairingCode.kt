package com.fantasysea.clanservice.pairing.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "pairing_codes")
class PairingCode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "device_id", nullable = false, length = 64)
    val deviceId: String,
    @Column(name = "code_hash", nullable = false, length = 64, unique = true)
    val codeHash: String,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
)
