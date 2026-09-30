package com.fantasysea.clanservice.pairing.dto

import com.fantasysea.clanservice.pairing.entity.PairingRequest
import java.time.Instant
import jakarta.validation.constraints.Pattern

data class PairingCodeResponse(val pairingCode: String, val expiresAt: Instant)

data class VerifyPairingCodeRequest(
    @field:Pattern(regexp = "^[0-9]{6}$", message = "pairingCode must contain exactly six digits")
    val pairingCode: String,
)

data class PairingVerifyResponse(val requestId: String, val expiresAt: Instant)

data class PairingRequestResponse(
    val requestId: String,
    val status: String,
    val expiresAt: Instant,
    val handledAt: Instant?,
) {
    companion object {
        fun from(request: PairingRequest) = PairingRequestResponse(
            requestId = request.id,
            status = request.status.name,
            expiresAt = request.expiresAt,
            handledAt = request.handledAt,
        )
    }
}
