package com.fantasysea.clanservice.common.config

import com.fantasysea.clanservice.common.security.DeviceAuthenticationInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val deviceAuthenticationInterceptor: DeviceAuthenticationInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(deviceAuthenticationInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/v1/devices/register")
    }
}
