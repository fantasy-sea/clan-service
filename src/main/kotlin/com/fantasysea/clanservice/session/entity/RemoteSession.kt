package com.fantasysea.clanservice.session.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class RemoteSessionStatus { REQUESTING, CONNECTING, CONNECTED, REJECTED, ENDED, FAILED }

@Entity
@Table(name = "remote_sessions")
class RemoteSession(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    val id: String,
    @Column(name = "controller_device_id", nullable = false, length = 64)
    val controllerDeviceId: String,
    @Column(name = "controlled_device_id", nullable = false, length = 64)
    val controlledDeviceId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: RemoteSessionStatus,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "accepted_at") var acceptedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "connect_expires_at") var connectExpiresAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "connected_at") var connectedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "rejected_at") var rejectedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "ended_at") var endedAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "failed_at") var failedAt: Instant? = null,
    @Column(name = "failure_reason", length = 64) var failureReason: String? = null,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    fun isActive(): Boolean = status in ACTIVE_STATUSES
    fun involves(deviceId: String): Boolean = controllerDeviceId == deviceId || controlledDeviceId == deviceId

    companion object {
        val ACTIVE_STATUSES = setOf(RemoteSessionStatus.REQUESTING, RemoteSessionStatus.CONNECTING, RemoteSessionStatus.CONNECTED)
    }
}
