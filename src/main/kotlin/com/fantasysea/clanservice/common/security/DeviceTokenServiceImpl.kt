package com.fantasysea.clanservice.common.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.springframework.stereotype.Component

@Component
class DeviceTokenServiceImpl : DeviceTokenService {

    private val secureRandom = SecureRandom()

    override fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    override fun matches(rawToken: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(rawToken).toByteArray(Charsets.US_ASCII),
            expectedHash.toByteArray(Charsets.US_ASCII),
        )

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
