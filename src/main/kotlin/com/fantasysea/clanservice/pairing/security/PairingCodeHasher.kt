package com.fantasysea.clanservice.pairing.security

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "clan.pairing")
data class PairingProperties(var hmacKey: String = "")

@Component
class PairingCodeHasher(properties: PairingProperties) {
    private val key = properties.hmacKey.toByteArray(StandardCharsets.UTF_8).also {
        require(it.size >= 32) { "clan.pairing.hmac-key must contain at least 32 UTF-8 bytes" }
    }

    fun hash(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(code.toByteArray(StandardCharsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }
}
