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
        // 17 字节工作缓冲区：第 17 个字节恒为 0，避免最后一位编码越界。
        val buffer = ByteArray(17)
        bytes16.copyInto(buffer)
        val sb = StringBuilder(26)
        for (i in 0 until 26) {
            val bitPos = i * 5
            val bytePos = bitPos / 8
            val shift = 8 - (bitPos % 8) - 5
            val value = if (shift >= 0) {
                (buffer[bytePos].toInt() and 0xFF) ushr shift
            } else {
                ((buffer[bytePos].toInt() and 0xFF) shl -shift) or
                    ((buffer[bytePos + 1].toInt() and 0xFF) ushr (8 + shift))
            }
            sb.append(ALPHABET[value and 0x1F])
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
