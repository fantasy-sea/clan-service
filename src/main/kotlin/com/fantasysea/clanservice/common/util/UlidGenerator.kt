package com.fantasysea.clanservice.common.util

import java.security.SecureRandom
import org.springframework.stereotype.Component

/**
 * 极简 ULID 生成器（不引入第三方库）：
 * 128 位 = 48 位毫秒时间戳（大端，前 6 字节）+ 80 位安全随机数（后 10 字节），
 * 按标准 ULID 位抽取编码为 26 位 Crockford Base32 大写字符（不含 I/L/O/U）。
 */
@Component
class UlidGenerator {

    private val secureRandom = SecureRandom()

    /** 生成 26 字符 ULID。SecureRandom 线程安全，无共享可变状态。 */
    fun ulid(): String {
        val timestamp = System.currentTimeMillis()
        val random = ByteArray(RANDOM_BYTES)
        secureRandom.nextBytes(random)

        val buffer = ByteArray(16)
        buffer[0] = (timestamp ushr 40).toByte()
        buffer[1] = (timestamp ushr 32).toByte()
        buffer[2] = (timestamp ushr 24).toByte()
        buffer[3] = (timestamp ushr 16).toByte()
        buffer[4] = (timestamp ushr 8).toByte()
        buffer[5] = timestamp.toByte()
        random.copyInto(buffer, destinationOffset = TIMESTAMP_BYTES)
        return encodeBase32(buffer)
    }

    private fun encodeBase32(bytes16: ByteArray): String {
        // ULID 用 26 个 Base32 字符承载 128 位，因此编码前必须补两个高位 0。
        val sb = StringBuilder(26)
        for (i in 0 until 26) {
            var value = 0
            repeat(5) { bitInCharacter ->
                value = value shl 1
                val sourceBit = i * 5 + bitInCharacter - 2
                if (sourceBit >= 0) {
                    val byteIndex = sourceBit / 8
                    val bitIndex = 7 - sourceBit % 8
                    value = value or ((bytes16[byteIndex].toInt() ushr bitIndex) and 1)
                }
            }
            sb.append(ALPHABET[value])
        }
        return sb.toString()
    }

    private companion object {
        /** Crockford Base32（排除 I、L、O、U）。 */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val TIMESTAMP_BYTES = 6
        const val RANDOM_BYTES = 10
    }
}
