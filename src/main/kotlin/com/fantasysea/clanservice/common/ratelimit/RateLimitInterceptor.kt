package com.fantasysea.clanservice.common.ratelimit

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import org.springframework.stereotype.Component
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RateLimitInterceptor(private val limiterProvider: ObjectProvider<InMemoryRateLimiter>) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val ip = request.remoteAddr ?: "unknown"
        val limiter = limiterProvider.ifAvailable ?: return true
        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
            ?: request.requestURI
        when {
            request.method == "POST" && route == "/api/v1/devices/register" ->
                limiter.check("device-register-ip", ip, 5, Duration.ofHours(1))
            request.method == "POST" && route == "/api/v1/pairing/verify" ->
                limiter.check("pairing-verify-ip", ip, 20, Duration.ofMinutes(1))
        }
        return true
    }
}
