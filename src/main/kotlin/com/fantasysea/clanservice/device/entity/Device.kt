package com.fantasysea.clanservice.device.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * Device 实体（后端设计 §7）。
 * 普通 class 而非 data class：equals/hashCode 只按 id，避免状态变化破坏集合语义。
 * 应用侧生成 dev_{ULID} id；数据库只保存 deviceTokenHash，绝不保存明文 token。
 */
@Entity
@Table(
    name = "devices",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_device_token_hash", columnNames = ["device_token_hash"]),
    ],
    indexes = [
        Index(name = "idx_devices_online", columnList = "online"),
    ],
)
class Device(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    val id: String,

    @Column(name = "device_name", nullable = false, length = 128)
    var deviceName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 32)
    var platform: DevicePlatform,

    @Column(name = "model", length = 128)
    var model: String? = null,

    @Column(name = "android_version", length = 64)
    var androidVersion: String? = null,

    @Column(name = "device_token_hash", nullable = false, length = 128)
    var deviceTokenHash: String,

    // MySQL BOOLEAN 实际为 TINYINT(1)，显式声明 TINYINT 以保证 ddl-auto=validate 通过。
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "online", nullable = false)
    var online: Boolean = false,

    // Instant 统一映射到 DATETIME(6)（配合 preferred_instant_jdbc_type=TIMESTAMP 与 UTC 时区）。
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Device) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Device(id=" + id + ", deviceName=" + deviceName + ", platform=" + platform + ", online=" + online + ")"

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
