package com.fantasysea.clanservice.binding.entity

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
@Table(name = "device_bindings")
class DeviceBinding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "device_low_id", nullable = false, length = 64)
    val deviceLowId: String,
    @Column(name = "device_high_id", nullable = false, length = 64)
    val deviceHighId: String,
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "bound_at", nullable = false, updatable = false)
    val boundAt: Instant,
) {
    companion object {
        fun of(first: String, second: String, boundAt: Instant): DeviceBinding {
            require(first != second) { "A device cannot bind to itself" }
            val (low, high) = if (first < second) first to second else second to first
            return DeviceBinding(deviceLowId = low, deviceHighId = high, boundAt = boundAt)
        }
    }

    fun peerOf(deviceId: String): String = when (deviceId) {
        deviceLowId -> deviceHighId
        deviceHighId -> deviceLowId
        else -> throw IllegalArgumentException("Device is not part of binding")
    }
}
