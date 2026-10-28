package com.fantasysea.clanservice.signaling.dto

import com.fantasysea.clanservice.common.validation.optionalInt
import com.fantasysea.clanservice.common.validation.optionalText
import com.fantasysea.clanservice.common.validation.requiredText
import tools.jackson.databind.JsonNode

/** WebRTC SDP。服务端只做大小/类型校验，不解析内容。 */
data class SdpPayload(val sdp: String) {
    fun toPayload(): Map<String, Any> = mapOf("sdp" to sdp)

    companion object {
        private const val MAX_SDP_BYTES = 32 * 1024

        fun parse(payload: JsonNode): SdpPayload = SdpPayload(payload.requiredText("sdp", MAX_SDP_BYTES))
    }
}

/**
 * Trickle ICE candidate。candidate 允许空串，用于表达 end-of-candidates；
 * 服务端不保存、不解析候选内容，只按规则转发。
 */
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
) {
    fun toPayload(): Map<String, Any> = buildMap {
        put("candidate", candidate)
        if (sdpMid != null) put("sdpMid", sdpMid)
        if (sdpMLineIndex != null) put("sdpMLineIndex", sdpMLineIndex)
        if (usernameFragment != null) put("usernameFragment", usernameFragment)
    }

    companion object {
        private const val MAX_CANDIDATE_BYTES = 4 * 1024
        private const val MAX_SDP_MID_BYTES = 64
        private const val MAX_USERNAME_FRAGMENT_BYTES = 256
        private val SDP_MLINE_INDEX = 0..255

        fun parse(payload: JsonNode): IceCandidatePayload = IceCandidatePayload(
            candidate = payload.requiredText("candidate", MAX_CANDIDATE_BYTES, allowBlank = true),
            sdpMid = payload.optionalText("sdpMid", MAX_SDP_MID_BYTES, allowBlank = false),
            sdpMLineIndex = payload.optionalInt("sdpMLineIndex", SDP_MLINE_INDEX),
            usernameFragment = payload.optionalText("usernameFragment", MAX_USERNAME_FRAGMENT_BYTES, allowBlank = false),
        )
    }
}
