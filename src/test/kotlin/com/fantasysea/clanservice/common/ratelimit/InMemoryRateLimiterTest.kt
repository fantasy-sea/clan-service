package com.fantasysea.clanservice.common.ratelimit

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InMemoryRateLimiterTest {
    @Test
    fun `rejects requests beyond a fixed window and reports retry after`() {
        val limiter = InMemoryRateLimiter(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        repeat(2) { limiter.check("auth", "127.0.0.1", 2, Duration.ofMinutes(1)) }

        val error = assertThrows(BusinessException::class.java) {
            limiter.check("auth", "127.0.0.1", 2, Duration.ofMinutes(1))
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(60, error.retryAfterSeconds)
    }
}
