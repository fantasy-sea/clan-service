package com.fantasysea.clanservice.pairing.security

import java.security.SecureRandom
import org.springframework.stereotype.Component

@Component
class PairingCodeGenerator {
    private val random = SecureRandom()
    fun generate(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
}
