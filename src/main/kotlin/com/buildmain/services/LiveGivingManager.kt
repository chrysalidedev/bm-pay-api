package com.buildmain.services

import com.buildmain.models.DonationSuccessEvent
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestionnaire temps réel des flux d'offrandes par paroisse (WebSockets).
 * Permet aux écrans de culte (Live Giving Screen) et aux dashboards des trésoriers
 * de recevoir instantanément chaque confirmation de don dès réception du webhook Jèko.
 */
object LiveGivingManager {
    private val logger = LoggerFactory.getLogger(LiveGivingManager::class.java)

    // Map: churchId -> Ensemble thread-safe des sessions WebSocket connectées
    private val subscribers = ConcurrentHashMap<String, MutableSet<DefaultWebSocketSession>>()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun register(churchId: String, session: DefaultWebSocketSession) {
        val churchSessions = subscribers.computeIfAbsent(churchId) {
            Collections.synchronizedSet(LinkedHashSet())
        }
        churchSessions.add(session)
        logger.info("Nouveau client WebSocket connecté pour l'église $churchId (Total: ${churchSessions.size})")
    }

    fun unregister(churchId: String, session: DefaultWebSocketSession) {
        subscribers[churchId]?.let { set ->
            set.remove(session)
            logger.info("Déconnexion client WebSocket pour l'église $churchId (Restants: ${set.size})")
            if (set.isEmpty()) {
                subscribers.remove(churchId)
            }
        }
    }

    suspend fun broadcast(churchId: String, event: DonationSuccessEvent) {
        val sessions = subscribers[churchId] ?: return
        val message = json.encodeToString(DonationSuccessEvent.serializer(), event)
        logger.info("Diffusion WebSocket pour $churchId: ${event.amount} FCFA reçus de ${event.donorName} (${sessions.size} récepteurs)")

        val deadSessions = mutableListOf<DefaultWebSocketSession>()
        sessions.forEach { session ->
            try {
                session.send(message)
            } catch (e: Exception) {
                logger.warn("Erreur d'envoi WebSocket vers un client: ${e.message}")
                deadSessions.add(session)
            }
        }

        if (deadSessions.isNotEmpty()) {
            sessions.removeAll(deadSessions.toSet())
        }
    }
}
