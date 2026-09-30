package com.fantasysea.clanservice.common.ratelimit

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

@Component
class InMemoryRateLimiter(private val clock: Clock) {
    private data class Window(var startedAt: Instant, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    @Synchronized
    fun check(namespace: String, key: String, limit: Int, duration: Duration) {
        val now = clock.instant()
        val windowKey = "$namespace:$key"
        if (!windows.containsKey(windowKey) && windows.size >= MAX_ENTRIES) {
            evictExpiredInternal(now)
            if (windows.size >= MAX_ENTRIES) {
                windows.entries.minByOrNull { it.value.startedAt }?.key?.let(windows::remove)
            }
        }
        var retryAfter = 1L
        var allowed = true
        windows.compute(windowKey) { _, current ->
            val value = if (current == null || !now.isBefore(current.startedAt.plus(duration))) {
                Window(now, 1)
            } else {
                current.count += 1
                current
            }
            if (value.count > limit) {
                allowed = false
                retryAfter = Duration.between(now, value.startedAt.plus(duration)).seconds.coerceAtLeast(1)
            }
            value
        }
        if (!allowed) throw BusinessException(ErrorCode.RATE_LIMITED, retryAfterSeconds = retryAfter)
    }

    @Synchronized
    fun evictExpired(now: Instant = clock.instant()) {
        evictExpiredInternal(now)
    }

    private fun evictExpiredInternal(now: Instant) {
        windows.entries.asSequence()
            .filter { now.isAfter(it.value.startedAt.plus(MAX_WINDOW)) }
            .take(MAX_EVICTION_BATCH)
            .map { it.key }
            .toList()
            .forEach(windows::remove)
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_EVICTION_BATCH = 1_000
        val MAX_WINDOW: Duration = Duration.ofHours(1)
    }
}
