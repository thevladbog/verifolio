package com.verifolio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@Modulithic
@SpringBootApplication
@ConfigurationPropertiesScan
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
