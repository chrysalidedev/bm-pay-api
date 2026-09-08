package com.buildmain

import com.buildmain.config.DatabaseFactory
import com.buildmain.plugins.configureHTTP
import com.buildmain.plugins.configureRouting
import com.buildmain.plugins.configureSerialization
import com.buildmain.plugins.configureSockets
import com.buildmain.services.JekoPaymentService
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("ApplicationMain")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    logger.info("Démarrage du serveur Build-Main sur le port $port...")

    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // 1. Initialisation de la base de données (PostgreSQL / Exposed)
    try {
        DatabaseFactory.init()
    } catch (e: Exception) {
        LoggerFactory.getLogger("ApplicationModule")
            .warn("Attention: Initialisation PostgreSQL ignorée (utiliser DB_JDBC_URL pour connecter la BD réelle): ${e.message}")
    }

    // 2. Service Jèko
    val jekoPaymentService = JekoPaymentService()

    // 3. Configuration des plugins Ktor
    configureSerialization()
    configureHTTP()
    configureSockets()
    configureRouting(jekoPaymentService)
}
