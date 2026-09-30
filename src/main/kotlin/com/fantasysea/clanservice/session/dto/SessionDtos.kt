package com.fantasysea.clanservice.session.dto

import com.fantasysea.clanservice.session.entity.RemoteSession
import java.time.Instant
import jakarta.validation.constraints.NotBlank

data class CreateSessionRequest(@field:NotBlank val targetDeviceId: String)

data class SessionResponse(
    val sessionId: String,
    val controllerDeviceId: String,
    val controlledDeviceId: String,
    val status: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val acceptedAt: Instant?,
    val connectExpiresAt: Instant?,
    val connectedAt: Instant?,
    val rejectedAt: Instant?,
    val endedAt: Instant?,
    val failedAt: Instant?,
    val failureReason: String?,
) {
    companion object {
        fun from(session: RemoteSession) = SessionResponse(
            sessionId = session.id,
            controllerDeviceId = session.controllerDeviceId,
            controlledDeviceId = session.controlledDeviceId,
            status = session.status.name,
            expiresAt = session.expiresAt,
            createdAt = session.createdAt,
            acceptedAt = session.acceptedAt,
            connectExpiresAt = session.connectExpiresAt,
            connectedAt = session.connectedAt,
            rejectedAt = session.rejectedAt,
            endedAt = session.endedAt,
            failedAt = session.failedAt,
            failureReason = session.failureReason,
        )
    }
}
