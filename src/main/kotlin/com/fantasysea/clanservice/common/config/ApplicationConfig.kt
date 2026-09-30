package com.fantasysea.clanservice.common.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class ApplicationConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
