package com.buildmain.plugins

import com.buildmain.config.EnvConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

fun Application.configureHTTP() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("X-API-KEY")
        allowHeader("X-API-KEY-ID")
        allowHeader("Jeko-Signature")

        val allowedOrigins = EnvConfig.getAllowedCorsOrigins()
        if (allowedOrigins.isNotEmpty() && EnvConfig.isProduction()) {
            allowedOrigins.forEach { origin ->
                val clean = origin.removePrefix("https://").removePrefix("http://")
                val schemes = if (origin.startsWith("http://")) listOf("http") else listOf("https", "http")
                allowHost(clean, schemes = schemes)
            }
        } else {
            // En environnement local ou dev, autorise tous les hôtes pour les tests rapides
            anyHost()
        }
    }

    install(io.ktor.server.plugins.calllogging.CallLogging) {
        level = org.slf4j.event.Level.INFO
    }
}
