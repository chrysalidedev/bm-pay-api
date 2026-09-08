package com.buildmain.plugins

import com.buildmain.services.LiveGivingManager
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    val logger = LoggerFactory.getLogger("SocketsPlugin")

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        /**
         * WS /ws/live/{churchId}
         * Flux WebSocket temps réel des offrandes collectées pour une église donnée.
         */
        webSocket("/ws/live/{churchId}") {
            val churchId = call.parameters["churchId"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "churchId manquant"))
                return@webSocket
            }

            logger.info("Connexion WebSocket établie pour la paroisse $churchId")
            LiveGivingManager.register(churchId, this)

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        logger.debug("Message reçu du client WS: $text")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Fin de session WebSocket ($churchId): ${e.message}")
            } finally {
                LiveGivingManager.unregister(churchId, this)
            }
        }

        /**
         * WS /ws/monitoring
         * Flux WebSocket temps réel des événements, logs, appels API Jèko et Webhooks.
         */
        webSocket("/ws/monitoring") {
            logger.info("Connexion WebSocket client Monitoring établie")
            com.buildmain.services.MonitoringService.registerSubscriber(this)
            try {
                for (frame in incoming) {
                    // Ignorer les messages montants
                }
            } catch (e: Exception) {
                logger.debug("Déconnexion client WS Monitoring: ${e.message}")
            } finally {
                com.buildmain.services.MonitoringService.unregisterSubscriber(this)
            }
        }
    }
}
