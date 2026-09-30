package com.fantasysea.clanservice.common.config

import com.fantasysea.clanservice.common.security.DeviceAuthenticationInterceptor
import com.fantasysea.clanservice.common.ratelimit.RateLimitInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val deviceAuthenticationInterceptor: DeviceAuthenticationInterceptor,
    private val rateLimitInterceptorProvider: ObjectProvider<RateLimitInterceptor>,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        rateLimitInterceptorProvider.ifAvailable?.let {
            registry.addInterceptor(it).addPathPatterns("/api/v1/devices/register", "/api/v1/pairing/verify")
        }
        registry.addInterceptor(deviceAuthenticationInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/v1/devices/register")
    }
}
