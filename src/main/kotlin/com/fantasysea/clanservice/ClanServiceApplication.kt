package com.fantasysea.clanservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class ClanServiceApplication

fun main(args: Array<String>) {
    runApplication<ClanServiceApplication>(*args)
}
